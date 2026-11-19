package cuaderno.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * All the notebooks, by subject. A subject is a folder in [root] and a notebook is
 * a file in it (`<id>.cuaderno`); notebooks right in [root] have no subject. The
 * folders and files are the truth: moving a notebook to another subject moves its
 * file, and nothing else has to agree. `library.json` only adds what files cannot
 * say: each subject's colour, their order, and the notebook opened last. Lost or
 * unreadable, it is rebuilt from the folders and no notebook goes missing.
 */
class Library(val root: File) {
    class Subject(val name: String, val color: Int)

    /** A notebook as listed. [problem] is set when the file could not be read; it is still listed, never hidden. */
    class Entry(val file: File, val subject: String?, val title: String, val modified: String,
                val pages: Int, val cover: Page?, val problem: String? = null)

    private val index = File(root, INDEX)

    /** Subjects in the order they were made; folders found without an entry come after, colour 0 (the app picks one). */
    fun subjects(): List<Subject> {
        root.mkdirs()
        val known = readIndex().first
        val folders = root.listFiles { f -> f.isDirectory }?.map { it.name }?.toSet() ?: emptySet()
        val listed = known.filter { it.name in folders }
        val found = (folders - listed.map { it.name }.toSet()).sorted().map { Subject(it, 0) }
        return listed + found
    }

    /** The notebooks of [subject] ([NO_SUBJECT] for those without one, null for all), the latest changed first. */
    fun notebooks(subject: String? = null): List<Entry> {
        root.mkdirs()
        val places = when (subject) {
            null -> listOf<String?>(null) + subjects().map { it.name }
            NO_SUBJECT -> listOf(null)
            else -> listOf(subject)
        }
        return places.flatMap { place ->
            val dir = if (place == null) root else File(root, place)
            (dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) } ?: emptyArray()).map { entry(it, place) }
        }.sortedByDescending { it.modified }
    }

    private fun entry(file: File, subject: String?): Entry = try {
        val s = NotebookFile.summary(file)
        Entry(file, subject, s.title, s.modified, s.pages, s.first)
    } catch (e: FormatException) {
        // Listed by its file name so it can still be found; the date sorts it last.
        Entry(file, subject, file.name.removeSuffix(EXTENSION), "", 0, null, e.message ?: "unreadable")
    }

    /** A new subject (a folder). Its name is trimmed; it must not be empty, taken, or a path. */
    fun addSubject(name: String, color: Int): Subject {
        val clean = name.trim()
        require(valid(clean)) { "bad subject name '$name'" }
        require(!File(root, clean).exists()) { "subject '$clean' exists" }
        require(File(root, clean).mkdirs()) { "could not make '$clean'" }
        val (known, last) = readIndex()
        writeIndex(known.filter { it.name != clean } + Subject(clean, color), last)
        return Subject(clean, color)
    }

    /** Starts a notebook with one page of [paper], in [subject] (null for none). [now] is ISO-8601; the title is the caller's. */
    fun create(title: String, subject: String?, paper: Paper, now: String, id: String): File {
        val dir = if (subject == null) root else File(root, subject).also { require(it.isDirectory) { "no subject '$subject'" } }
        dir.mkdirs()
        val file = File(dir, id + EXTENSION)
        require(!file.exists()) { "notebook '$id' exists" }
        NotebookFile.write(file, Notebook(id, title.trim(), now, now, listOf(Page("p1", Page.A4_WIDTH, Page.A4_HEIGHT, paper, emptyList()))))
        return file
    }

    /**
     * A notebook made of a PDF ([bytes]), one page per PDF page, each the size [sizes] gives
     * (points, width and height), in [subject] (null for none). Reading the PDF's pages is the
     * caller's: this module does not read PDFs.
     */
    fun importPdf(title: String, subject: String?, bytes: ByteArray, sizes: List<Pair<Float, Float>>, now: String, id: String): File {
        require(sizes.isNotEmpty()) { "a PDF without pages" }
        val dir = if (subject == null) root else File(root, subject).also { require(it.isDirectory) { "no subject '$subject'" } }
        dir.mkdirs()
        val file = File(dir, id + EXTENSION)
        require(!file.exists()) { "notebook '$id' exists" }
        val name = PdfPage.nameFor(bytes)
        val pages = sizes.mapIndexed { i, (w, h) -> Page("p${i + 1}", w, h, Paper.BLANK, emptyList(), pdf = PdfPage(name, bytes, i)) }
        NotebookFile.write(file, Notebook(id, title.trim(), now, now, pages))
        return file
    }

    /** Gives the notebook in [file] a new title. The whole file is written again, atomically. */
    fun rename(file: File, title: String) {
        val n = NotebookFile.read(file)
        NotebookFile.write(file, Notebook(n.id, title.trim(), n.created, n.modified, n.pages, n.attachments))
    }

    /** Moves the notebook to [subject] (null for none). Returns where it is now. */
    fun move(file: File, subject: String?): File {
        val dir = if (subject == null) root else File(root, subject)
        require(dir.isDirectory) { "no subject '$subject'" }
        val target = File(dir, file.name)
        if (target.canonicalPath == file.canonicalPath) return file
        require(!target.exists()) { "'${target.name}' is already there" }
        Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        val (known, last) = readIndex()
        if (last == relative(file)) writeIndex(known, relative(target))
        return target
    }

    /** The notebook opened last, if it is still there. */
    var last: File?
        get() = readIndex().second?.let { File(root, it) }?.takeIf { it.isFile }
        set(value) {
            val (known, _) = readIndex()
            writeIndex(known, value?.let { relative(it) })
        }

    /** The subject a notebook file is in, or null. */
    fun subjectOf(file: File): String? = file.parentFile?.takeIf { it.canonicalPath != root.canonicalPath }?.name

    private fun relative(file: File) = file.canonicalFile.relativeTo(root.canonicalFile).path

    private fun valid(name: String) = name.isNotEmpty() && name.length <= 60 && !name.startsWith(".") &&
        name.none { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() } && name != INDEX

    // --- library.json -----------------------------------------------------------------

    private fun readIndex(): Pair<List<Subject>, String?> = try {
        if (!index.isFile) Pair(emptyList(), null) else {
            val json = Json.parseToJsonElement(index.readText()).jsonObject
            val subjects = json["subjects"]?.jsonArray?.map {
                val o = it.jsonObject
                Subject(o.getValue("name").jsonPrimitive.content, o.getValue("color").jsonPrimitive.int)
            } ?: emptyList()
            Pair(subjects, json["last"]?.jsonPrimitive?.content)
        }
    } catch (e: Exception) {
        Pair(emptyList(), null)  // only colours and order are lost: the folders still say everything else
    }

    private fun writeIndex(subjects: List<Subject>, last: String?) {
        root.mkdirs()
        val json = buildJsonObject {
            put("subjects", buildJsonArray {
                for (s in subjects) add(buildJsonObject { put("name", s.name); put("color", s.color) })
            })
            if (last != null) put("last", last)
        }
        val temp = File(root, "$INDEX.tmp")
        FileOutputStream(temp).use { it.write(json.toString().toByteArray()); it.fd.sync() }
        Files.move(temp.toPath(), index.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val EXTENSION = ".cuaderno"
        const val INDEX = "library.json"
        /** Asks [notebooks] for the notebooks that have no subject. */
        const val NO_SUBJECT = "\u0000none"
    }
}
