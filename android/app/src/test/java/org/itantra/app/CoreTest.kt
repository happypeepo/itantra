package org.itantra.app

import org.itantra.app.audio.SampleRing
import org.itantra.app.audio.Wav
import org.itantra.app.link.Frame
import org.itantra.app.speech.TextSanitizer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CoreTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun pythonWireVectorsMatchByteForByte() {
        val json = JSONObject(javaClass.getResource("/test_vectors.json")!!.readText())
        val vectors = json.getJSONArray("vectors")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val bytes = hex(v.getString("hex"))
            if (v.has("expected")) { rejects { Frame.decode(bytes) }; continue }
            val decoded = Frame.decode(bytes)
            assertEquals(v.getInt("seq"), decoded.sequence)
            if (v.has("text")) {
                assertEquals(v.getString("text"), decoded.text())
                assertArrayEquals(bytes, Frame.speech(v.getString("text"), v.getInt("wire_id"), v.getInt("seq")).encode())
            } else assertArrayEquals(bytes, decoded.encode())
        }
        assertEquals(0x29b1, Frame.crc16("123456789".toByteArray()))
    }
    @Test fun corruptAndMalformedFramesAreRejected() {
        val valid = Frame.speech("नमस्ते", 0, 65537).encode()
        assertEquals(1, Frame.decode(valid).sequence)
        for (i in valid.indices) {
            val corrupted = valid.copyOf(); corrupted[i] = (corrupted[i].toInt() xor 1).toByte()
            rejects { Frame.decode(corrupted) }
        }
        rejects { Frame.decode(valid.copyOf(valid.size - 1)) }
        rejects { Frame.decode(Frame(2, 0, 0, byteArrayOf()).encode()) }
        rejects { Frame.decode(Frame(3, 0, 0, byteArrayOf(1)).encode()) }
        rejects { Frame.decode(Frame(17, 1, 0, byteArrayOf(0x80.toByte())).encode()) }
        rejects { Frame.decode(Frame(99, 0, 0, byteArrayOf()).encode()) }
        rejects { Frame.speech("a".repeat(65536), 1, 1).encode() }
    }
    @Test fun mixedScriptsAndEmojiFallBackLosslessly() {
        for (text in listOf("தமிழ்।", "hello 🙂", "हिन्दी বাংলা", "abc")) {
            val encoded = Frame.speech(text, 8, 7)
            assertEquals(1, encoded.type)
            assertEquals(text, Frame.decode(encoded.encode()).text())
        }
    }
    @Test fun prerollUsesOnlyRecordedSamplesAcrossWrapAndReset() {
        val ring = SampleRing(5)
        ring.append(floatArrayOf(1f, 2f, 3f))
        assertArrayEquals(floatArrayOf(1f, 2f), ring.range(-10, 2), 0f)
        ring.append(floatArrayOf(4f, 5f, 6f, 7f))
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f), ring.range(0, 6), 0f)
        ring.clear()
        assertEquals(0, ring.range(-4800, 0).size)
    }
    private fun wav(rate: Int, channels: Int = 1): ByteArray {
        val b = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(40).put("WAVEfmt ".toByteArray()).putInt(16)
        b.putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(4).putShort(-32768).putShort(16384)
        return b.array()
    }
    @Test fun wavPreservesEveryVoiceSampleRateAndRejectsTruncation() {
        for (rate in listOf(16000, 22050, 24000)) {
            val pcm = Wav.decode(wav(rate))
            assertEquals(rate, pcm.rate)
            assertArrayEquals(floatArrayOf(-1f, 0.5f), pcm.samples, 0f)
        }
        rejects { Wav.decode(wav(24000).copyOf(47)) }
        rejects { Wav.decode(wav(24000, 2)) }
    }
    @Test fun sanitizerTrimsSentenceEndsAndFiltersUnknownCharacters() {
        assertEquals("hello", TextSanitizer(null, false).clean(" hello! । "))
        val tokens = File.createTempFile("tokens", ".txt")
        try {
            tokens.writeText("a 0\nb 1\n  2\n")
            assertEquals("ab a", TextSanitizer(tokens, true).clean("ab🙂 a!"))
        } finally { tokens.delete() }
    }
}
