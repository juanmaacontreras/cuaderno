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
    fun move(page: Int, which: Collection<Int>, dx: Float, dy: Float) = move(page, Selection(which.toList()), dx, dy)

    /** Moves the strokes and pictures of [page] in [which]. Like the other [move]. */
    fun move(page: Int, which: Selection, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        edit(page, which, { it.moved(dx, dy) }) { it.moved(dx, dy) }
    }

    /** Makes the strokes of [page] at [which] [k] times as big around [ax], [ay]. Like [move]. */
    fun scale(page: Int, which: Collection<Int>, ax: Float, ay: Float, k: Float) = scale(page, Selection(which.toList()), ax, ay, k)

    fun scale(page: Int, which: Selection, ax: Float, ay: Float, k: Float) {
        if (k == 1f) return
        edit(page, which, { it.scaled(ax, ay, k) }) { it.scaled(ax, ay, k) }
    }

    /** Gives the strokes of [page] at [which] [color]. Like [move]. */
    fun recolor(page: Int, which: Collection<Int>, color: Int) {
        val at = pages.getOrNull(page) ?: return
        if (which.all { at.strokes.getOrNull(it)?.color ?: color == color }) return
        edit(page, Selection(which.toList()), { if (it.color == color) it else it.recolored(color) }) { it }
    }

    /** Puts [strokes] on [page], on top of what is there. One undo step. Returns their indices. */
    fun paste(page: Int, strokes: List<Stroke>): List<Int> = paste(page, strokes, emptyList()).strokes

    /** Puts [strokes] and [pictures] on [page], on top of what is there. One undo step. Returns where they went. */
    fun paste(page: Int, strokes: List<Stroke>, pictures: List<Picture>): Selection {
        val at = pages.getOrNull(page) ?: return Selection(emptyList())
        if (strokes.isEmpty() && pictures.isEmpty()) return Selection(emptyList())
        change(pages.mapIndexed { i, p -> if (i == page) p.with(p.strokes + strokes, p.pictures + pictures) else p }, merge = false)
        return Selection((at.strokes.size until at.strokes.size + strokes.size).toList(),
            (at.pictures.size until at.pictures.size + pictures.size).toList())
    }

    /** Each stroke and picture of [page] in [which] replaced by [f] or [g] of it, in its place. One undo step. */
    private fun edit(page: Int, which: Selection, f: (Stroke) -> Stroke, g: (Picture) -> Picture) {
        val at = pages.getOrNull(page) ?: return
        if (which.isEmpty) return
        val set = which.strokes.toHashSet()
        val pics = which.pictures.toHashSet()
        val edited = at.strokes.mapIndexed { i, s -> if (i in set) f(s) else s }
        val placed = at.pictures.mapIndexed { i, p -> if (i in pics) g(p) else p }
        change(pages.mapIndexed { i, p -> if (i == page) p.with(edited, placed) else p }, merge = false)
    }

    /** Takes the strokes of [page] at [which] (indices) away. One undo step. */
    fun remove(page: Int, which: Collection<Int>) = remove(page, Selection(which.toList()))

    /** Takes the strokes and pictures of [page] in [which] away. One undo step. */
    fun remove(page: Int, which: Selection) {
        val at = pages.getOrNull(page) ?: return
        val set = which.strokes.toHashSet()
        val pics = which.pictures.toHashSet()
        val kept = at.strokes.filterIndexed { i, _ -> i !in set }
        val placed = at.pictures.filterIndexed { i, _ -> i !in pics }
        if (kept.size == at.strokes.size && placed.size == at.pictures.size) return
        change(pages.mapIndexed { i, p -> if (i == page) p.with(kept, placed) else p }, merge = false)
    }

    /** Puts [picture] on [page], over the others. One undo step. Returns its index among the page's pictures. */
    fun insert(page: Int, picture: Picture): Int = paste(page, emptyList(), listOf(picture)).pictures.firstOrNull() ?: -1

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
        change(pages.toMutableList().apply { add(page + 1, at.withId(freshId(pages))) }, merge = false)
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
        change(pages.mapIndexed { i, p -> if (i == page) p.withPaper(paper) else p }, merge = false)
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
        if (list.size > 1 && list.last().isBlank) return list.dropLast(1)
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
