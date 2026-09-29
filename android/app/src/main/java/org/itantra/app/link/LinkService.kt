package org.itantra.app.link

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/** Minimal P3 integration transport. One connection, bounded frames, serialized writes. */
class LinkService(private val status: (String) -> Unit, private val receive: (Frame) -> Unit) {
    private val generation = AtomicInteger()
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(32))
    @Volatile private var server: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    val connected get() = socket?.let { it.isConnected && !it.isClosed } == true
    fun connect(host: String?) {
        disconnect()
        val epoch = generation.get()
        Thread {
            try {
                val peer = if (host == null) {
                    val listener = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
                    synchronized(this) {
                        if (epoch != generation.get()) { listener.close(); return@Thread }
                        server = listener
                    }
                    status("Hosting ${addresses()}:$PORT")
                    listener.accept().also { listener.close() }
                } else {
                    Socket().also {
                        synchronized(this) {
                            if (epoch != generation.get()) { it.close(); return@Thread }
                            socket = it
                        }
                        status("Connecting to $host")
                        it.connect(InetSocketAddress(host, PORT), 5000)
                    }
                }
                synchronized(this) {
                    if (epoch != generation.get()) { peer.close(); return@Thread }
                    socket = peer
                }
                peer.tcpNoDelay = true
                status("Connected to ${peer.inetAddress.hostAddress}")
                val input = DataInputStream(peer.getInputStream())
                while (epoch == generation.get()) {
                    val header = ByteArray(6)
                    input.readFully(header)
                    val n = ((header[4].toInt() and 255) shl 8) or (header[5].toInt() and 255)
                    val rest = ByteArray(n + 2)
                    input.readFully(rest)
                    try { receive(Frame.decode(header + rest)) }
                    catch (e: IllegalArgumentException) { status("Dropped: ${e.message}") }
                }
            } catch (e: Exception) {
                if (epoch == generation.get()) { disconnect(); status("Disconnected: ${e.message}") }
            }
        }.apply { name = "itantra-link"; isDaemon = true; start() }
    }
    fun send(frame: Frame, done: (Int) -> Unit = {}) {
        val peer = socket
        val bytes = frame.encode()
        try { writer.execute {
            try {
                check(peer != null && peer === socket && !peer.isClosed) { "No connection" }
                peer.getOutputStream().write(bytes)
                done(bytes.size)
            } catch (e: Exception) { status("Send failed: ${e.message}") }
        } } catch (_: RejectedExecutionException) { status("Send queue unavailable; message dropped") }
    }
    @Synchronized fun disconnect() {
        generation.incrementAndGet()
        runCatching { server?.close() }; server = null
        runCatching { socket?.close() }; socket = null
    }
    fun close() { disconnect(); writer.shutdownNow() }
    companion object {
        const val PORT = 26173
        fun addresses(): String = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && it.address.size == 4 }.joinToString { it.hostAddress ?: "" }
        }.getOrDefault("IP unavailable")
    }
}
