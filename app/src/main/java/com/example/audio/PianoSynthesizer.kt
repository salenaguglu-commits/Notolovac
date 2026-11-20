package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.model.MusicTheory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Reliable, zero-asset warm piano & woodblock metronome synthesizer built on streaming AudioTrack.
 * Uses MODE_STREAM with WRITE_BLOCKING on Dispatchers.IO so playback works consistently across
 * all Android devices without Looper or MODE_STATIC buffer-size quirks.
 */
class PianoSynthesizer {
    private val sampleRate = 44100
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTracks = CopyOnWriteArrayList<AudioTrack>()

    fun playNote(
        midiNote: Int,
        durationMs: Int = 550,
        calibrationCents: Float = 0f,
        volume: Float = 0.92f
    ) {
        if (midiNote < 21 || midiNote > 108) return
        scope.launch {
            val freq = MusicTheory.midiToFrequency(midiNote, calibrationCents)
            val totalMs = durationMs.coerceIn(180, 2600)
            val pcm = synthesizePianoPcm(freq, totalMs, volume)
            playStreamingBuffer(pcm)
        }
    }

    fun playMetronomeTick(accent: Boolean) {
        scope.launch {
            val pcm = synthesizeWoodblockPcm(accent)
            playStreamingBuffer(pcm)
        }
    }

    fun stopAll() {
        val snapshot = activeTracks.toList()
        activeTracks.clear()
        snapshot.forEach { track ->
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.stop()
                }
            }
        }
    }

    private fun synthesizePianoPcm(freqHz: Double, durationMs: Int, volume: Float): ShortArray {
        val toneSamples = (sampleRate * durationMs) / 1000
        // Add a short 60ms silence tail so the hardware audio buffer flushes completely before stop()
        val tailSamples = (sampleRate * 60) / 1000
        val out = ShortArray(toneSamples + tailSamples)
        val attackSamples = (sampleRate * 0.008).toInt().coerceAtLeast(1)
        val releaseSamples = (sampleRate * 0.060).toInt().coerceAtLeast(1)
        val twoPi = 2.0 * PI

        // Higher piano notes decay faster than bass notes
        val decayRate = 2.4 + ((freqHz - 261.0).coerceAtLeast(0.0) / 320.0)

        for (i in 0 until toneSamples) {
            val t = i.toDouble() / sampleRate
            val attackEnv = if (i < attackSamples) i.toDouble() / attackSamples else 1.0
            val releaseEnv = if (i > toneSamples - releaseSamples) {
                (toneSamples - i).toDouble() / releaseSamples
            } else {
                1.0
            }

            val env1 = exp(-decayRate * t)
            val env2 = exp(-(decayRate * 1.45) * t)
            val env3 = exp(-(decayRate * 2.1) * t)
            val env4 = exp(-(decayRate * 2.9) * t)

            val sample =
                0.58 * env1 * sin(twoPi * freqHz * t) +
                0.24 * env2 * sin(twoPi * freqHz * 2.0004 * t) +
                0.11 * env3 * sin(twoPi * freqHz * 3.0012 * t) +
                0.05 * env4 * sin(twoPi * freqHz * 4.0025 * t) +
                0.02 * env4 * sin(twoPi * freqHz * 5.0 * t)

            val scaled = (sample * attackEnv * releaseEnv * volume * 31000.0)
                .coerceIn(-32767.0, 32767.0)
            out[i] = scaled.toInt().toShort()
        }
        return out
    }

    private fun synthesizeWoodblockPcm(accent: Boolean): ShortArray {
        val toneSamples = (sampleRate * 55) / 1000
        val tailSamples = (sampleRate * 60) / 1000
        val out = ShortArray(toneSamples + tailSamples)
        val baseFreq = if (accent) 1180.0 else 820.0
        val twoPi = 2.0 * PI

        for (i in 0 until toneSamples) {
            val t = i.toDouble() / sampleRate
            val env = exp(-68.0 * t)
            val wave = 0.72 * sin(twoPi * baseFreq * t) +
                0.28 * sin(twoPi * baseFreq * 1.52 * t)
            val gain = if (accent) 28000.0 else 21000.0
            out[i] = (wave * env * gain).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        return out
    }

    private fun playStreamingBuffer(pcm: ShortArray) {
        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val bufferSizeBytes = maxOf(minBufBytes * 2, 8192)

        val track = runCatching {
            AudioTrack.Builder()
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
                .build()
        }.getOrNull() ?: return

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return
        }

        // Prevent too many simultaneous active tracks
        while (activeTracks.size >= 8) {
            val oldest = activeTracks.removeAt(0)
            runCatching {
                oldest.pause()
                oldest.flush()
                oldest.stop()
            }
        }
        activeTracks.add(track)

        try {
            track.play()
            var offset = 0
            while (offset < pcm.size && activeTracks.contains(track)) {
                val chunk = minOf(2048, pcm.size - offset)
                val written = track.write(pcm, offset, chunk, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            if (activeTracks.contains(track)) {
                runCatching { track.stop() }
            }
        } catch (_: Throwable) {
            // Safely ignore playback interruption
        } finally {
            activeTracks.remove(track)
            runCatching { track.release() }
        }
    }
}
