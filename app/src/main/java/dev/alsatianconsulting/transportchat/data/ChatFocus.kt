package dev.alsatianconsulting.transportchat.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks which peer chat is currently visible.
 * Stores a SET of normalized host aliases (IPv4/IPv6, no scope ids).
 */
object ChatFocus {
    private val _activeHosts = MutableStateFlow<Set<String>>(emptySet())
    val activeHosts: StateFlow<Set<String>> = _activeHosts

    fun setActiveHosts(hosts: Set<String>) {
        _activeHosts.value = hosts.map(::stripScope).toSet()
    }

    fun clear() {
        _activeHosts.value = emptySet()
    }

    fun isActiveHost(host: String): Boolean {
        return _activeHosts.value.contains(stripScope(host))
    }

    private fun stripScope(h: String): String = h.substringBefore('%')
}
