plugins {
    id("com.android.application") version "8.12.3" apply false
    id("com.android.library") version "8.12.3" apply false // only if already present
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    // if you already use the Kotlin Compose plugin, align it too:
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}