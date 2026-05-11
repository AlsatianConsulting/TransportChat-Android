package dev.alsatianconsulting.transportchat.transport.messaging

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket

class LanMessageServer(
    private val port: Int,
    private val onInboundEnvelope: (InboundEnvelope, String) -> Unit,
    private val onProfileRequest: () -> EndpointPeerProfile?
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var serverJob: Job? = null
    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch {
            runCatching {
                ServerSocket(port).use { socket ->
                    serverSocket = socket
                    while (isActive) {
                        val inboundSocket = socket.accept()
                        inboundSocket.use {
                            val input = DataInputStream(inboundSocket.getInputStream())
                            val length = input.readInt()
                            val payload = ByteArray(length)
                            input.readFully(payload)
                            if (PeerProfileProtocol.decodeRequest(payload)) {
                                val profile = onProfileRequest() ?: return@use
                                val responsePayload = PeerProfileProtocol.encodeResponse(profile)
                                DataOutputStream(inboundSocket.getOutputStream()).use { output ->
                                    output.writeInt(responsePayload.size)
                                    output.write(responsePayload)
                                    output.flush()
                                }
                                return@use
                            }
                            val envelope = LanMessageProtocol.decode(payload) ?: return@use
                            onInboundEnvelope(envelope, inboundSocket.inetAddress.hostAddress ?: "unknown")
                        }
                    }
                }
            }.onFailure {
                if (it !is IOException) {
                    Log.e(TAG, "Message server failed", it)
                }
            }.also {
                serverSocket = null
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob = null
    }

    companion object {
        private const val TAG = "LanMessageServer"
    }
}
