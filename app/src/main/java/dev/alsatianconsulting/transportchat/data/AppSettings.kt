package dev.alsatianconsulting.transportchat.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-wide settings persisted via SharedPreferences.
 * - Persists Display Name and Listen Port across app restarts.
 * - If no Display Name is found, one is GENERATED on first init and saved.
 * - Chats/messages are NOT persisted anywhere (see ChatRepository).
 */
object AppSettings {

    private const val PREFS_NAME = "transportchat_prefs"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_LISTEN_PORT = "listen_port"
    private const val DEFAULT_PORT = 7777

    private lateinit var appCtx: Context
    private val prefs by lazy { appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName

    private val _listenPort = MutableStateFlow(DEFAULT_PORT)
    val listenPort: StateFlow<Int> = _listenPort

    private val initialized = AtomicBoolean(false)

    /** Call from Application/Activity once; safe to call repeatedly. */
    fun init(context: Context) {
        if (initialized.getAndSet(true)) return
        appCtx = context.applicationContext

        // Load persisted values
        var savedName = prefs.getString(KEY_DISPLAY_NAME, null)?.trim().orEmpty()
        val savedPort = prefs.getInt(KEY_LISTEN_PORT, DEFAULT_PORT)

        // If no name saved, generate one and persist it immediately
        if (savedName.isBlank()) {
            savedName = generateDefaultName()
            prefs.edit().putString(KEY_DISPLAY_NAME, savedName).apply()
        }

        _displayName.value = savedName
        _listenPort.value = savedPort.coerceIn(1, 65535)
    }

    /** Update and persist display name. */
    fun updateDisplayName(value: String) {
        val v = value.trim()
        _displayName.value = v
        prefs.edit().putString(KEY_DISPLAY_NAME, v).apply()
    }

    /** Update and persist listening port. */
    fun updateListenPort(port: Int) {
        val p = port.coerceIn(1, 65535)
        _listenPort.value = p
        prefs.edit().putInt(KEY_LISTEN_PORT, p).apply()
    }

    /** Convenience getters. */
    val displayNameValue: String get() = _displayName.value
    val listenPortValue: Int get() = _listenPort.value

    /** Human-friendly default name, saved on first init if none exists. */
    private fun generateDefaultName(): String {
        // Stable-ish device suffix for readability + short random to reduce collisions
        val model = (Build.MODEL ?: "Device").replace(Regex("""\s+"""), "")
        val androidId = runCatching {
            Settings.Secure.getString(appCtx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        val tail = androidId.takeLast(4).ifBlank { "%04X".format(Locale.US, (System.nanoTime() and 0xFFFF).toInt()) }
        return "Transport-$model-$tail"
    }
}
