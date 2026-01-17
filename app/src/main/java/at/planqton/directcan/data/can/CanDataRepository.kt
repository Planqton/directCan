package at.planqton.directcan.data.can

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import at.planqton.directcan.data.dbc.DbcFile
import at.planqton.directcan.data.dbc.DbcMessage
import at.planqton.directcan.data.dbc.DbcSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "CanDataRepository"

/**
 * Global repository for CAN data - used for snapshotting and logging
 */
class CanDataRepository(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // Current CAN bus state - Map of ID to latest frame data (for snapshots)
    private val _currentFrames = MutableStateFlow<Map<Long, CanFrameData>>(emptyMap())
    val currentFrames: StateFlow<Map<Long, CanFrameData>> = _currentFrames.asStateFlow()

    // Frame list for Monitor (all frames or overwrite mode)
    private val _monitorFrames = MutableStateFlow<List<CanFrame>>(emptyList())
    val monitorFrames: StateFlow<List<CanFrame>> = _monitorFrames.asStateFlow()

    // Frame buffer for monitor
    private val monitorFrameBuffer = mutableListOf<CanFrame>()
    private val monitorIdIndex = mutableMapOf<Long, Int>()
    private var _overwriteMode = true

    // Sniffer data - Map of ID to sniffer frame data
    private val _snifferFrames = MutableStateFlow<Map<Long, SnifferFrameData>>(emptyMap())
    val snifferFrames: StateFlow<Map<Long, SnifferFrameData>> = _snifferFrames.asStateFlow()
    private val snifferDataMap = mutableMapOf<Long, SnifferFrameData>()

    // Statistics
    private val _totalFramesCaptured = MutableStateFlow(0L)
    val totalFramesCaptured: StateFlow<Long> = _totalFramesCaptured.asStateFlow()

    private val _framesPerSecond = MutableStateFlow(0)
    val framesPerSecond: StateFlow<Int> = _framesPerSecond.asStateFlow()
    private var frameCountThisSecond = 0

    // Auto-start logging preference
    private val _autoStartLogging = MutableStateFlow(false)
    val autoStartLogging: StateFlow<Boolean> = _autoStartLogging.asStateFlow()

    // Recent snapshot descriptions
    private val _recentDescriptions = MutableStateFlow<List<String>>(emptyList())
    val recentDescriptions: StateFlow<List<String>> = _recentDescriptions.asStateFlow()

    // Shared frame filter - Map of ID to enabled state (true = show, false = hide)
    private val _frameFilter = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val frameFilter: StateFlow<Map<Long, Boolean>> = _frameFilter.asStateFlow()

    // All known CAN IDs (for filter list)
    private val _knownIds = MutableStateFlow<Set<Long>>(emptySet())
    val knownIds: StateFlow<Set<Long>> = _knownIds.asStateFlow()

    // Active DBC file for signal decoding
    private var _activeDbcFile: DbcFile? = null

    // Signal values - Map of "messageId_signalName" to SignalValue
    private val _signalValues = MutableStateFlow<Map<String, SignalValue>>(emptyMap())
    val signalValues: StateFlow<Map<String, SignalValue>> = _signalValues.asStateFlow()
    private val signalValuesMap = mutableMapOf<String, SignalValue>()

    // Signal history buffer for graphing
    private val signalHistoryBuffer = SignalHistoryBuffer(maxSamples = 2000)

    // Is logging active
    private val _isLogging = MutableStateFlow(false)
    val isLogging: StateFlow<Boolean> = _isLogging.asStateFlow()

    // Available log files
    private val _logFiles = MutableStateFlow<List<LogFileInfo>>(emptyList())
    val logFiles: StateFlow<List<LogFileInfo>> = _logFiles.asStateFlow()

    // Log directory URI (user selected via folder picker)
    private val _logDirectoryUri = MutableStateFlow<Uri?>(null)
    val logDirectoryUri: StateFlow<Uri?> = _logDirectoryUri.asStateFlow()

    private val prefs = context.getSharedPreferences("directcan_prefs", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileNameDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

    init {
        Log.d(TAG, "Initializing CanDataRepository")
        _autoStartLogging.value = prefs.getBoolean("auto_start_logging", false)
        Log.d(TAG, "Auto-start logging: ${_autoStartLogging.value}")

        // Load saved log directory URI
        prefs.getString("log_directory_uri", null)?.let { uriString ->
            try {
                _logDirectoryUri.value = Uri.parse(uriString)
                Log.d(TAG, "Loaded log directory URI: $uriString")
            } catch (e: Exception) {
                Log.w(TAG, "Invalid saved log directory URI", e)
            }
        }

        // Load recent descriptions
        prefs.getStringSet("recent_descriptions", emptySet())?.let { descriptions ->
            _recentDescriptions.value = descriptions.toList().take(10)
            Log.d(TAG, "Loaded ${descriptions.size} recent descriptions")
        }

        refreshLogFiles()

        // FPS calculation
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                _framesPerSecond.value = frameCountThisSecond
                frameCountThisSecond = 0
            }
        }

        // Periodic UI update for sniffer and signals (push to state flow)
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(100)
                if (_isLogging.value) {
                    _snifferFrames.value = snifferDataMap.toMap()
                    _monitorFrames.value = monitorFrameBuffer.toList()
                    _signalValues.value = signalValuesMap.toMap()
                }
            }
        }
    }

    fun setAutoStartLogging(enabled: Boolean) {
        Log.i(TAG, "Auto-start logging set to: $enabled")
        _autoStartLogging.value = enabled
        prefs.edit().putBoolean("auto_start_logging", enabled).apply()
    }

    fun setLoggingActive(active: Boolean) {
        Log.i(TAG, "Logging active set to: $active")
        _isLogging.value = active
    }

    fun setOverwriteMode(enabled: Boolean) {
        Log.d(TAG, "Overwrite mode set to: $enabled")
        _overwriteMode = enabled
    }

    /**
     * Set the active DBC file for signal decoding
     */
    fun setActiveDbcFile(dbcFile: DbcFile?) {
        Log.i(TAG, "Active DBC file set: ${dbcFile?.let { "${it.messages.size} messages" } ?: "none"}")
        _activeDbcFile = dbcFile
        if (dbcFile == null) {
            Log.d(TAG, "Clearing signal values (DBC unloaded)")
            signalValuesMap.clear()
            _signalValues.value = emptyMap()
        }
    }

    /**
     * Get signal history samples for graphing
     */
    fun getSignalHistory(signalKey: String): List<SignalHistoryBuffer.Sample> {
        return signalHistoryBuffer.getSamples(signalKey)
    }

    /**
     * Get all available signal keys that have history
     */
    fun getAvailableSignalKeys(): Set<String> {
        return signalHistoryBuffer.getAvailableKeys()
    }

    /**
     * Clear signal history
     */
    fun clearSignalHistory() {
        Log.d(TAG, "Clearing signal history")
        signalHistoryBuffer.clear()
        signalValuesMap.clear()
        _signalValues.value = emptyMap()
    }

    // Filter methods
    fun setIdFilterEnabled(id: Long, enabled: Boolean) {
        Log.v(TAG, "Filter for ID 0x${id.toString(16).uppercase()}: $enabled")
        val current = _frameFilter.value.toMutableMap()
        current[id] = enabled
        _frameFilter.value = current
    }

    fun setAllFiltersEnabled(enabled: Boolean) {
        Log.d(TAG, "Setting all ${_knownIds.value.size} filters to: $enabled")
        val current = _frameFilter.value.toMutableMap()
        _knownIds.value.forEach { id ->
            current[id] = enabled
        }
        _frameFilter.value = current
    }

    fun isIdEnabled(id: Long): Boolean {
        // If not in filter map, default to enabled
        return _frameFilter.value[id] ?: true
    }

    fun clearFiltersOnClear(keepFilters: Boolean) {
        if (!keepFilters) {
            Log.d(TAG, "Clearing all filters and known IDs")
            _frameFilter.value = emptyMap()
            _knownIds.value = emptySet()
        }
    }

    /**
     * Restore frame filter from saved settings
     */
    fun restoreFrameFilter(filter: Map<Long, Boolean>) {
        Log.d(TAG, "Restoring frame filter: ${filter.size} entries")
        _frameFilter.value = filter
        _knownIds.value = filter.keys
    }

    /**
     * Process incoming CAN frame - updates all data structures
     */
    fun processFrame(frame: CanFrame) {
        if (!_isLogging.value) return

        _totalFramesCaptured.value++
        frameCountThisSecond++

        // Track known IDs for filtering
        if (!_knownIds.value.contains(frame.id)) {
            _knownIds.value = _knownIds.value + frame.id
            // New IDs are enabled by default
            if (!_frameFilter.value.containsKey(frame.id)) {
                val current = _frameFilter.value.toMutableMap()
                current[frame.id] = true
                _frameFilter.value = current
            }
        }

        // Update snapshot data
        updateFrame(frame)

        // Update monitor frames
        updateMonitorFrame(frame)

        // Update sniffer data
        updateSnifferFrame(frame)

        // Decode signals if DBC is active
        _activeDbcFile?.findMessage(frame.id)?.let { message ->
            updateSignalValues(frame, message)
        }
    }

    /**
     * Update signal values from decoded frame
     */
    private fun updateSignalValues(frame: CanFrame, message: DbcMessage) {
        val now = System.currentTimeMillis()
        message.decodeSignals(frame.data).forEach { (signal, value) ->
            val signalKey = "${frame.id}_${signal.name}"

            // Update current value
            val signalValue = SignalValue(
                messageId = frame.id,
                messageName = message.name,
                signalName = signal.name,
                value = value,
                unit = signal.unit,
                timestamp = now,
                valueDescription = signal.valueDescriptions[value.toInt()],
                min = signal.min,
                max = signal.max
            )
            signalValuesMap[signalKey] = signalValue

            // Add to history buffer for graphing
            signalHistoryBuffer.addSample(signalKey, frame.timestamp, value)
        }
    }

    private fun updateMonitorFrame(frame: CanFrame) {
        if (_overwriteMode) {
            val existingIndex = monitorIdIndex[frame.id]
            if (existingIndex != null && existingIndex < monitorFrameBuffer.size) {
                monitorFrameBuffer[existingIndex] = frame
            } else {
                monitorIdIndex[frame.id] = monitorFrameBuffer.size
                monitorFrameBuffer.add(frame)
            }
        } else {
            monitorFrameBuffer.add(frame)
            if (monitorFrameBuffer.size > 2000) {
                repeat(500) {
                    if (monitorFrameBuffer.isNotEmpty()) {
                        val removed = monitorFrameBuffer.removeAt(0)
                        monitorIdIndex.remove(removed.id)
                    }
                }
                monitorIdIndex.clear()
                monitorFrameBuffer.forEachIndexed { idx, f ->
                    monitorIdIndex[f.id] = idx
                }
            }
        }
    }

    private fun updateSnifferFrame(frame: CanFrame) {
        val now = System.currentTimeMillis()
        val dataSize = minOf(frame.data.size, 8)

        val existing = snifferDataMap[frame.id]
        if (existing != null) {
            val newChangeTime = existing.byteChangeTime.copyOf()
            val newChangeDir = existing.byteChangeDir.copyOf()

            for (i in 0 until dataSize) {
                if (i !in newChangeTime.indices) continue
                val prev = existing.currentData.getOrNull(i)?.toInt()?.and(0xFF) ?: 0
                val curr = frame.data.getOrNull(i)?.toInt()?.and(0xFF) ?: 0

                if (curr != prev) {
                    newChangeTime[i] = now
                    newChangeDir[i] = if (curr > prev) 1 else -1
                }
            }

            snifferDataMap[frame.id] = SnifferFrameData(
                id = frame.id,
                currentData = frame.data.copyOf(),
                previousData = existing.currentData.copyOf(),
                byteChangeTime = newChangeTime,
                byteChangeDir = newChangeDir,
                notchedBits = existing.notchedBits,
                lastUpdate = now,
                updateCount = existing.updateCount + 1
            )
        } else {
            snifferDataMap[frame.id] = SnifferFrameData(
                id = frame.id,
                currentData = frame.data.copyOf(),
                previousData = frame.data.copyOf(),
                byteChangeTime = LongArray(8) { 0L },
                byteChangeDir = IntArray(8) { 0 },
                notchedBits = BooleanArray(64) { false },
                lastUpdate = now,
                updateCount = 1
            )
        }
    }

    fun clearMonitorFrames() {
        Log.d(TAG, "Clearing monitor frames (was ${monitorFrameBuffer.size} frames)")
        monitorFrameBuffer.clear()
        monitorIdIndex.clear()
        _monitorFrames.value = emptyList()
        _totalFramesCaptured.value = 0
    }

    fun clearSnifferFrames() {
        Log.d(TAG, "Clearing sniffer frames (was ${snifferDataMap.size} IDs)")
        snifferDataMap.clear()
        _snifferFrames.value = emptyMap()
    }

    /**
     * Update frame data from incoming CAN frame (for snapshots)
     */
    private fun updateFrame(frame: CanFrame) {
        val current = _currentFrames.value.toMutableMap()
        current[frame.id] = CanFrameData(
            id = frame.id,
            data = frame.data.copyOf(),
            timestamp = frame.timestamp,
            isExtended = frame.isExtended,
            isRtr = frame.isRtr,
            direction = frame.direction,
            bus = frame.bus
        )
        _currentFrames.value = current
    }

    /**
     * Clear all current frame data
     */
    fun clearFrames() {
        _currentFrames.value = emptyMap()
    }

    /**
     * Set the log directory URI (from folder picker)
     */
    fun setLogDirectoryUri(uri: Uri) {
        Log.i(TAG, "Setting log directory URI: $uri")
        // Take persistable permission
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Log.d(TAG, "Persistable permission taken for log directory")
        } catch (e: Exception) {
            Log.w(TAG, "Could not take persistable permission (may already be held)", e)
        }

        _logDirectoryUri.value = uri
        prefs.edit().putString("log_directory_uri", uri.toString()).apply()
        refreshLogFiles()
    }

    /**
     * Clear the log directory setting
     */
    fun clearLogDirectoryUri() {
        Log.i(TAG, "Clearing log directory URI")
        _logDirectoryUri.value = null
        prefs.edit().remove("log_directory_uri").apply()
        _logFiles.value = emptyList()
    }

    /**
     * Get the display path for UI
     */
    fun getLogDirectoryDisplayPath(): String {
        val uri = _logDirectoryUri.value ?: return "Nicht festgelegt"
        return try {
            val docFile = DocumentFile.fromTreeUri(context, uri)
            docFile?.name ?: uri.lastPathSegment ?: "Unbekannt"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "Unbekannt"
        }
    }

    /**
     * Check if log directory is configured
     */
    fun isLogDirectoryConfigured(): Boolean {
        return _logDirectoryUri.value != null
    }

    /**
     * Check if we can write to the log directory
     */
    fun canWriteLogs(): Boolean {
        val uri = _logDirectoryUri.value ?: return false
        return try {
            val docFile = DocumentFile.fromTreeUri(context, uri)
            docFile?.canWrite() == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Refresh list of available log files
     */
    fun refreshLogFiles() {
        scope.launch {
            val uri = _logDirectoryUri.value
            if (uri == null) {
                Log.v(TAG, "No log directory configured, skipping refresh")
                _logFiles.value = emptyList()
                return@launch
            }

            try {
                val docFile = DocumentFile.fromTreeUri(context, uri)
                Log.d(TAG, "Refreshing log files from: ${docFile?.uri}")

                val allFiles = docFile?.listFiles() ?: emptyArray()
                Log.v(TAG, "Found ${allFiles.size} files total in log directory")

                val files = allFiles
                    .filter { it.isFile && (it.name?.contains(".log") == true) }
                    .mapNotNull { file ->
                        val fileName = file.name ?: return@mapNotNull null
                        // Remove .log or .log.txt extension
                        val name = fileName
                            .removeSuffix(".txt")
                            .removeSuffix(".log")
                        LogFileInfo(
                            name = name,
                            documentFile = file,
                            uri = file.uri,
                            size = file.length(),
                            lastModified = file.lastModified(),
                            snapshotCount = countSnapshots(file)
                        )
                    }
                    .sortedByDescending { it.lastModified }

                Log.d(TAG, "Found ${files.size} log files")
                _logFiles.value = files
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing log files", e)
                _logFiles.value = emptyList()
            }
        }
    }

    private fun countSnapshots(file: DocumentFile): Int {
        return try {
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.useLines { lines ->
                lines.count { it.startsWith("=== SNAPSHOT:") }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Create a new log file
     */
    fun createLogFile(name: String): LogFileInfo? {
        Log.i(TAG, "Creating log file: $name")
        val uri = _logDirectoryUri.value ?: run {
            Log.w(TAG, "Cannot create log file - no directory configured")
            return null
        }

        return try {
            val docFile = DocumentFile.fromTreeUri(context, uri) ?: return null
            val baseName = name.replace(" ", "_")

            // Check if file already exists (with or without extension added by system)
            val existingLog = docFile.findFile("$baseName.log")
            val existingTxt = docFile.findFile("$baseName.log.txt")
            if (existingLog != null || existingTxt != null) {
                Log.w(TAG, "Log file already exists: $baseName")
                return null
            }

            // Use application/octet-stream to prevent Android from adding .txt extension
            val newFile = docFile.createFile("application/octet-stream", "$baseName.log") ?: run {
                Log.e(TAG, "Failed to create log file")
                return null
            }

            // Write initial content
            context.contentResolver.openOutputStream(newFile.uri)?.bufferedWriter()?.use { writer ->
                writer.write("# DirectCAN Log: $name\n")
                writer.write("# Created: ${dateFormat.format(Date())}\n\n")
            }

            refreshLogFiles()
            Log.i(TAG, "Log file created successfully: $baseName")
            LogFileInfo(baseName, newFile, newFile.uri, newFile.length(), newFile.lastModified(), 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating log file", e)
            null
        }
    }

    /**
     * Delete a log file
     */
    fun deleteLogFile(logFile: LogFileInfo): Boolean {
        Log.i(TAG, "Deleting log file: ${logFile.name}")
        return try {
            val deleted = logFile.documentFile?.delete() ?: false
            if (deleted) {
                Log.i(TAG, "Log file deleted: ${logFile.name}")
            } else {
                Log.w(TAG, "Failed to delete log file: ${logFile.name}")
            }
            refreshLogFiles()
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting log file", e)
            false
        }
    }

    /**
     * Capture snapshot data immediately (before dialog)
     * Returns the captured data that can be saved later
     */
    fun captureSnapshot(): SnapshotData {
        val frames = _currentFrames.value.toMap()
        val timestamp = System.currentTimeMillis()
        Log.i(TAG, "Snapshot captured: ${frames.size} frames")
        return SnapshotData(frames, timestamp)
    }

    /**
     * Save a previously captured snapshot to log file
     */
    fun saveSnapshot(snapshot: SnapshotData, logFile: LogFileInfo, description: String): Boolean {
        Log.i(TAG, "Saving snapshot '$description' to ${logFile.name} (${snapshot.frames.size} frames)")
        return try {
            val timestamp = dateFormat.format(Date(snapshot.captureTime))

            val frames = snapshot.frames
            val sb = StringBuilder()
            sb.appendLine("=== SNAPSHOT: $description ===")
            sb.appendLine("Time: $timestamp")
            sb.appendLine("Frames: ${frames.size}")
            sb.appendLine("---")

            frames.entries.sortedBy { it.key }.forEach { (id, data) ->
                val idHex = "0x${id.toString(16).uppercase().padStart(3, '0')}"
                val dataHex = data.data.joinToString(" ") {
                    it.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0')
                }
                val ascii = data.data.map { b ->
                    val c = b.toInt().and(0xFF)
                    if (c in 32..126) c.toChar() else '.'
                }.joinToString("")

                sb.appendLine("$idHex [${data.data.size}] $dataHex | $ascii")
            }
            sb.appendLine("=== END SNAPSHOT ===")
            sb.appendLine()

            // Append to file using URI
            context.contentResolver.openOutputStream(logFile.uri, "wa")?.bufferedWriter()?.use { writer ->
                writer.write(sb.toString())
            }

            // Save description to recent descriptions
            if (description.isNotBlank() && description != "Unbenannter Snapshot") {
                addRecentDescription(description)
            }

            refreshLogFiles()
            Log.i(TAG, "Snapshot saved successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving snapshot", e)
            false
        }
    }

    /**
     * Add a description to recent descriptions list
     */
    private fun addRecentDescription(description: String) {
        val current = _recentDescriptions.value.toMutableList()
        // Remove if already exists (to move it to front)
        current.remove(description)
        // Add to front
        current.add(0, description)
        // Keep only last 10
        val updated = current.take(10)
        _recentDescriptions.value = updated
        // Persist
        prefs.edit().putStringSet("recent_descriptions", updated.toSet()).apply()
    }

    /**
     * Data class for frame storage
     */
    data class CanFrameData(
        val id: Long,
        val data: ByteArray,
        val timestamp: Long,
        val isExtended: Boolean,
        val isRtr: Boolean,
        val direction: CanFrame.Direction,
        val bus: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as CanFrameData
            return id == other.id && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /**
     * Info about a log file
     */
    data class LogFileInfo(
        val name: String,
        val documentFile: DocumentFile?,
        val uri: Uri,
        val size: Long,
        val lastModified: Long,
        val snapshotCount: Int
    ) {
        val displaySize: String
            get() = when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> "${size / 1024} KB"
                else -> "${size / (1024 * 1024)} MB"
            }
    }

    /**
     * Captured snapshot data - captured immediately, saved later
     */
    data class SnapshotData(
        val frames: Map<Long, CanFrameData>,
        val captureTime: Long
    )

    /**
     * Sniffer frame data with change tracking
     */
    data class SnifferFrameData(
        val id: Long,
        val currentData: ByteArray,
        val previousData: ByteArray,
        val byteChangeTime: LongArray,
        val byteChangeDir: IntArray,
        val notchedBits: BooleanArray,
        val lastUpdate: Long,
        val updateCount: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as SnifferFrameData
            return id == other.id && updateCount == other.updateCount
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + updateCount.hashCode()
            return result
        }
    }

    /**
     * Notch all changed bits in sniffer frames (mark current state as baseline)
     */
    fun notchSnifferChanges() {
        snifferDataMap.forEach { (id, data) ->
            val newNotched = data.notchedBits.copyOf()
            for (byteIdx in 0 until minOf(data.currentData.size, 8)) {
                val prevByte = data.previousData.getOrNull(byteIdx)?.toInt()?.and(0xFF) ?: 0
                val currByte = data.currentData.getOrNull(byteIdx)?.toInt()?.and(0xFF) ?: 0
                val changed = prevByte xor currByte
                for (bitIdx in 0 until 8) {
                    if ((changed shr bitIdx) and 1 == 1) {
                        val notchIdx = byteIdx * 8 + (7 - bitIdx)
                        if (notchIdx in newNotched.indices) {
                            newNotched[notchIdx] = true
                        }
                    }
                }
            }
            snifferDataMap[id] = data.copy(notchedBits = newNotched)
        }
        _snifferFrames.value = snifferDataMap.toMap()
    }

    /**
     * Clear all notched bits in sniffer frames
     */
    fun unNotchSnifferChanges() {
        snifferDataMap.forEach { (id, data) ->
            snifferDataMap[id] = data.copy(notchedBits = BooleanArray(64) { false })
        }
        _snifferFrames.value = snifferDataMap.toMap()
    }

    /**
     * Current signal value with metadata
     */
    data class SignalValue(
        val messageId: Long,
        val messageName: String,
        val signalName: String,
        val value: Double,
        val unit: String,
        val timestamp: Long,
        val valueDescription: String? = null,
        val min: Double = 0.0,
        val max: Double = 0.0
    ) {
        val signalKey: String get() = "${messageId}_${signalName}"

        val formattedValue: String
            get() = when {
                valueDescription != null -> "$valueDescription ($value)"
                value == value.toLong().toDouble() -> "${value.toLong()} $unit"
                else -> String.format("%.2f %s", value, unit)
            }

        val messageIdHex: String
            get() = "0x${messageId.toString(16).uppercase().padStart(3, '0')}"
    }

    /**
     * Ring buffer for signal history (for graphing)
     */
    class SignalHistoryBuffer(private val maxSamples: Int = 1000) {
        data class Sample(val timestamp: Long, val value: Double)

        private val buffers = mutableMapOf<String, ArrayDeque<Sample>>()
        private val lock = Any()

        fun addSample(signalKey: String, timestamp: Long, value: Double) {
            synchronized(lock) {
                val buffer = buffers.getOrPut(signalKey) { ArrayDeque(maxSamples) }
                buffer.addLast(Sample(timestamp, value))
                while (buffer.size > maxSamples) {
                    buffer.removeFirst()
                }
            }
        }

        fun getSamples(signalKey: String): List<Sample> {
            synchronized(lock) {
                return buffers[signalKey]?.toList() ?: emptyList()
            }
        }

        fun getAvailableKeys(): Set<String> {
            synchronized(lock) {
                return buffers.keys.toSet()
            }
        }

        fun clear() {
            synchronized(lock) {
                buffers.clear()
            }
        }

        fun clearSignal(signalKey: String) {
            synchronized(lock) {
                buffers.remove(signalKey)
            }
        }
    }
}
