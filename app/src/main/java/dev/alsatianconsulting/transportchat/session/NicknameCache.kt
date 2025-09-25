package dev.alsatianconsulting.transportchat.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ephemeral nicknames (not persisted). Keys are "host:port".
 * Single nullable setter to avoid JVM overload clashes:
 *  - set(host, port, label: String?) -> null/blank clears; non-blank sets
 *  - set(key, label: String?)        -> null/blank clears; non-blank sets
 */
object NicknameCache {
    private val _labels = MutableStateFlow<Map<String, String>>(emptyMap())
    val labels: StateFlow<Map<String, String>> = _labels

    fun key(host: String, port: Int): String = "$host:$port"

    fun get(host: String, port: Int): String? = _labels.value[key(host, port)]
    fun get(key: String): String? = _labels.value[key]

    /** Null/blank clears; otherwise sets the nickname for host:port. */
    fun set(host: String, port: Int, label: String?) {
        val k = key(host, port)
        if (label.isNullOrBlank()) {
            removeKey(k)
        } else {
            _labels.value = _labels.value.toMutableMap().apply { put(k, label) }
        }
    }

    /** Null/blank clears; otherwise sets the nickname for a prebuilt "host:port" key. */
    fun set(key: String, label: String?) {
        if (label.isNullOrBlank()) {
            removeKey(key)
        } else {
            _labels.value = _labels.value.toMutableMap().apply { put(key, label) }
        }
    }

    fun remove(host: String, port: Int) {
        removeKey(key(host, port))
    }

    private fun removeKey(key: String) {
        if (_labels.value.containsKey(key)) {
            _labels.value = _labels.value.toMutableMap().apply { remove(key) }
        }
    }

    fun clear() {
        _labels.value = emptyMap()
    }
}
