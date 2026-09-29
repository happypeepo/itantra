package org.itantra.app.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Pcm(val samples: FloatArray, val rate: Int)
object Wav {
    fun decode(bytes: ByteArray): Pcm {
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Not a WAV file" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var rate = 0
        var pcm: ByteArray? = null
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = b.getInt(offset + 4)
            require(size >= 0 && size.toLong() + offset + 8 <= bytes.size) { "Truncated WAV" }
            val start = offset + 8
            if (id == "fmt ") {
                require(size >= 16 && b.getShort(start).toInt() == 1 && b.getShort(start + 2).toInt() == 1 && b.getShort(start + 14).toInt() == 16) { "Expected mono PCM16 WAV" }
                rate = b.getInt(start + 4)
            }
            if (id == "data") pcm = bytes.copyOfRange(start, start + size)
            offset = start + size + (size and 1)
        }
        val data = requireNotNull(pcm) { "Missing WAV data" }
        require(rate in 8000..96000 && data.size % 2 == 0 && data.isNotEmpty())
        val values = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return Pcm(FloatArray(data.size / 2) { values.short / 32768f }, rate)
    }
}
