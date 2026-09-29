package org.itantra.app.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Secure RFCOMM: Android handles pairing; only the matching iTantra UUID connects. */
@SuppressLint("MissingPermission") // Activity checks runtime permissions before entering this transport.
class BluetoothLink(private val adapter: BluetoothAdapter?, private val status: (String) -> Unit,
                    private val receive: (Frame) -> Unit) : PeerLink {
    private val generation = AtomicInteger()
    private val timeout = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "itantra-bluetooth-timeout").apply { isDaemon = true }
    }
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(32))
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var awaitingPeer = false
    override val connected get() = socket?.isConnected == true
    override val listening get() = awaitingPeer

    fun connect(device: BluetoothDevice? = null) {
        disconnect()
        val epoch = generation.get()
        Thread {
            try {
                val radio = requireNotNull(adapter) { "Bluetooth is unavailable" }
                check(radio.isEnabled) { "Turn on Bluetooth" }
                radio.cancelDiscovery()
                val peer = if (device == null) {
                    val listener = radio.listenUsingRfcommWithServiceRecord("iTantra", SERVICE_UUID)
                    synchronized(this) {
                        if (epoch != generation.get()) { listener.close(); return@Thread }
                        server = listener; awaitingPeer = true
                    }
                    status("Hosting Bluetooth · waiting for a phone")
                    listener.accept().also { listener.close(); awaitingPeer = false }
                } else {
                    val pending = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                    synchronized(this) {
                        if (epoch != generation.get()) { pending.close(); return@Thread }
                        socket = pending
                    }
                    status("Connecting to ${device.name ?: "Bluetooth phone"} · approve pairing if asked")
                    val deadline = timeout.schedule({
                        if (epoch == generation.get() && !pending.isConnected) runCatching { pending.close() }
                    }, 30, TimeUnit.SECONDS)
                    try { pending.connect() } finally { deadline.cancel(false) }
                    pending
                }
                synchronized(this) {
                    if (epoch != generation.get()) { peer.close(); return@Thread }
                    socket = peer
                }
                status("Connected over Bluetooth")
                val input = peer.inputStream
                while (epoch == generation.get()) {
                    try { receive(Frame.decode(FrameStream.read(input))) }
                    catch (e: IllegalArgumentException) { status("Dropped: ${e.message}") }
                }
            } catch (e: Exception) {
                if (epoch == generation.get()) { disconnect(); status("Disconnected: ${e.message}. Ensure the other phone is hosting iTantra over Bluetooth.") }
            }
        }.apply { name = "itantra-bluetooth"; isDaemon = true; start() }
    }
    override fun send(frame: Frame, done: (Int) -> Unit) {
        val peer = socket
        val bytes = frame.encode()
        try { writer.execute {
            try {
                check(peer != null && peer === socket && peer.isConnected) { "No Bluetooth connection" }
                peer.outputStream.write(bytes); done(bytes.size)
            } catch (e: Exception) { status("Send failed: ${e.message}") }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { status("Send queue unavailable; message dropped") }
    }
    @Synchronized override fun disconnect() {
        generation.incrementAndGet(); awaitingPeer = false
        runCatching { server?.close() }; server = null
        runCatching { socket?.close() }; socket = null
    }
    override fun close() { disconnect(); writer.shutdownNow(); timeout.shutdownNow() }
    companion object { val SERVICE_UUID: UUID = UUID.fromString("3c83e648-23e1-44bd-9bed-596ca0d26173") }
}
