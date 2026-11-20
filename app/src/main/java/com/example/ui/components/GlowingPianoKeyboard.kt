package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.MusicTheory
import com.example.model.NoteLabelMode
import com.example.ui.theme.CoralPrimary
import com.example.ui.theme.HoneyGoldContainer
import com.example.ui.theme.HoneyGoldTertiary
import com.example.ui.theme.MutedInk
import com.example.ui.theme.NotePastelColors
import com.example.ui.theme.WarmCardBorder
import com.example.ui.theme.WarmInk
import com.example.ui.theme.WarmSurface

private val WHITE_KEY_OFFSETS = intArrayOf(0, 2, 4, 5, 7, 9, 11)
private val BLACK_KEY_SPECS = listOf(
    1 to 0.68f,   // C#
    3 to 1.72f,   // D#
    6 to 3.66f,   // F#
    8 to 4.70f,   // G#
    10 to 5.74f   // A#
)

/**
 * Tactile, kid-friendly glowing piano keyboard spanning 1.5 to 2 octaves (15 white keys: e.g. C4..C6).
 * Highlights the active MIDI note heard from the microphone or played back from a saved melody,
 * and allows tapping any key to audition real piano tones.
 */
@Composable
fun GlowingPianoKeyboard(
    activeMidiNote: Int?,
    startOctave: Int,
    labelMode: NoteLabelMode,
    onKeyClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    keyboardHeight: Dp = 112.dp,
    octaveSpan: Int = 2 // 2 full octaves + top C (15 white keys) or 1 octave + C (8 white keys)
) {
    val baseMidi = (startOctave + 1) * 12 // C4 = 60, C3 = 48
    val whiteKeyMidis = buildList {
        for (oct in 0 until octaveSpan) {
            WHITE_KEY_OFFSETS.forEach { offset -> add(baseMidi + oct * 12 + offset) }
        }
        add(baseMidi + octaveSpan * 12) // closing C at top
    }

    val blackKeys = buildList {
        for (oct in 0 until octaveSpan) {
            BLACK_KEY_SPECS.forEach { (semi, pos) ->
                add((baseMidi + oct * 12 + semi) to (oct * 7 + pos))
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("glowing_piano_keyboard"),
        shape = RoundedCornerShape(18.dp),
        color = WarmInk,
        border = BorderStroke(2.dp, WarmCardBorder),
        shadowElevation = 3.dp
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(keyboardHeight)
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            val whiteKeyWidth = maxWidth / whiteKeyMidis.size
            val blackKeyWidth = whiteKeyWidth * 0.64f
            val blackKeyHeight = keyboardHeight * 0.56f

            // Layer 1: White Keys
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(1.5.dp)
            ) {
                whiteKeyMidis.forEach { midi ->
                    val isActive = activeMidiNote == midi
                    val accent = NotePastelColors[MusicTheory.diatonicColorIndex(midi) % NotePastelColors.size]
                    val bgColor by animateColorAsState(
                        targetValue = if (isActive) accent else Color(0xFFFFFDF9),
                        animationSpec = spring(stiffness = 650f),
                        label = "whiteKeyColor"
                    )
                    val isMiddleC = midi == 60
                    val label = when (labelMode) {
                        NoteLabelMode.SOLFEGE -> MusicTheory.midiToSolfege(midi)
                        NoteLabelMode.LETTERS -> MusicTheory.midiToLetter(midi, includeOctave = midi % 12 == 0)
                        NoteLabelMode.NONE -> if (midi % 12 == 0) "C${midi / 12 - 1}" else ""
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp, topStart = 3.dp, topEnd = 3.dp))
                            .background(
                                brush = if (isActive) {
                                    Brush.verticalGradient(listOf(bgColor.copy(alpha = 0.85f), bgColor))
                                } else {
                                    Brush.verticalGradient(listOf(Color(0xFFFFFDF9), Color(0xFFF6EFE2)))
                                }
                            )
                            .border(
                                width = if (isActive) 1.5.dp else 0.6.dp,
                                color = if (isActive) WarmInk else WarmCardBorder,
                                shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp, topStart = 3.dp, topEnd = 3.dp)
                            )
                            .clickable { onKeyClick(midi) }
                            .testTag("piano_key_$midi"),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(bottom = 5.dp)
                        ) {
                            if (isMiddleC && !isActive) {
                                Box(
                                    modifier = Modifier
                                        .padding(bottom = 2.dp)
                                        .width(6.dp)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(CoralPrimary)
                                )
                            }
                            if (label.isNotEmpty()) {
                                Text(
                                    text = label,
                                    fontSize = if (whiteKeyMidis.size > 10) 9.sp else 11.sp,
                                    fontWeight = if (isActive || isMiddleC) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isActive) Color.White else if (isMiddleC) CoralPrimary else MutedInk,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // Layer 2: Black Keys
            blackKeys.forEach { (midi, slotPos) ->
                val isActive = activeMidiNote == midi
                val blackColor by animateColorAsState(
                    targetValue = if (isActive) HoneyGoldTertiary else Color(0xFF352C27),
                    animationSpec = spring(stiffness = 700f),
                    label = "blackKeyColor"
                )
                val xOffset = whiteKeyWidth * slotPos

                Box(
                    modifier = Modifier
                        .offset(x = xOffset)
                        .width(blackKeyWidth)
                        .height(blackKeyHeight)
                        .shadow(elevation = if (isActive) 6.dp else 3.dp, shape = RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                        .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                        .background(blackColor)
                        .border(
                            width = if (isActive) 1.5.dp else 0.8.dp,
                            color = if (isActive) HoneyGoldContainer else Color(0xFF1D1714),
                            shape = RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)
                        )
                        .clickable { onKeyClick(midi) }
                        .testTag("piano_key_$midi"),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (isActive) {
                        Text(
                            text = "#",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = WarmInk,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun OctaveSelectorRow(
    startOctave: Int,
    onSelectOctave: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Opseg dirki:",
            style = MaterialTheme.typography.bodySmall,
            color = MutedInk
        )
        listOf(3 to "C3 – C5", 4 to "C4 – C6").forEach { (oct, label) ->
            val selected = startOctave == oct
            Surface(
                onClick = { onSelectOctave(oct) },
                shape = RoundedCornerShape(10.dp),
                color = if (selected) HoneyGoldContainer else WarmSurface,
                border = BorderStroke(1.dp, if (selected) HoneyGoldTertiary else WarmCardBorder),
                modifier = Modifier.testTag("octave_btn_$oct")
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = WarmInk,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}
