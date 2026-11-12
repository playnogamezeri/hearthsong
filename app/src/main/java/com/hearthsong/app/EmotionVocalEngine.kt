package com.hearthsong.app

import kotlin.math.*

/**
 * Emotion Vocal Engine 2.0: offline, mono whole-mix processing.
 * It deliberately does not claim to isolate vocals from guitar.
 */
internal object EmotionVocalEngine {
    fun process(input: ShortArray, sampleRate: Int, settings: AutoTuneProcessor.ToneSettings): ShortArray {
        if (input.isEmpty()) return input
        val x = DoubleArray(input.size) { input[it] / 32768.0 }

        // Remove subsonic handling noise without stripping the guitar's body.
        highPass(x, sampleRate, 68.0)
        lowShelf(x, sampleRate, 125.0, settings.bassDb.coerceIn(-6.0, 6.0))
        peak(x, sampleRate, 2800.0, 0.9, settings.presenceDb.coerceIn(-3.0, 4.0))
        highShelf(x, sampleRate, 7600.0, settings.trebleDb.coerceIn(-6.0, 6.0))

        // Broad, restrained vocal-color resonances; this is EQ coloration, not formant shifting.
        val color = settings.formantColor.coerceIn(0.0, 100.0) / 100.0
        if (color > 0.001) {
            peak(x, sampleRate, 720.0, 1.0, color * 2.8)
            peak(x, sampleRate, 1450.0, 1.15, color * 2.0)
        }

        deEss(x, sampleRate, settings.deEss.coerceIn(0.0, 1.0))
        compress(x, settings.compression.coerceIn(0.0, 100.0) / 100.0)
        saturate(x, settings.saturation.coerceIn(0.0, 0.35))
        reverb(x, sampleRate, settings.reverb.coerceIn(0.0, 100.0) / 100.0)

        // Leave headroom and avoid hard clipping at the integer conversion boundary.
        var peak = 0.0
        for (v in x) peak = max(peak, abs(v))
        val makeup = if (peak > 0.985) 0.985 / peak else 1.0
        return ShortArray(x.size) { i ->
            (x[i] * makeup).coerceIn(-0.985, 0.985).times(32767.0).roundToInt().toShort()
        }
    }

    private fun highPass(x: DoubleArray, sr: Int, cutoff: Double) {
        val rc = 1.0 / (2.0 * Math.PI * cutoff)
        val dt = 1.0 / sr
        val a = rc / (rc + dt)
        var previousY = 0.0
        var previousX = 0.0
        for (i in x.indices) {
            val current = x[i]
            val y = a * (previousY + current - previousX)
            x[i] = y
            previousY = y
            previousX = current
        }
    }

    private fun lowShelf(x: DoubleArray, sr: Int, frequency: Double, db: Double) {
        if (abs(db) < 0.01) return
        val a = 10.0.pow(db / 20.0)
        val alpha = exp(-2.0 * Math.PI * frequency / sr)
        var low = 0.0
        for (i in x.indices) {
            low = (1.0 - alpha) * x[i] + alpha * low
            x[i] += (a - 1.0) * low
        }
    }

    private fun highShelf(x: DoubleArray, sr: Int, frequency: Double, db: Double) {
        if (abs(db) < 0.01) return
        val a = 10.0.pow(db / 20.0)
        val alpha = exp(-2.0 * Math.PI * frequency / sr)
        var low = 0.0
        for (i in x.indices) {
            low = (1.0 - alpha) * x[i] + alpha * low
            x[i] += (a - 1.0) * (x[i] - low)
        }
    }

    private fun peak(x: DoubleArray, sr: Int, frequency: Double, q: Double, db: Double) {
        if (abs(db) < 0.01) return
        val a = 10.0.pow(db / 40.0)
        val w = 2.0 * Math.PI * frequency.coerceAtMost(sr * 0.44) / sr
        val alpha = sin(w) / (2.0 * q)
        val c = cos(w)
        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * c
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * c
        val a2 = 1.0 - alpha / a
        runBiquad(x, b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    private fun runBiquad(x: DoubleArray, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        var x1=0.0; var x2=0.0; var y1=0.0; var y2=0.0
        for (i in x.indices) {
            val input=x[i]
            val y=b0*input+b1*x1+b2*x2-a1*y1-a2*y2
            x[i]=y
            x2=x1; x1=input; y2=y1; y1=y
        }
    }

    /** Gentle dynamic attenuation of the upper sibilance band. */
    private fun deEss(x: DoubleArray, sr: Int, amount: Double) {
        if (amount <= 0.001) return
        val alpha = exp(-2.0 * Math.PI * 5600.0.coerceAtMost(sr * 0.38) / sr)
        var low = 0.0
        var envelope = 0.0
        for (i in x.indices) {
            low = (1.0 - alpha) * x[i] + alpha * low
            val high = x[i] - low
            val level = abs(high)
            val coeff = if (level > envelope) 0.18 else 0.008
            envelope += (level - envelope) * coeff
            val reduction = (envelope * amount * 1.8).coerceIn(0.0, 0.42)
            x[i] -= high * reduction
        }
    }

    /** Smoothed feed-forward compressor; preset intensity controls threshold and ratio. */
    private fun compress(x: DoubleArray, amount: Double) {
        if (amount <= 0.001) return
        val threshold = 0.72 - 0.43 * amount
        val ratio = 1.0 + 4.5 * amount
        var envelope = 0.0
        var gain = 1.0
        for (i in x.indices) {
            val level = abs(x[i])
            val attack = if (level > envelope) 0.12 else 0.004
            envelope += (level - envelope) * attack
            val target = if (envelope > threshold) {
                (threshold + (envelope - threshold) / ratio) / envelope.coerceAtLeast(1e-9)
            } else 1.0
            val smoothing = if (target < gain) 0.16 else 0.006
            gain += (target - gain) * smoothing
            x[i] *= gain
        }
    }

    /** Very light tanh saturation adds density while retaining transient headroom. */
    private fun saturate(x: DoubleArray, amount: Double) {
        if (amount <= 0.001) return
        val drive = 1.0 + amount * 3.0
        val norm = tanh(drive)
        for (i in x.indices) x[i] = tanh(x[i] * drive) / norm
    }

    /** Four damped feedback combs with an all-pass diffusion stage: compact algorithmic reverb. */
    private fun reverb(x: DoubleArray, sr: Int, amount: Double) {
        if (amount <= 0.001) return
        val delays = intArrayOf(29, 37, 41, 43).map { max(1, sr * it / 1000) }
        val buffers = delays.map { DoubleArray(it) }
        val positions = IntArray(4)
        val damping = DoubleArray(4)
        val feedback = 0.68 + amount * 0.12
        val wet = 0.30 * amount
        val dry = 1.0 - wet * 0.72
        val diffDelay = DoubleArray(max(1, sr * 5 / 1000))
        var diffPos = 0
        var diffState = 0.0
        val diffGain = 0.55
        for (i in x.indices) {
            val source = x[i]
            var sum = 0.0
            for (k in buffers.indices) {
                val buffer = buffers[k]
                val pos = positions[k]
                val delayed = buffer[pos]
                damping[k] = delayed * 0.24 + damping[k] * 0.76
                buffer[pos] = source + damping[k] * feedback
                positions[k] = (pos + 1) % buffer.size
                sum += delayed
            }
            val comb = sum * 0.25
            val delayedDiff = diffDelay[diffPos]
            val diffused = -diffGain * comb + delayedDiff
            diffDelay[diffPos] = comb + diffGain * diffused
            diffPos = (diffPos + 1) % diffDelay.size
            diffState = diffused * 0.75 + diffState * 0.25
            x[i] = source * dry + diffState * wet
        }
    }
}
