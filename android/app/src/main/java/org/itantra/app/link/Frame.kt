package org.itantra.app.link

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Byte-compatible with p2-models/scripts/frame.py. */
data class Frame(val type: Int, val language: Int, val sequence: Int, val payload: ByteArray) {
    fun encode(): ByteArray {
        require(language in 0..9 && payload.size <= 65535)
        val body = ByteBuffer.allocate(6 + payload.size).put(type.toByte()).put(language.toByte())
            .putShort(sequence.toShort()).putShort(payload.size.toShort()).put(payload).array()
        return body + byteArrayOf((crc16(body) shr 8).toByte(), crc16(body).toByte())
    }
    fun text(): String = when (type) {
        1 -> Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload)).toString()
        17 -> payload.joinToString("") {
            val b = it.toInt() and 255
            (if (b < 128) b else bases[language] + b - 128).toChar().toString()
        }
        else -> error("Not speech")
    }
    companion object {
        private val bases = intArrayOf(0x900, 0, 0x980, 0xa80, 0xc80, 0xd00, 0x900, 0xb00, 0xb80, 0xc00)
        fun speech(text: String, language: Int, sequence: Int): Frame {
            require(language in 0..9)
            val utf8 = text.toByteArray(Charsets.UTF_8)
            val base = bases[language]
            val packed = if (base != 0 && text.all { it.code < 128 || it.code in base until base + 128 })
                text.map { (if (it.code < 128) it.code else it.code - base + 128).toByte() }.toByteArray() else null
            return if (packed != null && packed.size < utf8.size) Frame(17, language, sequence, packed)
                else Frame(1, language, sequence, utf8)
        }
        fun decode(bytes: ByteArray): Frame {
            require(bytes.size >= 8) { "Short frame" }
            val b = ByteBuffer.wrap(bytes)
            val type = b.get().toInt() and 255
            val lang = b.get().toInt() and 255
            val seq = b.short.toInt() and 65535
            val size = b.short.toInt() and 65535
            require(bytes.size == size + 8) { "Length mismatch" }
            require(crc16(bytes.copyOf(bytes.size - 2)) == (ByteBuffer.wrap(bytes.takeLast(2).toByteArray()).short.toInt() and 65535)) { "CRC mismatch - dropped" }
            require(lang in 0..9 && type in listOf(1, 17, 2, 3)) { "Unknown type/language" }
            require(type != 17 || bases[lang] != 0) { "Invalid packed language" }
            require(type != 2 || size == 1) { "Invalid alert length" }
            require(type != 3 || size == 0) { "Invalid ping length" }
            val frame = Frame(type, lang, seq, bytes.copyOfRange(6, 6 + size))
            if (type == 1 || type == 17) frame.text() // Validate before delivery.
            return frame
        }
        fun crc16(bytes: ByteArray): Int {
            var crc = 65535
            for (b in bytes) {
                crc = crc xor ((b.toInt() and 255) shl 8)
                repeat(8) { crc = (if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1) and 65535 }
            }
            return crc
        }
    }
}
