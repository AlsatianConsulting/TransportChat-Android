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

object NotificationHelper {
    private const val CHANNEL_ID = "chat"
    private const val CHANNEL_NAME = "Chat Messages"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    enableLights(true)
                    lightColor = Color.WHITE
                    enableVibration(true)
                    description = "LAN chat message notifications"
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun notifyChat(ctx: Context, host: String, port: Int, preview: String, displayName: String? = null) {

        ensureChannel(ctx)
        val nm = NotificationManagerCompat.from(ctx)

        val intent = Intent(ctx, PeerChatActivity::class.java).apply {
            putExtra(PeerChatActivity.EXTRA_HOST, host)
            putExtra(PeerChatActivity.EXTRA_PORT, port)
            putExtra(PeerChatActivity.EXTRA_NAME, "$host:$port")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            ctx,
            (host + port).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("New message from " + (displayName ?: "$host:$port"))
            .setContentText("")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify((host + port).hashCode(), notif)
    }
}
