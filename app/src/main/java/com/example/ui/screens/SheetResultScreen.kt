package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.MusicTheory
import com.example.model.NoteDuration
import com.example.model.NoteLabelMode
import com.example.ui.components.FullSheetMusicCanvas
import com.example.ui.components.GlowingPianoKeyboard
import com.example.ui.components.NoteEditorCard
import com.example.ui.components.OctaveSelectorRow
import com.example.ui.theme.CoralContainer
import com.example.ui.theme.CoralPrimary
import com.example.ui.theme.CreamPaper
import com.example.ui.theme.HoneyGoldContainer
import com.example.ui.theme.HoneyGoldTertiary
import com.example.ui.theme.LavenderContainer
import com.example.ui.theme.LavenderMusic
import com.example.ui.theme.MutedInk
import com.example.ui.theme.OnLavenderContainer
import com.example.ui.theme.OnSageMintContainer
import com.example.ui.theme.SageMintContainer
import com.example.ui.theme.SageMintSecondary
import com.example.ui.theme.SoftError
import com.example.ui.theme.WarmCardBorder
import com.example.ui.theme.WarmInk
import com.example.ui.theme.WarmSurface
import com.example.viewmodel.ActiveSheetState

@Composable
fun SheetResultScreen(
    sheet: ActiveSheetState,
    labelMode: NoteLabelMode,
    keyboardStartOctave: Int,
    keyboardPressedMidi: Int?,
    onBack: () -> Unit,
    onTitleChange: (String) -> Unit,
    onLabelModeSelected: (NoteLabelMode) -> Unit,
    onToggleEditMode: () -> Unit,
    onEnterEditModeForNote: (Int) -> Unit,
    onNoteTapped: (Int) -> Unit,
    onSelectAdjacentNote: (Int) -> Unit,
    onPitchStepDown: () -> Unit,
    onPitchStepUp: () -> Unit,
    onToggleSharp: () -> Unit,
    onConvertRestToNote: () -> Unit,
    onSelectDuration: (NoteDuration) -> Unit,
    onInsertNoteAfter: () -> Unit,
    onRemoveNote: (Int) -> Unit,
    onTogglePlayback: () -> Unit,
    onSaveSong: () -> Unit,
    onSharePng: () -> Unit,
    onOctaveChange: (Int) -> Unit,
    onPianoKeyClick: (Int) -> Unit,
    onAuditionNote: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        if (sheet.isEditMode) onToggleEditMode() else onBack()
    }

    val playingMidi = sheet.playingNoteIndex?.let { idx ->
        sheet.notes.getOrNull(idx)?.takeIf { !it.isRest }?.midiNote
    }
    val selectedNote = sheet.selectedNoteIndex?.let { sheet.notes.getOrNull(it) }
    val editSelectedMidi = if (sheet.isEditMode) selectedNote?.takeIf { !it.isRest }?.midiNote else null
    val activeKeyMidi = playingMidi ?: keyboardPressedMidi ?: editSelectedMidi
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
                .widthIn(max = 680.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Top Bar: Back + Editable Title + Quick Share Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("sheet_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = WarmInk
                    )
                }

                OutlinedTextField(
                    value = sheet.title,
                    onValueChange = onTitleChange,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.default_song_title)) },
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = WarmInk
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CoralPrimary,
                        unfocusedBorderColor = WarmCardBorder,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = WarmSurface
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("song_title_input")
                )

                Surface(
                    onClick = onSharePng,
                    shape = RoundedCornerShape(14.dp),
                    color = LavenderContainer,
                    border = BorderStroke(1.dp, LavenderMusic),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("share_png_icon_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.action_share_image),
                            tint = OnLavenderContainer
                        )
                    }
                }
            }

            // 2. Metadata, Edit Mode Toggle & Label Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val noteCount = sheet.notes.count { !it.isRest }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = WarmSurface,
                    border = BorderStroke(1.dp, WarmCardBorder)
                ) {
                    Text(
                        text = "$noteCount nota • ${sheet.bpm} BPM • ${sheet.beatsPerMeasure}/4",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedInk,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                // Prominent "Izmeni" / "Gotovo" Edit Mode Button
                Surface(
                    onClick = onToggleEditMode,
                    enabled = sheet.notes.isNotEmpty(),
                    shape = RoundedCornerShape(14.dp),
                    color = if (sheet.isEditMode) SageMintSecondary else HoneyGoldContainer,
                    border = BorderStroke(
                        width = 1.5.dp,
                        color = if (sheet.isEditMode) SageMintSecondary else CoralPrimary
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("toggle_edit_mode_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (sheet.isEditMode) Icons.Filled.Check else Icons.Filled.Edit,
                            contentDescription = if (sheet.isEditMode) {
                                stringResource(R.string.action_done_editing)
                            } else {
                                stringResource(R.string.action_edit_notes)
                            },
                            tint = if (sheet.isEditMode) Color.White else CoralPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (sheet.isEditMode) {
                                stringResource(R.string.action_done_editing)
                            } else {
                                stringResource(R.string.action_edit_notes)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (sheet.isEditMode) Color.White else WarmInk
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (sheet.isEditMode) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = SageMintContainer,
                        border = BorderStroke(1.dp, SageMintSecondary)
                    ) {
                        Text(
                            text = "Režim izmene",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = OnSageMintContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                NoteLabelModeRow(
                    selectedMode = labelMode,
                    onSelect = onLabelModeSelected
                )
            }

            // 3. Full Interactive Multi-line Sheet Music Notebook Page
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFFFFFDF8),
                border = BorderStroke(
                    width = if (sheet.isEditMode) 2.5.dp else 1.5.dp,
                    color = if (sheet.isEditMode) CoralPrimary else WarmCardBorder
                ),
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                    FullSheetMusicCanvas(
                        notes = sheet.notes,
                        beatsPerMeasure = sheet.beatsPerMeasure,
                        labelMode = labelMode,
                        playingNoteIndex = sheet.playingNoteIndex,
                        selectedNoteIndex = sheet.selectedNoteIndex,
                        onNoteTapped = onNoteTapped
                    )
                    Text(
                        text = if (sheet.isEditMode) {
                            "Dodirni notu na linijama, pa joj promeni visinu (strelicama ili dirkom na klavijaturi) i dužinu trajanja."
                        } else {
                            "Savet: Pritisni „Izmeni“ ili dodirni bilo koju notu da je čuješ, pomeriš na pravo mesto ili promeniš trajanje."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (sheet.isEditMode) CoralPrimary else MutedInk,
                        fontWeight = if (sheet.isEditMode) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            // 4. Full Note Editor Card (in Edit Mode) OR Compact Inspector Bar (in View Mode)
            AnimatedVisibility(visible = sheet.isEditMode && selectedNote != null) {
                val selIdx = sheet.selectedNoteIndex ?: return@AnimatedVisibility
                val sel = selectedNote ?: return@AnimatedVisibility
                NoteEditorCard(
                    selectedNote = sel,
                    selectedIndex = selIdx,
                    totalCount = sheet.notes.size,
                    onSelectPrevious = { onSelectAdjacentNote(-1) },
                    onSelectNext = { onSelectAdjacentNote(1) },
                    onPitchStepDown = onPitchStepDown,
                    onPitchStepUp = onPitchStepUp,
                    onToggleSharp = onToggleSharp,
                    onConvertRestToNote = onConvertRestToNote,
                    onSelectDuration = onSelectDuration,
                    onAuditionNote = { if (!sel.isRest) onAuditionNote(sel.midiNote) },
                    onInsertNoteAfter = onInsertNoteAfter,
                    onDeleteNote = { onRemoveNote(selIdx) }
                )
            }

            AnimatedVisibility(visible = !sheet.isEditMode && selectedNote != null) {
                val selIdx = sheet.selectedNoteIndex ?: return@AnimatedVisibility
                val sel = selectedNote ?: return@AnimatedVisibility
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = HoneyGoldContainer,
                    border = BorderStroke(1.5.dp, HoneyGoldTertiary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (sel.isRest) {
                                    "Pauza (${sel.duration.serbianName})"
                                } else {
                                    MusicTheory.formatFullNoteDescription(sel.midiNote)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = WarmInk
                            )
                            Text(
                                text = sel.duration.serbianName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedInk
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Surface(
                                onClick = { onEnterEditModeForNote(selIdx) },
                                shape = RoundedCornerShape(12.dp),
                                color = CoralPrimary,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("quick_edit_selected_note_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = "Izmeni ovu notu",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.action_edit_notes),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                            if (!sel.isRest) {
                                IconButton(
                                    onClick = { onAuditionNote(sel.midiNote) },
                                    modifier = Modifier.testTag("preview_selected_note_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "Poslušaj notu",
                                        tint = SageMintSecondary
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onRemoveNote(selIdx) },
                                modifier = Modifier.testTag("remove_selected_note_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteOutline,
                                    contentDescription = "Ukloni notu",
                                    tint = SoftError
                                )
                            }
                        }
                    }
                }
            }

            // 5. Synchronized Glowing Keyboard (+ Octave Switcher when editing)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sheet.isEditMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Dodirni dirku za novu visinu",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = CoralPrimary
                        )
                        OctaveSelectorRow(
                            startOctave = keyboardStartOctave,
                            onSelectOctave = onOctaveChange
                        )
                    }
                }
                GlowingPianoKeyboard(
                    activeMidiNote = activeKeyMidi,
                    startOctave = keyboardStartOctave,
                    labelMode = labelMode,
                    onKeyClick = onPianoKeyClick,
                    keyboardHeight = if (sheet.isEditMode) 108.dp else 92.dp
                )
            }

            // 6. Primary Playback & Save/Share Actions
            Button(
                onClick = onTogglePlayback,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (sheet.isPlaying) CoralPrimary else SageMintSecondary,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("play_melody_button")
            ) {
                Icon(
                    imageVector = if (sheet.isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (sheet.isPlaying) {
                        stringResource(R.string.action_stop_playback)
                    } else {
                        stringResource(R.string.action_play_melody)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSaveSong,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sheet.isSaved) SageMintContainer else CoralPrimary,
                        contentColor = if (sheet.isSaved) WarmInk else Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("save_song_button")
                ) {
                    Icon(
                        imageVector = if (sheet.isSaved) Icons.Filled.BookmarkAdded else Icons.Filled.BookmarkBorder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (sheet.isSaved) "Sačuvano ✓" else stringResource(R.string.action_save_song),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                OutlinedButton(
                    onClick = onSharePng,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.5.dp, LavenderMusic),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("share_song_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = null,
                        tint = LavenderMusic,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_share_image),
                        color = WarmInk,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}
