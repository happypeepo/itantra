package org.itantra.app.speech

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads the files a language needs but doesn't have (normally its STT model), using the
 * manifest's "downloads" section (base_url + per-file asset name, size and SHA-256).
 *
 * Each file goes to "<name>.part", is checked against its exact size and SHA-256, and only then
 * renamed into place, so a failed, cancelled or tampered download never leaves a broken model.
 * Runs on the caller's thread: call it from a background thread.
 */
class ModelDownloader(private val root: File, manifestFile: File) {
    data class RemoteFile(val path: String, val url: String, val bytes: Long, val sha256: String)

    private val files: Map<String, RemoteFile>  // key: path relative to the models folder

    init {
        val d = JSONObject(manifestFile.readText(Charsets.UTF_8)).optJSONObject("downloads")
        files = if (d == null) emptyMap() else {
            val base = d.getString("base_url").let { if (it.endsWith("/")) it else "$it/" }
            val f = d.getJSONObject("files")
            f.keys().asSequence().associateWith { rel ->
                val o = f.getJSONObject(rel)
                RemoteFile(rel, base + o.getString("asset"), o.getLong("bytes"), o.getString("sha256").lowercase())
            }
        }
    }

    /** What to fetch so every path in [needed] exists: empty = nothing missing, null = something
     *  missing that can't be downloaded (copy it by cable instead). */
    fun plan(needed: List<String>): List<RemoteFile>? =
        needed.filter { it.isNotEmpty() && !File(it).isFile }.map { abs -> files[relative(abs)] ?: return null }

    fun freeBytes(): Long = root.apply { mkdirs() }.usableSpace

    /** Installed files among [needed] that can be downloaded again, so deleting them is reversible.
     *  Anything not in the downloads list (English STT, voices, VAD) is never offered for deletion. */
    fun removable(needed: List<String>): List<File> =
        needed.filter { it.isNotEmpty() && File(it).isFile && files.containsKey(relative(it)) }.map(::File)

    /** Deletes [targets] (and any leftover .part files); returns the bytes freed. */
    fun delete(targets: List<File>): Long = targets.sumOf { f ->
        File(f.path + ".part").delete()
        val size = f.length()
        if (f.delete()) size else 0L
    }

    /** Fetch everything in [plan]; progress(doneBytes, totalBytes). Throws IOException on any failure or cancel. */
    fun download(plan: List<RemoteFile>, cancelled: AtomicBoolean, progress: (Long, Long) -> Unit) {
        val total = plan.sumOf { it.bytes }
        var done = 0L
        for (rf in plan) {
            val dst = File(root, rf.path).apply { parentFile?.mkdirs() }
            val part = File(dst.path + ".part")
            try {
                val md = MessageDigest.getInstance("SHA-256")
                var got = 0L
                val conn = (URL(rf.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
                }
                try {
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("server answered ${conn.responseCode} for ${rf.path}")
                    conn.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                if (cancelled.get()) throw IOException("cancelled")
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n); md.update(buf, 0, n); got += n
                                if (got > rf.bytes) throw IOException("${rf.path} is larger than expected")
                                progress(done + got, total)
                            }
                        }
                    }
                } finally { conn.disconnect() }
                if (got != rf.bytes) throw IOException("${rf.path}: received $got of ${rf.bytes} bytes")
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (hex != rf.sha256) throw IOException("${rf.path}: checksum mismatch, file rejected")
                if (dst.exists()) dst.delete()
                if (!part.renameTo(dst)) throw IOException("could not save ${rf.path}")
                done += got
            } catch (e: Exception) {
                part.delete()
                throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName, e)
            }
        }
    }

    private fun relative(abs: String) = File(abs).relativeTo(root).invariantSeparatorsPath
}
