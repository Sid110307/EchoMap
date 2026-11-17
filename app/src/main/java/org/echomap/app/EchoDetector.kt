package org.echomap.app

import kotlin.math.*

/** Matched filtering uses every sample: decimating a high-frequency chirp aliases it. */
object EchoDetector {
    const val RATE = 48000
    const val CAPTURE_SAMPLES = 24576 // 512 ms includes Android's playback startup latency.
    const val MIN_RANGE = 0.55
    const val MAX_RANGE = 5.0
    private const val SOUND_SPEED = 343.0

    sealed interface Detection
    data class Echo(val distance: Double, val strength: Float) : Detection
    enum class Rejection : Detection { NO_PULSE, NO_ECHO, CLIPPED, INCOMPLETE }

    fun chirp(): ShortArray {
        // Audible 4–12 kHz works on more built-in speakers/mics than 17.5–19.5 kHz.
        val length = 576
        return ShortArray(length) { i ->
            val t = i.toDouble() / RATE
            val phase = 2 * PI * (4000 * t + 8000 / (2.0 * length / RATE) * t * t)
            (sin(phase) * sin(PI * i / (length - 1)).pow(2) * 10000).roundToInt().toShort()
        }
    }

    fun estimate(signal: ShortArray, pulse: ShortArray = chirp()): Detection {
        if (pulse.isEmpty() || signal.size < pulse.size) return Rejection.INCOMPLETE
        if (signal.count { abs(it.toInt()) >= 32760 } >= max(3, pulse.size / 100)) return Rejection.CLIPPED
        val pulseEnergy = pulse.sumOf { it.toDouble() * it }
        if (pulseEnergy == 0.0) return Rejection.NO_PULSE
        // Include the last valid lag, including a pulse ending at the capture boundary.
        val scores = DoubleArray(signal.size - pulse.size + 1)
        val energies = DoubleArray(signal.size + 1)
        for (i in signal.indices) energies[i + 1] = energies[i] + signal[i].toDouble() * signal[i]
        for (lag in scores.indices) {
            var sum = 0.0
            for (i in pulse.indices) sum += signal[lag + i].toDouble() * pulse[i]
            scores[lag] = abs(sum)
        }
        val sorted = scores.sortedArray()
        // A robust noise estimate excludes the sparse direct/echo peaks.
        val noiseThreshold = sorted[sorted.size / 2] / 0.67449 * 8
        val strongest = scores.maxOrNull() ?: return Rejection.NO_PULSE
        if (strongest <= max(noiseThreshold, 1.0)) return Rejection.NO_PULSE
        val threshold = max(noiseThreshold, strongest * 0.20)
        fun correlation(lag: Int): Double {
            val energy = energies[lag + pulse.size] - energies[lag]
            return if (energy > 0) scores[lag] / sqrt(energy * pulseEnergy) else 0.0
        }
        // Use the first significant arrival, even when a reflection is louder.
        val first = scores.indices.firstOrNull { scores[it] >= threshold && correlation(it) >= 0.15 }
            ?: return Rejection.NO_PULSE
        val direct = (first..min(first + 24, scores.lastIndex)).maxBy { scores[it] }
        val minDelay = ceil(MIN_RANGE * 2 / SOUND_SPEED * RATE).toInt()
        val maxDelay = floor(MAX_RANGE * 2 / SOUND_SPEED * RATE).toInt()
        if (direct + maxDelay > scores.lastIndex) return Rejection.INCOMPLETE
        val start = direct + minDelay
        val end = direct + maxDelay
        val peak = (start..end).maxBy { scores[it] }
        val quality = scores[peak] / scores[direct]
        if (scores[peak] <= max(noiseThreshold, scores[direct] * 0.045)) return Rejection.NO_ECHO
        return Echo((peak - direct).toDouble() / RATE * SOUND_SPEED / 2, quality.toFloat().coerceAtMost(1f))
    }
}
