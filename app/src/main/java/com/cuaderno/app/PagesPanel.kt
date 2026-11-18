package com.cuaderno.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.cuaderno.app.page.PageView
import com.cuaderno.app.page.Render
import com.cuaderno.app.page.Thumbnail
import com.juanma.kit.Icons
import com.juanma.kit.Ui
import cuaderno.core.Document
import cuaderno.core.Page
import cuaderno.core.Paper
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The notebook's pages as thumbnails, over the left of the page (opening it
 * does not shrink the sheet or move the writing). Tapping one goes to it;
 * holding one offers a new page after it, a copy, or another paper; holding and
 * dragging moves it. Thumbnails are drawn off the main thread, only while the
 * panel is open, and kept for pages that have not changed.
 */
class PagesPanel(
    private val activity: Activity,
    private val page: PageView,
    private val document: () -> Document,
    /** The notebook changed here: save it and show it. */
    private val changed: () -> Unit,
    /** Sends page [Int] to the trash and takes it out of the notebook. */
    private val trashPage: (Int) -> Unit,
) {
    /** The panel itself, for the screen to place over the page's left side. */
    val view: LinearLayout = Ui.column(activity).apply {
        setBackgroundColor(Ui.BG)
        elevation = dp(6f).toFloat()
        visibility = View.GONE
        isClickable = true  // touches on the panel never reach the page under it
    }
    /** Over everything while a page's menu is open; a tap outside the menu closes it. */
    val menuLayer: FrameLayout = FrameLayout(activity).apply {
        visibility = View.GONE
        setOnClickListener { closeMenu() }
    }

    val open: Boolean get() = view.visibility == View.VISIBLE

    private val list = Ui.column(activity).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(18f), 0, dp(18f), dp(18f))
    }
    private val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(list) }
    private val menu = Ui.column(activity).apply {
        background = Ui.rounded(Ui.RAISED, 14f, activity)
        setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        elevation = dp(8f).toFloat()
        isClickable = true
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "cuaderno-thumbnails") }
    private val workerRender = Render()  // only ever used on the worker
    /** Thumbnails by page (pages never change in place: a changed page is a new object). Main thread only. */
    private val pictures = IdentityHashMap<Page, Bitmap>()
    private var drawnDark = false
    private var current = -1
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop

    private val title = Ui.text(activity, Strings.PAGES, 15f, Ui.TEXT, bold = true).apply {
        setPadding(dp(18f), dp(18f), dp(18f), dp(14f))
    }

    init {
        view.addView(title)
        view.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        menuLayer.addView(menu, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
    }

    fun toggle() = if (open) close() else show()

    fun show() {
        view.visibility = View.VISIBLE
        refresh()
        scrollTo(page.currentPage)
    }

    fun close() {
        closeMenu()
        view.visibility = View.GONE
    }

    /** Builds the list again from the notebook as it is now. Does nothing while closed. */
    fun refresh() {
        if (!open) return
        val pages = document().pages
        title.text = Strings.pagesTitle(pages.size)
        val dark = page.darkPaper
        if (dark != drawnDark) { pictures.clear(); drawnDark = dark }
        pictures.keys.retainAll(pages.toSet())
        current = page.currentPage
        list.removeAllViews()
        for ((i, sheet) in pages.withIndex()) list.addView(item(i, sheet, dark))
    }

    /** The page in the middle of the screen changed: move the ring to it. */
    fun markCurrent(index: Int) {
        if (!open || index == current) return
        ring(current, false)
        current = index
        ring(index, true)
    }

    // --- one page -----------------------------------------------------------------

    private fun item(index: Int, sheet: Page, dark: Boolean): View {
        val item = Ui.column(activity).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val width = dp(THUMB)
        val picture = ImageView(activity).apply {
            val edge = dp(3f)
            setPadding(edge, edge, edge, edge)
            val known = pictures[sheet]
            if (known != null) setImageBitmap(known) else {
                // Paper-coloured until drawn.
                setImageDrawable(ColorDrawable(if (dark) Render.PAPER_DARK else Render.PAPER_LIGHT))
                draw(sheet, dark, this)
            }
        }
        val height = (width * sheet.height / sheet.width).toInt()
        item.addView(picture, LinearLayout.LayoutParams(width + dp(6f), height + dp(6f)))
        item.addView(Ui.text(activity, "${index + 1}", 13f, Ui.DIM).apply { setPadding(0, dp(5f), 0, dp(14f)) })
        item.tag = index
        item.setOnClickListener { page.showPage(index) }
        item.setOnLongClickListener { hold(index, item); true }
        item.setOnTouchListener(Drag(index))
        if (index == current) ringOn(item, true)
        return item
    }

    private fun draw(sheet: Page, dark: Boolean, into: ImageView) {
        val width = dp(THUMB)
        val density = activity.resources.displayMetrics.density
        worker.execute {
            val bitmap = Thumbnail.draw(sheet, width, dark, density, render = workerRender)
            activity.runOnUiThread {
                if (dark != drawnDark) return@runOnUiThread
                pictures[sheet] = bitmap
                into.setImageBitmap(bitmap)
            }
        }
    }

    /** Waits until every thumbnail asked for so far is drawn (the results still go through the main thread). For tests and renders. */
    fun awaitThumbnails() = worker.submit {}.get()

    private fun ring(index: Int, on: Boolean) {
        val item = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.tag == index } ?: return
        ringOn(item, on)
    }

    private fun ringOn(item: View, on: Boolean) {
        val group = item as LinearLayout
        group.getChildAt(0).background = if (on) GradientDrawable().apply { cornerRadius = dp(9f).toFloat(); setColor(Ui.ACCENT) } else null
        (group.getChildAt(1) as android.widget.TextView).apply {
            setTextColor(if (on) Ui.BRIGHT else Ui.DIM)
            typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
    }

    private fun scrollTo(index: Int) {
        scroll.post {
            val item = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.tag == index } ?: return@post
            scroll.scrollTo(0, (item.top - dp(40f)).coerceAtLeast(0))
        }
    }

    // --- holding a page: its menu, or moving it ----------------------------------------

    private var held = -1

    private fun hold(index: Int, item: View) {
        held = index
        scroll.requestDisallowInterceptTouchEvent(true)
        item.alpha = 0.7f
        mainMenu(index)
        // Beside the page held, kept on screen. Measured on the layer's parent: the layer
        // itself is hidden until now, so it has no size yet.
        val area = menuLayer.parent as View
        val at = IntArray(2); item.getLocationInWindow(at)
        val panel = IntArray(2); view.getLocationInWindow(panel)
        val base = IntArray(2); area.getLocationInWindow(base)
        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val top = (at[1] - base[1]).coerceIn(0, (area.height - menu.measuredHeight).coerceAtLeast(0))
        (menu.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = panel[0] - base[0] + view.width + dp(10f)
            topMargin = top
        }
        menuLayer.visibility = View.VISIBLE
        menuLayer.requestLayout()
    }

    private fun mainMenu(index: Int) {
        val sheet = document().pages.getOrNull(index) ?: return
        menu.removeAllViews()
        option("add", Strings.NEW_PAGE_AFTER) { document().insertAfter(index); done() }
        option("pages", Strings.DUPLICATE) { document().duplicate(index); done() }
        option("dot", Strings.paperIs(name(sheet.paper))) { paperMenu(index) }
        // The only page stays: a notebook has at least one (the whole notebook goes from the library).
        if (document().pages.size > 1) option("delete", Strings.TO_TRASH, tint = Ui.RED) {
            closeMenu()
            trashPage(index)
            refresh()
        }
    }

    private fun paperMenu(index: Int) {
        val now = document().pages.getOrNull(index)?.paper ?: return
        menu.removeAllViews()
        for (paper in listOf(Paper.GRID, Paper.RULED, Paper.DOTTED, Paper.BLANK)) {
            option(if (paper == now) "dot" else "", name(paper).replaceFirstChar { it.uppercase() }, bold = paper == now) {
                document().paper(index, paper); done()
            }
        }
    }

    private fun option(glyph: String, label: String, bold: Boolean = false, tint: Int = Ui.TEXT, onClick: () -> Unit) {
        val line = Ui.row(activity).apply {
            setPadding(dp(14f), dp(12f), dp(22f), dp(12f))
            setOnClickListener { onClick() }
        }
        line.addView(View(activity).apply { if (glyph.isNotEmpty()) background = Icons.drawable(glyph, tint) },
            LinearLayout.LayoutParams(dp(20f), dp(20f)))
        line.addView(Ui.text(activity, label, 15f, tint, bold = bold).apply { setPadding(dp(14f), 0, 0, 0) })
        menu.addView(line)
    }

    private fun name(paper: Paper) = when (paper) {
        Paper.GRID -> Strings.GRID
        Paper.RULED -> Strings.RULED
        Paper.DOTTED -> Strings.DOTTED
        Paper.BLANK -> Strings.BLANK
    }.lowercase()

    private fun done() {
        closeMenu()
        changed()
        refresh()
    }

    fun closeMenu() {
        menuLayer.visibility = View.GONE
        if (held >= 0) (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.tag == held }?.alpha = 1f
        held = -1
    }

    /** After holding a page, dragging it moves it: it follows the finger and lands where it is let go. */
    private inner class Drag(private val index: Int) : View.OnTouchListener {
        private var downY = 0f
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = event.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    if (held != index) return false
                    val dy = event.rawY - downY
                    if (!dragging && abs(dy) > slop) {
                        dragging = true
                        menuLayer.visibility = View.GONE
                        v.cancelLongPress()
                        v.isPressed = false
                        v.translationZ = dp(8f).toFloat()
                    }
                    if (dragging) {
                        v.translationY = dy
                        // The others step aside to show where it will land.
                        val to = landing(v)
                        for (i in 0 until list.childCount) {
                            val other = list.getChildAt(i)
                            val at = other.tag as? Int ?: continue
                            if (at == index) continue
                            other.translationY = when {
                                at in (index + 1)..to -> -v.height.toFloat()
                                at in to until index -> v.height.toFloat()
                                else -> 0f
                            }
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return false
                    val to = landing(v)
                    v.translationY = 0f
                    v.translationZ = 0f
                    dragging = false
                    held = -1
                    v.alpha = 1f
                    if (to != index && event.actionMasked == MotionEvent.ACTION_UP) {
                        document().move(index, to)
                        changed()
                    }
                    refresh()
                    return true
                }
            }
            return false
        }

        private fun landing(v: View) =
            (index + (v.translationY / v.height.coerceAtLeast(1)).roundToInt()).coerceIn(0, list.childCount - 1)
    }

    private fun dp(v: Float) = Ui.dp(activity, v)

    private companion object {
        /** Thumbnail width, dp. */
        const val THUMB = 132f
    }
}
