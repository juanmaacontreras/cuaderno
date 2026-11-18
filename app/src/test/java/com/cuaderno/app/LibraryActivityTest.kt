package com.cuaderno.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import com.juanma.kit.Ui
import cuaderno.core.Document
import cuaderno.core.Library
import cuaderno.core.Notebook
import cuaderno.core.NotebookFile
import cuaderno.core.Paper
import cuaderno.core.StrokeBuilder
import cuaderno.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

/** Notebooks by subject on screen: the one he has keeps opening, and every notebook is one tap from the library. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-mdpi")
class LibraryActivityTest {
    private val root: File get() = File(RuntimeEnvironment.getApplication().filesDir, "notebooks")
    private val library get() = Library(root)

    @Before fun noFrontBuffer() { WriteActivity.lowLatency = false }

    /** A notebook with one stroke on its first page, as the app saves them. */
    private fun written(file: File, title: String) {
        file.parentFile!!.mkdirs()
        val doc = Document(Notebook("nb-" + file.nameWithoutExtension, title, "2026-11-17T10:00:00-03:00", "2026-11-17T10:00:00-03:00", emptyList()))
        doc.add(0, StrokeBuilder(Tool.PEN, Ui.BG, 1.1f).apply { add(100f, 100f, 0.5f, 0f); add(200f, 120f, 0.5f, 10f) }.build())
        NotebookFile.write(file, doc.notebook("2026-11-17T10:00:00-03:00"))
    }

    private fun write(path: File? = null): WriteActivity {
        val intent = Intent(RuntimeEnvironment.getApplication(), WriteActivity::class.java)
        if (path != null) intent.putExtra(WriteActivity.NOTEBOOK, path.path)
        return Robolectric.buildActivity(WriteActivity::class.java, intent).setup().get()
    }

    private fun libraryScreen() = Robolectric.buildActivity(LibraryActivity::class.java).setup().get()

    private fun texts(view: View): List<String> = when (view) {
        is android.widget.TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun find(view: View, text: String): View? {
        if (view is android.widget.TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), text)?.let { return it }
        return null
    }

    @Test fun theNotebookHeHasKeepsOpeningAndBecomesTheLastOne() {
        val first = File(root, "cuaderno.cuaderno")
        written(first, "Cuaderno")
        val screen = write()
        assertEquals(1, screen.document.pages[0].strokes.size)
        assertEquals(first.canonicalFile, library.last!!.canonicalFile)
    }

    @Test fun theBarsTopButtonGoesToTheLibrary() {
        written(File(root, "cuaderno.cuaderno"), "Cuaderno")
        val screen = write()
        screen.bar.getChildAt(0).performClick()
        assertEquals(LibraryActivity::class.java.name, shadowOf(screen).nextStartedActivity.component!!.className)
        assertTrue(screen.isFinishing)
    }

    @Test fun aCoverOpensItsNotebook() {
        library.addSubject("Circuitos", 0)
        written(File(root, "Circuitos/nb-1.cuaderno"), "Teoría · Unidad 3")
        written(File(root, "Circuitos/nb-2.cuaderno"), "Guía 2")
        val screen = libraryScreen()
        val content = screen.findViewById<View>(android.R.id.content)
        assertTrue(texts(content).containsAll(listOf("Circuitos", "Teoría · Unidad 3", "Guía 2", "2 cuadernos")))

        // The title's parent is the cover.
        (find(content, "Guía 2")!!.parent as View).performClick()
        val started = shadowOf(screen).nextStartedActivity
        assertEquals(File(root, "Circuitos/nb-2.cuaderno").path, started.getStringExtra(WriteActivity.NOTEBOOK))
        library.last = File(root, "Circuitos/nb-1.cuaderno")  // so only the one asked for can explain what opens
        val opened = Robolectric.buildActivity(WriteActivity::class.java, started).setup().get()
        assertEquals("Guía 2", opened.document.title)
        assertEquals("nb-2.cuaderno", library.last!!.name)
    }

    @Test fun aNewNotebookGoesInTheSubjectShownWithThePaperChosen() {
        library.addSubject("Circuitos", 0)
        library.addSubject("Física II", 0)
        val screen = libraryScreen()
        find(screen.findViewById(android.R.id.content), "Física II")!!.let { (it.parent as View).performClick() }
        assertEquals("Física II", screen.shown)

        val form = screen.newNotebook()
        assertEquals("Física II", form.subject)
        form.input.setText("  Guía 3 · ondas ")
        find(form.card, "Rayado")!!.let { (it.parent as View).performClick() }
        find(form.card, "Crear")!!.performClick()

        val file = library.notebooks("Física II").single()
        assertEquals("Guía 3 · ondas", file.title)
        assertEquals(Paper.RULED, file.cover!!.paper)
        assertEquals(file.file.path, shadowOf(screen).nextStartedActivity.getStringExtra(WriteActivity.NOTEBOOK))
    }

    @Test fun aNotebookWithoutANameIsCalledUntitled() {
        val screen = libraryScreen()
        val form = screen.newNotebook()
        assertNull(form.subject)
        find(form.card, "Crear")!!.performClick()
        assertEquals(Strings.UNTITLED, library.notebooks().single().title)
    }

    @Test fun holdingACoverRenamesItAndMovesItKeepingItsInk() {
        library.addSubject("Circuitos", 0)
        val first = File(root, "cuaderno.cuaderno")
        written(first, "Cuaderno")
        val screen = libraryScreen()
        val content = screen.findViewById<View>(android.R.id.content)
        (find(content, "Cuaderno")!!.parent as View).performLongClick()
        assertNotNull(screen.dialog)
        val form = screen.edit(library.notebooks().single())
        form.input.setText("Teoría · Unidad 1")
        find(form.card, "Circuitos")!!.performClick()
        find(form.card, "Guardar")!!.performClick()

        assertNull(screen.dialog)
        assertFalse(first.exists())
        val moved = library.notebooks("Circuitos").single()
        assertEquals("Teoría · Unidad 1", moved.title)
        assertEquals(1, NotebookFile.read(moved.file).pages[0].strokes.size)
    }

    @Test fun aSubjectIsMadeFromTheList() {
        val screen = libraryScreen()
        val field = screen.newSubject()
        find(screen.dialog!!, "Crear")!!.performClick()
        assertTrue("an empty name is refused", texts(screen.dialog!!).contains(Strings.SUBJECT_EMPTY))
        field.setText("Electrónica")
        find(screen.dialog!!, "Crear")!!.performClick()
        assertNull(screen.dialog)
        assertEquals(listOf("Electrónica"), library.subjects().map { it.name })
        assertEquals("Electrónica", screen.shown)
        assertEquals(LibraryActivity.PALETTE[0], library.subjects().single().color)
    }

    @Test fun backGoesToTheNotebookThatWasOpen() {
        val file = File(root, "cuaderno.cuaderno")
        written(file, "Cuaderno")
        library.last = file
        val screen = libraryScreen()
        @Suppress("DEPRECATION") screen.onBackPressed()
        assertEquals(file.path, File(shadowOf(screen).nextStartedActivity.getStringExtra(WriteActivity.NOTEBOOK)!!).path)
    }
}
