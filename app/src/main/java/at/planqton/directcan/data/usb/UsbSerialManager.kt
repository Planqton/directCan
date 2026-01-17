package at.planqton.directcan.data.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
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
import at.planqton.directcan.data.can.CanSimulator
import at.planqton.directcan.DirectCanApplication
import java.io.IOException
import java.util.concurrent.Executors

private const val TAG = "UsbSerialManager"

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

    // Simulator for demo mode - exposed for SimulatorScreen
    val simulator = CanSimulator()
    private var simulatorJob: Job? = null
    private var _isSimulationMode = MutableStateFlow(false)
    val isSimulationMode: StateFlow<Boolean> = _isSimulationMode.asStateFlow()

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
                    Log.d(TAG, "USB permission response received")
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.i(TAG, "USB permission granted for device: ${device?.productName}")
                        device?.let { connectToDevice(it) }
                    } else {
                        Log.w(TAG, "USB permission denied")
                        scope.launch { _errors.emit("USB permission denied") }
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB device attached")
                    refreshDeviceList()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB device detached")
                    disconnect()
                    refreshDeviceList()
                }
            }
        }
    }

    init {
        Log.d(TAG, "Initializing UsbSerialManager")
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
        Log.d(TAG, "UsbSerialManager initialized")
    }

    fun refreshDeviceList(hideSimulation: Boolean = false) {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        Log.v(TAG, "Found ${drivers.size} USB serial devices")
        val usbDevices = drivers.map { driver ->
            UsbDeviceInfo(
                device = driver.device,
                driver = driver,
                name = driver.device.productName ?: "Unknown Device",
                vendorId = driver.device.vendorId,
                productId = driver.device.productId,
                isConnected = serialPort?.device == driver.device,
                isSimulation = false
            )
        }

        // Add simulation device at the end (unless hidden)
        if (!hideSimulation) {
            val simulationDevice = UsbDeviceInfo(
                device = null,
                driver = null,
                name = "🚗 Simulations-Modus",
                vendorId = 0,
                productId = 0,
                isConnected = _isSimulationMode.value,
                isSimulation = true
            )
            _availableDevices.value = usbDevices + simulationDevice
        } else {
            _availableDevices.value = usbDevices
        }
    }

    fun connect(deviceInfo: UsbDeviceInfo) {
        Log.i(TAG, "Connect requested: ${deviceInfo.displayName}")
        if (deviceInfo.isSimulation) {
            connectToSimulation()
        } else if (deviceInfo.device != null) {
            if (usbManager.hasPermission(deviceInfo.device)) {
                connectToDevice(deviceInfo.device)
            } else {
                Log.d(TAG, "Requesting USB permission")
                requestPermission(deviceInfo.device)
            }
        }
    }

    private fun connectToSimulation() {
        scope.launch {
            try {
                Log.i(TAG, "Connecting to simulation mode")
                _connectionState.value = ConnectionState.CONNECTING
                _isSimulationMode.value = true

                // Load Simulation DBC if available
                loadSimulationDbc()

                // Start simulator
                simulator.start()
                Log.d(TAG, "Simulator started")

                // Collect simulator output and forward to receivedLines
                simulatorJob = scope.launch {
                    simulator.simulatedLines.collect { line ->
                        _receivedLines.emit(line)
                    }
                }

                _connectionState.value = ConnectionState.CONNECTED
                refreshDeviceList()
                Log.i(TAG, "Simulation mode connected")

            } catch (e: Exception) {
                Log.e(TAG, "Simulation connection failed", e)
                _connectionState.value = ConnectionState.ERROR
                _errors.emit("Simulation failed: ${e.message}")
                _isSimulationMode.value = false
            }
        }
    }

    private suspend fun loadSimulationDbc() {
        try {
            val dbcRepository = DirectCanApplication.instance.dbcRepository

            // Load Simulation DBC if it exists
            val simulationDbc = dbcRepository.dbcFiles.value.find { it.name == "Simulation" }
            if (simulationDbc != null) {
                dbcRepository.loadDbc(simulationDbc)
                Log.d(TAG, "Simulation DBC loaded")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Simulation DBC", e)
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

    private fun connectToDevice(device: UsbDevice) {
        scope.launch {
            try {
                Log.i(TAG, "Connecting to USB device: ${device.productName}")
                _connectionState.value = ConnectionState.CONNECTING

                val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                    ?: throw IOException("No driver for device")
                Log.d(TAG, "Driver found: ${driver.javaClass.simpleName}")

                val connection = usbManager.openDevice(device)
                    ?: throw IOException("Cannot open device")
                Log.d(TAG, "Device opened")

                serialPort = driver.ports[0].apply {
                    open(connection)
                    setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    dtr = true
                    rts = true
                }
                Log.d(TAG, "Serial port configured: $BAUD_RATE baud")

                startReading()
                _connectionState.value = ConnectionState.CONNECTED
                refreshDeviceList()

                // Request firmware info
                send("i\n")
                Log.i(TAG, "USB device connected successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _connectionState.value = ConnectionState.ERROR
                _errors.emit("Connection failed: ${e.message}")
                serialPort?.close()
                serialPort = null
            }
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
                            // Prevent buffer overflow - if line is too long, it's corrupted
                            if (lineBuffer.length > MAX_LINE_LENGTH) {
                                Log.w(TAG, "Line too long, discarding: ${lineBuffer.take(50)}...")
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
                Log.e(TAG, "Serial read error", e)
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
            Log.v(TAG, "Sent: ${data.trim()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Write error", e)
            scope.launch { _errors.emit("Write error: ${e.message}") }
            false
        }
    }

    fun sendBytes(data: ByteArray): Boolean {
        return try {
            serialPort?.write(data, WRITE_TIMEOUT)
            Log.v(TAG, "Sent ${data.size} bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Write error", e)
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
        Log.i(TAG, "Disconnecting...")
        // Stop simulation if active
        if (_isSimulationMode.value) {
            Log.d(TAG, "Stopping simulation")
            simulator.stop()
            simulatorJob?.cancel()
            simulatorJob = null
            _isSimulationMode.value = false
        }

        // Stop USB connection if active
        ioManager?.stop()
        ioManager = null

        try {
            serialPort?.close()
            Log.d(TAG, "Serial port closed")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing serial port", e)
        }
        serialPort = null

        _connectionState.value = ConnectionState.DISCONNECTED
        refreshDeviceList()
        Log.i(TAG, "Disconnected")
    }

    fun destroy() {
        Log.i(TAG, "Destroying UsbSerialManager")
        disconnect()
        try {
            context.unregisterReceiver(usbReceiver)
            Log.d(TAG, "USB receiver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "USB receiver already unregistered")
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
        val device: UsbDevice?,
        val driver: UsbSerialDriver?,
        val name: String,
        val vendorId: Int,
        val productId: Int,
        val isConnected: Boolean,
        val isSimulation: Boolean = false
    ) {
        val displayName: String
            get() = if (isSimulation) name else "$name (${vendorId.toString(16)}:${productId.toString(16)})"
    }
}
