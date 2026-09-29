package org.itantra.app.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** DNS-SD on the local network. All state and callbacks are confined to the main thread. */
@Suppress("DEPRECATION") // Listener APIs retain support for Android 8–12.
class DeviceDiscovery(
    context: Context,
    private val devicesChanged: (List<String>) -> Unit,
    private val notice: (String) -> Unit,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("discovery", Context.MODE_PRIVATE)
    private val suffix = prefs.getString("instance", null) ?: UUID.randomUUID().toString().take(4).also {
        prefs.edit().putString("instance", it).apply()
    }
    private val requestedName = "iTantra ${Build.MODEL.take(30)} · $suffix"
    private var ownName = requestedName
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val found = linkedMapOf<String, NsdServiceInfo>()
    private var generation = 0
    private var resolving = false
    private var closed = false
    private val timeout = Runnable {
        stopScan(clear = false)
        notice(if (found.isEmpty()) "No hosts found. Tap Host on the other phone, check Wi-Fi, then scan again."
            else "Scan finished. Pick a phone, or scan again to refresh.")
    }

    fun advertise(port: Int) {
        if (closed || registration != null) return
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { main.post {
                if (registration !== this || closed) { runCatching { nsd.unregisterService(this) }; return@post }
                ownName = info.serviceName
                notice("Visible as $ownName. The other phone can scan and select you.")
            } }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { main.post {
                if (registration === this) { registration = null; notice("Could not advertise this phone ($code). Try Host again.") }
            } }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        registration = listener
        try {
            nsd.registerService(NsdServiceInfo().apply {
                serviceName = requestedName; serviceType = TYPE; this.port = port
            }, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) { registration = null; notice("Discovery unavailable: ${e.message}") }
    }

    fun stopAdvertising() {
        val listener = registration ?: return
        registration = null
        runCatching { nsd.unregisterService(listener) }
    }

    fun scan() {
        if (closed) return
        stopScan()
        val epoch = generation
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { main.post {
                if (discovery === this) notice("Searching for iTantra hosts on this Wi-Fi network…")
            } }
            override fun onServiceFound(info: NsdServiceInfo) { main.post {
                if (epoch != generation || discovery !== this || closed) return@post
                if (info.serviceType.trimEnd('.') != TYPE || info.serviceName == ownName || info.serviceName == requestedName) return@post
                found[info.serviceName] = info
                devicesChanged(found.keys.sorted())
                notice("Tap a phone below to connect.")
            } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post {
                if (epoch != generation || discovery !== this) return@post
                found.remove(info.serviceName); devicesChanged(found.keys.sorted())
                if (found.isEmpty()) notice("No hosts available yet. Tap Host on the other phone.")
            } }
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { main.post {
                if (discovery === this) { stopScan(); notice("Scan failed ($code). Check Wi-Fi and try again.") }
            } }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        }
        discovery = listener
        notice("Searching for iTantra hosts on this Wi-Fi network…")
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            main.postDelayed(timeout, 30_000)
        } catch (e: Exception) { stopScan(); notice("Cannot scan: ${e.message}") }
    }

    fun select(name: String, connect: (String, Int) -> Unit) {
        if (closed || resolving) return
        val service = found[name] ?: run { notice("That phone is no longer available. Scan again."); return }
        val epoch = generation
        resolving = true
        notice("Connecting to $name…")
        try {
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) { main.post {
                    resolving = false
                    if (epoch == generation && !closed) notice("Could not reach $name ($code). Scan again.")
                } }
                override fun onServiceResolved(info: NsdServiceInfo) { main.post {
                    resolving = false
                    if (epoch != generation || closed) return@post
                    if (!found.containsKey(name)) { notice("That phone went offline. Scan again."); return@post }
                    val host = info.host?.hostAddress
                    if (host == null || info.port !in 1..65535) { notice("Invalid host address. Scan again."); return@post }
                    stopScan()
                    connect(host, info.port)
                } }
            })
        } catch (e: Exception) { resolving = false; notice("Could not connect: ${e.message}") }
    }

    fun stopScan(clear: Boolean = true) {
        main.removeCallbacks(timeout)
        if (clear) { generation++; found.clear(); devicesChanged(emptyList()) }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }
    fun pause() {
        val wasScanning = discovery != null
        stopScan(); stopAdvertising()
        if (wasScanning && !closed) notice("Scan stopped. Tap Scan for devices to search again.")
    }
    fun close() { closed = true; pause() }
    companion object { const val TYPE = "_itantra._tcp" }
}
