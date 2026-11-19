package cuaderno.core

/**
 * A notebook while it is open: its pages, and what editing does to them.
 * Every change replaces [pages] with a new list (pages and strokes never
 * change in place), so another thread can draw or save the list it holds
 * while writing goes on, and undo is just the previous list.
 *
 * Pages are added when he asks ([addPage], pulling past the last one; Juanma,
 * 2026-11-18): writing never adds one by itself.
 */
class Document(notebook: Notebook, private val paper: Paper = Paper.GRID) {
    val id = notebook.id
    val title = notebook.title
    val created = notebook.created
    private val attachments = notebook.attachments

    @Volatile var pages: List<Page> = opened(notebook.pages)
        private set

    /** Goes up with every change; whoever saves compares it to know if there is something new. */
    @Volatile var revision = 0L
        private set

    private val undone = ArrayList<List<Page>>()
    private val history = ArrayList<List<Page>>()

    val canUndo: Boolean get() = history.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    fun add(page: Int, stroke: Stroke) {
        change(pages.mapIndexed { i, p -> if (i == page) p.with(p.strokes + stroke) else p }, merge = false)
    }

    /**
     * Removes the strokes of [page] within [reach] of a point (page points).
     * [merge] joins this to the previous erase, so one rubbing gesture is one
     * undo step however many strokes it took. Returns whether anything went.
     */
    fun erase(page: Int, x: Float, y: Float, reach: Float, merge: Boolean): Boolean {
        val target = pages.getOrNull(page) ?: return false
        val kept = target.strokes.filterNot { stroke ->
            val b = stroke.bounds
            val r = reach + stroke.width / 2
            x >= b[0] - r && x <= b[2] + r && y >= b[1] - r && y <= b[3] + r && stroke.distanceTo(x, y) <= r
        }
        if (kept.size == target.strokes.size) return false
        change(pages.mapIndexed { i, p -> if (i == page) p.with(kept) else p }, merge)
        return true
    }

    /**
     * Moves the strokes of [page] at [which] (indices) by [dx], [dy] points. They keep their
     * place in the page's order, so a highlighter stays under the ink, and so [which] still
     * names them afterwards. One undo step.
     */
    fun move(page: Int, which: Collection<Int>, dx: Float, dy: Float) {
        val at = pages.getOrNull(page) ?: return
        if (which.isEmpty() || (dx == 0f && dy == 0f)) return
        val set = which.toHashSet()
        val moved = at.strokes.mapIndexed { i, s -> if (i in set) s.moved(dx, dy) else s }
        change(pages.mapIndexed { i, p -> if (i == page) p.with(moved) else p }, merge = false)
    }

    /** Takes the strokes of [page] at [which] (indices) away. One undo step. */
    fun remove(page: Int, which: Collection<Int>) {
        val at = pages.getOrNull(page) ?: return
        val set = which.toHashSet()
        val kept = at.strokes.filterIndexed { i, _ -> i !in set }
        if (kept.size == at.strokes.size) return
        change(pages.mapIndexed { i, p -> if (i == page) p.with(kept) else p }, merge = false)
    }

    /** A blank page at the end, like the last one. */
    fun addPage() = insertAfter(pages.size - 1)

    /** A blank page right after [page], with its paper. */
    fun insertAfter(page: Int) {
        val at = pages.getOrNull(page) ?: return
        change(pages.toMutableList().apply { add(page + 1, Page(freshId(pages), at.width, at.height, at.paper, emptyList())) }, merge = false)
    }

    /** A copy of [page], with everything written on it, right after it. */
    fun duplicate(page: Int) {
        val at = pages.getOrNull(page) ?: return
        change(pages.toMutableList().apply { add(page + 1, Page(freshId(pages), at.width, at.height, at.paper, at.strokes)) }, merge = false)
    }

    /** Takes [page] out (the trash keeps it: see [Trash.page]). The only page never goes. */
    fun remove(page: Int) {
        if (pages.size < 2 || page !in pages.indices) return
        change(pages.filterIndexed { i, _ -> i != page }, merge = false)
    }

    /** Changes the paper of [page]; what is written stays. */
    fun paper(page: Int, paper: Paper) {
        val at = pages.getOrNull(page) ?: return
        if (at.paper == paper) return
        change(pages.mapIndexed { i, p -> if (i == page) Page(p.id, p.width, p.height, paper, p.strokes) else p }, merge = false)
    }

    /** Moves page [from] to position [to] (both counted in the list as it is now). */
    fun move(from: Int, to: Int) {
        if (from == to || from !in pages.indices || to !in pages.indices) return
        change(pages.toMutableList().apply { add(to, removeAt(from)) }, merge = false)
    }

    fun undo() {
        if (history.isEmpty()) return
        undone.add(pages)
        pages = history.removeAt(history.size - 1)
        revision++
    }

    fun redo() {
        if (undone.isEmpty()) return
        history.add(pages)
        pages = undone.removeAt(undone.size - 1)
        revision++
    }

    /** What to save. [modified] is an ISO-8601 text from the caller: this module has no clock. */
    fun notebook(modified: String) = Notebook(id, title, created, modified, pages, attachments)

    private fun change(next: List<Page>, merge: Boolean) {
        if (!merge) {
            history.add(pages)
            if (history.size > HISTORY) history.removeAt(0)
        }
        undone.clear()
        pages = next
        revision++
    }

    /**
     * A notebook with nothing opens on one blank page. Until v22 writing kept a blank page at
     * the end by itself: that one is left out (it holds nothing), so it does not show as a
     * page he never asked for. A blank last page he adds now goes the same way when the
     * notebook is opened again.
     */
    private fun opened(list: List<Page>): List<Page> {
        if (list.isEmpty()) return listOf(Page("p1", Page.A4_WIDTH, Page.A4_HEIGHT, paper, emptyList()))
        if (list.size > 1 && list.last().strokes.isEmpty()) return list.dropLast(1)
        return list
    }

    private fun freshId(list: List<Page>): String {
        var n = list.size + 1
        while (list.any { it.id == "p$n" }) n++
        return "p$n"
    }

    private companion object {
        const val HISTORY = 200
    }
}
