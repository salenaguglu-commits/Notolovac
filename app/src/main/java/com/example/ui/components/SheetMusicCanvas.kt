package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DetectedNote
import com.example.model.MusicTheory
import com.example.model.NoteDuration
import com.example.model.NoteLabelMode
import com.example.ui.theme.CoralPrimary
import com.example.ui.theme.FredokaFontFamily
import com.example.ui.theme.HoneyGoldTertiary
import com.example.ui.theme.LavenderMusic
import com.example.ui.theme.MutedInk
import com.example.ui.theme.NotePastelColors
import com.example.ui.theme.NotebookRuleLine
import com.example.ui.theme.SageMintSecondary
import com.example.ui.theme.WarmInk
import kotlin.math.hypot

private data class DrawnNoteTarget(
    val index: Int,
    val center: Offset
)

/**
 * Live horizontal music staff strip displayed inside the Studio notebook page while listening.
 * Shows the last 8 captured notes plus a live glowing preview of the currently held piano tone.
 */
@Composable
fun LiveSheetStrip(
    capturedNotes: List<DetectedNote>,
    liveMidiNote: Int?,
    beatsPerMeasure: Int,
    labelMode: NoteLabelMode,
    modifier: Modifier = Modifier,
    liveMidiNotes: Set<Int> = emptySet(),
    maxSlots: Int = 8,
    fillAvailableHeight: Boolean = false
) {
    val textMeasurer = rememberTextMeasurer()
    val historyCapacity = (maxSlots - 1).coerceAtLeast(1)
    val visibleNotes = remember(capturedNotes, historyCapacity) {
        capturedNotes.takeLast(historyCapacity)
    }
    val activeChordMidis = remember(liveMidiNote, liveMidiNotes) {
        buildSet {
            if (liveMidiNote != null) add(liveMidiNote)
            addAll(liveMidiNotes)
        }.sorted()
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fillAvailableHeight) Modifier else Modifier.height(176.dp))
            .testTag("live_sheet_strip")
    ) {
        val leftPad = 16.dp.toPx()
        val rightPad = 16.dp.toPx()
        // Adapt staff geometry gracefully to compact landscape strips as well as portrait cards
        val lineSpacing = (size.height / 13.5f).coerceIn(7.5.dp.toPx(), 13.dp.toPx())
        val halfStep = lineSpacing / 2f
        val staffHeight = 4f * lineSpacing
        val staffTop = ((size.height - staffHeight) * 0.44f).coerceAtLeast(22.dp.toPx())
        val staffBottom = staffTop + staffHeight

        // Subtle notebook decorative faint top line
        drawLine(
            color = NotebookRuleLine.copy(alpha = 0.65f),
            start = Offset(0f, 10.dp.toPx()),
            end = Offset(size.width, 10.dp.toPx()),
            strokeWidth = 1.dp.toPx()
        )

        // 5 Staff lines
        for (i in 0..4) {
            val y = staffTop + i * lineSpacing
            drawLine(
                color = MutedInk.copy(alpha = 0.72f),
                start = Offset(leftPad, y),
                end = Offset(size.width - rightPad, y),
                strokeWidth = 1.4.dp.toPx()
            )
        }
        // Start bar line
        drawLine(
            color = WarmInk,
            start = Offset(leftPad, staffTop),
            end = Offset(leftPad, staffBottom),
            strokeWidth = 2.dp.toPx()
        )

        drawTrebleClefVector(
            cx = leftPad + 20.dp.toPx(),
            staffTop = staffTop,
            lineSpacing = lineSpacing,
            color = WarmInk
        )

        drawTimeSignature(
            textMeasurer = textMeasurer,
            beatsPerMeasure = beatsPerMeasure,
            cx = leftPad + 50.dp.toPx(),
            staffTop = staffTop,
            lineSpacing = lineSpacing
        )

        val startX = leftPad + 76.dp.toPx()
        val endX = size.width - rightPad - 14.dp.toPx()
        val totalSlots = maxSlots.coerceAtLeast(4)
        val slotWidth = ((endX - startX) / totalSlots).coerceAtLeast(26.dp.toPx())

        visibleNotes.forEachIndexed { idx, note ->
            if (!note.isRest) {
                val cx = startX + idx * slotWidth + slotWidth * 0.45f
                drawSingleNote(
                    textMeasurer = textMeasurer,
                    midiNote = note.midiNote,
                    duration = note.duration,
                    cx = cx,
                    staffBottom = staffBottom,
                    lineSpacing = lineSpacing,
                    halfStep = halfStep,
                    labelMode = labelMode,
                    highlightColor = null,
                    usePastelAccent = true
                )
            }
        }

        // Live glowing currently heard / pressed note(s) — supports full multi-touch chords!
        if (activeChordMidis.isNotEmpty()) {
            val liveSlotIndex = visibleNotes.size.coerceAtMost(totalSlots - 1)
            val cx = startX + liveSlotIndex * slotWidth + slotWidth * 0.45f
            activeChordMidis.forEachIndexed { chordIdx, midi ->
                drawSingleNote(
                    textMeasurer = textMeasurer,
                    midiNote = midi,
                    duration = NoteDuration.QUARTER,
                    cx = cx,
                    staffBottom = staffBottom,
                    lineSpacing = lineSpacing,
                    halfStep = halfStep,
                    // Avoid overlapping text labels when stacking 2+ simultaneous chord tones
                    labelMode = if (activeChordMidis.size > 1 && chordIdx > 0) NoteLabelMode.NONE else labelMode,
                    highlightColor = HoneyGoldTertiary,
                    usePastelAccent = true
                )
            }
        }
    }
}

/**
 * Full multi-line interactive sheet music canvas for SheetResultScreen.
 * Supports tapping any note to select & preview it, synchronized playback halo highlight,
 * measure bar lines, sharps, ledger lines, whole/half/quarter/eighth notes, and Do-Re-Mi / C-D-E labels.
 */
@Composable
fun FullSheetMusicCanvas(
    notes: List<DetectedNote>,
    beatsPerMeasure: Int,
    labelMode: NoteLabelMode,
    playingNoteIndex: Int?,
    selectedNoteIndex: Int?,
    onNoteTapped: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val notesPerRow = if (maxWidth >= 520.dp) 8 else 6
        val rowCount = maxOf(1, (notes.size + notesPerRow - 1) / notesPerRow)
        val rowHeightDp = 158.dp
        val totalHeightDp = (rowHeightDp * rowCount) + 28.dp

        val hitTargets = remember(notes, notesPerRow, maxWidth) { mutableListOf<DrawnNoteTarget>() }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(totalHeightDp)
                .testTag("full_sheet_music_canvas")
                .pointerInput(notes, notesPerRow) {
                    detectTapGestures { tapOffset ->
                        val nearest = hitTargets.minByOrNull {
                            hypot(it.center.x - tapOffset.x, it.center.y - tapOffset.y)
                        }
                        if (nearest != null) {
                            val dist = hypot(nearest.center.x - tapOffset.x, nearest.center.y - tapOffset.y)
                            if (dist <= 40.dp.toPx()) {
                                onNoteTapped(nearest.index)
                            }
                        }
                    }
                }
        ) {
            hitTargets.clear()
            val leftPad = 14.dp.toPx()
            val rightPad = 14.dp.toPx()
            val lineSpacing = 12.dp.toPx()
            val halfStep = lineSpacing / 2f
            val rowHeightPx = rowHeightDp.toPx()

            for (row in 0 until rowCount) {
                val staffTop = 38.dp.toPx() + row * rowHeightPx
                val staffBottom = staffTop + 4f * lineSpacing

                // 5 Staff lines
                for (i in 0..4) {
                    val y = staffTop + i * lineSpacing
                    drawLine(
                        color = MutedInk.copy(alpha = 0.72f),
                        start = Offset(leftPad, y),
                        end = Offset(size.width - rightPad, y),
                        strokeWidth = 1.4.dp.toPx()
                    )
                }

                // Start & End vertical bar lines
                drawLine(
                    color = WarmInk,
                    start = Offset(leftPad, staffTop),
                    end = Offset(leftPad, staffBottom),
                    strokeWidth = 2.dp.toPx()
                )
                drawLine(
                    color = WarmInk,
                    start = Offset(size.width - rightPad, staffTop),
                    end = Offset(size.width - rightPad, staffBottom),
                    strokeWidth = if (row == rowCount - 1) 3.5.dp.toPx() else 2.dp.toPx()
                )
                if (row == rowCount - 1) {
                    // Double closing barline at the end of the piece
                    drawLine(
                        color = WarmInk,
                        start = Offset(size.width - rightPad - 6.dp.toPx(), staffTop),
                        end = Offset(size.width - rightPad - 6.dp.toPx(), staffBottom),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }

                drawTrebleClefVector(
                    cx = leftPad + 20.dp.toPx(),
                    staffTop = staffTop,
                    lineSpacing = lineSpacing,
                    color = WarmInk
                )

                val contentStartX = if (row == 0) {
                    drawTimeSignature(
                        textMeasurer = textMeasurer,
                        beatsPerMeasure = beatsPerMeasure,
                        cx = leftPad + 50.dp.toPx(),
                        staffTop = staffTop,
                        lineSpacing = lineSpacing
                    )
                    leftPad + 76.dp.toPx()
                } else {
                    leftPad + 48.dp.toPx()
                }

                val availableW = (size.width - rightPad - contentStartX - 10.dp.toPx()).coerceAtLeast(100f)
                val slotW = availableW / notesPerRow.toFloat()

                val startGlobalIndex = row * notesPerRow
                val endGlobalIndex = minOf(notes.size, startGlobalIndex + notesPerRow)

                var beatsAccum = 0f
                for (globalIdx in startGlobalIndex until endGlobalIndex) {
                    val localIdx = globalIdx - startGlobalIndex
                    val note = notes[globalIdx]
                    val cx = contentStartX + localIdx * slotW + slotW * 0.46f

                    val highlight = when {
                        playingNoteIndex == globalIdx -> SageMintSecondary
                        selectedNoteIndex == globalIdx -> CoralPrimary
                        else -> null
                    }

                    if (note.isRest) {
                        val restCenterY = staffTop + 2f * lineSpacing
                        hitTargets.add(DrawnNoteTarget(globalIdx, Offset(cx, restCenterY)))
                        if (highlight != null) {
                            drawCircle(
                                color = highlight.copy(alpha = 0.24f),
                                radius = 18.dp.toPx(),
                                center = Offset(cx, restCenterY)
                            )
                        }
                        drawRestSymbol(cx, staffTop, lineSpacing, note.beats >= 2f)
                    } else {
                        val (displayMidi, _) = MusicTheory.staffDisplayMidi(note.midiNote)
                        val step = MusicTheory.midiToStaffStep(displayMidi)
                        val noteY = staffBottom - (step - 2) * halfStep
                        hitTargets.add(DrawnNoteTarget(globalIdx, Offset(cx, noteY)))
                        drawSingleNote(
                            textMeasurer = textMeasurer,
                            midiNote = note.midiNote,
                            duration = note.duration,
                            cx = cx,
                            staffBottom = staffBottom,
                            lineSpacing = lineSpacing,
                            halfStep = halfStep,
                            labelMode = labelMode,
                            highlightColor = highlight,
                            usePastelAccent = true
                        )
                    }

                    beatsAccum += note.beats
                    if (beatsAccum >= beatsPerMeasure - 0.01f) {
                        beatsAccum = 0f
                        if (globalIdx < endGlobalIndex - 1) {
                            val barX = cx + slotW * 0.50f
                            drawLine(
                                color = WarmInk.copy(alpha = 0.7f),
                                start = Offset(barX, staffTop),
                                end = Offset(barX, staffBottom),
                                strokeWidth = 1.6.dp.toPx()
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact decorative staff thumbnail for each saved song card in "Moja sveska".
 */
@Composable
fun MiniSheetPreview(
    notes: List<DetectedNote>,
    modifier: Modifier = Modifier
) {
    val previewNotes = remember(notes) { notes.filter { !it.isRest }.take(8) }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
    ) {
        val leftPad = 6.dp.toPx()
        val rightPad = 6.dp.toPx()
        val lineSpacing = 7.dp.toPx()
        val halfStep = lineSpacing / 2f
        val staffTop = 12.dp.toPx()
        val staffBottom = staffTop + 4f * lineSpacing

        for (i in 0..4) {
            val y = staffTop + i * lineSpacing
            drawLine(
                color = MutedInk.copy(alpha = 0.35f),
                start = Offset(leftPad, y),
                end = Offset(size.width - rightPad, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        if (previewNotes.isEmpty()) return@Canvas
        val stepX = (size.width - leftPad - rightPad - 20.dp.toPx()) / maxOf(previewNotes.size, 6)
        previewNotes.forEachIndexed { i, n ->
            val (displayMidi, _) = MusicTheory.staffDisplayMidi(n.midiNote)
            val step = MusicTheory.midiToStaffStep(displayMidi).coerceIn(-2, 12)
            val cx = leftPad + 16.dp.toPx() + i * stepX
            val cy = (staffBottom - (step - 2) * halfStep).coerceIn(6.dp.toPx(), size.height - 6.dp.toPx())
            val color = NotePastelColors[MusicTheory.diatonicColorIndex(n.midiNote) % NotePastelColors.size]
            drawCircle(color = color, radius = 4.2.dp.toPx(), center = Offset(cx, cy))
            drawLine(
                color = WarmInk.copy(alpha = 0.7f),
                start = Offset(cx + 3.6.dp.toPx(), cy),
                end = Offset(cx + 3.6.dp.toPx(), (cy - 14.dp.toPx()).coerceAtLeast(3.dp.toPx())),
                strokeWidth = 1.3.dp.toPx()
            )
        }
    }
}

private fun DrawScope.drawSingleNote(
    textMeasurer: TextMeasurer,
    midiNote: Int,
    duration: NoteDuration,
    cx: Float,
    staffBottom: Float,
    lineSpacing: Float,
    halfStep: Float,
    labelMode: NoteLabelMode,
    highlightColor: Color?,
    usePastelAccent: Boolean
) {
    val (displayMidi, octaveMarker) = MusicTheory.staffDisplayMidi(midiNote)
    val step = MusicTheory.midiToStaffStep(displayMidi)
    val noteY = staffBottom - (step - 2) * halfStep

    val noteColor = when {
        highlightColor != null -> highlightColor
        usePastelAccent -> WarmInk
        else -> WarmInk
    }
    val pastelBadgeColor = NotePastelColors[MusicTheory.diatonicColorIndex(midiNote) % NotePastelColors.size]

    // Glowing halo when active/selected
    if (highlightColor != null) {
        drawCircle(
            color = highlightColor.copy(alpha = 0.28f),
            radius = 18.dp.toPx(),
            center = Offset(cx, noteY)
        )
        drawCircle(
            color = highlightColor.copy(alpha = 0.16f),
            radius = 25.dp.toPx(),
            center = Offset(cx, noteY)
        )
    }

    // Ledger lines below E4 (step <= 0, even steps)
    if (step <= 0) {
        var s = 0
        while (s >= step) {
            val ly = staffBottom - (s - 2) * halfStep
            drawLine(
                color = MutedInk.copy(alpha = 0.85f),
                start = Offset(cx - 12.dp.toPx(), ly),
                end = Offset(cx + 12.dp.toPx(), ly),
                strokeWidth = 1.5.dp.toPx()
            )
            s -= 2
        }
    }

    // Ledger lines above F5 (step >= 12, even steps)
    if (step >= 12) {
        var s = 12
        while (s <= step) {
            val ly = staffBottom - (s - 2) * halfStep
            drawLine(
                color = MutedInk.copy(alpha = 0.85f),
                start = Offset(cx - 12.dp.toPx(), ly),
                end = Offset(cx + 12.dp.toPx(), ly),
                strokeWidth = 1.5.dp.toPx()
            )
            s += 2
        }
    }

    // Sharp symbol (#)
    if (MusicTheory.isSharp(midiNote)) {
        val sharpLayout = textMeasurer.measure(
            text = "#",
            style = TextStyle(
                fontFamily = FredokaFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = LavenderMusic
            )
        )
        drawText(
            textLayoutResult = sharpLayout,
            topLeft = Offset(cx - 18.dp.toPx(), noteY - sharpLayout.size.height / 2f)
        )
    }

    // Notehead (tilted oval)
    val rx = 7.4.dp.toPx()
    val ry = 5.3.dp.toPx()
    rotate(degrees = -18f, pivot = Offset(cx, noteY)) {
        if (duration == NoteDuration.WHOLE || duration == NoteDuration.HALF) {
            drawOval(
                color = Color(0xFFFFFBF2),
                topLeft = Offset(cx - rx, noteY - ry),
                size = Size(rx * 2f, ry * 2f)
            )
            drawOval(
                color = noteColor,
                topLeft = Offset(cx - rx, noteY - ry),
                size = Size(rx * 2f, ry * 2f),
                style = Stroke(width = 2.4.dp.toPx())
            )
        } else {
            drawOval(
                color = noteColor,
                topLeft = Offset(cx - rx, noteY - ry),
                size = Size(rx * 2f, ry * 2f)
            )
            // Subtle warm dot accent inside notehead for playful notebook polish
            drawCircle(
                color = pastelBadgeColor.copy(alpha = 0.45f),
                radius = 2.2.dp.toPx(),
                center = Offset(cx - 1.5.dp.toPx(), noteY - 1.dp.toPx())
            )
        }
    }

    // Stem + eighth (1 flag) / sixteenth (2 flags)
    if (duration != NoteDuration.WHOLE) {
        val stemUp = step < 6 // B4 and above -> stem goes down
        val stemLen = 3.1f * lineSpacing
        val flagCount = when (duration) {
            NoteDuration.SIXTEENTH -> 2
            NoteDuration.EIGHTH -> 1
            else -> 0
        }
        if (stemUp) {
            val sx = cx + rx - 1.dp.toPx()
            drawLine(
                color = noteColor,
                start = Offset(sx, noteY - 1.dp.toPx()),
                end = Offset(sx, noteY - stemLen),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            for (f in 0 until flagCount) {
                val fy = noteY - stemLen + f * 7.5.dp.toPx()
                val flag = Path().apply {
                    moveTo(sx, fy)
                    quadraticTo(
                        sx + 11.dp.toPx(),
                        fy + 6.5.dp.toPx(),
                        sx + 7.dp.toPx(),
                        fy + 16.5.dp.toPx()
                    )
                }
                drawPath(
                    path = flag,
                    color = noteColor,
                    style = Stroke(width = 2.3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        } else {
            val sx = cx - rx + 1.dp.toPx()
            drawLine(
                color = noteColor,
                start = Offset(sx, noteY + 1.dp.toPx()),
                end = Offset(sx, noteY + stemLen),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            for (f in 0 until flagCount) {
                val fy = noteY + stemLen - f * 7.5.dp.toPx()
                val flag = Path().apply {
                    moveTo(sx, fy)
                    quadraticTo(
                        sx + 11.dp.toPx(),
                        fy - 6.5.dp.toPx(),
                        sx + 7.dp.toPx(),
                        fy - 16.5.dp.toPx()
                    )
                }
                drawPath(
                    path = flag,
                    color = noteColor,
                    style = Stroke(width = 2.3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }

    // Standard 8va / 8vb octave shift indicator for extended keyboard ranges (C2..B2 & C#6..C7)
    if (octaveMarker != null) {
        val markerLayout = textMeasurer.measure(
            text = octaveMarker,
            style = TextStyle(
                fontFamily = FredokaFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = LavenderMusic
            )
        )
        val staffTop = staffBottom - 4f * lineSpacing
        val markerTopLeft = if (octaveMarker == "8va") {
            Offset(
                x = cx - markerLayout.size.width / 2f,
                y = (noteY - 19.dp.toPx()).coerceAtLeast(staffTop - 36.dp.toPx())
            )
        } else {
            Offset(
                x = cx + 9.dp.toPx(),
                y = (noteY - markerLayout.size.height / 2f + 2.dp.toPx())
                    .coerceAtMost(staffBottom + 50.dp.toPx())
            )
        }
        drawText(
            textLayoutResult = markerLayout,
            topLeft = markerTopLeft
        )
    }

    // Do-Re-Mi or C-D-E note name below the staff
    val label = MusicTheory.formatNoteLabel(midiNote, labelMode)
    if (label.isNotEmpty()) {
        val labelLayout = textMeasurer.measure(
            text = label,
            style = TextStyle(
                fontFamily = FredokaFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = highlightColor ?: pastelBadgeColor
            )
        )
        val labelTopY = maxOf(staffBottom + 20.dp.toPx(), noteY + 14.dp.toPx())
        drawText(
            textLayoutResult = labelLayout,
            topLeft = Offset(cx - labelLayout.size.width / 2f, labelTopY)
        )
    }
}

private fun DrawScope.drawRestSymbol(
    cx: Float,
    staffTop: Float,
    lineSpacing: Float,
    isHalfRest: Boolean
) {
    if (isHalfRest) {
        drawRect(
            color = WarmInk,
            topLeft = Offset(cx - 7.dp.toPx(), staffTop + 2f * lineSpacing - 5.dp.toPx()),
            size = Size(14.dp.toPx(), 5.dp.toPx())
        )
    } else {
        val path = Path().apply {
            moveTo(cx - 3.dp.toPx(), staffTop + 0.8f * lineSpacing)
            lineTo(cx + 4.dp.toPx(), staffTop + 1.7f * lineSpacing)
            lineTo(cx - 4.dp.toPx(), staffTop + 2.5f * lineSpacing)
            lineTo(cx + 3.dp.toPx(), staffTop + 3.4f * lineSpacing)
        }
        drawPath(
            path = path,
            color = WarmInk,
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

private fun DrawScope.drawTimeSignature(
    textMeasurer: TextMeasurer,
    beatsPerMeasure: Int,
    cx: Float,
    staffTop: Float,
    lineSpacing: Float
) {
    val style = TextStyle(
        fontFamily = FredokaFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        color = WarmInk
    )
    val topNum = textMeasurer.measure(beatsPerMeasure.toString(), style)
    val botNum = textMeasurer.measure("4", style)
    drawText(
        textLayoutResult = topNum,
        topLeft = Offset(cx - topNum.size.width / 2f, staffTop + lineSpacing - topNum.size.height / 2f)
    )
    drawText(
        textLayoutResult = botNum,
        topLeft = Offset(cx - botNum.size.width / 2f, staffTop + 3f * lineSpacing - botNum.size.height / 2f)
    )
}

private fun DrawScope.drawTrebleClefVector(
    cx: Float,
    staffTop: Float,
    lineSpacing: Float,
    color: Color
) {
    val gLineY = staffTop + 3f * lineSpacing
    val path = Path().apply {
        moveTo(cx + 2.dp.toPx(), gLineY + lineSpacing * 0.1f)
        cubicTo(
            cx - 7.dp.toPx(), gLineY - lineSpacing * 0.2f,
            cx - 7.dp.toPx(), gLineY - lineSpacing * 1.15f,
            cx + 2.dp.toPx(), gLineY - lineSpacing * 1.1f
        )
        cubicTo(
            cx + 12.dp.toPx(), gLineY - lineSpacing * 1.0f,
            cx + 12.dp.toPx(), gLineY + lineSpacing * 0.85f,
            cx - 2.dp.toPx(), gLineY + lineSpacing * 0.85f
        )
        cubicTo(
            cx - 13.dp.toPx(), gLineY + lineSpacing * 0.85f,
            cx - 13.dp.toPx(), gLineY - lineSpacing * 1.4f,
            cx + 4.dp.toPx(), staffTop - lineSpacing * 0.45f
        )
        cubicTo(
            cx + 10.dp.toPx(), staffTop - lineSpacing * 1.25f,
            cx - 1.dp.toPx(), staffTop - lineSpacing * 1.35f,
            cx - 2.dp.toPx(), staffTop - lineSpacing * 0.25f
        )
        lineTo(cx + 1.dp.toPx(), staffTop + 4.75f * lineSpacing)
        cubicTo(
            cx + 1.dp.toPx(), staffTop + 5.3f * lineSpacing,
            cx - 7.5.dp.toPx(), staffTop + 5.3f * lineSpacing,
            cx - 7.5.dp.toPx(), staffTop + 4.7f * lineSpacing
        )
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}
