package org.itantra.app.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Classic Bluetooth discovery, reporting only devices with a real name: a phone hosting iTantra is
 * discoverable and always has one, while the many nameless LE gadgets in a crowded room would
 * otherwise show up as bare addresses. Names come from the scan broadcast itself (EXTRA_NAME) or a
 * later ACTION_NAME_CHANGED, since BluetoothDevice.getName() is often still null when a device is
 * found. Updates are coalesced, because a busy room fires hundreds of ACTION_FOUNDs and rebuilding
 * the list on each one froze the UI thread (ANR on ColorOS).
 */
@SuppressLint("MissingPermission") // Called after Activity permission checks.
@Suppress("DEPRECATION")
class BluetoothDiscovery(private val context: Context, private val adapter: BluetoothAdapter?,
                         private val changed: (List<Pair<BluetoothDevice, String>>) -> Unit, private val notice: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, Pair<BluetoothDevice, String>>()
    private var receiver: BroadcastReceiver? = null
    private val timeout = Runnable { finishScan() }
    private var publishPending = false
    private val publish = Runnable { publishPending = false; changed(devices.values.toList()) }

    fun scan() {
        stop()
        val radio = adapter ?: run { notice("Bluetooth is unavailable on this device"); return }
        try {
            radio.bondedDevices.forEach { remember(it, runCatching { it.name }.getOrNull()) }
            changed(devices.values.toList())
            val listener = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (receiver !== this) return
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> {
                            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                            val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: runCatching { device.name }.getOrNull()
                            if (remember(device, name) && !publishPending) { publishPending = true; main.postDelayed(publish, 250) }
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> finishScan()
                        BluetoothAdapter.ACTION_STATE_CHANGED -> if (!radio.isEnabled) {
                            stop(); notice("Bluetooth is off. Turn it on and scan again.")
                        }
                    }
                }
            }
            receiver = listener
            ContextCompat.registerReceiver(context, listener, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED); addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }, ContextCompat.RECEIVER_EXPORTED)
            notice("Searching Bluetooth… Select the phone hosting iTantra. Paired devices are also shown.")
            if (!radio.startDiscovery()) finishScan()
            else main.postDelayed(timeout, 20_000)
        } catch (e: Exception) { stop(); notice("Bluetooth scan failed: ${e.message}") }
    }

    /** True if the visible list changed. Nameless devices, or "names" that are just the address, are skipped. */
    private fun remember(device: BluetoothDevice, name: String?): Boolean {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() && !it.equals(device.address, ignoreCase = true) } ?: return false
        if (devices[device.address]?.second == clean) return false
        devices[device.address] = device to clean
        return true
    }

    private fun finishScan() {
        stop(clear = false)
        notice(if (devices.isEmpty()) "No Bluetooth devices found. Make the other iTantra phone discoverable with Host, then scan again."
            else "Select the phone hosting iTantra. Approve Android’s pairing prompt if asked.")
    }
    fun stop(clear: Boolean = true) {
        main.removeCallbacks(timeout)
        receiver?.let { runCatching { context.unregisterReceiver(it) } }; receiver = null
        runCatching { adapter?.cancelDiscovery() }
        if (publishPending) { main.removeCallbacks(publish); publishPending = false; if (!clear) changed(devices.values.toList()) }
        if (clear) { devices.clear(); changed(emptyList()) }
    }
}
