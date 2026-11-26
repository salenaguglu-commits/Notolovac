package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Piano
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.export.SheetMusicBitmapExporter
import com.example.ui.components.TuneCalibrationDialog
import com.example.ui.screens.NotebookScreen
import com.example.ui.screens.SheetResultScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.CoralContainer
import com.example.ui.theme.CoralPrimary
import com.example.ui.theme.CreamPaper
import com.example.ui.theme.MutedInk
import com.example.ui.theme.NotolovacTheme
import com.example.ui.theme.WarmInk
import com.example.ui.theme.WarmSurface
import com.example.viewmodel.AppTab
import com.example.viewmodel.NotolovacViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotolovacTheme {
                NotolovacApp()
            }
        }
    }
}

private enum class PendingMicAction {
    NONE,
    RECORD,
    TUNE
}

@Composable
fun NotolovacApp(
    viewModel: NotolovacViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val savedSongs by viewModel.savedSongs.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var pendingAction by remember { mutableStateOf(PendingMicAction.NONE) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            when (pendingAction) {
                PendingMicAction.RECORD -> viewModel.startRecording()
                PendingMicAction.TUNE -> viewModel.startCalibrationListening()
                PendingMicAction.NONE -> Unit
            }
        } else {
            viewModel.showMessage(context.getString(R.string.error_mic_permission_denied))
        }
        pendingAction = PendingMicAction.NONE
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.dismissSnackbar()
    }

    fun requestMicAndRun(action: PendingMicAction) {
        val currentlyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        hasMicPermission = currentlyGranted
        if (currentlyGranted) {
            when (action) {
                PendingMicAction.RECORD -> viewModel.startRecording()
                PendingMicAction.TUNE -> viewModel.startCalibrationListening()
                PendingMicAction.NONE -> Unit
            }
        } else {
            pendingAction = action
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun shareActiveSheetPng() {
        val sheet = uiState.activeSheet ?: return
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                SheetMusicBitmapExporter.exportToShareUri(
                    context = context,
                    title = sheet.title,
                    notes = sheet.notes,
                    bpm = sheet.bpm,
                    beatsPerMeasure = sheet.beatsPerMeasure,
                    labelMode = settings.noteLabelMode
                )
            }
            if (uri != null) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, sheet.title)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(
                        shareIntent,
                        context.getString(R.string.share_sheet_chooser)
                    )
                )
            } else {
                viewModel.showMessage(context.getString(R.string.error_share_export_failed))
            }
        }
    }

    val showingSheetResult = uiState.activeSheet != null

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscapePianoStage = !showingSheetResult &&
            uiState.currentTab == AppTab.STUDIO &&
            maxWidth > maxHeight

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = CreamPaper,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!showingSheetResult && !isLandscapePianoStage) {
                NavigationBar(
                    containerColor = WarmSurface,
                    contentColor = WarmInk
                ) {
                    NavigationBarItem(
                        selected = uiState.currentTab == AppTab.STUDIO,
                        onClick = { viewModel.selectTab(AppTab.STUDIO) },
                        icon = {
                            Icon(
                                imageVector = if (uiState.currentTab == AppTab.STUDIO) {
                                    Icons.Filled.Piano
                                } else {
                                    Icons.Outlined.Piano
                                },
                                contentDescription = stringResource(R.string.tab_studio)
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(R.string.tab_studio),
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CoralPrimary,
                            selectedTextColor = WarmInk,
                            indicatorColor = CoralContainer,
                            unselectedIconColor = MutedInk,
                            unselectedTextColor = MutedInk
                        ),
                        modifier = Modifier.testTag("nav_tab_studio")
                    )

                    NavigationBarItem(
                        selected = uiState.currentTab == AppTab.NOTEBOOK,
                        onClick = { viewModel.selectTab(AppTab.NOTEBOOK) },
                        icon = {
                            Icon(
                                imageVector = if (uiState.currentTab == AppTab.NOTEBOOK) {
                                    Icons.Filled.MenuBook
                                } else {
                                    Icons.Outlined.MenuBook
                                },
                                contentDescription = stringResource(R.string.tab_notebook)
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(R.string.tab_notebook),
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CoralPrimary,
                            selectedTextColor = WarmInk,
                            indicatorColor = CoralContainer,
                            unselectedIconColor = MutedInk,
                            unselectedTextColor = MutedInk
                        ),
                        modifier = Modifier.testTag("nav_tab_notebook")
                    )
                }
            }
        }
    ) { innerPadding ->
        val activeSheet = uiState.activeSheet
        if (activeSheet != null) {
            SheetResultScreen(
                sheet = activeSheet,
                labelMode = settings.noteLabelMode,
                keyboardStartOctave = uiState.keyboardStartOctave,
                keyboardPressedMidi = uiState.keyboardPressedMidi,
                onBack = { viewModel.closeActiveSheet() },
                onTitleChange = { viewModel.updateActiveSheetTitle(it) },
                onLabelModeSelected = { viewModel.setNoteLabelMode(it) },
                onToggleEditMode = { viewModel.toggleEditMode() },
                onEnterEditModeForNote = { viewModel.enterEditModeForNote(it) },
                onNoteTapped = { viewModel.selectAndPreviewNote(it) },
                onSelectAdjacentNote = { viewModel.selectAdjacentNote(it) },
                onPitchStepDown = { viewModel.stepSelectedNotePitch(-1) },
                onPitchStepUp = { viewModel.stepSelectedNotePitch(1) },
                onToggleSharp = { viewModel.toggleSelectedNoteSharp() },
                onConvertRestToNote = { viewModel.setSelectedNotePitch(60) },
                onSelectDuration = { viewModel.setSelectedNoteDuration(it) },
                onInsertNoteAfter = { viewModel.insertNoteAfterSelected() },
                onRemoveNote = { viewModel.removeNoteAt(it) },
                onClearAllNotes = { viewModel.clearActiveSheetNotes() },
                onAdjustBpm = { viewModel.adjustActiveSheetBpm(it) },
                onTogglePlayback = { viewModel.togglePlayback() },
                onTogglePlaybackFromSelected = { viewModel.togglePlaybackFromSelected() },
                onSaveSong = { viewModel.saveActiveSheet() },
                onSharePng = { shareActiveSheetPng() },
                onOctaveChange = { viewModel.setKeyboardOctave(it) },
                onPianoKeyClick = { viewModel.onSheetPianoKeyTapped(it) },
                onPianoKeyRelease = { viewModel.onSheetPianoKeyReleased(it) },
                onAuditionNote = { viewModel.onPianoKeyTapped(it) },
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            Crossfade(
                targetState = uiState.currentTab,
                modifier = Modifier.padding(innerPadding),
                label = "mainTabCrossfade"
            ) { tab ->
                when (tab) {
                    AppTab.STUDIO -> StudioScreen(
                        uiState = uiState,
                        settings = settings,
                        hasMicPermission = hasMicPermission,
                        onRequestMicPermission = {
                            pendingAction = PendingMicAction.NONE
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onListenToggle = {
                            when {
                                uiState.isKeyboardRecording -> viewModel.stopKeyboardRecording()
                                uiState.isRecording -> viewModel.stopRecording()
                                else -> requestMicAndRun(PendingMicAction.RECORD)
                            }
                        },
                        onToggleKeyboardRecording = {
                            if (uiState.isRecording) {
                                viewModel.stopRecording()
                            } else {
                                viewModel.toggleKeyboardRecording()
                            }
                        },
                        onOpenTuneDialog = { viewModel.openTuneDialog() },
                        onLabelModeSelected = { viewModel.setNoteLabelMode(it) },
                        onToggleMetronome = { viewModel.toggleMetronome() },
                        onBpmChange = { viewModel.setMetronomeBpm(it) },
                        onBeatsPerMeasureChange = { viewModel.setBeatsPerMeasure(it) },
                        onOctaveChange = { viewModel.setKeyboardOctave(it) },
                        onPianoKeyClick = { viewModel.onPianoKeyPressed(it) },
                        onPianoKeyRelease = { viewModel.onPianoKeyReleased(it) },
                        onClearKeyboardHistory = { viewModel.clearStudioStaff() },
                        isLandscape = isLandscapePianoStage
                    )

                    AppTab.NOTEBOOK -> NotebookScreen(
                        songs = savedSongs,
                        onOpenSong = { viewModel.openSavedSong(it) },
                        onDeleteSong = { viewModel.deleteSavedSong(it) },
                        onGoToStudio = { viewModel.selectTab(AppTab.STUDIO) }
                    )
                }
            }
        }
    }
    }

    // Tune / Piano Calibration Modal Dialog
    TuneCalibrationDialog(
        state = uiState.tuneDialog,
        onSelectMode = { viewModel.selectCalibrationMode(it) },
        onPlayReferenceC = { viewModel.playReferenceC() },
        onToggleListen = {
            if (uiState.tuneDialog.isListening) {
                viewModel.startCalibrationListening() // toggles off
            } else {
                requestMicAndRun(PendingMicAction.TUNE)
            }
        },
        onResetStandard = { viewModel.resetCalibrationToStandard() },
        onDismiss = { viewModel.closeTuneDialog() }
    )
}
