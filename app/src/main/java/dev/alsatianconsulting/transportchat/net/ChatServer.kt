package dev.alsatianconsulting.transportchat.net

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.alsatianconsulting.transportchat.crypto.ChatCrypto
import dev.alsatianconsulting.transportchat.data.ChatStore
import dev.alsatianconsulting.transportchat.data.TransferCenter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

// normalize host (strip IPv6 scope like %wlan0)
private fun normalizeHost(host: String?): String? = host?.substringBefore('%')

object ChatServer {

    private const val TAG = "ChatServer"

    private lateinit var appCtx: Context
    private var server: ServerSocket? = null
    private var serverPort: Int = 7777

    data class IncomingText(
        val id: String,
        val remoteHost: String,
        val localPort: Int,
        val text: String
    )

    data class IncomingFileOffer(
        val id: String,
        val remoteHost: String,
        val localPort: Int,
        val name: String,
        val mime: String?,
        val size: Long
    )

    private val _incomingMessages = MutableSharedFlow<IncomingText>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingMessages: SharedFlow<IncomingText> = _incomingMessages

    private val _incomingFileOffers = MutableSharedFlow<IncomingFileOffer>(
        replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingFileOffers: SharedFlow<IncomingFileOffer> = _incomingFileOffers

    private data class PendingConn(
        val socket: Socket,
        val br: BufferedReader,
        val wr: OutputStreamWriter,
        val session: ChatCrypto.Session,
        val name: String,
        val mime: String?,
        val size: Long
    )
    private val pending = ConcurrentHashMap<String, PendingConn>()

    // Active connections (in-progress receives) so we can cancel from UI
    private val active = ConcurrentHashMap<String, PendingConn>()

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(port: Int) {
        if (serverPort == port && server != null && !server!!.isClosed) return
        stop()
        serverPort = port
        scope.launch {
            try {
                server = ServerSocket(serverPort)
                Log.i(TAG, "Listening on $serverPort")
                while (!server!!.isClosed) {
                    val socket = server!!.accept()
                    handle(socket)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Server stopped: ${t.message}")
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 0
        scope.launch {
            try {
                val remoteHost = normalizeHost(socket.inetAddress?.hostAddress)
                val localPort = serverPort
                if (remoteHost == null) { runCatching { socket.close() }; return@launch }
                if (dev.alsatianconsulting.transportchat.store.PeerStore(appCtx).isBlocked(remoteHost, localPort)) {
                    Log.i(TAG, "Blocked connection from $remoteHost:$localPort — closing")
                    runCatching { socket.close() }
                    return@launch
                }

                val br = BufferedReader(InputStreamReader(socket.getInputStream()))
                val wr = OutputStreamWriter(socket.getOutputStream())

                val hello = br.readLine()
                if (hello != "HELLO") { socket.close(); return@launch }
                val peerPub = br.readLine() ?: run { socket.close(); return@launch }
                wr.write("WELCOME\n"); wr.write(ChatCrypto.publicB64 + "\n"); wr.flush()
                val session = ChatCrypto.deriveSession(peerPub)

                val tag = br.readLine() ?: run { socket.close(); return@launch }
                if (tag != "ENC") { socket.close(); return@launch }
                val b64 = br.readLine() ?: run { socket.close(); return@launch }
                val obj = JSONObject(String(ChatCrypto.decryptFromB64(session, b64)))
                when (obj.optString("type")) {
                    "TEXT" -> {
                        val id = obj.optString("id")
                        val body = obj.optString("body")
                        _incomingMessages.tryEmit(IncomingText(id, remoteHost, localPort, body))
                        // Ack delivered
                        val ack = JSONObject()
                            .put("type", "RCPT")
                            .put("kind", "DELIVERED")
                            .put("id", id)
                            .put("convPort", localPort)
                        sendEnc(wr, session, ack)
                        socket.close()
                    }
                    "RCPT" -> {
                        val kind = obj.optString("kind")
                        val id = obj.optString("id")
                        val at = obj.optLong("at", System.currentTimeMillis())

                        when (kind) {
                            "DELIVERED" -> ChatStore.markDelivered(remoteHost, serverPort, id)
                            "READ" -> {
                                ChatStore.markRead(remoteHost, serverPort, id, at)
                                // Also try other ports for same host (best-effort)
                                runCatching {
                                    dev.alsatianconsulting.transportchat.data.ChatStore
                                        .listOpenChats()
                                        .filter { (h, p) -> h == remoteHost && p != serverPort }
                                        .forEach { (_, p) -> ChatStore.markRead(remoteHost, p, id, at) }
                                }
                            }
                        }
                        socket.close()
                    }
                    "FILE_OFFER" -> {
                        val name = obj.optString("name", "file")
                        val size = obj.optLong("size", -1L)
                        val mime = obj.optString("mime").ifBlank { null }
                        val id = TransferCenter.newId()

                        if (dev.alsatianconsulting.transportchat.store.PeerStore(appCtx).isBlocked(remoteHost, localPort)) {
                            Log.i(TAG, "Blocked file offer from $remoteHost:$localPort — closing")
                            runCatching { socket.close() }
                            return@launch
                        }

                        pending[id] = PendingConn(socket, br, wr, session, name, mime, size)
                        TransferCenter.startIncoming(id, remoteHost, localPort, name, mime, size)
                        _incomingFileOffers.tryEmit(
                            IncomingFileOffer(id, remoteHost, localPort, name, mime, size)
                        )
                        // keep socket open for accept/reject
                    }
                    else -> socket.close()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Handle error: ${t.message}")
            }
        }
    }

    private fun sendEnc(wr: OutputStreamWriter, session: ChatCrypto.Session, obj: JSONObject) {
        runCatching {
            wr.write("ENC\n")
            wr.write(ChatCrypto.encryptToB64(session, obj.toString().toByteArray()))
            wr.write("\n")
            wr.flush()
        }
    }

    /** UI accepted offer; stream to the chosen location with timeouts & progress. */
    fun acceptOffer(id: String, saveUri: Uri) {
        val p = pending.remove(id) ?: return
        // track as active so the UI can cancel
        active[id] = p
        scope.launch {
            try {
                // Inform sender we accept (encrypted)
                sendEnc(p.wr, p.session, JSONObject().put("type", "FILE_REPLY").put("decision", "ACCEPT"))

                // 30s idle timeout waiting for DATA and between frames
                p.socket.soTimeout = 30_000

                // Expect "DATA"
                val first = try { p.br.readLine() } catch (t: SocketTimeoutException) { null }
                if (first == null) {
                    TransferCenter.failed(id, "Timeout waiting for data")
                    runCatching { p.socket.close() }
                    return@launch
                }
                if (first != "DATA") {
                    TransferCenter.failed(id, "Protocol error (expected DATA)")
                    runCatching { p.socket.close() }
                    return@launch
                }

                var copied = 0L
                var peerCancelled = false

                appCtx.contentResolver.openOutputStream(saveUri)?.use { out ->
                    while (true) {
                        // If local user requested cancel, stop by closing the socket
                        if (TransferCenter.isCancelled(id)) {
                            runCatching {
                                // Tell the sender we are cancelling before closing.
                                p.wr.write("CANCEL\n")
                                p.wr.flush()
                            }
                            runCatching { p.socket.close() }
                            break
                        }
                        val tag = try { p.br.readLine() } catch (t: SocketTimeoutException) { null } ?: break
                        when (tag) {
                            "CHUNK" -> {
                                val b64 = try { p.br.readLine() } catch (t: SocketTimeoutException) { null } ?: break
                                val clear = ChatCrypto.decryptFromB64(p.session, b64)
                                val bytes = if (clear is ByteArray) clear else clear.toString().toByteArray()
                                out.write(bytes)
                                copied += bytes.size
                                TransferCenter.progress(id, copied)
                            }
                            "END" -> break
                            "CANCEL" -> {
                                peerCancelled = true
                                break
                            }
                            else -> {
                                TransferCenter.failed(id, "Protocol error ($tag)")
                                runCatching { p.socket.close() }
                                return@launch
                            }
                        }
                    }
                    runCatching { out.flush() }
                }

                when {
                    TransferCenter.isCancelled(id) -> {
                        TransferCenter.cancelled(id)
                    }
                    peerCancelled -> {
                        TransferCenter.cancelled(id)
                    }
                    else -> {
                        TransferCenter.finishedIncoming(id, saveUri)
                        // Optional ack back to sender (encrypted)
                        sendEnc(p.wr, p.session, JSONObject().put("type", "FILE_ACK").put("status", "OK"))
                    }
                }
            } catch (t: Throwable) {
                if (TransferCenter.isCancelled(id)) {
                    TransferCenter.cancelled(id)
                } else {
                    TransferCenter.failed(id, t.message)
                }
            } finally {
                active.remove(id)
                runCatching { p.socket.close() }
            }
        }
    }

    /** UI rejected offer (unchanged) */
    fun rejectOffer(id: String) {
        val p = pending.remove(id) ?: return
        scope.launch {
            runCatching {
                sendEnc(p.wr, p.session, JSONObject().put("type", "FILE_REPLY").put("decision", "REJECT"))
            }
            TransferCenter.rejected(id)
            runCatching { p.socket.close() }
        }
    }

    /**
     * Cancel an in-progress incoming transfer from the receiver side.
     *
     * Surgical fix: inform the sender by writing a plain "CANCEL" line
     * before closing, so the sender can mark the transfer as CANCELLED
     * instead of treating it as a generic failure.
     */
    fun cancelTransfer(id: String) {
        // mark as cancelled for UI immediately
        TransferCenter.requestCancel(id)

        // If there is an active receive connection, notify the peer first.
        active[id]?.let { conn ->
            runCatching {
                conn.wr.write("CANCEL\n")
                conn.wr.flush()
            }.onFailure {
                Log.d(TAG, "Failed to notify sender of cancel (will close anyway): ${it.message}")
            }
        }

        // Close any active socket to interrupt readLine()
        active.remove(id)?.let { conn ->
            runCatching { conn.socket.close() }
        }
        // if it was still pending (not yet accepted), just drop it
        pending.remove(id)?.let { conn ->
            runCatching { conn.socket.close() }
        }
    }
}
