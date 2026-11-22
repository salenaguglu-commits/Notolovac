package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

data class AudioPitchFrame(
    val spectral: SpectralPitchFrame,
    val timestampMs: Long
)

/**
 * Captures real-time mono 16-bit PCM audio from the device microphone using standard AudioSource.MIC
 * with a 4096-sample Hann analysis window (~93ms) and 1024-sample hop (~23ms update rate),
 * feeding directly into the 8192-point FFT PianoPitchEngine.
 */
class AudioCaptureEngine(
    private val frameSize: Int = 4096,
    private val hopSize: Int = 1024
) {
    @SuppressLint("MissingPermission")
    fun pitchFramesFlow(
        calibrationCents: Float = 0f,
        widePitchTolerance: Boolean = false,
        calibrationProfile: PianoCalibrationProfile? = null
    ): Flow<AudioPitchFrame> = flow {
        val (audioRecord, actualSampleRate) = createAudioRecord()
            ?: throw IllegalStateException("Mikrofon trenutno nije dostupan.")

        val engine = PianoPitchEngine(
            sampleRate = actualSampleRate,
            windowSize = frameSize,
            calibrationCents = calibrationCents,
            widePitchTolerance = widePitchTolerance,
            calibrationProfile = calibrationProfile
        )
        val analysisWindow = ShortArray(frameSize)
        val hopBuffer = ShortArray(hopSize)

        try {
            audioRecord.startRecording()
            var totalSamplesCollected = 0

            while (currentCoroutineContext().isActive) {
                val read = audioRecord.read(
                    hopBuffer,
                    0,
                    hopSize,
                    AudioRecord.READ_BLOCKING
                )
                if (read > 0) {
                    val shift = minOf(read, frameSize)
                    System.arraycopy(analysisWindow, shift, analysisWindow, 0, frameSize - shift)
                    System.arraycopy(hopBuffer, 0, analysisWindow, frameSize - shift, shift)
                    totalSamplesCollected += read

                    if (totalSamplesCollected < frameSize) {
                        continue
                    }

                    val frame = engine.analyze(
                        pcmSamples = analysisWindow,
                        count = frameSize
                    )
                    emit(
                        AudioPitchFrame(
                            spectral = frame,
                            timestampMs = SystemClock.elapsedRealtime()
                        )
                    )
                }
            }
        } finally {
            runCatching {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
            }
            runCatching { audioRecord.release() }
        }
    }.flowOn(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): Pair<AudioRecord, Int>? {
        val sampleRates = intArrayOf(44100, 48000, 16000)
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )

        for (rate in sampleRates) {
            val minBufBytes = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufBytes <= 0) continue
            val bufferBytes = maxOf(minBufBytes * 2, frameSize * 4)

            for (source in sources) {
                val recorder = runCatching {
                    AudioRecord(
                        source,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferBytes
                    )
                }.getOrNull()
                if (recorder != null && recorder.state == AudioRecord.STATE_INITIALIZED) {
                    return recorder to rate
                }
                recorder?.release()
            }
        }
        return null
    }
}
