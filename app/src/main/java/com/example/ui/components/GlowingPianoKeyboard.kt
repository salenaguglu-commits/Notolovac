package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
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
 * Pure geometric hit-tester for the 2-layer piano keyboard (black keys take priority over white keys
 * in the upper 56% region). Extracted for exact multi-touch & glissando accuracy and unit testing.
 */
internal object PianoKeyboardHitTest {
    const val BLACK_KEY_WIDTH_RATIO = 0.64f
    const val BLACK_KEY_HEIGHT_RATIO = 0.56f

    fun whiteKeyMidis(startOctave: Int, octaveSpan: Int): List<Int> {
        val baseMidi = (startOctave + 1) * 12
        return buildList {
            for (oct in 0 until octaveSpan) {
                WHITE_KEY_OFFSETS.forEach { offset -> add(baseMidi + oct * 12 + offset) }
            }
            add(baseMidi + octaveSpan * 12)
        }
    }

    fun blackKeys(startOctave: Int, octaveSpan: Int): List<Pair<Int, Float>> {
        val baseMidi = (startOctave + 1) * 12
        return buildList {
            for (oct in 0 until octaveSpan) {
                BLACK_KEY_SPECS.forEach { (semi, pos) ->
                    add((baseMidi + oct * 12 + semi) to (oct * 7 + pos))
                }
            }
        }
    }

    fun hitTest(
        x: Float,
        y: Float,
        widthPx: Float,
        heightPx: Float,
        whiteKeyMidis: List<Int>,
        blackKeys: List<Pair<Int, Float>>
    ): Int? {
        if (widthPx <= 0f || heightPx <= 0f || whiteKeyMidis.isEmpty()) return null
        if (x < 0f || x > widthPx || y < 0f || y > heightPx) return null

        val whiteKeyWidth = widthPx / whiteKeyMidis.size
        val blackKeyWidth = whiteKeyWidth * BLACK_KEY_WIDTH_RATIO
        val blackKeyHeight = heightPx * BLACK_KEY_HEIGHT_RATIO

        // Priority 1: Black keys in upper region
        if (y <= blackKeyHeight) {
            for ((midi, slotPos) in blackKeys) {
                val left = whiteKeyWidth * slotPos
                val right = left + blackKeyWidth
                if (x in left..right) {
                    return midi
                }
            }
        }

        // Priority 2: White keys across full height
        val whiteIdx = (x / whiteKeyWidth).toInt().coerceIn(0, whiteKeyMidis.lastIndex)
        return whiteKeyMidis[whiteIdx]
    }
}

/**
 * Tactile, kid-friendly glowing piano keyboard spanning 1 to 2 octaves (default 15 white keys: C4..C6).
 *
 * Supports:
 * - True multi-touch polyphony (press multiple fingers at once to play chords / two-hand intervals)
 * - Smooth glissando swiping (slide fingers smoothly across white & black keys without parent scroll stealing the gesture)
 * - Synchronized visual glow for live microphone pitch, melody playback, and all simultaneously touched keys
 */
@Composable
fun GlowingPianoKeyboard(
    activeMidiNote: Int?,
    startOctave: Int,
    labelMode: NoteLabelMode,
    onKeyClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    activeMidiNotes: Set<Int> = emptySet(),
    onKeyRelease: (Int) -> Unit = {},
    keyboardHeight: Dp? = 112.dp,
    octaveSpan: Int = 2 // 2 full octaves + top C (15 white keys) or 1 octave + C (8 white keys)
) {
    val whiteKeyMidis = remember(startOctave, octaveSpan) {
        PianoKeyboardHitTest.whiteKeyMidis(startOctave, octaveSpan)
    }
    val blackKeys = remember(startOctave, octaveSpan) {
        PianoKeyboardHitTest.blackKeys(startOctave, octaveSpan)
    }

    val currentOnKeyPress by rememberUpdatedState(onKeyClick)
    val currentOnKeyRelease by rememberUpdatedState(onKeyRelease)
    var touchedMidis by remember(startOctave, octaveSpan) { mutableStateOf(emptySet<Int>()) }

    // Ensure no voices stay stuck if octave changes or keyboard leaves composition
    DisposableEffect(startOctave, octaveSpan) {
        onDispose {
            val held = touchedMidis
            touchedMidis = emptySet()
            held.forEach { midi -> currentOnKeyRelease(midi) }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (keyboardHeight != null) Modifier.height(keyboardHeight + 8.dp) else Modifier.fillMaxHeight())
            .testTag("glowing_piano_keyboard"),
        shape = RoundedCornerShape(18.dp),
        color = WarmInk,
        border = BorderStroke(2.dp, WarmCardBorder),
        shadowElevation = 3.dp
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .pointerInput(whiteKeyMidis, blackKeys) {
                    val pointerToMidi = mutableMapOf<PointerId, Int>()
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            var changed = false

                            for (change in event.changes) {
                                if (change.pressed) {
                                    // Prevent outer verticalScroll from stealing glissando swipes or chords
                                    change.consume()
                                    val newMidi = PianoKeyboardHitTest.hitTest(
                                        x = change.position.x,
                                        y = change.position.y,
                                        widthPx = size.width.toFloat(),
                                        heightPx = size.height.toFloat(),
                                        whiteKeyMidis = whiteKeyMidis,
                                        blackKeys = blackKeys
                                    )
                                    val oldMidi = pointerToMidi[change.id]
                                    if (newMidi != oldMidi) {
                                        if (newMidi != null) {
                                            pointerToMidi[change.id] = newMidi
                                        } else {
                                            pointerToMidi.remove(change.id)
                                        }
                                        if (oldMidi != null && !pointerToMidi.containsValue(oldMidi)) {
                                            currentOnKeyRelease(oldMidi)
                                        }
                                        if (newMidi != null) {
                                            currentOnKeyPress(newMidi)
                                        }
                                        changed = true
                                    }
                                } else {
                                    val oldMidi = pointerToMidi.remove(change.id)
                                    if (oldMidi != null) {
                                        if (!pointerToMidi.containsValue(oldMidi)) {
                                            currentOnKeyRelease(oldMidi)
                                        }
                                        changed = true
                                    }
                                }
                            }

                            if (changed) {
                                touchedMidis = pointerToMidi.values.toSet()
                            }
                        }
                    }
                }
        ) {
            val whiteKeyWidth = maxWidth / whiteKeyMidis.size
            val blackKeyWidth = whiteKeyWidth * PianoKeyboardHitTest.BLACK_KEY_WIDTH_RATIO
            val blackKeyHeight = maxHeight * PianoKeyboardHitTest.BLACK_KEY_HEIGHT_RATIO
            val isWideKeyLayout = whiteKeyWidth >= 34.dp

            // Layer 1: White Keys
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(1.5.dp)
            ) {
                whiteKeyMidis.forEach { midi ->
                    val isTouched = midi in touchedMidis
                    val isActive = isTouched || activeMidiNote == midi || midi in activeMidiNotes
                    val accent = NotePastelColors[MusicTheory.diatonicColorIndex(midi) % NotePastelColors.size]
                    val bgColor by animateColorAsState(
                        targetValue = if (isActive) accent else Color(0xFFFFFDF9),
                        animationSpec = spring(stiffness = 750f),
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
                            .offset(y = if (isTouched) 1.5.dp else 0.dp)
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
                                    fontSize = when {
                                        isWideKeyLayout -> 13.sp
                                        whiteKeyMidis.size > 10 -> 9.sp
                                        else -> 11.sp
                                    },
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
                val isTouched = midi in touchedMidis
                val isActive = isTouched || activeMidiNote == midi || midi in activeMidiNotes
                val blackColor by animateColorAsState(
                    targetValue = if (isActive) HoneyGoldTertiary else Color(0xFF352C27),
                    animationSpec = spring(stiffness = 800f),
                    label = "blackKeyColor"
                )
                val xOffset = whiteKeyWidth * slotPos

                Box(
                    modifier = Modifier
                        .offset(x = xOffset, y = if (isTouched) 1.dp else 0.dp)
                        .width(blackKeyWidth)
                        .height(blackKeyHeight)
                        .shadow(
                            elevation = if (isActive) 6.dp else 3.dp,
                            shape = RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)
                        )
                        .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                        .background(blackColor)
                        .border(
                            width = if (isActive) 1.5.dp else 0.8.dp,
                            color = if (isActive) HoneyGoldContainer else Color(0xFF1D1714),
                            shape = RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)
                        )
                        .testTag("piano_key_$midi"),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (isActive) {
                        Text(
                            text = "#",
                            fontSize = if (isWideKeyLayout) 11.sp else 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = WarmInk,
                            modifier = Modifier.padding(bottom = 5.dp)
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
            text = stringResource(R.string.keyboard_octave_range),
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
