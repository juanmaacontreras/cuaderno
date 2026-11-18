package cuaderno.core

/**
 * A notebook while it is open: its pages, and what editing does to them.
 * Every change replaces [pages] with a new list (pages and strokes never
 * change in place), so another thread can draw or save the list it holds
 * while writing goes on, and undo is just the previous list.
 *
 * The notebook always ends with an empty page: writing on the last one adds
 * the next, so there is no "new page" step in the middle of a class.
 */
class Document(notebook: Notebook, private val paper: Paper = Paper.GRID) {
    val id = notebook.id
    val title = notebook.title
    val created = notebook.created
    private val attachments = notebook.attachments

    @Volatile var pages: List<Page> = withBlankEnd(notebook.pages)
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
        pages = withBlankEnd(next)
        revision++
    }

    private fun withBlankEnd(list: List<Page>): List<Page> {
        if (list.isNotEmpty() && list.last().strokes.isEmpty()) return list
        return list + Page(freshId(list), Page.A4_WIDTH, Page.A4_HEIGHT, list.lastOrNull()?.paper ?: paper, emptyList())
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
