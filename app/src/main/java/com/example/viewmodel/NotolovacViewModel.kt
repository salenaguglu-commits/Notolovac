package com.example.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioCaptureEngine
import com.example.audio.MetronomeEngine
import com.example.audio.PianoCalibrationProfile
import com.example.audio.PianoNoteTracker
import com.example.audio.PianoSynthesizer
import com.example.audio.ScaleCalibrationEngine
import com.example.audio.ScaleCalibrationPhase
import com.example.audio.ScaleCalibrationSnapshot
import com.example.data.NotolovacDatabase
import com.example.data.SavedSong
import com.example.data.SongRepository
import com.example.data.UserPreferencesRepository
import com.example.data.UserSettings
import com.example.model.DetectedNote
import com.example.model.MusicTheory
import com.example.model.NoteDuration
import com.example.model.NoteLabelMode
import com.example.model.SheetNoteEditor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class AppTab {
    STUDIO,
    NOTEBOOK
}

enum class CalibrationMode {
    QUICK_C,
    FULL_SCALE
}

enum class TunePhase {
    IDLE,
    LISTENING,
    LOCKING_C,
    WRONG_NOTE,
    RETRY_UNSTABLE,
    SAVED
}

data class TuneDialogState(
    val isOpen: Boolean = false,
    val mode: CalibrationMode = CalibrationMode.FULL_SCALE,
    val phase: TunePhase = TunePhase.IDLE,
    val isListening: Boolean = false,
    // Live microphone detection readout (Quick C mode)
    val heardNoteName: String? = null,
    val heardRawHz: Double = 0.0,
    val liveHz: Double = MusicTheory.STANDARD_C4_HZ,
    val liveCents: Float = 0f,
    val liveRms: Float = 0f,
    val lockedSamples: Int = 0,
    val requiredSamples: Int = 7,
    val statusMessage: String = "Pritisni „Slušaj moje C“ pa odsviraj srednje C (Do) na klaviru.",
    // Guided C4..C5 scale calibration state
    val scaleSnapshot: ScaleCalibrationSnapshot = ScaleCalibrationSnapshot(),
    // Persisted active calibration proof
    val savedIsCalibrated: Boolean = false,
    val savedC4Hz: Double = MusicTheory.STANDARD_C4_HZ,
    val savedCents: Float = 0f,
    val savedA4Hz: Double = MusicTheory.STANDARD_A4_HZ,
    val savedProfile: PianoCalibrationProfile = PianoCalibrationProfile.STANDARD
) {
    val progress: Float
        get() = (lockedSamples.toFloat() / requiredSamples.coerceAtLeast(1)).coerceIn(0f, 1f)
    val completed: Boolean
        get() = phase == TunePhase.SAVED
}

data class ActiveSheetState(
    val songId: Long = 0L,
    val title: String = "Moja nova melodija",
    val notes: List<DetectedNote> = emptyList(),
    val bpm: Int = 90,
    val beatsPerMeasure: Int = 4,
    val calibrationCentsUsed: Float = 0f,
    val selectedNoteIndex: Int? = null,
    val isEditMode: Boolean = false,
    val isPlaying: Boolean = false,
    val playingNoteIndex: Int? = null,
    val isSaved: Boolean = false
)

data class NotolovacUiState(
    val currentTab: AppTab = AppTab.STUDIO,
    val isRecording: Boolean = false,
    val recordingElapsedSeconds: Int = 0,
    val liveMidiNote: Int? = null,
    val liveCentsOff: Float = 0f,
    val liveFrequencyHz: Double = 0.0,
    val rmsLevel: Float = 0f,
    val isMicClipping: Boolean = false,
    val liveCapturedNotes: List<DetectedNote> = emptyList(),
    val keyboardPressedMidi: Int? = null,
    val keyboardStartOctave: Int = 4, // 3 (C3..B4) or 4 (C4..B5)
    val metronomeRunning: Boolean = false,
    val currentBeatIndex: Int = -1,
    val tuneDialog: TuneDialogState = TuneDialogState(),
    val activeSheet: ActiveSheetState? = null,
    val snackbarMessage: String? = null
)

class NotolovacViewModel(application: Application) : AndroidViewModel(application) {

    private val database = NotolovacDatabase.getInstance(application)
    private val songRepository = SongRepository(database.songDao())
    private val preferencesRepository = UserPreferencesRepository(application)
    private val audioEngine = AudioCaptureEngine()
    private val synthesizer = PianoSynthesizer()
    private val metronomeEngine = MetronomeEngine()

    val settings: StateFlow<UserSettings> = preferencesRepository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserSettings()
    )

    val savedSongs: StateFlow<List<SavedSong>> = songRepository.allSongs.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _uiState = MutableStateFlow(NotolovacUiState())
    val uiState: StateFlow<NotolovacUiState> = _uiState.asStateFlow()

    private var recordingJob: Job? = null
    private var recordingTimerJob: Job? = null
    private var calibrationJob: Job? = null
    private var playbackJob: Job? = null
    private var keyGlowJob: Job? = null
    private var activeTracker: PianoNoteTracker? = null
    private var recordingHadMetronome: Boolean = false

    fun selectTab(tab: AppTab) {
        if (_uiState.value.isRecording) return
        stopPlayback()
        _uiState.update { it.copy(currentTab = tab, activeSheet = null) }
    }

    fun dismissSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun setNoteLabelMode(mode: NoteLabelMode) {
        viewModelScope.launch {
            preferencesRepository.setNoteLabelMode(mode)
        }
    }

    fun setMetronomeBpm(bpm: Int) {
        val clamped = bpm.coerceIn(60, 140)
        viewModelScope.launch {
            preferencesRepository.setMetronomeBpm(clamped)
        }
        // Update active sheet tempo too if viewing an unsaved result
        _uiState.update { state ->
            state.activeSheet?.let { sheet ->
                state.copy(activeSheet = sheet.copy(bpm = clamped))
            } ?: state
        }
        // Seamlessly adjust running metronome tempo without restarting AudioTrack
        metronomeEngine.updateBpm(clamped)
    }

    fun setBeatsPerMeasure(beats: Int) {
        val valid = if (beats == 3) 3 else 4
        viewModelScope.launch {
            preferencesRepository.setBeatsPerMeasure(valid)
        }
        // Seamlessly adjust time signature without dropping a beat
        metronomeEngine.updateBeatsPerMeasure(valid)
    }

    fun toggleMetronome() {
        val nowRunning = !_uiState.value.metronomeRunning
        viewModelScope.launch {
            preferencesRepository.setMetronomeEnabled(nowRunning)
        }
        if (nowRunning) {
            startMetronomeLoop(settings.value.metronomeBpm, settings.value.beatsPerMeasure)
        } else {
            stopMetronomeLoop()
        }
    }

    private fun startMetronomeLoop(bpm: Int, beatsPerMeasure: Int) {
        _uiState.update { it.copy(metronomeRunning = true, currentBeatIndex = 0) }
        metronomeEngine.start(
            bpm = bpm,
            beatsPerMeasure = beatsPerMeasure,
            onBeat = { beatIdx ->
                _uiState.update { state ->
                    if (state.metronomeRunning) state.copy(currentBeatIndex = beatIdx) else state
                }
            }
        )
    }

    private fun stopMetronomeLoop() {
        metronomeEngine.stop()
        _uiState.update { it.copy(metronomeRunning = false, currentBeatIndex = -1) }
    }

    fun setKeyboardOctave(octave: Int) {
        _uiState.update { it.copy(keyboardStartOctave = octave.coerceIn(3, 4)) }
    }

    fun onPianoKeyTapped(midiNote: Int) {
        synthesizer.playNote(
            midiNote = midiNote,
            durationMs = 600,
            calibrationCents = settings.value.calibrationCents
        )
        keyGlowJob?.cancel()
        _uiState.update { it.copy(keyboardPressedMidi = midiNote) }
        keyGlowJob = viewModelScope.launch {
            delay(380)
            _uiState.update { it.copy(keyboardPressedMidi = null) }
        }
    }

    // ---- RECORDING ("SLUŠAJ SADA") ----

    fun startRecording() {
        if (_uiState.value.isRecording) return
        stopPlayback()
        stopCalibrationListening()

        val currentSettings = settings.value
        val activeProfile = currentSettings.calibrationProfile
        val tracker = PianoNoteTracker(calibrationProfile = activeProfile)
        activeTracker = tracker

        val useMetronome = _uiState.value.metronomeRunning
        recordingHadMetronome = useMetronome

        _uiState.update {
            it.copy(
                isRecording = true,
                recordingElapsedSeconds = 0,
                liveMidiNote = null,
                liveCentsOff = 0f,
                liveFrequencyHz = 0.0,
                rmsLevel = 0f,
                isMicClipping = false,
                liveCapturedNotes = emptyList(),
                activeSheet = null
            )
        }

        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            var sec = 0
            while (isActive) {
                delay(1000)
                sec++
                _uiState.update { it.copy(recordingElapsedSeconds = sec) }
            }
        }

        recordingJob?.cancel()
        recordingJob = viewModelScope.launch {
            audioEngine.pitchFramesFlow(
                calibrationCents = currentSettings.calibrationCents,
                calibrationProfile = activeProfile
            )
                .catch { err ->
                    _uiState.update {
                        it.copy(
                            isRecording = false,
                            isMicClipping = false,
                            snackbarMessage = err.message ?: "Greška pri pokretanju mikrofona."
                        )
                    }
                    recordingTimerJob?.cancel()
                }
                .collect { frame ->
                    val update = tracker.processFrame(frame.spectral, frame.timestampMs)
                    _uiState.update { state ->
                        state.copy(
                            liveMidiNote = update.liveMidiNote,
                            liveCentsOff = update.liveCentsOff,
                            liveFrequencyHz = update.liveFrequencyHz,
                            rmsLevel = update.rmsLevel,
                            isMicClipping = update.isClipping,
                            liveCapturedNotes = update.capturedNotes
                        )
                    }
                }
        }
    }

    fun stopRecording() {
        if (!_uiState.value.isRecording) return
        recordingJob?.cancel()
        recordingJob = null
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        stopMetronomeLoop()

        val currentSettings = settings.value
        val transcription = activeTracker?.finishAndQuantize(
            metronomeBpm = currentSettings.metronomeBpm,
            metronomeWasActive = recordingHadMetronome,
            endTimestampMs = SystemClock.elapsedRealtime()
        )
        activeTracker = null

        val quantizedNotes = transcription?.notes.orEmpty()
        val effectiveBpm = transcription?.effectiveBpm ?: currentSettings.metronomeBpm
        val playableCount = quantizedNotes.count { !it.isRest }
        if (playableCount == 0) {
            _uiState.update {
                it.copy(
                    isRecording = false,
                    liveMidiNote = null,
                    rmsLevel = 0f,
                    isMicClipping = false,
                    snackbarMessage = "Nisam uspeo da čujem note klavira. Pokušaj malo glasnije ili bliže klaviru!"
                )
            }
            return
        }

        val nextNumber = savedSongs.value.size + 1
        _uiState.update {
            it.copy(
                isRecording = false,
                liveMidiNote = null,
                rmsLevel = 0f,
                isMicClipping = false,
                activeSheet = ActiveSheetState(
                    songId = 0L,
                    title = "Moja melodija #$nextNumber",
                    notes = quantizedNotes,
                    bpm = effectiveBpm,
                    beatsPerMeasure = currentSettings.beatsPerMeasure,
                    calibrationCentsUsed = currentSettings.calibrationCents,
                    isSaved = false
                )
            )
        }
    }

    // ---- TUNE / C-CALIBRATION ("ŠTIMUJ C") ----

    fun openTuneDialog() {
        if (_uiState.value.isRecording) stopRecording()
        stopPlayback()
        stopMetronomeLoop()
        val s = settings.value
        val profile = s.calibrationProfile
        val savedC4 = if (s.isCalibrated) s.calibratedC4Hz.toDouble() else MusicTheory.STANDARD_C4_HZ
        val savedCents = if (s.isCalibrated) s.calibrationCents else 0f
        val savedA4 = MusicTheory.equivalentA4Hz(savedCents)
        val initialMode = if (s.isCalibrated && !profile.isScaleCalibrated) {
            CalibrationMode.QUICK_C
        } else {
            CalibrationMode.FULL_SCALE
        }
        _uiState.update {
            it.copy(
                tuneDialog = TuneDialogState(
                    isOpen = true,
                    mode = initialMode,
                    phase = TunePhase.IDLE,
                    isListening = false,
                    heardNoteName = null,
                    heardRawHz = 0.0,
                    liveCents = savedCents,
                    liveHz = savedC4,
                    liveRms = 0f,
                    lockedSamples = 0,
                    statusMessage = if (s.isCalibrated) {
                        "Tvoj štim je već sačuvan i aktivan. Možeš ponovo da izmeriš C ako želiš."
                    } else {
                        "Pritisni „Slušaj moje C“, zatim pritisni i zadrži srednje C (Do) 1–2 sekunde."
                    },
                    scaleSnapshot = ScaleCalibrationSnapshot(),
                    savedIsCalibrated = s.isCalibrated,
                    savedC4Hz = savedC4,
                    savedCents = savedCents,
                    savedA4Hz = savedA4,
                    savedProfile = profile
                )
            )
        }
    }

    fun selectCalibrationMode(mode: CalibrationMode) {
        if (_uiState.value.tuneDialog.mode == mode) return
        stopCalibrationListening()
        _uiState.update { state ->
            state.copy(
                tuneDialog = state.tuneDialog.copy(
                    mode = mode,
                    phase = TunePhase.IDLE,
                    isListening = false,
                    heardNoteName = null,
                    heardRawHz = 0.0,
                    liveRms = 0f,
                    lockedSamples = 0,
                    scaleSnapshot = ScaleCalibrationSnapshot()
                )
            )
        }
    }

    fun closeTuneDialog() {
        stopCalibrationListening()
        _uiState.update { it.copy(tuneDialog = it.tuneDialog.copy(isOpen = false, isListening = false)) }
    }

    fun playReferenceC() {
        synthesizer.playNote(midiNote = 60, durationMs = 1100, calibrationCents = 0f)
    }

    fun resetCalibrationToStandard() {
        stopCalibrationListening()
        viewModelScope.launch {
            preferencesRepository.saveCalibrationProfile(PianoCalibrationProfile.STANDARD)
            _uiState.update {
                it.copy(
                    tuneDialog = it.tuneDialog.copy(
                        phase = TunePhase.IDLE,
                        isListening = false,
                        heardNoteName = null,
                        heardRawHz = 0.0,
                        liveCents = 0f,
                        liveHz = MusicTheory.STANDARD_C4_HZ,
                        lockedSamples = 0,
                        scaleSnapshot = ScaleCalibrationSnapshot(),
                        savedIsCalibrated = false,
                        savedC4Hz = MusicTheory.STANDARD_C4_HZ,
                        savedCents = 0f,
                        savedA4Hz = MusicTheory.STANDARD_A4_HZ,
                        savedProfile = PianoCalibrationProfile.STANDARD,
                        statusMessage = "Vraćeno na standardni štim (C4 = 261.6 Hz, A4 = 440.0 Hz)."
                    ),
                    snackbarMessage = "Štimovanje vraćeno na standardnih 440 Hz."
                )
            }
        }
    }

    fun startCalibrationListening() {
        if (_uiState.value.tuneDialog.isListening) {
            stopCalibrationListening()
            return
        }
        if (_uiState.value.tuneDialog.mode == CalibrationMode.FULL_SCALE) {
            startScaleCalibrationListening()
            return
        }
        startQuickCCalibrationListening()
    }

    private fun startScaleCalibrationListening() {
        val engine = ScaleCalibrationEngine()
        val initialSnap = engine.start()

        _uiState.update {
            it.copy(
                tuneDialog = it.tuneDialog.copy(
                    isListening = true,
                    phase = TunePhase.LISTENING,
                    liveRms = 0f,
                    scaleSnapshot = initialSnap
                )
            )
        }

        calibrationJob?.cancel()
        calibrationJob = viewModelScope.launch {
            audioEngine.pitchFramesFlow(calibrationCents = 0f, widePitchTolerance = true)
                .catch { err ->
                    _uiState.update {
                        it.copy(
                            tuneDialog = it.tuneDialog.copy(
                                isListening = false,
                                phase = TunePhase.IDLE,
                                liveRms = 0f,
                                scaleSnapshot = ScaleCalibrationSnapshot(
                                    statusMessage = err.message ?: "Mikrofon nije dostupan."
                                )
                            )
                        )
                    }
                }
                .collect { frame ->
                    val snap = engine.processFrame(frame.spectral)
                    if (snap.phase == ScaleCalibrationPhase.COMPLETED && snap.resultingProfile != null) {
                        val profile = snap.resultingProfile
                        preferencesRepository.saveCalibrationProfile(profile)
                        val ct = profile.globalCents.roundToInt()
                        val sign = if (ct > 0) "+" else ""
                        val c4Str = String.format(java.util.Locale.US, "%.1f Hz", profile.calibratedC4Hz)
                        _uiState.update {
                            it.copy(
                                tuneDialog = it.tuneDialog.copy(
                                    isListening = false,
                                    phase = TunePhase.SAVED,
                                    liveRms = 0f,
                                    scaleSnapshot = snap,
                                    savedIsCalibrated = true,
                                    savedC4Hz = profile.calibratedC4Hz.toDouble(),
                                    savedCents = profile.globalCents,
                                    savedA4Hz = profile.equivalentA4Hz,
                                    savedProfile = profile
                                ),
                                snackbarMessage = "Sačuvan profil klavira (Skala Do–Do): C4 = $c4Str ($sign$ct ct)"
                            )
                        }
                        calibrationJob?.cancel()
                    } else {
                        _uiState.update {
                            it.copy(
                                tuneDialog = it.tuneDialog.copy(
                                    liveRms = snap.liveRms,
                                    scaleSnapshot = snap
                                )
                            )
                        }
                    }
                }
        }
    }

    private fun startQuickCCalibrationListening() {
        val collectedCents = mutableListOf<Float>()
        val targetSamples = 7
        var silentFramesSinceLastC = 0
        var lastPartialC4Hz = 0.0
        var lastPartialCents = 0f
        var wrongNoteMidi: Int? = null
        var wrongNoteFrames = 0
        var quietFramesTotal = 0

        _uiState.update {
            it.copy(
                tuneDialog = it.tuneDialog.copy(
                    phase = TunePhase.LISTENING,
                    isListening = true,
                    heardNoteName = null,
                    heardRawHz = 0.0,
                    liveRms = 0f,
                    lockedSamples = 0,
                    requiredSamples = targetSamples,
                    statusMessage = "Slušam mikrofon... Pritisni i zadrži jednu dirku C (Do) 1–2 sekunde."
                )
            )
        }

        calibrationJob?.cancel()
        calibrationJob = viewModelScope.launch {
            audioEngine.pitchFramesFlow(calibrationCents = 0f, widePitchTolerance = true)
                .catch { err ->
                    _uiState.update {
                        it.copy(
                            tuneDialog = it.tuneDialog.copy(
                                phase = TunePhase.IDLE,
                                isListening = false,
                                liveRms = 0f,
                                statusMessage = err.message ?: "Mikrofon nije dostupan."
                            )
                        )
                    }
                }
                .collect { frame ->
                    val est = frame.spectral
                    _uiState.update {
                        it.copy(tuneDialog = it.tuneDialog.copy(liveRms = est.rms))
                    }

                    val hasClearPitch = est.midiNote != null &&
                        est.frequencyHz > 0.0 &&
                        est.confidence >= 0.42f &&
                        est.rms >= 0.0030f

                    if (hasClearPitch) {
                        quietFramesTotal = 0
                        val cResult = MusicTheory.computeCalibrationCentsFromC(est.frequencyHz)
                        if (cResult != null) {
                            // Heard a C note!
                            wrongNoteMidi = null
                            wrongNoteFrames = 0
                            silentFramesSinceLastC = 0

                            val (centsDiff, normalizedC4) = cResult
                            val nearestC = MusicTheory.nearestCMidi(est.frequencyHz)
                            val cLabel = MusicTheory.formatFullNoteDescription(nearestC)
                            val roundedCt = centsDiff.roundToInt()
                            val sign = if (roundedCt > 0) "+" else ""
                            val c4Text = String.format(java.util.Locale.US, "%.1f Hz", normalizedC4)

                            // Check stability against previous C readings
                            val unstableJump = collectedCents.isNotEmpty() &&
                                abs(collectedCents.last() - centsDiff) > 18f

                            if (unstableJump) {
                                collectedCents.clear()
                                _uiState.update {
                                    it.copy(
                                        tuneDialog = it.tuneDialog.copy(
                                            phase = TunePhase.RETRY_UNSTABLE,
                                            heardNoteName = cLabel,
                                            heardRawHz = est.frequencyHz,
                                            liveHz = normalizedC4,
                                            liveCents = centsDiff,
                                            lockedSamples = 0,
                                            statusMessage = "Čuo sam $cLabel ($c4Text, $sign$roundedCt ct), ali ton nije stabilan. Pritisni opet samo jednu dirku C!"
                                        )
                                    )
                                }
                            } else {
                                collectedCents.add(centsDiff)
                                lastPartialC4Hz = normalizedC4
                                lastPartialCents = centsDiff

                                _uiState.update {
                                    it.copy(
                                        tuneDialog = it.tuneDialog.copy(
                                            phase = TunePhase.LOCKING_C,
                                            heardNoteName = cLabel,
                                            heardRawHz = est.frequencyHz,
                                            liveHz = normalizedC4,
                                            liveCents = centsDiff,
                                            lockedSamples = collectedCents.size,
                                            statusMessage = "Čujem $cLabel ($c4Text, $sign$roundedCt ct) — zadrži dirku još trenutak (${collectedCents.size}/$targetSamples)..."
                                        )
                                    )
                                }

                                if (collectedCents.size >= targetSamples) {
                                    val sorted = collectedCents.sorted()
                                    val medianCents = sorted[sorted.size / 2]
                                    val calibratedC4 = (MusicTheory.STANDARD_C4_HZ *
                                        Math.pow(2.0, medianCents / 1200.0)).toFloat()
                                    val equivalentA4 = MusicTheory.equivalentA4Hz(medianCents)

                                    val quickProfile = PianoCalibrationProfile.fromQuickC(
                                        cents = medianCents,
                                        c4Hz = calibratedC4,
                                        isCalibrated = true
                                    )
                                    preferencesRepository.saveCalibrationProfile(quickProfile)

                                    val finalCt = medianCents.roundToInt()
                                    val finalSign = if (finalCt > 0) "+" else ""
                                    val finalC4Text = String.format(java.util.Locale.US, "%.1f Hz", calibratedC4)
                                    val finalA4Text = String.format(java.util.Locale.US, "%.1f Hz", equivalentA4)

                                    _uiState.update {
                                        it.copy(
                                            tuneDialog = it.tuneDialog.copy(
                                                phase = TunePhase.SAVED,
                                                isListening = false,
                                                heardNoteName = cLabel,
                                                heardRawHz = est.frequencyHz,
                                                liveHz = calibratedC4.toDouble(),
                                                liveCents = medianCents,
                                                lockedSamples = targetSamples,
                                                savedIsCalibrated = true,
                                                savedC4Hz = calibratedC4.toDouble(),
                                                savedCents = medianCents,
                                                savedA4Hz = equivalentA4,
                                                savedProfile = quickProfile,
                                                statusMessage = "Štim tvog klavira je sačuvan! C4 = $finalC4Text ($finalSign$finalCt ct • A4 ≈ $finalA4Text). Sve note se od sada prepoznaju po ovom štimu."
                                            ),
                                            snackbarMessage = "Sačuvan štim klavira: C4 = $finalC4Text ($finalSign$finalCt ct)"
                                        )
                                    }
                                    calibrationJob?.cancel()
                                }
                            }
                        } else {
                            // Clearly heard a DIFFERENT note (not C)
                            val midi = est.midiNote
                            if (wrongNoteMidi == midi) {
                                wrongNoteFrames++
                            } else {
                                wrongNoteMidi = midi
                                wrongNoteFrames = 1
                            }

                            if (wrongNoteFrames >= 2) {
                                collectedCents.clear()
                                silentFramesSinceLastC = 0
                                val heardName = MusicTheory.formatFullNoteDescription(midi)
                                val rawHzText = String.format(java.util.Locale.US, "%.1f Hz", est.frequencyHz)
                                _uiState.update {
                                    it.copy(
                                        tuneDialog = it.tuneDialog.copy(
                                            phase = TunePhase.WRONG_NOTE,
                                            heardNoteName = heardName,
                                            heardRawHz = est.frequencyHz,
                                            lockedSamples = 0,
                                            statusMessage = "Čuo sam notu $heardName ($rawHzText) — to nije C. Pritisni dirku C (Do) opet!"
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        // Silence or unclear sound
                        wrongNoteFrames = 0
                        if (collectedCents.isNotEmpty()) {
                            silentFramesSinceLastC++
                            if (silentFramesSinceLastC >= 7) {
                                val partialCt = lastPartialCents.roundToInt()
                                val partialSign = if (partialCt > 0) "+" else ""
                                val partialHzText = String.format(java.util.Locale.US, "%.1f Hz", lastPartialC4Hz)
                                collectedCents.clear()
                                silentFramesSinceLastC = 0
                                _uiState.update {
                                    it.copy(
                                        tuneDialog = it.tuneDialog.copy(
                                            phase = TunePhase.RETRY_UNSTABLE,
                                            lockedSamples = 0,
                                            statusMessage = "Čuo sam C ($partialHzText, $partialSign$partialCt ct), ali ton je bio prekratak. Pritisni C opet i zadrži dirku 1–2 sekunde!"
                                        )
                                    )
                                }
                            }
                        } else {
                            quietFramesTotal++
                            if (quietFramesTotal == 220) {
                                _uiState.update {
                                    it.copy(
                                        tuneDialog = it.tuneDialog.copy(
                                            statusMessage = "Još ne čujem jasan ton klavira. Približi telefon klaviru i pritisni C malo jače!"
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }

    private fun stopCalibrationListening() {
        calibrationJob?.cancel()
        calibrationJob = null
        _uiState.update { state ->
            val nextPhase = if (state.tuneDialog.phase == TunePhase.SAVED) TunePhase.SAVED else TunePhase.IDLE
            val nextScaleSnap = if (state.tuneDialog.scaleSnapshot.phase == ScaleCalibrationPhase.COMPLETED) {
                state.tuneDialog.scaleSnapshot
            } else {
                ScaleCalibrationSnapshot()
            }
            state.copy(
                tuneDialog = state.tuneDialog.copy(
                    isListening = false,
                    phase = nextPhase,
                    liveRms = 0f,
                    scaleSnapshot = nextScaleSnap
                )
            )
        }
    }

    // ---- SHEET RESULT, PLAYBACK & SAVED NOTEBOOK ----

    fun openSavedSong(song: SavedSong) {
        stopPlayback()
        _uiState.update {
            it.copy(
                activeSheet = ActiveSheetState(
                    songId = song.id,
                    title = song.title,
                    notes = song.notes,
                    bpm = song.bpm,
                    beatsPerMeasure = song.beatsPerMeasure,
                    calibrationCentsUsed = song.calibrationCentsUsed,
                    isSaved = true
                )
            )
        }
    }

    fun closeActiveSheet() {
        stopPlayback()
        _uiState.update { it.copy(activeSheet = null) }
    }

    fun updateActiveSheetTitle(newTitle: String) {
        _uiState.update { state ->
            state.activeSheet?.let { sheet ->
                state.copy(activeSheet = sheet.copy(title = newTitle, isSaved = false))
            } ?: state
        }
    }

    fun toggleEditMode() {
        val sheet = _uiState.value.activeSheet ?: return
        stopPlayback()
        val entering = !sheet.isEditMode
        val nextSelected = if (entering) {
            sheet.selectedNoteIndex?.takeIf { it in sheet.notes.indices }
                ?: sheet.notes.indexOfFirst { !it.isRest }.takeIf { it >= 0 }
                ?: sheet.notes.indices.firstOrNull()
        } else {
            null
        }
        val selectedMidi = nextSelected?.let { sheet.notes.getOrNull(it) }?.takeIf { !it.isRest }?.midiNote
        _uiState.update { state ->
            val adjustedOctave = adjustOctaveForMidi(state.keyboardStartOctave, selectedMidi)
            state.copy(
                keyboardStartOctave = adjustedOctave,
                activeSheet = sheet.copy(
                    isEditMode = entering,
                    selectedNoteIndex = nextSelected
                )
            )
        }
    }

    fun enterEditModeForNote(index: Int) {
        val sheet = _uiState.value.activeSheet ?: return
        if (index !in sheet.notes.indices) return
        stopPlayback()
        val note = sheet.notes[index]
        _uiState.update { state ->
            val adjustedOctave = adjustOctaveForMidi(
                state.keyboardStartOctave,
                note.takeIf { !it.isRest }?.midiNote
            )
            state.copy(
                keyboardStartOctave = adjustedOctave,
                activeSheet = sheet.copy(
                    isEditMode = true,
                    selectedNoteIndex = index
                )
            )
        }
    }

    fun selectAndPreviewNote(index: Int) {
        val sheet = _uiState.value.activeSheet ?: return
        val note = sheet.notes.getOrNull(index) ?: return
        _uiState.update { state ->
            val adjustedOctave = adjustOctaveForMidi(
                state.keyboardStartOctave,
                note.takeIf { !it.isRest }?.midiNote
            )
            state.copy(
                keyboardStartOctave = adjustedOctave,
                activeSheet = sheet.copy(selectedNoteIndex = index)
            )
        }
        if (!note.isRest) {
            onPianoKeyTapped(note.midiNote)
        }
    }

    fun selectAdjacentNote(delta: Int) {
        val sheet = _uiState.value.activeSheet ?: return
        if (sheet.notes.isEmpty()) return
        val current = sheet.selectedNoteIndex ?: 0
        val nextIdx = (current + delta).coerceIn(0, sheet.notes.lastIndex)
        if (nextIdx != current) {
            selectAndPreviewNote(nextIdx)
        }
    }

    fun stepSelectedNotePitch(direction: Int) {
        val sheet = _uiState.value.activeSheet ?: return
        val idx = sheet.selectedNoteIndex ?: return
        val note = sheet.notes.getOrNull(idx) ?: return
        if (note.isRest) return
        val nextMidi = SheetNoteEditor.stepDiatonic(note.midiNote, direction)
        if (nextMidi != note.midiNote) {
            setSelectedNotePitch(nextMidi)
        }
    }

    fun toggleSelectedNoteSharp() {
        val sheet = _uiState.value.activeSheet ?: return
        val idx = sheet.selectedNoteIndex ?: return
        val note = sheet.notes.getOrNull(idx) ?: return
        if (note.isRest) return
        val nextMidi = SheetNoteEditor.toggleSharp(note.midiNote)
        if (nextMidi != note.midiNote) {
            setSelectedNotePitch(nextMidi)
        }
    }

    fun setSelectedNotePitch(newMidi: Int) {
        val sheet = _uiState.value.activeSheet ?: return
        val idx = sheet.selectedNoteIndex ?: return
        if (idx !in sheet.notes.indices) return
        stopPlayback()
        val clamped = newMidi.coerceIn(MusicTheory.MIN_PIANO_MIDI, MusicTheory.MAX_PIANO_MIDI)
        val updated = SheetNoteEditor.updatePitch(sheet.notes, idx, clamped)
        _uiState.update { state ->
            state.copy(
                keyboardStartOctave = adjustOctaveForMidi(state.keyboardStartOctave, clamped),
                activeSheet = sheet.copy(
                    notes = updated,
                    isSaved = false
                )
            )
        }
        onPianoKeyTapped(clamped)
    }

    fun setSelectedNoteDuration(duration: NoteDuration) {
        val sheet = _uiState.value.activeSheet ?: return
        val idx = sheet.selectedNoteIndex ?: return
        val note = sheet.notes.getOrNull(idx) ?: return
        stopPlayback()
        val updated = SheetNoteEditor.updateDuration(sheet.notes, idx, duration, sheet.bpm)
        _uiState.update { state ->
            state.copy(
                activeSheet = sheet.copy(
                    notes = updated,
                    isSaved = false
                )
            )
        }
        if (!note.isRest) {
            val msPerBeat = 60_000.0 / sheet.bpm.coerceIn(50, 160)
            val previewMs = (duration.beats * msPerBeat).toInt().coerceIn(220, 1200)
            synthesizer.playNote(
                midiNote = note.midiNote,
                durationMs = previewMs,
                calibrationCents = settings.value.calibrationCents
            )
        }
    }

    fun insertNoteAfterSelected() {
        val sheet = _uiState.value.activeSheet ?: return
        stopPlayback()
        val (updated, insertedIdx) = SheetNoteEditor.insertNoteAfter(
            notes = sheet.notes,
            selectedIndex = sheet.selectedNoteIndex,
            bpm = sheet.bpm
        )
        val insertedNote = updated.getOrNull(insertedIdx)
        _uiState.update { state ->
            state.copy(
                activeSheet = sheet.copy(
                    notes = updated,
                    selectedNoteIndex = insertedIdx,
                    isEditMode = true,
                    isSaved = false
                )
            )
        }
        if (insertedNote != null && !insertedNote.isRest) {
            onPianoKeyTapped(insertedNote.midiNote)
        }
    }

    fun onSheetPianoKeyTapped(midiNote: Int) {
        val sheet = _uiState.value.activeSheet
        if (sheet != null && sheet.isEditMode && sheet.selectedNoteIndex != null) {
            setSelectedNotePitch(midiNote)
        } else {
            onPianoKeyTapped(midiNote)
        }
    }

    private fun adjustOctaveForMidi(currentStartOctave: Int, midi: Int?): Int {
        if (midi == null) return currentStartOctave
        val lowVisible = (currentStartOctave + 1) * 12
        val highVisible = lowVisible + 24
        return when {
            midi < lowVisible -> 3
            midi > highVisible -> 4
            else -> currentStartOctave
        }
    }

    fun clearNoteSelection() {
        _uiState.update { state ->
            state.activeSheet?.let { sheet ->
                state.copy(activeSheet = sheet.copy(selectedNoteIndex = null))
            } ?: state
        }
    }

    fun removeNoteAt(index: Int) {
        val sheet = _uiState.value.activeSheet ?: return
        if (index !in sheet.notes.indices) return
        stopPlayback()
        val (updated, nextSel) = SheetNoteEditor.removeNoteAt(sheet.notes, index)
        _uiState.update { state ->
            state.copy(
                activeSheet = sheet.copy(
                    notes = updated,
                    selectedNoteIndex = if (sheet.isEditMode) nextSel else null,
                    isEditMode = sheet.isEditMode && updated.isNotEmpty(),
                    isSaved = false
                )
            )
        }
    }

    fun togglePlayback() {
        val sheet = _uiState.value.activeSheet ?: return
        if (sheet.isPlaying) {
            stopPlayback()
            return
        }
        if (sheet.notes.isEmpty()) return

        playbackJob?.cancel()
        _uiState.update {
            it.copy(
                activeSheet = sheet.copy(
                    isPlaying = true,
                    isEditMode = false,
                    playingNoteIndex = null,
                    selectedNoteIndex = null
                )
            )
        }

        playbackJob = viewModelScope.launch {
            val msPerBeat = 60_000.0 / sheet.bpm.coerceIn(50, 160)
            sheet.notes.forEachIndexed { idx, note ->
                if (!isActive) return@launch
                _uiState.update { state ->
                    state.activeSheet?.let { cur ->
                        state.copy(activeSheet = cur.copy(playingNoteIndex = idx))
                    } ?: state
                }
                val stepDurationMs = (note.beats * msPerBeat).toLong().coerceIn(95L, 2600L)
                if (!note.isRest) {
                    synthesizer.playNote(
                        midiNote = note.midiNote,
                        durationMs = (stepDurationMs * 1.15).toInt().coerceAtLeast(220),
                        calibrationCents = settings.value.calibrationCents
                    )
                }
                delay(stepDurationMs)
            }
            _uiState.update { state ->
                state.activeSheet?.let { cur ->
                    state.copy(activeSheet = cur.copy(isPlaying = false, playingNoteIndex = null))
                } ?: state
            }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        synthesizer.stopAll()
        _uiState.update { state ->
            state.activeSheet?.let { cur ->
                state.copy(activeSheet = cur.copy(isPlaying = false, playingNoteIndex = null))
            } ?: state
        }
    }

    fun saveActiveSheet() {
        val sheet = _uiState.value.activeSheet ?: return
        if (sheet.notes.none { !it.isRest }) {
            showMessage("Dodaj bar jednu notu pre čuvanja.")
            return
        }
        viewModelScope.launch {
            val cleanTitle = sheet.title.trim().ifEmpty { "Moja melodija" }
            val song = SavedSong(
                id = sheet.songId,
                title = cleanTitle,
                createdAtMillis = System.currentTimeMillis(),
                bpm = sheet.bpm,
                beatsPerMeasure = sheet.beatsPerMeasure,
                notes = sheet.notes,
                calibrationCentsUsed = sheet.calibrationCentsUsed
            )
            val savedId = songRepository.saveSong(song)
            _uiState.update { state ->
                state.activeSheet?.let { cur ->
                    state.copy(
                        activeSheet = cur.copy(songId = savedId, title = cleanTitle, isSaved = true),
                        snackbarMessage = "Sačuvano u tvoju muzičku svesku!"
                    )
                } ?: state
            }
        }
    }

    fun deleteSavedSong(song: SavedSong) {
        viewModelScope.launch {
            songRepository.deleteSong(song.id)
            showMessage("Pesmica „${song.title}“ je obrisana.")
        }
    }

    override fun onCleared() {
        super.onCleared()
        recordingJob?.cancel()
        recordingTimerJob?.cancel()
        calibrationJob?.cancel()
        playbackJob?.cancel()
        metronomeEngine.stop()
        synthesizer.stopAll()
    }
}
