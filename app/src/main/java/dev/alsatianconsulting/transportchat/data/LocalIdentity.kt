package dev.alsatianconsulting.transportchat.data

import android.content.Context
import android.os.Build

/**
 * Central place to resolve the user's display name.
 * We try (in order):
 *  1) AppSettings (via reflection, so we don't add a hard dependency on a particular API shape)
 *  2) SharedPreferences keys commonly used for display names
 *  3) Device model
 *  4) "Me"
 *
 * If your Settings screen stores a name, ensure it writes to one of the keys below
 * (e.g., "display_name"). Then this resolver (and NSD registration) will pick it up.
 */
object LocalIdentity {

    /** Resolve the best current display name for this device. */
    fun resolveDisplayName(context: Context): String {
        // 1) Try AppSettings via reflection (supports property or getter method)
        runCatching {
            val cls = Class.forName("dev.alsatianconsulting.transportchat.data.AppSettings")
            val instance = cls.kotlin.objectInstance
                ?: cls.fields.firstOrNull { it.name.equals("INSTANCE", ignoreCase = true) }?.get(null)

            // Fields that look like display name
            (cls.declaredFields + (instance?.javaClass?.declaredFields ?: emptyArray()))
                .firstOrNull { f ->
                    f.type == String::class.java &&
                            f.name.contains("display", ignoreCase = true) &&
                            f.name.contains("name", ignoreCase = true)
                }?.apply { isAccessible = true }?.get(instance)?.let { v ->
                    if (v is String && v.isNotBlank()) return v
                }

            // Methods that look like a display name getter
            (cls.methods + (instance?.javaClass?.methods ?: emptyArray()))
                .firstOrNull { m ->
                    m.parameterCount == 0 &&
                            m.returnType == String::class.java &&
                            m.name.contains("display", ignoreCase = true) &&
                            m.name.contains("name", ignoreCase = true)
                }?.invoke(instance)?.let { v ->
                    if (v is String && v.isNotBlank()) return v
                }
        }

        // 2) SharedPreferences fallbacks (adjust names if your app uses different ones)
        val prefNames = arrayOf(
            "app_settings", "settings",
            "transport_chat", "transportchat",
            "transport_chat_settings"
        )
        val keys = arrayOf("display_name", "user_display_name", "username", "name")
        for (pn in prefNames) {
            val sp = context.getSharedPreferences(pn, Context.MODE_PRIVATE)
            for (k in keys) {
                val v = sp.getString(k, null)
                if (!v.isNullOrBlank()) return v
            }
        }

        // 3) Last resorts
        Build.MODEL?.let { if (it.isNotBlank()) return it }
        return "Me"
    }
}
