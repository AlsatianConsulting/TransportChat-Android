package dev.alsatianconsulting.transportchat.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.alsatianconsulting.transportchat.PeerChatActivity
import dev.alsatianconsulting.transportchat.net.ChatServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import kotlin.math.abs

object IncomingDispatcher {

    private const val CHANNEL_ID = "tc_incoming"
    private const val CHANNEL_NAME = "Incoming messages"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    private lateinit var appCtx: Context

    fun start(context: Context) {
        if (started) return
        started = true
        appCtx = context.applicationContext
        ensureChannel()

        scope.launch {
            ChatServer.incomingMessages.collect { msg ->
                ChatStore.appendIncoming(msg.remoteHost, msg.localPort, msg.text, id = msg.id)
                if (!ActiveChat.isActive(msg.remoteHost, msg.localPort)) {
                    bumpUnreadSafe(msg.remoteHost, msg.localPort)
                    notifySanitized(
                        host = msg.remoteHost,
                        port = msg.localPort,
                        label = resolvePeerLabel(msg.remoteHost, msg.localPort),
                        isFile = false
                    )
                }
            }
        }

        scope.launch {
            ChatServer.incomingFileOffers.collect { offer ->
                if (!ActiveChat.isActive(offer.remoteHost, offer.localPort)) {
                    bumpUnreadSafe(offer.remoteHost, offer.localPort)
                    notifySanitized(
                        host = offer.remoteHost,
                        port = offer.localPort,
                        label = resolvePeerLabel(offer.remoteHost, offer.localPort),
                        isFile = true
                    )
                }
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifications for incoming LAN-only chat messages"
                enableLights(true)
                enableVibration(true)
                lightColor = Color.MAGENTA
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun notifySanitized(host: String, port: Int, label: String, isFile: Boolean) {
        val intent = Intent(appCtx, PeerChatActivity::class.java)
            .putExtra(PeerChatActivity.EXTRA_HOST, host)
            .putExtra(PeerChatActivity.EXTRA_PORT, port)
            .putExtra(PeerChatActivity.EXTRA_NAME, label)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getActivity(appCtx, abs(host.hashCode() xor port), intent, flags)

        val notif = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (isFile) "File from $label" else "Message from $label")
            .setContentText("Open to view")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(appCtx).notify(notifId(host, port, isFile), notif)
    }

    private fun notifId(host: String, port: Int, isFile: Boolean): Int {
        val ns = if (isFile) 0x6EC0F000.toInt() else 0x6EC00000.toInt()
        return ns or (host.hashCode() xor port)
    }

    private fun resolvePeerLabel(host: String, port: Int): String = "$host:$port"

    private fun bumpUnreadSafe(host: String, port: Int) {
        try {
            val cls = Class.forName("dev.alsatianconsulting.transportchat.data.UnreadCenter")
            val inst = cls.kotlin.objectInstance ?: cls.getDeclaredField("INSTANCE").get(null)
            val candidates = arrayOf("bump", "inc", "increment", "add")
            var called = false
            for (name in candidates) {
                val m: Method? = runCatching { cls.getMethod(name, String::class.java, Int::class.javaPrimitiveType) }.getOrNull()
                if (m != null) { m.invoke(inst, host, port); called = true; break }
            }
            if (!called) {
                val m: Method? = runCatching { cls.getMethod("bump", String::class.java) }.getOrNull()
                if (m != null) m.invoke(inst, "$host:$port")
            }
        } catch (_: Throwable) {
            // ignore
        }
    }
}
