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

@SuppressLint("MissingPermission") // Called after Activity permission checks.
@Suppress("DEPRECATION")
class BluetoothDiscovery(private val context: Context, private val adapter: BluetoothAdapter?,
                         private val changed: (List<BluetoothDevice>) -> Unit, private val notice: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, BluetoothDevice>()
    private var receiver: BroadcastReceiver? = null
    private val timeout = Runnable { finishScan() }
    fun scan() {
        stop()
        val radio = adapter ?: run { notice("Bluetooth is unavailable on this device"); return }
        try {
            radio.bondedDevices.forEach { devices[it.address] = it }
            changed(devices.values.toList())
            val listener = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (receiver !== this) return
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                            devices[device.address] = device; changed(devices.values.toList())
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
                addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }, ContextCompat.RECEIVER_EXPORTED)
            notice("Searching Bluetooth… Select the phone hosting iTantra. Paired devices are also shown.")
            if (!radio.startDiscovery()) finishScan()
            else main.postDelayed(timeout, 20_000)
        } catch (e: Exception) { stop(); notice("Bluetooth scan failed: ${e.message}") }
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
        if (clear) { devices.clear(); changed(emptyList()) }
    }
}
