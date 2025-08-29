package dev.alsatianconsulting.transportchat.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object NicknameCache {
    private fun key(host: String, port: Int) = "$host:$port"

    private val _labels = MutableStateFlow<Map<String, String>>(emptyMap())
    val labels: StateFlow<Map<String, String>> get() = _labels

    fun get(host: String, port: Int): String? = _labels.value[key(host, port)]

    /** Set label; pass null/blank to clear. Not persisted. */
    fun set(host: String, port: Int, label: String?) {
        val k = key(host, port)
        val current = _labels.value.toMutableMap()
        val value = label?.trim().orEmpty()
        if (value.isBlank()) current.remove(k) else current[k] = value
        _labels.value = current
    }
}
