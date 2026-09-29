package org.itantra.app.audio

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Used exclusively on the capture executor; STT/TTS cannot stall VAD reads. */
class VadSegmenter(model: String) {
    private val vad = Vad(config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
        model = model, minSilenceDuration = 0.5f, maxSpeechDuration = 15f)))
    private val history = SampleRing(16000 * 18)
    private var pending = FloatArray(0)
    fun accept(samples: FloatArray, emit: (FloatArray) -> Unit) {
        pending += samples
        while (pending.size >= 512) {
            val window = pending.copyOfRange(0, 512)
            pending = pending.copyOfRange(512, pending.size)
            history.append(window)
            vad.acceptWaveform(window)
            while (!vad.empty()) {
                val segment = vad.front()
                val prefix = history.range(segment.start.toLong() - 4800, segment.start.toLong())
                emit(prefix + segment.samples)
                vad.pop()
            }
        }
    }
    fun reset() { vad.reset(); history.clear(); pending = FloatArray(0) }
    fun release() { vad.release() }
}
