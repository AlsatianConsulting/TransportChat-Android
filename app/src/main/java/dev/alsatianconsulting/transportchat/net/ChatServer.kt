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
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

// NEW: imports for blocklist checks
import dev.alsatianconsulting.transportchat.store.PeerStore
import kotlinx.coroutines.runBlocking

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

// NEW: normalize host (strip IPv6 scope like %wlan0)
private fun normalizeHost(host: String?): String? = host?.substringBefore('%')

object ChatServer {
    private const val TAG = "ChatServer"

    private lateinit var appCtx: Context
    private var serverPort: Int = 7777
    private var server: ServerSocket? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _incomingMessages = MutableSharedFlow<IncomingText>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingMessages: SharedFlow<IncomingText> = _incomingMessages

    private val _incomingFileOffers = MutableSharedFlow<IncomingFileOffer>(
        replay = 0, extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST
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

    fun init(context: Context) {
        appCtx = context.applicationContext
        // Generate our ephemeral keypair now
        ChatCrypto.publicB64 // triggers ensureKeys()
    }

    fun start(port: Int) {
        if (::appCtx.isInitialized.not()) return
        if (serverPort == port && server?.isClosed == false) return
        stop()
        serverPort = port
        thread(name = "tcp-listener-$port") {
            try {
                val srv = ServerSocket(port)
                server = srv
                Log.d(TAG, "Listening on $port")
                while (!srv.isClosed) {
                    val s = srv.accept()
                    handle(s)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Listener stopped: ${t.message}")
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    // ===== Connection handling with handshake =====
    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 0
        scope.launch {
            try {
                // NEW: EARLY BLOCK CHECK — before handshake or any read/write
                val remoteHost = normalizeHost(socket.inetAddress?.hostAddress)
                val localPort = serverPort
                if (remoteHost == null) {
                    runCatching { socket.close() }
                    return@launch
                }
                val blocked = runBlocking { PeerStore(appCtx).isBlocked(remoteHost, localPort) }
                if (blocked) {
                    Log.i(TAG, "Blocked connection from $remoteHost:$localPort — closing")
                    runCatching { socket.close() }
                    return@launch
                }

                val br = BufferedReader(InputStreamReader(socket.getInputStream()))
                val wr = OutputStreamWriter(socket.getOutputStream())

                // Handshake
                val hello = br.readLine()
                if (hello != "HELLO") { socket.close(); return@launch }
                val peerPub = br.readLine() ?: run { socket.close(); return@launch }
                wr.write("WELCOME\n"); wr.write(ChatCrypto.publicB64 + "\n"); wr.flush()
                val session = ChatCrypto.deriveSession(peerPub)

                // First encrypted command
                val tag = br.readLine() ?: run { socket.close(); return@launch }
                if (tag != "ENC") { socket.close(); return@launch }
                val b64 = br.readLine() ?: run { socket.close(); return@launch }
                val obj = JSONObject(String(ChatCrypto.decryptFromB64(session, b64)))
                when (obj.optString("type")) {
                    "TEXT" -> {
                        val id = obj.optString("id")
                        val body = obj.optString("body")
                        val host = normalizeHost(socket.inetAddress?.hostAddress) ?: "?"

                        // NEW: DEFENSIVE BLOCK CHECK — if block toggled mid-session, drop without ack
                        val stillBlocked = runBlocking { PeerStore(appCtx).isBlocked(host, serverPort) }
                        if (stillBlocked) {
                            Log.i(TAG, "Blocked mid-text from $host:$serverPort — dropping without ack")
                            runCatching { socket.close() }
                            return@launch
                        }

                        _incomingMessages.emit(IncomingText(id, host, serverPort, body))

                        // delivery ack (encrypted)
                        val ack = JSONObject().put("type", "RCPT").put("kind", "DELIVERED").put("id", id)
                        sendEnc(wr, session, ack)
                        socket.close()
                    }
                    "RCPT" -> {
                        val kind = obj.optString("kind")
                        val id = obj.optString("id")
                        val at = obj.optLong("at", System.currentTimeMillis())
                        val host = normalizeHost(socket.inetAddress?.hostAddress) ?: "?"
                        when (kind) {
                            "DELIVERED" -> ChatStore.markDelivered(host, serverPort, id)
                            "READ" -> ChatStore.markRead(host, serverPort, id, at)
                        }
                        socket.close()
                    }
                    "FILE_OFFER" -> {
                        val name = obj.optString("name", "file")
                        val size = obj.optLong("size", -1L)
                        val mime = obj.optString("mime").ifBlank { null }
                        val id = TransferCenter.newId()
                        val host = normalizeHost(socket.inetAddress?.hostAddress) ?: "?"

                        // NEW: DEFENSIVE BLOCK CHECK — don't accept offers from blocked peers
                        val stillBlocked = runBlocking { PeerStore(appCtx).isBlocked(host, serverPort) }
                        if (stillBlocked) {
                            Log.i(TAG, "Blocked file offer from $host:$serverPort — closing")
                            runCatching { socket.close() }
                            return@launch
                        }

                        TransferCenter.startIncoming(id, host, serverPort, name, mime, size)
                        pending[id] = PendingConn(socket, br, wr, session, name, mime, size)
                        _incomingFileOffers.emit(
                            IncomingFileOffer(id, host, serverPort, name, mime, size)
                        )
                        // NOTE: Do not close; waiting for accept/reject.
                    }
                    else -> socket.close()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Handle error: ${t.message}")
                runCatching { socket.close() }
            }
        }
    }

    private fun sendEnc(wr: OutputStreamWriter, session: ChatCrypto.Session, obj: JSONObject) {
        val b64 = ChatCrypto.encryptToB64(session, obj.toString().toByteArray())
        wr.write("ENC\n"); wr.write(b64 + "\n"); wr.flush()
    }

    /** UI accepted: tell sender (encrypted), then receive encrypted chunks. */
    fun acceptOffer(id: String, saveUri: Uri) {
        val p = pending.remove(id) ?: return
        scope.launch {
            try {
                sendEnc(p.wr, p.session, JSONObject().put("type", "FILE_REPLY").put("decision", "ACCEPT"))
                // Expect "DATA", then many "CHUNK\n<base64>\n", then "END"
                val first = p.br.readLine() ?: return@launch
                if (first != "DATA") return@launch

                var copied = 0L
                appCtx.contentResolver.openOutputStream(saveUri)?.use { out ->
                    while (true) {
                        val tag = p.br.readLine() ?: break
                        if (tag == "END") break
                        if (tag != "CHUNK") break
                        val b64 = p.br.readLine() ?: break
                        val plain = ChatCrypto.decryptFromB64(p.session, b64)
                        out.write(plain)
                        copied += plain.size
                        TransferCenter.progress(id, copied)
                    }
                    out.flush()
                }
                TransferCenter.finishedIncoming(id, saveUri)
            } catch (t: Throwable) {
                TransferCenter.failed(id, t.message)
            } finally {
                runCatching { p.socket.close() }
            }
        }
    }

    /** UI rejected: inform sender (encrypted) and close. */
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
}
