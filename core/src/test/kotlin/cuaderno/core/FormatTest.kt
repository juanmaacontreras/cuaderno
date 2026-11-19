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
            zip.write("""{"format":4,"id":"x","title":"x","created":"","modified":"","pages":[]}""".toByteArray())
            zip.closeEntry()
        }
        val error = assertThrows(FormatException::class.java) { NotebookFile.read(newer) }
        assertTrue(error.message!!.contains("format 4"))
        assertThrows(FormatException::class.java) { NotebookFile.read(folder.newFile("empty.cuaderno")) }
    }

    // --- format 2: pictures ----------------------------------------------------------

    private val photo = ByteArray(300) { (it * 7).toByte() }

    /** The sample with a picture on its first page, the same one again on its second. */
    private fun withPictures(): Notebook {
        val name = Picture.nameFor(photo, "jpg")
        val n = sample()
        val pages = listOf(
            n.pages[0].with(n.pages[0].strokes, listOf(Picture(name, photo, 50f, 60f, 300f, 200f))),
            n.pages[1].with(emptyList(), listOf(Picture(name, photo, 10f, 20f, 150f, 100f))))
        return Notebook(n.id, n.title, n.created, n.modified, pages, n.attachments)
    }

    private fun manifest(file: File) = java.util.zip.ZipFile(file).use { zip ->
        String(zip.getInputStream(zip.getEntry("manifest.json")).readBytes())
    }

    @Test fun picturesComeBackWhereTheyWereAndTheirFileIsKeptOnce() {
        val file = folder.newFile("p.cuaderno")
        NotebookFile.write(file, withPictures())
        assertTrue(manifest(file).contains("\"format\":2"))
        val names = java.util.zip.ZipFile(file).use { zip -> zip.entries().toList().map { it.name } }
        assertEquals("one file for the picture used twice", 1, names.count { it.startsWith("attachments/pic-") })
        val back = NotebookFile.read(file)
        assertSame(withPictures(), back)
        assertEquals(withPictures().pages.map { it.pictures }, back.pages.map { it.pictures })
        assertArrayEquals(photo, back.pages[1].pictures[0].bytes)
        assertEquals("pictures are not listed as attachments", setOf("guia.pdf"), back.attachments.keys)
        assertEquals("the cover keeps its picture", 1, NotebookFile.summary(file).first!!.pictures.size)
        assertEquals("a page with only a picture is not a blank end", 2, NotebookFile.summary(file).pages)
    }

    @Test fun aNotebookWithoutPicturesIsStillFormat1() {
        val file = folder.newFile("a.cuaderno")
        NotebookFile.write(file, sample())
        assertTrue("so an app before pictures still opens it", manifest(file).contains("\"format\":1"))
    }

    @Test fun aPictureWhoseFileIsMissingIsAnError() {
        val file = folder.newFile("broken.cuaderno")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zip.write(("""{"format":2,"id":"x","title":"x","created":"","modified":"","pages":[{"id":"p1","width":595,"height":842,""" +
                """"paper":"grid","strokes":"pages/p1.strokes","pictures":[{"file":"attachments/pic-0.jpg","x":0,"y":0,"width":1,"height":1}]}]}""").toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("pages/p1.strokes"))
            zip.write(StrokeCodec.encode(emptyList()))
            zip.closeEntry()
        }
        assertThrows(FormatException::class.java) { NotebookFile.read(file) }
    }

    @Test fun aVersion2NotebookStillOpens() {
        System.getenv("CUADERNO_FIXTURE")?.let { NotebookFile.write(File(it), withPictures()) }
        val file = folder.newFile("v2.cuaderno")
        javaClass.getResourceAsStream("/v2.cuaderno")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        val back = NotebookFile.read(file)
        assertSame(withPictures(), back)
        assertEquals(withPictures().pages.map { it.pictures }, back.pages.map { it.pictures })
    }

    // --- format 3: a PDF to write on --------------------------------------------------

    private val guide = "%PDF-1.4 a made-up handout".toByteArray() + ByteArray(500) { (it * 13).toByte() }

    /** Three PDF pages (the second Letter size, written on), then a blank page of the notebook's own. */
    private fun withPdf(): Notebook {
        val name = PdfPage.nameFor(guide)
        val n = sample()
        val pages = listOf(
            Page("p1", 595f, 842f, Paper.BLANK, emptyList(), pdf = PdfPage(name, guide, 0)),
            Page("p2", 612f, 792f, Paper.BLANK, n.pages[0].strokes, pdf = PdfPage(name, guide, 1)),
            Page("p3", 595f, 842f, Paper.BLANK, emptyList(), listOf(Picture(Picture.nameFor(photo, "jpg"), photo, 1f, 2f, 3f, 4f)), PdfPage(name, guide, 2)),
            Page("p4", 595f, 842f, Paper.GRID, emptyList()))
        return Notebook(n.id, n.title, n.created, n.modified, pages)
    }

    @Test fun aPdfComesBackOnItsPagesAndIsKeptOnce() {
        val file = folder.newFile("pdf.cuaderno")
        NotebookFile.write(file, withPdf())
        assertTrue(manifest(file).contains("\"format\":3"))
        val entries = java.util.zip.ZipFile(file).use { zip -> zip.entries().toList() }
        assertEquals("one file for the PDF's three pages", 1, entries.count { it.name.startsWith("attachments/pdf-") })
        assertEquals("stored, not compressed again", java.util.zip.ZipEntry.STORED, entries.first { it.name.startsWith("attachments/pdf-") }.method)
        val back = NotebookFile.read(file)
        assertSame(withPdf(), back)
        assertEquals(withPdf().pages.map { it.pdf }, back.pages.map { it.pdf })
        assertEquals(listOf(612f, 792f), listOf(back.pages[1].width, back.pages[1].height))
        assertArrayEquals(guide, back.pages[0].pdf!!.bytes)
        assertTrue("one array for every page, so it is opened once", back.pages[0].pdf!!.bytes === back.pages[2].pdf!!.bytes)
        assertEquals("pictures still come back beside it", 1, back.pages[2].pictures.size)
        assertEquals("the cover is the PDF's first page", 0, NotebookFile.summary(file).first!!.pdf!!.index)
        assertTrue(back.attachments.isEmpty())
    }

    @Test fun aPdfPageIsNeverABlankPage() {
        val page = withPdf().pages[0]
        assertFalse(page.isBlank)
        val file = folder.newFile("one.cuaderno")
        val n = withPdf()
        NotebookFile.write(file, Notebook(n.id, n.title, n.created, n.modified, n.pages.take(3)))
        assertEquals("a PDF page at the end is counted", 3, NotebookFile.summary(file).pages)
        val doc = Document(NotebookFile.read(file))
        assertEquals("and opened", 3, doc.pages.size)
        assertEquals("every copy of a page keeps its PDF", page.pdf, page.withId("x").withPaper(Paper.GRID).with(emptyList()).pdf)
    }

    @Test fun aPageAddedAfterAPdfPageIsBlankAndOfItsSize() {
        val n = withPdf()
        val doc = Document(n)
        doc.insertAfter(1)
        val added = doc.pages[2]
        assertEquals(null, added.pdf)
        assertEquals(listOf(612f, 792f), listOf(added.width, added.height))
    }

    @Test fun aPdfWhoseFileIsMissingOrPageIsWrongIsAnError() {
        for (pdf in listOf(""""file":"attachments/pdf-0.pdf","page":0""", """"file":"../x.pdf","page":0""")) {
            val file = folder.newFile()
            java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zip.write(("""{"format":3,"id":"x","title":"x","created":"","modified":"","pages":[{"id":"p1","width":595,"height":842,""" +
                    """"paper":"blank","strokes":"pages/p1.strokes","pdf":{$pdf}}]}""").toByteArray())
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("pages/p1.strokes"))
                zip.write(StrokeCodec.encode(emptyList()))
                zip.closeEntry()
            }
            assertThrows(FormatException::class.java) { NotebookFile.read(file) }
        }
        val n = withPdf()
        val bad = Notebook(n.id, n.title, n.created, n.modified, listOf(Page("p1", 1f, 1f, Paper.BLANK, emptyList(), pdf = PdfPage("x.pdf", guide, -1))))
        assertThrows(IllegalArgumentException::class.java) { NotebookFile.write(folder.newFile(), bad) }
    }

    @Test fun aVersion3NotebookStillOpens() {
        System.getenv("CUADERNO_FIXTURE_V3")?.let { NotebookFile.write(File(it), withPdf()) }
        val file = folder.newFile("v3.cuaderno")
        javaClass.getResourceAsStream("/v3.cuaderno")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        val back = NotebookFile.read(file)
        assertSame(withPdf(), back)
        assertEquals(withPdf().pages.map { it.pdf }, back.pages.map { it.pdf })
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
