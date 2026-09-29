package org.itantra.app.link

import java.io.DataInputStream
import java.io.InputStream

/** Both TCP and RFCOMM are byte streams: read exactly one bounded frame. */
object FrameStream {
    fun read(input: InputStream): ByteArray {
        val stream = DataInputStream(input)
        val header = ByteArray(6)
        stream.readFully(header)
        val size = ((header[4].toInt() and 255) shl 8) or (header[5].toInt() and 255)
        val rest = ByteArray(size + 2)
        stream.readFully(rest)
        return header + rest
    }
}
