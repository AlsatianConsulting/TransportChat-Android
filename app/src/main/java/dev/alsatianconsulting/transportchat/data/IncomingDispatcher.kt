package dev.alsatianconsulting.transportchat.data

import android.content.Context
import android.util.Log
import dev.alsatianconsulting.transportchat.net.ChatClient
import dev.alsatianconsulting.transportchat.net.ChatServer
import dev.alsatianconsulting.transportchat.store.PeerStore
import kotlinx.coroutines.*

/**
 * Background glue:
 * - listens to ChatServer.incomingMessages
 * - updates ChatStore (in-memory backlog)
 * - posts unread + notifications if that chat isn't active
 * - if chat is active, marks as read and sends READ receipt back
 */
object IncomingDispatcher {
    private const val TAG = "IncomingDispatcher"
    private var scope: CoroutineScope? = null
    private lateinit var appCtx: Context

    fun start(appContext: Context) {
        if (scope != null) return
        appCtx = appContext.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { sc ->
            sc.launch {
                ChatServer.incomingMessages.collect { inc ->
                    Log.d(TAG, "Incoming ${inc.remoteHost}:${inc.localPort} id=${inc.id} '${inc.text.take(64)}'")

                    // Drop blocked peers entirely
                    if (PeerStore(appCtx).isBlocked(inc.remoteHost, inc.localPort)) {
                        Log.d(TAG, "Dropped blocked message from ${inc.remoteHost}:${inc.localPort}")
                        return@collect
                    }

                    // Drop blocked peers entirely — no store, no notification
                    if (PeerStore(appCtx).isBlocked(inc.remoteHost, inc.localPort)) {
                        Log.d(TAG, "Dropped blocked message from ${inc.remoteHost}:${inc.localPort}")
                        return@collect
                    }

                    ChatStore.appendIncoming(inc.remoteHost, inc.localPort, inc.text, id = inc.id)

                    val isActive = ActiveChat.isActive(inc.remoteHost, inc.localPort)
                    if (isActive) {
                        // Mark read & send receipt immediately
                        ChatStore.markRead(inc.remoteHost, inc.localPort, inc.id)
                        val at = System.currentTimeMillis()
                        launch { ChatClient.sendReadReceipt(inc.remoteHost, inc.localPort, inc.id, at) }
                    } else {
                        UnreadCenter.incrementFor(inc.remoteHost, inc.localPort)
                        val nick = PeerStore(appCtx).getNickname(inc.remoteHost, inc.localPort)
                        NotificationHelper.notifyChat(appCtx, inc.remoteHost, inc.localPort, "", nick)

                    }
                }
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
    }
}
