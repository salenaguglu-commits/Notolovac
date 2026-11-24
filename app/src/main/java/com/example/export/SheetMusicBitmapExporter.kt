package com.example.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.model.DetectedNote
import com.example.model.MusicTheory
import com.example.model.NoteDuration
import com.example.model.NoteLabelMode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a crisp, printable warm-pastel music notebook PNG image of the transcribed song
 * and returns a FileProvider Uri ready for Android's Share Sheet (zero storage permissions needed).
 */
object SheetMusicBitmapExporter {

    fun exportToShareUri(
        context: Context,
        title: String,
        notes: List<DetectedNote>,
        bpm: Int,
        beatsPerMeasure: Int,
        labelMode: NoteLabelMode
    ): Uri? {
        return runCatching {
            val bitmap = renderSheetBitmap(
                title = title.ifBlank { "Moja melodija" },
                notes = notes,
                bpm = bpm,
                beatsPerMeasure = beatsPerMeasure,
                labelMode = labelMode
            )
            val sheetsDir = File(context.cacheDir, "sheets").apply { mkdirs() }
            val file = File(sheetsDir, "notolovac_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }.getOrNull()
    }

    private fun renderSheetBitmap(
        title: String,
        notes: List<DetectedNote>,
        bpm: Int,
        beatsPerMeasure: Int,
        labelMode: NoteLabelMode
    ): Bitmap {
        val width = 1280
        val notesPerRow = 8
        val rowCount = maxOf(1, (notes.size + notesPerRow - 1) / notesPerRow)
        val headerHeight = 220
        val rowHeight = 250
        val footerHeight = 90
        val height = (headerHeight + rowCount * rowHeight + footerHeight).coerceAtLeast(640)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Warm cream paper background
        canvas.drawColor(Color.parseColor("#FFFBF2"))

        // Subtle notebook border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#EBD9BE")
            strokeWidth = 6f
        }
        canvas.drawRoundRect(RectF(28f, 28f, width - 28f, height - 28f), 32f, 32f, borderPaint)

        // Decorative pastel top ribbon
        val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFF0C8")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(56f, 52f, width - 56f, 180f), 24f, 24f, ribbonPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2E2622")
            textSize = 52f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(title, width / 2f, 118f, titlePaint)

        val dateStr = SimpleDateFormat("dd.MM.yyyy.", Locale.getDefault()).format(Date())
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6F625A")
            textSize = 26f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "Notolovac  •  $bpm BPM  •  $beatsPerMeasure/4  •  $dateStr",
            width / 2f,
            158f,
            subtitlePaint
        )

        val staffLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8C7D72")
            strokeWidth = 3f
        }
        val barLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5D5048")
            strokeWidth = 4f
        }
        val inkFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2E2622")
            style = Paint.Style.FILL
        }
        val inkStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2E2622")
            style = Paint.Style.STROKE
            strokeWidth = 4.5f
            strokeCap = Paint.Cap.ROUND
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E86F51")
            textSize = 26f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val clefPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2E2622")
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val timeSigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2E2622")
            textSize = 44f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val sharpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8378D4")
            textSize = 36f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }

        val leftMargin = 76f
        val rightMargin = width - 76f
        val lineSpacing = 22f // distance between two adjacent staff lines
        val halfStep = lineSpacing / 2f

        for (row in 0 until rowCount) {
            val staffTop = headerHeight + row * rowHeight + 48f
            val staffBottom = staffTop + 4 * lineSpacing

            // Draw 5 staff lines
            for (line in 0..4) {
                val y = staffTop + line * lineSpacing
                canvas.drawLine(leftMargin, y, rightMargin, y, staffLinePaint)
            }
            // Start and end vertical bars
            canvas.drawLine(leftMargin, staffTop, leftMargin, staffBottom, barLinePaint)
            canvas.drawLine(rightMargin, staffTop, rightMargin, staffBottom, barLinePaint)

            // Draw Treble Clef
            drawTrebleClef(canvas, leftMargin + 36f, staffTop, lineSpacing, clefPaint)

            // Time signature on first row
            val contentStartX = if (row == 0) {
                canvas.drawText(
                    beatsPerMeasure.toString(),
                    leftMargin + 94f,
                    staffTop + 2f * lineSpacing - 4f,
                    timeSigPaint
                )
                canvas.drawText(
                    "4",
                    leftMargin + 94f,
                    staffTop + 4f * lineSpacing - 2f,
                    timeSigPaint
                )
                leftMargin + 150f
            } else {
                leftMargin + 100f
            }

            val rowNotes = notes.drop(row * notesPerRow).take(notesPerRow)
            if (rowNotes.isEmpty()) continue

            val availableW = (rightMargin - contentStartX - 30f).coerceAtLeast(200f)
            val slotW = availableW / notesPerRow.toFloat()

            var beatsInMeasure = 0f
            rowNotes.forEachIndexed { idx, note ->
                val cx = contentStartX + idx * slotW + slotW * 0.45f

                if (note.isRest) {
                    // Draw a clean quarter/half rest symbol
                    if (note.beats >= 2f) {
                        canvas.drawRect(
                            cx - 14f,
                            staffTop + 2f * lineSpacing - 9f,
                            cx + 14f,
                            staffTop + 2f * lineSpacing,
                            inkFillPaint
                        )
                    } else {
                        val restPath = Path().apply {
                            moveTo(cx - 5f, staffTop + 0.8f * lineSpacing)
                            lineTo(cx + 8f, staffTop + 1.8f * lineSpacing)
                            lineTo(cx - 7f, staffTop + 2.6f * lineSpacing)
                            lineTo(cx + 6f, staffTop + 3.4f * lineSpacing)
                        }
                        canvas.drawPath(restPath, inkStrokePaint)
                    }
                } else {
                    val step = MusicTheory.midiToStaffStep(note.midiNote)
                    // E4 is step 2 -> bottom line (staffBottom)
                    val noteY = staffBottom - (step - 2) * halfStep

                    // Ledger lines below E4 (step <= 0, even steps)
                    if (step <= 0) {
                        var s = 0
                        while (s >= step) {
                            val ly = staffBottom - (s - 2) * halfStep
                            canvas.drawLine(cx - 24f, ly, cx + 24f, ly, staffLinePaint)
                            s -= 2
                        }
                    }
                    // Ledger lines above F5 (step >= 12, even steps)
                    if (step >= 12) {
                        var s = 12
                        while (s <= step) {
                            val ly = staffBottom - (s - 2) * halfStep
                            canvas.drawLine(cx - 24f, ly, cx + 24f, ly, staffLinePaint)
                            s += 2
                        }
                    }

                    // Sharp symbol
                    if (MusicTheory.isSharp(note.midiNote)) {
                        canvas.drawText("#", cx - 22f, noteY + 12f, sharpPaint)
                    }

                    // Notehead (oval slightly tilted)
                    canvas.save()
                    canvas.rotate(-16f, cx, noteY)
                    val rx = 14f
                    val ry = 10f
                    val oval = RectF(cx - rx, noteY - ry, cx + rx, noteY + ry)
                    val dur = note.duration
                    if (dur == NoteDuration.WHOLE || dur == NoteDuration.HALF) {
                        canvas.drawOval(oval, inkStrokePaint)
                    } else {
                        canvas.drawOval(oval, inkFillPaint)
                    }
                    canvas.restore()

                    // Stem & eighth (1 flag) / sixteenth (2 flags)
                    if (dur != NoteDuration.WHOLE) {
                        val stemUp = step < 6 // B4 or above -> stem down
                        val stemLen = 3.2f * lineSpacing
                        val flagCount = when (dur) {
                            NoteDuration.SIXTEENTH -> 2
                            NoteDuration.EIGHTH -> 1
                            else -> 0
                        }
                        if (stemUp) {
                            val sx = cx + 12.5f
                            canvas.drawLine(sx, noteY - 2f, sx, noteY - stemLen, inkStrokePaint)
                            for (f in 0 until flagCount) {
                                val fy = noteY - stemLen + f * 14f
                                val flag = Path().apply {
                                    moveTo(sx, fy)
                                    quadTo(sx + 22f, fy + 13f, sx + 14f, fy + 32f)
                                }
                                canvas.drawPath(flag, inkStrokePaint)
                            }
                        } else {
                            val sx = cx - 12.5f
                            canvas.drawLine(sx, noteY + 2f, sx, noteY + stemLen, inkStrokePaint)
                            for (f in 0 until flagCount) {
                                val fy = noteY + stemLen - f * 14f
                                val flag = Path().apply {
                                    moveTo(sx, fy)
                                    quadTo(sx + 22f, fy - 13f, sx + 14f, fy - 32f)
                                }
                                canvas.drawPath(flag, inkStrokePaint)
                            }
                        }
                    }

                    // Note label below staff
                    val label = MusicTheory.formatNoteLabel(note.midiNote, labelMode)
                    if (label.isNotEmpty()) {
                        val labelY = maxOf(staffBottom + 48f, noteY + 42f)
                        canvas.drawText(label, cx, labelY, labelPaint)
                    }
                }

                // Draw measure bar line when measure fills up
                beatsInMeasure += note.beats
                if (beatsInMeasure >= beatsPerMeasure - 0.01f) {
                    beatsInMeasure = 0f
                    if (idx < rowNotes.lastIndex) {
                        val barX = cx + slotW * 0.52f
                        canvas.drawLine(barX, staffTop, barX, staffBottom, barLinePaint)
                    }
                }
            }
        }

        // Footer watermark
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9C8D82")
            textSize = 22f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "Napravljeno u aplikaciji Notolovac",
            width / 2f,
            height - 46f,
            footerPaint
        )

        return bitmap
    }

    private fun drawTrebleClef(
        canvas: Canvas,
        cx: Float,
        staffTop: Float,
        lineSpacing: Float,
        paint: Paint
    ) {
        val gLineY = staffTop + 3f * lineSpacing
        val path = Path().apply {
            // Spiral around G4 line
            moveTo(cx + 4f, gLineY + lineSpacing * 0.1f)
            cubicTo(
                cx - 14f, gLineY - lineSpacing * 0.2f,
                cx - 14f, gLineY - lineSpacing * 1.15f,
                cx + 4f, gLineY - lineSpacing * 1.1f
            )
            cubicTo(
                cx + 22f, gLineY - lineSpacing * 1.0f,
                cx + 22f, gLineY + lineSpacing * 0.85f,
                cx - 4f, gLineY + lineSpacing * 0.85f
            )
            cubicTo(
                cx - 24f, gLineY + lineSpacing * 0.85f,
                cx - 24f, gLineY - lineSpacing * 1.4f,
                cx + 8f, staffTop - lineSpacing * 0.45f
            )
            // Top loop above staff
            cubicTo(
                cx + 18f, staffTop - lineSpacing * 1.25f,
                cx - 2f, staffTop - lineSpacing * 1.4f,
                cx - 4f, staffTop - lineSpacing * 0.3f
            )
            // Vertical spine down through the staff with bottom hook
            lineTo(cx + 2f, staffTop + 4.8f * lineSpacing)
            cubicTo(
                cx + 2f, staffTop + 5.35f * lineSpacing,
                cx - 14f, staffTop + 5.35f * lineSpacing,
                cx - 14f, staffTop + 4.75f * lineSpacing
            )
        }
        canvas.drawPath(path, paint)
    }
}
