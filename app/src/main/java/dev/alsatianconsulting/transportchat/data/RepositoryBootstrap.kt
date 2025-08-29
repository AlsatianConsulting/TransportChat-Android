package dev.alsatianconsulting.transportchat.data

import android.content.Context
import android.util.Log

/**
 * Initializes ChatRepository safely, regardless of its public API.
 * Tries common init method names or, as a last resort, sets an appContext field via reflection.
 */
object RepositoryBootstrap {
    private const val TAG = "RepoBootstrap"
    private const val REPO_CLASS = "dev.alsatianconsulting.transportchat.data.ChatRepository"

    fun ensureInitialized(appCtx: Context) {
        runCatching {
            val cls = Class.forName(REPO_CLASS)
            val instance = runCatching { cls.getField("INSTANCE").get(null) }.getOrNull() ?: return

            // Try common initializer method names
            val candidates = listOf("init", "initialize", "setAppContext")
            for (name in candidates) {
                val method = cls.methods.firstOrNull { m ->
                    m.name == name && m.parameterTypes.size == 1 &&
                            Context::class.java.isAssignableFrom(m.parameterTypes[0])
                }
                if (method != null) {
                    method.invoke(instance, appCtx)
                    Log.d(TAG, "Initialized ChatRepository via .$name(Context)")
                    return
                }
            }

            // Fallback: set a lateinit/appContext field directly if it exists
            runCatching {
                val f = cls.getDeclaredField("appContext")
                f.isAccessible = true
                f.set(instance, appCtx)
                Log.d(TAG, "Initialized ChatRepository via appContext field")
            }.onFailure { e ->
                Log.w(TAG, "No compatible init method/field found on ChatRepository", e)
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to initialize ChatRepository (class not found or reflection error)", e)
        }
    }
}
