package cuaderno.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime

/**
 * What was deleted, kept [DAYS] days so it can come back. Each item is a notebook file
 * (`<stem>.cuaderno`, format 1: a deleted page is kept as a notebook of that one page)
 * and what is needed to put it back (`<stem>.json`: where it was, when it went, its
 * title, and for a page its place). In its own folder, outside the notebooks, so it is
 * never taken for a subject. Nothing in here is ever read as a notebook to write on.
 */
class Trash(val dir: File, private val library: Library) {
    enum class Kind { NOTEBOOK, PAGE }

    /**
     * One thing in the trash. [from] is where it was, relative to the notebooks folder
     * (the notebook file, for a page too); [page] is the page's place (0 is the first).
     */
    class Item(val stem: String, val kind: Kind, val title: String, val from: String, val page: Int,
               val deleted: String, val cover: Page?) {
        /** The subject it was in, or null. */
        val subject: String? get() = from.substringBeforeLast('/', "").ifEmpty { null }
    }

    /** Everything in the trash, the last deleted first. An item whose files are broken is skipped, never thrown away. */
    fun items(): List<Item> {
        val notes = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return notes.mapNotNull { note ->
            try {
                val o = Json.parseToJsonElement(note.readText()).jsonObject
                val stem = note.name.removeSuffix(".json")
                val payload = File(dir, stem + Library.EXTENSION)
                if (!payload.isFile) return@mapNotNull null
                Item(stem, if (o.getValue("kind").jsonPrimitive.content == "page") Kind.PAGE else Kind.NOTEBOOK,
                    o.getValue("title").jsonPrimitive.content, o.getValue("from").jsonPrimitive.content,
                    o["page"]?.jsonPrimitive?.int ?: 0, o.getValue("deleted").jsonPrimitive.content,
                    runCatching { NotebookFile.summary(payload).first }.getOrNull())
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.deleted }
    }

    /** Sends the notebook in [file] to the trash. [now] is ISO-8601 (core has no clock). */
    fun notebook(file: File, now: String): Item {
        val title = runCatching { NotebookFile.summary(file).title }.getOrDefault(file.name.removeSuffix(Library.EXTENSION))
        val stem = fresh(now)
        dir.mkdirs()
        // The note first: a crash between the two leaves a note without a file, which is skipped.
        note(stem, Kind.NOTEBOOK, title, relative(file), 0, now)
        Files.move(file.toPath(), File(dir, stem + Library.EXTENSION).toPath(), StandardCopyOption.ATOMIC_MOVE)
        return items().first { it.stem == stem }
    }

    /**
     * Keeps [page], the [index]-th of the notebook in [file] (titled [title]), in the trash.
     * Taking it out of the notebook is the caller's, after this returns.
     */
    fun page(file: File, title: String, index: Int, page: Page, now: String): Item {
        val stem = fresh(now)
        dir.mkdirs()
        NotebookFile.write(File(dir, stem + Library.EXTENSION), Notebook(stem, title, now, now, listOf(page)))
        note(stem, Kind.PAGE, title, relative(file), index, now)
        return items().first { it.stem == stem }
    }

    /**
     * Puts [item] back and returns the notebook file it is in. A notebook goes back to its
     * subject (no subject if that one is gone); a page goes back to its place in its notebook,
     * or, if the notebook is gone too, becomes a notebook of its own beside where it was.
     */
    fun restore(item: Item): File {
        val payload = File(dir, item.stem + Library.EXTENSION)
        val subject = item.subject?.takeIf { File(library.root, it).isDirectory }
        val folder = if (subject == null) library.root else File(library.root, subject)
        val back: File
        if (item.kind == Kind.NOTEBOOK) {
            back = free(folder, item.from.substringAfterLast('/'))
            Files.move(payload.toPath(), back.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } else {
            val page = NotebookFile.read(payload).pages.single()
            val home = File(library.root, item.from)
            if (home.isFile) {
                val n = NotebookFile.read(home)
                val pages = n.pages.toMutableList()
                pages.add(item.page.coerceIn(0, pages.size), page.withId(freshPage(pages)))
                NotebookFile.write(home, Notebook(n.id, n.title, n.created, n.modified, pages, n.attachments))
                back = home
            } else {
                back = free(folder, "nb-" + item.stem + Library.EXTENSION)
                NotebookFile.write(back, Notebook(back.name.removeSuffix(Library.EXTENSION), item.title, item.deleted, item.deleted,
                    listOf(page.withId("p1"))))
            }
            payload.delete()
        }
        File(dir, item.stem + ".json").delete()
        return back
    }

    /** Deletes [item] for good. */
    fun forget(item: Item) {
        File(dir, item.stem + Library.EXTENSION).delete()
        File(dir, item.stem + ".json").delete()
    }

    /** Deletes for good what has been in the trash [DAYS] days or more by [now] (ISO-8601). Returns how many. */
    fun purge(now: String): Int {
        val limit = OffsetDateTime.parse(now).minusDays(DAYS.toLong())
        var gone = 0
        for (item in items()) {
            val deleted = runCatching { OffsetDateTime.parse(item.deleted) }.getOrNull() ?: continue
            if (!deleted.isAfter(limit)) { forget(item); gone++ }
        }
        return gone
    }

    /** Days [item] has left in the trash at [now], counting the one it is in (30 just deleted, 1 in its last day, 0 past it). */
    fun daysLeft(item: Item, now: String): Int = try {
        val end = OffsetDateTime.parse(item.deleted).plusDays(DAYS.toLong())
        val minutes = java.time.Duration.between(OffsetDateTime.parse(now), end).toMinutes()
        maxOf(0, ((minutes + 1439) / 1440).toInt())
    } catch (e: Exception) { DAYS }

    private fun note(stem: String, kind: Kind, title: String, from: String, page: Int, now: String) {
        val json = buildJsonObject {
            put("kind", if (kind == Kind.PAGE) "page" else "notebook")
            put("title", title)
            put("from", from)
            if (kind == Kind.PAGE) put("page", page)
            put("deleted", now)
        }
        val target = File(dir, "$stem.json")
        val temp = File(dir, "$stem.json.tmp")
        FileOutputStream(temp).use { it.write(json.toString().toByteArray()); it.fd.sync() }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun relative(file: File) = file.canonicalFile.relativeTo(library.root.canonicalFile).path

    private fun fresh(now: String): String {
        val base = "t" + now.filter { it.isDigit() }.take(14)
        var n = 1
        while (File(dir, "$base-$n.json").exists() || File(dir, "$base-$n${Library.EXTENSION}").exists()) n++
        return "$base-$n"
    }

    /** [name] in [folder], or with a number if that is taken. */
    private fun free(folder: File, name: String): File {
        folder.mkdirs()
        var file = File(folder, name)
        var n = 2
        while (file.exists()) { file = File(folder, name.removeSuffix(Library.EXTENSION) + "-$n" + Library.EXTENSION); n++ }
        return file
    }

    private fun freshPage(pages: List<Page>): String {
        var n = pages.size + 1
        while (pages.any { it.id == "p$n" }) n++
        return "p$n"
    }

    companion object {
        /** How long deleted things stay. */
        const val DAYS = 30
    }
}
