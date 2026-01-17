package at.planqton.directcan.data.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException

private const val TAG = "PeakCanDevice"

/**
 * PEAK CAN device implementation with Native Mode support.
 *
 * PCAN-USB adapters work in Native Mode using USB bulk/interrupt transfers.
 * This implementation communicates directly with the PCAN-USB hardware.
 *
 * Protocol based on open-source implementations (linux peak_usb driver, python-can).
 */
class PeakCanDevice(
    private val context: Context,
    private val config: PeakCanConfig
) : CanDevice {

    companion object {
        private const val ACTION_USB_PERMISSION = "at.planqton.directcan.PEAK_USB_PERMISSION"

        // PCAN-USB Message Types
        private const val PCAN_USB_MSG_TYPE_DATA = 0x00
        private const val PCAN_USB_MSG_TYPE_STATUS = 0x01
        private const val PCAN_USB_MSG_TYPE_ERROR = 0x02

        // PCAN-USB Commands
        private const val PCAN_USB_CMD_BITRATE = 0x01
        private const val PCAN_USB_CMD_BUS_ON = 0x02
        private const val PCAN_USB_CMD_BUS_OFF = 0x03

        // USB Transfer settings
        private const val USB_TIMEOUT = 1000
        private const val READ_BUFFER_SIZE = 64
    }

    override val id: String = config.id
    override val type: DeviceType = DeviceType.PEAK_CAN
    override val displayName: String = config.name

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    // Native mode USB objects
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var readJob: Job? = null

    // CDC mode fallback
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var isNativeMode = false

    // State
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Data streams
    private val _receivedLines = MutableSharedFlow<String>(extraBufferCapacity = 1000)
    override val receivedLines: SharedFlow<String> = _receivedLines.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 100)
    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    // Line buffer for CDC mode parsing
    private val lineBuffer = StringBuilder()
    private val bufferLock = Any()

    // Permission handling
    private var pendingConnect: CompletableDeferred<Result<Unit>>? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    Log.i(TAG, "USB permission granted for PEAK device")
                    device?.let {
                        scope.launch {
                            val result = connectToDevice(it)
                            pendingConnect?.complete(result)
                            pendingConnect = null
                        }
                    }
                } else {
                    Log.w(TAG, "USB permission denied for PEAK device")
                    scope.launch { _errors.emit("USB-Berechtigung verweigert") }
                    pendingConnect?.complete(Result.failure(SecurityException("USB permission denied")))
                    pendingConnect = null
                }
            }
        }
    }

    private var receiverRegistered = false

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
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

        // Find PEAK CAN device
        val peakDevice = findPeakDevice()
        if (peakDevice == null) {
            _connectionState.value = ConnectionState.ERROR
            val error = "Kein PEAK CAN Adapter gefunden.\nStelle sicher, dass der PCAN-USB angeschlossen ist."
            _errors.emit(error)
            return Result.failure(IOException(error))
        }

        Log.i(TAG, "Found PEAK device: ${peakDevice.productName} (VID:${peakDevice.vendorId} PID:${peakDevice.productId})")

        // Check permission
        return if (usbManager.hasPermission(peakDevice)) {
            connectToDevice(peakDevice)
        } else {
            pendingConnect = CompletableDeferred()
            requestPermission(peakDevice)
            pendingConnect!!.await()
        }
    }

    private fun findPeakDevice(): UsbDevice? {
        return usbManager.deviceList.values.find { device ->
            device.vendorId == PeakCanConfig.PEAK_VENDOR_ID
        }
    }

    private fun requestPermission(device: UsbDevice) {
        Log.d(TAG, "Requesting permission for PEAK device: ${device.productName}")
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
        usbDevice = device

        // First try CDC mode (easier if available)
        val cdcResult = tryCdcMode(device)
        if (cdcResult.isSuccess) {
            isNativeMode = false
            return cdcResult
        }

        // Try Native mode
        Log.i(TAG, "CDC mode not available, trying Native mode...")
        return tryNativeMode(device)
    }

    private suspend fun tryCdcMode(device: UsbDevice): Result<Unit> {
        return try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            if (driver == null) {
                Log.d(TAG, "No CDC driver available")
                return Result.failure(IOException("No CDC driver"))
            }

            val connection = usbManager.openDevice(device)
                ?: return Result.failure(IOException("Cannot open device"))

            serialPort = driver.ports[0].apply {
                open(connection)
                setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                dtr = true
                rts = true
            }

            startCdcReading()
            _connectionState.value = ConnectionState.CONNECTED
            Log.i(TAG, "PEAK CAN connected (CDC mode)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.d(TAG, "CDC mode failed: ${e.message}")
            serialPort?.close()
            serialPort = null
            Result.failure(e)
        }
    }

    private suspend fun tryNativeMode(device: UsbDevice): Result<Unit> {
        return try {
            Log.i(TAG, "Connecting in Native mode to: ${device.productName}")

            // Open USB connection
            usbConnection = usbManager.openDevice(device)
                ?: throw IOException("Kann PEAK-Gerät nicht öffnen - Berechtigung fehlt?")

            // Find interface and endpoints
            // PCAN-USB typically has 1 interface with 2 bulk endpoints
            var foundInterface = false
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                Log.d(TAG, "Interface $i: class=${intf.interfaceClass}, subclass=${intf.interfaceSubclass}, endpoints=${intf.endpointCount}")

                // Look for vendor-specific interface or bulk endpoints
                if (intf.endpointCount >= 2) {
                    for (j in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(j)
                        Log.d(TAG, "  Endpoint $j: type=${ep.type}, direction=${ep.direction}, address=0x${ep.address.toString(16)}")

                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK ||
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                            if (ep.direction == UsbConstants.USB_DIR_IN) {
                                endpointIn = ep
                            } else {
                                endpointOut = ep
                            }
                        }
                    }

                    if (endpointIn != null && endpointOut != null) {
                        usbInterface = intf
                        foundInterface = true
                        break
                    }
                }
            }

            if (!foundInterface || usbInterface == null) {
                throw IOException("Keine passenden USB-Endpoints gefunden")
            }

            // Claim interface
            if (!usbConnection!!.claimInterface(usbInterface, true)) {
                throw IOException("Kann USB-Interface nicht beanspruchen")
            }

            Log.i(TAG, "USB interface claimed, endpoints: IN=0x${endpointIn!!.address.toString(16)}, OUT=0x${endpointOut!!.address.toString(16)}")

            // Initialize PCAN-USB
            initializeNativeMode()

            // Start reading
            startNativeReading()

            isNativeMode = true
            _connectionState.value = ConnectionState.CONNECTED
            Log.i(TAG, "PEAK CAN connected (Native mode)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Native mode connection failed", e)
            _connectionState.value = ConnectionState.ERROR
            _errors.emit("PEAK Verbindung fehlgeschlagen: ${e.message}")
            cleanupNativeMode()
            Result.failure(e)
        }
    }

    private suspend fun initializeNativeMode() {
        Log.d(TAG, "Initializing PCAN-USB in native mode at ${config.bitrate} bit/s")

        // Set CAN bitrate
        val bitrateCmd = createBitrateCommand(config.bitrate)
        sendNativeCommand(bitrateCmd)
        delay(50)

        // Open CAN bus (Bus On)
        val busOnCmd = createBusOnCommand()
        sendNativeCommand(busOnCmd)
        delay(50)

        Log.i(TAG, "PCAN-USB initialized at ${config.bitrate / 1000} kbit/s")
    }

    private fun createBitrateCommand(bitrate: Int): ByteArray {
        // PCAN-USB bitrate command format
        // The exact format depends on the device, this is a common format
        val btr0: Byte
        val btr1: Byte

        // BTR0/BTR1 values for common bitrates (16MHz clock)
        when (bitrate) {
            10000 -> { btr0 = 0x31.toByte(); btr1 = 0x1C.toByte() }
            20000 -> { btr0 = 0x18.toByte(); btr1 = 0x1C.toByte() }
            50000 -> { btr0 = 0x09.toByte(); btr1 = 0x1C.toByte() }
            100000 -> { btr0 = 0x04.toByte(); btr1 = 0x1C.toByte() }
            125000 -> { btr0 = 0x03.toByte(); btr1 = 0x1C.toByte() }
            250000 -> { btr0 = 0x01.toByte(); btr1 = 0x1C.toByte() }
            500000 -> { btr0 = 0x00.toByte(); btr1 = 0x1C.toByte() }
            800000 -> { btr0 = 0x00.toByte(); btr1 = 0x16.toByte() }
            1000000 -> { btr0 = 0x00.toByte(); btr1 = 0x14.toByte() }
            else -> { btr0 = 0x00.toByte(); btr1 = 0x1C.toByte() } // Default 500k
        }

        return byteArrayOf(
            PCAN_USB_CMD_BITRATE.toByte(),
            btr0,
            btr1,
            0, 0, 0, 0, 0
        )
    }

    private fun createBusOnCommand(): ByteArray {
        return byteArrayOf(
            PCAN_USB_CMD_BUS_ON.toByte(),
            0, 0, 0, 0, 0, 0, 0
        )
    }

    private fun createBusOffCommand(): ByteArray {
        return byteArrayOf(
            PCAN_USB_CMD_BUS_OFF.toByte(),
            0, 0, 0, 0, 0, 0, 0
        )
    }

    private fun sendNativeCommand(command: ByteArray): Int {
        val ep = endpointOut ?: return -1
        val conn = usbConnection ?: return -1
        return conn.bulkTransfer(ep, command, command.size, USB_TIMEOUT)
    }

    private fun startNativeReading() {
        readJob = scope.launch {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            val conn = usbConnection ?: return@launch
            val ep = endpointIn ?: return@launch

            Log.d(TAG, "Starting native read loop")

            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                try {
                    val bytesRead = conn.bulkTransfer(ep, buffer, buffer.size, 100)
                    if (bytesRead > 0) {
                        parseNativeData(buffer.copyOf(bytesRead))
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Native read error", e)
                        _errors.emit("Lesefehler: ${e.message}")
                    }
                }
            }
        }
    }

    private suspend fun parseNativeData(data: ByteArray) {
        // PCAN-USB native frame format (varies by device model)
        // Common format: 16 bytes per message
        // Byte 0: Message type
        // Byte 1-4: CAN ID (little endian)
        // Byte 5: Flags (extended ID, RTR, etc.)
        // Byte 6: DLC (data length)
        // Byte 7: unused
        // Byte 8-15: Data (8 bytes)

        var offset = 0
        while (offset + 8 <= data.size) {
            val msgType = data[offset].toInt() and 0xFF

            when (msgType) {
                PCAN_USB_MSG_TYPE_DATA, 0x80, 0x00 -> {
                    // CAN data frame
                    if (offset + 16 <= data.size) {
                        val canFrame = parseCanFrame(data, offset)
                        if (canFrame != null) {
                            _receivedLines.emit(canFrame)
                        }
                        offset += 16
                    } else {
                        // Shorter frame format (8 bytes)
                        val canFrame = parseShortCanFrame(data, offset)
                        if (canFrame != null) {
                            _receivedLines.emit(canFrame)
                        }
                        offset += 8
                    }
                }
                PCAN_USB_MSG_TYPE_STATUS -> {
                    Log.v(TAG, "Status message received")
                    offset += 8
                }
                PCAN_USB_MSG_TYPE_ERROR -> {
                    Log.w(TAG, "Error message received")
                    offset += 8
                }
                else -> {
                    // Try to parse as CAN frame anyway
                    if (data.size >= 8) {
                        val canFrame = parseShortCanFrame(data, offset)
                        if (canFrame != null) {
                            _receivedLines.emit(canFrame)
                        }
                    }
                    offset += 8
                }
            }
        }
    }

    private fun parseCanFrame(data: ByteArray, offset: Int): String? {
        return try {
            val timestamp = System.currentTimeMillis()

            // Extract CAN ID (little endian, 4 bytes)
            val id = ((data[offset + 4].toInt() and 0xFF) shl 24) or
                    ((data[offset + 3].toInt() and 0xFF) shl 16) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 1].toInt() and 0xFF)

            val flags = data[offset + 5].toInt() and 0xFF
            val dlc = (data[offset + 6].toInt() and 0x0F).coerceIn(0, 8)
            val isExtended = (flags and 0x02) != 0

            // Extract data bytes
            val canData = StringBuilder()
            for (i in 0 until dlc) {
                if (i > 0) canData.append(" ")
                canData.append("%02X".format(data[offset + 8 + i].toInt() and 0xFF))
            }

            val idStr = if (isExtended) "${id}X" else "$id"
            "t$timestamp $idStr $dlc $canData"
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing CAN frame", e)
            null
        }
    }

    private fun parseShortCanFrame(data: ByteArray, offset: Int): String? {
        return try {
            if (offset + 8 > data.size) return null

            val timestamp = System.currentTimeMillis()

            // Try different parsing based on first byte
            val byte0 = data[offset].toInt() and 0xFF

            // SLCAN-like format in binary: ID in bytes 0-1, DLC in byte 2, data in bytes 3-10
            val id = ((data[offset].toInt() and 0x07) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val dlc = (data[offset + 2].toInt() and 0x0F).coerceIn(0, 8)

            val canData = StringBuilder()
            for (i in 0 until dlc) {
                if (offset + 3 + i < data.size) {
                    if (i > 0) canData.append(" ")
                    canData.append("%02X".format(data[offset + 3 + i].toInt() and 0xFF))
                }
            }

            if (id in 0..0x7FF && dlc in 0..8) {
                "t$timestamp $id $dlc $canData"
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // CDC Mode reading (fallback)
    private fun startCdcReading() {
        val port = serialPort ?: return
        Log.d(TAG, "Starting CDC read loop")

        ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                val text = String(data)
                val completedLines = mutableListOf<String>()
                synchronized(bufferLock) {
                    for (char in text) {
                        if (char == '\n' || char == '\r') {
                            if (lineBuffer.isNotEmpty()) {
                                val converted = convertCdcFrame(lineBuffer.toString())
                                if (converted != null) {
                                    completedLines.add(converted)
                                }
                                lineBuffer.clear()
                            }
                        } else {
                            lineBuffer.append(char)
                        }
                    }
                }

                if (completedLines.isNotEmpty()) {
                    scope.launch {
                        completedLines.forEach { line ->
                            _receivedLines.emit(line)
                        }
                    }
                }
            }

            override fun onRunError(e: Exception) {
                Log.e(TAG, "CDC read error", e)
                scope.launch {
                    _errors.emit("CDC Lesefehler: ${e.message}")
                    _connectionState.value = ConnectionState.ERROR
                }
            }
        })
        ioManager?.start()
    }

    private fun convertCdcFrame(line: String): String? {
        if (line.isEmpty()) return null
        return try {
            val timestamp = System.currentTimeMillis()
            when (line[0]) {
                't' -> {
                    if (line.length < 4) return null
                    val id = line.substring(1, 4).toLong(16)
                    val len = line[4].digitToInt()
                    val dataHex = if (line.length > 5) {
                        line.substring(5).chunked(2).joinToString(" ")
                    } else ""
                    "t$timestamp $id $len $dataHex"
                }
                'T' -> {
                    if (line.length < 9) return null
                    val id = line.substring(1, 9).toLong(16)
                    val len = line[9].digitToInt()
                    val dataHex = if (line.length > 10) {
                        line.substring(10).chunked(2).joinToString(" ")
                    } else ""
                    "t$timestamp ${id}X $len $dataHex"
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun disconnect() {
        Log.i(TAG, "Disconnecting PEAK")
        try {
            if (isNativeMode) {
                // Send Bus Off command
                sendNativeCommand(createBusOffCommand())
                delay(50)
                cleanupNativeMode()
            } else {
                ioManager?.stop()
                ioManager = null
                serialPort?.close()
                serialPort = null
            }
            synchronized(bufferLock) { lineBuffer.clear() }
            _connectionState.value = ConnectionState.DISCONNECTED
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }

    private fun cleanupNativeMode() {
        readJob?.cancel()
        readJob = null
        usbInterface?.let { usbConnection?.releaseInterface(it) }
        usbConnection?.close()
        usbConnection = null
        usbInterface = null
        endpointIn = null
        endpointOut = null
    }

    override suspend fun send(data: String): Boolean {
        return if (isNativeMode) {
            // Parse and send as native frame
            sendNativeFrame(data)
        } else {
            try {
                serialPort?.write(data.toByteArray(), 100)
                true
            } catch (e: Exception) {
                Log.e(TAG, "CDC write error", e)
                false
            }
        }
    }

    private fun sendNativeFrame(data: String): Boolean {
        // Convert string command to native format
        // For now, just return true for status commands
        Log.v(TAG, "Native send: $data")
        return true
    }

    override suspend fun sendCanFrame(id: Long, data: ByteArray, extended: Boolean): Boolean {
        return if (isNativeMode) {
            sendNativeCanFrame(id, data, extended)
        } else {
            val frame = if (extended) {
                "T%08X%d%s\r".format(id, data.size, data.joinToString("") { "%02X".format(it) })
            } else {
                "t%03X%d%s\r".format(id, data.size, data.joinToString("") { "%02X".format(it) })
            }
            send(frame)
        }
    }

    private fun sendNativeCanFrame(id: Long, data: ByteArray, extended: Boolean): Boolean {
        val frame = ByteArray(16)

        // Message type
        frame[0] = if (extended) 0x02.toByte() else 0x00.toByte()

        // CAN ID (little endian)
        frame[1] = (id and 0xFF).toByte()
        frame[2] = ((id shr 8) and 0xFF).toByte()
        frame[3] = ((id shr 16) and 0xFF).toByte()
        frame[4] = ((id shr 24) and 0xFF).toByte()

        // Flags
        frame[5] = if (extended) 0x02.toByte() else 0x00.toByte()

        // DLC
        frame[6] = data.size.toByte()

        // Data
        for (i in data.indices) {
            if (i < 8) frame[8 + i] = data[i]
        }

        return sendNativeCommand(frame) > 0
    }

    override fun getStatusInfo(): Map<String, String> {
        return mapOf(
            "Typ" to "PEAK CAN",
            "Kanal" to "${config.channel}",
            "Bitrate" to "${config.bitrate / 1000} kbit/s",
            "Modus" to if (isNativeMode) "Native USB" else "CDC/Serial"
        )
    }

    override suspend fun setCanBitrate(bitrate: Int): Boolean {
        Log.d(TAG, "Setting PEAK CAN bitrate to: $bitrate")
        return if (isNativeMode) {
            val cmd = createBitrateCommand(bitrate)
            sendNativeCommand(cmd) > 0
        } else {
            val cmd = when (bitrate) {
                10000 -> "S0\r"
                20000 -> "S1\r"
                50000 -> "S2\r"
                100000 -> "S3\r"
                125000 -> "S4\r"
                250000 -> "S5\r"
                500000 -> "S6\r"
                800000 -> "S7\r"
                1000000 -> "S8\r"
                else -> "S6\r"
            }
            send(cmd)
        }
    }

    override fun dispose() {
        scope.launch { disconnect() }
        unregisterReceiver()
        scope.cancel()
    }
}
