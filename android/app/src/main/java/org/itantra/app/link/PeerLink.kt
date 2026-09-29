package org.itantra.app.link

/** The speech pipeline is independent of the underlying byte stream. */
interface PeerLink {
    val connected: Boolean
    val listening: Boolean
    fun send(frame: Frame, done: (Int) -> Unit = {})
    fun disconnect()
    fun close()
}
