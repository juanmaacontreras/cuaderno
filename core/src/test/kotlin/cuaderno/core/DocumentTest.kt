package cuaderno.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Editing a notebook: pages grow by themselves, undo and redo, the eraser. */
class DocumentTest {
    private fun line(y: Float): Stroke {
        val b = StrokeBuilder(Tool.PEN, -1, 1f)
        b.add(100f, y, 0.5f, 0f); b.add(300f, y, 0.5f, 10f)
        return b.build()
    }

    private fun empty() = Document(Notebook("n", "t", "c", "m", emptyList()))

    @Test fun aNewNotebookOpensOnOneBlankA4Page() {
        val doc = empty()
        assertEquals(1, doc.pages.size)
        assertEquals(595f, doc.pages[0].width); assertEquals(842f, doc.pages[0].height)
        assertFalse(doc.canUndo)
    }

    @Test fun writingNeverAddsAPageAskingDoes() {
        val doc = empty()
        doc.add(0, line(100f))
        assertEquals(listOf("p1"), doc.pages.map { it.id })
        doc.paper(0, Paper.RULED)
        doc.addPage()
        assertEquals(listOf("p1", "p2"), doc.pages.map { it.id })
        assertEquals("like the last one", Paper.RULED, doc.pages[1].paper)
        doc.undo()
        assertEquals(1, doc.pages.size)
    }

    @Test fun theBlankPageThatWritingUsedToAddIsNotOpened() {
        val blank = Page("p2", Page.A4_WIDTH, Page.A4_HEIGHT, Paper.GRID, emptyList())
        val written = empty().apply { add(0, line(100f)) }.pages.single()
        assertEquals(listOf("p1"), Document(Notebook("n", "t", "c", "m", listOf(written, blank))).pages.map { it.id })
        // A notebook with nothing written still opens on its one page.
        assertEquals(1, Document(Notebook("n", "t", "c", "m", listOf(blank))).pages.size)
    }

    @Test fun undoAndRedoWalkTheChanges() {
        val doc = empty()
        doc.add(0, line(100f)); doc.add(0, line(200f))
        doc.undo()
        assertEquals(1, doc.pages[0].strokes.size)
        assertTrue(doc.canRedo)
        doc.redo()
        assertEquals(2, doc.pages[0].strokes.size)
        doc.undo(); doc.undo()
        assertEquals(1, doc.pages.size)
        assertFalse(doc.canUndo)
        doc.add(0, line(300f))
        assertFalse("a new change drops what was undone", doc.canRedo)
    }

    @Test fun oneRubbingGestureIsOneUndoStep() {
        val doc = empty()
        doc.add(0, line(100f)); doc.add(0, line(120f)); doc.add(0, line(400f))
        assertFalse(doc.erase(0, 200f, 250f, 5f, merge = false))
        assertTrue(doc.erase(0, 200f, 102f, 5f, merge = false))
        assertTrue(doc.erase(0, 200f, 118f, 5f, merge = true))
        assertEquals(1, doc.pages[0].strokes.size)
        doc.undo()
        assertEquals(3, doc.pages[0].strokes.size)
    }

    @Test fun everyChangeMovesTheRevisionAndSavesWhatIsThere() {
        val doc = empty()
        val before = doc.revision
        doc.add(0, line(100f))
        assertTrue(doc.revision > before)
        val saved = doc.notebook("now")
        assertEquals("now", saved.modified); assertEquals("c", saved.created)
        assertEquals(1, saved.pages.size)
        assertEquals(1, Document(saved).pages.size)
    }

    /** Three written pages, a, b and c by the height of their one line. */
    private fun three(): Document {
        val doc = empty()
        doc.add(0, line(100f)); doc.addPage(); doc.add(1, line(200f)); doc.addPage(); doc.add(2, line(300f))
        return doc
    }

    private fun Document.lines() = pages.map { p -> p.strokes.firstOrNull()?.y(0)?.toInt() ?: 0 }

    @Test fun pagesAreAddedDuplicatedMovedAndRepaperedAndEachIsOneUndo() {
        val doc = three()
        assertEquals(listOf(100, 200, 300), doc.lines())

        doc.insertAfter(0)
        assertEquals(listOf(100, 0, 200, 300), doc.lines())
        assertEquals("ids stay unique", doc.pages.size, doc.pages.map { it.id }.toSet().size)
        doc.undo()
        assertEquals(listOf(100, 200, 300), doc.lines())

        doc.duplicate(1)
        assertEquals(listOf(100, 200, 200, 300), doc.lines())
        assertTrue(doc.pages[1].strokes == doc.pages[2].strokes)
        assertEquals(doc.pages.size, doc.pages.map { it.id }.toSet().size)
        doc.undo()

        doc.move(2, 0)
        assertEquals(listOf(300, 100, 200), doc.lines())
        doc.move(0, 2)
        assertEquals(listOf(100, 200, 300), doc.lines())
        doc.undo(); doc.undo()
        assertEquals(listOf(100, 200, 300), doc.lines())

        doc.paper(1, Paper.RULED)
        assertEquals(Paper.RULED, doc.pages[1].paper)
        assertEquals(listOf(100, 200, 300), doc.lines())
        doc.undo()
        assertEquals(Paper.GRID, doc.pages[1].paper)
    }


}
