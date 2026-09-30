package org.itantra.app.audio

import android.content.Context
import android.media.*
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicLong

/** FIFO speech, priority alerts. A playing alert is never preempted by app messages. */
class Player(context: Context, private val busy: (Boolean) -> Unit, private val error: (String) -> Unit) {
    /** [done] reports whether the item played to the end (false: interrupted, cleared or failed). */
    private data class Item(val load: () -> Pcm, val alert: Boolean, val first: () -> Unit, val epoch: Long, val done: (Boolean) -> Unit = {})
    private val audio = context.getSystemService(AudioManager::class.java)
    private val queue = LinkedBlockingDeque<Item>(32)
    private val interruptSpeech = AtomicLong()
    @Volatile private var closed = false
    @Volatile private var track: AudioTrack? = null
    private val worker = Thread {
        while (!closed) {
            val item = try { queue.take() } catch (_: InterruptedException) { break }
            val epoch = item.epoch
            if (!item.alert && epoch != interruptSpeech.get()) { item.done(false); if (queue.isEmpty()) busy(false); continue }
            try {
                busy(true) // Must synchronously stop the recorder before any sound.
                play(item, epoch)
                item.done(!closed && (item.alert || epoch == interruptSpeech.get()))
            } catch (e: Exception) { item.done(false); if (!closed) error("Playback: ${e.message}") }
            finally { if (queue.isEmpty() || closed) busy(false) }
        }
    }.apply { name = "itantra-player"; start() }
    fun speech(pcm: Pcm, first: () -> Unit = {}, done: (Boolean) -> Unit = {}) {
        if (!closed && !queue.offerLast(Item({ pcm }, false, first, interruptSpeech.get(), done))) { error("Playback queue full; speech dropped"); done(false) }
    }
    fun alert(load: () -> Pcm) {
        if (closed) return
        // Reserve room without evicting an alert. Stop current speech at the next small write.
        if (queue.remainingCapacity() == 0) queue.firstOrNull { !it.alert }?.let { queue.remove(it) }
        if (!queue.offerFirst(Item(load, true, {}, 0))) { error("Alert queue full"); return }
        interruptSpeech.incrementAndGet()
    }
    private fun play(item: Item, epoch: Long) {
        val pcm = item.load()
        require(pcm.samples.isNotEmpty()) { "Empty audio" }
        val attrs = AudioAttributes.Builder().setUsage(if (item.alert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = AudioFocusRequest.Builder(if (item.alert) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs).setOnAudioFocusChangeListener { change ->
                if (!item.alert && change < 0) interruptSpeech.incrementAndGet()
            }.build()
        check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus denied" }
        val oldVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        var output: AudioTrack? = null
        try {
            if (item.alert) audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val format = AudioFormat.Builder().setSampleRate(pcm.rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build()
            val minimum = AudioTrack.getMinBufferSize(pcm.rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            check(minimum > 0)
            val out = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum, 4096)).setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
            android.util.Log.i("iTantra", "AudioTrack requested low latency; actual mode=${out.performanceMode}, rate=${pcm.rate}")
            output = out; track = out
            check(out.state == AudioTrack.STATE_INITIALIZED)
            out.play()
            var offset = 0
            var reported = false
            val deadline = System.nanoTime() + (pcm.samples.size.toDouble() / pcm.rate * 1e9).toLong() + 5_000_000_000L
            while (offset < pcm.samples.size && !closed && (item.alert || epoch == interruptSpeech.get())) {
                val n = out.write(pcm.samples, offset, minOf(1024, pcm.samples.size - offset), AudioTrack.WRITE_BLOCKING)
                check(n > 0) { "Audio write failed: $n" }
                offset += n
                if (!reported && out.playbackHeadPosition > 0) { item.first(); reported = true }
            }
            while (!closed && (item.alert || epoch == interruptSpeech.get()) && out.playbackHeadPosition < offset) {
                check(System.nanoTime() < deadline) { "Playback timed out" }
                if (!reported && out.playbackHeadPosition > 0) { item.first(); reported = true }
                Thread.sleep(5)
            }
        } finally {
            track = null
            output?.let { runCatching { it.pause(); it.flush() }; it.release() }
            if (item.alert) runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0) }
            audio.abandonAudioFocusRequest(focus)
        }
    }
    fun clearSpeech() { queue.filter { !it.alert }.forEach { if (queue.remove(it)) it.done(false) }; interruptSpeech.incrementAndGet() }
    fun close() {
        closed = true; queue.clear(); interruptSpeech.incrementAndGet()
        runCatching { track?.pause() }; worker.interrupt(); worker.join(1500)
    }
}
