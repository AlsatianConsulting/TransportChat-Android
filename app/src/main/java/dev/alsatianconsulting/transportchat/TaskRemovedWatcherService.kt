package dev.alsatianconsulting.transportchat

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.alsatianconsulting.transportchat.data.ChatStore

/**
 * Ensures chats are wiped *when the task is actually removed* (not on simple backgrounding).
 * Start this once (e.g., in MainActivity.onCreate) with startService(Intent(this, TaskRemovedWatcherService::class.java))
 */
class TaskRemovedWatcherService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sticky so system can recreate it to catch onTaskRemoved.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Wipe chats exactly when user removes the task from Recents.
        ChatStore.clearAll()
        stopSelf()
    }
}
