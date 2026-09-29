package org.itantra.app

import org.itantra.app.audio.MicLevel
import org.itantra.app.speech.InferenceWorkers
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeviceFixTest {
    @Test fun outgoingRecognitionDoesNotWaitForBlockedSynthesis() {
        val workers = InferenceWorkers()
        val started = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val recognized = CountDownLatch(1)
        val nextVoice = CountDownLatch(1)
        try {
            workers.tts.execute { started.countDown(); unblock.await(5, TimeUnit.SECONDS) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            workers.tts.execute { nextVoice.countDown() }
            workers.stt.execute { recognized.countDown() }
            assertTrue(recognized.await(2, TimeUnit.SECONDS))
            assertEquals(1L, nextVoice.count) // TTS remains serialized independently.
            unblock.countDown()
            assertTrue(nextVoice.await(2, TimeUnit.SECONDS))
        } finally {
            unblock.countDown(); workers.stt.shutdownNow(); workers.tts.shutdownNow()
        }
    }
    @Test fun meterHandlesSilenceSpeechLevelAndClippingWithoutRetainingAudio() {
        assertEquals(0, MicLevel.percent(floatArrayOf()))
        assertEquals(0, MicLevel.percent(FloatArray(512)))
        assertEquals(0, MicLevel.percent(floatArrayOf(0.001f))) // -60 dBFS
        assertEquals(50, MicLevel.percent(floatArrayOf(0.03162278f))) // -30 dBFS
        assertEquals(100, MicLevel.percent(floatArrayOf(-1f, 1f)))
        assertEquals(100, MicLevel.percent(floatArrayOf(2f)))
        assertEquals(0, MicLevel.percent(floatArrayOf(Float.NaN)))
    }
}
