package at.planqton.directcan

import android.app.Application
import at.planqton.directcan.data.can.CanDataRepository
import at.planqton.directcan.data.can.CanFrame
import at.planqton.directcan.data.dbc.DbcRepository
import at.planqton.directcan.data.usb.UsbSerialManager
import at.planqton.directcan.service.CanLoggingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DirectCanApplication : Application() {

    lateinit var usbSerialManager: UsbSerialManager
        private set

    lateinit var dbcRepository: DbcRepository
        private set

    lateinit var canDataRepository: CanDataRepository
        private set

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        instance = this

        usbSerialManager = UsbSerialManager(this)
        dbcRepository = DbcRepository(this)
        canDataRepository = CanDataRepository(this)

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
        scope.launch {
            dbcRepository.activeDbcFile.collect { dbcFile ->
                canDataRepository.setActiveDbcFile(dbcFile)
            }
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
