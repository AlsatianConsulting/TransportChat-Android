package dev.alsatianconsulting.transportchat.data

import android.util.Log
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

object ChatStore {

    private val backlogs = ConcurrentHashMap<String, MutableStateFlow<List<ChatLine>>>()

    private fun key(host: String, port: Int): String = "$host:$port"

    fun stream(key: String): MutableStateFlow<List<ChatLine>> =
        backlogs.getOrPut(key) { MutableStateFlow(emptyList()) }

    fun messagesFlow(host: String, port: Int): StateFlow<List<ChatLine>> = stream(key(host, port))

    fun appendIncoming(host: String, port: Int, text: String, ts: Long = System.currentTimeMillis(), id: String = UUID.randomUUID().toString()) {
        stream(key(host, port)).update { it + ChatLine(id = id, text = text, outgoing = false, timestamp = ts) }
    }

    fun appendOutgoing(host: String, port: Int, text: String, ts: Long = System.currentTimeMillis(), id: String = UUID.randomUUID().toString()) {
        stream(key(host, port)).update { it + ChatLine(id = id, text = text, outgoing = true, timestamp = ts) }
    }

    fun markDelivered(host: String, port: Int, id: String) {
        val k = key(host, port)
        stream(k).update { list -> list.map { if (it.id == id) it.copy(delivered = true) else it } }
    }

    fun markRead(host: String, port: Int, id: String, atMillis: Long = System.currentTimeMillis()) {
        val k = key(host, port)
        stream(k).update { list -> list.map { if (it.id == id) it.copy(readAt = atMillis) else it } }
        Log.d("ChatStore", "markRead host=$host port=$port id=$id")
    }

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

    fun clearChat(host: String, port: Int) {
        stream(key(host, port)).value = emptyList()
    }

    /** Wipe all chats (used by TaskRemovedWatcherService). */
    fun clearAll() {
        // Empty each existing flow so any collectors see the reset,
        // then clear the map to free memory.
        backlogs.values.forEach { flow -> flow.value = emptyList() }
        backlogs.clear()
    }

    /** For share target: list known chats by recent activity (best-effort). */
    fun listOpenChats(): List<Pair<String, Int>> {
        return backlogs.keys
            .mapNotNull {
                val h = it.substringBefore(':', missingDelimiterValue = "")
                val p = it.substringAfter(':', missingDelimiterValue = "").toIntOrNull()
                if (h.isNotEmpty() && p != null) h to p else null
            }
            .sortedBy { (h, p) -> stream(key(h, p)).value.lastOrNull()?.timestamp ?: 0L }
            .reversed()
    }
}
