package dev.alsatianconsulting.transportchat.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks whether the app is in foreground (any Activity started) or background.
 * Register once from MainActivity.onCreate: AppVisibility.register(application)
 */
object AppVisibility : Application.ActivityLifecycleCallbacks {

    private val registered = AtomicBoolean(false)
    private var startedCount = 0

    private val _isForeground = MutableStateFlow(true)
    val isForegroundFlow: StateFlow<Boolean> = _isForeground
    fun isForeground(): Boolean = _isForeground.value

    fun register(app: Application) {
        if (registered.compareAndSet(false, true)) {
            app.registerActivityLifecycleCallbacks(this)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        startedCount += 1
        _isForeground.value = startedCount > 0
    }
    override fun onActivityStopped(activity: Activity) {
        startedCount = (startedCount - 1).coerceAtLeast(0)
        _isForeground.value = startedCount > 0
    }

    // No-ops
    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityResumed(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}
