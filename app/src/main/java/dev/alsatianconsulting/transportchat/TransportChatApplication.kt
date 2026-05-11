package dev.alsatianconsulting.transportchat

import android.app.Application
import dev.alsatianconsulting.transportchat.core.AppContainer

class TransportChatApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        appContainer.ensureIdentity()
    }
}
