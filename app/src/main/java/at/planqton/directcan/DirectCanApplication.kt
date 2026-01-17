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
import at.planqton.directcan.data.usb.UsbSerialManager
import at.planqton.directcan.service.CanLoggingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        txScriptExecutor = TxScriptExecutor(usbSerialManager, canDataRepository)
        Log.d(TAG, "All repositories initialized")

        // Install default DBC files and restore settings on startup
        scope.launch {
            installDefaultDbcFiles()
            restoreSettings()
        }

        // Auto-start logging when connected if enabled
        scope.launch {
            combine(
                usbSerialManager.connectionState,
                canDataRepository.autoStartLogging
            ) { connectionState, autoStart ->
                connectionState to autoStart
            }.collect { (connectionState, autoStart) ->
                Log.d(TAG, "Connection state: $connectionState, autoStart: $autoStart")
                if (connectionState == UsbSerialManager.ConnectionState.CONNECTED && autoStart) {
                    if (!canDataRepository.isLogging.value) {
                        Log.i(TAG, "Auto-starting logging on USB connect")
                        CanLoggingService.start(this@DirectCanApplication)
                    }
                } else if (connectionState == UsbSerialManager.ConnectionState.DISCONNECTED) {
                    if (CanLoggingService.isRunning.value) {
                        Log.i(TAG, "Stopping logging due to USB disconnect")
                        CanLoggingService.stop(this@DirectCanApplication)
                        canDataRepository.setLoggingActive(false)
                    }
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
        scope.launch {
            usbSerialManager.receivedLines.collect { line ->
                CanFrame.fromTextLine(line)?.let { frame ->
                    canDataRepository.processFrame(frame)
                }
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
    }

    companion object {
        lateinit var instance: DirectCanApplication
            private set
    }
}
