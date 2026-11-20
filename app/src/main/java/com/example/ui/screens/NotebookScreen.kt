package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.SavedSong
import com.example.ui.components.MiniSheetPreview
import com.example.ui.theme.CoralPrimary
import com.example.ui.theme.CreamPaper
import com.example.ui.theme.HoneyGoldContainer
import com.example.ui.theme.MutedInk
import com.example.ui.theme.SageMintContainer
import com.example.ui.theme.SoftError
import com.example.ui.theme.WarmCardBorder
import com.example.ui.theme.WarmInk
import com.example.ui.theme.WarmSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NotebookScreen(
    songs: List<SavedSong>,
    onOpenSong: (SavedSong) -> Unit,
    onDeleteSong: (SavedSong) -> Unit,
    onGoToStudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingDeleteSong by remember { mutableStateOf<SavedSong?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CreamPaper),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.tab_notebook),
                        style = MaterialTheme.typography.headlineSmall,
                        color = WarmInk
                    )
                    Text(
                        text = if (songs.isEmpty()) {
                            "Ovde čuvaš sve svoje melodije"
                        } else {
                            "Sačuvano pesmica: ${songs.size}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedInk
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (songs.isEmpty()) {
                EmptyNotebookView(onGoToStudio = onGoToStudio)
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.testTag("notebook_song_list")
                ) {
                    items(songs, key = { it.id }) { song ->
                        SavedSongNotebookCard(
                            song = song,
                            onClick = { onOpenSong(song) },
                            onRequestDelete = { pendingDeleteSong = song }
                        )
                    }
                }
            }
        }
    }

    pendingDeleteSong?.let { song ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSong = null },
            containerColor = CreamPaper,
            title = {
                Text(
                    text = "Obrisati pesmicu?",
                    style = MaterialTheme.typography.titleLarge,
                    color = WarmInk
                )
            },
            text = {
                Text(
                    text = "Da li sigurno želiš da obrišeš „${song.title}“ iz svoje sveske?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSong(song)
                        pendingDeleteSong = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SoftError),
                    modifier = Modifier.testTag("confirm_delete_song_button")
                ) {
                    Text("Obriši")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSong = null }) {
                    Text("Odustani", color = WarmInk)
                }
            }
        )
    }
}

@Composable
private fun EmptyNotebookView(onGoToStudio: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_notolovac_mascot_1795199060528),
            contentDescription = "Notolovac lisica hvata note",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(210.dp)
                .clip(RoundedCornerShape(32.dp))
                .border(2.dp, WarmCardBorder, RoundedCornerShape(32.dp))
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.empty_notebook_title),
            style = MaterialTheme.typography.titleLarge,
            color = WarmInk,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_notebook_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MutedInk,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onGoToStudio,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CoralPrimary,
                contentColor = Color.White
            ),
            modifier = Modifier
                .height(52.dp)
                .testTag("empty_notebook_record_button")
        ) {
            Icon(imageVector = Icons.Filled.Mic, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Snimi prvu melodiju",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun SavedSongNotebookCard(
    song: SavedSong,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val dateLabel = remember(song.createdAtMillis) {
        SimpleDateFormat("dd.MM.yyyy. • HH:mm", Locale.getDefault()).format(Date(song.createdAtMillis))
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFFFFFDF8),
        border = BorderStroke(1.5.dp, WarmCardBorder),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("saved_song_card_${song.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = HoneyGoldContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = CoralPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = WarmInk,
                            maxLines = 1
                        )
                        Text(
                            text = dateLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedInk
                        )
                    }
                }

                IconButton(
                    onClick = onRequestDelete,
                    modifier = Modifier.testTag("delete_song_button_${song.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MutedInk
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Mini staff preview
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = WarmSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                MiniSheetPreview(
                    notes = song.notes,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(10.dp), color = SageMintContainer) {
                    Text(
                        text = "${song.playableNoteCount} nota",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarmInk,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Surface(shape = RoundedCornerShape(10.dp), color = WarmSurface) {
                    Text(
                        text = "${song.bpm} BPM • ${song.beatsPerMeasure}/4",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedInk,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
