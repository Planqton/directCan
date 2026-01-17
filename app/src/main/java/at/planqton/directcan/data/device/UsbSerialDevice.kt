package at.planqton.directcan.data.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException

private const val TAG = "UsbSerialDevice"

/**
 * USB Serial CAN device implementation.
 * Supports any USB-to-serial adapter with compatible firmware.
 */
class UsbSerialDevice(
    private val context: Context,
    private val config: UsbSerialConfig
) : CanDevice {

    companion object {
        private const val ACTION_USB_PERMISSION = "at.planqton.directcan.USB_PERMISSION"
        private const val READ_TIMEOUT = 100
        private const val WRITE_TIMEOUT = 100
        private const val MAX_LINE_LENGTH = 200
    }

    override val id: String = config.id
    override val type: DeviceType = DeviceType.USB_SERIAL
    override val displayName: String = config.name

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var targetDevice: UsbDevice? = null
    private var pendingConnect: CompletableDeferred<Result<Unit>>? = null

    // State
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Data streams
    private val _receivedLines = MutableSharedFlow<String>(extraBufferCapacity = 1000)
    override val receivedLines: SharedFlow<String> = _receivedLines.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 100)
    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    // Line buffer for parsing
    private val lineBuffer = StringBuilder()
    private val bufferLock = Any()

    // USB Permission Receiver
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    Log.d(TAG, "USB permission response received")
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.i(TAG, "USB permission granted for device: ${device?.productName}")
                        device?.let {
                            scope.launch {
                                val result = connectToDevice(it)
                                pendingConnect?.complete(result)
                                pendingConnect = null
                            }
                        }
                    } else {
                        Log.w(TAG, "USB permission denied")
                        scope.launch { _errors.emit("USB permission denied") }
                        pendingConnect?.complete(Result.failure(SecurityException("USB permission denied")))
                        pendingConnect = null
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device?.vendorId == targetDevice?.vendorId &&
                        device?.productId == targetDevice?.productId) {
                        Log.i(TAG, "USB device detached")
                        scope.launch { disconnect() }
                    }
                }
            }
        }
    }

    private var receiverRegistered = false

    private fun registerReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(usbReceiver)
            receiverRegistered = false
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver", e)
        }
    }

    override suspend fun connect(): Result<Unit> {
        registerReceiver()

        _connectionState.value = ConnectionState.CONNECTING

        // Find matching USB device
        val device = findMatchingDevice()
        if (device == null) {
            _connectionState.value = ConnectionState.ERROR
            val error = "No matching USB device found"
            _errors.emit(error)
            return Result.failure(IOException(error))
        }

        targetDevice = device

        // Check permission
        return if (usbManager.hasPermission(device)) {
            connectToDevice(device)
        } else {
            // Request permission and wait for callback
            pendingConnect = CompletableDeferred()
            requestPermission(device)
            pendingConnect!!.await()
        }
    }

    private fun findMatchingDevice(): UsbDevice? {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        return if (config.vendorId != null && config.productId != null) {
            // Find specific device
            drivers.find { driver ->
                driver.device.vendorId == config.vendorId &&
                driver.device.productId == config.productId
            }?.device
        } else {
            // Auto-detect: use first available
            drivers.firstOrNull()?.device
        }
    }

    private fun requestPermission(device: UsbDevice) {
        Log.d(TAG, "Requesting permission for device: ${device.productName}")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private suspend fun connectToDevice(device: UsbDevice): Result<Unit> {
        return try {
            Log.i(TAG, "Connecting to USB device: ${device.productName}")

            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                ?: throw IOException("No driver for device")
            Log.d(TAG, "Driver found: ${driver.javaClass.simpleName}")

            val connection = usbManager.openDevice(device)
                ?: throw IOException("Cannot open device")
            Log.d(TAG, "Device opened")

            serialPort = driver.ports[0].apply {
                open(connection)
                setParameters(
                    config.baudRate,
                    config.dataBits,
                    config.stopBits,
                    config.parity
                )
                dtr = true
                rts = true
            }
            Log.d(TAG, "Serial port configured: ${config.baudRate} baud")

            startReading()
            _connectionState.value = ConnectionState.CONNECTED

            // Request firmware info
            send("i\n")
            Log.i(TAG, "USB device connected successfully")

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _connectionState.value = ConnectionState.ERROR
            _errors.emit("Connection failed: ${e.message}")
            serialPort?.close()
            serialPort = null
            Result.failure(e)
        }
    }

    private fun startReading() {
        val port = serialPort ?: return
        Log.d(TAG, "Starting serial read loop")

        ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                val text = String(data)

                // Thread-safe line parsing
                val completedLines = mutableListOf<String>()
                synchronized(bufferLock) {
                    for (char in text) {
                        if (char == '\n' || char == '\r') {
                            if (lineBuffer.isNotEmpty()) {
                                completedLines.add(lineBuffer.toString())
                                lineBuffer.clear()
                            }
                        } else {
                            lineBuffer.append(char)
                            if (lineBuffer.length > MAX_LINE_LENGTH) {
                                Log.w(TAG, "Line too long, discarding")
                                lineBuffer.clear()
                            }
                        }
                    }
                }

                // Emit outside of synchronized block
                if (completedLines.isNotEmpty()) {
                    scope.launch {
                        completedLines.forEach { line ->
                            _receivedLines.emit(line)
                        }
                    }
                }
            }

            override fun onRunError(e: Exception) {
                Log.e(TAG, "Serial read error", e)
                scope.launch {
                    _errors.emit("Read error: ${e.message}")
                    _connectionState.value = ConnectionState.ERROR
                }
            }
        })

        ioManager?.start()
    }

    override suspend fun disconnect() {
        Log.i(TAG, "Disconnecting")
        try {
            ioManager?.stop()
            ioManager = null
            serialPort?.close()
            serialPort = null
            targetDevice = null
            synchronized(bufferLock) { lineBuffer.clear() }
            _connectionState.value = ConnectionState.DISCONNECTED
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }

    override suspend fun send(data: String): Boolean {
        return try {
            serialPort?.write(data.toByteArray(), WRITE_TIMEOUT)
            Log.v(TAG, "Sent: ${data.trim()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Write error", e)
            _errors.emit("Write error: ${e.message}")
            false
        }
    }

    override suspend fun sendBytes(data: ByteArray): Boolean {
        return try {
            serialPort?.write(data, WRITE_TIMEOUT)
            Log.v(TAG, "Sent ${data.size} bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Write error", e)
            _errors.emit("Write error: ${e.message}")
            false
        }
    }

    override fun getStatusInfo(): Map<String, String> {
        val info = mutableMapOf<String, String>()
        info["Typ"] = "USB Serial"
        info["Baudrate"] = "${config.baudRate}"

        targetDevice?.let { device ->
            info["Gerät"] = device.productName ?: "Unknown"
            info["VID:PID"] = "%04X:%04X".format(device.vendorId, device.productId)
        }

        return info
    }

    override suspend fun setCanBitrate(bitrate: Int): Boolean {
        Log.d(TAG, "Setting CAN bitrate to: $bitrate")
        // Send SLCAN-style baudrate command: "b<bitrate>\n"
        return send("b$bitrate\n")
    }

    override fun dispose() {
        scope.launch { disconnect() }
        unregisterReceiver()
        scope.cancel()
    }
}
