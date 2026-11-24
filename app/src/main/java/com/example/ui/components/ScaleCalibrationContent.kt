package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.audio.PianoCalibrationProfile
import com.example.audio.ScaleCalibrationEngine
import com.example.audio.ScaleCalibrationPhase
import com.example.audio.ScaleCalibrationSnapshot
import com.example.audio.ScaleNoteMeasurement
import com.example.audio.ScaleStatusCode
import com.example.model.MusicTheory
import com.example.ui.theme.CoralContainer
import com.example.ui.theme.CoralPrimary
import com.example.ui.theme.HoneyGoldContainer
import com.example.ui.theme.HoneyGoldTertiary
import com.example.ui.theme.MutedInk
import com.example.ui.theme.OnCoralContainer
import com.example.ui.theme.OnSageMintContainer
import com.example.ui.theme.SageMintContainer
import com.example.ui.theme.SageMintSecondary
import com.example.ui.theme.WarmCard
import com.example.ui.theme.WarmCardBorder
import com.example.ui.theme.WarmInk
import com.example.ui.theme.WarmSurface
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun ScaleCalibrationContent(
    snapshot: ScaleCalibrationSnapshot,
    isListening: Boolean,
    savedProfile: PianoCalibrationProfile,
    onToggleListen: () -> Unit,
    onDismiss: () -> Unit
) {
    val isCompleted = snapshot.phase == ScaleCalibrationPhase.COMPLETED
    val isSilence = snapshot.phase == ScaleCalibrationPhase.SILENCE_CHECK
    val isMeasuring = snapshot.phase == ScaleCalibrationPhase.MEASURING_NOTE

    val cardBg = when {
        isCompleted -> SageMintContainer
        snapshot.isWrongNoteWarning -> CoralContainer.copy(alpha = 0.65f)
        isMeasuring -> WarmSurface
        isSilence -> HoneyGoldContainer.copy(alpha = 0.45f)
        else -> WarmSurface
    }
    val cardBorder = when {
        isCompleted -> SageMintSecondary
        snapshot.isWrongNoteWarning -> CoralPrimary
        isMeasuring -> HoneyGoldTertiary
        isSilence -> HoneyGoldTertiary
        else -> WarmCardBorder
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cardBg,
        border = BorderStroke(1.5.dp, cardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scale_calibration_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Live Status Pill
            ScaleLiveStatusPill(snapshot = snapshot, isListening = isListening)

            Spacer(Modifier.height(10.dp))

            // 8-Key Interactive Scale Stepper (2 rows of 4 keys: Do-Re-Mi-Fa / Sol-La-Si-Do)
            val completedByMidi = snapshot.completedNotes.associateBy { it.midi }
            val scaleNotes = ScaleCalibrationEngine.SCALE_NOTES

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (rowIdx in 0..1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (colIdx in 0..3) {
                            val idx = rowIdx * 4 + colIdx
                            val midi = scaleNotes[idx]
                            val measurement = completedByMidi[midi]
                            val isCurrentTarget = isMeasuring && snapshot.currentNoteIndex == idx
                            ScaleKeyTile(
                                midi = midi,
                                measurement = measurement,
                                isCurrentTarget = isCurrentTarget,
                                currentProgress = if (isCurrentTarget) snapshot.currentNoteProgress else 0f,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Silence Check Progress Bar
            if (isSilence) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.scale_silence_label),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = WarmInk
                    )
                    LinearProgressIndicator(
                        progress = { snapshot.silenceProgress },
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp),
                        color = HoneyGoldTertiary,
                        trackColor = WarmCardBorder,
                        strokeCap = StrokeCap.Round
                    )
                }
            }

            // Live Microphone Meter while listening
            if (isListening) {
                Spacer(Modifier.height(10.dp))
                val micLevel = (sqrt(snapshot.liveRms.coerceAtLeast(0f)) * 2.8f).coerceIn(0.05f, 1f)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tune_mic_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedInk
                    )
                    LinearProgressIndicator(
                        progress = { micLevel },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp),
                        color = SageMintSecondary,
                        trackColor = WarmCardBorder.copy(alpha = 0.6f),
                        strokeCap = StrokeCap.Round
                    )
                    Text(
                        text = "${snapshot.completedNotes.size}/8",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = WarmInk
                    )
                }
            }

            // Acoustic Profile Summary when Completed
            if (isCompleted && snapshot.resultingProfile != null) {
                Spacer(Modifier.height(10.dp))
                AcousticProfileSummaryBadges(profile = snapshot.resultingProfile)
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = localizedScaleStatusMessage(snapshot),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isCompleted || snapshot.isWrongNoteWarning) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    snapshot.isWrongNoteWarning -> OnCoralContainer
                    isCompleted -> OnSageMintContainer
                    else -> WarmInk
                },
                textAlign = TextAlign.Center
            )
        }
    }

    Spacer(Modifier.height(14.dp))

    // Action Buttons
    if (isCompleted) {
        Button(
            onClick = onDismiss,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SageMintSecondary,
                contentColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("scale_calibration_done_button")
        ) {
            Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.scale_cal_use_profile),
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onToggleListen,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, SageMintSecondary),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("scale_calibration_retry_button")
        ) {
            Icon(imageVector = Icons.Filled.Mic, contentDescription = null, tint = SageMintSecondary)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.scale_cal_retry),
                color = WarmInk,
                style = MaterialTheme.typography.labelLarge
            )
        }
    } else {
        Button(
            onClick = onToggleListen,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isListening) CoralPrimary else SageMintSecondary,
                contentColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("scale_calibration_start_button")
        ) {
            Icon(imageVector = Icons.Filled.Mic, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    isListening -> stringResource(R.string.scale_cal_stop)
                    savedProfile.isScaleCalibrated -> stringResource(R.string.scale_cal_retry)
                    else -> stringResource(R.string.scale_cal_start)
                },
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun ScaleLiveStatusPill(
    snapshot: ScaleCalibrationSnapshot,
    isListening: Boolean
) {
    val targetDesc = MusicTheory.formatFullNoteDescription(snapshot.currentTargetMidi)
    val (bg, fg, icon, label) = when {
        snapshot.phase == ScaleCalibrationPhase.COMPLETED -> ScalePillVisual(
            SageMintSecondary,
            Color.White,
            Icons.Filled.CheckCircle,
            stringResource(R.string.scale_pill_saved)
        )
        snapshot.isWrongNoteWarning -> ScalePillVisual(
            CoralPrimary,
            Color.White,
            Icons.Filled.ErrorOutline,
            stringResource(R.string.scale_pill_wrong_note, snapshot.heardNoteName ?: "?", targetDesc)
        )
        snapshot.phase == ScaleCalibrationPhase.SILENCE_CHECK -> ScalePillVisual(
            HoneyGoldTertiary,
            WarmInk,
            Icons.Filled.Mic,
            stringResource(R.string.scale_pill_silence)
        )
        snapshot.phase == ScaleCalibrationPhase.MEASURING_NOTE -> ScalePillVisual(
            HoneyGoldTertiary,
            WarmInk,
            Icons.Filled.MusicNote,
            stringResource(R.string.scale_pill_play_note, targetDesc)
        )
        isListening -> ScalePillVisual(
            WarmCard,
            WarmInk,
            Icons.Filled.Mic,
            stringResource(R.string.scale_pill_listening)
        )
        else -> ScalePillVisual(
            WarmCard,
            MutedInk,
            Icons.Filled.Piano,
            stringResource(R.string.scale_pill_ready)
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        modifier = Modifier.testTag("scale_live_status_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = fg
            )
        }
    }
}

@Composable
private fun ScaleKeyTile(
    midi: Int,
    measurement: ScaleNoteMeasurement?,
    isCurrentTarget: Boolean,
    currentProgress: Float,
    modifier: Modifier = Modifier
) {
    val isDone = measurement != null
    val bgColor by animateColorAsState(
        targetValue = when {
            isDone -> SageMintSecondary
            isCurrentTarget -> HoneyGoldContainer
            else -> WarmCard
        },
        animationSpec = spring(stiffness = 380f),
        label = "scaleTileBg"
    )
    val borderColor = when {
        isDone -> SageMintSecondary
        isCurrentTarget -> HoneyGoldTertiary
        else -> WarmCardBorder
    }

    val solfege = MusicTheory.midiToSolfege(midi)
    val letter = MusicTheory.midiToLetter(midi, includeOctave = true)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = BorderStroke(if (isCurrentTarget) 2.dp else 1.dp, borderColor),
        shadowElevation = if (isCurrentTarget) 3.dp else 0.dp,
        modifier = modifier
            .height(64.dp)
            .testTag("scale_key_tile_$midi")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (isDone) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Text(
                    text = solfege,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isDone) Color.White else WarmInk
                )
            }

            if (measurement != null) {
                val ct = measurement.measuredCents.roundToInt()
                val sign = if (ct > 0) "+" else ""
                Text(
                    text = "$sign${ct}ct",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.92f)
                )
            } else if (isCurrentTarget) {
                LinearProgressIndicator(
                    progress = { currentProgress.coerceAtLeast(0.08f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .height(5.dp),
                    color = CoralPrimary,
                    trackColor = WarmCardBorder,
                    strokeCap = StrokeCap.Round
                )
            } else {
                Text(
                    text = letter,
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedInk
                )
            }
        }
    }
}

@Composable
private fun AcousticProfileSummaryBadges(profile: PianoCalibrationProfile) {
    val noiseLabel = when {
        profile.ambientNoiseRms < 0.004f -> stringResource(R.string.scale_room_quiet)
        profile.ambientNoiseRms < 0.009f -> stringResource(R.string.scale_room_moderate)
        else -> stringResource(R.string.scale_room_noisy)
    }
    val stretchInt = profile.octaveStretchCentsPerOctave.roundToInt()
    val stretchSign = if (stretchInt > 0) "+" else ""

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ProfileChip(
                label = stringResource(R.string.scale_chip_room, noiseLabel),
                modifier = Modifier.weight(1f)
            )
            ProfileChip(
                label = stringResource(R.string.scale_chip_octave, stretchSign, stretchInt),
                modifier = Modifier.weight(1f)
            )
        }
        if (profile.clippingWarning) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = HoneyGoldContainer,
                border = BorderStroke(1.dp, HoneyGoldTertiary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = CoralPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.scale_clipping_warning),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = WarmInk
                    )
                }
            }
        }
    }
}

@Composable
private fun localizedScaleStatusMessage(snapshot: ScaleCalibrationSnapshot): String {
    val primary = snapshot.statusPrimaryNote ?: MusicTheory.formatFullNoteDescription(snapshot.currentTargetMidi)
    val secondary = snapshot.statusSecondaryNote ?: ""
    val hzStr = String.format(Locale.US, "%.1f Hz", snapshot.heardRawHz)
    val sign = if (snapshot.statusCents > 0) "+" else ""
    return when (snapshot.statusCode) {
        ScaleStatusCode.IDLE -> stringResource(R.string.scale_status_idle)
        ScaleStatusCode.SILENCE_START -> stringResource(R.string.scale_status_silence_start)
        ScaleStatusCode.SILENCE_LOUD -> stringResource(R.string.scale_status_silence_loud)
        ScaleStatusCode.SILENCE_MEASURING -> stringResource(R.string.scale_status_silence_measuring)
        ScaleStatusCode.SILENCE_DONE -> stringResource(R.string.scale_status_silence_done, primary)
        ScaleStatusCode.PRESS_KEY -> stringResource(R.string.scale_status_press_key, primary)
        ScaleStatusCode.HOLD_KEY -> stringResource(
            R.string.scale_status_hold_key,
            primary,
            snapshot.currentNoteLockedSamples,
            snapshot.requiredSamplesPerNote
        )
        ScaleStatusCode.NOTE_DONE -> stringResource(R.string.scale_status_note_done, primary, secondary)
        ScaleStatusCode.LOCKING_NOTE -> stringResource(
            R.string.scale_status_locking_note,
            primary,
            hzStr,
            sign,
            snapshot.statusCents,
            snapshot.currentNoteLockedSamples,
            snapshot.requiredSamplesPerNote
        )
        ScaleStatusCode.NEXT_KEY -> stringResource(R.string.scale_status_next_key, primary)
        ScaleStatusCode.WRONG_NOTE -> stringResource(R.string.scale_status_wrong_note, primary, hzStr, secondary)
        ScaleStatusCode.COMPLETED -> {
            val profile = snapshot.resultingProfile ?: PianoCalibrationProfile.STANDARD
            val ct = profile.globalCents.roundToInt()
            val cSign = if (ct > 0) "+" else ""
            val c4Str = String.format(Locale.US, "%.1f Hz", profile.calibratedC4Hz)
            val a4Str = String.format(Locale.US, "%.1f Hz", profile.equivalentA4Hz)
            val base = stringResource(R.string.scale_status_completed, c4Str, cSign, ct, a4Str)
            if (profile.clippingWarning) {
                base + stringResource(R.string.scale_status_completed_clip_tip)
            } else {
                base
            }
        }
        ScaleStatusCode.MIC_UNAVAILABLE -> stringResource(R.string.error_mic_unavailable)
    }
}

@Composable
private fun ProfileChip(
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.White.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, SageMintSecondary.copy(alpha = 0.4f)),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = OnSageMintContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

private data class ScalePillVisual<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
