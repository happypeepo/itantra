package org.itantra.app.speech

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Each native engine has one owner. A slow TTS request cannot queue ahead of STT. */
class InferenceWorkers {
    val stt = worker("stt")
    val tts = worker("tts")
    private fun worker(name: String) = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(16), { task -> Thread(task, "itantra-$name") })
}
