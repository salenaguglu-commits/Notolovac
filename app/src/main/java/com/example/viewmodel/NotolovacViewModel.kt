package com.example.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.audio.AudioCaptureEngine
import com.example.audio.KeyboardPerformanceRecorder
import com.example.audio.MetronomeEngine
import com.example.audio.MicrophoneUnavailableException
import com.example.audio.PianoCalibrationProfile
import com.example.audio.PianoNoteTracker
import com.example.audio.PianoSynthesizer
import com.example.audio.ScaleCalibrationEngine
import com.example.audio.ScaleCalibrationPhase
import com.example.audio.ScaleCalibrationSnapshot
import com.example.audio.ScaleStatusCode
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
    val statusMessage: String = "",
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
    val title: String = "",
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
    val isKeyboardRecording: Boolean = false,
    val keyboardRecordingElapsedSeconds: Int = 0,
    val keyboardRecordedNotes: List<DetectedNote> = emptyList(),
    val liveMidiNote: Int? = null,
    val liveCentsOff: Float = 0f,
    val liveFrequencyHz: Double = 0.0,
    val rmsLevel: Float = 0f,
    val isMicClipping: Boolean = false,
    val liveCapturedNotes: List<DetectedNote> = emptyList(),
    val keyboardPressedMidi: Int? = null,
    val keyboardActiveNotes: Set<Int> = emptySet(),
    val keyboardPlayedHistory: List<DetectedNote> = emptyList(),
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
    private val keyboardRecorder = KeyboardPerformanceRecorder()

    private fun str(@StringRes resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

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
    private var keyboardRecordingTimerJob: Job? = null
    private var calibrationJob: Job? = null
    private var playbackJob: Job? = null
    private var keyGlowJob: Job? = null
    private var activeTracker: PianoNoteTracker? = null
    private var recordingHadMetronome: Boolean = false

    fun selectTab(tab: AppTab) {
        if (_uiState.value.isRecording || _uiState.value.isKeyboardRecording) return
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
        val clamped = bpm.coerceIn(MetronomeEngine.MIN_BPM, MetronomeEngine.MAX_BPM)
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

    private var keyboardHistoryIdCounter: Long = 1L

    fun onPianoKeyPressed(midiNote: Int) {
        synthesizer.noteOn(
            midiNote = midiNote,
            calibrationCents = settings.value.calibrationCents
        )
        val nowElapsed = SystemClock.elapsedRealtime()
        val recordedSnapshot = if (keyboardRecorder.isRecording) {
            keyboardRecorder.onKeyPressed(midiNote, nowElapsed)
        } else {
            null
        }
        val entry = DetectedNote(
            id = keyboardHistoryIdCounter++,
            midiNote = midiNote,
            startTimeMs = nowElapsed,
            durationMs = 400L,
            beats = 1.0f,
            confidence = 1f,
            isRest = false
        )
        _uiState.update { state ->
            state.copy(
                keyboardActiveNotes = state.keyboardActiveNotes + midiNote,
                keyboardPlayedHistory = (state.keyboardPlayedHistory + entry).takeLast(14),
                keyboardRecordedNotes = recordedSnapshot ?: state.keyboardRecordedNotes
            )
        }
    }

    fun onPianoKeyReleased(midiNote: Int) {
        synthesizer.noteOff(midiNote)
        val recordedSnapshot = if (keyboardRecorder.isRecording) {
            keyboardRecorder.onKeyReleased(midiNote, SystemClock.elapsedRealtime())
        } else {
            null
        }
        _uiState.update { state ->
            state.copy(
                keyboardActiveNotes = state.keyboardActiveNotes - midiNote,
                keyboardRecordedNotes = recordedSnapshot ?: state.keyboardRecordedNotes
            )
        }
    }

    fun clearKeyboardHistory() {
        _uiState.update { it.copy(keyboardPlayedHistory = emptyList()) }
    }

    fun clearStudioStaff() {
        if (_uiState.value.isRecording || _uiState.value.isKeyboardRecording) return
        _uiState.update {
            it.copy(
                keyboardPlayedHistory = emptyList(),
                liveCapturedNotes = emptyList(),
                liveMidiNote = null
            )
        }
    }

    // ---- KEYBOARD RECORDING ("SNIMI SVIRKU" IN LANDSCAPE STAGE) ----

    fun toggleKeyboardRecording() {
        if (_uiState.value.isKeyboardRecording) {
            stopKeyboardRecording()
        } else {
            startKeyboardRecording()
        }
    }

    fun startKeyboardRecording() {
        if (_uiState.value.isKeyboardRecording) return
        if (_uiState.value.isRecording) stopRecording()
        stopPlayback()
        stopCalibrationListening()

        keyboardRecorder.start()
        _uiState.update {
            it.copy(
                isKeyboardRecording = true,
                keyboardRecordingElapsedSeconds = 0,
                keyboardRecordedNotes = emptyList(),
                activeSheet = null
            )
        }

        keyboardRecordingTimerJob?.cancel()
        keyboardRecordingTimerJob = viewModelScope.launch {
            var sec = 0
            while (isActive) {
                delay(1000)
                sec++
                _uiState.update { it.copy(keyboardRecordingElapsedSeconds = sec) }
            }
        }
    }

    fun stopKeyboardRecording() {
        if (!_uiState.value.isKeyboardRecording) return
        keyboardRecordingTimerJob?.cancel()
        keyboardRecordingTimerJob = null

        val currentSettings = settings.value
        val transcription = keyboardRecorder.finishAndQuantize(
            endTimestampMs = SystemClock.elapsedRealtime(),
            fallbackBpm = currentSettings.metronomeBpm,
            lockToMetronomeBpm = _uiState.value.metronomeRunning
        )
        val quantizedNotes = transcription.notes
        val playableCount = quantizedNotes.count { !it.isRest }

        if (playableCount == 0) {
            _uiState.update {
                it.copy(
                    isKeyboardRecording = false,
                    keyboardRecordingElapsedSeconds = 0,
                    keyboardRecordedNotes = emptyList(),
                    snackbarMessage = str(R.string.msg_no_keyboard_notes_recorded)
                )
            }
            return
        }

        val nextNumber = savedSongs.value.size + 1
        _uiState.update {
            it.copy(
                isKeyboardRecording = false,
                keyboardRecordingElapsedSeconds = 0,
                keyboardRecordedNotes = emptyList(),
                activeSheet = ActiveSheetState(
                    songId = 0L,
                    title = str(R.string.default_numbered_melody_title, nextNumber),
                    notes = quantizedNotes,
                    bpm = transcription.effectiveBpm,
                    beatsPerMeasure = currentSettings.beatsPerMeasure,
                    calibrationCentsUsed = currentSettings.calibrationCents,
                    isSaved = false
                )
            )
        }
    }

    // ---- RECORDING ("SLUŠAJ SADA") ----

    fun startRecording() {
        if (_uiState.value.isRecording) return
        if (_uiState.value.isKeyboardRecording) {
            keyboardRecordingTimerJob?.cancel()
            keyboardRecordingTimerJob = null
            keyboardRecorder.cancel()
        }
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
                isKeyboardRecording = false,
                keyboardRecordingElapsedSeconds = 0,
                keyboardRecordedNotes = emptyList(),
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
                    val msg = if (err is MicrophoneUnavailableException) {
                        str(R.string.error_mic_unavailable)
                    } else {
                        str(R.string.error_mic_start_failed)
                    }
                    _uiState.update {
                        it.copy(
                            isRecording = false,
                            isMicClipping = false,
                            snackbarMessage = msg
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
                    snackbarMessage = str(R.string.msg_no_piano_notes_heard)
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
                    title = str(R.string.default_numbered_melody_title, nextNumber),
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
                        str(R.string.tune_status_idle_saved)
                    } else {
                        str(R.string.tune_status_idle_default)
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
                        statusMessage = str(R.string.tune_status_reset_done)
                    ),
                    snackbarMessage = str(R.string.msg_tuning_reset_snackbar)
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
                .catch {
                    _uiState.update {
                        it.copy(
                            tuneDialog = it.tuneDialog.copy(
                                isListening = false,
                                phase = TunePhase.IDLE,
                                liveRms = 0f,
                                scaleSnapshot = ScaleCalibrationSnapshot(
                                    statusCode = ScaleStatusCode.MIC_UNAVAILABLE
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
                                snackbarMessage = str(R.string.msg_scale_profile_saved_snackbar, c4Str, sign, ct)
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
                    statusMessage = str(R.string.tune_status_listening_prompt)
                )
            )
        }

        calibrationJob?.cancel()
        calibrationJob = viewModelScope.launch {
            audioEngine.pitchFramesFlow(calibrationCents = 0f, widePitchTolerance = true)
                .catch {
                    _uiState.update {
                        it.copy(
                            tuneDialog = it.tuneDialog.copy(
                                phase = TunePhase.IDLE,
                                isListening = false,
                                liveRms = 0f,
                                statusMessage = str(R.string.error_mic_unavailable)
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
                                            statusMessage = str(
                                                R.string.tune_status_unstable,
                                                cLabel,
                                                c4Text,
                                                sign,
                                                roundedCt
                                            )
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
                                            statusMessage = str(
                                                R.string.tune_status_locking,
                                                cLabel,
                                                c4Text,
                                                sign,
                                                roundedCt,
                                                collectedCents.size,
                                                targetSamples
                                            )
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
                                                statusMessage = str(
                                                    R.string.tune_status_saved,
                                                    finalC4Text,
                                                    finalSign,
                                                    finalCt,
                                                    finalA4Text
                                                )
                                            ),
                                            snackbarMessage = str(
                                                R.string.msg_quick_c_saved_snackbar,
                                                finalC4Text,
                                                finalSign,
                                                finalCt
                                            )
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
                                            statusMessage = str(
                                                R.string.tune_status_wrong_note,
                                                heardName,
                                                rawHzText
                                            )
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
                                            statusMessage = str(
                                                R.string.tune_status_too_short,
                                                partialHzText,
                                                partialSign,
                                                partialCt
                                            )
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
                                            statusMessage = str(R.string.tune_status_no_sound_yet)
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
        if (sheet.isPlaying) {
            stopPlayback()
        }
        _uiState.update { state ->
            val currentSheet = state.activeSheet ?: return@update state
            val adjustedOctave = adjustOctaveForMidi(
                state.keyboardStartOctave,
                note.takeIf { !it.isRest }?.midiNote
            )
            state.copy(
                keyboardStartOctave = adjustedOctave,
                activeSheet = currentSheet.copy(selectedNoteIndex = index)
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
            val msPerBeat = 60_000.0 / sheet.bpm.coerceIn(MetronomeEngine.MIN_BPM, MetronomeEngine.MAX_BPM)
            val previewMs = (duration.beats * msPerBeat).toInt().coerceIn(180, 1200)
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
            onPianoKeyPressed(midiNote)
        }
    }

    fun onSheetPianoKeyReleased(midiNote: Int) {
        val sheet = _uiState.value.activeSheet
        if (sheet == null || !sheet.isEditMode) {
            onPianoKeyReleased(midiNote)
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

    fun clearActiveSheetNotes() {
        val sheet = _uiState.value.activeSheet ?: return
        if (sheet.notes.isEmpty()) return
        stopPlayback()
        val (clearedNotes, clearedSel) = SheetNoteEditor.clearAllNotes()
        _uiState.update { state ->
            val currentSheet = state.activeSheet ?: return@update state
            state.copy(
                activeSheet = currentSheet.copy(
                    notes = clearedNotes,
                    selectedNoteIndex = clearedSel,
                    isEditMode = false,
                    isPlaying = false,
                    playingNoteIndex = null,
                    isSaved = false
                ),
                snackbarMessage = str(R.string.msg_sheet_cleared)
            )
        }
    }

    fun adjustActiveSheetBpm(deltaBpm: Int) {
        _uiState.update { state ->
            val sheet = state.activeSheet ?: return@update state
            val nextBpm = SheetNoteEditor.adjustTempo(sheet.bpm, deltaBpm)
            if (nextBpm == sheet.bpm) {
                state
            } else {
                state.copy(activeSheet = sheet.copy(bpm = nextBpm, isSaved = false))
            }
        }
    }

    fun togglePlayback() {
        val sheet = _uiState.value.activeSheet ?: return
        if (sheet.isPlaying) {
            stopPlayback()
            return
        }
        startPlaybackFrom(startIndex = 0, keepEditMode = false)
    }

    fun togglePlaybackFromSelected() {
        val sheet = _uiState.value.activeSheet ?: return
        if (sheet.isPlaying) {
            stopPlayback()
            return
        }
        val startIndex = SheetNoteEditor.resolvePlaybackStartIndex(
            notesSize = sheet.notes.size,
            requestedStartIndex = sheet.selectedNoteIndex
        ) ?: return
        startPlaybackFrom(startIndex = startIndex, keepEditMode = sheet.isEditMode)
    }

    private fun startPlaybackFrom(startIndex: Int, keepEditMode: Boolean) {
        val sheet = _uiState.value.activeSheet ?: return
        val resolvedStart = SheetNoteEditor.resolvePlaybackStartIndex(sheet.notes.size, startIndex) ?: return

        playbackJob?.cancel()
        val notesSnapshot = sheet.notes
        _uiState.update { state ->
            val currentSheet = state.activeSheet ?: return@update state
            state.copy(
                activeSheet = currentSheet.copy(
                    isPlaying = true,
                    isEditMode = keepEditMode,
                    playingNoteIndex = null,
                    selectedNoteIndex = if (keepEditMode) currentSheet.selectedNoteIndex else null
                )
            )
        }

        playbackJob = viewModelScope.launch {
            for (idx in resolvedStart until notesSnapshot.size) {
                if (!isActive) return@launch
                val note = notesSnapshot[idx]
                val liveBpm = _uiState.value.activeSheet?.bpm ?: sheet.bpm
                _uiState.update { state ->
                    state.activeSheet?.let { cur ->
                        state.copy(activeSheet = cur.copy(playingNoteIndex = idx))
                    } ?: state
                }
                val stepDurationMs = SheetNoteEditor.computePlaybackStepMs(note.beats, liveBpm)
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
            showMessage(str(R.string.msg_add_note_before_saving))
            return
        }
        viewModelScope.launch {
            val cleanTitle = sheet.title.trim().ifEmpty { str(R.string.default_song_title_short) }
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
                        snackbarMessage = str(R.string.msg_saved_to_notebook)
                    )
                } ?: state
            }
        }
    }

    fun deleteSavedSong(song: SavedSong) {
        viewModelScope.launch {
            songRepository.deleteSong(song.id)
            showMessage(str(R.string.msg_song_deleted, song.title))
        }
    }

    override fun onCleared() {
        super.onCleared()
        recordingJob?.cancel()
        recordingTimerJob?.cancel()
        keyboardRecordingTimerJob?.cancel()
        calibrationJob?.cancel()
        playbackJob?.cancel()
        metronomeEngine.stop()
        synthesizer.stopAll()
    }
}
