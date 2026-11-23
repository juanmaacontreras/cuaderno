package com.juanma.kit

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread

class UpdaterTest {
    @Test fun aReleaseWithAnApkIsOffered() {
        val release = Updater.parse("""{"tag_name":"v12","assets":[
            {"name":"notes.txt","browser_download_url":"https://example.com/notes.txt"},
            {"name":"cuaderno.apk","browser_download_url":"https://example.com/cuaderno.apk"}]}""")!!
        assertEquals(12L, release.version)
        assertEquals("https://example.com/cuaderno.apk", release.apkUrl)
    }

    @Test fun anythingElseIsNot() {
        assertNull(Updater.parse("not json"))
        assertNull(Updater.parse("""{"tag_name":"nightly","assets":[]}"""))
        assertNull(Updater.parse("""{"tag_name":"v3","assets":[{"name":"a.apk","browser_download_url":"http://plain"}]}"""))
    }
}

/** The download against a server on this machine: no network, no device. */
class UpdaterDownloadTest {
    private val apk = ByteArray(300_000) { (it * 31).toByte() }
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val dir = Files.createTempDirectory("updater").toFile()
    private val target = File(dir, "update-v9.apk")

    @Before fun start() {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                socket.use { answer(it.getInputStream().bufferedReader().readLine().split(" ")[1], it.getOutputStream()) }
            }
        }
    }

    private fun answer(path: String, out: OutputStream) {
        fun head(status: String, vararg lines: String) =
            out.write(("HTTP/1.1 $status\r\nConnection: close\r\n" + lines.joinToString("") { "$it\r\n" } + "\r\n").toByteArray())
        when (path) {
            "/apk" -> {
                head("200 OK", "Content-Length: ${apk.size}")
                for (at in apk.indices step 10_000) { out.write(apk, at, minOf(10_000, apk.size - at)); out.flush() }
            }
            // GitHub answers the asset's address with a redirect to where the file is.
            "/moved" -> head("302 Found", "Location: ${url("/apk")}", "Content-Length: 0")
            // Says the whole size, sends a third and hangs up: a dropped connection.
            "/cut" -> {
                head("200 OK", "Content-Length: ${apk.size}")
                out.write(apk, 0, apk.size / 3)
            }
            else -> head("404 Not Found", "Content-Length: 0")
        }
        out.flush()
    }

    @After fun stop() {
        server.close()
        dir.deleteRecursively()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"

    @Test fun downloadsWholeReportingEachPercentUpTo100() {
        val seen = mutableListOf<Int>()
        Updater.download(url("/apk"), target) { seen += it }
        assertArrayEquals(apk, target.readBytes())
        assertEquals(seen.sorted(), seen)
        assertEquals(seen.distinct(), seen)
        assertEquals(100, seen.last())
        assertTrue("progress before the end, not one jump: $seen", seen.first() < 100)
        assertFalse(File(target.path + ".part").exists())
    }

    @Test fun followsTheRedirect() {
        Updater.download(url("/moved"), target) {}
        assertArrayEquals(apk, target.readBytes())
    }

    @Test fun aCutDownloadLeavesNoFile() {
        assertThrows(IOException::class.java) { Updater.download(url("/cut"), target) {} }
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test fun anErrorStatusFails() {
        assertThrows(IOException::class.java) { Updater.download(url("/gone"), target) {} }
        assertFalse(target.exists())
    }
}
