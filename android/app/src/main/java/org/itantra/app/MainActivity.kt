package org.itantra.app

import android.Manifest as Permissions
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.color.MaterialColors
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import org.itantra.app.audio.*
import org.itantra.app.link.*
import org.itantra.app.speech.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var ptt: Button
    private lateinit var mode: MaterialSwitch
    private lateinit var picker: MaterialAutoCompleteTextView
    private lateinit var mic: MicRecorder
    private lateinit var player: Player
    private lateinit var link: LinkService
    private lateinit var manifest: org.itantra.app.speech.Manifest
    private lateinit var voices: TtsPool
    private val inference = executor("inference", 16)
    private val capture = executor("capture", 128)
    private val sequence = AtomicInteger()
    private val captureEpoch = AtomicInteger()
    private val speechEpoch = AtomicInteger()
    private val loadEpoch = AtomicInteger()
    private var hosting = false
    private var pingCounter = 0
    private var recognizerCode: String? = null
    private var recognizer: OfflineRecognizer? = null // inference executor only
    private var vad: VadSegmenter? = null // capture executor only
    private val utterance = ArrayList<Float>() // capture executor only
    @Volatile private var language = "en"
    @Volatile private var ready = false
    @Volatile private var active = false
    @Volatile private var speaking = false
    @Volatile private var recording = false
    @Volatile private var continuous = false
    @Volatile private var destroyed = false
    private var pingSequence: Int? = null
    private var pingStarted = 0L
    private val alertNames = mutableMapOf<Int, String>()
    private val messages = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        mic = MicRecorder({ samples ->
            val epoch = captureEpoch.get()
            val code = language
            val speechGeneration = speechEpoch.get()
            submit(capture) {
                if (epoch != captureEpoch.get() || !recording) return@submit
                if (continuous) vad?.accept(samples) { if (epoch == captureEpoch.get()) transcribe(it, code, now(), speechGeneration) }
                else {
                    utterance.addAll(samples.toList())
                    if (utterance.size >= 16000 * 30) runOnUiThread { endPtt(true); message("30-second recording limit reached") }
                }
            }
        }, { message(it); runOnUiThread { stopCapture() } })
        player = Player(this, { busy ->
            speaking = busy
            if (busy) stopCapture()
            else runOnUiThread { resumeContinuous() }
        }, ::message)
        link = LinkService({ text ->
            message(text)
            runOnUiThread {
                status.text = text
                if (text.startsWith("Connected")) {
                    if (::voices.isInitialized) submit(inference) { voices.preload(language); voices.speak(language, warmup(language)) }
                    resumeContinuous()
                } else if (!link.connected) stopCapture()
            }
        }, ::received)
        try {
            val root = File(getExternalFilesDir(null), "models").apply { mkdirs() }
            manifest = org.itantra.app.speech.Manifest(root,
                File(root, "manifest.json").takeIf { it.isFile } ?: File(cacheDir, "manifest.json").apply {
                    writeText(assets.open("manifest.json").bufferedReader().use { it.readText() })
                })
            voices = TtsPool(manifest)
            val langs = manifest.languages.values.sortedBy { it.wireId }
            picker.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, langs.map { it.name }))
            picker.setText(langs.first { it.code == "en" }.name, false)
            picker.setOnItemClickListener { _, _, position, _ -> loadLanguage(langs[position].code) }
            loadLanguage("en")
            message("Models: ${root.absolutePath}")
        } catch (e: Exception) { message("Setup failed: ${e.message}") }
        if (checkSelfPermission(Permissions.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Permissions.permission.RECORD_AUDIO), 1)
    }

    private fun buildUi() {
        fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(32)) }
        val scroll = ScrollView(this).apply { addView(body); isFillViewport = true }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        var section = body
        fun label(text: String, size: Float = 16f) = MaterialTextView(this).apply {
            this.text = text; textSize = size
            section.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        fun card(title: String, subtitle: String) {
            val card = MaterialCardView(this).apply {
                radius = dp(24).toFloat(); cardElevation = 0f; strokeWidth = 0
                setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow))
            }
            body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(16)) }
            card.addView(section)
            label(title, 22f); label(subtitle, 14f)
        }
        fun button(parent: LinearLayout, title: String, filled: Boolean = false, action: () -> Unit) = MaterialButton(this).apply {
            text = title; isAllCaps = false; minHeight = dp(48)
            if (!filled) {
                backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline))
            }
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            setOnClickListener { action() }
        }
        label("iTantra", 34f)
        label("Your voice. Across the distance.")
        label("OFFLINE  ·  10 LANGUAGES", 12f)
        card("Connect a phone", "Use the same Wi-Fi network or phone hotspot.")
        status = label("Not connected", 16f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val hostField = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = "Host IP address" }
        val host = TextInputEditText(hostField.context).apply { inputType = android.text.InputType.TYPE_CLASS_PHONE; maxLines = 1 }
        hostField.addView(host); section.addView(hostField)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; section.addView(this) }
        button(controls, "Host") { hosting = true; pingSequence = null; link.connect(null) }
        button(controls, "Join", filled = true) { if (!host.text.isNullOrBlank()) { hostField.error = null; hosting = false; pingSequence = null; link.connect(host.text.toString().trim()) } else hostField.error = "Enter the other phone’s IP address" }
        button(controls, "Disconnect") { link.disconnect(); stopCapture(); status.text = "Disconnected" }
        button(section, "Check connection · Ping") {
            if (!link.connected) message("Connect first") else {
                pingCounter = (pingCounter + 2) and 65534
                pingSequence = pingCounter or (if (hosting) 0 else 1); pingStarted = now()
                link.send(Frame(3, 0, pingSequence!!, byteArrayOf()))
            }
        }
        card("Talk", "Hold to record. Release to send your message.")
        val languageField = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle).apply { hint = "Speech language" }
        picker = MaterialAutoCompleteTextView(languageField.context).apply { inputType = 0 }
        languageField.addView(picker); section.addView(languageField)
        ptt = MaterialButton(this).apply {
            text = "Hold to talk"; textSize = 22f; minHeight = dp(112); cornerRadius = dp(28); isEnabled = false; section.addView(this, LinearLayout.LayoutParams(-1, -2))
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { view.parent.requestDisallowInterceptTouchEvent(true); beginCapture(); isPressed = recording; if (recording) text = "Release to send"; true }
                    MotionEvent.ACTION_UP -> { view.parent.requestDisallowInterceptTouchEvent(false); endPtt(true); isPressed = false; text = "Hold to talk"; view.performClick(); true }
                    MotionEvent.ACTION_CANCEL -> { view.parent.requestDisallowInterceptTouchEvent(false); endPtt(false); isPressed = false; text = "Hold to talk"; true }
                    else -> true
                }
            }
        }
        mode = MaterialSwitch(this).apply {
            text = "Continuous listening"; minHeight = dp(56); section.addView(this)
            setOnCheckedChangeListener { _, checked ->
                stopCapture(); continuous = checked
                ptt.isEnabled = !checked && ready
                ptt.text = if (checked) "Continuous mode" else "Hold to talk"
                resumeContinuous()
            }
        }
        button(section, "Reload language") { if (::manifest.isInitialized) loadLanguage(language) }
        card("Priority alerts", "Plays in the receiving phone’s language at full alarm volume.")
        val alerts = JSONObject(assets.open("alerts.json").bufferedReader().use { it.readText() }).getJSONObject("alerts")
        alerts.keys().forEach { name -> alertNames[alerts.getJSONObject(name).getInt("id")] = name }
        alertNames.toSortedMap().forEach { (id, name) ->
            button(section, name.replace('_', ' ').replaceFirstChar { it.titlecase() }) {
                if (link.connected && ::manifest.isInitialized) link.send(Frame(2, manifest.languages.getValue(language).wireId, sequence.getAndIncrement(), byteArrayOf(id.toByte())))
                else message("Connect first to send an alert")
            }
        }
        button(section, "Test alert on this phone") { playAlert(alertNames.keys.minOrNull() ?: 1) }
        card("Activity", "Messages, speech timings and connection details.")
        log = label("Your activity will appear here.").apply { textSize = 14f; setTextIsSelectable(true) }
    }

    private fun loadLanguage(code: String) {
        stopCapture(); ready = false; language = code; ptt.isEnabled = false
        speechEpoch.incrementAndGet()
        val epoch = loadEpoch.incrementAndGet()
        player.clearSpeech()
        submit(capture) { vad?.release(); vad = null }
        submit(inference) {
            if (epoch != loadEpoch.get()) return@submit
            recognizer?.release(); recognizer = null; recognizerCode = null
            voices.release()
            val entry = manifest.languages.getValue(code)
            val engine = manifest.engines.getValue(entry.tts.engine)
            val needed = listOf(entry.stt.model, entry.stt.tokens, engine.model, engine.tokens, manifest.vadModel)
                .filter { it.isNotEmpty() }
            require(needed.all { File(it).isFile }) { "Missing models for $code. Push models to ${manifest.root}, then Reload." }
            recognizer = manifest.makeRecognizer(code)
            recognizerCode = code
            voices.preload(code)
            voices.speak(code, warmup(code)) // Warm generation, deliberately not played or timed.
            submit(capture) {
                if (epoch != loadEpoch.get()) return@submit
                vad?.release()
                vad = VadSegmenter(manifest.vadModel)
                runOnUiThread {
                    if (epoch == loadEpoch.get() && !destroyed) {
                        ready = true; ptt.isEnabled = !continuous
                        message("${entry.name} ready"); resumeContinuous()
                    }
                }
            }
        }
    }

    @Synchronized private fun stopCapture() {
        recording = false; captureEpoch.incrementAndGet()
        if (::mic.isInitialized) mic.stop()
        submit(capture) { utterance.clear(); vad?.reset() }
    }
    @Synchronized private fun beginCapture() {
        if (!ready || !active || speaking || recording || !link.connected) { if (!continuous) message("Wait for models and connection; recording pauses during playback"); return }
        if (checkSelfPermission(Permissions.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread { requestPermissions(arrayOf(Permissions.permission.RECORD_AUDIO), 1) }; return
        }
        recording = true
        try { mic.start() } catch (e: Exception) { recording = false; message("Mic: ${e.message}") }
    }
    private fun endPtt(send: Boolean) {
        if (continuous || !recording) return
        mic.stop()
        // Keep recording true until all already-enqueued microphone buffers are drained.
        val epoch = captureEpoch.get()
        val code = language
        val ended = now()
        val speechGeneration = speechEpoch.get()
        submit(capture) {
            if (epoch != captureEpoch.get()) return@submit
            recording = false
            val samples = utterance.toFloatArray(); utterance.clear()
            if (send && samples.size >= 1600) transcribe(samples, code, ended, speechGeneration)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) resumeContinuous()
        else message("Microphone permission is required for sending speech")
    }
    private fun resumeContinuous() { if (continuous && active && ready && !speaking) beginCapture() }
    private fun transcribe(samples: FloatArray, code: String, ended: Long, epoch: Int) {
        submit(inference) {
            if (epoch != speechEpoch.get() || code != recognizerCode) return@submit
            val start = now()
            val text = recognizer?.transcribe(samples).orEmpty()
            val finished = now()
            if (text.isNotBlank() && epoch == speechEpoch.get() && active) {
                val frame = Frame.speech(text, manifest.languages.getValue(code).wireId, sequence.getAndIncrement())
                link.send(frame) { bytes ->
                    message("TX #${frame.sequence and 65535} [$code] $text\nSTT ${finished - start} ms · end→STT ${finished - ended} ms · RTF ${"%.3f".format((finished - start) / (samples.size / 16.0))} · $bytes bytes")
                }
            }
        }
    }
    private fun received(frame: Frame) {
        val receivedAt = now()
        if (destroyed) return
        when (frame.type) {
            3 -> runOnUiThread {
                if (pingSequence == frame.sequence) { message("Ping RTT ${now() - pingStarted} ms"); pingSequence = null }
                else if (frame.sequence % 2 != (if (hosting) 0 else 1)) link.send(frame) // Host uses even ping IDs, joiner odd: no echo loops.
            }
            2 -> playAlert(frame.payload[0].toInt() and 255)
            else -> {
                val epoch = speechEpoch.get()
                if (!::manifest.isInitialized || !::voices.isInitialized) return
                val code = manifest.byWireId(frame.language)?.code ?: return
                message("RX #${frame.sequence} [$code] ${frame.text()}")
                submit(inference) {
                    if (!active || epoch != speechEpoch.get()) return@submit
                    val parts = frame.text().split(Regex("[,，;؛\\n]+" )).filter { it.isNotBlank() }
                    parts.forEachIndexed { index, part ->
                        if (!active || epoch != speechEpoch.get()) return@submit
                        val start = now()
                        val audio = voices.speak(code, part) ?: return@forEachIndexed
                        val took = now() - start
                        if (epoch == speechEpoch.get()) player.speech(Pcm(audio.samples, audio.sampleRate)) {
                            message("RX #${frame.sequence} part ${index + 1} · first playback ${now() - receivedAt} ms · TTS $took ms · RTF ${"%.3f".format(took / (audio.samples.size * 1000.0 / audio.sampleRate))}")
                        }
                    }
                }
            }
        }
    }
    private fun playAlert(id: Int) {
        val name = alertNames[id] ?: run { message("Unknown alert $id"); return }
        val code = language
        speechEpoch.incrementAndGet()
        player.clearSpeech()
        player.alert {
            val local = File(getExternalFilesDir(null), "alerts/$code/$name.wav")
            val bytes = if (local.isFile) local.readBytes() else assets.open("alerts/$code/$name.wav").use { it.readBytes() }
            Wav.decode(bytes)
        }
        message("Alert: $name [$code]")
    }
    private fun message(text: String) {
        Log.i("iTantra", text)
        runOnUiThread {
            if (!destroyed && ::log.isInitialized) {
                messages.addLast(text); while (messages.size > 80) messages.removeFirst()
                log.text = messages.joinToString("\n\n")
            }
        }
    }
    private fun submit(executor: ThreadPoolExecutor, block: () -> Unit) {
        if (executor.isShutdown) return
        try { executor.execute { try { block() } catch (e: Exception) { message(e.message ?: e.javaClass.simpleName) } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { message("Busy: work queue full; input dropped") }
    }
    override fun onResume() { super.onResume(); active = true; if (::mic.isInitialized) resumeContinuous() }
    override fun onPause() { active = false; speechEpoch.incrementAndGet(); stopCapture(); if (::player.isInitialized) player.clearSpeech(); super.onPause() }
    override fun onDestroy() {
        destroyed = true; active = false; loadEpoch.incrementAndGet(); speechEpoch.incrementAndGet(); stopCapture()
        link.close(); player.close()
        capture.queue.clear()
        submit(capture) { vad?.release(); vad = null }; capture.shutdown()
        inference.queue.clear()
        submit(inference) { recognizer?.release(); if (::voices.isInitialized) voices.release() }; inference.shutdown()
        super.onDestroy()
    }
    companion object {
        private fun now() = SystemClock.elapsedRealtime()
        private fun executor(name: String, size: Int) = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(size), { r -> Thread(r, "itantra-$name") })
        private fun warmup(code: String) = mapOf("en" to "ready", "hi" to "नमस्ते", "bn" to "নমস্কার", "gu" to "નમસ્તે", "kn" to "ನಮಸ್ಕಾರ", "ml" to "നമസ്കാരം", "mr" to "नमस्कार", "or" to "ନମସ୍କାର", "ta" to "வணக்கம்", "te" to "నమస్కారం").getValue(code)
    }
}
