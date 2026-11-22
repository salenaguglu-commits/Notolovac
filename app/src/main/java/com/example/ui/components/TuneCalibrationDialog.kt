package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.model.MusicTheory
import com.example.ui.theme.CoralContainer
import com.example.ui.theme.CoralPrimary
import com.example.ui.theme.CreamPaper
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
import com.example.viewmodel.CalibrationMode
import com.example.viewmodel.TuneDialogState
import com.example.viewmodel.TunePhase
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun TuneCalibrationDialog(
    state: TuneDialogState,
    onSelectMode: (CalibrationMode) -> Unit,
    onPlayReferenceC: () -> Unit,
    onToggleListen: () -> Unit,
    onResetStandard: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!state.isOpen) return

    val animatedCents by animateFloatAsState(
        targetValue = state.liveCents.coerceIn(-60f, 60f),
        animationSpec = spring(stiffness = 280f),
        label = "tuneNeedle"
    )

    val isWarningPhase = state.phase == TunePhase.WRONG_NOTE || state.phase == TunePhase.RETRY_UNSTABLE
    val isSavedPhase = state.phase == TunePhase.SAVED

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = CreamPaper,
            border = BorderStroke(2.dp, WarmCardBorder),
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("tune_calibration_dialog")
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = SageMintSecondary,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.tune_dialog_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = WarmInk
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("tune_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Zatvori",
                            tint = MutedInk
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.tune_dialog_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedInk,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                // 1. Unmistakable Active Saved Calibration Proof Card
                SavedCalibrationStatusCard(state = state)

                Spacer(Modifier.height(10.dp))

                // 1b. Mode Switcher: Full Scale (Do-Do) vs Quick C
                CalibrationModeSwitcher(
                    selectedMode = state.mode,
                    onSelectMode = onSelectMode
                )

                Spacer(Modifier.height(12.dp))

                if (state.mode == CalibrationMode.FULL_SCALE) {
                    ScaleCalibrationContent(
                        snapshot = state.scaleSnapshot,
                        isListening = state.isListening,
                        savedProfile = state.savedProfile,
                        onToggleListen = onToggleListen,
                        onDismiss = onDismiss
                    )
                } else {

                // 2. Live Measurement Dial + Heard Note/Hz/Cents Feedback Card
                val dialBg = when {
                    isSavedPhase -> SageMintContainer
                    isWarningPhase -> CoralContainer.copy(alpha = 0.65f)
                    state.phase == TunePhase.LOCKING_C -> HoneyGoldContainer.copy(alpha = 0.55f)
                    else -> WarmSurface
                }
                val dialBorder = when {
                    isSavedPhase -> SageMintSecondary
                    isWarningPhase -> CoralPrimary
                    state.phase == TunePhase.LOCKING_C -> HoneyGoldTertiary
                    else -> WarmCardBorder
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = dialBg,
                    border = BorderStroke(1.5.dp, dialBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Live "What microphone hears RIGHT NOW" pill
                        LiveHeardNotePill(state = state)

                        Spacer(Modifier.height(6.dp))

                        // Visual Tuning Dial Arc (-60 ct ... 0 ct ... +60 ct)
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(98.dp)
                        ) {
                            val arcRadius = minOf(size.width * 0.40f, size.height * 0.84f)
                            val center = Offset(size.width / 2f, size.height - 8.dp.toPx())
                            val topLeft = Offset(center.x - arcRadius, center.y - arcRadius)
                            val arcSize = Size(arcRadius * 2f, arcRadius * 2f)

                            // Background arc
                            drawArc(
                                color = WarmCardBorder,
                                startAngle = 195f,
                                sweepAngle = 150f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round)
                            )

                            // Standard C4 center zone (0 ct)
                            drawArc(
                                color = SageMintSecondary.copy(alpha = 0.65f),
                                startAngle = 258f,
                                sweepAngle = 24f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round)
                            )

                            // Needle angle: -60ct -> 195 deg, 0ct -> 270 deg, +60ct -> 345 deg
                            val angleDeg = 270f + (animatedCents / 60f) * 75f
                            val angleRad = angleDeg * (PI / 180.0)
                            val tipX = center.x + (arcRadius - 8.dp.toPx()) * cos(angleRad).toFloat()
                            val tipY = center.y + (arcRadius - 8.dp.toPx()) * sin(angleRad).toFloat()

                            drawLine(
                                color = when {
                                    isSavedPhase -> SageMintSecondary
                                    isWarningPhase -> CoralPrimary
                                    else -> CoralPrimary
                                },
                                start = center,
                                end = Offset(tipX, tipY),
                                strokeWidth = 4.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            drawCircle(
                                color = WarmInk,
                                radius = 6.dp.toPx(),
                                center = center
                            )
                        }

                        // Scale labels beneath arc
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("-60 ct", style = MaterialTheme.typography.labelSmall, color = MutedInk)
                            Text("0 (261.6 Hz)", style = MaterialTheme.typography.labelSmall, color = SageMintSecondary, fontWeight = FontWeight.Bold)
                            Text("+60 ct", style = MaterialTheme.typography.labelSmall, color = MutedInk)
                        }

                        Spacer(Modifier.height(8.dp))

                        // Exact numeric Hz & +/- cents readout
                        val centsInt = state.liveCents.roundToInt()
                        val sign = if (centsInt > 0) "+" else ""
                        val hzDiff = state.liveHz - MusicTheory.STANDARD_C4_HZ
                        val hzDiffSign = if (hzDiff > 0.05) "+" else ""
                        val hzFormatted = String.format(Locale.US, "%.1f Hz", state.liveHz)
                        val hzDiffFormatted = String.format(Locale.US, "%s%.1f Hz", hzDiffSign, hzDiff)

                        Text(
                            text = "C4: $hzFormatted  •  $sign$centsInt ct ($hzDiffFormatted)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = WarmInk,
                            textAlign = TextAlign.Center
                        )

                        // Live microphone input level bar while listening
                        if (state.isListening) {
                            Spacer(Modifier.height(8.dp))
                            val micLevel = (kotlin.math.sqrt(state.liveRms.coerceAtLeast(0f)) * 2.8f).coerceIn(0.05f, 1f)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Mikrofon",
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
                            }
                        }

                        // Sample lock progress bar (X / 7)
                        if (state.isListening || state.phase == TunePhase.SAVED) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Potvrda ${state.lockedSamples}/${state.requiredSamples}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSavedPhase) OnSageMintContainer else MutedInk
                                )
                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(8.dp),
                                    color = if (isSavedPhase) SageMintSecondary else HoneyGoldTertiary,
                                    trackColor = WarmCardBorder,
                                    strokeCap = StrokeCap.Round
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Honest, actionable status / retry message
                        Text(
                            text = state.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isWarningPhase || isSavedPhase) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                isWarningPhase -> OnCoralContainer
                                isSavedPhase -> OnSageMintContainer
                                else -> WarmInk
                            },
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 3. Action Buttons
                if (isSavedPhase) {
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
                            .testTag("tune_done_button")
                    ) {
                        Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Gotovo — koristi ovaj štim", style = MaterialTheme.typography.labelLarge)
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onToggleListen,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, SageMintSecondary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("tune_listen_button")
                    ) {
                        Icon(imageVector = Icons.Filled.Mic, contentDescription = null, tint = SageMintSecondary)
                        Spacer(Modifier.width(8.dp))
                        Text("Izmeri C ponovo", color = WarmInk, style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Button(
                        onClick = onToggleListen,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isListening) CoralPrimary else SageMintSecondary,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("tune_listen_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (state.isListening) {
                                "Zaustavi slušanje"
                            } else if (state.savedIsCalibrated) {
                                "Izmeri moje C ponovo"
                            } else {
                                stringResource(R.string.tune_start_listening)
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onPlayReferenceC,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, WarmCardBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("tune_play_reference_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = SageMintSecondary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.tune_play_reference),
                            color = WarmInk,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                }

                Spacer(Modifier.height(4.dp))

                TextButton(
                    onClick = onResetStandard,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("tune_reset_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MutedInk,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.tune_reset_standard),
                        color = MutedInk,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedCalibrationStatusCard(state: TuneDialogState) {
    val savedCentsInt = state.savedCents.roundToInt()
    val sign = if (savedCentsInt > 0) "+" else ""
    val c4Text = String.format(Locale.US, "%.1f Hz", state.savedC4Hz)
    val a4Text = String.format(Locale.US, "%.1f Hz", state.savedA4Hz)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (state.savedIsCalibrated) SageMintContainer else WarmCard,
        border = BorderStroke(
            width = 1.5.dp,
            color = if (state.savedIsCalibrated) SageMintSecondary else WarmCardBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("saved_calibration_status_card")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (state.savedIsCalibrated) Icons.Filled.CheckCircle else Icons.Filled.Tune,
                contentDescription = null,
                tint = if (state.savedIsCalibrated) SageMintSecondary else MutedInk,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        state.savedProfile.isScaleCalibrated -> "Sačuvan profil tvog klavira (Skala Do–Do)"
                        state.savedIsCalibrated -> "Sačuvan štim tvog klavira (Brzi C)"
                        else -> "Aktivan standardni štim (nije kalibrisano)"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (state.savedIsCalibrated) OnSageMintContainer else WarmInk
                )
                Text(
                    text = "C4 = $c4Text ($sign$savedCentsInt ct)  •  A4 = $a4Text",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.savedIsCalibrated) OnSageMintContainer else MutedInk
                )
                if (state.savedProfile.isScaleCalibrated) {
                    val stretchCt = state.savedProfile.octaveStretchCentsPerOctave.roundToInt()
                    val stretchSign = if (stretchCt > 0) "+" else ""
                    Text(
                        text = "Soba i dinamika kalibrisani • Razvučenost oktave: $stretchSign${stretchCt} ct",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSageMintContainer.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CalibrationModeSwitcher(
    selectedMode: CalibrationMode,
    onSelectMode: (CalibrationMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val fullSelected = selectedMode == CalibrationMode.FULL_SCALE
        Surface(
            onClick = { onSelectMode(CalibrationMode.FULL_SCALE) },
            shape = RoundedCornerShape(14.dp),
            color = if (fullSelected) HoneyGoldContainer else WarmCard,
            border = BorderStroke(
                width = if (fullSelected) 2.dp else 1.dp,
                color = if (fullSelected) HoneyGoldTertiary else WarmCardBorder
            ),
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("mode_full_scale_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Piano,
                    contentDescription = null,
                    tint = if (fullSelected) CoralPrimary else MutedInk,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.tune_mode_full_scale),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (fullSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (fullSelected) WarmInk else MutedInk
                )
            }
        }

        val quickSelected = selectedMode == CalibrationMode.QUICK_C
        Surface(
            onClick = { onSelectMode(CalibrationMode.QUICK_C) },
            shape = RoundedCornerShape(14.dp),
            color = if (quickSelected) HoneyGoldContainer else WarmCard,
            border = BorderStroke(
                width = if (quickSelected) 2.dp else 1.dp,
                color = if (quickSelected) HoneyGoldTertiary else WarmCardBorder
            ),
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("mode_quick_c_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    tint = if (quickSelected) SageMintSecondary else MutedInk,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.tune_mode_quick_c),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (quickSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (quickSelected) WarmInk else MutedInk
                )
            }
        }
    }
}

@Composable
private fun LiveHeardNotePill(state: TuneDialogState) {
    val rawHzText = if (state.heardRawHz > 0.0) {
        String.format(Locale.US, "%.1f Hz", state.heardRawHz)
    } else {
        null
    }

    val (bgColor, textColor, icon, text) = when (state.phase) {
        TunePhase.SAVED -> Quad(
            SageMintSecondary,
            Color.White,
            Icons.Filled.CheckCircle,
            "Sačuvano: ${state.heardNoteName ?: "Do (C4)"}"
        )
        TunePhase.WRONG_NOTE -> Quad(
            CoralPrimary,
            Color.White,
            Icons.Filled.ErrorOutline,
            "Čuo sam: ${state.heardNoteName ?: "?"} (${rawHzText ?: ""}) — nije C!"
        )
        TunePhase.RETRY_UNSTABLE -> Quad(
            CoralPrimary,
            Color.White,
            Icons.Filled.ErrorOutline,
            "Pritisni C opet (${state.heardNoteName ?: "nestabilan ton"})"
        )
        TunePhase.LOCKING_C -> Quad(
            HoneyGoldTertiary,
            WarmInk,
            Icons.Filled.MusicNote,
            "Čujem: ${state.heardNoteName ?: "C"} (${rawHzText ?: ""})"
        )
        TunePhase.LISTENING -> Quad(
            WarmCard,
            MutedInk,
            Icons.Filled.Mic,
            "Čekam zvuk klavira..."
        )
        TunePhase.IDLE -> Quad(
            WarmCard,
            MutedInk,
            Icons.Filled.Tune,
            "Spremno za merenje C"
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        modifier = Modifier.testTag("tune_live_heard_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
