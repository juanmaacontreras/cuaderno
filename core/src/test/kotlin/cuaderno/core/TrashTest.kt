package cuaderno.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Nothing deleted is lost for 30 days: notebooks and pages go to the trash and come back where they were. */
class TrashTest {
    @get:Rule val folder = TemporaryFolder()

    private val library by lazy { Library(File(folder.root, "notebooks")) }
    private val trash by lazy { Trash(File(folder.root, "trash"), library) }
    private val now = "2026-11-18T10:00:00-03:00"

    private fun line(y: Float) = StrokeBuilder(Tool.PEN, -1, 1f).apply { add(100f, y, 0.5f, 0f); add(300f, y, 0.5f, 10f) }.build()

    /** A notebook in Circuitos with three pages, told apart by the height of their line. */
    private fun written(): File {
        library.addSubject("Circuitos", 0)
        val file = library.create("Teoría", "Circuitos", Paper.GRID, now, "nb-1")
        val doc = Document(NotebookFile.read(file))
        doc.add(0, line(100f)); doc.addPage(); doc.add(1, line(200f)); doc.addPage(); doc.add(2, line(300f))
        NotebookFile.write(file, doc.notebook(now))
        return file
    }

    private fun lines(file: File) = NotebookFile.read(file).pages.map { it.strokes.firstOrNull()?.y(0)?.toInt() ?: 0 }

    @Test fun aNotebookGoesAndComesBackToItsSubject() {
        val file = written()
        val item = trash.notebook(file, now)
        assertFalse(file.exists())
        assertEquals(emptyList<Library.Entry>(), library.notebooks())
        assertEquals("the trash is not a subject", listOf("Circuitos"), library.subjects().map { it.name })
        assertEquals(Trash.Kind.NOTEBOOK, item.kind)
        assertEquals("Teoría", item.title)
        assertEquals("Circuitos", item.subject)
        assertEquals(100f, item.cover!!.strokes.single().y(0), 0.01f)

        val back = trash.restore(item)
        assertEquals(file.canonicalPath, back.canonicalPath)
        assertEquals(listOf(100, 200, 300), lines(back))
        assertTrue(trash.items().isEmpty())
    }

    @Test fun aNotebookWhoseSubjectIsGoneComesBackWithoutOne() {
        val file = written()
        val item = trash.notebook(file, now)
        File(library.root, "Circuitos").deleteRecursively()
        val back = trash.restore(item)
        assertNull(library.subjectOf(back))
        assertEquals(listOf(100, 200, 300), lines(back))
    }

    @Test fun aPageWithAPictureKeepsItThroughTheTrash() {
        val file = written()
        val doc = Document(NotebookFile.read(file))
        val bytes = ByteArray(64) { it.toByte() }
        doc.insert(1, Picture(Picture.nameFor(bytes, "jpg"), bytes, 10f, 20f, 100f, 50f))
        NotebookFile.write(file, doc.notebook(now))
        val sheet = doc.pages[1]
        val item = trash.page(file, "Teoría", 1, sheet, now)
        doc.remove(1)
        NotebookFile.write(file, doc.notebook(now))
        assertEquals(0, NotebookFile.read(file).pages.sumOf { it.pictures.size })
        val back = NotebookFile.read(trash.restore(item))
        val picture = back.pages[1].pictures.single()
        assertEquals(10f, picture.x); assertEquals(100f, picture.width)
        assertTrue(bytes.contentEquals(picture.bytes))
    }

    @Test fun aPageComesBackToItsPlace() {
        val file = written()
        val doc = Document(NotebookFile.read(file))
        val item = trash.page(file, doc.title, 1, doc.pages[1], now)
        doc.remove(1)
        NotebookFile.write(file, doc.notebook(now))
        assertEquals(listOf(100, 300), lines(file))
        assertEquals(Trash.Kind.PAGE, item.kind)
        assertEquals(1, item.page)

        assertEquals(file.canonicalPath, trash.restore(item).canonicalPath)
        assertEquals(listOf(100, 200, 300), lines(file))
        val ids = NotebookFile.read(file).pages.map { it.id }
        assertEquals("page ids stay unique", ids.size, ids.toSet().size)
        assertTrue(trash.items().isEmpty())
    }

    @Test fun aPageWhoseNotebookIsGoneBecomesANotebook() {
        val file = written()
        val doc = Document(NotebookFile.read(file))
        val page = trash.page(file, doc.title, 2, doc.pages[2], now)
        trash.notebook(file, now)
        val back = trash.restore(page)
        assertEquals("Circuitos", library.subjectOf(back))
        assertEquals("Teoría", NotebookFile.read(back).title)
        assertEquals(listOf(300), lines(back))
        assertEquals(1, trash.items().size)  // the notebook is still there
    }

    @Test fun after30DaysItIsGoneAndNotBefore() {
        val file = written()
        trash.notebook(file, "2026-10-20T10:00:00-03:00")
        assertEquals(2, trash.daysLeft(trash.items().single(), "2026-11-18T09:00:00-03:00"))  // 25 hours
        assertEquals(1, trash.daysLeft(trash.items().single(), "2026-11-18T11:00:00-03:00"))  // 23 hours
        assertEquals(30, trash.daysLeft(trash.items().single(), "2026-10-20T10:00:01-03:00"))  // just deleted
        assertEquals(0, trash.purge("2026-11-18T09:00:00-03:00"))
        assertEquals(1, trash.items().size)
        assertEquals(1, trash.purge("2026-11-19T10:00:00-03:00"))
        assertTrue(trash.items().isEmpty())
        assertEquals(0, trash.dir.listFiles()!!.size)
    }

    @Test fun aBrokenNoteIsSkippedNotThrownAway() {
        val file = written()
        val item = trash.notebook(file, now)
        File(trash.dir, item.stem + ".json").writeText("{ not json")
        assertTrue(trash.items().isEmpty())
        assertEquals(0, trash.purge("2027-01-01T00:00:00-03:00"))
        assertTrue(File(trash.dir, item.stem + Library.EXTENSION).exists())
    }

    @Test fun theOnlyPageIsNeverRemoved() {
        val doc = Document(Notebook("n", "t", "c", "m", emptyList()))
        doc.remove(0)
        assertEquals(1, doc.pages.size)
        assertFalse(doc.canUndo)
    }
}
