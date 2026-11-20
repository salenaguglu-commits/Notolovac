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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
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
import com.example.ui.theme.CoralPrimary
import com.example.ui.theme.CreamPaper
import com.example.ui.theme.HoneyGoldTertiary
import com.example.ui.theme.MutedInk
import com.example.ui.theme.SageMintContainer
import com.example.ui.theme.SageMintSecondary
import com.example.ui.theme.WarmCardBorder
import com.example.ui.theme.WarmInk
import com.example.ui.theme.WarmSurface
import com.example.viewmodel.TuneDialogState
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun TuneCalibrationDialog(
    state: TuneDialogState,
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
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                        modifier = Modifier.testTag("tune_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Zatvori",
                            tint = MutedInk
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.tune_dialog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(16.dp))

                // Visual Tuning Dial Arc
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (state.completed) SageMintContainer else WarmSurface,
                    border = BorderStroke(1.dp, WarmCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                        ) {
                            val arcRadius = minOf(size.width * 0.42f, size.height * 0.85f)
                            val center = Offset(size.width / 2f, size.height - 10.dp.toPx())
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
                                style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                            )

                            // Sweet-spot center green zone
                            drawArc(
                                color = SageMintSecondary.copy(alpha = 0.65f),
                                startAngle = 258f,
                                sweepAngle = 24f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                            )

                            // Needle angle: -60ct -> 195 deg, 0ct -> 270 deg, +60ct -> 345 deg
                            val angleDeg = 270f + (animatedCents / 60f) * 75f
                            val angleRad = angleDeg * (PI / 180.0)
                            val tipX = center.x + (arcRadius - 8.dp.toPx()) * cos(angleRad).toFloat()
                            val tipY = center.y + (arcRadius - 8.dp.toPx()) * sin(angleRad).toFloat()

                            drawLine(
                                color = if (state.completed) SageMintSecondary else CoralPrimary,
                                start = center,
                                end = Offset(tipX, tipY),
                                strokeWidth = 4.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            drawCircle(
                                color = WarmInk,
                                radius = 7.dp.toPx(),
                                center = center
                            )
                        }

                        val centsInt = state.liveCents.roundToInt()
                        val sign = if (centsInt > 0) "+" else ""
                        val hzFormatted = String.format(Locale.US, "%.1f Hz", state.liveHz)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (state.completed) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = SageMintSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Text(
                                text = "C4 • $hzFormatted ($sign$centsInt ct)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = WarmInk
                            )
                        }

                        if (state.isListening) {
                            Spacer(Modifier.height(8.dp))
                            val micLevel = (kotlin.math.sqrt(state.liveRms.coerceAtLeast(0f)) * 2.8f).coerceIn(0.05f, 1f)
                            LinearProgressIndicator(
                                progress = { micLevel },
                                modifier = Modifier
                                    .fillMaxWidth(0.75f)
                                    .height(5.dp),
                                color = SageMintSecondary,
                                trackColor = WarmCardBorder.copy(alpha = 0.6f),
                                strokeCap = StrokeCap.Round
                            )
                        }

                        if (state.isListening || state.progress > 0f) {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = if (state.completed) SageMintSecondary else HoneyGoldTertiary,
                                trackColor = WarmCardBorder,
                                strokeCap = StrokeCap.Round
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = state.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = WarmInk,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Play Reference C4 button
                OutlinedButton(
                    onClick = onPlayReferenceC,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.5.dp, SageMintSecondary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("tune_play_reference_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.VolumeUp,
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

                Spacer(Modifier.height(10.dp))

                // Start/Stop listening for child's C note
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
                        text = if (state.isListening) "Zaustavi slušanje" else stringResource(R.string.tune_start_listening),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Spacer(Modifier.height(6.dp))

                TextButton(
                    onClick = onResetStandard,
                    modifier = Modifier.testTag("tune_reset_button")
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
