package cuaderno.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Notebooks by subject: folders are the truth, library.json only adds colours, order and the last one opened. */
class LibraryTest {
    @get:Rule val folder = TemporaryFolder()

    private fun library() = Library(File(folder.root, "notebooks"))

    @Test fun subjectsAreFoldersAndNotebooksAreFilesInThem() {
        val lib = library()
        lib.addSubject("  Circuitos ", 1)
        lib.addSubject("Física II", 2)
        val a = lib.create("Teoría · Unidad 1", "Circuitos", Paper.GRID, "2026-11-01T10:00:00Z", "nb-1")
        lib.create("Guía 2", "Circuitos", Paper.RULED, "2026-11-02T10:00:00Z", "nb-2")
        lib.create("Suelto", null, Paper.BLANK, "2026-11-03T10:00:00Z", "nb-3")

        assertEquals(listOf("Circuitos", "Física II"), lib.subjects().map { it.name })
        assertEquals(listOf(1, 2), lib.subjects().map { it.color })
        assertEquals(File(lib.root, "Circuitos/nb-1.cuaderno"), a)
        // The latest changed first.
        assertEquals(listOf("Guía 2", "Teoría · Unidad 1"), lib.notebooks("Circuitos").map { it.title })
        assertEquals(listOf("Suelto"), lib.notebooks(Library.NO_SUBJECT).map { it.title })
        assertEquals(3, lib.notebooks().size)
        assertTrue(lib.notebooks("Física II").isEmpty())

        val cover = lib.notebooks("Circuitos").first { it.title == "Guía 2" }
        assertEquals(1, cover.pages)
        assertEquals(Paper.RULED, cover.cover!!.paper)
        assertEquals("Circuitos", lib.subjectOf(a))
        assertNull(lib.subjectOf(File(lib.root, "nb-3.cuaderno")))
    }

    @Test fun aNewNotebookKeepsItsPaperOnTheNextPages() {
        val lib = library()
        val file = lib.create("Rayado", null, Paper.RULED, "2026-11-01T10:00:00Z", "nb-1")
        val doc = Document(NotebookFile.read(file))
        doc.add(0, StrokeBuilder(Tool.PEN, 0, 1f).apply { add(10f, 10f, 0.5f, 0f) }.build())
        assertEquals(listOf(Paper.RULED, Paper.RULED), doc.pages.map { it.paper })
    }

    @Test fun badSubjectNamesAreRefused() {
        val lib = library()
        lib.addSubject("Análisis II", 0)
        for (bad in listOf("", "   ", "a/b", "..", ".oculta", "Análisis II", "library.json", "x".repeat(61))) {
            assertTrue(bad, runCatching { lib.addSubject(bad, 0) }.isFailure)
        }
        assertEquals(listOf("Análisis II"), lib.subjects().map { it.name })
    }

    @Test fun movingAndRenamingKeepEveryStroke() {
        val lib = library()
        lib.addSubject("Circuitos", 0)
        val file = lib.create("Cuaderno", null, Paper.GRID, "2026-11-01T10:00:00Z", "nb-1")
        val doc = Document(NotebookFile.read(file))
        doc.add(0, StrokeBuilder(Tool.PEN, 0, 1f).apply { add(10f, 10f, 0.5f, 0f); add(20f, 30f, 0.5f, 4f) }.build())
        NotebookFile.write(file, doc.notebook("2026-11-01T11:00:00Z"))
        lib.last = file

        assertEquals("the blank page at the end is not counted", 1, lib.notebooks().single().pages)
        val moved = lib.move(file, "Circuitos")
        assertFalse(file.exists())
        assertEquals(moved, lib.last)  // the last one opened follows it
        lib.rename(moved, "  Teoría · Unidad 3 ")
        val back = NotebookFile.read(moved)
        assertEquals("Teoría · Unidad 3", back.title)
        assertEquals(doc.pages.map { it.strokes }, back.pages.map { it.strokes })
        assertEquals(listOf("Teoría · Unidad 3"), lib.notebooks("Circuitos").map { it.title })
    }

    @Test fun losingTheIndexLosesNoNotebook() {
        val lib = library()
        lib.addSubject("Circuitos", 5)
        lib.create("Uno", "Circuitos", Paper.GRID, "2026-11-01T10:00:00Z", "nb-1")
        File(lib.root, Library.INDEX).writeText("{ not json")
        assertEquals(listOf("Circuitos"), lib.subjects().map { it.name })
        assertEquals(0, lib.subjects().single().color)  // only the colour is gone
        assertEquals(listOf("Uno"), lib.notebooks().map { it.title })
        assertNull(lib.last)
    }

    @Test fun anUnreadableNotebookIsListedNotHidden() {
        val lib = library()
        lib.root.mkdirs()
        File(lib.root, "roto.cuaderno").writeText("not a zip")
        val entry = lib.notebooks().single()
        assertEquals("roto", entry.title)
        assertNotNull(entry.problem)
        assertTrue(entry.file.exists())
    }

    @Test fun theLastOneOpenedIsRememberedWhileItExists() {
        val lib = library()
        val file = lib.create("Uno", null, Paper.GRID, "2026-11-01T10:00:00Z", "nb-1")
        assertNull(lib.last)
        lib.last = file
        assertEquals(file.canonicalFile, lib.last!!.canonicalFile)
        assertEquals(file.canonicalFile, Library(lib.root).last!!.canonicalFile)
        file.delete()
        assertNull(lib.last)
    }
}
