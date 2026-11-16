package com.cuaderno.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.cuaderno.app.ink.OwnSurface
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

    /** The bench the way the tablet shows it, lying down, with the synthetic recording written on it. */
    @Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-xhdpi")
    @Test fun benchOnTheTablet() {
        assumeTrue(out.isNotEmpty())
        val activity = Robolectric.buildActivity(BenchActivity::class.java).setup().get()
        val root = activity.window.decorView
        layout(root, 2304, 1440)
        Replay.into(activity.surface as OwnSurface, Replay.load("synthetic.csv"))
        save(root, 2304, 1440, "1-banco-tablet.png")
    }

    @Config(sdk = [34], qualifiers = "w393dp-h851dp-xxhdpi")
    @Test fun benchOnThePhone() {
        assumeTrue(out.isNotEmpty())
        val activity = Robolectric.buildActivity(BenchActivity::class.java).setup().get()
        val root = activity.window.decorView
        layout(root, 1080, 2340)
        Replay.into(activity.surface as OwnSurface, Replay.load("synthetic.csv"), scale = 0.45f)
        save(root, 1080, 2340, "2-banco-celular.png")
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun save(view: View, width: Int, height: Int, name: String) {
        layout(view, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out).mkdirs()
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
