package dev.alsatianconsulting.transportchat.transport.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.alsatianconsulting.transportchat.TransportChatApplication

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val app = context.applicationContext as? TransportChatApplication ?: return
        if (app.appContainer.lockManager.isConfigured()) {
            LanTransportService.start(context)
        }
    }
}
