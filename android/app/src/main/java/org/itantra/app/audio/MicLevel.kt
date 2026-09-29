package org.itantra.app.audio

import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** RMS meter: -60 dBFS to 0 dBFS, with no recording or speech recognition. */
object MicLevel {
    fun percent(samples: FloatArray): Int {
        if (samples.isEmpty()) return 0
        val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
        if (!rms.isFinite() || rms <= 0) return 0
        return ((20 * log10(rms) + 60) / 60 * 100).roundToInt().coerceIn(0, 100)
    }
}
