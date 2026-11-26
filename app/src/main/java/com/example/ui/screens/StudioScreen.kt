package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.audio.MetronomeEngine
import com.example.data.UserSettings
import com.example.model.MusicTheory
import com.example.model.NoteLabelMode
import com.example.ui.components.AboutAppDialog
import com.example.ui.components.GlowingPianoKeyboard
import com.example.ui.components.LiveSheetStrip
import com.example.ui.components.OctaveSelectorRow
import com.example.ui.theme.CoralContainer
import com.example.ui.theme.CoralPrimary
import com.example.ui.theme.CreamPaper
import com.example.ui.theme.HoneyGoldContainer
import com.example.ui.theme.HoneyGoldTertiary
import com.example.ui.theme.LavenderContainer
import com.example.ui.theme.LavenderMusic
import com.example.ui.theme.MutedInk
import com.example.ui.theme.OnCoralContainer
import com.example.ui.theme.OnLavenderContainer
import com.example.ui.theme.OnSageMintContainer
import com.example.ui.theme.SageMintContainer
import com.example.ui.theme.SageMintSecondary
import com.example.ui.theme.SoftError
import com.example.ui.theme.WarmCard
import com.example.ui.theme.WarmCardBorder
import com.example.ui.theme.WarmInk
import com.example.ui.theme.WarmSurface
import com.example.viewmodel.NotolovacUiState
import kotlin.math.roundToInt

@Composable
fun StudioScreen(
    uiState: NotolovacUiState,
    settings: UserSettings,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onListenToggle: () -> Unit,
    onToggleKeyboardRecording: () -> Unit = {},
    onOpenTuneDialog: () -> Unit,
    onLabelModeSelected: (NoteLabelMode) -> Unit,
    onToggleMetronome: () -> Unit,
    onBpmChange: (Int) -> Unit,
    onBeatsPerMeasureChange: (Int) -> Unit,
    onOctaveChange: (Int) -> Unit,
    onPianoKeyClick: (Int) -> Unit,
    onPianoKeyRelease: (Int) -> Unit = {},
    onClearKeyboardHistory: () -> Unit = {},
    isLandscape: Boolean = false,
    modifier: Modifier = Modifier
) {
    val activeKeyMidi = uiState.liveMidiNote ?: uiState.keyboardPressedMidi
    val isAnyRecording = uiState.isRecording || uiState.isKeyboardRecording
    val scrollState = rememberScrollState()
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }

    if (showAboutDialog) {
        AboutAppDialog(onDismiss = { showAboutDialog = false })
    }

    if (isLandscape) {
        LandscapePianoStage(
            uiState = uiState,
            settings = settings,
            onToggleKeyboardRecording = onToggleKeyboardRecording,
            onOctaveChange = onOctaveChange,
            onPianoKeyClick = onPianoKeyClick,
            onPianoKeyRelease = onPianoKeyRelease,
            onClearKeyboardHistory = onClearKeyboardHistory,
            modifier = modifier
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CreamPaper),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Playful Notebook Header + "Štimuj C" calibration pill
            StudioHeaderRow(
                settings = settings,
                isRecording = isAnyRecording,
                onOpenTuneDialog = onOpenTuneDialog,
                onLongPressMascot = { showAboutDialog = true }
            )

            // Mic permission friendly banner if not yet granted
            AnimatedVisibility(visible = !hasMicPermission) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = HoneyGoldContainer,
                    border = BorderStroke(1.5.dp, HoneyGoldTertiary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = null,
                            tint = CoralPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.permission_mic_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = WarmInk
                            )
                            Text(
                                text = stringResource(R.string.permission_mic_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedInk
                            )
                        }
                        Button(
                            onClick = onRequestMicPermission,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("grant_mic_button")
                        ) {
                            Text(stringResource(R.string.action_allow), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // 2. Metronome Control Card (BPM, 4/4 vs 3/4, bouncing beat dots)
            MetronomeCompactCard(
                bpm = settings.metronomeBpm,
                beatsPerMeasure = settings.beatsPerMeasure,
                isRunning = uiState.metronomeRunning,
                currentBeat = uiState.currentBeatIndex,
                onToggle = onToggleMetronome,
                onBpmChange = onBpmChange,
                onBeatsChange = onBeatsPerMeasureChange
            )

            // 3. Live Music Notebook Paper Card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFFFFFDF8),
                border = BorderStroke(
                    width = if (isAnyRecording) 2.5.dp else 1.5.dp,
                    color = if (isAnyRecording) CoralPrimary else WarmCardBorder
                ),
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp, bottom = 10.dp)) {
                    // Top status bar inside notebook card
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        LiveNoteBadge(
                            liveMidiNote = uiState.liveMidiNote,
                            isRecording = uiState.isRecording,
                            isKeyboardRecording = uiState.isKeyboardRecording,
                            capturedCount = if (uiState.isKeyboardRecording) {
                                uiState.keyboardRecordedNotes.size
                            } else {
                                uiState.liveCapturedNotes.size
                            }
                        )

                        if (isAnyRecording) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (uiState.isRecording) {
                                    SoundLevelBars(rms = uiState.rmsLevel)
                                }
                                val elapsed = if (uiState.isKeyboardRecording) {
                                    uiState.keyboardRecordingElapsedSeconds
                                } else {
                                    uiState.recordingElapsedSeconds
                                }
                                val mins = elapsed / 60
                                val secs = elapsed % 60
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = CoralContainer
                                ) {
                                    Text(
                                        text = String.format("%d:%02d", mins, secs),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = OnCoralContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        } else {
                            // Label mode quick switcher (Do-Re-Mi / C-D-E / Clean)
                            NoteLabelModeRow(
                                selectedMode = settings.noteLabelMode,
                                onSelect = onLabelModeSelected
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Live Canvas Sheet Music Strip
                    LiveSheetStrip(
                        capturedNotes = when {
                            uiState.isKeyboardRecording -> uiState.keyboardRecordedNotes
                            uiState.isRecording || uiState.liveCapturedNotes.isNotEmpty() -> uiState.liveCapturedNotes
                            else -> uiState.keyboardPlayedHistory
                        },
                        liveMidiNote = uiState.liveMidiNote,
                        liveMidiNotes = uiState.keyboardActiveNotes,
                        beatsPerMeasure = settings.beatsPerMeasure,
                        labelMode = settings.noteLabelMode
                    )

                    AnimatedVisibility(visible = uiState.isRecording && uiState.isMicClipping) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = HoneyGoldContainer,
                            border = BorderStroke(1.dp, HoneyGoldTertiary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                                .testTag("mic_clipping_hint")
                        ) {
                            Text(
                                text = stringResource(R.string.studio_mic_clipping_hint),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = WarmInk,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    val hasPortraitStaffNotes = uiState.liveCapturedNotes.isNotEmpty() ||
                        uiState.keyboardPlayedHistory.isNotEmpty()
                    if (!isAnyRecording && hasPortraitStaffNotes) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End
                        ) {
                            Surface(
                                onClick = onClearKeyboardHistory,
                                shape = RoundedCornerShape(12.dp),
                                color = CoralContainer.copy(alpha = 0.65f),
                                border = BorderStroke(1.dp, SoftError.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .height(48.dp)
                                    .padding(vertical = 6.dp)
                                    .testTag("studio_clear_staff_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteSweep,
                                        contentDescription = stringResource(R.string.action_clear_sheet_desc),
                                        tint = SoftError,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.action_clear_sheet),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = OnCoralContainer
                                    )
                                }
                            }
                        }
                    } else if (!isAnyRecording) {
                        Text(
                            text = stringResource(R.string.studio_empty_staff_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedInk,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // 4. Interactive Glowing Piano Keyboard
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.studio_keyboard_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = WarmInk
                    )
                    OctaveSelectorRow(
                        startOctave = uiState.keyboardStartOctave,
                        onSelectOctave = onOctaveChange
                    )
                }

                GlowingPianoKeyboard(
                    activeMidiNote = activeKeyMidi,
                    activeMidiNotes = uiState.keyboardActiveNotes,
                    startOctave = uiState.keyboardStartOctave,
                    labelMode = settings.noteLabelMode,
                    onKeyClick = onPianoKeyClick,
                    onKeyRelease = onPianoKeyRelease,
                    keyboardHeight = 114.dp
                )
            }

            // 5. Big Inviting "Slušaj sada" / "Završi snimanje" Primary Button
            Spacer(Modifier.height(4.dp))
            ListenNowButton(
                isRecording = isAnyRecording,
                onClick = onListenToggle
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Full-width distraction-free Landscape Piano Stage:
 * Hides top header, metronome, tuner, and navigation so the child gets a wide dynamic live staff
 * on top, a 1-tap "Snimi svirku" / "Record Play" button, and a tall 2-octave multi-touch/glissando keyboard below.
 */
@Composable
private fun LandscapePianoStage(
    uiState: NotolovacUiState,
    settings: UserSettings,
    onToggleKeyboardRecording: () -> Unit,
    onOctaveChange: (Int) -> Unit,
    onPianoKeyClick: (Int) -> Unit,
    onPianoKeyRelease: (Int) -> Unit,
    onClearKeyboardHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeKeyMidi = uiState.liveMidiNote ?: uiState.keyboardPressedMidi
    val isAnyRecording = uiState.isRecording || uiState.isKeyboardRecording
    val displayedNotes = when {
        uiState.isKeyboardRecording -> uiState.keyboardRecordedNotes
        uiState.isRecording || uiState.liveCapturedNotes.isNotEmpty() -> uiState.liveCapturedNotes
        else -> uiState.keyboardPlayedHistory
    }
    val elapsedSeconds = if (uiState.isKeyboardRecording) {
        uiState.keyboardRecordingElapsedSeconds
    } else {
        uiState.recordingElapsedSeconds
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CreamPaper)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("landscape_piano_stage"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top 42%: Wide Dynamic Live Music Staff showing played notes & live chords
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFFFFFDF8),
            border = BorderStroke(
                width = if (isAnyRecording) 2.5.dp else 1.5.dp,
                color = if (isAnyRecording) CoralPrimary else WarmCardBorder
            ),
            shadowElevation = 2.dp,
            modifier = Modifier
                .weight(0.42f)
                .fillMaxWidth()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LiveSheetStrip(
                    capturedNotes = displayedNotes,
                    liveMidiNote = uiState.liveMidiNote,
                    liveMidiNotes = uiState.keyboardActiveNotes,
                    beatsPerMeasure = settings.beatsPerMeasure,
                    labelMode = settings.noteLabelMode,
                    maxSlots = 13,
                    fillAvailableHeight = true,
                    modifier = Modifier.fillMaxSize()
                )

                // Compact live recording indicator in top-left while recording
                if (isAnyRecording) {
                    val recCount = if (uiState.isKeyboardRecording) {
                        uiState.keyboardRecordedNotes.size
                    } else {
                        uiState.liveCapturedNotes.size
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = CoralContainer.copy(alpha = 0.94f),
                        border = BorderStroke(1.dp, CoralPrimary),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 10.dp, top = 6.dp)
                            .testTag("landscape_rec_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FiberManualRecord,
                                contentDescription = null,
                                tint = Color(0xFFD64545),
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = stringResource(R.string.live_badge_recording_keys, recCount),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = OnCoralContainer
                            )
                        }
                    }
                }

                // Top-right compact stage controls: Clear Staff, Octave toggle, and "Snimi svirku" button
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!isAnyRecording && displayedNotes.isNotEmpty()) {
                        Surface(
                            onClick = onClearKeyboardHistory,
                            shape = RoundedCornerShape(10.dp),
                            color = CoralContainer.copy(alpha = 0.88f),
                            border = BorderStroke(1.dp, SoftError.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .height(48.dp)
                                .padding(vertical = 8.dp)
                                .testTag("landscape_clear_staff_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteSweep,
                                    contentDescription = stringResource(R.string.action_clear_sheet_desc),
                                    tint = SoftError,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.action_clear_sheet),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = OnCoralContainer
                                )
                            }
                        }
                    }

                    val nextOctave = if (uiState.keyboardStartOctave == 4) 3 else 4
                    val octaveLabel = if (uiState.keyboardStartOctave == 4) "C4–C6" else "C3–C5"
                    Surface(
                        onClick = { onOctaveChange(nextOctave) },
                        shape = RoundedCornerShape(10.dp),
                        color = HoneyGoldContainer.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, HoneyGoldTertiary),
                        modifier = Modifier
                            .height(48.dp)
                            .padding(vertical = 8.dp)
                            .testTag("landscape_octave_toggle")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        ) {
                            Text(
                                text = octaveLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = WarmInk
                            )
                        }
                    }

                    // Primary Landscape "Snimi svirku" / "Završi 0:05" pill button
                    val mins = elapsedSeconds / 60
                    val secs = elapsedSeconds % 60
                    val recordLabel = if (isAnyRecording) {
                        "${stringResource(R.string.action_stop_record_play)} ${String.format("%d:%02d", mins, secs)}"
                    } else {
                        stringResource(R.string.action_record_play)
                    }
                    Surface(
                        onClick = onToggleKeyboardRecording,
                        shape = RoundedCornerShape(14.dp),
                        color = if (isAnyRecording) Color(0xFFD64545) else CoralPrimary,
                        shadowElevation = if (isAnyRecording) 4.dp else 2.dp,
                        modifier = Modifier
                            .height(48.dp)
                            .padding(vertical = 6.dp)
                            .testTag("landscape_record_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (isAnyRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(if (isAnyRecording) 16.dp else 13.dp)
                            )
                            Text(
                                text = recordLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Bottom 58%: Wide, tall full-screen multi-touch & glissando keyboard
        GlowingPianoKeyboard(
            activeMidiNote = activeKeyMidi,
            activeMidiNotes = uiState.keyboardActiveNotes,
            startOctave = uiState.keyboardStartOctave,
            labelMode = settings.noteLabelMode,
            onKeyClick = onPianoKeyClick,
            onKeyRelease = onPianoKeyRelease,
            keyboardHeight = null,
            modifier = Modifier
                .weight(0.58f)
                .fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StudioHeaderRow(
    settings: UserSettings,
    isRecording: Boolean,
    onOpenTuneDialog: () -> Unit,
    onLongPressMascot: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ShimmeringMascotIcon(
                isRecording = isRecording,
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPressMascot()
                }
            )
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = WarmInk
                )
                Text(
                    text = if (isRecording) {
                        stringResource(R.string.studio_subtitle_listening)
                    } else {
                        stringResource(R.string.studio_subtitle_default)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedInk
                )
            }
        }

        // Tune / C-Calibration Pill Button
        val centsInt = settings.calibrationCents.roundToInt()
        val sign = if (centsInt > 0) "+" else ""
        Surface(
            onClick = onOpenTuneDialog,
            enabled = !isRecording,
            shape = RoundedCornerShape(16.dp),
            color = if (settings.isCalibrated) SageMintContainer else HoneyGoldContainer,
            border = BorderStroke(
                1.5.dp,
                if (settings.isCalibrated) SageMintSecondary else HoneyGoldTertiary
            ),
            modifier = Modifier
                .height(48.dp)
                .testTag("tune_c_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (settings.isCalibrated) Icons.Filled.CheckCircle else Icons.Filled.Tune,
                    contentDescription = stringResource(R.string.action_tune_c),
                    tint = if (settings.isCalibrated) SageMintSecondary else WarmInk,
                    modifier = Modifier.size(18.dp)
                )
                Column {
                    Text(
                        text = when {
                            settings.calibrationProfile.isScaleCalibrated -> stringResource(R.string.studio_tune_profile_saved)
                            settings.isCalibrated -> stringResource(R.string.studio_tune_saved)
                            else -> stringResource(R.string.action_tune_c)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (settings.isCalibrated) OnSageMintContainer else WarmInk
                    )
                    Text(
                        text = if (settings.isCalibrated) {
                            val hzStr = String.format(java.util.Locale.US, "%.1f Hz", settings.calibratedC4Hz)
                            "$hzStr ($sign$centsInt ct)"
                        } else {
                            stringResource(R.string.studio_tune_standard)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (settings.isCalibrated) OnSageMintContainer else MutedInk
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShimmeringMascotIcon(
    isRecording: Boolean,
    onLongPress: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mascotGlint")

    // Subtle periodic glint every 6.8 seconds: calm most of the time, then a warm golden sweep
    val shimmerProgress by infiniteTransition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 6800
                -0.6f at 0 using LinearEasing
                -0.6f at 5200 using LinearEasing
                1.6f at 6250 using LinearEasing
                1.6f at 6800 using LinearEasing
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "mascotShimmerSweep"
    )

    val glowStrength by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 6800
                0f at 0
                0f at 5050
                1f at 5750
                0.85f at 6150
                0f at 6700
                0f at 6800
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "mascotGlowAlpha"
    )

    val activeGlow = if (isRecording) 0f else glowStrength
    val iconScale = 1f + (activeGlow * 0.045f)
    val borderColor = lerp(WarmCardBorder, HoneyGoldTertiary, activeGlow)
    val borderWidth = (1.5f + activeGlow * 1.1f).dp
    val shape = RoundedCornerShape(14.dp)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(52.dp)
    ) {
        if (activeGlow > 0.02f) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                HoneyGoldTertiary.copy(alpha = 0.42f * activeGlow),
                                CoralPrimary.copy(alpha = 0.14f * activeGlow),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        Image(
            painter = painterResource(id = R.drawable.ic_notolovac_icon_cat),
            contentDescription = stringResource(R.string.studio_mascot_desc),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .scale(iconScale)
                .clip(shape)
                .drawWithContent {
                    drawContent()
                    if (!isRecording && shimmerProgress in -0.5f..1.5f) {
                        val band = size.width * 0.45f
                        val startX = shimmerProgress * size.width
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.55f),
                                    HoneyGoldContainer.copy(alpha = 0.35f),
                                    Color.Transparent
                                ),
                                start = Offset(startX - band, 0f),
                                end = Offset(startX + band, size.height)
                            )
                        )
                    }
                }
                .border(borderWidth, borderColor, shape)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress
                )
                .testTag("studio_mascot_icon")
        )

        if (activeGlow > 0.08f) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(14.dp)
                    .scale(0.6f + 0.5f * activeGlow)
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val rOuter = size.minDimension * 0.48f
                val rInner = rOuter * 0.30f
                val star = Path().apply {
                    moveTo(cx, cy - rOuter)
                    lineTo(cx + rInner, cy - rInner)
                    lineTo(cx + rOuter, cy)
                    lineTo(cx + rInner, cy + rInner)
                    lineTo(cx, cy + rOuter)
                    lineTo(cx - rInner, cy + rInner)
                    lineTo(cx - rOuter, cy)
                    lineTo(cx - rInner, cy - rInner)
                    close()
                }
                drawPath(star, color = HoneyGoldTertiary.copy(alpha = activeGlow))
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f * activeGlow),
                    radius = rInner * 0.55f,
                    center = Offset(cx, cy)
                )
            }
        }
    }
}

@Composable
private fun MetronomeCompactCard(
    bpm: Int,
    beatsPerMeasure: Int,
    isRunning: Boolean,
    currentBeat: Int,
    onToggle: () -> Unit,
    onBpmChange: (Int) -> Unit,
    onBeatsChange: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = WarmSurface,
        border = BorderStroke(1.dp, WarmCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Metronome play/stop button
                Surface(
                    onClick = onToggle,
                    shape = CircleShape,
                    color = if (isRunning) LavenderMusic else LavenderContainer,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("metronome_toggle_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = if (isRunning) {
                                stringResource(R.string.metronome_stop_desc)
                            } else {
                                stringResource(R.string.metronome_start_desc)
                            },
                            tint = if (isRunning) Color.White else OnLavenderContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Beat dots + label
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (b in 0 until beatsPerMeasure) {
                            val active = isRunning && currentBeat == b
                            Box(
                                modifier = Modifier
                                    .size(if (active) 11.dp else 8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            active && b == 0 -> CoralPrimary
                                            active -> LavenderMusic
                                            else -> WarmCardBorder
                                        }
                                    )
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.metronome_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedInk
                    )
                }

                // BPM - / + controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    IconButton(
                        onClick = { onBpmChange(bpm - 5) },
                        enabled = bpm > MetronomeEngine.MIN_BPM,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("bpm_minus_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Remove,
                            contentDescription = stringResource(R.string.metronome_tempo_down_desc),
                            tint = if (bpm > MetronomeEngine.MIN_BPM) WarmInk else MutedInk.copy(alpha = 0.4f)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$bpm",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = WarmInk
                        )
                        Text(
                            text = "BPM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedInk
                        )
                    }
                    IconButton(
                        onClick = { onBpmChange(bpm + 5) },
                        enabled = bpm < MetronomeEngine.MAX_BPM,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("bpm_plus_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.metronome_tempo_up_desc),
                            tint = if (bpm < MetronomeEngine.MAX_BPM) WarmInk else MutedInk.copy(alpha = 0.4f)
                        )
                    }
                }

                // 4/4 vs 3/4 time signature pill
                Surface(
                    onClick = { onBeatsChange(if (beatsPerMeasure == 4) 3 else 4) },
                    shape = RoundedCornerShape(12.dp),
                    color = WarmCard,
                    border = BorderStroke(1.dp, WarmCardBorder),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("time_signature_button")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    ) {
                        Text(
                            text = "$beatsPerMeasure/4",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = WarmInk
                        )
                    }
                }
            }

            // Smooth tempo slider (40..260 BPM) for fast jumps to any tempo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${MetronomeEngine.MIN_BPM}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedInk
                )
                Slider(
                    value = bpm.toFloat().coerceIn(
                        MetronomeEngine.MIN_BPM.toFloat(),
                        MetronomeEngine.MAX_BPM.toFloat()
                    ),
                    onValueChange = { onBpmChange(it.roundToInt()) },
                    valueRange = MetronomeEngine.MIN_BPM.toFloat()..MetronomeEngine.MAX_BPM.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = LavenderMusic,
                        activeTrackColor = LavenderMusic,
                        inactiveTrackColor = LavenderContainer
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .testTag("bpm_slider")
                )
                Text(
                    text = "${MetronomeEngine.MAX_BPM}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedInk
                )
            }
        }
    }
}

@Composable
private fun LiveNoteBadge(
    liveMidiNote: Int?,
    isRecording: Boolean,
    isKeyboardRecording: Boolean,
    capturedCount: Int
) {
    val activeRec = isRecording || isKeyboardRecording
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = when {
                liveMidiNote != null -> HoneyGoldContainer
                activeRec -> CoralContainer
                else -> WarmSurface
            },
            border = BorderStroke(
                1.dp,
                when {
                    liveMidiNote != null -> HoneyGoldTertiary
                    activeRec -> CoralPrimary
                    else -> WarmCardBorder
                }
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = if (liveMidiNote != null) CoralPrimary else MutedInk,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = when {
                        liveMidiNote != null -> MusicTheory.formatFullNoteDescription(liveMidiNote)
                        isKeyboardRecording -> stringResource(R.string.live_badge_recording_keys, capturedCount)
                        isRecording -> stringResource(R.string.live_badge_listening, capturedCount)
                        else -> stringResource(R.string.live_badge_sheet)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = WarmInk
                )
            }
        }
    }
}

@Composable
private fun SoundLevelBars(rms: Float) {
    val level = (kotlin.math.sqrt(rms.coerceAtLeast(0f)) * 2.8f).coerceIn(0.14f, 1f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(20.dp)
    ) {
        val weights = floatArrayOf(0.55f, 0.9f, 1.0f, 0.7f)
        weights.forEach { w ->
            val barH = (18f * level * w).coerceIn(4f, 18f)
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(barH.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SageMintSecondary)
            )
        }
    }
}

@Composable
fun NoteLabelModeRow(
    selectedMode: NoteLabelMode,
    onSelect: (NoteLabelMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val items = listOf(
            NoteLabelMode.SOLFEGE to stringResource(R.string.label_mode_solfege),
            NoteLabelMode.LETTERS to stringResource(R.string.label_mode_letters),
            NoteLabelMode.NONE to stringResource(R.string.label_mode_clean)
        )
        items.forEach { (mode, label) ->
            val isSelected = selectedMode == mode
            Surface(
                onClick = { onSelect(mode) },
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) LavenderContainer else WarmSurface,
                border = BorderStroke(1.dp, if (isSelected) LavenderMusic else WarmCardBorder),
                modifier = Modifier.testTag("label_mode_${mode.key}")
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) OnLavenderContainer else MutedInk,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun ListenNowButton(
    isRecording: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recordPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isRecording) 1.035f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val gradient = if (isRecording) {
        Brush.horizontalGradient(listOf(Color(0xFFD64545), CoralPrimary))
    } else {
        Brush.horizontalGradient(listOf(CoralPrimary, Color(0xFFF08A4B)))
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = if (isRecording) 8.dp else 5.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .scale(pulseScale)
            .testTag("listen_now_button")
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = if (isRecording) {
                        stringResource(R.string.action_stop_listening)
                    } else {
                        stringResource(R.string.action_listen_now)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}
