package at.planqton.directcan.data.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.Executors

class UsbSerialManager(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "at.planqton.directcan.USB_PERMISSION"
        private const val BAUD_RATE = 2000000  // 2 Mbit/s wie in Firmware
        private const val READ_TIMEOUT = 100
        private const val WRITE_TIMEOUT = 100
        private const val MAX_LINE_LENGTH = 200  // CAN frame line should never be this long
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // State flows
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val availableDevices: StateFlow<List<UsbDeviceInfo>> = _availableDevices.asStateFlow()

    private val _receivedData = MutableSharedFlow<String>(extraBufferCapacity = 1000)
    val receivedData: SharedFlow<String> = _receivedData.asSharedFlow()

    private val _receivedLines = MutableSharedFlow<String>(extraBufferCapacity = 1000)
    val receivedLines: SharedFlow<String> = _receivedLines.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val lineBuffer = StringBuilder()
    private val bufferLock = Any()

    // USB Permission Receiver
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { connectToDevice(it) }
                    } else {
                        scope.launch { _errors.emit("USB permission denied") }
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    refreshDeviceList()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    disconnect()
                    refreshDeviceList()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }

        refreshDeviceList()
    }

    fun refreshDeviceList() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val devices = drivers.map { driver ->
            UsbDeviceInfo(
                device = driver.device,
                driver = driver,
                name = driver.device.productName ?: "Unknown Device",
                vendorId = driver.device.vendorId,
                productId = driver.device.productId,
                isConnected = serialPort?.device == driver.device
            )
        }
        _availableDevices.value = devices
    }

    fun connect(deviceInfo: UsbDeviceInfo) {
        if (usbManager.hasPermission(deviceInfo.device)) {
            connectToDevice(deviceInfo.device)
        } else {
            requestPermission(deviceInfo.device)
        }
    }

    private fun requestPermission(device: UsbDevice) {
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

    private fun connectToDevice(device: UsbDevice) {
        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING

                val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                    ?: throw IOException("No driver for device")

                val connection = usbManager.openDevice(device)
                    ?: throw IOException("Cannot open device")

                serialPort = driver.ports[0].apply {
                    open(connection)
                    setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    dtr = true
                    rts = true
                }

                startReading()
                _connectionState.value = ConnectionState.CONNECTED
                refreshDeviceList()

                // Request firmware info
                send("i\n")

            } catch (e: Exception) {
                _connectionState.value = ConnectionState.ERROR
                _errors.emit("Connection failed: ${e.message}")
                serialPort?.close()
                serialPort = null
            }
        }
    }

    private fun startReading() {
        val port = serialPort ?: return

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
                            // Prevent buffer overflow - if line is too long, it's corrupted
                            if (lineBuffer.length > MAX_LINE_LENGTH) {
                                android.util.Log.w("UsbSerial", "Line too long, discarding: ${lineBuffer.take(50)}...")
                                lineBuffer.clear()
                            }
                        }
                    }
                }

                // Emit outside of synchronized block
                if (completedLines.isNotEmpty() || text.isNotEmpty()) {
                    scope.launch {
                        _receivedData.emit(text)
                        completedLines.forEach { line ->
                            _receivedLines.emit(line)
                        }
                    }
                }
            }

            override fun onRunError(e: Exception) {
                scope.launch {
                    _errors.emit("Read error: ${e.message}")
                    _connectionState.value = ConnectionState.ERROR
                }
            }
        })

        ioManager?.start()
    }

    fun send(data: String): Boolean {
        return try {
            serialPort?.write(data.toByteArray(), WRITE_TIMEOUT)
            true
        } catch (e: Exception) {
            scope.launch { _errors.emit("Write error: ${e.message}") }
            false
        }
    }

    fun sendBytes(data: ByteArray): Boolean {
        return try {
            serialPort?.write(data, WRITE_TIMEOUT)
            true
        } catch (e: Exception) {
            scope.launch { _errors.emit("Write error: ${e.message}") }
            false
        }
    }

    // Firmware commands
    fun sendCanFrame(id: Long, data: ByteArray, extended: Boolean = false) {
        val idHex = id.toString(16).uppercase()
        val extMarker = if (extended) "X" else ""
        val dataHex = data.joinToString(" ") { it.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0') }
        send("s$idHex$extMarker ${data.size} $dataHex\n")
    }

    fun sendIsoTp(txId: Long, rxId: Long, data: ByteArray) {
        val txHex = txId.toString(16).uppercase()
        val rxHex = rxId.toString(16).uppercase()
        val dataHex = data.joinToString(" ") { it.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0') }
        send("u$txHex $rxHex ${data.size} $dataHex\n")
    }

    // Convenience overload for ECU requests
    fun sendIsoTp(requestId: Int, data: ByteArray) {
        // Standard OBD-II: Response ID is Request ID + 8
        val responseId = requestId + 8
        sendIsoTp(requestId.toLong(), responseId.toLong(), data)
    }

    fun setBaudrate(baudrate: Int) {
        send("b$baudrate\n")
    }

    fun startLogging() {
        send("l1\n")
    }

    fun stopLogging() {
        send("l0\n")
    }

    fun setLoopback(enabled: Boolean) {
        send(if (enabled) "k1\n" else "k0\n")
    }

    fun getStatus() {
        send("?\n")
    }

    fun getInfo() {
        send("i\n")
    }

    fun disconnect() {
        ioManager?.stop()
        ioManager = null

        try {
            serialPort?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serialPort = null

        _connectionState.value = ConnectionState.DISCONNECTED
        refreshDeviceList()
    }

    fun destroy() {
        disconnect()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        executor.shutdown()
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    data class UsbDeviceInfo(
        val device: UsbDevice,
        val driver: UsbSerialDriver,
        val name: String,
        val vendorId: Int,
        val productId: Int,
        val isConnected: Boolean
    ) {
        val displayName: String
            get() = "$name (${vendorId.toString(16)}:${productId.toString(16)})"
    }
}
