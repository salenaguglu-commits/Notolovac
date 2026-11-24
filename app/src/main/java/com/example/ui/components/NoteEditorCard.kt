package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DetectedNote
import com.example.model.MusicTheory
import com.example.model.NoteDuration
import com.example.model.SheetNoteEditor
import com.example.ui.theme.CoralContainer
import com.example.ui.theme.CoralPrimary
import com.example.ui.theme.HoneyGoldContainer
import com.example.ui.theme.HoneyGoldTertiary
import com.example.ui.theme.LavenderContainer
import com.example.ui.theme.LavenderMusic
import com.example.ui.theme.MutedInk
import com.example.ui.theme.OnCoralContainer
import com.example.ui.theme.OnLavenderContainer
import com.example.ui.theme.SageMintContainer
import com.example.ui.theme.SageMintSecondary
import com.example.ui.theme.SoftError
import com.example.ui.theme.WarmCard
import com.example.ui.theme.WarmCardBorder
import com.example.ui.theme.WarmInk
import com.example.ui.theme.WarmSurface

/**
 * Compact, kid-friendly interactive editor panel designed to stay pinned at the bottom of the screen
 * while the sheet music notebook scrolls freely above it.
 * All interactive controls maintain >= 48.dp touch targets for comfortable child-friendly tapping.
 */
@Composable
fun NoteEditorCard(
    selectedNote: DetectedNote,
    selectedIndex: Int,
    totalCount: Int,
    onSelectPrevious: () -> Unit,
    onSelectNext: () -> Unit,
    onPitchStepDown: () -> Unit,
    onPitchStepUp: () -> Unit,
    onToggleSharp: () -> Unit,
    onConvertRestToNote: () -> Unit,
    onSelectDuration: (NoteDuration) -> Unit,
    onAuditionNote: () -> Unit,
    onInsertNoteAfter: () -> Unit,
    onDeleteNote: () -> Unit,
    showKeyboard: Boolean = true,
    onToggleKeyboard: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isSharp = !selectedNote.isRest && MusicTheory.isSharp(selectedNote.midiNote)
    val sharpEnabled = !selectedNote.isRest && SheetNoteEditor.canToggleSharp(selectedNote.midiNote)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("note_editor_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Note Navigation & Identity Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = onSelectPrevious,
                enabled = selectedIndex > 0,
                shape = RoundedCornerShape(14.dp),
                color = if (selectedIndex > 0) WarmSurface else WarmCard.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, WarmCardBorder),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("edit_prev_note_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.edit_prev_note_desc),
                        tint = if (selectedIndex > 0) WarmInk else MutedInk.copy(alpha = 0.4f)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (selectedNote.isRest) {
                        "${stringResource(R.string.edit_rest_label)}  •  #${selectedIndex + 1}/$totalCount"
                    } else {
                        "${MusicTheory.formatFullNoteDescription(selectedNote.midiNote)}  •  #${selectedIndex + 1}/$totalCount"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = WarmInk,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = selectedNote.duration.localizedName(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedInk,
                    maxLines = 1
                )
            }

            Surface(
                onClick = onSelectNext,
                enabled = selectedIndex < totalCount - 1,
                shape = RoundedCornerShape(14.dp),
                color = if (selectedIndex < totalCount - 1) WarmSurface else WarmCard.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, WarmCardBorder),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("edit_next_note_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.edit_next_note_desc),
                        tint = if (selectedIndex < totalCount - 1) WarmInk else MutedInk.copy(alpha = 0.4f)
                    )
                }
            }
        }

        // 2. Pitch Position Controls ("Niže ▼" / "#" / "Više ▲")
        if (selectedNote.isRest) {
            Button(
                onClick = onConvertRestToNote,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SageMintSecondary,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("edit_convert_rest_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.edit_convert_rest),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPitchStepDown,
                    enabled = selectedNote.midiNote > MusicTheory.MIN_PIANO_MIDI,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WarmSurface,
                        contentColor = WarmInk,
                        disabledContainerColor = WarmCard.copy(alpha = 0.5f),
                        disabledContentColor = MutedInk.copy(alpha = 0.4f)
                    ),
                    border = BorderStroke(1.5.dp, WarmCardBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier
                        .weight(1.25f)
                        .height(48.dp)
                        .testTag("edit_pitch_down_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.edit_pitch_down)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.edit_pitch_down), style = MaterialTheme.typography.labelLarge)
                }

                Surface(
                    onClick = onToggleSharp,
                    enabled = sharpEnabled,
                    shape = RoundedCornerShape(14.dp),
                    color = when {
                        isSharp -> LavenderMusic
                        sharpEnabled -> LavenderContainer
                        else -> WarmCard.copy(alpha = 0.45f)
                    },
                    border = BorderStroke(
                        1.5.dp,
                        when {
                            isSharp -> LavenderMusic
                            sharpEnabled -> LavenderMusic.copy(alpha = 0.55f)
                            else -> WarmCardBorder
                        }
                    ),
                    modifier = Modifier
                        .weight(0.75f)
                        .height(48.dp)
                        .testTag("edit_pitch_sharp_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "#",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isSharp -> Color.White
                                sharpEnabled -> OnLavenderContainer
                                else -> MutedInk.copy(alpha = 0.4f)
                            }
                        )
                    }
                }

                Button(
                    onClick = onPitchStepUp,
                    enabled = selectedNote.midiNote < MusicTheory.MAX_PIANO_MIDI,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WarmSurface,
                        contentColor = WarmInk,
                        disabledContainerColor = WarmCard.copy(alpha = 0.5f),
                        disabledContentColor = MutedInk.copy(alpha = 0.4f)
                    ),
                    border = BorderStroke(1.5.dp, WarmCardBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier
                        .weight(1.25f)
                        .height(48.dp)
                        .testTag("edit_pitch_up_button")
                ) {
                    Text(stringResource(R.string.edit_pitch_up), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.edit_pitch_up)
                    )
                }
            }
        }

        // 3. Duration Picker ("1/16", "1/8", "1/4", "1/2", "1/1")
        val durationChoices = if (selectedNote.isRest) {
            listOf(
                NoteDuration.QUARTER to ("1/4" to NoteDuration.QUARTER.localizedShortName()),
                NoteDuration.HALF to ("1/2" to NoteDuration.HALF.localizedShortName())
            )
        } else {
            listOf(
                NoteDuration.SIXTEENTH to ("1/16" to NoteDuration.SIXTEENTH.localizedShortName()),
                NoteDuration.EIGHTH to ("1/8" to NoteDuration.EIGHTH.localizedShortName()),
                NoteDuration.QUARTER to ("1/4" to NoteDuration.QUARTER.localizedShortName()),
                NoteDuration.HALF to ("1/2" to NoteDuration.HALF.localizedShortName()),
                NoteDuration.WHOLE to ("1/1" to NoteDuration.WHOLE.localizedShortName())
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            durationChoices.forEach { (dur, labels) ->
                val isSelected = selectedNote.duration == dur
                Surface(
                    onClick = { onSelectDuration(dur) },
                    shape = RoundedCornerShape(13.dp),
                    color = if (isSelected) CoralPrimary else WarmSurface,
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) CoralPrimary else WarmCardBorder
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("edit_duration_${dur.name.lowercase()}")
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    ) {
                        Text(
                            text = labels.first,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color.White else WarmInk
                        )
                        Text(
                            text = labels.second,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) Color.White.copy(alpha = 0.9f) else MutedInk
                        )
                    }
                }
            }
        }

        // 4. Utility Row: Audition + Insert Note + Delete Note + Optional Piano Keyboard Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!selectedNote.isRest) {
                OutlinedButton(
                    onClick = onAuditionNote,
                    shape = RoundedCornerShape(13.dp),
                    border = BorderStroke(1.5.dp, SageMintSecondary),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SageMintContainer.copy(alpha = 0.65f),
                        contentColor = WarmInk
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("preview_selected_note_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.edit_audition_desc),
                        tint = SageMintSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.edit_audition_short),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                }
            }

            OutlinedButton(
                onClick = onInsertNoteAfter,
                shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.5.dp, HoneyGoldTertiary),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = WarmSurface,
                    contentColor = WarmInk
                ),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier
                    .weight(1.1f)
                    .height(48.dp)
                    .testTag("edit_insert_note_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.edit_add_note_desc),
                    tint = CoralPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.edit_add_note),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }

            OutlinedButton(
                onClick = onDeleteNote,
                shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.5.dp, SoftError.copy(alpha = 0.7f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = CoralContainer.copy(alpha = 0.55f),
                    contentColor = OnCoralContainer
                ),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("remove_selected_note_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.edit_remove_note_desc),
                    tint = SoftError,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.action_delete),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }

            if (onToggleKeyboard != null) {
                Surface(
                    onClick = onToggleKeyboard,
                    shape = RoundedCornerShape(13.dp),
                    color = if (showKeyboard) HoneyGoldContainer else WarmSurface,
                    border = BorderStroke(
                        width = 1.5.dp,
                        color = if (showKeyboard) CoralPrimary else WarmCardBorder
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("edit_toggle_keyboard_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Piano,
                            contentDescription = if (showKeyboard) {
                                stringResource(R.string.edit_hide_keyboard_desc)
                            } else {
                                stringResource(R.string.edit_show_keyboard_desc)
                            },
                            tint = if (showKeyboard) CoralPrimary else MutedInk,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NoteDuration.localizedName(): String = when (this) {
    NoteDuration.SIXTEENTH -> stringResource(R.string.duration_sixteenth)
    NoteDuration.EIGHTH -> stringResource(R.string.duration_eighth)
    NoteDuration.QUARTER -> stringResource(R.string.duration_quarter)
    NoteDuration.HALF -> stringResource(R.string.duration_half)
    NoteDuration.WHOLE -> stringResource(R.string.duration_whole)
}

@Composable
fun NoteDuration.localizedShortName(): String = when (this) {
    NoteDuration.SIXTEENTH -> stringResource(R.string.duration_short_sixteenth)
    NoteDuration.EIGHTH -> stringResource(R.string.duration_short_eighth)
    NoteDuration.QUARTER -> stringResource(R.string.duration_short_quarter)
    NoteDuration.HALF -> stringResource(R.string.duration_short_half)
    NoteDuration.WHOLE -> stringResource(R.string.duration_short_whole)
}
