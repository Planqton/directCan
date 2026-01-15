package at.planqton.directcan

import android.app.Application
import android.util.Log
import at.planqton.directcan.data.can.CanDataRepository
import at.planqton.directcan.data.can.CanFrame
import at.planqton.directcan.data.dbc.DbcFileInfo
import at.planqton.directcan.data.dbc.DbcRepository
import at.planqton.directcan.data.settings.SettingsRepository
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

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        instance = this

        settingsRepository = SettingsRepository(this)
        usbSerialManager = UsbSerialManager(this)
        dbcRepository = DbcRepository(this)
        canDataRepository = CanDataRepository(this)

        // Restore settings on startup
        scope.launch {
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
                if (connectionState == UsbSerialManager.ConnectionState.CONNECTED && autoStart) {
                    if (!canDataRepository.isLogging.value) {
                        // Start foreground service for background logging
                        CanLoggingService.start(this@DirectCanApplication)
                    }
                } else if (connectionState == UsbSerialManager.ConnectionState.DISCONNECTED) {
                    // Stop service when disconnected
                    if (CanLoggingService.isRunning.value) {
                        CanLoggingService.stop(this@DirectCanApplication)
                        canDataRepository.setLoggingActive(false)
                    }
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
        // Also save active DBC path to settings
        scope.launch {
            dbcRepository.activeDbcFile.collect { dbcFile ->
                canDataRepository.setActiveDbcFile(dbcFile)
            }
        }

        // Save active DBC path when it changes
        scope.launch {
            dbcRepository.activeDbc.collect { dbcInfo ->
                settingsRepository.setActiveDbcPath(dbcInfo?.path)
            }
        }

        // Save frame filter when it changes
        scope.launch {
            canDataRepository.frameFilter.collect { filter ->
                if (filter.isNotEmpty()) {
                    settingsRepository.setFrameFilter(filter)
                }
            }
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
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring settings", e)
        }
    }

    fun resetAllStats() {
        scope.launch {
            // Clear all data
            canDataRepository.clearMonitorFrames()
            canDataRepository.clearSnifferFrames()
            canDataRepository.clearSignalHistory()
            canDataRepository.clearFiltersOnClear(false)

            // Reset settings
            settingsRepository.resetAll()

            // Deactivate DBC
            // Note: We don't have a deactivate method, but clearing settings will not auto-load next time
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        usbSerialManager.destroy()
    }

    companion object {
        lateinit var instance: DirectCanApplication
            private set
    }
}
