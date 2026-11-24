package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Pure, deterministic, sample-accurate PCM generator for the metronome.
 *
 * All beat timing is driven directly by the 44,100 Hz audio sample clock using a Double-precision
 * phase accumulator — completely immune to UI-thread jank, coroutine delay() drift, or per-beat
 * AudioTrack creation latency.
 */
internal class MetronomeSampleGenerator(
    val sampleRate: Int = 44100
) {
    private val accentClick: ShortArray = synthesizeWoodblockClick(accent = true)
    private val regularClick: ShortArray = synthesizeWoodblockClick(accent = false)

    private var totalSamplesGenerated: Long = 0L
    private var hasEmittedFirstBeat: Boolean = false
    private var lastBeatExactPos: Double = 0.0
    private var nextBeatExactPos: Double = 0.0
    private var nextBeatIndex: Int = 0
    private var lastUsedBpm: Int = -1

    private var activeClick: ShortArray? = null
    private var activeClickOffset: Int = 0

    fun reset() {
        totalSamplesGenerated = 0L
        hasEmittedFirstBeat = false
        lastBeatExactPos = 0.0
        nextBeatExactPos = 0.0
        nextBeatIndex = 0
        lastUsedBpm = -1
        activeClick = null
        activeClickOffset = 0
    }

    /**
     * Fills [out] with exact sample-timed woodblock clicks and silence between beats.
     * Supports live, glitch-free changes to [bpm] and [beatsPerMeasure] without restarting audio.
     */
    fun fillBuffer(
        out: ShortArray,
        bpm: Int,
        beatsPerMeasure: Int,
        onBeatScheduled: (beatFramePos: Long, beatIndex: Int) -> Unit
    ) {
        val safeBpm = bpm.coerceIn(MetronomeEngine.MIN_BPM, MetronomeEngine.MAX_BPM)
        val safeBeats = if (beatsPerMeasure == 3) 3 else 4
        val samplesPerBeat = (sampleRate.toDouble() * 60.0) / safeBpm.toDouble()

        if (hasEmittedFirstBeat && lastUsedBpm != safeBpm) {
            // Smoothly adjust the upcoming beat deadline to the new tempo without skipping or stuttering
            val retargeted = lastBeatExactPos + samplesPerBeat
            nextBeatExactPos = max(totalSamplesGenerated.toDouble(), retargeted)
            lastUsedBpm = safeBpm
        }

        for (i in out.indices) {
            val absSample = totalSamplesGenerated + i

            val shouldTrigger = if (!hasEmittedFirstBeat) {
                true
            } else {
                absSample >= nextBeatExactPos.toLong()
            }

            if (shouldTrigger) {
                val beatIdx = nextBeatIndex % safeBeats
                activeClick = if (beatIdx == 0) accentClick else regularClick
                activeClickOffset = 0

                onBeatScheduled(absSample, beatIdx)

                if (!hasEmittedFirstBeat) {
                    hasEmittedFirstBeat = true
                    lastBeatExactPos = absSample.toDouble()
                    nextBeatExactPos = absSample.toDouble() + samplesPerBeat
                } else {
                    // Preserve exact fractional beat grid so cumulative drift stays 0.000 ms
                    lastBeatExactPos = nextBeatExactPos
                    nextBeatExactPos += samplesPerBeat
                    if (nextBeatExactPos <= absSample.toDouble()) {
                        nextBeatExactPos = absSample.toDouble() + samplesPerBeat
                    }
                }
                lastUsedBpm = safeBpm
                nextBeatIndex = (beatIdx + 1) % safeBeats
            }

            val click = activeClick
            if (click != null && activeClickOffset < click.size) {
                out[i] = click[activeClickOffset++]
                if (activeClickOffset >= click.size) {
                    activeClick = null
                }
            } else {
                out[i] = 0
            }
        }

        totalSamplesGenerated += out.size
    }

    /**
     * Synthesizes a crisp, woody 30ms percussive click with fast 0.6ms attack and smooth zero-tail
     * decay — razor-sharp for rhythm timing and short/inharmonic enough not to fool piano pitch detection.
     */
    private fun synthesizeWoodblockClick(accent: Boolean): ShortArray {
        val durationMs = if (accent) 32 else 28
        val numSamples = (sampleRate * durationMs) / 1000
        val out = ShortArray(numSamples)
        val attackSamples = (sampleRate * 0.0006).toInt().coerceAtLeast(1)
        val fadeOutSamples = (sampleRate * 0.004).toInt().coerceAtLeast(1)
        val twoPi = 2.0 * PI

        val f1 = if (accent) 1420.0 else 940.0
        val f2 = if (accent) 2310.0 else 1530.0
        val decay = if (accent) 118.0 else 128.0
        val gain = if (accent) 29500.0 else 22500.0

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val attackEnv = if (i < attackSamples) i.toDouble() / attackSamples else 1.0
            val fadeOutEnv = if (i > numSamples - fadeOutSamples) {
                (numSamples - i).toDouble() / fadeOutSamples
            } else {
                1.0
            }
            val env = exp(-decay * t) * attackEnv * fadeOutEnv
            val transientKnock = exp(-650.0 * t) * sin(twoPi * (f1 * 2.73) * t) * 0.25
            val body = 0.68 * sin(twoPi * f1 * t) + 0.32 * sin(twoPi * f2 * t)
            out[i] = ((body + transientKnock) * env * gain)
                .coerceIn(-32767.0, 32767.0)
                .toInt()
                .toShort()
        }
        return out
    }
}

/**
 * Hardware-clocked, sample-accurate Android metronome built on a single persistent streaming AudioTrack
 * running on a dedicated high-priority audio thread.
 *
 * Fixes all classic Android metronome defects:
 * - Zero skipped beats (no per-beat AudioTrack creation/destruction)
 * - Zero off-beat jitter or drift (all timing comes from the 44.1 kHz DAC sample counter)
 * - Visual beat callbacks synchronized to AudioTrack.playbackHeadPosition (actual speaker output)
 * - Glitch-free live BPM and 4/4 <-> 3/4 updates while running
 */
class MetronomeEngine {
    companion object {
        const val MIN_BPM = 40
        const val MAX_BPM = 260
    }

    private val sampleRate = 44100
    private val chunkSamples = 512 // ~11.6 ms chunks for tight low-latency visual sync & live BPM changes

    private val running = AtomicBoolean(false)
    @Volatile private var currentBpm: Int = 90
    @Volatile private var currentBeatsPerMeasure: Int = 4
    @Volatile private var beatListener: ((Int) -> Unit)? = null

    private var audioThread: Thread? = null
    @Volatile private var activeTrack: AudioTrack? = null

    private data class ScheduledBeat(val framePos: Long, val beatIndex: Int)

    fun isRunning(): Boolean = running.get()

    @Synchronized
    fun start(
        bpm: Int,
        beatsPerMeasure: Int,
        onBeat: (beatIndex: Int) -> Unit
    ) {
        currentBpm = bpm.coerceIn(MIN_BPM, MAX_BPM)
        currentBeatsPerMeasure = if (beatsPerMeasure == 3) 3 else 4
        beatListener = onBeat

        if (running.get()) {
            // Already streaming continuously — updated BPM/beats take effect seamlessly!
            return
        }

        running.set(true)
        val thread = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            runStreamingLoop()
        }, "NotolovacMetronome")
        audioThread = thread
        thread.start()
    }

    fun updateBpm(bpm: Int) {
        currentBpm = bpm.coerceIn(MIN_BPM, MAX_BPM)
    }

    fun updateBeatsPerMeasure(beatsPerMeasure: Int) {
        currentBeatsPerMeasure = if (beatsPerMeasure == 3) 3 else 4
    }

    @Synchronized
    fun stop() {
        running.set(false)
        beatListener = null
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
            runCatching { thread.join(250L) }
        }
    }

    private fun runStreamingLoop() {
        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        // Keep hardware buffer tight enough for instant feel while large enough to never underrun
        val bufferSizeBytes = maxOf(minBufBytes * 2, 4096)
        val bufferFrames = (bufferSizeBytes / 2).toLong()

        val track = buildAudioTrack(bufferSizeBytes)
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            track?.release()
            running.set(false)
            return
        }

        activeTrack = track
        val generator = MetronomeSampleGenerator(sampleRate = sampleRate)
        val pcmChunk = ShortArray(chunkSamples)
        val pendingVisualBeats = ArrayDeque<ScheduledBeat>()

        var framesWritten = 0L
        var lastRawHead = 0L
        var headWrapOffset = 0L

        try {
            track.play()

            while (running.get()) {
                generator.fillBuffer(
                    out = pcmChunk,
                    bpm = currentBpm,
                    beatsPerMeasure = currentBeatsPerMeasure
                ) { beatFramePos, beatIdx ->
                    pendingVisualBeats.addLast(ScheduledBeat(beatFramePos, beatIdx))
                }

                val written = track.write(pcmChunk, 0, pcmChunk.size, AudioTrack.WRITE_BLOCKING)
                if (written <= 0 || !running.get()) break
                framesWritten += written

                // Sync visual beat indicator directly to the hardware speaker playback head
                val rawHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (rawHead < lastRawHead) {
                    headWrapOffset += (1L shl 32)
                }
                lastRawHead = rawHead
                val hardwarePlayedFrames = headWrapOffset + rawHead

                // Fallback in case an OEM audio driver reports playbackHeadPosition == 0 continuously
                val effectivePlayedFrames = if (hardwarePlayedFrames == 0L && framesWritten > bufferFrames) {
                    (framesWritten - bufferFrames).coerceAtLeast(0L)
                } else {
                    hardwarePlayedFrames
                }

                while (pendingVisualBeats.isNotEmpty() &&
                    pendingVisualBeats.peekFirst()!!.framePos <= effectivePlayedFrames + (chunkSamples / 2)
                ) {
                    val beat = pendingVisualBeats.removeFirst()
                    beatListener?.invoke(beat.beatIndex)
                }
            }
        } catch (_: Throwable) {
            // Clean exit on stop/interruption
        } finally {
            if (activeTrack === track) {
                activeTrack = null
            }
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.stop()
                }
            }
            runCatching { track.release() }
            running.set(false)
        }
    }

    private fun buildAudioTrack(bufferSizeBytes: Int): AudioTrack? {
        return runCatching {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
