package org.itantra.app

import android.Manifest as Permissions
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButtonToggleGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.color.MaterialColors
import android.content.res.ColorStateList
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import org.itantra.app.metrics.AppFootprint
import org.itantra.app.speech.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {
    private lateinit var discovery: DeviceDiscovery
    private lateinit var nearbyDevices: LinearLayout
    private lateinit var discoveryHint: TextView
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var ptt: Button
    private lateinit var mode: MaterialSwitch
    private lateinit var picker: MaterialAutoCompleteTextView
    private lateinit var micTestButton: Button
    private lateinit var micLevel: ProgressBar
    private lateinit var micHint: TextView
    @Volatile private var micTesting = false
    private var lastMeterUpdate = 0L
    private var permissionDialogOpen = false
    private lateinit var mic: MicRecorder
    private lateinit var player: Player
    private lateinit var wifiLink: LinkService
    private lateinit var bluetoothLink: BluetoothLink
    private lateinit var bluetoothDiscovery: BluetoothDiscovery
    private val bluetoothAdapter by lazy { getSystemService(BluetoothManager::class.java)?.adapter }
    @Volatile private var useBluetooth = false
    private val link: PeerLink get() = if (useBluetooth) bluetoothLink else wifiLink
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!destroyed) discoveryHint.text = "Tap Host or Scan for devices to continue."
    }
    private val makeDiscoverable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (useBluetooth && !destroyed && result.resultCode > 0) {
            hosting = true; bluetoothLink.connect()
        } else if (!destroyed) discoveryHint.text = "Bluetooth hosting cancelled. Tap Host to try again."
    }
    private lateinit var manifest: org.itantra.app.speech.Manifest
    private lateinit var voices: TtsPool
    private val workers = InferenceWorkers()
    private val inference = workers.stt
    private val synthesis = workers.tts
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
    private val alertTexts = mutableMapOf<Int, JSONObject>() // alert id -> its phrase per language code
    private val messages = ArrayDeque<String>()
    private lateinit var conversation: Conversation
    private val bluetoothButtons = HashMap<String, MaterialButton>() // address -> its row in nearbyDevices
    // Top-right size/CPU/RAM badge and its breakdown dialog
    private val footprint by lazy { AppFootprint(this) { if (::manifest.isInitialized) manifest else null } }
    private lateinit var footprintBadge: MaterialButton
    @Volatile private var footprintParts: List<AppFootprint.Part> = emptyList()
    @Volatile private var footprintUsage: AppFootprint.Usage? = null
    private var footprintRows: LinearLayout? = null
    // Speech-language picker and on-demand model download
    private lateinit var downloader: ModelDownloader
    private var pickerLangs: List<LanguageEntry> = emptyList()
    private val downloading = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var downloadCancel: java.util.concurrent.atomic.AtomicBoolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        discovery = DeviceDiscovery(this, ::showDevices, { text ->
            if (!destroyed && !useBluetooth) discoveryHint.text = text
        })
        bluetoothDiscovery = BluetoothDiscovery(this, bluetoothAdapter, ::showBluetoothDevices, { text ->
            if (!destroyed && useBluetooth) discoveryHint.text = text
        })
        mic = MicRecorder({ samples ->
            if (micTesting) {
                val meterEpoch = captureEpoch.get()
                if (now() - lastMeterUpdate >= 100) {
                    lastMeterUpdate = now()
                    val level = MicLevel.percent(samples)
                    runOnUiThread { if (micTesting && meterEpoch == captureEpoch.get()) micLevel.progress = level }
                }
                return@MicRecorder
            }
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
        wifiLink = LinkService({ linkStatus(it, false) }, { if (!useBluetooth) received(it) })
        bluetoothLink = BluetoothLink(bluetoothAdapter, { linkStatus(it, true) }, { if (useBluetooth) received(it) })
        try {
            val root = File(getExternalFilesDir(null), "models").apply { mkdirs() }
            val manifestFile = File(root, "manifest.json").takeIf { it.isFile } ?: File(cacheDir, "manifest.json").apply {
                writeText(assets.open("manifest.json").bufferedReader().use { it.readText() })
            }
            manifest = org.itantra.app.speech.Manifest(root, manifestFile)
            downloader = ModelDownloader(root, manifestFile)
            voices = TtsPool(manifest)
            // All 10 languages are offered. A phone ships with STT for Hindi + English + one Indic
            // language (plus every voice); picking any other language downloads its STT model once.
            pickerLangs = manifest.languages.values.sortedBy { it.wireId }
            refreshPicker()
            picker.setOnItemClickListener { _, _, position, _ -> chooseLanguage(pickerLangs[position].code) }
            val first = pickerLangs.firstOrNull { it.code == "en" && installed(it) } ?: pickerLangs.firstOrNull { installed(it) } ?: pickerLangs.first()
            picker.setText(pickerLabel(first), false)
            loadLanguage(first.code)
            message("Models: ${root.absolutePath} · speech languages installed: ${pickerLangs.count { installed(it) }}/${pickerLangs.size}")
        } catch (e: Exception) { message("Setup failed: ${e.message}") }
    }

    private fun linkStatus(text: String, bluetooth: Boolean) {
        message(text)
        runOnUiThread {
            if (destroyed || bluetooth != useBluetooth) return@runOnUiThread
            status.text = when {
                text.startsWith("Hosting") -> if (useBluetooth) "Hosting over Bluetooth" else "Hosting over Wi-Fi"
                text.startsWith("Connected") -> if (useBluetooth) "Connected over Bluetooth" else "Connected over Wi-Fi"
                text.startsWith("Connecting") -> "Connecting…"
                else -> text
            }
            if (!useBluetooth && active && hosting && link.listening) discovery.advertise(LinkService.PORT)
            else discovery.stopAdvertising()
            if (text.startsWith("Connected")) {
                discoveryHint.text = "Connected. Use Talk below, or scan to choose another phone."
                if (::voices.isInitialized) submit(synthesis) { voices.preload(language); voices.speak(language, warmup(language)) }
                resumeContinuous()
            } else if (!link.connected) {
                stopCapture()
                if (text.startsWith("Disconnected")) discoveryHint.text = "Connection ended. Tap Host or scan to reconnect."
            }
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(32)) }
        val scroll = ScrollView(this).apply { addView(body); isFillViewport = true }
        // The caption bar sits below the scrolling page, so the latest message is always readable.
        conversation = Conversation(this, ::dp)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); addView(conversation.bar)
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        var section = body
        fun label(text: String, size: Float = 16f) = MaterialTextView(this).apply {
            this.text = text; textSize = size
            section.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        fun card(title: String, subtitle: String): MaterialCardView {
            val card = MaterialCardView(this).apply {
                radius = dp(24).toFloat(); cardElevation = 0f; strokeWidth = 0
                setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow))
            }
            body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(16)) }
            card.addView(section)
            label(title, 22f); label(subtitle, 14f)
            return card
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
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        body.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        header.addView(MaterialTextView(this).apply { text = "iTantra"; textSize = 34f }, LinearLayout.LayoutParams(0, -2, 1f))
        footprintBadge = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            isAllCaps = false; textSize = 12f; maxLines = 2; minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            insetTop = 0; insetBottom = 0; cornerRadius = dp(16); setPadding(dp(12), dp(6), dp(12), dp(6))
            text = "App size …"; contentDescription = "App size and CPU/RAM usage. Tap for the breakdown."
            setOnClickListener { showFootprint() }
        }
        header.addView(footprintBadge, LinearLayout.LayoutParams(-2, -2))
        label("Your voice. Across the distance.")
        label("OFFLINE  ·  10 LANGUAGES", 12f)
        card("Connect a phone", "Choose Wi-Fi on the same network, or Bluetooth nearby.")
        val transport = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        // Outlined style: in a toggle group the checked segment gets a tonal fill, so the chosen transport is visible.
        val segment = com.google.android.material.R.attr.materialButtonOutlinedStyle
        val wifiChoice = MaterialButton(this, null, segment).apply { id = View.generateViewId(); text = "Wi-Fi"; isCheckable = true }
        val bluetoothChoice = MaterialButton(this, null, segment).apply { id = View.generateViewId(); text = "Bluetooth"; isCheckable = true }
        transport.addView(wifiChoice, LinearLayout.LayoutParams(0, -2, 1f))
        transport.addView(bluetoothChoice, LinearLayout.LayoutParams(0, -2, 1f))
        section.addView(transport)
        transport.check(wifiChoice.id)
        transport.addOnButtonCheckedListener { _, id, checked ->
            if (checked) switchTransport(id == bluetoothChoice.id)
        }
        status = label("Not connected", 16f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        discoveryHint = label("Tap Host on one phone. On the other, scan and pick it from the list.", 14f)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; section.addView(this) }
        button(controls, "Host") {
            if (useBluetooth) {
                if (ensureBluetooth(scan = false)) {
                    resetConnection()
                    makeDiscoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
                }
            } else {
                resetConnection(); hosting = true; wifiLink.connect(null)
            }
        }
        button(controls, "Scan for devices", filled = true) {
            if (!useBluetooth || ensureBluetooth(scan = true)) {
                resetConnection()
                if (useBluetooth) bluetoothDiscovery.scan() else discovery.scan()
            }
        }
        nearbyDevices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; section.addView(this) }
        button(controls, "Disconnect") {
            resetConnection()
            discoveryHint.text = "Tap Host or scan to connect again."
        }
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
            text = "Hold to talk"; textSize = 22f; minHeight = dp(112); cornerRadius = dp(28); isEnabled = true; section.addView(this, LinearLayout.LayoutParams(-1, -2))
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
                if (checked && !ensureMicPermission()) { isChecked = false; return@setOnCheckedChangeListener }
                stopCapture(); continuous = checked
                ptt.isEnabled = !checked
                ptt.text = if (checked) "Continuous mode" else "Hold to talk"
                resumeContinuous()
            }
        }
        button(section, "Microphone access") { if (ensureMicPermission()) message("Microphone access is enabled") }
        micTestButton = button(section, "Test microphone") { toggleMicTest() }
        micLevel = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; contentDescription = "Microphone level"
            section.addView(this, LinearLayout.LayoutParams(-1, dp(12)))
        }
        micHint = label("Test your mic without models or a connection. No audio is saved or sent.", 14f)
        button(section, "Reload language") { if (::manifest.isInitialized) loadLanguage(language) }
        button(section, "Manage languages") { if (::manifest.isInitialized) manageLanguages() }
        val conversationCard = card("Conversation", "Everything you say and hear, as text. The newest message also stays pinned at the bottom of the screen.")
        section.addView(conversation.panel)
        conversation.onBarClick = { scroll.smoothScrollTo(0, conversationCard.top) }
        card("Priority alerts", "Plays in the receiving phone’s language at full alarm volume.")
        val alerts = JSONObject(assets.open("alerts.json").bufferedReader().use { it.readText() }).getJSONObject("alerts")
        alerts.keys().forEach { name ->
            val a = alerts.getJSONObject(name)
            alertNames[a.getInt("id")] = name; alertTexts[a.getInt("id")] = a.getJSONObject("texts")
        }
        alertNames.toSortedMap().forEach { (id, name) ->
            button(section, name.replace('_', ' ').replaceFirstChar { it.titlecase() }) {
                if (link.connected && ::manifest.isInitialized) {
                    val sent = conversation.add(Conversation.Kind.SENT, "You · alert", alertText(id, language), "Sending…")
                    link.send(Frame(2, manifest.languages.getValue(language).wireId, sequence.getAndIncrement(), byteArrayOf(id.toByte()))) { bytes ->
                        sent.update(status = "Sent · $bytes bytes")
                    }
                } else message("Connect first to send an alert")
            }
        }
        button(section, "Test alert on this phone") { playAlert(alertNames.keys.minOrNull() ?: 1) }
        card("Activity", "Messages, speech timings and connection details.")
        log = label("Your activity will appear here.").apply { textSize = 14f; setTextIsSelectable(true) }
    }

    private fun resetConnection() {
        hosting = false; pingSequence = null; speechEpoch.incrementAndGet(); stopCapture(); player.clearSpeech()
        discovery.pause(); bluetoothDiscovery.stop()
        wifiLink.disconnect(); bluetoothLink.disconnect()
        nearbyDevices.removeAllViews(); bluetoothButtons.clear(); status.text = "Not connected"
    }

    private fun switchTransport(bluetooth: Boolean) {
        if (useBluetooth == bluetooth) return
        resetConnection(); useBluetooth = bluetooth
        discoveryHint.text = if (bluetooth) "Tap Host on the other phone, then scan. Android may ask you to pair."
            else "Use the same Wi-Fi network. Tap Host on one phone and scan on the other."
    }

    @SuppressLint("MissingPermission")
    private fun ensureBluetooth(scan: Boolean): Boolean {
        val radio = bluetoothAdapter ?: run { message("Bluetooth is unavailable. Use Wi-Fi instead."); return false }
        val required = if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Permissions.permission.BLUETOOTH_CONNECT, Permissions.permission.BLUETOOTH_SCAN, Permissions.permission.BLUETOOTH_ADVERTISE
        ) else if (scan) arrayOf(Permissions.permission.ACCESS_FINE_LOCATION) else emptyArray()
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            val prefs = getPreferences(MODE_PRIVATE)
            if (missing.all { prefs.getBoolean(it, false) && !shouldShowRequestPermissionRationale(it) }) {
                MaterialAlertDialogBuilder(this).setTitle("Enable Bluetooth access")
                    .setMessage("Allow Nearby devices (or Location on older Android) in app permissions, then try again.")
                    .setPositiveButton("Open Settings") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                    .setNegativeButton("Not now", null).show()
            } else {
                missing.forEach { prefs.edit().putBoolean(it, true).apply() }
                requestPermissions(missing.toTypedArray(), 2)
            }
            discoveryHint.text = "Bluetooth access is needed. Grant permission, then tap Host or Scan again."
            return false
        }
        if (!radio.isEnabled) { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return false }
        return true
    }

    /** Updates rows in place (one per address) instead of rebuilding the list on every scan result. */
    @SuppressLint("MissingPermission")
    private fun showBluetoothDevices(devices: List<Pair<BluetoothDevice, String>>) {
        if (destroyed || !useBluetooth) return
        if (devices.isEmpty()) { nearbyDevices.removeAllViews(); bluetoothButtons.clear(); return }
        devices.forEach { (device, name) ->
            val row = bluetoothButtons[device.address]?.takeIf { it.parent === nearbyDevices } ?: MaterialButton(this).apply {
                isAllCaps = false
                setOnClickListener {
                    if (ensureBluetooth(scan = false)) {
                        bluetoothDiscovery.stop(); hosting = false; pingSequence = null
                        bluetoothLink.connect(device)
                    }
                }
                nearbyDevices.addView(this, LinearLayout.LayoutParams(-1, -2))
                bluetoothButtons[device.address] = this
            }
            row.text = "$name · ${device.address.takeLast(5)}"
            row.contentDescription = "Connect to $name over Bluetooth"
        }
    }

    private fun showDevices(names: List<String>) {
        if (destroyed || useBluetooth) return
        nearbyDevices.removeAllViews()
        names.forEach { name ->
            nearbyDevices.addView(MaterialButton(this).apply {
                text = name; isAllCaps = false
                contentDescription = "Connect to $name"
                setOnClickListener {
                    discovery.select(name) { host, port ->
                        hosting = false; pingSequence = null; wifiLink.connect(host, port)
                    }
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun loadLanguage(code: String) {
        stopCapture(); ready = false; language = code; ptt.isEnabled = !continuous
        speechEpoch.incrementAndGet()
        val epoch = loadEpoch.incrementAndGet()
        player.clearSpeech()
        submit(capture) { vad?.release(); vad = null }
        submit(inference) {
            if (epoch != loadEpoch.get()) return@submit
            recognizer?.release(); recognizer = null; recognizerCode = null
            val entry = manifest.languages.getValue(code)
            val engine = manifest.engines.getValue(entry.tts.engine)
            val needed = listOf(entry.stt.model, entry.stt.tokens, engine.model, engine.tokens, manifest.vadModel)
                .filter { it.isNotEmpty() }
            require(needed.all { File(it).isFile }) { "Missing models for $code. Push models to ${manifest.root}, then Reload." }
            recognizer = manifest.makeRecognizer(code)
            recognizerCode = code
            submit(synthesis) {
                if (epoch == loadEpoch.get()) { voices.preload(code); voices.speak(code, warmup(code)) }
            } // Voice warm-up does not hold up outgoing STT.
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
        recording = false; micTesting = false; captureEpoch.incrementAndGet()
        runOnUiThread {
            if (::micTestButton.isInitialized && !destroyed) {
                micTestButton.text = "Test microphone"; micLevel.progress = 0
                micHint.text = "Test your mic without models or a connection. No audio is saved or sent."
                ptt.isPressed = false; ptt.text = if (continuous) "Continuous mode" else "Hold to talk"
            }
        }
        if (::mic.isInitialized) mic.stop()
        submit(capture) { utterance.clear(); vad?.reset() }
    }
    @Synchronized private fun beginCapture() {
        if (!ensureMicPermission()) return
        if (micTesting) stopCapture()
        if (!ready || !active || speaking || recording || !link.connected) { if (!continuous) message("Wait for models and connection; recording pauses during playback"); return }
        recording = true
        try { mic.start() } catch (e: Exception) { recording = false; message("Mic: ${e.message}") }
    }
    private fun endPtt(send: Boolean) {
        if (continuous || micTesting || !recording) return
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
    private fun hasMicPermission() = checkSelfPermission(Permissions.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensureMicPermission(): Boolean {
        if (hasMicPermission()) return true
        if (permissionDialogOpen) return false
        val prefs = getPreferences(MODE_PRIVATE)
        val rationale = shouldShowRequestPermissionRationale(Permissions.permission.RECORD_AUDIO)
        val requested = prefs.getBoolean("mic_requested", false)
        fun request() {
            prefs.edit().putBoolean("mic_requested", true).apply()
            requestPermissions(arrayOf(Permissions.permission.RECORD_AUDIO), 1)
        }
        permissionDialogOpen = true
        if (requested && !rationale) {
            MaterialAlertDialogBuilder(this).setTitle("Enable microphone access")
                .setMessage("Allow Microphone in this app’s permissions to talk or test your mic. Receiving audio still works.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }.setNegativeButton("Not now", null)
                .setOnDismissListener { permissionDialogOpen = false }.show()
        } else if (rationale) {
            MaterialAlertDialogBuilder(this).setTitle("Microphone access")
                .setMessage("iTantra needs the microphone to send speech and show the local mic meter.")
                .setPositiveButton("Continue") { _, _ -> request() }
                .setNegativeButton("Not now", null)
                .setOnDismissListener { permissionDialogOpen = false }.show()
        } else request()
        message("Microphone permission is required for sending speech")
        return false
    }

    @Synchronized private fun toggleMicTest() {
        if (micTesting) { stopCapture(); return }
        if (!ensureMicPermission() || !active) return
        if (speaking) { message("Wait for playback to finish before testing the mic"); return }
        mode.isChecked = false
        stopCapture()
        micTesting = true; lastMeterUpdate = 0
        micTestButton.text = "Stop microphone test"
        micHint.text = "Mic test active · speak to move the meter"
        try { mic.start() } catch (e: Exception) { stopCapture(); message("Mic: ${e.message}") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            discoveryHint.text = if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
                "Bluetooth permission granted. Tap Host or Scan for devices."
            else "Bluetooth access denied. Tap Host or Scan to retry, or use Wi-Fi."
            return
        }
        if (requestCode != 1) return
        permissionDialogOpen = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            message("Microphone enabled. Hold to talk, enable continuous listening, or test your mic.")
        else {
            mode.isChecked = false; stopCapture()
            message("Microphone permission denied. Tap Microphone access to retry or open Settings.")
        }
    }
    private fun resumeContinuous() {
        if (!hasMicPermission()) { if (::mode.isInitialized) mode.isChecked = false; return }
        if (continuous && active && ready && !speaking) beginCapture()
    }
    private fun transcribe(samples: FloatArray, code: String, ended: Long, epoch: Int) {
        val outgoingLink = link
        val who = "You · ${languageName(code)}"
        // Push-to-talk shows a caption while recognition runs. Continuous mode waits for text, so
        // background noise that VAD passes on doesn't flash empty captions.
        val caption = if (continuous) null else conversation.add(Conversation.Kind.SENT, who, "…", "Recognising speech…")
        submit(inference) {
            if (epoch != speechEpoch.get() || code != recognizerCode) { caption?.remove(); return@submit }
            val start = now()
            val text = recognizer?.transcribe(samples).orEmpty()
            val finished = now()
            if (text.isNotBlank() && epoch == speechEpoch.get() && active) {
                val frame = Frame.speech(text, manifest.languages.getValue(code).wireId, sequence.getAndIncrement())
                val sent = caption?.apply { update(text = text, status = "Sending…") } ?: conversation.add(Conversation.Kind.SENT, who, text, "Sending…")
                outgoingLink.send(frame) { bytes ->
                    sent.update(status = "Sent · $bytes bytes")
                    message("TX #${frame.sequence and 65535} [$code] $text\nSTT ${finished - start} ms · end→STT ${finished - ended} ms · RTF ${"%.3f".format((finished - start) / (samples.size / 16.0))} · $bytes bytes")
                }
            } else caption?.update(text = if (text.isBlank()) "(no speech recognised)" else text, status = "Not sent")
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
                // The text is on screen before any audio: deaf users don't wait for (or depend on) the voice.
                val caption = conversation.add(Conversation.Kind.HEARD, languageName(code), frame.text(), "Received")
                submit(synthesis) {
                    if (!active || epoch != speechEpoch.get()) { caption.update(status = "Not spoken"); return@submit }
                    val parts = frame.text().split(Regex("[,，;؛\\n]+" )).filter { it.isNotBlank() }
                    parts.forEachIndexed { index, part ->
                        if (!active || epoch != speechEpoch.get()) { caption.update(status = "Interrupted"); return@submit }
                        val start = now()
                        val audio = voices.speak(code, part) ?: return@forEachIndexed
                        val took = now() - start
                        if (epoch == speechEpoch.get()) player.speech(Pcm(audio.samples, audio.sampleRate), first = {
                            caption.update(status = if (parts.size > 1) "Speaking ${index + 1} of ${parts.size}" else "Speaking…")
                            message("RX #${frame.sequence} part ${index + 1} · first playback ${now() - receivedAt} ms · TTS $took ms · RTF ${"%.3f".format(took / (audio.samples.size * 1000.0 / audio.sampleRate))}")
                        }, done = { complete ->
                            if (!complete) caption.update(status = "Interrupted") else if (index == parts.lastIndex) caption.update(status = "Spoken")
                        })
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
        conversation.add(Conversation.Kind.ALERT, "Alert · ${languageName(code)}", alertText(id, code), "Full-volume alert")
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
    private fun neededFiles(l: LanguageEntry): List<String> {
        val e = manifest.engines.getValue(l.tts.engine)
        return listOf(l.stt.model, l.stt.tokens, l.stt.encoder, l.stt.decoder, e.model, e.tokens).filter { it.isNotEmpty() }
    }
    private fun installed(l: LanguageEntry) = neededFiles(l).all { File(it).isFile }
    private fun pickerLabel(l: LanguageEntry): String {
        if (installed(l)) return l.name
        val plan = downloader.plan(neededFiles(l)) ?: return "${l.name} · not installed"
        return "${l.name} · download ${AppFootprint.mb(plan.sumOf { it.bytes })}"
    }
    private fun refreshPicker() {
        picker.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, pickerLangs.map { pickerLabel(it) }))
        manifest.languages[language]?.let { picker.setText(pickerLabel(it), false) }
    }
    private fun restorePicker() { manifest.languages[language]?.let { picker.setText(pickerLabel(it), false) } }
    private fun languageName(code: String) = if (::manifest.isInitialized) manifest.languages[code]?.name ?: code else code
    private fun alertText(id: Int, code: String) =
        alertTexts[id]?.optString(code)?.takeIf { it.isNotBlank() } ?: alertNames[id]?.replace('_', ' ') ?: "Alert $id"

    /** Every speech language with its state; download what's missing, delete what can be downloaded again. */
    private fun manageLanguages() {
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(4), dp(24), dp(8)) }
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        fun render() {
            rows.removeAllViews()
            rows.addView(MaterialTextView(this).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                text = "Deleting a language only removes its speech recognition. All voices stay, so you still hear messages in every language."
                setPadding(0, 0, 0, dp(8))
            })
            pickerLangs.forEach { l ->
                val isInstalled = installed(l)
                val removable = if (isInstalled) downloader.removable(neededFiles(l)) else emptyList()
                val plan = if (isInstalled) null else downloader.plan(neededFiles(l))
                val state = when {
                    !isInstalled && plan != null -> "${AppFootprint.mb(plan.sumOf { it.bytes })} to download"
                    !isInstalled -> "Not installed · copy by cable"
                    removable.isEmpty() -> "Built in"
                    else -> "Installed · ${AppFootprint.mb(removable.sumOf { it.length() })}"
                } + if (isInstalled && l.code == language) " · in use" else ""
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = dp(64); setPadding(0, dp(4), 0, dp(4))
                }
                line.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(MaterialTextView(context).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium); text = l.name })
                    addView(MaterialTextView(context).apply {
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium); text = state
                        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                fun action(title: String, destructive: Boolean, onClick: () -> Unit) = line.addView(
                    MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                        text = title; contentDescription = "$title ${l.name}"
                        if (destructive) setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError))
                        setOnClickListener { onClick() }
                    })
                when {
                    removable.isNotEmpty() && l.code != language -> action("Delete", true) { confirmDelete(l, removable) { render() } }
                    plan != null -> action("Download", false) { dialog.dismiss(); confirmDownload(l, plan) }
                }
                rows.addView(line)
            }
        }
        render()
        dialog = MaterialAlertDialogBuilder(this).setTitle("Speech languages")
            .setView(ScrollView(this).apply { addView(rows) })
            .setPositiveButton("Done", null).show()
    }

    private fun confirmDelete(l: LanguageEntry, files: List<File>, deleted: () -> Unit) {
        val size = AppFootprint.mb(files.sumOf { it.length() })
        MaterialAlertDialogBuilder(this).setTitle("Delete ${l.name}?")
            .setMessage("This frees $size. You won't be able to speak in ${l.name} until you download it again (internet needed once). Incoming ${l.name} messages still play.")
            .setPositiveButton("Delete") { _, _ ->
                if (l.code == language || downloading.get()) { message("${l.name} is in use; switch language first"); return@setPositiveButton }
                val freed = downloader.delete(files)
                message("Deleted ${l.name} speech recognition, freed ${AppFootprint.mb(freed)}")
                refreshPicker()
                footprint.refresh { p -> footprintParts = p; runOnUiThread { renderFootprint() } }
                deleted()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Picked in the dropdown: load it, or offer to download its missing model first. */
    private fun chooseLanguage(code: String) {
        val l = manifest.languages.getValue(code)
        val plan = downloader.plan(neededFiles(l))
        when {
            plan == null -> {
                restorePicker()
                MaterialAlertDialogBuilder(this).setTitle("${l.name} isn't installed")
                    .setMessage("This phone has no ${l.name} speech model, and this build can't download it. Copy it to the phone by cable (see models/README.md).")
                    .setPositiveButton("OK", null).show()
            }
            plan.isEmpty() -> loadLanguage(code)
            else -> confirmDownload(l, plan)
        }
    }

    private fun confirmDownload(l: LanguageEntry, plan: List<ModelDownloader.RemoteFile>) {
        val total = plan.sumOf { it.bytes }
        restorePicker()
        if (downloading.get()) { message("A download is already running"); return }
        if (downloader.freeBytes() < total + 50_000_000L) {
            MaterialAlertDialogBuilder(this).setTitle("Not enough space")
                .setMessage("${l.name} needs ${AppFootprint.mb(total)} but the phone has only ${AppFootprint.mb(downloader.freeBytes())} free.")
                .setPositiveButton("OK", null).show()
            return
        }
        MaterialAlertDialogBuilder(this).setTitle("Download ${l.name}?")
            .setMessage("${l.name} speech recognition isn't on this phone yet. Download it now (${AppFootprint.mb(total)})?\n\nInternet is needed once; after that it works offline.")
            .setPositiveButton("Download") { _, _ -> startDownload(l, plan) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startDownload(l: LanguageEntry, plan: List<ModelDownloader.RemoteFile>) {
        val total = plan.sumOf { it.bytes }
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        downloadCancel = cancel; downloading.set(true)
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply { max = 1000; isIndeterminate = false }
        val info = MaterialTextView(this).apply { text = "Connecting…"; setPadding(0, 24, 0, 0) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(64, 24, 64, 8); addView(bar); addView(info) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Downloading ${l.name}").setView(box).setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancel.set(true) }.show()
        val started = SystemClock.elapsedRealtime()
        var lastUi = 0L
        Thread {
            try {
                downloader.download(plan, cancel) { done, all ->
                    val t = SystemClock.elapsedRealtime()
                    if (t - lastUi >= 200 || done == all) {
                        lastUi = t
                        val rate = done / 1e6 / ((t - started).coerceAtLeast(1) / 1000.0)
                        runOnUiThread {
                            if (!destroyed) {
                                bar.progress = (1000 * done / all.coerceAtLeast(1)).toInt()
                                info.text = "${AppFootprint.mb(done)} of ${AppFootprint.mb(all)} · %.1f MB/s".format(rate)
                            }
                        }
                    }
                }
                val secs = (SystemClock.elapsedRealtime() - started) / 1000.0
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    dialog.dismiss()
                    message("Downloaded ${l.name} (${AppFootprint.mb(total)} in %.0f s), size and checksum verified".format(secs))
                    refreshPicker(); picker.setText(pickerLabel(l), false)
                    loadLanguage(l.code)
                    footprint.refresh { p -> footprintParts = p; runOnUiThread { renderFootprint() } }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    dialog.dismiss(); restorePicker()
                    message("${l.name} download stopped: ${e.message}")
                    if (!cancel.get()) {
                        val offline = e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException
                        MaterialAlertDialogBuilder(this).setTitle("Download failed")
                            .setMessage(when {
                                offline -> "No internet connection. Connect once to download ${l.name}, or copy it to the phone by cable."
                                // e.g. college/hotel Wi-Fi that answers for github.com with its own certificate
                                e is javax.net.ssl.SSLException -> "This network intercepted the secure connection (a login page or firewall, common on college and hotel Wi-Fi). Sign in to the network, or use mobile data or a hotspot, then try again.\n\nNothing was installed."
                                else -> "${e.message}\n\nNothing was installed; try again."
                            })
                            .setPositiveButton("OK", null).show()
                    }
                }
            } finally { downloading.set(false); downloadCancel = null }
        }.apply { name = "itantra-download"; isDaemon = true; start() }
    }

    /** Badge: total size, CPU and RAM. Dialog (when open): every part and live usage. */
    private fun renderFootprint() {
        if (destroyed || !::footprintBadge.isInitialized) return
        val parts = footprintParts; val u = footprintUsage
        val total = parts.sumOf { it.bytes }
        val usage = u?.let { "CPU %.0f%% · RAM %.0f MB".format(it.cpuOfPhone, it.pssMb) } ?: "CPU … · RAM …"
        footprintBadge.text = "${if (total > 0) AppFootprint.mb(total) else "…"}\n$usage"
        val rows = footprintRows ?: return
        rows.removeAllViews()
        fun row(label: String, value: String, bold: Boolean = false) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 6) }
            line.addView(MaterialTextView(this).apply { text = label; textSize = 14f; if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD) },
                LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(MaterialTextView(this).apply { text = value; textSize = 14f; gravity = android.view.Gravity.END
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(-2, -2))
            rows.addView(line)
        }
        fun heading(text: String) = rows.addView(MaterialTextView(this).apply { this.text = text; textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 24, 0, 8) })
        heading("Storage on this phone")
        row("Total", if (total > 0) AppFootprint.mb(total) else "measuring…", bold = true)
        parts.forEach { row(it.label, AppFootprint.mb(it.bytes)) }
        heading("Live usage (updates every 2 s)")
        if (u == null) row("CPU / RAM", "measuring…") else {
            row("CPU, whole phone", "%.1f%%".format(u.cpuOfPhone))
            row("CPU, one core (of ${u.cores})", "%.0f%%".format(u.cpuOfOneCore))
            row("RAM (PSS)", "%.0f MB".format(u.pssMb), bold = true)
            row("  Java heap", "%.0f MB".format(u.javaHeapMb))
            row("  Native heap (models, audio)", "%.0f MB".format(u.nativeHeapMb))
        }
    }

    private fun showFootprint() {
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 8, 48, 8) }
        footprintRows = rows
        renderFootprint()
        footprint.refresh { p -> footprintParts = p; runOnUiThread { renderFootprint() } }
        MaterialAlertDialogBuilder(this).setTitle("App size and usage")
            .setView(ScrollView(this).apply { addView(rows) })
            .setPositiveButton("Close", null)
            .setOnDismissListener { footprintRows = null }
            .show()
    }

    private fun submit(executor: ThreadPoolExecutor, block: () -> Unit) {
        if (executor.isShutdown) return
        try { executor.execute { try { block() } catch (e: Exception) { message(e.message ?: e.javaClass.simpleName) } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { message("Busy: work queue full; input dropped") }
    }
    override fun onResume() {
        super.onResume(); active = true
        footprint.start({ u -> footprintUsage = u; runOnUiThread { renderFootprint() } }, { p -> footprintParts = p; runOnUiThread { renderFootprint() } })
        if (::mic.isInitialized) resumeContinuous()
        if (::discovery.isInitialized && !useBluetooth && hosting && link.listening) discovery.advertise(LinkService.PORT)
    }
    override fun onPause() { active = false; footprint.stop(); if (::discovery.isInitialized) discovery.pause(); if (::bluetoothDiscovery.isInitialized) bluetoothDiscovery.stop(); speechEpoch.incrementAndGet(); stopCapture(); if (::player.isInitialized) player.clearSpeech(); super.onPause() }
    override fun onDestroy() {
        destroyed = true; active = false; footprint.stop(); downloadCancel?.set(true); loadEpoch.incrementAndGet(); speechEpoch.incrementAndGet(); stopCapture()
        discovery.close(); bluetoothDiscovery.stop(); wifiLink.close(); bluetoothLink.close(); player.close()
        capture.queue.clear()
        submit(capture) { vad?.release(); vad = null }; capture.shutdown()
        inference.queue.clear()
        submit(inference) { recognizer?.release() }; inference.shutdown()
        synthesis.queue.clear()
        submit(synthesis) { if (::voices.isInitialized) voices.release() }; synthesis.shutdown()
        super.onDestroy()
    }
    companion object {
        private fun now() = SystemClock.elapsedRealtime()
        private fun executor(name: String, size: Int) = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(size), { r -> Thread(r, "itantra-$name") })
        private fun warmup(code: String) = mapOf("en" to "ready", "hi" to "नमस्ते", "bn" to "নমস্কার", "gu" to "નમસ્તે", "kn" to "ನಮಸ್ಕಾರ", "ml" to "നമസ്കാരം", "mr" to "नमस्कार", "or" to "ନମସ୍କାର", "ta" to "வணக்கம்", "te" to "నమస్కారం").getValue(code)
    }
}
