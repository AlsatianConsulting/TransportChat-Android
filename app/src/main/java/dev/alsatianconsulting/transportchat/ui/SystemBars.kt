package dev.alsatianconsulting.transportchat.ui

import android.app.Activity
import android.graphics.Color
import androidx.core.view.WindowCompat

fun Activity.forceBlackSystemBars() {
    // Solid black bars
    window.statusBarColor = Color.BLACK
    window.navigationBarColor = Color.BLACK

    // Ensure icons are light (not dark) on black background
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = false
    controller.isAppearanceLightNavigationBars = false
}
