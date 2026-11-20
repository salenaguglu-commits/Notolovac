package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.MusicTheory
import com.example.model.NoteLabelMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.notolovacDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "notolovac_settings"
)

data class UserSettings(
    val calibrationCents: Float = 0f,
    val calibratedC4Hz: Float = MusicTheory.STANDARD_C4_HZ.toFloat(),
    val isCalibrated: Boolean = false,
    val noteLabelMode: NoteLabelMode = NoteLabelMode.SOLFEGE,
    val metronomeBpm: Int = 90,
    val metronomeEnabled: Boolean = false,
    val beatsPerMeasure: Int = 4
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val CALIBRATION_CENTS = floatPreferencesKey("calibration_cents")
        val CALIBRATED_C4_HZ = floatPreferencesKey("calibrated_c4_hz")
        val IS_CALIBRATED = booleanPreferencesKey("is_calibrated")
        val NOTE_LABEL_MODE = stringPreferencesKey("note_label_mode")
        val METRONOME_BPM = intPreferencesKey("metronome_bpm")
        val METRONOME_ENABLED = booleanPreferencesKey("metronome_enabled")
        val BEATS_PER_MEASURE = intPreferencesKey("beats_per_measure")
    }

    val settingsFlow: Flow<UserSettings> = context.notolovacDataStore.data.map { prefs ->
        UserSettings(
            calibrationCents = prefs[Keys.CALIBRATION_CENTS] ?: 0f,
            calibratedC4Hz = prefs[Keys.CALIBRATED_C4_HZ] ?: MusicTheory.STANDARD_C4_HZ.toFloat(),
            isCalibrated = prefs[Keys.IS_CALIBRATED] ?: false,
            noteLabelMode = NoteLabelMode.fromKey(prefs[Keys.NOTE_LABEL_MODE]),
            metronomeBpm = (prefs[Keys.METRONOME_BPM] ?: 90).coerceIn(60, 140),
            metronomeEnabled = prefs[Keys.METRONOME_ENABLED] ?: false,
            beatsPerMeasure = if (prefs[Keys.BEATS_PER_MEASURE] == 3) 3 else 4
        )
    }

    suspend fun saveCalibration(cents: Float, c4Hz: Float, isCalibrated: Boolean) {
        context.notolovacDataStore.edit { prefs ->
            prefs[Keys.CALIBRATION_CENTS] = cents
            prefs[Keys.CALIBRATED_C4_HZ] = c4Hz
            prefs[Keys.IS_CALIBRATED] = isCalibrated
        }
    }

    suspend fun setNoteLabelMode(mode: NoteLabelMode) {
        context.notolovacDataStore.edit { prefs ->
            prefs[Keys.NOTE_LABEL_MODE] = mode.key
        }
    }

    suspend fun setMetronomeBpm(bpm: Int) {
        context.notolovacDataStore.edit { prefs ->
            prefs[Keys.METRONOME_BPM] = bpm.coerceIn(60, 140)
        }
    }

    suspend fun setMetronomeEnabled(enabled: Boolean) {
        context.notolovacDataStore.edit { prefs ->
            prefs[Keys.METRONOME_ENABLED] = enabled
        }
    }

    suspend fun setBeatsPerMeasure(beats: Int) {
        context.notolovacDataStore.edit { prefs ->
            prefs[Keys.BEATS_PER_MEASURE] = if (beats == 3) 3 else 4
        }
    }
}
