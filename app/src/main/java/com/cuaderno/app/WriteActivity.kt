package com.cuaderno.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.cuaderno.app.page.PageView
import com.cuaderno.app.page.Render
import com.juanma.kit.Ui
import com.juanma.kit.Updater
import cuaderno.core.Document
import cuaderno.core.Library
import cuaderno.core.Tool
import java.io.File
import kotlin.concurrent.thread

/**
 * The writing screen: tools down the left edge, the notebook taking the rest
 * (the layout Juanma picked, the one he has in Samsung Notes). It opens the
 * notebook it is given ([NOTEBOOK]), or the last one; the bar's top button
 * goes to the library.
 */
class WriteActivity : Activity() {
    /** The pen's first colour is the paper's plain ink; the rest read on both papers. */
    private val colors get() = if (page.tool == Tool.HIGHLIGHTER) Render.HIGHLIGHTS
        else listOf(Render.ink(page.darkPaper), Ui.ACCENT_DEEP, Ui.RED, Ui.GREEN_DEEP, Ui.ORANGE)
    private val prefs by lazy { getSharedPreferences("cuaderno", MODE_PRIVATE) }
    /**
     * Widths in page points. Pen: about 0.25, 0.4 and 0.65 mm on paper. Highlighter:
     * about 2, 3.5 and 5 mm (a grid square is 5 mm).
     */
    private val widths get() = if (page.tool == Tool.HIGHLIGHTER) listOf(6f, 10f, 14f) else listOf(0.7f, 1.1f, 1.8f)
    /** What each tool was last set to (colour, width), so switching tools brings it back. */
    private val chosen = mutableMapOf(Tool.HIGHLIGHTER to Pair(Ui.YELLOW, 10f))
    private val updater = Updater("juanmaacontreras/cuaderno")

    private lateinit var store: Store
    lateinit var document: Document
        private set
    lateinit var page: PageView
        private set
    private lateinit var recorder: Recorder
    /** The tools down the left edge. */
    lateinit var bar: LinearLayout
        private set
    private lateinit var widthPanel: LinearLayout
    lateinit var pages: PagesPanel
        private set
    private lateinit var notice: TextView
    private lateinit var updateButton: TextView
    private var update: Updater.Release? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val library = Library(LibraryActivity.notebooks(this))
        // The first notebook, from before there were more, kept its name: it stays where it is.
        val file = intent.getStringExtra(NOTEBOOK)?.let { File(it) } ?: library.last ?: File(library.root, "cuaderno.cuaderno")
        store = Store(file)
        document = store.load()
        library.last = file

        page = PageView(this, lowLatency)
        page.document = document
        // Light paper unless he chose dark (Juanma, 2026-11-16: light is what he is used to).
        page.darkPaper = prefs.getBoolean("dark_paper", false)
        page.color = Render.ink(page.darkPaper)
        // Starting to write puts the widths away: the page is for writing, not for a panel in the way.
        page.onPenDown = { if (widthPanel.visibility == View.VISIBLE) widthPanel.visibility = View.GONE }
        page.onChange = {
            store.saveSoon(document)
            renderBar()
            // The thumbnails follow the writing a moment later, not on every stroke.
            if (pages.open) {
                page.removeCallbacks(refreshPages)
                page.postDelayed(refreshPages, 800)
            }
        }
        pages = PagesPanel(this, page, { document }) { changed() }
        page.onPageShown = { pages.markCurrent(it) }
        page.onAddPage = { document.addPage(); changed() }
        recorder = Recorder(this)
        recorder.addView(page, FrameLayout.LayoutParams(-1, -1))

        bar = Ui.column(this).apply {
            setBackgroundColor(Ui.SURFACE)
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = Ui.dp(this@WriteActivity, 6f)
            setPadding(pad, Ui.dp(this@WriteActivity, 8f), pad, Ui.dp(this@WriteActivity, 8f))
        }
        widthPanel = Ui.row(this).apply {
            background = Ui.rounded(Ui.SURFACE, 16f, this@WriteActivity)
            val pad = Ui.dp(this@WriteActivity, 4f)
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }
        notice = Ui.text(this, "", 13f, Ui.YELLOW).apply {
            background = Ui.rounded(Ui.SURFACE, 12f, this@WriteActivity)
            val pad = Ui.dp(this@WriteActivity, 10f)
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }
        updateButton = Ui.button(this, Strings.UPDATE, true) { installUpdate() }.apply { visibility = View.GONE }

        val m = Ui.dp(this, 10f)
        val area = FrameLayout(this).apply {
            addView(recorder, FrameLayout.LayoutParams(-1, -1))
            addView(pages.view, FrameLayout.LayoutParams(Ui.dp(this@WriteActivity, 176f), -1, Gravity.START))
            addView(widthPanel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(m, m, m, m) })
            addView(updateButton, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(m, m, m, m) })
            addView(notice, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(m, m, m, m) })
        }
        setContentView(FrameLayout(this).apply {
            addView(Ui.row(this@WriteActivity).apply {
                addView(bar, LinearLayout.LayoutParams(-2, -1))
                addView(area, LinearLayout.LayoutParams(0, -1, 1f))
            }, FrameLayout.LayoutParams(-1, -1))
            // A page's menu, over everything (bar included) so a tap anywhere else closes it.
            addView(pages.menuLayer, FrameLayout.LayoutParams(-1, -1))
        })
        renderBar()
        showProblem(store.problem?.let { Strings.UNREADABLE + it })
    }

    /**
     * The whole screen is the page: no clock and icons above, no gesture line
     * below (as Samsung Notes does). A swipe from the edge brings them back
     * for a moment. Asked again on focus because a dialog or the keyboard
     * returns them.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        fastestScreen()
        Screen.fullscreen(this)
    }

    /**
     * Asks for the screen's fastest mode at its current size (90 Hz on the Tab S10 FE).
     * The page is drawn on its own surface, and Android picks the refresh rate from how
     * regularly a surface is fed: without asking, it can settle on 60 for this one.
     */
    private fun fastestScreen() {
        @Suppress("DEPRECATION") val display = windowManager.defaultDisplay ?: return  // Activity.display needs API 30
        val now = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (window.attributes.preferredDisplayModeId != best.modeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }

    override fun onResume() {
        super.onResume()
        updater.check(this) { release ->
            update = release
            updateButton.text = Strings.UPDATE + " · v" + release.version
            updateButton.visibility = View.VISIBLE
        }
    }

    /** Leaving the screen is the last sure moment before the system may kill the app. */
    override fun onPause() {
        super.onPause()
        store.saveNow(document)
        showProblem(store.problem?.let { Strings.SAVE_FAILED + it })
    }

    // --- the bar ------------------------------------------------------------------

    private fun renderBar() {
        bar.removeAllViews()
        // Saved on the way out (onPause), as when leaving the app.
        bar.addView(Ui.tool(this, "library") {
            startActivity(Intent(this, LibraryActivity::class.java))
            finish()
        })
        bar.addView(Ui.tool(this, "pages", on = pages.open) {
            pages.toggle()
            widthPanel.visibility = View.GONE
            renderBar()
        })
        bar.addView(space(10f))
        for ((tool, icon) in listOf(Tool.PEN to "pen", Tool.HIGHLIGHTER to "highlighter")) {
            val inUse = !page.erasing && page.tool == tool
            bar.addView(Ui.tool(this, icon, on = inUse) {
                if (inUse) {
                    // Tapping the tool in use opens its widths, as in Samsung Notes.
                    widthPanel.visibility = if (widthPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                } else {
                    use(tool)
                    widthPanel.visibility = View.GONE
                }
                renderBar()
            })
        }
        bar.addView(Ui.tool(this, "eraser", on = page.erasing) {
            page.erasing = true
            widthPanel.visibility = View.GONE
            renderBar()
        })
        bar.addView(space(10f))
        for (option in colors) {
            val current = Render.shown(page.color, page.darkPaper) == option
            bar.addView(Ui.swatch(this, option, 22f, !page.erasing && current) {
                page.color = option
                page.erasing = false
                renderBar()
            })
        }
        bar.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        bar.addView(Ui.tool(this, "undo", dim = !document.canUndo) { document.undo(); changed() })
        bar.addView(Ui.tool(this, "redo", dim = !document.canRedo) { document.redo(); changed() })
        lateinit var more: View
        more = Ui.tool(this, "more") { menu(more) }
        bar.addView(more)

        widthPanel.removeAllViews()
        val marker = page.tool == Tool.HIGHLIGHTER
        for (option in widths) {
            val size = if (marker) 6f + option * 1.2f else 3f + option * 6f
            widthPanel.addView(Ui.swatch(this, if (marker) page.color else Ui.TEXT, size, page.widthPt == option) {
                page.widthPt = option
                widthPanel.visibility = View.GONE
                renderBar()
            })
        }
    }

    /** Switches the stylus to [tool] with the colour and width it had last time. */
    private fun use(tool: Tool) {
        page.erasing = false
        if (page.tool == tool) return
        chosen[page.tool] = Pair(page.color, page.widthPt)
        page.tool = tool
        val (color, width) = chosen[tool] ?: Pair(Render.ink(page.darkPaper), 1.1f)
        page.color = color
        page.widthPt = width
    }

    private val refreshPages = Runnable { pages.refresh() }

    /** Undo, redo and the pages panel change the notebook from outside the page. */
    private fun changed() {
        page.document = document
        store.saveSoon(document)
        renderBar()
        pages.refresh()
    }

    @Deprecated("Still the simplest way back on every Android it runs on")
    override fun onBackPressed() {
        if (pages.open) {
            pages.close()
            renderBar()
        } else {
            @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun space(dp: Float) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, Ui.dp(this@WriteActivity, dp)) }

    private fun menu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, Strings.EXPORT)
        popup.menu.add(0, 3, 1, if (page.darkPaper) Strings.PAPER_LIGHT else Strings.PAPER_DARK)
        popup.menu.add(0, 2, 2, if (recorder.recording) Strings.RECORD_STOP else Strings.RECORD)
        popup.menu.add(0, 4, 3, if (page.onFluency != null) Strings.FLUENCY_STOP else Strings.FLUENCY)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> export()
                2 -> if (recorder.recording) stopRecording() else recorder.begin()
                4 -> if (page.onFluency != null) {
                    page.onFluency = null
                    showProblem(null)
                } else {
                    page.onFluency = { frames, worst, hz -> showProblem(Strings.fluency(frames, worst, hz)) }
                }
                3 -> {
                    page.darkPaper = !page.darkPaper
                    prefs.edit().putBoolean("dark_paper", page.darkPaper).apply()
                    renderBar()
                }
            }
            true
        }
        popup.show()
    }

    private fun showProblem(text: String?) {
        notice.text = text ?: ""
        notice.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // --- out of the app -------------------------------------------------------------

    private fun export() {
        val pages = document.pages
        val dark = page.darkPaper
        val name = document.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().ifEmpty { "cuaderno" }
        val file = File(cacheDir, "exports/$name.pdf")
        thread(name = "cuaderno-export") {
            val done = try { PdfExport.write(pages, file, dark) } catch (e: Exception) { null }
            runOnUiThread {
                when (done) {
                    true -> share(file, "application/pdf", Strings.EXPORT_SHARE)
                    false -> toast(Strings.EXPORT_EMPTY)
                    null -> toast(Strings.EXPORT_FAILED)
                }
            }
        }
    }

    /** Saves what the pen sent and opens "share", so it can go to the PC (Enlace: A la compu). */
    private fun stopRecording() {
        val recording = recorder.end()
        if (recording.samples.isEmpty()) {
            toast(Strings.RECORD_EMPTY)
            return
        }
        val file = File(cacheDir, "recordings/trazos-" + System.currentTimeMillis() + ".csv")
        file.parentFile?.mkdirs()
        file.writeText(recording.format())
        share(file, "text/csv", Strings.RECORD_SHARE)
    }

    private fun share(file: File, type: String, title: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, title))
    }

    private fun installUpdate() {
        val release = update ?: return
        updater.install(this, release) { state ->
            when (state) {
                Updater.State.NEEDS_PERMISSION -> toast(Strings.UPDATE_PERMISSION)
                Updater.State.DOWNLOADING -> updateButton.text = Strings.UPDATE_DOWNLOADING
                Updater.State.FAILED -> {
                    updateButton.text = Strings.UPDATE + " · v" + release.version
                    toast(Strings.UPDATE_FAILED)
                }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        /** The path of the notebook to open. */
        const val NOTEBOOK = "notebook"
        /** The tests turn this off: the front buffer needs a real screen. */
        var lowLatency = true
    }
}
