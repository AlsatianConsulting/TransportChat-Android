package dev.alsatianconsulting.transportchat.net

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dev.alsatianconsulting.transportchat.crypto.ChatCrypto
import dev.alsatianconsulting.transportchat.data.ChatStore
import dev.alsatianconsulting.transportchat.data.TransferCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import java.io.File
import android.webkit.MimeTypeMap

object ChatClient {
    private const val TAG = "ChatClient"
    private const val TIMEOUT = 8000

    // ===== Handshake =====
    private data class SessionIO(
        val s: Socket,
        val wr: OutputStreamWriter,
        val br: BufferedReader,
        val ses: ChatCrypto.Session
    )

    private fun handshake(host: String, port: Int): SessionIO {
        val s = Socket()
        s.soTimeout = TIMEOUT
        s.connect(InetSocketAddress(host, port), TIMEOUT)
        val wr = OutputStreamWriter(s.getOutputStream())
        val br = BufferedReader(InputStreamReader(s.getInputStream()))

        wr.write("HELLO\n"); wr.write(ChatCrypto.publicB64 + "\n"); wr.flush()
        val welcome = br.readLine()
        require(welcome == "WELCOME") { "No welcome" }
        val peerPub = br.readLine() ?: error("No server pubkey")
        val ses = ChatCrypto.deriveSession(peerPub)
        return SessionIO(s, wr, br, ses)
    }

    private fun sendEnc(io: SessionIO, obj: JSONObject) {
        val b64 = ChatCrypto.encryptToB64(io.ses, obj.toString().toByteArray())
        io.wr.write("ENC\n")
        io.wr.write(b64 + "\n")
        io.wr.flush()
    }

    private fun recvEnc(io: SessionIO): JSONObject? {
        val tag = io.br.readLine() ?: return null
        if (tag != "ENC") return null
        val b64 = io.br.readLine() ?: return null
        val plain = ChatCrypto.decryptFromB64(io.ses, b64)
        return JSONObject(String(plain))
    }

    // ===== Text =====
    suspend fun sendSecureText(host: String, port: Int, id: String, text: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Connecting to $host:$port for TEXT ...")
        val io = handshake(host, port)
        val payload = JSONObject().put("type", "TEXT").put("id", id).put("body", text)
        sendEnc(io, payload)
        val reply = recvEnc(io)
        if (reply != null && reply.optString("type") == "RCPT" &&
            reply.optString("kind") == "DELIVERED" &&
            reply.optString("id") == id
        ) {
            ChatStore.markDelivered(host, port, id)
        }
        io.s.close()
        Log.d(TAG, "Wrote text to $host:$port; closed")
    }

    // ===== Read receipt =====
    suspend fun sendReadReceipt(host: String, port: Int, id: String, readAtMillis: Long) = withContext(Dispatchers.IO) {
        val io = handshake(host, port)
        sendEnc(io, JSONObject()
            .put("type", "RCPT")
            .put("kind", "READ")
            .put("id", id)
            .put("at", readAtMillis)
        )
        io.s.close()
    }

    // ===== File =====
    suspend fun sendFile(context: Context, host: String, port: Int, uri: Uri) = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val (name, size, mime) = queryFileMeta(cr, uri)

        val id = TransferCenter.newId()
        TransferCenter.startOutgoing(id, host, port, name ?: "file", mime, size ?: -1L)

        Log.d(TAG, "Connecting to $host:$port for FILE ...")
        val io = handshake(host, port)

        // Offer
        sendEnc(io, JSONObject()
            .put("type", "FILE_OFFER")
            .put("name", name ?: "file")
            .put("size", max(size ?: -1L, -1L))
            .put("mime", mime ?: "")
        )

        val reply = recvEnc(io)
        if (reply == null || reply.optString("type") != "FILE_REPLY") {
            TransferCenter.failed(id, "No reply")
            io.s.close(); return@withContext
        }
        if (reply.optString("decision") != "ACCEPT") {
            TransferCenter.rejected(id)
            io.s.close(); return@withContext
        }

        // Stream encrypted chunks
        io.wr.write("DATA\n"); io.wr.flush()
        io.s.soTimeout = 30_000 // allow cancel/idle detection while uploading

        // Background reader to detect receiver CANCEL immediately
        val peerCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val readerJob = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val tag = try { io.br.readLine() } catch (_: java.net.SocketTimeoutException) { continue } ?: break
                    when (tag) {
                        "CANCEL" -> { peerCancelled.set(true); break }
                        "ENC" -> {
                            val b64 = io.br.readLine() ?: break
                            runCatching { ChatCrypto.decryptFromB64(io.ses, b64) }
                        }
                        else -> { /* ignore */ }
                    }
                }
            } catch (_: Throwable) { /* socket closed or benign */ }
        }

        try {
            var sent = 0L
            val buf = ByteArray(128 * 1024)
            cr.openInputStream(uri)?.use { input ->
                while (true) {
                    if (dev.alsatianconsulting.transportchat.data.TransferCenter.isCancelled(id) || peerCancelled.get()) {
                        runCatching { io.wr.write("CANCEL\n"); io.wr.flush() }
                        TransferCenter.cancelled(id)
                        runCatching { io.s.close() }
                        return@withContext
                    }
                    val n = input.read(buf)
                    if (n <= 0) break
                    val chunk = buf.copyOf(n)
                    val b64 = ChatCrypto.encryptToB64(io.ses, chunk)
                    try {
                        io.wr.write("CHUNK\n")
                        io.wr.write(b64 + "\n")
                        io.wr.flush()
                    } catch (t: Throwable) {
                        if (peerCancelled.get() || dev.alsatianconsulting.transportchat.data.TransferCenter.isCancelled(id)) {
                            TransferCenter.cancelled(id)
                            runCatching { io.s.close() }
                            return@withContext
                        } else {
                            throw t
                        }
                    }
                    sent += n
                    TransferCenter.progress(id, sent)

                    if (dev.alsatianconsulting.transportchat.data.TransferCenter.isCancelled(id) || peerCancelled.get()) {
                        runCatching { io.wr.write("CANCEL\n"); io.wr.flush() }
                        TransferCenter.cancelled(id)
                        runCatching { io.s.close() }
                        return@withContext
                    }
                }
            }

            if (peerCancelled.get()) {
                TransferCenter.cancelled(id)
                runCatching { io.s.close() }
                return@withContext
            }

            io.wr.write("END\n"); io.wr.flush()
            io.s.close()
            TransferCenter.finishedOutgoing(id)
            Log.d(TAG, "File sent and socket closed")
        } catch (t: Throwable) {
            if (peerCancelled.get() || dev.alsatianconsulting.transportchat.data.TransferCenter.isCancelled(id)) {
                TransferCenter.cancelled(id)
            } else {
                TransferCenter.failed(id, t.message ?: "send error")
            }
        } finally {
            readerJob.cancel()
            runCatching { io.s.close() }
        }
    }

    private fun queryFileMeta(cr: ContentResolver, uri: Uri): Triple<String?, Long?, String?> {
        var name: String? = null
        var size: Long? = null
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        var mime: String? = cr.getType(uri)

        // Fallbacks for file:// URIs (Sharesheet cache etc.) — avoid lambdas to prevent smart-cast issues
        if ((size ?: 0L) <= 0L && "file".equals(uri.scheme, ignoreCase = true)) {
            val path = uri.path
            if (path != null) {
                val f = File(path)
                if (f.exists()) {
                    size = f.length()
                }
                if (mime.isNullOrBlank()) {
                    val ext = f.extension.takeIf { it.isNotBlank() } ?: name?.substringAfterLast('.', "")
                    if (!ext.isNullOrBlank()) {
                        mime = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(ext.lowercase())
                    }
                }
            }
        }

        if (name == null) {
            name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        }
        return Triple(name, size, mime)
    }
}
