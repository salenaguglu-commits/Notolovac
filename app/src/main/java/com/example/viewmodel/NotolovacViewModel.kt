package com.example.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioCaptureEngine
import com.example.audio.PianoNoteTracker
import com.example.audio.PianoSynthesizer
import com.example.data.NotolovacDatabase
import com.example.data.SavedSong
import com.example.data.SongRepository
import com.example.data.UserPreferencesRepository
import com.example.data.UserSettings
import com.example.model.DetectedNote
import com.example.model.MusicTheory
import com.example.model.NoteLabelMode
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

data class TuneDialogState(
    val isOpen: Boolean = false,
    val isListening: Boolean = false,
    val liveHz: Double = 0.0,
    val liveCents: Float = 0f,
    val liveRms: Float = 0f,
    val progress: Float = 0f,
    val statusMessage: String = "Pritisni „Slušaj moje C“ pa odsviraj srednje C (Do) na klaviru.",
    val completed: Boolean = false
)

data class ActiveSheetState(
    val songId: Long = 0L,
    val title: String = "Moja nova melodija",
    val notes: List<DetectedNote> = emptyList(),
    val bpm: Int = 90,
    val beatsPerMeasure: Int = 4,
    val calibrationCentsUsed: Float = 0f,
    val selectedNoteIndex: Int? = null,
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
    private var metronomeJob: Job? = null
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
        if (_uiState.value.metronomeRunning) {
            restartMetronomeLoop(clamped, settings.value.beatsPerMeasure)
        }
    }

    fun setBeatsPerMeasure(beats: Int) {
        val valid = if (beats == 3) 3 else 4
        viewModelScope.launch {
            preferencesRepository.setBeatsPerMeasure(valid)
        }
        if (_uiState.value.metronomeRunning) {
            restartMetronomeLoop(settings.value.metronomeBpm, valid)
        }
    }

    fun toggleMetronome() {
        val nowRunning = !_uiState.value.metronomeRunning
        viewModelScope.launch {
            preferencesRepository.setMetronomeEnabled(nowRunning)
        }
        if (nowRunning) {
            restartMetronomeLoop(settings.value.metronomeBpm, settings.value.beatsPerMeasure)
        } else {
            stopMetronomeLoop()
        }
    }

    private fun restartMetronomeLoop(bpm: Int, beatsPerMeasure: Int) {
        metronomeJob?.cancel()
        _uiState.update { it.copy(metronomeRunning = true, currentBeatIndex = 0) }
        metronomeJob = viewModelScope.launch {
            var beat = 0
            val intervalMs = (60_000L / bpm.coerceIn(60, 140))
            while (isActive) {
                _uiState.update { it.copy(currentBeatIndex = beat) }
                synthesizer.playMetronomeTick(accent = beat == 0)
                beat = (beat + 1) % beatsPerMeasure
                delay(intervalMs)
            }
        }
    }

    private fun stopMetronomeLoop() {
        metronomeJob?.cancel()
        metronomeJob = null
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
        val tracker = PianoNoteTracker()
        activeTracker = tracker

        val useMetronome = currentSettings.metronomeEnabled || _uiState.value.metronomeRunning
        recordingHadMetronome = useMetronome

        _uiState.update {
            it.copy(
                isRecording = true,
                recordingElapsedSeconds = 0,
                liveMidiNote = null,
                liveCentsOff = 0f,
                liveFrequencyHz = 0.0,
                rmsLevel = 0f,
                liveCapturedNotes = emptyList(),
                activeSheet = null
            )
        }

        if (useMetronome && !_uiState.value.metronomeRunning) {
            restartMetronomeLoop(currentSettings.metronomeBpm, currentSettings.beatsPerMeasure)
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
            audioEngine.pitchFramesFlow(calibrationCents = currentSettings.calibrationCents)
                .catch { err ->
                    _uiState.update {
                        it.copy(
                            isRecording = false,
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
        _uiState.update {
            it.copy(
                tuneDialog = TuneDialogState(
                    isOpen = true,
                    isListening = false,
                    liveCents = settings.value.calibrationCents,
                    liveHz = settings.value.calibratedC4Hz.toDouble(),
                    statusMessage = "Pritisni „Slušaj moje C“ pa odsviraj srednje C (Do) na klaviru."
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
            preferencesRepository.saveCalibration(
                cents = 0f,
                c4Hz = MusicTheory.STANDARD_C4_HZ.toFloat(),
                isCalibrated = false
            )
            _uiState.update {
                it.copy(
                    tuneDialog = it.tuneDialog.copy(
                        liveCents = 0f,
                        liveHz = MusicTheory.STANDARD_C4_HZ,
                        progress = 0f,
                        completed = false,
                        statusMessage = "Vraćeno na standardno štimovanje (A4 = 440 Hz, C4 = 261.6 Hz)."
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
        val collectedCents = mutableListOf<Float>()
        val targetSamples = 8

        _uiState.update {
            it.copy(
                tuneDialog = it.tuneDialog.copy(
                    isListening = true,
                    liveRms = 0f,
                    progress = 0f,
                    completed = false,
                    statusMessage = "Slušam... Odsviraj i zadrži notu C (Do) na klaviru!"
                )
            )
        }

        calibrationJob?.cancel()
        calibrationJob = viewModelScope.launch {
            audioEngine.pitchFramesFlow(calibrationCents = 0f)
                .catch { err ->
                    _uiState.update {
                        it.copy(
                            tuneDialog = it.tuneDialog.copy(
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
                    if (est.midiNote != null && est.frequencyHz > 0.0 && est.confidence >= 0.45f && est.rms >= 0.0035f) {
                        val cResult = MusicTheory.computeCalibrationCentsFromC(est.frequencyHz)
                        if (cResult != null) {
                            val (centsDiff, normalizedC4) = cResult
                            if (collectedCents.isNotEmpty() && abs(collectedCents.last() - centsDiff) > 25f) {
                                collectedCents.clear()
                            }
                            collectedCents.add(centsDiff)
                            val prog = (collectedCents.size.toFloat() / targetSamples).coerceIn(0f, 1f)

                            _uiState.update {
                                it.copy(
                                    tuneDialog = it.tuneDialog.copy(
                                        liveHz = normalizedC4,
                                        liveCents = centsDiff,
                                        progress = prog,
                                        statusMessage = "Odlično! Zadrži notu C još malo..."
                                    )
                                )
                            }

                            if (collectedCents.size >= targetSamples) {
                                val sorted = collectedCents.sorted()
                                val medianCents = sorted[sorted.size / 2]
                                val calibratedC4 = (MusicTheory.STANDARD_C4_HZ *
                                    Math.pow(2.0, medianCents / 1200.0)).toFloat()

                                preferencesRepository.saveCalibration(
                                    cents = medianCents,
                                    c4Hz = calibratedC4,
                                    isCalibrated = true
                                )
                                val roundedCents = medianCents.roundToInt()
                                val sign = if (roundedCents > 0) "+" else ""
                                _uiState.update {
                                    it.copy(
                                        tuneDialog = it.tuneDialog.copy(
                                            isListening = false,
                                            liveHz = calibratedC4.toDouble(),
                                            liveCents = medianCents,
                                            progress = 1f,
                                            completed = true,
                                            statusMessage = "Bravo! Klavir je naštimovan ($sign$roundedCents centi)."
                                        ),
                                        snackbarMessage = "Klavir je uspešno naštimovan na tvoje C!"
                                    )
                                }
                                calibrationJob?.cancel()
                            }
                        } else {
                            val heardName = MusicTheory.formatFullNoteDescription(est.midiNote)
                            _uiState.update {
                                it.copy(
                                    tuneDialog = it.tuneDialog.copy(
                                        statusMessage = "Čujem $heardName — pokušaj da pritisneš dirku C (Do)!"
                                    )
                                )
                            }
                        }
                    }
                }
        }
    }

    private fun stopCalibrationListening() {
        calibrationJob?.cancel()
        calibrationJob = null
        _uiState.update { it.copy(tuneDialog = it.tuneDialog.copy(isListening = false)) }
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

    fun selectAndPreviewNote(index: Int) {
        val sheet = _uiState.value.activeSheet ?: return
        val note = sheet.notes.getOrNull(index) ?: return
        _uiState.update { state ->
            state.copy(activeSheet = sheet.copy(selectedNoteIndex = index))
        }
        if (!note.isRest) {
            onPianoKeyTapped(note.midiNote)
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
        val updated = sheet.notes.toMutableList().apply { removeAt(index) }
        _uiState.update { state ->
            state.copy(
                activeSheet = sheet.copy(
                    notes = updated,
                    selectedNoteIndex = null,
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
            it.copy(activeSheet = sheet.copy(isPlaying = true, playingNoteIndex = null, selectedNoteIndex = null))
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
                val stepDurationMs = (note.beats * msPerBeat).toLong().coerceIn(180L, 2600L)
                if (!note.isRest) {
                    synthesizer.playNote(
                        midiNote = note.midiNote,
                        durationMs = (stepDurationMs * 1.15).toInt().coerceAtLeast(320),
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
        metronomeJob?.cancel()
        calibrationJob?.cancel()
        playbackJob?.cancel()
        synthesizer.stopAll()
    }
}
