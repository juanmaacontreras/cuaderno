package cuaderno.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class Paper(val code: String) {
    BLANK("blank"), RULED("ruled"), GRID("grid"), DOTTED("dotted");

    companion object {
        fun of(code: String): Paper = entries.firstOrNull { it.code == code }
            ?: throw FormatException("unknown paper '$code'")
    }
}

/**
 * A picture on a page (a photo of the board, a screenshot): the image file's bytes, named by
 * their content ([nameFor]), and where it sits, in page points. Under the ink, over the paper.
 * Immutable; the bytes are never changed once read.
 */
class Picture(val name: String, val bytes: ByteArray, val x: Float, val y: Float, val width: Float, val height: Float) {
    fun moved(dx: Float, dy: Float) = Picture(name, bytes, x + dx, y + dy, width, height)

    /** [k] times as big around [ax], [ay], like [Stroke.scaled]. */
    fun scaled(ax: Float, ay: Float, k: Float) = Picture(name, bytes, ax + (x - ax) * k, ay + (y - ay) * k, width * k, height * k)

    override fun equals(other: Any?) = other is Picture && name == other.name && x == other.x && y == other.y &&
        width == other.width && height == other.height

    override fun hashCode() = 31 * (31 * name.hashCode() + x.hashCode()) + y.hashCode()

    companion object {
        /** A name from the bytes themselves: the same image is one file however many times it is used. */
        fun nameFor(bytes: ByteArray, extension: String) = contentName("pic-", bytes, extension)
    }
}

/** A file name from [bytes] themselves: [prefix], 20 hex digits of their SHA-256, the extension. */
internal fun contentName(prefix: String, bytes: ByteArray, extension: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    return prefix + digest.take(10).joinToString("") { "%02x".format(it) } + "." + extension
}

/**
 * The page of an imported PDF that a page is written on: the whole PDF file's bytes (every
 * page of it shares the same array), named by their content, and which page, from 0. The
 * sheet has that page's size; the PDF is drawn on it, under everything else.
 */
class PdfPage(val name: String, val bytes: ByteArray, val index: Int) {
    override fun equals(other: Any?) = other is PdfPage && name == other.name && index == other.index
    override fun hashCode() = 31 * name.hashCode() + index

    companion object {
        fun nameFor(bytes: ByteArray) = contentName("pdf-", bytes, "pdf")
    }
}

/** One page. [width] and [height] in page points (A4 is 595 x 842). [pdf]: the PDF page it is written on, if any. */
class Page(
    val id: String,
    val width: Float,
    val height: Float,
    val paper: Paper,
    val strokes: List<Stroke>,
    val pictures: List<Picture> = emptyList(),
    val pdf: PdfPage? = null,
) {
    /** The same page with other strokes; everything else, pictures and PDF included, stays. */
    fun with(strokes: List<Stroke>) = Page(id, width, height, paper, strokes, pictures, pdf)
    fun with(strokes: List<Stroke>, pictures: List<Picture>) = Page(id, width, height, paper, strokes, pictures, pdf)
    fun withPaper(paper: Paper) = Page(id, width, height, paper, strokes, pictures, pdf)
    fun withId(id: String) = Page(id, width, height, paper, strokes, pictures, pdf)

    /** Nothing written, placed or printed on it. */
    val isBlank: Boolean get() = strokes.isEmpty() && pictures.isEmpty() && pdf == null

    companion object {
        const val A4_WIDTH = 595f
        const val A4_HEIGHT = 842f
    }
}

/**
 * A notebook as it is in memory. [created] and [modified] are ISO-8601 texts
 * the caller provides, so this module needs no clock. [attachments] are the
 * files that travel inside the notebook (a background PDF), by name; pictures
 * carry their own bytes ([Picture]) and are not listed here.
 */
class Notebook(
    val id: String,
    val title: String,
    val created: String,
    val modified: String,
    val pages: List<Page>,
    val attachments: Map<String, ByteArray> = emptyMap(),
)

/**
 * A notebook on disk: one zip file, described in docs/format.md. The file is
 * the truth; anything else the app keeps about it is a cache.
 */
object NotebookFile {
    /** The newest format this version reads and writes. Each file is written in the oldest format that holds it. */
    const val FORMAT = 3
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val ATTACHMENT = Regex("[A-Za-z0-9_.-]{1,128}")

    /**
     * Writes the whole notebook next to [file] and swaps it in with one
     * rename, so a crash or a full disk leaves the previous file untouched:
     * there is never a half-written notebook.
     */
    fun write(file: File, notebook: Notebook) {
        val seen = HashSet<String>()
        for (page in notebook.pages) {
            require(ID.matches(page.id) && seen.add(page.id)) { "bad or repeated page id '${page.id}'" }
        }
        for (name in notebook.attachments.keys) require(ATTACHMENT.matches(name)) { "bad attachment name '$name'" }
        // Pictures' files go with the rest of what travels inside, once each.
        val files = LinkedHashMap(notebook.attachments)
        for (page in notebook.pages) for (picture in page.pictures) {
            require(ATTACHMENT.matches(picture.name)) { "bad picture name '${picture.name}'" }
            files.putIfAbsent(picture.name, picture.bytes)
        }
        for (page in notebook.pages) page.pdf?.let { pdf ->
            require(ATTACHMENT.matches(pdf.name) && pdf.index >= 0) { "bad PDF '${pdf.name}' page ${pdf.index}" }
            files.putIfAbsent(pdf.name, pdf.bytes)
        }
        // Older readers know nothing of pictures (2) or PDFs (3): only a notebook with them needs the newer number.
        val format = when {
            notebook.pages.any { it.pdf != null } -> 3
            notebook.pages.any { it.pictures.isNotEmpty() } -> 2
            else -> 1
        }

        val manifest = buildJsonObject {
            put("format", format)
            put("app", "cuaderno")
            put("id", notebook.id)
            put("title", notebook.title)
            put("created", notebook.created)
            put("modified", notebook.modified)
            put("pages", buildJsonArray {
                for (page in notebook.pages) add(buildJsonObject {
                    put("id", page.id)
                    put("width", page.width)
                    put("height", page.height)
                    put("paper", page.paper.code)
                    put("strokes", "pages/${page.id}.strokes")
                    if (page.pictures.isNotEmpty()) put("pictures", buildJsonArray {
                        for (picture in page.pictures) add(buildJsonObject {
                            put("file", "attachments/${picture.name}")
                            put("x", picture.x); put("y", picture.y)
                            put("width", picture.width); put("height", picture.height)
                        })
                    })
                    page.pdf?.let { pdf -> put("pdf", buildJsonObject { put("file", "attachments/${pdf.name}"); put("page", pdf.index) }) }
                })
            })
        }

        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { stream ->
                val zip = ZipOutputStream(stream)
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray())
                zip.closeEntry()
                for (page in notebook.pages) {
                    zip.putNextEntry(ZipEntry("pages/${page.id}.strokes"))
                    zip.write(StrokeCodec.encode(page.strokes))
                    zip.closeEntry()
                }
                for ((name, bytes) in files) {
                    // Images and PDFs are compressed already: stored as they are, a big PDF costs
                    // a copy on every save instead of a compression that gains nothing.
                    zip.putNextEntry(ZipEntry("attachments/$name").apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        crc = java.util.zip.CRC32().also { it.update(bytes) }.value
                    })
                    zip.write(bytes)
                    zip.closeEntry()
                }
                zip.finish()
                stream.flush()
                stream.fd.sync()  // on the disk before it replaces the good copy
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    fun read(file: File): Notebook {
        try {
            ZipFile(file).use { zip ->
                fun bytes(name: String): ByteArray {
                    val entry = zip.getEntry(name) ?: throw FormatException("missing '$name'")
                    return zip.getInputStream(entry).use { it.readBytes() }
                }
                val manifest = Json.parseToJsonElement(String(bytes("manifest.json"))).jsonObject
                val format = manifest.getValue("format").jsonPrimitive.int
                // A newer file may hold things this version would drop on the next save.
                if (format > FORMAT) throw FormatException("notebook format $format, this app reads up to $FORMAT")
                // Read once each, shared by every picture that shows them.
                val files = HashMap<String, ByteArray>()
                val pages = manifest.getValue("pages").jsonArray.map { element ->
                    val page: JsonObject = element.jsonObject
                    Page(
                        id = page.text("id"),
                        width = page.getValue("width").jsonPrimitive.float,
                        height = page.getValue("height").jsonPrimitive.float,
                        paper = Paper.of(page.text("paper")),
                        strokes = StrokeCodec.decode(bytes(page.text("strokes"))),
                        pictures = pictures(page) { files.getOrPut(it) { bytes(it) } },
                        pdf = pdf(page) { files.getOrPut(it) { bytes(it) } },
                    )
                }
                val attachments = LinkedHashMap<String, ByteArray>()
                for (entry in zip.entries()) {
                    if (!entry.isDirectory && entry.name.startsWith("attachments/") && entry.name !in files) {
                        attachments[entry.name.removePrefix("attachments/")] = bytes(entry.name)
                    }
                }
                return Notebook(manifest.text("id"), manifest.text("title"), manifest.text("created"),
                    manifest.text("modified"), pages, attachments)
            }
        } catch (e: FormatException) {
            throw e
        } catch (e: java.io.FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            throw FormatException("unreadable notebook: ${e.message}")
        }
    }

    /**
     * What a list of notebooks shows, without reading every page: the title, when it changed,
     * how many pages are written (the blank one at the end left out), and the first page (the
     * cover). Fails like [read] on a bad file.
     */
    fun summary(file: File): Summary {
        try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: throw FormatException("missing 'manifest.json'")
                val manifest = Json.parseToJsonElement(String(zip.getInputStream(entry).use { it.readBytes() })).jsonObject
                val format = manifest.getValue("format").jsonPrimitive.int
                if (format > FORMAT) throw FormatException("notebook format $format, this app reads up to $FORMAT")
                val pages = manifest.getValue("pages").jsonArray
                fun bytes(name: String): ByteArray {
                    val e = zip.getEntry(name) ?: throw FormatException("missing '$name'")
                    return zip.getInputStream(e).use { it.readBytes() }
                }
                val first = pages.firstOrNull()?.jsonObject?.let { page ->
                    Page(page.text("id"), page.getValue("width").jsonPrimitive.float, page.getValue("height").jsonPrimitive.float,
                        Paper.of(page.text("paper")), StrokeCodec.decode(bytes(page.text("strokes"))), pictures(page) { bytes(it) },
                        pdf(page) { bytes(it) })
                }
                // A blank last page is not opened (Document), so it is not counted.
                val blankEnd = pages.size > 1 && pages.last().jsonObject.let { page ->
                    StrokeCodec.decode(bytes(page.text("strokes"))).isEmpty() && page["pictures"] == null && page["pdf"] == null
                }
                return Summary(manifest.text("title"), manifest.text("modified"), pages.size - if (blankEnd) 1 else 0, first)
            }
        } catch (e: FormatException) {
            throw e
        } catch (e: Exception) {
            throw FormatException("unreadable notebook: ${e.message}")
        }
    }

    class Summary(val title: String, val modified: String, val pages: Int, val first: Page?)

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    /** The PDF page a page is written on (format 3), or null. [file] gives an entry's bytes. */
    private fun pdf(page: JsonObject, file: (String) -> ByteArray): PdfPage? {
        val p = page["pdf"]?.jsonObject ?: return null
        val path = p.text("file")
        if (!path.startsWith("attachments/") || !ATTACHMENT.matches(path.removePrefix("attachments/"))) {
            throw FormatException("bad PDF file '$path'")
        }
        val index = p.getValue("page").jsonPrimitive.int
        if (index < 0) throw FormatException("bad PDF page $index")
        return PdfPage(path.removePrefix("attachments/"), file(path), index)
    }

    /** A page's pictures (format 2); none in format 1. [file] gives an entry's bytes. */
    private fun pictures(page: JsonObject, file: (String) -> ByteArray): List<Picture> {
        val list = page["pictures"]?.jsonArray ?: return emptyList()
        return list.map { element ->
            val p = element.jsonObject
            val path = p.text("file")
            if (!path.startsWith("attachments/") || !ATTACHMENT.matches(path.removePrefix("attachments/"))) {
                throw FormatException("bad picture file '$path'")
            }
            Picture(path.removePrefix("attachments/"), file(path), p.getValue("x").jsonPrimitive.float,
                p.getValue("y").jsonPrimitive.float, p.getValue("width").jsonPrimitive.float, p.getValue("height").jsonPrimitive.float)
        }
    }
}
