package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.MusicTheory
import com.example.model.NoteDuration
import com.example.model.NoteLabelMode
import com.example.ui.components.FullSheetMusicCanvas
import com.example.ui.components.GlowingPianoKeyboard
import com.example.ui.components.NoteEditorCard
import com.example.ui.components.OctaveSelectorRow
import com.example.ui.components.localizedName
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
    onPianoKeyRelease: (Int) -> Unit = {},
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
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(CreamPaper),
        contentAlignment = Alignment.TopCenter
    ) {
        // Show compact piano keyboard inside the sticky edit dock by default on standard screens,
        // and collapse by default on short/landscape viewports (always 1-tap toggleable).
        val defaultShowKeyboard = maxHeight >= 640.dp
        var showEditKeyboard by rememberSaveable { mutableStateOf(defaultShowKeyboard) }
        var scrollViewportHeightPx by remember { mutableIntStateOf(0) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
        ) {
            // =========================================================================
            // 1. FIXED TOP HEADER (Ultra-compact in Edit Mode so sheet gets max height)
            // =========================================================================
            if (sheet.isEditMode) {
                Surface(
                    color = CreamPaper,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = onToggleEditMode,
                                modifier = Modifier.testTag("sheet_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_done_editing),
                                    tint = WarmInk
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = SageMintContainer,
                                    border = BorderStroke(1.dp, SageMintSecondary)
                                ) {
                                    Text(
                                        text = stringResource(R.string.edit_mode_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = OnSageMintContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = sheet.title.ifBlank { stringResource(R.string.default_song_title) },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = WarmInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            EditModeToggleButton(
                                isEditMode = true,
                                enabled = sheet.notes.isNotEmpty(),
                                onClick = onToggleEditMode
                            )
                        }
                        HorizontalDivider(color = WarmCardBorder.copy(alpha = 0.6f))
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                                text = stringResource(
                                    R.string.sheet_meta_format,
                                    noteCount,
                                    sheet.bpm,
                                    sheet.beatsPerMeasure
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedInk,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        EditModeToggleButton(
                            isEditMode = false,
                            enabled = sheet.notes.isNotEmpty(),
                            onClick = onToggleEditMode
                        )
                    }
                }
            }

            // =========================================================================
            // 2. INDEPENDENTLY SCROLLABLE SHEET MUSIC REGION
            // =========================================================================
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { scrollViewportHeightPx = it.height }
            ) {
                val canvasMaxWidth = (maxWidth - 32.dp).coerceAtLeast(100.dp)
                val notesPerRow = if (canvasMaxWidth >= 520.dp) 8 else 6

                // Automatically keep the edited (or currently playing) note's staff row in view
                val focusNoteIndex = sheet.playingNoteIndex ?: if (sheet.isEditMode) sheet.selectedNoteIndex else null
                LaunchedEffect(focusNoteIndex, sheet.isEditMode, notesPerRow, scrollViewportHeightPx) {
                    val idx = focusNoteIndex ?: return@LaunchedEffect
                    if (scrollViewportHeightPx <= 0 || idx !in sheet.notes.indices) return@LaunchedEffect
                    val row = idx / notesPerRow
                    val rowHeightPx = with(density) { 158.dp.toPx() }
                    val topOffsetPx = with(density) { 54.dp.toPx() }
                    val rowTop = (topOffsetPx + row * rowHeightPx).toInt()
                    val rowBottom = (rowTop + rowHeightPx).toInt()
                    val visibleTop = scrollState.value
                    val visibleBottom = scrollState.value + scrollViewportHeightPx

                    when {
                        rowTop < visibleTop -> {
                            scrollState.animateScrollTo((rowTop - 16).coerceAtLeast(0))
                        }
                        rowBottom > visibleBottom -> {
                            val target = (rowBottom - scrollViewportHeightPx + 24).coerceAtLeast(0)
                            scrollState.animateScrollTo(target)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        NoteLabelModeRow(
                            selectedMode = labelMode,
                            onSelect = onLabelModeSelected
                        )
                    }

                    // Interactive Multi-line Sheet Music Notebook Page
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
                                    stringResource(R.string.edit_sheet_hint_active)
                                } else {
                                    stringResource(R.string.edit_sheet_hint_view)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (sheet.isEditMode) CoralPrimary else MutedInk,
                                fontWeight = if (sheet.isEditMode) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // View Mode footer controls scroll naturally below the sheet
                    if (!sheet.isEditMode) {
                        GlowingPianoKeyboard(
                            activeMidiNote = activeKeyMidi,
                            startOctave = keyboardStartOctave,
                            labelMode = labelMode,
                            onKeyClick = onPianoKeyClick,
                            onKeyRelease = onPianoKeyRelease,
                            keyboardHeight = 92.dp
                        )

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
                                    text = if (sheet.isSaved) {
                                        stringResource(R.string.action_saved_check)
                                    } else {
                                        stringResource(R.string.action_save_song)
                                    },
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

                        Spacer(Modifier.height(8.dp))
                    } else {
                        // Small breathing room at bottom of scrollable sheet above the pinned editor dock
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }

            // =========================================================================
            // 3. STICKY BOTTOM DOCK (Pinned at screen bottom, never scrolls away!)
            // =========================================================================
            AnimatedVisibility(
                visible = sheet.isEditMode && selectedNote != null,
                enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
            ) {
                val selIdx = sheet.selectedNoteIndex ?: return@AnimatedVisibility
                val sel = selectedNote ?: return@AnimatedVisibility

                Surface(
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = Color(0xFFFFF6E4),
                    border = BorderStroke(1.5.dp, CoralPrimary.copy(alpha = 0.55f)),
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sticky_edit_dock")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Subtle visual handle pill indicating pinned bottom sheet dock
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(CoralPrimary.copy(alpha = 0.35f))
                        )

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
                            onDeleteNote = { onRemoveNote(selIdx) },
                            showKeyboard = showEditKeyboard,
                            onToggleKeyboard = { showEditKeyboard = !showEditKeyboard }
                        )

                        AnimatedVisibility(visible = showEditKeyboard) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.edit_tap_key_hint),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = CoralPrimary
                                    )
                                    OctaveSelectorRow(
                                        startOctave = keyboardStartOctave,
                                        onSelectOctave = onOctaveChange
                                    )
                                }
                                GlowingPianoKeyboard(
                                    activeMidiNote = activeKeyMidi,
                                    startOctave = keyboardStartOctave,
                                    labelMode = labelMode,
                                    onKeyClick = onPianoKeyClick,
                                    onKeyRelease = onPianoKeyRelease,
                                    keyboardHeight = 76.dp
                                )
                            }
                        }
                    }
                }
            }

            // Pinned Compact Inspector Bar in View Mode when any note on a long sheet is tapped
            AnimatedVisibility(
                visible = !sheet.isEditMode && selectedNote != null,
                enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
            ) {
                val selIdx = sheet.selectedNoteIndex ?: return@AnimatedVisibility
                val sel = selectedNote ?: return@AnimatedVisibility
                Surface(
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    color = HoneyGoldContainer,
                    border = BorderStroke(1.5.dp, HoneyGoldTertiary),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sticky_inspector_dock")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val localizedDuration = sel.duration.localizedName()
                            Text(
                                text = if (sel.isRest) {
                                    stringResource(R.string.edit_rest_with_duration, localizedDuration)
                                } else {
                                    MusicTheory.formatFullNoteDescription(sel.midiNote)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = WarmInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = localizedDuration,
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
                                        contentDescription = stringResource(R.string.edit_this_note_desc),
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
                                        contentDescription = stringResource(R.string.edit_audition_desc),
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
                                    contentDescription = stringResource(R.string.edit_remove_note_desc),
                                    tint = SoftError
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditModeToggleButton(
    isEditMode: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = if (isEditMode) SageMintSecondary else HoneyGoldContainer,
        border = BorderStroke(
            width = 1.5.dp,
            color = if (isEditMode) SageMintSecondary else CoralPrimary
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
                imageVector = if (isEditMode) Icons.Filled.Check else Icons.Filled.Edit,
                contentDescription = if (isEditMode) {
                    stringResource(R.string.action_done_editing)
                } else {
                    stringResource(R.string.action_edit_notes)
                },
                tint = if (isEditMode) Color.White else CoralPrimary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = if (isEditMode) {
                    stringResource(R.string.action_done_editing)
                } else {
                    stringResource(R.string.action_edit_notes)
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (isEditMode) Color.White else WarmInk
            )
        }
    }
}
