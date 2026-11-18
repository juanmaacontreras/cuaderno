package com.cuaderno.app

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.juanma.kit.Ui
import cuaderno.core.Document
import cuaderno.core.Notebook
import cuaderno.core.NotebookFile
import cuaderno.core.Paper
import cuaderno.core.StrokeBuilder
import cuaderno.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The pages panel: see the notebook's pages, go to one, add, copy, re-paper and reorder them, and it is all saved. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-mdpi")
class PagesPanelTest {
    private val file: File get() = File(RuntimeEnvironment.getApplication().filesDir, "notebooks/cuaderno.cuaderno")

    @Before fun noFrontBuffer() { WriteActivity.lowLatency = false }

    /** Three written pages, told apart by the height of their one line (100, 200, 300), and the blank one. */
    private fun open(): WriteActivity {
        file.parentFile!!.mkdirs()
        val doc = Document(Notebook("nb", "Cuaderno", "2026-11-17T10:00:00-03:00", "2026-11-17T10:00:00-03:00", emptyList()))
        for ((i, y) in listOf(100f, 200f, 300f).withIndex()) {
            doc.add(i, StrokeBuilder(Tool.PEN, Ui.BG, 1.1f).apply { add(100f, y, 0.5f, 0f); add(300f, y, 0.5f, 10f) }.build())
        }
        NotebookFile.write(file, doc.notebook("2026-11-17T10:00:00-03:00"))
        val intent = Intent(RuntimeEnvironment.getApplication(), WriteActivity::class.java).putExtra(WriteActivity.NOTEBOOK, file.path)
        return Robolectric.buildActivity(WriteActivity::class.java, intent).setup().get()
    }

    private fun lines(pages: List<cuaderno.core.Page>) = pages.map { p -> p.strokes.firstOrNull()?.y(0)?.toInt() ?: 0 }

    /** What is on disk once the screen is left. */
    private fun saved(screen: WriteActivity): List<Int> {
        screen.store().saveNow(screen.document)
        return lines(NotebookFile.read(file).pages)
    }

    private fun WriteActivity.store(): Store =
        WriteActivity::class.java.getDeclaredField("store").also { it.isAccessible = true }.get(this) as Store

    private fun items(screen: WriteActivity): List<View> {
        val scroll = screen.pages.view.getChildAt(1) as ViewGroup
        val list = scroll.getChildAt(0) as ViewGroup
        return (0 until list.childCount).map { list.getChildAt(it) }
    }

    private fun find(view: View, text: String): View? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), text)?.let { return it }
        return null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun openPanel(screen: WriteActivity) {
        screen.bar.getChildAt(1).performClick()
        idle()
    }

    @Test fun theBarOpensThePagesAndATapGoesToOne() {
        val screen = open()
        assertFalse(screen.pages.open)
        openPanel(screen)
        assertTrue(screen.pages.open)
        // The written pages; the blank one waiting at the end is not listed.
        assertEquals("Páginas · 3", (screen.pages.view.getChildAt(0) as TextView).text.toString())
        assertEquals(3, items(screen).size)

        screen.pages.awaitThumbnails(); idle()
        val picture = (items(screen)[2] as ViewGroup).getChildAt(0) as ImageView
        assertTrue("the thumbnail is drawn", picture.drawable is BitmapDrawable)

        assertEquals(0, screen.page.currentPage)
        items(screen)[2].performClick()
        assertEquals(2, screen.page.currentPage)

        // The same button, or back, puts it away.
        @Suppress("DEPRECATION") screen.onBackPressed()
        assertFalse(screen.pages.open)
        assertFalse(screen.isFinishing)
    }

    @Test fun holdingAPageAddsOneAfterItCopiesItOrChangesItsPaper() {
        val screen = open()
        openPanel(screen)

        items(screen)[0].performLongClick()
        assertEquals(View.VISIBLE, screen.pages.menuLayer.visibility)
        find(screen.pages.menuLayer, Strings.NEW_PAGE_AFTER)!!.let { (it.parent as View).performClick() }
        assertEquals(View.GONE, screen.pages.menuLayer.visibility)
        assertEquals(listOf(100, 0, 200, 300, 0), saved(screen))
        assertEquals(4, items(screen).size)

        items(screen)[2].performLongClick()
        find(screen.pages.menuLayer, Strings.DUPLICATE)!!.let { (it.parent as View).performClick() }
        assertEquals(listOf(100, 0, 200, 200, 300, 0), saved(screen))

        items(screen)[4].performLongClick()
        find(screen.pages.menuLayer, Strings.paperIs("cuadriculado"))!!.let { (it.parent as View).performClick() }
        find(screen.pages.menuLayer, Strings.RULED)!!.let { (it.parent as View).performClick() }
        saved(screen)
        assertEquals(Paper.RULED, NotebookFile.read(file).pages[4].paper)
        assertEquals(Paper.GRID, NotebookFile.read(file).pages[3].paper)

        // Each is one undo.
        screen.document.undo(); screen.document.undo(); screen.document.undo()
        assertEquals(listOf(100, 200, 300, 0), lines(screen.document.pages))
    }

    @Test fun holdingAPageAndDraggingItMovesIt() {
        val screen = open()
        openPanel(screen)
        val first = items(screen)[0]
        val step = first.height.toFloat()
        assertTrue(step > 0)
        val t = SystemClock.uptimeMillis()
        fun touch(action: Int, y: Float) = first.dispatchTouchEvent(MotionEvent.obtain(t, t, action, 50f, y, 0))
        touch(MotionEvent.ACTION_DOWN, 20f)
        first.performLongClick()
        touch(MotionEvent.ACTION_MOVE, 20f + step)
        touch(MotionEvent.ACTION_MOVE, 20f + 2 * step)
        assertEquals(View.GONE, screen.pages.menuLayer.visibility)
        touch(MotionEvent.ACTION_UP, 20f + 2 * step)
        // The first page lands third.
        assertEquals(listOf(200, 300, 100, 0), saved(screen))
        assertEquals(3, items(screen).size)
    }
}
