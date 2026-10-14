package com.titovpower.racegame

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Procedural engine sound: no samples, the waveform is synthesized from engine speed, load
 * and layout. The firing frequency (rpm / 60 x cylinders / 2) and its harmonics give the pitch;
 * a V8's uneven cross-plane firing adds a half-order burble, a V10 gets more high harmonics,
 * and throttle adds intake/exhaust roar. Pure Kotlin, so it can be rendered offline in tests.
 */
class EngineSynth(private val sampleRate: Int) {
    private val phases = DoubleArray(HARMONICS + 1)
    private var subPhase = 0.0
    private var rpm = CarSpec.IDLE_RPM
    private var load = 0f
    private var level = 0f
    private var noise = 0f
    private var noiseSeed = 12345
    private var rumble = 0f

    /**
     * Fills [out] with 16-bit samples, gliding from the previous state to [targetRpm],
     * [targetLoad] (throttle 0..1) and [targetLevel] (overall volume 0..1).
     */
    fun render(out: ShortArray, engine: Engine, redline: Float, targetRpm: Float, targetLoad: Float, targetLevel: Float) {
        val n = out.size
        val rpm0 = rpm
        val load0 = load
        val level0 = level
        val bright = when (engine) {
            Engine.V10 -> 0.75f
            Engine.FLAT6 -> 0.9f
            Engine.INLINE6 -> 1.1f
            Engine.V8 -> 1.25f
        }
        val burble = if (engine == Engine.V8) 0.45f else if (engine == Engine.FLAT6) 0.18f else 0.06f
        for (i in 0 until n) {
            val t = (i + 1).toFloat() / n
            val r = rpm0 + (targetRpm - rpm0) * t
            val l = load0 + (targetLoad - load0) * t
            val lv = level0 + (targetLevel - level0) * t
            val firing = r / 60.0 * engine.cylinders / 2.0
            val rev = (r / redline).coerceIn(0f, 1f)

            // Harmonic series of the firing frequency; more load and revs = brighter.
            var s = 0.0
            var norm = 0.0
            val tilt = bright - 0.35f * l - 0.25f * rev
            for (k in 1..HARMONICS) {
                val f = firing * k
                if (f > sampleRate * 0.45) break
                phases[k] = (phases[k] + f / sampleRate) % 1.0
                val a = 1.0 / Math.pow(k.toDouble(), tilt.toDouble())
                s += a * sin(2.0 * PI * phases[k])
                norm += a
            }
            s /= norm.coerceAtLeast(1.0) * 0.6
            // Half-order component: the off-beat of uneven firing.
            subPhase = (subPhase + firing * 0.5 / sampleRate) % 1.0
            s += burble * (0.6 + 0.4 * (1 - l)) * sin(2.0 * PI * subPhase)

            // Combustion roughness: low-passed noise, louder under load and at high revs.
            noiseSeed = noiseSeed * 1103515245 + 12345
            val white = ((noiseSeed ushr 8) and 0xFFFF) / 32768f - 1f
            noise += (white - noise) * (0.08f + 0.3f * rev)
            rumble += (noise - rumble) * 0.02f
            s += noise * (0.12 + 0.55 * l * (0.3 + rev)) + rumble * 0.8

            // Load makes the engine louder and grittier; soft clipping keeps it in range.
            val gain = (0.32 + 0.68 * l) * (0.55 + 0.45 * rev)
            val v = tanh(s * gain * (0.9 + 0.8 * l)) * lv
            out[i] = (v * 0.8 * Short.MAX_VALUE).toInt().toShort()
        }
        rpm = targetRpm
        load = targetLoad
        level = targetLevel
    }

    companion object {
        const val HARMONICS = 12
    }
}

/**
 * Streams [EngineSynth] output to an [AudioTrack] on its own thread, following the
 * player's engine as published in [Telemetry].
 */
class EngineSound(private val telemetry: Telemetry) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "engine-sound").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        val rate = SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, CHUNK * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            running = false
            return
        }
        val synth = EngineSynth(rate)
        val buf = ShortArray(CHUNK)
        track.play()
        var smoothLevel = 0f
        while (running) {
            val spec = telemetry.carSpec
            // Quieter while choosing a car; a dip in volume and load during gear shifts.
            val target = if (telemetry.picking) 0.45f else 0.85f
            smoothLevel += (target - smoothLevel) * 0.2f
            val load = if (telemetry.shifting) 0.1f else telemetry.throttle
            val level = smoothLevel * if (telemetry.shifting) 0.75f else 1f
            synth.render(buf, spec.engine, spec.redline, telemetry.rpm, load, level)
            if (track.write(buf, 0, buf.size) < 0) break
        }
        track.stop()
        track.release()
    }

    companion object {
        const val SAMPLE_RATE = 22050
        /** ~23 ms per chunk: low latency between pedal and sound. */
        const val CHUNK = 512
    }
}
