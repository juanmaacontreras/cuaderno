package com.cuaderno.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.cuaderno.app.ink.InkSurface
import com.cuaderno.app.ink.OwnSurface
import com.cuaderno.app.ink.Surface
import com.juanma.kit.Ui
import com.juanma.kit.Updater
import java.io.File

/**
 * Phase 0's bench (docs/plan.md): one page to write on with each of the three
 * ink engines, so Juanma picks by feel, with the engine's own delay shown
 * next to it, and a recorder that saves what the pen sent for the tests.
 * It is not the app's writing screen: nothing here is saved.
 */
class BenchActivity : Activity() {
    enum class Engine(val label: String, val about: String) {
        CLASSIC(Strings.ENGINE_CLASSIC, Strings.ABOUT_CLASSIC),
        FRONT(Strings.ENGINE_FRONT, Strings.ABOUT_FRONT),
        INK(Strings.ENGINE_INK, Strings.ABOUT_INK),
    }

    private val colors = listOf(Ui.BRIGHT, Ui.ACCENT, Ui.RED, Ui.GREEN, Ui.YELLOW)
    private val widths = listOf(1.5f, 2.5f, 4.5f)
    private val updater = Updater("juanmaacontreras/cuaderno")
    private val latency = Latency()
    private val ticker = Handler(Looper.getMainLooper())

    private var engine = Engine.CLASSIC
    lateinit var surface: Surface
        private set
    private var color = Ui.BRIGHT
    private var widthDp = 2.5f
    private var problem: String? = null
    private var update: Updater.Release? = null

    private lateinit var recorder: Recorder
    private lateinit var tools: LinearLayout
    private lateinit var status: TextView
    private lateinit var updateButton: TextView
    private var undoButton: TextView? = null
    private var clearButton: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recorder = Recorder(this)
        tools = Ui.row(this)
        status = Ui.text(this, "", 13f, Ui.DIM)
        updateButton = Ui.button(this, Strings.UPDATE, true) { installUpdate() }.apply {
            visibility = View.GONE
            compact(this)
        }

        val pad = Ui.dp(this, 8f)
        val bar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Ui.SURFACE)
            tools.setPadding(pad, pad, pad, pad)
            addView(tools)
        }
        val strip = Ui.row(this).apply {
            setBackgroundColor(Ui.SURFACE)
            setPadding(Ui.dp(this@BenchActivity, 14f), 0, pad, pad)
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            addView(updateButton)
        }
        val root = Ui.column(this)
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(strip, LinearLayout.LayoutParams(-1, -2))
        root.addView(recorder, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        use(Engine.CLASSIC)
    }

    override fun onResume() {
        super.onResume()
        ticker.post(tick)
        updater.check(this) { release ->
            update = release
            updateButton.text = Strings.UPDATE + " · v" + release.version
            updateButton.visibility = View.VISIBLE
        }
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    /** The numbers are refreshed twice a second, never from the drawing path. */
    private val tick = object : Runnable {
        override fun run() {
            renderStatus()
            ticker.postDelayed(this, 500)
        }
    }

    // --- engines ------------------------------------------------------------------

    fun use(wanted: Engine) {
        problem = null
        val fresh: Surface = try {
            when (wanted) {
                Engine.CLASSIC -> OwnSurface(this, lowLatency = false)
                Engine.FRONT -> OwnSurface(this, lowLatency = true)
                Engine.INK -> InkSurface(this)
            }
        } catch (e: Throwable) {
            // A library that will not load on this device must not take the app down with it.
            problem = Strings.ENGINE_FAILED + " (" + (e.message ?: e.javaClass.simpleName) + ")"
            OwnSurface(this, lowLatency = false)
        }
        engine = if (problem == null) wanted else Engine.CLASSIC
        fresh.color = color
        fresh.widthDp = widthDp
        fresh.onChange = { renderEdits() }
        fresh.onLatency = { ms -> latency.add(ms) }
        latency.reset()
        surface = fresh
        recorder.removeAllViews()
        recorder.addView(fresh.view, FrameLayout.LayoutParams(-1, -1))
        renderTools()
        renderStatus()
    }

    // --- the bar ------------------------------------------------------------------

    private fun renderTools() {
        tools.removeAllViews()
        for (option in Engine.entries) {
            tools.addView(pill(option.label, option == engine) { if (option != engine) use(option) })
        }
        tools.addView(space(14f))
        for (option in colors) {
            tools.addView(Ui.swatch(this, option, 22f, !surface.erasing && color == option) {
                color = option
                surface.color = option
                surface.erasing = false
                renderTools()
            })
        }
        tools.addView(space(10f))
        for (option in widths) {
            tools.addView(Ui.swatch(this, Ui.DIM, 4f + option * 3f, widthDp == option) {
                widthDp = option
                surface.widthDp = option
                renderTools()
            })
        }
        tools.addView(space(14f))
        tools.addView(pill(Strings.ERASER, surface.erasing) {
            surface.erasing = !surface.erasing
            renderTools()
        })
        undoButton = pill(Strings.UNDO, false) { surface.undo() }
        clearButton = pill(Strings.CLEAR, false) { surface.clear() }
        tools.addView(undoButton)
        tools.addView(clearButton)
        tools.addView(space(14f))
        tools.addView(pill(if (recorder.recording) Strings.RECORD_STOP else Strings.RECORD, recorder.recording) {
            if (recorder.recording) stopRecording() else recorder.begin()
            renderTools()
        })
        renderEdits()
    }

    /** After a stroke only two buttons can change: touch those, not the whole bar. */
    private fun renderEdits() {
        undoButton?.alpha = if (surface.canUndo) 1f else 0.4f
        clearButton?.alpha = if (surface.isEmpty) 0.4f else 1f
    }

    private fun renderStatus() {
        val numbers = latency.summary()
        val measured = if (numbers == null) Strings.LATENCY_NONE
            else Strings.latency(numbers.first, numbers.second, latency.count) + " (" + Strings.LATENCY_ABOUT + ")"
        val text = problem ?: (engine.about + "  ·  " + measured)
        status.text = if (recorder.recording) Strings.samples(recorder.count) + "  ·  " + text else text
        status.setTextColor(if (problem != null) Ui.YELLOW else Ui.DIM)
    }

    private fun pill(label: String, on: Boolean, onClick: () -> Unit): TextView =
        Ui.button(this, label, on, onClick).apply { compact(this) }

    private fun compact(button: TextView) {
        val h = Ui.dp(this, 12f)
        val v = Ui.dp(this, 9f)
        button.setPadding(h, v, h, v)
        button.textSize = 14f
        button.maxLines = 1
        button.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(this@BenchActivity, 8f) }
    }

    private fun space(dp: Float) = View(this).apply { layoutParams = LinearLayout.LayoutParams(Ui.dp(this@BenchActivity, dp), 1) }

    // --- recording ----------------------------------------------------------------

    /** Saves what the pen sent and opens "share", so it can go to the PC (Enlace: A la compu). */
    private fun stopRecording() {
        val recording = recorder.end()
        if (recording.samples.isEmpty()) {
            toast(Strings.RECORD_EMPTY)
            return
        }
        val folder = File(cacheDir, "recordings").apply { mkdirs() }
        val file = File(folder, "trazos-" + engine.name.lowercase() + "-" + System.currentTimeMillis() + ".csv")
        file.writeText(recording.format())
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, Strings.RECORD_SHARE))
    }

    // --- update -------------------------------------------------------------------

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
}
