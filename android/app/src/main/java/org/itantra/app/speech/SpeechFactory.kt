package org.itantra.app.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import org.json.JSONObject
import java.io.File

/*
 * Reads manifest.json and builds sherpa-onnx STT and TTS objects from it.
 * Models are loaded from files on the phone (pushed with adb), NOT from APK assets,
 * so assetManager is always null.
 *
 *   val m = Manifest(File(context.getExternalFilesDir(null), "models"))
 *   val stt = m.makeRecognizer("hi")          // for the sender's language
 *   val voices = TtsPool(m)                   // one per app
 *   val audio = voices.speak("ta", text)      // for each incoming message
 *   // play audio.samples at audio.sampleRate (it differs per voice!)
 *
 * Confine each recognizer to the STT executor and TtsPool to the TTS executor.
 * Never invoke native inference on the UI thread.
 */

data class SttEntry(
    val type: String, val model: String, val tokens: String,
    val encoder: String, val decoder: String, val language: String,
)

data class EngineEntry(
    val name: String, val model: String, val tokens: String,
    val dataDir: String, val characterFrontend: Boolean, val sampleRate: Int,
)

data class VoiceEntry(val engine: String, val sid: Int, val emotionId: Int?, val speed: Float)

data class LanguageEntry(
    val code: String, val name: String, val script: String, val wireId: Int,
    val stt: SttEntry, val tts: VoiceEntry,
)

class Manifest(val root: File, manifestFile: File = File(root, "manifest.json")) {
    val languages: Map<String, LanguageEntry>
    val engines: Map<String, EngineEntry>
    val vadModel: String

    init {
        val j = JSONObject(manifestFile.readText(Charsets.UTF_8))
        vadModel = path(j.getJSONObject("vad").getString("model"))

        val e = j.getJSONObject("tts_engines")
        engines = e.keys().asSequence().associateWith { name ->
            val o = e.getJSONObject(name)
            EngineEntry(
                name = name,
                model = path(o.getString("model")),
                tokens = path(o.getString("tokens")),
                dataDir = o.optString("data_dir", "").let { if (it.isEmpty()) "" else path(it) },
                characterFrontend = o.optString("frontend") == "characters",
                sampleRate = o.getInt("sample_rate"),
            )
        }

        val l = j.getJSONObject("languages")
        languages = l.keys().asSequence().associateWith { code ->
            val o = l.getJSONObject(code)
            val s = o.getJSONObject("stt")
            val t = o.getJSONObject("tts")
            LanguageEntry(
                code = code,
                name = o.getString("name"),
                script = o.getString("script"),
                wireId = o.getInt("wire_id"),
                stt = SttEntry(
                    type = s.getString("type"),
                    model = s.optString("model", "").let { if (it.isEmpty()) "" else path(it) },
                    tokens = path(s.getString("tokens")),
                    encoder = s.optString("encoder", "").let { if (it.isEmpty()) "" else path(it) },
                    decoder = s.optString("decoder", "").let { if (it.isEmpty()) "" else path(it) },
                    language = s.optString("language", "en"),
                ),
                tts = VoiceEntry(
                    engine = t.getString("engine"),
                    sid = t.optInt("sid", 0),
                    emotionId = if (t.has("emotion_id")) t.getInt("emotion_id") else null,
                    speed = t.optDouble("speed", 1.0).toFloat(),
                ),
            )
        }
    }

    fun byWireId(id: Int): LanguageEntry? = languages.values.firstOrNull { it.wireId == id }

    /** Create the STT for one language. Call release() on the old one when switching. */
    fun makeRecognizer(code: String, numThreads: Int = 2): OfflineRecognizer {
        val s = languages.getValue(code).stt
        val model = when (s.type) {
            "nemo_ctc" -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = s.model),
                tokens = s.tokens, numThreads = numThreads,
            )
            "omnilingual" -> OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = s.model),
                tokens = s.tokens, numThreads = numThreads,
            )
            "whisper" -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(encoder = s.encoder, decoder = s.decoder, language = s.language),
                tokens = s.tokens, numThreads = numThreads,
            )
            else -> error("unknown STT type ${s.type}")
        }
        val cfg = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = model,
        )
        return OfflineRecognizer(assetManager = null, config = cfg)
    }

    private fun path(rel: String) = File(root, rel).absolutePath
}

/** Speech -> text for one utterance (16 kHz mono float samples from the mic). */
fun OfflineRecognizer.transcribe(samples: FloatArray, sampleRate: Int = 16000): String {
    val stream = createStream()
    return try {
        stream.acceptWaveform(samples, sampleRate)
        decode(stream)
        getResult(stream).text.trim()
    } finally { stream.release() }
}

/**
 * Keeps at most [maxLoaded] TTS engines in memory and loads the others on
 * first use. rasa covers 6 languages, so it's usually the one that stays.
 *
 * [numThreads]: TTS is the latency bottleneck. Measured on a Snapdragon 7s Gen 3
 * (5 s Tamil sentence, rasa): RTF 0.98 at 2 threads, 0.77 at 4, 0.55 at 6.
 */
class TtsPool(private val m: Manifest, private val maxLoaded: Int = 2, private val numThreads: Int = 4) {
    private class Loaded(val tts: OfflineTts, val sanitizer: TextSanitizer)
    private val loaded = LinkedHashMap<String, Loaded>(4, 0.75f, true) // access order = LRU

    @Synchronized
    fun speak(code: String, text: String): GeneratedAudio? {
        val v = m.languages.getValue(code).tts
        val eng = get(v.engine)
        val clean = eng.sanitizer.clean(text)
        if (clean.isEmpty()) return null
        val cfg = GenerationConfig(
            sid = v.sid,
            speed = v.speed,
            extra = v.emotionId?.let { mapOf("emotion_id" to it.toString()) },
        )
        return eng.tts.generateWithConfig(clean, cfg)
    }

    /** Load the voice ahead of time, e.g. right after connecting. */
    @Synchronized
    fun preload(code: String) { get(m.languages.getValue(code).tts.engine) }

    @Synchronized
    fun release() { loaded.values.forEach { it.tts.release() }; loaded.clear() }

    private fun get(name: String): Loaded {
        loaded[name]?.let { return it }
        while (loaded.size >= maxLoaded) {
            val oldest = loaded.keys.first()
            loaded.remove(oldest)?.tts?.release()
        }
        val e = m.engines.getValue(name)
        require(File(e.model).isFile && File(e.tokens).isFile && (e.dataDir.isEmpty() || File(e.dataDir).isDirectory)) {
            "Missing TTS files for $name in ${m.root}"
        }
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = e.model, tokens = e.tokens, dataDir = e.dataDir),
                numThreads = numThreads,
            ),
            maxNumSentences = 1,
        )
        val l = Loaded(
            tts = OfflineTts(assetManager = null, config = cfg),
            sanitizer = TextSanitizer(File(e.tokens), e.characterFrontend),
        )
        loaded[name] = l
        return l
    }
}
