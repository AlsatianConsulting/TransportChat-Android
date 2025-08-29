package dev.alsatianconsulting.transportchat.data

import java.util.concurrent.atomic.AtomicReference

object ActiveChat {
    private val active = AtomicReference<Pair<String, Int>?>(null)

    fun setActive(host: String, port: Int) {
        active.set(host to port)
    }

    fun clearActive() {
        active.set(null)
    }

    fun isActive(host: String, port: Int): Boolean {
        val cur = active.get()
        return cur != null && cur.first == host && cur.second == port
    }
}
