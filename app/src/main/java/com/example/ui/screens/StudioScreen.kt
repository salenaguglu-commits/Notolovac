package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.UserSettings
import com.example.model.MusicTheory
import com.example.model.NoteLabelMode
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
    onOpenTuneDialog: () -> Unit,
    onLabelModeSelected: (NoteLabelMode) -> Unit,
    onToggleMetronome: () -> Unit,
    onBpmChange: (Int) -> Unit,
    onBeatsPerMeasureChange: (Int) -> Unit,
    onOctaveChange: (Int) -> Unit,
    onPianoKeyClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeKeyMidi = uiState.liveMidiNote ?: uiState.keyboardPressedMidi
    val scrollState = rememberScrollState()

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
                isRecording = uiState.isRecording,
                onOpenTuneDialog = onOpenTuneDialog
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
                            Text("Dozvoli", style = MaterialTheme.typography.labelMedium)
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
                    width = if (uiState.isRecording) 2.5.dp else 1.5.dp,
                    color = if (uiState.isRecording) CoralPrimary else WarmCardBorder
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
                            capturedCount = uiState.liveCapturedNotes.size
                        )

                        if (uiState.isRecording) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SoundLevelBars(rms = uiState.rmsLevel)
                                val mins = uiState.recordingElapsedSeconds / 60
                                val secs = uiState.recordingElapsedSeconds % 60
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
                            // Label mode quick switcher (Do-Re-Mi / C-D-E / Čisto)
                            NoteLabelModeRow(
                                selectedMode = settings.noteLabelMode,
                                onSelect = onLabelModeSelected
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Live Canvas Sheet Music Strip
                    LiveSheetStrip(
                        capturedNotes = uiState.liveCapturedNotes,
                        liveMidiNote = uiState.liveMidiNote,
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
                                text = "Malo preglasno za mikrofon — odmakni telefon korak od klavira za još čistije note.",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = WarmInk,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    if (!uiState.isRecording && uiState.liveCapturedNotes.isEmpty()) {
                        Text(
                            text = "Pritisni „Slušaj sada“ i odsviraj melodiju na klaviru, ili isprobaj dirke ispod!",
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
                        text = "Svetleća klavijatura",
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
                    startOctave = uiState.keyboardStartOctave,
                    labelMode = settings.noteLabelMode,
                    onKeyClick = onPianoKeyClick,
                    keyboardHeight = 114.dp
                )
            }

            // 5. Big Inviting "Slušaj sada" / "Završi snimanje" Primary Button
            Spacer(Modifier.height(4.dp))
            ListenNowButton(
                isRecording = uiState.isRecording,
                onClick = onListenToggle
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StudioHeaderRow(
    settings: UserSettings,
    isRecording: Boolean,
    onOpenTuneDialog: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_notolovac_icon_1795199040230),
                contentDescription = "Notolovac maskota",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.5.dp, WarmCardBorder, RoundedCornerShape(14.dp))
            )
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = WarmInk
                )
                Text(
                    text = if (isRecording) "Hvatam tvoje note..." else "Tvoja muzička sveska",
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
                    contentDescription = "Štimuj C",
                    tint = if (settings.isCalibrated) SageMintSecondary else WarmInk,
                    modifier = Modifier.size(18.dp)
                )
                Column {
                    Text(
                        text = if (settings.isCalibrated) "Štim sačuvan" else stringResource(R.string.action_tune_c),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (settings.isCalibrated) OnSageMintContainer else WarmInk
                    )
                    Text(
                        text = if (settings.isCalibrated) {
                            val hzStr = String.format(java.util.Locale.US, "%.1f Hz", settings.calibratedC4Hz)
                            "$hzStr ($sign$centsInt ct)"
                        } else {
                            "Standard 440 Hz"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (settings.isCalibrated) OnSageMintContainer else MutedInk
                    )
                }
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
                        contentDescription = if (isRunning) "Zaustavi metronom" else "Pokreni metronom",
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
                    text = "Metronom",
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
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("bpm_minus_button")
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = "Smanji tempo", tint = WarmInk)
                }
                Text(
                    text = "$bpm",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = WarmInk
                )
                IconButton(
                    onClick = { onBpmChange(bpm + 5) },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("bpm_plus_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Povećaj tempo", tint = WarmInk)
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
    }
}

@Composable
private fun LiveNoteBadge(
    liveMidiNote: Int?,
    isRecording: Boolean,
    capturedCount: Int
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = when {
                liveMidiNote != null -> HoneyGoldContainer
                isRecording -> CoralContainer
                else -> WarmSurface
            },
            border = BorderStroke(
                1.dp,
                when {
                    liveMidiNote != null -> HoneyGoldTertiary
                    isRecording -> CoralPrimary
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
                        isRecording -> "Slušam ($capturedCount)"
                        else -> "Notni list"
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
