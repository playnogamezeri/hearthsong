package com.hearthsong.app

import kotlin.math.*

/**
 * Auto-Tune Engine 3.0 pitch analysis/correction stage.
 *
 * Uses a YIN-style periodicity estimator, confidence gating, a smoothed pitch
 * trajectory, and conservative windowed overlap-add correction. This is a
 * lightweight monophonic estimator for a mono mix, not source separation.
 */
internal object VocalPitchEngine {
    enum class Finish { NATURAL, POLISHED, ROBOTIC }

    data class Result(val samples: ShortArray, val correctedFrames: Int)

    private data class PitchFrame(val midi: Double, val confidence: Double, val voiced: Boolean)

    fun correct(
        input: ShortArray,
        sampleRate: Int,
        key: Int,
        scale: Int,
        strength: Double,
        finish: Finish,
        retuneSpeed: Double = if (finish == Finish.NATURAL) 0.195 else 0.415
    ): Result {
        if (input.isEmpty() || strength <= 0.001) return Result(input, 0)

        val frameSize = 2048
        val hop = 512
        val half = frameSize / 2
        val frameCount = (input.size + hop - 1) / hop
        val analysis = ArrayList<PitchFrame>(frameCount)
        val notes = when (scale) {
            0 -> intArrayOf(0, 2, 4, 5, 7, 9, 11)
            1 -> intArrayOf(0, 2, 3, 5, 7, 8, 10)
            else -> IntArray(12) { it }
        }

        // Analyze the complete take first so correction can follow a stable contour.
        for (frameIndex in 0 until frameCount) {
            val center = frameIndex * hop
            val frame = DoubleArray(frameSize)
            var energy = 0.0
            for (i in 0 until frameSize) {
                val sample = input[(center - half + i).coerceIn(0, input.lastIndex)].toDouble()
                val window = 0.5 - 0.5 * cos(2.0 * Math.PI * i / (frameSize - 1))
                frame[i] = sample * window
                energy += sample * sample
            }
            val rms = sqrt(energy / frameSize)
            if (rms < 170.0) {
                analysis.add(PitchFrame(0.0, 0.0, false))
            } else {
                val estimate = yin(frame, sampleRate)
                analysis.add(
                    if (estimate.first > 0.0 && estimate.second >= 0.72)
                        PitchFrame(69.0 + 12.0 * ln(estimate.first / 440.0) / ln(2.0), estimate.second, true)
                    else PitchFrame(0.0, estimate.second, false)
                )
            }
        }

        val stableMidi = DoubleArray(frameCount)
        for (i in analysis.indices) {
            if (!analysis[i].voiced) continue
            val nearby = (max(0, i - 2)..min(frameCount - 1, i + 2))
                .filter { analysis[it].voiced }
                .map { analysis[it].midi }
                .sorted()
            stableMidi[i] = if (nearby.isEmpty()) analysis[i].midi else nearby[nearby.size / 2]
        }

        val output = DoubleArray(input.size)
        val weights = DoubleArray(input.size)
        var corrected = 0
        val maxCorrection = when (finish) { Finish.NATURAL -> 0.75; Finish.POLISHED -> 1.65; Finish.ROBOTIC -> 6.0 }
        val confidenceGate = when (finish) { Finish.NATURAL -> 0.80; Finish.POLISHED -> 0.76; Finish.ROBOTIC -> 0.68 }
        val profileStrength = if (finish == Finish.NATURAL) 0.72 else 1.0
        val vibratoPreservation = when (finish) { Finish.NATURAL -> 0.72; Finish.POLISHED -> 0.34; Finish.ROBOTIC -> 0.0 }
        var previousCorrection = 0.0

        for (frameIndex in 0 until frameCount) {
            val center = frameIndex * hop
            val source = ShortArray(frameSize) { i ->
                input[(center - half + i).coerceIn(0, input.lastIndex)]
            }
            val pitch = analysis[frameIndex]
            var semitoneShift = 0.0

            if (pitch.voiced && pitch.confidence >= confidenceGate) {
                val target = nearestScaleNote(stableMidi[frameIndex], key, notes)
                val raw = (target - stableMidi[frameIndex]).coerceIn(-maxCorrection, maxCorrection)
                // Smooth correction motion so adjacent frames do not jump between notes.
                val smoothing = (0.08 + retuneSpeed.coerceIn(0.0, 1.0) * 0.82)
                val smoothed = previousCorrection + (raw - previousCorrection) * smoothing
                semitoneShift = smoothed * strength.coerceIn(0.0, 1.0) * profileStrength * (1.0 - vibratoPreservation * (1.0 - pitch.confidence))
                previousCorrection = semitoneShift
                if (abs(semitoneShift) > 0.035) corrected++
            } else {
                // Release correction smoothly through unvoiced frames instead of snapping.
                previousCorrection *= 0.55
                semitoneShift = previousCorrection
            }

            val ratio = 2.0.pow(semitoneShift / 12.0)
            for (i in 0 until frameSize) {
                val outIndex = center - half + i
                if (outIndex !in input.indices) continue
                val sourcePosition = (half + (i - half) * ratio).coerceIn(0.0, (frameSize - 1).toDouble())
                val left = sourcePosition.toInt()
                val right = (left + 1).coerceAtMost(frameSize - 1)
                val fraction = sourcePosition - left
                val sample = source[left] * (1.0 - fraction) + source[right] * fraction
                val window = 0.5 - 0.5 * cos(2.0 * Math.PI * i / (frameSize - 1))
                output[outIndex] += sample * window
                weights[outIndex] += window
            }
        }

        val result = ShortArray(input.size) { i ->
            val value = if (weights[i] > 1e-8) output[i] / weights[i] else input[i].toDouble()
            value.roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return Result(result, corrected)
    }

    /** Returns estimated fundamental frequency and periodicity confidence. */
    private fun yin(frame: DoubleArray, sampleRate: Int): Pair<Double, Double> {
        // Analyze every second sample to reduce CPU cost on longer phone recordings.
        val reduced = DoubleArray(frame.size / 2) { frame[it * 2] }
        val reducedRate = sampleRate / 2
        val minLag = (reducedRate / 600).coerceAtLeast(2)
        val maxLag = min((reducedRate / 75).coerceAtMost(reduced.size / 2), reduced.size / 2 - 1)
        if (maxLag <= minLag) return 0.0 to 0.0

        val difference = DoubleArray(maxLag + 1)
        for (lag in 1..maxLag) {
            var sum = 0.0
            val limit = reduced.size - maxLag
            var i = 0
            while (i < limit) {
                val delta = reduced[i] - reduced[i + lag]
                sum += delta * delta
                i += 2
            }
            difference[lag] = sum
        }

        val normalized = DoubleArray(maxLag + 1)
        normalized[0] = 1.0
        var running = 0.0
        for (lag in 1..maxLag) {
            running += difference[lag]
            normalized[lag] = if (running > 1e-12) difference[lag] * lag / running else 1.0
        }

        var chosen = -1
        var lag = minLag
        while (lag <= maxLag) {
            if (normalized[lag] < 0.16) {
                while (lag + 1 <= maxLag && normalized[lag + 1] < normalized[lag]) lag++
                chosen = lag
                break
            }
            lag++
        }
        if (chosen < 0) {
            var best = Double.POSITIVE_INFINITY
            for (candidate in minLag..maxLag) {
                if (normalized[candidate] < best) {
                    best = normalized[candidate]
                    chosen = candidate
                }
            }
            if (best > 0.28) return 0.0 to (1.0 - best).coerceIn(0.0, 1.0)
        }

        val left = (chosen - 1).coerceAtLeast(minLag)
        val right = (chosen + 1).coerceAtMost(maxLag)
        val a = normalized[left]
        val b = normalized[chosen]
        val c = normalized[right]
        val denominator = a - 2.0 * b + c
        val offset = if (abs(denominator) > 1e-12) 0.5 * (a - c) / denominator else 0.0
        val refinedLag = (chosen + offset.coerceIn(-0.5, 0.5)).coerceAtLeast(1.0)
        return (reducedRate / refinedLag) to (1.0 - b).coerceIn(0.0, 1.0)
    }

    private fun nearestScaleNote(midi: Double, key: Int, notes: IntArray): Double {
        val base = floor(midi).toInt()
        var best = midi
        var bestDistance = Double.POSITIVE_INFINITY
        for (candidate in base - 2..base + 2) {
            val pitchClass = ((candidate - key) % 12 + 12) % 12
            if (notes.contains(pitchClass)) {
                val distance = abs(candidate - midi)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = candidate.toDouble()
                }
            }
        }
        return best
    }
}
