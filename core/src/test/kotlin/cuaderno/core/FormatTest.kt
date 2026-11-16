package cuaderno.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The file format: what is saved comes back the same, old files keep opening, a failed save loses nothing. */
class FormatTest {
    @get:Rule val folder = TemporaryFolder()

    private fun stroke(tool: Tool = Tool.PEN, tilt: Boolean = true): Stroke {
        val builder = StrokeBuilder(tool, 0xFFECEFF4.toInt(), 1.2f)
        for (i in 0..20) {
            builder.add(100f + i * 3.5f, 200f + i * i * 0.25f, 0.2f + i * 0.03f, i * 4.2f,
                if (tilt) 0.3f else 0f, if (tilt) 1.1f else 0f)
        }
        return builder.build()
    }

    private fun sample() = Notebook(
        id = "nb-1", title = "Teoría de Circuitos II", created = "2026-11-16T18:00:00-03:00",
        modified = "2026-11-16T19:30:00-03:00",
        pages = listOf(
            Page("p1", 595f, 842f, Paper.GRID, listOf(stroke(), stroke(Tool.HIGHLIGHTER, tilt = false))),
            Page("p2", 595f, 842f, Paper.BLANK, emptyList()),
        ),
        attachments = mapOf("guia.pdf" to byteArrayOf(1, 2, 3)),
    )

    private fun assertSame(expected: Notebook, actual: Notebook) {
        assertEquals(expected.id, actual.id)
        assertEquals(expected.title, actual.title)
        assertEquals(expected.created, actual.created)
        assertEquals(expected.modified, actual.modified)
        assertEquals(expected.pages.map { it.id }, actual.pages.map { it.id })
        for ((a, b) in expected.pages.zip(actual.pages)) {
            assertEquals(a.width, b.width); assertEquals(a.height, b.height)
            assertEquals(a.paper, b.paper)
            assertEquals(a.strokes, b.strokes)
        }
        assertEquals(expected.attachments.keys, actual.attachments.keys)
        for ((name, bytes) in expected.attachments) assertArrayEquals(bytes, actual.attachments[name])
    }

    @Test fun strokesComeBackBitForBit() {
        val strokes = listOf(stroke(), stroke(Tool.HIGHLIGHTER, tilt = false))
        assertEquals(strokes, StrokeCodec.decode(StrokeCodec.encode(strokes)))
        assertEquals(emptyList<Stroke>(), StrokeCodec.decode(StrokeCodec.encode(emptyList())))
    }

    @Test fun channelsThatAreAllZeroAreNotStored() {
        val withTilt = StrokeCodec.encode(listOf(stroke(tilt = true))).size
        val without = StrokeCodec.encode(listOf(stroke(tilt = false))).size
        assertEquals(21 * 2 * 4, withTilt - without)
    }

    @Test fun aDamagedStrokesEntryIsRefusedNotHalfRead() {
        val bytes = StrokeCodec.encode(listOf(stroke()))
        assertThrows(FormatException::class.java) { StrokeCodec.decode(bytes.copyOf(bytes.size - 5)) }
        assertThrows(FormatException::class.java) { StrokeCodec.decode(bytes + byteArrayOf(0)) }
        assertThrows(FormatException::class.java) { StrokeCodec.decode("nope nope nope".toByteArray()) }
    }

    @Test fun aNotebookComesBackTheSame() {
        val file = folder.newFile("a.cuaderno")
        NotebookFile.write(file, sample())
        assertSame(sample(), NotebookFile.read(file))
        assertFalse(File(file.path + ".tmp").exists())
    }

    /** The file saved by format 1 is kept in the repo; every later version must still open it. */
    @Test fun theNotebookSavedByFormat1StillOpens() {
        val target = System.getProperty("cuaderno.fixture").orEmpty()
        if (target.isNotEmpty()) NotebookFile.write(File(target), sample())
        val file = folder.newFile("v1.cuaderno")
        javaClass.getResourceAsStream("/v1.cuaderno")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        assertSame(sample(), NotebookFile.read(file))
    }

    @Test fun aSaveThatFailsLeavesThePreviousFileWhole() {
        val file = folder.newFile("a.cuaderno")
        NotebookFile.write(file, sample())
        val before = file.readBytes()
        // The temporary file cannot be created: the save dies before the swap.
        File(file.path + ".tmp").mkdir()
        File(file.path + ".tmp", "in-the-way").writeText("x")
        assertThrows(Exception::class.java) {
            NotebookFile.write(file, Notebook("nb-1", "otro", "x", "y", emptyList()))
        }
        assertArrayEquals(before, file.readBytes())
        assertSame(sample(), NotebookFile.read(file))
    }

    @Test fun aNotebookFromANewerAppIsRefused() {
        val file = folder.newFile("a.cuaderno")
        NotebookFile.write(file, sample())
        val newer = folder.newFile("b.cuaderno")
        java.util.zip.ZipOutputStream(newer.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zip.write("""{"format":2,"id":"x","title":"x","created":"","modified":"","pages":[]}""".toByteArray())
            zip.closeEntry()
        }
        val error = assertThrows(FormatException::class.java) { NotebookFile.read(newer) }
        assertTrue(error.message!!.contains("format 2"))
        assertThrows(FormatException::class.java) { NotebookFile.read(folder.newFile("empty.cuaderno")) }
    }

    @Test fun pageIdsCannotEscapeTheFile() {
        val file = folder.newFile("a.cuaderno")
        val bad = Notebook("n", "t", "", "", listOf(Page("../x", 1f, 1f, Paper.BLANK, emptyList())))
        assertThrows(IllegalArgumentException::class.java) { NotebookFile.write(file, bad) }
    }

    @Test fun distanceToAStrokeIsToItsSegmentsNotItsSamples() {
        val builder = StrokeBuilder(Tool.PEN, 0, 1f)
        builder.add(0f, 0f, 0.5f, 0f); builder.add(100f, 0f, 0.5f, 10f)
        val line = builder.build()
        assertEquals(5f, line.distanceTo(50f, 5f), 0.001f)
        assertEquals(10f, line.distanceTo(110f, 0f), 0.001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 100f, 0f), line.bounds, 0f)
    }

    @Test fun aPenRecordingComesBackTheSame() {
        val recording = Recording("SM X520", 2304, 1440, 2f, listOf(
            Recording.Sample('D', 0, 10.5f, 20.25f, 0.31f, 0.2f, 1.1f, 2, 0),
            Recording.Sample('M', 4, 12f, 21f, 0.4f, 0.2f, 1.1f, 2, 0),
            Recording.Sample('U', 9, 12f, 21f, 0f, 0.2f, 1.1f, 2, 0),
        ))
        val again = Recording.parse(recording.format())
        assertEquals("SM_X520", again.device)
        assertEquals(2304, again.width); assertEquals(2f, again.density)
        assertEquals(recording.format(), again.format())
        assertEquals(3, again.samples.size)
        assertThrows(FormatException::class.java) { Recording.parse("x,y\n1,2") }
    }
}
