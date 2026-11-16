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

    @Test fun writingOnTheLastPageAddsTheNextOne() {
        val doc = empty()
        doc.add(0, line(100f))
        assertEquals(listOf("p1", "p2"), doc.pages.map { it.id })
        doc.add(0, line(200f))
        assertEquals(2, doc.pages.size)
        doc.add(1, line(100f))
        assertEquals(3, doc.pages.size)
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
        assertEquals(2, saved.pages.size)
        assertEquals(2, Document(saved).pages.size)
    }
}
