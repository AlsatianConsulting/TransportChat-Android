package dev.alsatianconsulting.transportchat.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

object UnreadCenter {
    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadCounts: StateFlow<Map<String, Int>> = _unread

    fun key(host: String, port: Int) = "$host:$port"

    fun incrementFor(host: String, port: Int) {
        val k = key(host, port)
        _unread.update { cur ->
            val n = (cur[k] ?: 0) + 1
            cur + (k to n)
        }
    }

    fun clearFor(host: String, port: Int) {
        val k = key(host, port)
        _unread.update { cur ->
            if (cur.containsKey(k)) cur - k else cur
        }
    }
}