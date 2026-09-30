package org.itantra.app

import org.itantra.app.speech.ModelDownloader
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class ModelDownloaderTest {
    private lateinit var server: ServerSocket
    private lateinit var root: File
    private val body = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        root = Files.createTempDirectory("models").toFile()
        // Minimal HTTP/1.0 server: /ok.bin serves the full body, /short.bin only its first 1000 bytes.
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: IOException) { break }
                thread(isDaemon = true) {
                    s.use { c ->
                        val line = c.getInputStream().bufferedReader().readLine() ?: return@use
                        val data = if (line.contains("/short.bin")) body.copyOf(1000) else body
                        c.getOutputStream().apply {
                            write("HTTP/1.0 200 OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(data); flush()
                        }
                    }
                }
            }
        }
    }

    @After fun tearDown() { server.close(); root.deleteRecursively() }

    private fun downloader(asset: String, sha256: String = sha, bytes: Long = body.size.toLong()): ModelDownloader {
        val m = File(root, "manifest.json")
        m.writeText("""{"downloads": {"base_url": "http://127.0.0.1:${server.localPort}/",
            "files": {"stt/ta/model.int8.onnx": {"asset": "$asset", "bytes": $bytes, "sha256": "$sha256"}}}}""")
        return ModelDownloader(root, m)
    }

    private val target get() = File(root, "stt/ta/model.int8.onnx")

    @Test fun downloadsVerifiesAndInstalls() {
        val d = downloader("ok.bin")
        val plan = d.plan(listOf(target.path))!!
        assertEquals(1, plan.size)
        var last = 0L
        d.download(plan, AtomicBoolean(false)) { done, _ -> last = done }
        assertArrayEquals(body, target.readBytes())
        assertEquals(body.size.toLong(), last)
        assertFalse(File(target.path + ".part").exists())
        assertTrue(d.plan(listOf(target.path))!!.isEmpty())
    }

    @Test fun rejectsWrongChecksum() {
        val d = downloader("ok.bin", sha256 = "0".repeat(64))
        try { d.download(d.plan(listOf(target.path))!!, AtomicBoolean(false)) { _, _ -> }; fail("accepted a bad checksum") }
        catch (e: IOException) { assertTrue(e.message!!.contains("checksum")) }
        assertFalse(target.exists()); assertFalse(File(target.path + ".part").exists())
    }

    @Test fun rejectsTruncatedFile() {
        val d = downloader("short.bin")
        try { d.download(d.plan(listOf(target.path))!!, AtomicBoolean(false)) { _, _ -> }; fail("accepted a short file") }
        catch (e: IOException) { assertTrue(e.message!!.contains("received")) }
        assertFalse(target.exists())
    }

    @Test fun missingFileWithoutDownloadIsNotPlannable() {
        val d = downloader("ok.bin")
        assertNull(d.plan(listOf(File(root, "stt/gu/model.int8.onnx").path)))
    }

    @Test fun deletesOnlyReDownloadableFiles() {
        val d = downloader("ok.bin")
        d.download(d.plan(listOf(target.path))!!, AtomicBoolean(false)) { _, _ -> }
        val builtIn = File(root, "stt/en/model.int8.onnx").apply { parentFile!!.mkdirs(); writeText("not downloadable") }
        val removable = d.removable(listOf(target.path, builtIn.path, ""))
        assertEquals(listOf(target), removable)
        assertEquals(body.size.toLong(), d.delete(removable))
        assertFalse(target.exists()); assertTrue(builtIn.exists())
        assertEquals(1, d.plan(listOf(target.path))!!.size) // can be downloaded again
        assertTrue(d.removable(listOf(target.path)).isEmpty())
    }

    @Test fun cancelLeavesNothingBehind() {
        val d = downloader("ok.bin")
        try { d.download(d.plan(listOf(target.path))!!, AtomicBoolean(true)) { _, _ -> }; fail("ignored cancel") }
        catch (e: IOException) { assertEquals("cancelled", e.message) }
        assertFalse(target.exists()); assertFalse(File(target.path + ".part").exists())
    }
}
