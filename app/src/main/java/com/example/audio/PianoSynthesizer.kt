package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import com.example.model.MusicTheory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Pure, deterministic, real-time polyphonic piano mixer supporting up to [maxVoices] simultaneous
 * notes (multi-touch chords + fast glissando sweeps) with smooth damper release envelopes and warm
 * soft-clipping bus limiting so chords never harshly distort.
 */
internal class PolyphonicPianoMixer(
    val sampleRate: Int = 44100,
    val maxVoices: Int = 16
) {
    private class Voice(
        val midiNote: Int,
        val freqHz: Double,
        val volume: Float,
        val decayRate: Double,
        val fixedHoldSamples: Int?, // non-null for timed playNote(), null for live noteOn/noteOff
        val attackSamples: Int,
        val releaseSamples: Int
    ) {
        var ageSamples: Int = 0
        var releasing: Boolean = false
        var releaseElapsed: Int = 0
        var phase1: Double = 0.0
        var phase2: Double = 0.0
        var phase3: Double = 0.0
        var phase4: Double = 0.0
        var phase5: Double = 0.0

        val inc1: Double = 2.0 * PI * freqHz / 44100.0
        val inc2: Double = 2.0 * PI * (freqHz * 2.0004) / 44100.0
        val inc3: Double = 2.0 * PI * (freqHz * 3.0012) / 44100.0
        val inc4: Double = 2.0 * PI * (freqHz * 4.0025) / 44100.0
        val inc5: Double = 2.0 * PI * (freqHz * 5.0) / 44100.0
    }

    private val lock = Any()
    private val voices = ArrayList<Voice>(maxVoices)

    fun activeVoiceCount(): Int = synchronized(lock) { voices.size }

    fun noteOn(
        midiNote: Int,
        calibrationCents: Float = 0f,
        volume: Float = 0.88f,
        durationMs: Int? = null
    ) {
        if (midiNote !in 21..108) return
        val freq = MusicTheory.midiToFrequency(midiNote, calibrationCents)
        val decayRate = 1.9 + ((freq - 261.0).coerceAtLeast(0.0) / 360.0)
        val attackSamples = (sampleRate * 0.005).toInt().coerceAtLeast(1)
        val releaseSamples = (sampleRate * 0.115).toInt().coerceAtLeast(1)
        val fixedHold = durationMs?.let {
            (sampleRate * it.coerceIn(120, 2600)) / 1000
        }

        synchronized(lock) {
            // If this exact key is already held in live mode, release the older voice smoothly so
            // re-striking the same key feels crisp without phasing artifacts
            for (v in voices) {
                if (v.midiNote == midiNote && !v.releasing) {
                    v.releasing = true
                    v.releaseElapsed = 0
                }
            }
            // Voice stealing: drop the oldest releasing voice (or oldest overall) if at capacity
            while (voices.size >= maxVoices) {
                val releaseIdx = voices.indexOfFirst { it.releasing }
                voices.removeAt(if (releaseIdx >= 0) releaseIdx else 0)
            }
            voices.add(
                Voice(
                    midiNote = midiNote,
                    freqHz = freq,
                    volume = volume.coerceIn(0.05f, 1.0f),
                    decayRate = decayRate,
                    fixedHoldSamples = fixedHold,
                    attackSamples = attackSamples,
                    releaseSamples = releaseSamples
                )
            )
        }
    }

    fun noteOff(midiNote: Int) {
        synchronized(lock) {
            for (v in voices) {
                if (v.midiNote == midiNote && v.fixedHoldSamples == null && !v.releasing) {
                    v.releasing = true
                    v.releaseElapsed = 0
                }
            }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            voices.clear()
        }
    }

    /**
     * Renders [out.size] mixed PCM samples into [out].
     * Returns true if at least one voice contributed non-silent audio.
     */
    fun render(out: ShortArray): Boolean {
        val invSampleRate = 1.0 / sampleRate
        val rateScale = 44100.0 / sampleRate
        val mix = DoubleArray(out.size)
        var anyActive = false

        synchronized(lock) {
            if (voices.isEmpty()) {
                out.fill(0)
                return false
            }
            anyActive = true
            val maxNaturalAge = sampleRate * 4 // 4s max acoustic sustain tail

            val iter = voices.iterator()
            while (iter.hasNext()) {
                val v = iter.next()
                val inc1 = v.inc1 * rateScale
                val inc2 = v.inc2 * rateScale
                val inc3 = v.inc3 * rateScale
                val inc4 = v.inc4 * rateScale
                val inc5 = v.inc5 * rateScale
                val d1 = v.decayRate
                val d2 = d1 * 1.45
                val d3 = d1 * 2.1
                val d4 = d1 * 2.9

                var finished = false
                for (i in out.indices) {
                    val age = v.ageSamples
                    val t = age * invSampleRate

                    if (!v.releasing && v.fixedHoldSamples != null && age >= v.fixedHoldSamples) {
                        v.releasing = true
                        v.releaseElapsed = 0
                    }

                    val attackEnv = if (age < v.attackSamples) {
                        age.toDouble() / v.attackSamples
                    } else {
                        1.0
                    }

                    val releaseEnv = if (v.releasing) {
                        val rem = 1.0 - (v.releaseElapsed.toDouble() / v.releaseSamples)
                        v.releaseElapsed++
                        if (rem <= 0.0) {
                            finished = true
                            break
                        }
                        // Quadratic damper curve for extra smooth click-free release
                        rem * rem
                    } else {
                        1.0
                    }

                    val env1 = exp(-d1 * t)
                    if (env1 < 0.0015 || age >= maxNaturalAge) {
                        finished = true
                        break
                    }
                    val env2 = exp(-d2 * t)
                    val env3 = exp(-d3 * t)
                    val env4 = exp(-d4 * t)

                    val sample =
                        0.58 * env1 * sin(v.phase1) +
                        0.24 * env2 * sin(v.phase2) +
                        0.11 * env3 * sin(v.phase3) +
                        0.05 * env4 * sin(v.phase4) +
                        0.02 * env4 * sin(v.phase5)

                    mix[i] += sample * attackEnv * releaseEnv * v.volume

                    v.phase1 += inc1
                    v.phase2 += inc2
                    v.phase3 += inc3
                    v.phase4 += inc4
                    v.phase5 += inc5
                    v.ageSamples = age + 1
                }

                if (finished) {
                    iter.remove()
                }
            }
        }

        // Warm master soft-limiter (tanh): keeps single notes rich and 5-finger chords distortion-free
        val headroomGain = 0.62
        for (i in out.indices) {
            val limited = tanh(mix[i] * headroomGain)
            out[i] = (limited * 32000.0).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        return anyActive
    }
}

/**
 * Low-latency, real-time polyphonic piano synthesizer built on a single persistent streaming AudioTrack.
 *
 * Supports:
 * - Multi-touch chords (up to 16 simultaneous voices) via [noteOn] / [noteOff]
 * - Smooth glissando swipes with instant response and click-free damper releases
 * - Timed melody & reference note playback via [playNote]
 * - Automatic idle sleep when silent so battery/CPU usage drops to zero between touches
 */
class PianoSynthesizer {
    private val sampleRate = 44100
    private val chunkSamples = 512 // ~11.6 ms low-latency audio buffer
    private val idleTimeoutChunks = 130 // ~1.5s of silence before audio thread pauses cleanly

    private val mixer = PolyphonicPianoMixer(sampleRate = sampleRate, maxVoices = 16)
    private val running = AtomicBoolean(false)
    private val threadLock = Any()
    private var audioThread: Thread? = null
    @Volatile private var activeTrack: AudioTrack? = null

    /**
     * Triggers a sustained polyphonic key press (for live multi-touch & glissando keyboard playing).
     * Pair with [noteOff] when the finger leaves the key.
     */
    fun noteOn(
        midiNote: Int,
        calibrationCents: Float = 0f,
        volume: Float = 0.88f
    ) {
        if (midiNote !in 21..108) return
        mixer.noteOn(
            midiNote = midiNote,
            calibrationCents = calibrationCents,
            volume = volume,
            durationMs = null
        )
        ensureAudioThreadRunning()
    }

    /**
     * Releases a live held piano key with a smooth acoustic damper fade-out.
     */
    fun noteOff(midiNote: Int) {
        mixer.noteOff(midiNote)
    }

    /**
     * Plays a timed note (for melody playback, sheet note auditioning, and C4 tuning reference).
     */
    fun playNote(
        midiNote: Int,
        durationMs: Int = 550,
        calibrationCents: Float = 0f,
        volume: Float = 0.92f
    ) {
        if (midiNote !in 21..108) return
        mixer.noteOn(
            midiNote = midiNote,
            calibrationCents = calibrationCents,
            volume = volume,
            durationMs = durationMs
        )
        ensureAudioThreadRunning()
    }

    fun stopAll() {
        mixer.clearAll()
        synchronized(threadLock) {
            running.set(false)
            val track = activeTrack
            activeTrack = null
            if (track != null) {
                runCatching {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                        track.flush()
                        track.stop()
                    }
                }
            }
            val thread = audioThread
            audioThread = null
            if (thread != null && thread !== Thread.currentThread()) {
                runCatching { thread.join(200L) }
            }
        }
    }

    private fun ensureAudioThreadRunning() {
        if (running.get()) return
        synchronized(threadLock) {
            if (running.get()) return
            running.set(true)
            val thread = Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
                runPolyphonicAudioLoop()
            }, "NotolovacPianoSynth")
            audioThread = thread
            thread.start()
        }
    }

    private fun runPolyphonicAudioLoop() {
        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val bufferSizeBytes = maxOf(minBufBytes * 2, 4096)
        val track = buildAudioTrack(bufferSizeBytes)
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            track?.release()
            running.set(false)
            return
        }

        activeTrack = track
        val pcmChunk = ShortArray(chunkSamples)
        var silentChunks = 0

        try {
            track.play()
            while (running.get()) {
                val hadSound = mixer.render(pcmChunk)
                if (hadSound) {
                    silentChunks = 0
                } else {
                    silentChunks++
                    if (silentChunks >= idleTimeoutChunks) {
                        break
                    }
                }

                val written = track.write(pcmChunk, 0, pcmChunk.size, AudioTrack.WRITE_BLOCKING)
                if (written <= 0 || !running.get()) break
            }
        } catch (_: Throwable) {
            // Clean exit on stop/interruption
        } finally {
            synchronized(threadLock) {
                if (activeTrack === track) {
                    activeTrack = null
                }
                if (audioThread === Thread.currentThread()) {
                    audioThread = null
                }
                running.set(false)
            }
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.stop()
                }
            }
            runCatching { track.release() }

            // If a new note arrived in the tiny window while tearing down, restart seamlessly
            if (mixer.activeVoiceCount() > 0) {
                ensureAudioThreadRunning()
            }
        }
    }

    private fun buildAudioTrack(bufferSizeBytes: Int): AudioTrack? {
        return runCatching {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSizeBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            builder.build()
        }.getOrNull()
    }
}
