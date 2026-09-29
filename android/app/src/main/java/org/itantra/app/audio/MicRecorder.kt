package org.itantra.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** AudioRecord pattern adapted from sherpa-onnx v1.13.8 VadAsr example. */
class MicRecorder(private val samples: (FloatArray) -> Unit, private val error: (String) -> Unit) {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    @SuppressLint("MissingPermission")
    @Synchronized fun start() {
        if (running) return
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "16 kHz microphone unavailable" }
        val mic = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 4096))
        if (mic.state != AudioRecord.STATE_INITIALIZED) { mic.release(); error("Microphone initialization failed"); return }
        try { mic.startRecording() } catch (e: Exception) { mic.release(); throw e }
        recorder = mic
        running = true
        worker = Thread {
            val buffer = ShortArray(512)
            try {
                while (running) {
                    val n = mic.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (n < 0) { if (running) error("Microphone read failed: $n"); break }
                    if (n > 0 && running) samples(FloatArray(n) { buffer[it] / 32768f })
                }
            } catch (e: Exception) { if (running) error("Microphone: ${e.message}") }
            finally { running = false }
        }.apply { name = "itantra-mic"; start() }
    }
    @Synchronized fun stop() {
        running = false
        runCatching { recorder?.stop() }
        worker?.join(1000)
        recorder?.release(); recorder = null; worker = null
    }
}
