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

/** One page. [width] and [height] in page points (A4 is 595 x 842). */
class Page(
    val id: String,
    val width: Float,
    val height: Float,
    val paper: Paper,
    val strokes: List<Stroke>,
) {
    fun with(strokes: List<Stroke>) = Page(id, width, height, paper, strokes)

    companion object {
        const val A4_WIDTH = 595f
        const val A4_HEIGHT = 842f
    }
}

/**
 * A notebook as it is in memory. [created] and [modified] are ISO-8601 texts
 * the caller provides, so this module needs no clock. [attachments] are the
 * files that travel inside the notebook (a background PDF, images), by name.
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
    const val FORMAT = 1
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

        val manifest = buildJsonObject {
            put("format", FORMAT)
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
                for ((name, bytes) in notebook.attachments) {
                    zip.putNextEntry(ZipEntry("attachments/$name"))
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
                val pages = manifest.getValue("pages").jsonArray.map { element ->
                    val page: JsonObject = element.jsonObject
                    Page(
                        id = page.text("id"),
                        width = page.getValue("width").jsonPrimitive.float,
                        height = page.getValue("height").jsonPrimitive.float,
                        paper = Paper.of(page.text("paper")),
                        strokes = StrokeCodec.decode(bytes(page.text("strokes"))),
                    )
                }
                val attachments = LinkedHashMap<String, ByteArray>()
                for (entry in zip.entries()) {
                    if (!entry.isDirectory && entry.name.startsWith("attachments/")) {
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
                val first = pages.firstOrNull()?.jsonObject?.let { page ->
                    val strokes = zip.getEntry(page.text("strokes")) ?: throw FormatException("missing '${page.text("strokes")}'")
                    Page(page.text("id"), page.getValue("width").jsonPrimitive.float, page.getValue("height").jsonPrimitive.float,
                        Paper.of(page.text("paper")), StrokeCodec.decode(zip.getInputStream(strokes).use { it.readBytes() }))
                }
                // The blank page every notebook ends with (Document) is not a page written.
                val blankEnd = pages.size > 1 && pages.last().jsonObject.let { page ->
                    val strokes = zip.getEntry(page.text("strokes")) ?: throw FormatException("missing '${page.text("strokes")}'")
                    StrokeCodec.decode(zip.getInputStream(strokes).use { it.readBytes() }).isEmpty()
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
}
