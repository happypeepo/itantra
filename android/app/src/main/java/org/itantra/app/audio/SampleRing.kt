package org.itantra.app.audio

/** Absolute sample positions let VAD recover real pre-roll without synthetic silence. */
class SampleRing(private val capacity: Int) {
    private val data = FloatArray(capacity)
    var end = 0L; private set
    fun append(samples: FloatArray) { samples.forEach { data[(end++ % capacity).toInt()] = it } }
    fun range(start: Long, stop: Long): FloatArray {
        val from = maxOf(0, end - capacity, start)
        val to = minOf(end, stop)
        return FloatArray(maxOf(0L, to - from).toInt()) { data[((from + it) % capacity).toInt()] }
    }
    fun clear() { end = 0 }
}
