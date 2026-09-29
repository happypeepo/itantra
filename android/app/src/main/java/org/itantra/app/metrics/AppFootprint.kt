package org.itantra.app.metrics

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import org.itantra.app.speech.Manifest
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * What the app occupies on this phone, split into its parts, plus live CPU and RAM.
 * All measuring runs on one background thread; callbacks arrive on that thread.
 */
class AppFootprint(private val context: Context, private val manifest: () -> Manifest?) {
    data class Part(val label: String, val bytes: Long)
    data class Usage(val cpuOfPhone: Double, val cpuOfOneCore: Double, val cores: Int,
                     val pssMb: Double, val javaHeapMb: Double, val nativeHeapMb: Double)

    private var timer: ScheduledExecutorService? = null
    private var lastCpuMs = Process.getElapsedCpuTime()
    private var lastWallMs = SystemClock.elapsedRealtime()

    /** Sizes on disk. Every file is counted once, even if two parts share it. */
    fun parts(): List<Part> {
        val seen = HashSet<String>()
        fun size(f: File): Long {
            if (!f.exists() || !seen.add(f.canonicalPath)) return 0
            return if (f.isDirectory) f.listFiles()?.sumOf { size(it) } ?: 0 else f.length()
        }
        val out = mutableListOf<Part>()
        val info = context.applicationInfo
        out += Part("App (APK)", (listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())).sumOf { File(it).length() })
        manifest()?.let { m ->
            val byWire = m.languages.values.sortedBy { it.wireId }
            byWire.forEach { l ->
                val b = listOf(l.stt.model, l.stt.tokens, l.stt.encoder, l.stt.decoder).filter { it.isNotEmpty() }.sumOf { size(File(it)) }
                if (b > 0) out += Part("Speech recognition · ${l.name}", b)
            }
            m.engines.values.sortedBy { e -> byWire.indexOfFirst { it.tts.engine == e.name } }.forEach { e ->
                val b = size(File(e.model)) + size(File(e.tokens))
                val users = byWire.filter { it.tts.engine == e.name }.joinToString(", ") { it.name }
                if (b > 0) out += Part("Voice · $users", b)
            }
            m.engines.values.map { it.dataDir }.filter { it.isNotEmpty() }.distinct().forEach { d ->
                val b = size(File(d)); if (b > 0) out += Part("Pronunciation data (Hindi, English)", b)
            }
            size(File(m.vadModel)).let { if (it > 0) out += Part("Voice activity detector", it) }
            size(m.root).let { if (it > 0) out += Part("Other model files", it) }  // manifest etc.: whatever wasn't counted above
        }
        context.getExternalFilesDir(null)?.let { ext ->
            size(File(ext, "alerts")).let { if (it > 0) out += Part("Alert sounds", it) }
            size(ext).let { if (it > 0) out += Part("Other app files", it) }
        }
        val data = listOf(context.filesDir, context.cacheDir, context.codeCacheDir, context.externalCacheDir)
            .filterNotNull().sumOf { size(it) }
        if (data > 0) out += Part("App data and cache", data)
        return out
    }

    /** CPU since the previous call, and memory right now. */
    fun usage(): Usage {
        val cpu = Process.getElapsedCpuTime(); val wall = SystemClock.elapsedRealtime()
        val oneCore = if (wall > lastWallMs) 100.0 * (cpu - lastCpuMs) / (wall - lastWallMs) else 0.0
        lastCpuMs = cpu; lastWallMs = wall
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val rt = Runtime.getRuntime(); val cores = rt.availableProcessors()
        return Usage(oneCore / cores, oneCore, cores, mi.totalPss / 1024.0,
            (rt.totalMemory() - rt.freeMemory()) / 1048576.0, Debug.getNativeHeapAllocatedSize() / 1048576.0)
    }

    /** Usage every 2 s, sizes every 30 s, until [stop]. */
    @Synchronized fun start(onUsage: (Usage) -> Unit, onParts: (List<Part>) -> Unit) {
        stop()
        timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "itantra-footprint").apply { isDaemon = true } }.also { t ->
            lastCpuMs = Process.getElapsedCpuTime(); lastWallMs = SystemClock.elapsedRealtime()
            t.scheduleWithFixedDelay({ runCatching { onUsage(usage()) } }, 2, 2, TimeUnit.SECONDS)
            t.scheduleWithFixedDelay({ runCatching { onParts(parts()) } }, 0, 30, TimeUnit.SECONDS)
        }
    }

    /** Re-measure sizes now (e.g. when the breakdown is opened). */
    @Synchronized fun refresh(onParts: (List<Part>) -> Unit) { timer?.execute { runCatching { onParts(parts()) } } }

    @Synchronized fun stop() { timer?.shutdownNow(); timer = null }

    companion object {
        fun mb(bytes: Long): String = when {
            bytes >= 100_000_000 -> "%.0f MB".format(bytes / 1e6)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
            else -> "%.0f KB".format(bytes / 1e3)
        }
    }
}
