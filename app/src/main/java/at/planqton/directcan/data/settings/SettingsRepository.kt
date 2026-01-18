package at.planqton.directcan.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    // Keys
    private object Keys {
        val ACTIVE_DBC_PATH = stringPreferencesKey("active_dbc_path")
        val MONITOR_OVERWRITE_MODE = booleanPreferencesKey("monitor_overwrite_mode")
        val MONITOR_AUTO_SCROLL = booleanPreferencesKey("monitor_auto_scroll")
        val MONITOR_INTERPRET_FRAMES = booleanPreferencesKey("monitor_interpret_frames")
        val MONITOR_KEEP_FILTERS = booleanPreferencesKey("monitor_keep_filters")
        val MONITOR_EXPANDED_ROWS = booleanPreferencesKey("monitor_expanded_rows")
        val SNIFFER_HIGHLIGHT_DURATION = floatPreferencesKey("sniffer_highlight_duration")
        val SNIFFER_NEVER_EXPIRE = booleanPreferencesKey("sniffer_never_expire")
        val SNIFFER_MUTE_NOTCHED = booleanPreferencesKey("sniffer_mute_notched")
        val AUTO_START_LOGGING = booleanPreferencesKey("auto_start_logging")
        val FRAME_FILTER_JSON = stringPreferencesKey("frame_filter_json")
        val HIDE_SIMULATION_MODE = booleanPreferencesKey("hide_simulation_mode")
        val LANGUAGE = stringPreferencesKey("language")
        // Dev settings
        val DEV_LOG_ENABLED = booleanPreferencesKey("dev_log_enabled")
        val DEV_LOG_INTERVAL_MINUTES = intPreferencesKey("dev_log_interval_minutes")
        // Port colors (stored as ARGB Long)
        val PORT_1_COLOR = longPreferencesKey("port_1_color")
        val PORT_2_COLOR = longPreferencesKey("port_2_color")
    }

    // Default port colors
    companion object {
        const val DEFAULT_PORT_1_COLOR = 0xFF4CAF50L  // Green
        const val DEFAULT_PORT_2_COLOR = 0xFF2196F3L  // Blue
    }

    // Language setting: "system", "en", "de"
    val language: Flow<String> = context.dataStore.data.map { it[Keys.LANGUAGE] ?: "system" }

    suspend fun setLanguage(lang: String) {
        context.dataStore.edit { it[Keys.LANGUAGE] = lang }
    }

    suspend fun getLanguageSync(): String = context.dataStore.data.first()[Keys.LANGUAGE] ?: "system"

    // Active DBC
    val activeDbcPath: Flow<String?> = context.dataStore.data.map { it[Keys.ACTIVE_DBC_PATH] }

    suspend fun setActiveDbcPath(path: String?) {
        context.dataStore.edit { prefs ->
            if (path != null) {
                prefs[Keys.ACTIVE_DBC_PATH] = path
            } else {
                prefs.remove(Keys.ACTIVE_DBC_PATH)
            }
        }
    }

    // Monitor Settings
    val monitorOverwriteMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.MONITOR_OVERWRITE_MODE] ?: true }
    val monitorAutoScroll: Flow<Boolean> = context.dataStore.data.map { it[Keys.MONITOR_AUTO_SCROLL] ?: true }
    val monitorInterpretFrames: Flow<Boolean> = context.dataStore.data.map { it[Keys.MONITOR_INTERPRET_FRAMES] ?: true }
    val monitorKeepFilters: Flow<Boolean> = context.dataStore.data.map { it[Keys.MONITOR_KEEP_FILTERS] ?: false }
    val monitorExpandedRows: Flow<Boolean> = context.dataStore.data.map { it[Keys.MONITOR_EXPANDED_ROWS] ?: false }

    suspend fun setMonitorOverwriteMode(value: Boolean) {
        context.dataStore.edit { it[Keys.MONITOR_OVERWRITE_MODE] = value }
    }

    suspend fun setMonitorAutoScroll(value: Boolean) {
        context.dataStore.edit { it[Keys.MONITOR_AUTO_SCROLL] = value }
    }

    suspend fun setMonitorInterpretFrames(value: Boolean) {
        context.dataStore.edit { it[Keys.MONITOR_INTERPRET_FRAMES] = value }
    }

    suspend fun setMonitorKeepFilters(value: Boolean) {
        context.dataStore.edit { it[Keys.MONITOR_KEEP_FILTERS] = value }
    }

    suspend fun setMonitorExpandedRows(value: Boolean) {
        context.dataStore.edit { it[Keys.MONITOR_EXPANDED_ROWS] = value }
    }

    // Sniffer Settings
    val snifferHighlightDuration: Flow<Float> = context.dataStore.data.map { it[Keys.SNIFFER_HIGHLIGHT_DURATION] ?: 500f }
    val snifferNeverExpire: Flow<Boolean> = context.dataStore.data.map { it[Keys.SNIFFER_NEVER_EXPIRE] ?: true }
    val snifferMuteNotched: Flow<Boolean> = context.dataStore.data.map { it[Keys.SNIFFER_MUTE_NOTCHED] ?: false }

    suspend fun setSnifferHighlightDuration(value: Float) {
        context.dataStore.edit { it[Keys.SNIFFER_HIGHLIGHT_DURATION] = value }
    }

    suspend fun setSnifferNeverExpire(value: Boolean) {
        context.dataStore.edit { it[Keys.SNIFFER_NEVER_EXPIRE] = value }
    }

    suspend fun setSnifferMuteNotched(value: Boolean) {
        context.dataStore.edit { it[Keys.SNIFFER_MUTE_NOTCHED] = value }
    }

    // Auto-start logging
    val autoStartLogging: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_START_LOGGING] ?: false }

    suspend fun setAutoStartLogging(value: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_START_LOGGING] = value }
    }

    // Hide simulation mode
    val hideSimulationMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.HIDE_SIMULATION_MODE] ?: false }

    suspend fun setHideSimulationMode(value: Boolean) {
        context.dataStore.edit { it[Keys.HIDE_SIMULATION_MODE] = value }
    }

    // Dev settings - periodic status logging
    val devLogEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.DEV_LOG_ENABLED] ?: true }
    val devLogIntervalMinutes: Flow<Int> = context.dataStore.data.map { it[Keys.DEV_LOG_INTERVAL_MINUTES] ?: 5 }

    suspend fun setDevLogEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.DEV_LOG_ENABLED] = value }
    }

    suspend fun setDevLogIntervalMinutes(value: Int) {
        context.dataStore.edit { it[Keys.DEV_LOG_INTERVAL_MINUTES] = value }
    }

    suspend fun getDevLogEnabledSync(): Boolean = context.dataStore.data.first()[Keys.DEV_LOG_ENABLED] ?: true
    suspend fun getDevLogIntervalMinutesSync(): Int = context.dataStore.data.first()[Keys.DEV_LOG_INTERVAL_MINUTES] ?: 5

    // Frame filter (as JSON map of Long -> Boolean)
    val frameFilter: Flow<Map<Long, Boolean>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[Keys.FRAME_FILTER_JSON]
        if (jsonStr != null) {
            try {
                json.decodeFromString<Map<String, Boolean>>(jsonStr)
                    .mapKeys { it.key.toLong() }
            } catch (e: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
    }

    suspend fun setFrameFilter(filter: Map<Long, Boolean>) {
        val jsonStr = json.encodeToString(filter.mapKeys { it.key.toString() })
        context.dataStore.edit { it[Keys.FRAME_FILTER_JSON] = jsonStr }
    }

    // Reset all settings
    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
    }

    // Get current values (for initialization)
    suspend fun getActiveDbcPathSync(): String? = context.dataStore.data.first()[Keys.ACTIVE_DBC_PATH]
    suspend fun getAutoStartLoggingSync(): Boolean = context.dataStore.data.first()[Keys.AUTO_START_LOGGING] ?: false
    suspend fun getFrameFilterSync(): Map<Long, Boolean> {
        val jsonStr = context.dataStore.data.first()[Keys.FRAME_FILTER_JSON]
        return if (jsonStr != null) {
            try {
                json.decodeFromString<Map<String, Boolean>>(jsonStr)
                    .mapKeys { it.key.toLong() }
            } catch (e: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
    }

    // Port color settings
    val port1Color: Flow<Long> = context.dataStore.data.map {
        it[Keys.PORT_1_COLOR] ?: DEFAULT_PORT_1_COLOR
    }

    val port2Color: Flow<Long> = context.dataStore.data.map {
        it[Keys.PORT_2_COLOR] ?: DEFAULT_PORT_2_COLOR
    }

    suspend fun setPort1Color(color: Long) {
        context.dataStore.edit { it[Keys.PORT_1_COLOR] = color }
    }

    suspend fun setPort2Color(color: Long) {
        context.dataStore.edit { it[Keys.PORT_2_COLOR] = color }
    }

    suspend fun getPort1ColorSync(): Long = context.dataStore.data.first()[Keys.PORT_1_COLOR] ?: DEFAULT_PORT_1_COLOR
    suspend fun getPort2ColorSync(): Long = context.dataStore.data.first()[Keys.PORT_2_COLOR] ?: DEFAULT_PORT_2_COLOR
}
