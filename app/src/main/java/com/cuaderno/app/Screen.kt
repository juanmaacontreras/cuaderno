package com.cuaderno.app

import android.app.Activity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** What every screen of the app shares. */
object Screen {
    /**
     * The whole screen is the app: no clock and icons above, no gesture line
     * below (as Samsung Notes does). A swipe from the edge brings them back
     * for a moment. Asked again on focus because a dialog or the keyboard
     * returns them.
     */
    fun fullscreen(activity: Activity) {
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Now, as the notebook file writes times. */
    fun now(): String = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
}
