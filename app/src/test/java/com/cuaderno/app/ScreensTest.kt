package com.cuaderno.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the real screens to PNG without a device, for design proposals and to
 * see a change before installing it. Run by tools/screens.sh; skipped otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
    private val out = System.getProperty("cuaderno.screens") ?: ""

    private fun write(width: Int, height: Int, name: String, scale: Float, dark: Boolean = false) {
        assumeTrue(out.isNotEmpty())
        WriteActivity.lowLatency = false
        val activity = Robolectric.buildActivity(WriteActivity::class.java).setup().get()
        val root = activity.window.decorView
        layout(root, width, height)
        activity.page.darkPaper = dark
        activity.page.color = com.cuaderno.app.page.Render.ink(dark)
        Replay.into(activity.page, Replay.load("synthetic.csv"), scale = scale, dx = 20f, dy = -60f * scale)
        layout(root, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(out).mkdirs()
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The tablet lying down, as he uses it. */
    @Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-xhdpi")
    @Test fun writingOnTheTablet() = write(2304, 1440, "1-escribir-tablet.png", scale = 0.9f)

    @Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-xhdpi")
    @Test fun writingOnDarkPaper() = write(2304, 1440, "3-escribir-papel-oscuro.png", scale = 0.9f, dark = true)

    @Config(sdk = [34], qualifiers = "w720dp-h1152dp-port-xhdpi")
    @Test fun writingOnTheTabletUpright() = write(1440, 2304, "2-escribir-tablet-vertical.png", scale = 0.55f)

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
}
