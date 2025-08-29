package dev.alsatianconsulting.transportchat.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ChatLine(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val outgoing: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val delivered: Boolean = false,
    val readAt: Long? = null
)

/** In-memory (process-lifetime) message store; nothing is written to disk. */
object ChatStore {
    private val flows = ConcurrentHashMap<String, MutableStateFlow<List<ChatLine>>>()

    private fun key(host: String, port: Int) = "$host:$port"

    private fun stream(k: String): MutableStateFlow<List<ChatLine>> =
        flows.getOrPut(k) { MutableStateFlow(emptyList()) }

    fun messagesFlow(host: String, port: Int): StateFlow<List<ChatLine>> =
        stream(key(host, port))

    fun appendIncoming(host: String, port: Int, text: String, id: String = UUID.randomUUID().toString()) {
        val k = key(host, port)
        stream(k).update { it + ChatLine(id = id, text = text, outgoing = false) }
    }

    fun appendOutgoing(host: String, port: Int, text: String, id: String = UUID.randomUUID().toString()) {
        val k = key(host, port)
        stream(k).update { it + ChatLine(id = id, text = text, outgoing = true) }
    }

    fun markDelivered(host: String, port: Int, id: String) {
        val k = key(host, port)
        stream(k).update { list ->
            list.map { if (it.id == id) it.copy(delivered = true) else it }
        }
    }

    fun markRead(host: String, port: Int, id: String, atMillis: Long = System.currentTimeMillis()) {
        val k = key(host, port)
        stream(k).update { list ->
            list.map { if (it.id == id) it.copy(readAt = atMillis, delivered = true) else it }
        }
    }

    /** Convenience for marking all incoming as read (used when opening a chat). */
    fun markAllIncomingRead(host: String, port: Int, atMillis: Long = System.currentTimeMillis()): List<String> {
        val k = key(host, port)
        val ids = mutableListOf<String>()
        stream(k).update { list ->
            list.map {
                if (!it.outgoing && it.readAt == null) {
                    ids += it.id
                    it.copy(readAt = atMillis)
                } else it
            }
        }
        return ids
    }
}
