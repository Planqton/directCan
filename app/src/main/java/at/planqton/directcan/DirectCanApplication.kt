package at.planqton.directcan

import android.app.Application
import android.util.Log
import at.planqton.directcan.data.can.CanDataRepository
import at.planqton.directcan.data.can.CanFrame
import at.planqton.directcan.data.dbc.DbcFileInfo
import at.planqton.directcan.data.dbc.DbcRepository
import at.planqton.directcan.data.gemini.AiChatRepository
import at.planqton.directcan.data.settings.SettingsRepository
import at.planqton.directcan.data.txscript.TxScriptExecutor
import at.planqton.directcan.data.txscript.TxScriptRepository
import at.planqton.directcan.data.update.UpdateRepository
import at.planqton.directcan.data.usb.UsbSerialManager
import at.planqton.directcan.data.device.ConnectionState
import at.planqton.directcan.data.device.DeviceManager
import at.planqton.directcan.service.CanLoggingService
import at.planqton.directcan.ui.components.SerialLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val TAG = "DirectCanApplication"

class DirectCanApplication : Application() {

    lateinit var usbSerialManager: UsbSerialManager
        private set

    lateinit var dbcRepository: DbcRepository
        private set

    lateinit var canDataRepository: CanDataRepository
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var aiChatRepository: AiChatRepository
        private set

    lateinit var txScriptRepository: TxScriptRepository
        private set

    lateinit var txScriptExecutor: TxScriptExecutor
        private set

    lateinit var deviceManager: DeviceManager
        private set

    lateinit var updateRepository: UpdateRepository
        private set

    // Serial Monitor State
    private val _serialMonitorVisible = MutableStateFlow(false)
    val serialMonitorVisible: StateFlow<Boolean> = _serialMonitorVisible.asStateFlow()

    private val _serialLogs = MutableStateFlow<List<SerialLogEntry>>(emptyList())
    val serialLogs: StateFlow<List<SerialLogEntry>> = _serialLogs.asStateFlow()

    private val maxSerialLogs = 500

    fun showSerialMonitor() {
        _serialMonitorVisible.value = true
    }

    fun hideSerialMonitor() {
        _serialMonitorVisible.value = false
    }

    fun addSerialLog(direction: SerialLogEntry.Direction, message: String) {
        val newLog = SerialLogEntry(direction = direction, message = message)
        _serialLogs.value = (_serialLogs.value + newLog).takeLast(maxSerialLogs)
    }

    fun clearSerialLogs() {
        _serialLogs.value = emptyList()
    }

    fun sendSerialCommand(command: String) {
        // Add to log as TX
        addSerialLog(SerialLogEntry.Direction.TX, command)
        // Send via active device
        scope.launch {
            deviceManager.activeDevice.value?.send("$command\r")
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Application onCreate - initializing DirectCAN")
        instance = this

        Log.d(TAG, "Initializing repositories...")
        settingsRepository = SettingsRepository(this)
        usbSerialManager = UsbSerialManager(this)
        dbcRepository = DbcRepository(this)
        canDataRepository = CanDataRepository(this)
        aiChatRepository = AiChatRepository(this)
        txScriptRepository = TxScriptRepository(this)
        deviceManager = DeviceManager(this)
        txScriptExecutor = TxScriptExecutor(usbSerialManager, canDataRepository, deviceManager)
        updateRepository = UpdateRepository(this)
        Log.d(TAG, "All repositories initialized")

        // Install default DBC files and restore settings on startup
        scope.launch {
            installDefaultDbcFiles()
            restoreSettings()
        }

        // Auto-start logging when connected if enabled
        scope.launch {
            combine(
                deviceManager.connectionState,
                canDataRepository.autoStartLogging
            ) { connectionState, autoStart ->
                connectionState to autoStart
            }.collect { (connectionState, autoStart) ->
                Log.d(TAG, "Connection state: $connectionState, autoStart: $autoStart")
                if (connectionState == ConnectionState.CONNECTED && autoStart) {
                    if (!canDataRepository.isLogging.value) {
                        Log.i(TAG, "Auto-starting logging on device connect")
                        CanLoggingService.start(this@DirectCanApplication)
                    }
                    // Add connection info to serial monitor
                    addSerialLog(SerialLogEntry.Direction.RX, "--- Connected ---")
                } else if (connectionState == ConnectionState.DISCONNECTED) {
                    if (CanLoggingService.isRunning.value) {
                        Log.i(TAG, "Stopping logging due to device disconnect")
                        CanLoggingService.stop(this@DirectCanApplication)
                        canDataRepository.setLoggingActive(false)
                    }
                    // Add disconnect info to serial monitor
                    addSerialLog(SerialLogEntry.Direction.RX, "--- Disconnected ---")
                }
            }
        }

        // Start/stop service whenever logging state changes (manual or auto)
        scope.launch {
            canDataRepository.isLogging.collect { isLogging ->
                Log.d(TAG, "isLogging changed: $isLogging, serviceRunning: ${CanLoggingService.isRunning.value}")
                if (isLogging && !CanLoggingService.isRunning.value) {
                    Log.i(TAG, "Starting CanLoggingService (logging activated)")
                    CanLoggingService.start(this@DirectCanApplication)
                } else if (!isLogging && CanLoggingService.isRunning.value) {
                    Log.i(TAG, "Stopping CanLoggingService (logging deactivated)")
                    CanLoggingService.stop(this@DirectCanApplication)
                }
            }
        }

        // Collect CAN frames centrally - feeds Monitor, Sniffer, and Snapshot data
        // Use DeviceManager for all device connections (USB SLCAN, Simulator)
        // Use receivedLinesWithPort for proper multi-port support
        scope.launch {
            Log.d(TAG, "Starting receivedLinesWithPort collector")
            deviceManager.receivedLinesWithPort.collect { (port, line) ->
                Log.v(TAG, "Received from port $port: ${line.take(50)}")
                // Always add to serial monitor (buffer is limited to 500)
                addSerialLog(SerialLogEntry.Direction.RX, line)
                CanFrame.fromTextLine(line, port)?.let { frame ->
                    Log.d(TAG, "Parsed frame: ID=${frame.idHex}, port=$port")
                    canDataRepository.processFrame(frame)
                } ?: Log.w(TAG, "Failed to parse line: ${line.take(50)}")
            }
        }

        // Sync active DBC file to CanDataRepository for signal decoding
        scope.launch {
            dbcRepository.activeDbcFile.collect { dbcFile ->
                Log.d(TAG, "Active DBC file changed: ${dbcFile?.description ?: "none"}")
                canDataRepository.setActiveDbcFile(dbcFile)
            }
        }

        // Save active DBC path when it changes
        scope.launch {
            dbcRepository.activeDbc.collect { dbcInfo ->
                Log.d(TAG, "Active DBC changed: ${dbcInfo?.name ?: "none"}")
                settingsRepository.setActiveDbcPath(dbcInfo?.path)
            }
        }

        // Save frame filter when it changes
        scope.launch {
            canDataRepository.frameFilter.collect { filter ->
                if (filter.isNotEmpty()) {
                    Log.v(TAG, "Frame filter updated: ${filter.size} entries")
                    settingsRepository.setFrameFilter(filter)
                }
            }
        }

        Log.i(TAG, "Application initialization complete")
    }

    private suspend fun installDefaultDbcFiles() {
        try {
            // Install Simulation DBC if not already present
            val existingDbc = dbcRepository.dbcFiles.value.find { it.name == "Simulation" }
            if (existingDbc == null) {
                val dbcContent = assets.open("dbc/Simulation.dbc").bufferedReader().readText()
                dbcRepository.importDbcFromText(dbcContent, "Simulation")
                Log.d(TAG, "Installed default Simulation DBC")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error installing default DBC files", e)
        }
    }

    private suspend fun restoreSettings() {
        try {
            // Restore active DBC
            val activeDbcPath = settingsRepository.getActiveDbcPathSync()
            if (activeDbcPath != null) {
                val dbcInfo = dbcRepository.dbcFiles.value.find { it.path == activeDbcPath }
                if (dbcInfo != null) {
                    Log.d(TAG, "Restoring active DBC: ${dbcInfo.name}")
                    dbcRepository.loadDbc(dbcInfo)
                }
            }

            // Restore frame filter
            val savedFilter = settingsRepository.getFrameFilterSync()
            if (savedFilter.isNotEmpty()) {
                Log.d(TAG, "Restoring frame filter: ${savedFilter.size} entries")
                canDataRepository.restoreFrameFilter(savedFilter)
            }

            // Load AI chat sessions
            aiChatRepository.loadChatSessions()
            aiChatRepository.initializeProvider()
            aiChatRepository.initializeModel()
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring settings", e)
        }
    }

    fun resetAllStats() {
        Log.w(TAG, "Resetting all stats and settings!")
        scope.launch {
            Log.d(TAG, "Clearing monitor frames...")
            canDataRepository.clearMonitorFrames()
            Log.d(TAG, "Clearing sniffer frames...")
            canDataRepository.clearSnifferFrames()
            Log.d(TAG, "Clearing signal history...")
            canDataRepository.clearSignalHistory()
            canDataRepository.clearFiltersOnClear(false)
            canDataRepository.clearFrames()

            Log.d(TAG, "Deactivating DBC...")
            dbcRepository.deactivateDbc()

            Log.d(TAG, "Resetting settings...")
            settingsRepository.resetAll()
            Log.i(TAG, "All stats reset complete")
        }
    }

    override fun onTerminate() {
        Log.i(TAG, "Application onTerminate")
        super.onTerminate()
        usbSerialManager.destroy()
        updateRepository.destroy()
    }

    companion object {
        lateinit var instance: DirectCanApplication
            private set
    }
}
