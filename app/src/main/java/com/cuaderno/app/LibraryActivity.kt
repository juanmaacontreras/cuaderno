package com.cuaderno.app

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cuaderno.app.page.Thumbnail
import com.juanma.kit.Icons
import com.juanma.kit.Ui
import cuaderno.core.Library
import cuaderno.core.Page
import cuaderno.core.Paper
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The notebooks, by subject: subjects down the left, the open one's notebooks as
 * covers (their first page). Tapping a cover opens it; holding it renames it or
 * moves it to another subject. The app opens on the last notebook, not here: at
 * the start of a class that is the one wanted.
 */
class LibraryActivity : Activity() {
    lateinit var library: Library
        private set
    private val prefs by lazy { getSharedPreferences("cuaderno", MODE_PRIVATE) }

    /** The subject shown: a name, [Library.NO_SUBJECT], or null for all. */
    var shown: String? = null
        private set

    private lateinit var rail: LinearLayout
    private lateinit var main: LinearLayout
    private lateinit var root: FrameLayout
    /** Over everything: the dialog open, if any. */
    var dialog: View? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(notebooks(this))
        shown = prefs.getString(SHOWN, null)?.takeIf { it == Library.NO_SUBJECT || File(library.root, it).isDirectory }
        rail = Ui.column(this).apply {
            setBackgroundColor(Ui.SURFACE)
            setPadding(dp(14f), dp(22f), dp(14f), dp(16f))
        }
        main = Ui.column(this).apply { setBackgroundColor(Ui.BG); setPadding(dp(36f), dp(28f), dp(36f), 0) }
        root = FrameLayout(this).apply {
            addView(Ui.row(this@LibraryActivity).apply {
                gravity = Gravity.TOP
                addView(rail, LinearLayout.LayoutParams(dp(250f), -1))
                addView(main, LinearLayout.LayoutParams(0, -1, 1f))
            }, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
        refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Screen.fullscreen(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refresh()  // as many covers a row as fit the new width
    }

    /** Back goes back to the notebook that was open. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (dialog != null) { close(); return }
        val last = library.last
        if (last != null) open(last) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // --- the two columns --------------------------------------------------------------

    fun refresh() {
        val subjects = library.subjects()
        val all = library.notebooks()
        val counts = all.groupingBy { it.subject }.eachCount()

        rail.removeAllViews()
        rail.addView(Ui.text(this, Strings.NOTEBOOKS, 22f, Ui.BRIGHT, bold = true).apply { setPadding(dp(14f), 0, 0, dp(16f)) })
        rail.addView(line(Strings.ALL, all.size, null, null, shown == null) { show(null) })
        for ((i, subject) in subjects.withIndex()) {
            rail.addView(line(subject.name, counts[subject.name] ?: 0, colorOf(subject, i), null, shown == subject.name) { show(subject.name) })
        }
        if ((counts[null] ?: 0) > 0 || shown == Library.NO_SUBJECT) {
            rail.addView(line(Strings.NO_SUBJECT, counts[null] ?: 0, Ui.MUTED, null, shown == Library.NO_SUBJECT) { show(Library.NO_SUBJECT) })
        }
        rail.addView(line(Strings.NEW_SUBJECT, 0, null, "add", false) { newSubject() })

        main.removeAllViews()
        val entries = if (shown == null) all else all.filter { (it.subject ?: Library.NO_SUBJECT) == shown }
        val head = Ui.row(this)
        val names = Ui.column(this)
        names.addView(Ui.text(this, when (shown) { null -> Strings.ALL; Library.NO_SUBJECT -> Strings.NO_SUBJECT; else -> shown!! }, 28f, Ui.BRIGHT, bold = true))
        names.addView(Ui.text(this, Strings.notebooks(entries.size), 15f, Ui.DIM))
        head.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Ui.button(this, "+  " + Strings.NEW_NOTEBOOK, filled = true) { newNotebook() })
        main.addView(head, LinearLayout.LayoutParams(-1, -2))

        val grid = Ui.column(this).apply { setPadding(0, 0, 0, dp(28f)) }
        if (entries.isEmpty()) {
            grid.addView(Ui.text(this, Strings.EMPTY_SUBJECT, 16f, Ui.DIM).apply { setPadding(0, dp(32f), 0, 0) })
        }
        val columns = maxOf(2, ((resources.configuration.screenWidthDp - 250 - 72 + 30) / (COVER + 30)))
        var row = Ui.row(this)
        for ((n, entry) in entries.withIndex()) {
            if (n % columns == 0) {
                row = Ui.row(this).apply { gravity = Gravity.TOP }
                grid.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(if (n == 0) 26f else 22f) })
            }
            row.addView(cover(entry), LinearLayout.LayoutParams(dp(COVER.toFloat()), -2).apply { marginEnd = dp(30f) })
        }
        main.addView(ScrollView(this).apply { addView(grid); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun show(subject: String?) {
        shown = subject
        prefs.edit().putString(SHOWN, subject).apply()
        refresh()
    }

    /** One line of the subjects list: dot or icon, name, count; [on] is the one shown. */
    private fun line(name: String, count: Int, color: Int?, glyph: String?, on: Boolean, onClick: () -> Unit): View {
        val row = Ui.row(this).apply {
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
            if (on) background = Ui.rounded(Ui.RAISED, 12f, this@LibraryActivity)
            setOnClickListener { onClick() }
        }
        when {
            glyph != null -> row.addView(View(this).apply { background = Icons.drawable(glyph, Ui.DIM) }, LinearLayout.LayoutParams(dp(20f), dp(20f)))
            color != null -> row.addView(dot(color))
            else -> row.addView(View(this), LinearLayout.LayoutParams(dp(12f), 1))
        }
        row.addView(Ui.text(this, name, 16f, if (on) Ui.BRIGHT else Ui.TEXT, bold = on).apply { maxLines = 1 },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12f) })
        if (count > 0) row.addView(Ui.text(this, "$count", 14f, Ui.DIM))
        return row
    }

    private fun dot(color: Int) = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(12f), dp(12f))
    }

    private fun cover(entry: Library.Entry): View {
        val card = Ui.column(this).apply {
            setOnClickListener { open(entry.file) }
            setOnLongClickListener { edit(entry); true }
        }
        val page = entry.cover ?: Page("x", Page.A4_WIDTH, Page.A4_HEIGHT, Paper.BLANK, emptyList())
        card.addView(ImageView(this).apply { setImageBitmap(thumbnail(page, COVER.toFloat())) })
        card.addView(Ui.text(this, entry.title, 15f, Ui.BRIGHT, bold = true).apply { setPadding(0, dp(10f), 0, 0); maxLines = 2 })
        val about = if (entry.problem != null) Strings.UNREADABLE_SHORT else Strings.pages(entry.pages) + " · " + day(entry.modified)
        card.addView(Ui.text(this, about, 13f, if (entry.problem != null) Ui.YELLOW else Ui.DIM))
        return card
    }

    /** A sheet [widthDp] wide, on the paper he set unless asked otherwise (see [Thumbnail]). */
    private fun thumbnail(page: Page, widthDp: Float, part: Float = 1f,
                          dark: Boolean = prefs.getBoolean("dark_paper", false)): Bitmap =
        Thumbnail.draw(page, dp(widthDp), dark, resources.displayMetrics.density, part)

    /** "hoy", "ayer", or the day and month. */
    private fun day(iso: String): String = try {
        val date = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        when (date) {
            today -> Strings.TODAY
            today.minusDays(1) -> Strings.YESTERDAY
            else -> "${date.dayOfMonth} ${Strings.MONTHS[date.monthValue - 1]}"
        }
    } catch (e: Exception) { "" }

    fun open(file: File) {
        library.last = file
        startActivity(Intent(this, WriteActivity::class.java).putExtra(WriteActivity.NOTEBOOK, file.path))
        finish()
    }

    // --- dialogs ----------------------------------------------------------------------

    /** A card over the dimmed library, with a name field, subject chips and maybe papers. */
    inner class Form(title: String, name: String, var subject: String?, withPaper: Boolean, action: String,
                             val done: (Form) -> Unit) {
        val input = EditText(this@LibraryActivity).apply {
            setText(name)
            setSelection(text.length)
            textSize = 17f
            setTextColor(Ui.BRIGHT)
            setHintTextColor(Ui.MUTED)
            hint = Strings.NAME_HINT
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(16f), dp(13f), dp(16f), dp(13f))
            background = GradientDrawable().apply { cornerRadius = dp(12f).toFloat(); setColor(Ui.BG); setStroke(dp(2f), Ui.ACCENT) }
        }
        var paper = Paper.GRID
        private val chips = Ui.row(this@LibraryActivity)
        private val papers = Ui.row(this@LibraryActivity).apply { gravity = Gravity.TOP }
        val card: LinearLayout = Ui.column(this@LibraryActivity).apply {
            background = Ui.rounded(Ui.SURFACE, 22f, this@LibraryActivity)
            setPadding(dp(30f), dp(26f), dp(30f), dp(26f))
            isClickable = true  // a tap on the card is not a tap on the dimmed library behind
        }

        init {
            card.addView(Ui.text(this@LibraryActivity, title, 22f, Ui.BRIGHT, bold = true))
            card.addView(label(Strings.NAME))
            card.addView(input, LinearLayout.LayoutParams(-1, -2))
            card.addView(label(Strings.SUBJECT))
            card.addView(android.widget.HorizontalScrollView(this@LibraryActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            })
            if (withPaper) {
                card.addView(label(Strings.PAPER))
                card.addView(papers)
            }
            val buttons = Ui.row(this@LibraryActivity).apply { setPadding(0, dp(26f), 0, 0) }
            buttons.addView(View(this@LibraryActivity), LinearLayout.LayoutParams(0, 1, 1f))
            buttons.addView(Ui.button(this@LibraryActivity, Strings.CANCEL, filled = false) { close() },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10f) })
            buttons.addView(Ui.button(this@LibraryActivity, action, filled = true) { done(this) })
            card.addView(buttons, LinearLayout.LayoutParams(-1, -2))
            // Enter or ✓ puts the keyboard away, so the papers and the buttons show; it does not create.
            input.setOnEditorActionListener { view, _, _ ->
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(view.windowToken, 0)
                view.clearFocus()
                true
            }
            renderChips()
            renderPapers()
        }

        val name: String get() = input.text.toString().trim()

        private fun renderChips() {
            chips.removeAllViews()
            val options = library.subjects().map { it.name } + listOf<String?>(null)
            for (option in options) {
                val on = option == subject
                chips.addView(Ui.text(this@LibraryActivity, option ?: Strings.NO_SUBJECT, 15f, if (on) Ui.BG else Ui.TEXT, bold = on).apply {
                    setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
                    background = Ui.rounded(if (on) Ui.ACCENT else Ui.RAISED, 20f, this@LibraryActivity)
                    setOnClickListener { subject = option; renderChips() }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8f) })
            }
        }

        private fun renderPapers() {
            papers.removeAllViews()
            for ((option, label) in listOf(Paper.GRID to Strings.GRID, Paper.RULED to Strings.RULED,
                    Paper.DOTTED to Strings.DOTTED, Paper.BLANK to Strings.BLANK)) {
                val on = option == paper
                val item = Ui.column(this@LibraryActivity).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setOnClickListener { paper = option; renderPapers() }
                }
                val edge = dp(3f)
                item.addView(ImageView(this@LibraryActivity).apply {
                    // Light whatever the setting: this shows the pattern, which dark paper keeps faint.
                    setImageBitmap(thumbnail(Page("x", Page.A4_WIDTH, Page.A4_HEIGHT, option, emptyList()), 96f, part = 0.3f, dark = false))
                    setPadding(edge, edge, edge, edge)
                    if (on) background = GradientDrawable().apply { cornerRadius = dp(9f).toFloat(); setColor(Ui.ACCENT) }
                })
                item.addView(Ui.text(this@LibraryActivity, label, 14f, if (on) Ui.BRIGHT else Ui.TEXT, bold = on).apply { setPadding(0, dp(8f), 0, 0) })
                papers.addView(item, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(22f) })
            }
        }

        private fun label(text: String) = Ui.text(this@LibraryActivity, text, 14f, Ui.DIM).apply { setPadding(0, dp(20f), 0, dp(8f)) }
    }

    private fun present(card: View, widthDp: Float) {
        close()
        val layer = FrameLayout(this).apply {
            setBackgroundColor(0xB3242933.toInt())
            setOnClickListener { close() }
            addView(card, FrameLayout.LayoutParams(dp(widthDp), -2, Gravity.CENTER))
        }
        dialog = layer
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
    }

    fun close() {
        dialog?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
            root.removeView(it)
        }
        dialog = null
    }

    /** The form for a new notebook, in the subject being shown. */
    fun newNotebook(): Form {
        val start = shown?.takeIf { it != Library.NO_SUBJECT }
        val form = Form(Strings.NEW_NOTEBOOK, "", start, withPaper = true, action = Strings.CREATE) { f ->
            val id = "nb-" + System.currentTimeMillis()
            val file = library.create(f.name.ifEmpty { Strings.UNTITLED }, f.subject, f.paper, Screen.now(), id)
            open(file)
        }
        // No keyboard on its own: it would cover the papers and the buttons. Its ✓ just puts it away.
        present(form.card, 600f)
        return form
    }

    /** Rename a notebook or move it to another subject. */
    fun edit(entry: Library.Entry): Form {
        val form = Form(Strings.EDIT_NOTEBOOK, entry.title, entry.subject, withPaper = false, action = Strings.SAVE) { f ->
            try {
                var file = entry.file
                if (f.name.isNotEmpty() && f.name != entry.title) library.rename(file, f.name)
                if (f.subject != entry.subject) file = library.move(file, f.subject)
                close()
                refresh()
            } catch (e: Exception) {
                close()
                android.widget.Toast.makeText(this, Strings.EDIT_FAILED, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        present(form.card, 600f)
        return form
    }

    /** A subject: just its name. */
    fun newSubject(): EditText {
        val field = EditText(this).apply {
            textSize = 17f
            setTextColor(Ui.BRIGHT)
            setHintTextColor(Ui.MUTED)
            hint = Strings.SUBJECT_HINT
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(dp(16f), dp(13f), dp(16f), dp(13f))
            background = GradientDrawable().apply { cornerRadius = dp(12f).toFloat(); setColor(Ui.BG); setStroke(dp(2f), Ui.ACCENT) }
        }
        val problem = Ui.text(this, "", 14f, Ui.YELLOW).apply { visibility = View.GONE; setPadding(0, dp(10f), 0, 0) }
        fun create() {
            val name = field.text.toString().trim()
            try {
                val made = library.addSubject(name, PALETTE[library.subjects().size % PALETTE.size])
                close()
                show(made.name)
            } catch (e: Exception) {
                problem.text = if (name.isEmpty()) Strings.SUBJECT_EMPTY else Strings.SUBJECT_BAD
                problem.visibility = View.VISIBLE
            }
        }
        val card = Ui.column(this).apply {
            background = Ui.rounded(Ui.SURFACE, 22f, this@LibraryActivity)
            setPadding(dp(30f), dp(26f), dp(30f), dp(26f))
            isClickable = true
            addView(Ui.text(this@LibraryActivity, Strings.NEW_SUBJECT, 22f, Ui.BRIGHT, bold = true).apply { setPadding(0, 0, 0, dp(20f)) })
            addView(field, LinearLayout.LayoutParams(-1, -2))
            addView(problem)
            addView(Ui.row(this@LibraryActivity).apply {
                setPadding(0, dp(26f), 0, 0)
                addView(View(this@LibraryActivity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(Ui.button(this@LibraryActivity, Strings.CANCEL, filled = false) { close() },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10f) })
                addView(Ui.button(this@LibraryActivity, Strings.CREATE, filled = true) { create() })
            }, LinearLayout.LayoutParams(-1, -2))
        }
        field.setOnEditorActionListener { _, _, _ -> create(); true }
        present(card, 480f)
        field.requestFocus()
        return field
    }

    private fun colorOf(subject: Library.Subject, index: Int) =
        if (subject.color != 0) subject.color else PALETTE[index % PALETTE.size]

    private fun dp(v: Float) = Ui.dp(this, v)

    companion object {
        private const val SHOWN = "library_shown"
        /** Cover width, dp. */
        private const val COVER = 168
        /** Subject colours, in the order new ones take them: Nord frost, orange, green, yellow, purple, red, blue. */
        val PALETTE = listOf(Ui.ACCENT, Ui.ORANGE, Ui.GREEN, Ui.YELLOW, 0xFFB48EAD.toInt(), Ui.RED, Ui.ACCENT_DEEP)

        /** Where the notebooks live. */
        fun notebooks(activity: Activity) = File(activity.filesDir, "notebooks")
    }
}
