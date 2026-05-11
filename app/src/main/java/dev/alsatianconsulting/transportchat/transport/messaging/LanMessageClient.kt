package dev.alsatianconsulting.transportchat.transport.messaging

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class LanMessageClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outboundQueue = Channel<OutboundPacket>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (packet in outboundQueue) {
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(packet.host, packet.port), CONNECT_TIMEOUT_MS)
                        DataOutputStream(socket.getOutputStream()).use { output ->
                            output.writeInt(packet.payload.size)
                            output.write(packet.payload)
                            output.flush()
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Message send failed to ${packet.host}:${packet.port}", it)
                }
            }
        }
    }

    fun send(host: String, port: Int, payload: ByteArray) {
        val result = outboundQueue.trySend(
            OutboundPacket(host = host, port = port, payload = payload.copyOf())
        )
        if (result.isFailure) {
            Log.w(
                TAG,
                "Dropping outbound message for $host:$port because the queue is unavailable",
                result.exceptionOrNull()
            )
        }
    }

    fun requestPeerProfile(host: String, port: Int, connectTimeoutMs: Int = CONNECT_TIMEOUT_MS): EndpointPeerProfile? {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = READ_TIMEOUT_MS
                val requestPayload = PeerProfileProtocol.encodeRequest()

                DataOutputStream(socket.getOutputStream()).use { output ->
                    output.writeInt(requestPayload.size)
                    output.write(requestPayload)
                    output.flush()
                }

                DataInputStream(socket.getInputStream()).use { input ->
                    val length = input.readInt()
                    if (length <= 0 || length > MAX_PROFILE_PACKET_BYTES) return null
                    val payload = ByteArray(length)
                    input.readFully(payload)
                    PeerProfileProtocol.decodeResponse(payload)
                }
            }
        }.onFailure {
            Log.w(TAG, "Profile request failed for $host:$port", it)
        }.getOrNull()
    }

    private data class OutboundPacket(
        val host: String,
        val port: Int,
        val payload: ByteArray
    )

    companion object {
        private const val TAG = "LanMessageClient"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_PROFILE_PACKET_BYTES = 16 * 1024
    }
}
