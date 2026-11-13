package com.hearthsong.app

import kotlin.math.*

/**
 * Stateful live wrapper around the on-device YIN correction engine.
 *
 * Each microphone block is analyzed with a rolling pre-roll window so pitch
 * tracking does not restart at every AudioRecord read. The original PCM path
 * remains untouched for speech recognition.
 */
internal class LiveAutoTuneEngine {
    enum class Finish { NATURAL, POLISHED, ROBOTIC }

    data class Settings(
        val key: Int = 0,
        val scale: Int = 2,
        val strength: Double = 0.78,
        val retuneSpeed: Double = 0.58,
        val finish: Finish = Finish.NATURAL
    )

    @Volatile private var settings = Settings()
    private var history = ShortArray(2048)

    @Synchronized fun configure(value: Settings) {
        settings = value.copy(
            key = value.key.coerceIn(0, 11),
            scale = value.scale.coerceIn(0, 2),
            strength = value.strength.coerceIn(0.0, 1.0),
            retuneSpeed = value.retuneSpeed.coerceIn(0.0, 1.0)
        )
    }

    @Synchronized fun reset() {
        history.fill(0)
    }

    @Synchronized fun process(pcm: ByteArray, sampleRate: Int): ByteArray {
        if (pcm.size < 2048 || sampleRate !in 8000..48000) return pcm
        val count = pcm.size / 2
        if (count < 1024) return pcm
        val input = ShortArray(count) { index ->
            val low = pcm[index * 2].toInt() and 0xff
            val high = pcm[index * 2 + 1].toInt()
            ((high shl 8) or low).toShort()
        }
        val combined = ShortArray(history.size + input.size)
        history.copyInto(combined)
        input.copyInto(combined, history.size)

        val config = settings
        val finish = when (config.finish) {
            Finish.NATURAL -> VocalPitchEngine.Finish.NATURAL
            Finish.POLISHED -> VocalPitchEngine.Finish.POLISHED
            Finish.ROBOTIC -> VocalPitchEngine.Finish.ROBOTIC
        }
        val tuned = try {
            VocalPitchEngine.correct(
                combined, sampleRate, config.key, config.scale,
                config.strength, finish, config.retuneSpeed
            ).samples
        } catch (_: Exception) {
            return pcm
        }

        val output = tuned.copyOfRange(history.size, history.size + input.size)
        val nextHistory = combined.copyOfRange(
            (combined.size - history.size).coerceAtLeast(0), combined.size
        )
        history = nextHistory

        return ByteArray(pcm.size).also { result ->
            for (i in output.indices) {
                val value = output[i].toInt()
                result[i * 2] = (value and 0xff).toByte()
                result[i * 2 + 1] = (value shr 8).toByte()
            }
            if (pcm.size % 2 != 0) result[result.lastIndex] = pcm.last()
        }
    }
}
