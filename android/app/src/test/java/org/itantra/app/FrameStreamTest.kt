package org.itantra.app

import org.itantra.app.link.Frame
import org.itantra.app.link.FrameStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

class FrameStreamTest {
    @Test fun fragmentedSpeechAlertAndPingShareTheSameStream() {
        val frames = listOf(Frame.speech("नमस्ते", 0, 1), Frame(2, 8, 2, byteArrayOf(2)), Frame(3, 0, 4, byteArrayOf()))
        val bytes = frames.fold(byteArrayOf()) { all, frame -> all + frame.encode() }
        val fragmented = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        frames.forEach { assertArrayEquals(it.encode(), FrameStream.read(fragmented)) }
        assertEquals(-1, fragmented.read())
    }
    @Test fun truncatedPacketFailsInsteadOfSpeakingPartialData() {
        val bytes = Frame.speech("Do not play partial speech", 1, 1).encode()
        for (length in listOf(0, 3, 6, bytes.size - 1)) {
            try { FrameStream.read(ByteArrayInputStream(bytes.copyOf(length))); fail("Expected EOF") }
            catch (_: EOFException) { }
        }
    }
    @Test fun corruptFrameDoesNotConsumeFollowingValidFrame() {
        val bad = Frame(2, 0, 1, byteArrayOf(1)).encode().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val good = Frame.speech("Next message", 1, 2).encode()
        val input = ByteArrayInputStream(bad + good)
        try { Frame.decode(FrameStream.read(input)); fail("Expected CRC rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals("Next message", Frame.decode(FrameStream.read(input)).text())
    }
}
