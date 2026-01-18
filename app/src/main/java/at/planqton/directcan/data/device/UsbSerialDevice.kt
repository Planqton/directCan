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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException

private const val TAG = "UsbSlcanDevice"

/**
 * Firmware Capabilities detected via I command
 */
data class FirmwareCapabilities(
    val name: String = "Unknown",
    val version: String = "Unknown",
    val supportsIsoTp: Boolean = false,
    val supportsSlcan: Boolean = true
)

/**
 * USB SLCAN device implementation.
 * Supports any USB-to-serial adapter with SLCAN/LAWICEL protocol firmware.
 */
class UsbSlcanDevice(
    private val context: Context,
    private val config: UsbSlcanConfig
) : CanDevice {

    companion object {
        private const val ACTION_USB_PERMISSION = "at.planqton.directcan.USB_PERMISSION"
        private const val READ_TIMEOUT = 100
        private const val WRITE_TIMEOUT = 100
        private const val MAX_LINE_LENGTH = 200
    }

    override val id: String = config.id
    override val type: DeviceType = DeviceType.USB_SLCAN
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

    // Firmware Capabilities
    private val _capabilities = MutableStateFlow(FirmwareCapabilities())
    val capabilities: StateFlow<FirmwareCapabilities> = _capabilities.asStateFlow()

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

            // Detect firmware capabilities first
            detectCapabilities()

            // SLCAN: Set bitrate from config and open CAN
            setCanBitrate(config.canBitrate)
            delay(50)
            send("O\r")   // Open CAN
            delay(50)
            Log.i(TAG, "USB device connected successfully (SLCAN) with ${config.canBitrate} baud CAN bitrate")
            Log.i(TAG, "Firmware capabilities: ${_capabilities.value}")

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
            // SLCAN: Close CAN before disconnecting
            try {
                serialPort?.write("C\r".toByteArray(), 100)
                delay(50)  // Give firmware time to process
                Log.d(TAG, "Sent SLCAN close command")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send SLCAN close command", e)
            }

            // Stop IO manager and wait for it to fully stop
            ioManager?.let { manager ->
                manager.stop()
                delay(100)  // Give ioManager time to fully stop
            }
            ioManager = null

            // Close serial port
            serialPort?.close()
            serialPort = null
            targetDevice = null
            synchronized(bufferLock) { lineBuffer.clear() }
            _connectionState.value = ConnectionState.DISCONNECTED
            Log.i(TAG, "Disconnected successfully")
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
        info["Typ"] = "USB SLCAN"
        info["Baudrate"] = "${config.baudRate}"

        targetDevice?.let { device ->
            info["Gerät"] = device.productName ?: "Unknown"
            info["VID:PID"] = "%04X:%04X".format(device.vendorId, device.productId)
        }

        return info
    }

    override suspend fun setCanBitrate(bitrate: Int): Boolean {
        Log.d(TAG, "Setting CAN bitrate to: $bitrate")
        // SLCAN bitrate codes S0-S8
        val code = when (bitrate) {
            10000 -> "S0"
            20000 -> "S1"
            50000 -> "S2"
            100000 -> "S3"
            125000 -> "S4"
            250000 -> "S5"
            500000 -> "S6"
            800000 -> "S7"
            1000000 -> "S8"
            else -> {
                Log.w(TAG, "Unsupported bitrate: $bitrate, using 500k")
                "S6"
            }
        }
        return send("$code\r")
    }

    override suspend fun sendCanFrame(id: Long, data: ByteArray, extended: Boolean): Boolean {
        // SLCAN format: t<id:3><len:1><data> or T<id:8><len:1><data>
        val command = if (extended) {
            // Extended frame: T + 8 hex chars ID + length + data
            val idHex = id.toString(16).uppercase().padStart(8, '0')
            val dataHex = data.joinToString("") { "%02X".format(it) }
            "T$idHex${data.size}$dataHex\r"
        } else {
            // Standard frame: t + 3 hex chars ID + length + data
            val idHex = id.toString(16).uppercase().padStart(3, '0')
            val dataHex = data.joinToString("") { "%02X".format(it) }
            "t$idHex${data.size}$dataHex\r"
        }
        Log.d(TAG, "Sending SLCAN frame: $command")
        return send(command)
    }

    // ============== Firmware Capabilities Detection ==============

    private suspend fun detectCapabilities() {
        Log.d(TAG, "Detecting firmware capabilities...")

        // Send info command
        send("I\r")

        // Wait for JSON response
        val response = withTimeoutOrNull(500L) {
            receivedLines.first { it.startsWith("{") }
        }

        if (response != null) {
            try {
                val json = JSONObject(response)
                _capabilities.value = FirmwareCapabilities(
                    name = json.optString("fw", "Unknown"),
                    version = json.optString("ver", "Unknown"),
                    supportsIsoTp = json.optBoolean("isotp", false),
                    supportsSlcan = json.optBoolean("slcan", true)
                )
                Log.i(TAG, "Firmware capabilities detected: ${_capabilities.value}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse firmware capabilities JSON", e)
                _capabilities.value = FirmwareCapabilities()
            }
        } else {
            Log.d(TAG, "No firmware capabilities response - standard SLCAN device")
            _capabilities.value = FirmwareCapabilities()
        }
    }

    // ============== ISO-TP Functions ==============

    /**
     * Send ISO-TP message and wait for response.
     * Only works if firmware supports ISO-TP (detected via I command).
     *
     * @param txId CAN ID to send to
     * @param rxId CAN ID to receive from
     * @param data Data to send (up to 4095 bytes)
     * @param extended Use extended 29-bit CAN IDs
     * @return Response data or null on timeout/error
     */
    suspend fun sendIsoTp(txId: Long, rxId: Long, data: ByteArray, extended: Boolean = false): ByteArray? {
        if (!_capabilities.value.supportsIsoTp) {
            Log.w(TAG, "ISO-TP not supported by firmware")
            throw UnsupportedOperationException("Firmware unterstützt kein ISO-TP")
        }

        // Format: U<txid:3><rxid:3><len:2><data> for standard
        // Format: W<txid:8><rxid:8><len:4><data> for extended
        val command = if (extended) {
            val txHex = txId.toString(16).uppercase().padStart(8, '0')
            val rxHex = rxId.toString(16).uppercase().padStart(8, '0')
            val lenHex = data.size.toString(16).uppercase().padStart(4, '0')
            val dataHex = data.joinToString("") { "%02X".format(it) }
            "W$txHex$rxHex$lenHex$dataHex\r"
        } else {
            val txHex = txId.toString(16).uppercase().padStart(3, '0')
            val rxHex = rxId.toString(16).uppercase().padStart(3, '0')
            val lenHex = data.size.toString(16).uppercase().padStart(2, '0')
            val dataHex = data.joinToString("") { "%02X".format(it) }
            "U$txHex$rxHex$lenHex$dataHex\r"
        }

        Log.d(TAG, "Sending ISO-TP: $command")
        send(command)

        // Wait for response (u...) or error (uERR:...)
        return withTimeoutOrNull(2000L) {
            receivedLines.first { it.startsWith("u") || it.startsWith("w") }.let { response ->
                Log.d(TAG, "ISO-TP response: $response")
                if (response.startsWith("uERR:") || response.startsWith("wERR:")) {
                    val error = response.substringAfter(":")
                    Log.w(TAG, "ISO-TP error: $error")
                    null
                } else {
                    // Parse response: u<rxid:3><len:4><data> or w<rxid:8><len:4><data>
                    val dataStart = if (response.startsWith("w")) 13 else 8  // w + 8 + 4 or u + 3 + 4
                    val hexData = response.substring(dataStart)
                    hexData.chunked(2)
                        .filter { it.length == 2 }
                        .map { it.toInt(16).toByte() }
                        .toByteArray()
                }
            }
        }
    }

    // ============== OBD2 Convenience Methods ==============

    /**
     * Read Diagnostic Trouble Codes (DTCs)
     * OBD2 Service 0x03
     */
    suspend fun readDtcs(): Result<List<String>> {
        return try {
            // Send OBD2 request: Service 03 (Read DTCs)
            val response = sendIsoTp(0x7DF, 0x7E8, byteArrayOf(0x03))
                ?: return Result.failure(Exception("Keine Antwort vom Steuergerät"))

            if (response.isEmpty()) {
                return Result.success(emptyList())
            }

            // Check for positive response (0x43 = 0x03 + 0x40)
            if (response.isNotEmpty() && response[0] == 0x43.toByte()) {
                val dtcs = parseDtcResponse(response)
                Log.i(TAG, "Read ${dtcs.size} DTCs: $dtcs")
                Result.success(dtcs)
            } else {
                Result.failure(Exception("Ungültige Antwort: ${response.joinToString(" ") { "%02X".format(it) }}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read DTCs", e)
            Result.failure(e)
        }
    }

    /**
     * Clear Diagnostic Trouble Codes (DTCs)
     * OBD2 Service 0x04
     */
    suspend fun clearDtcs(): Result<Boolean> {
        return try {
            val response = sendIsoTp(0x7DF, 0x7E8, byteArrayOf(0x04))
                ?: return Result.failure(Exception("Keine Antwort vom Steuergerät"))

            // Check for positive response (0x44 = 0x04 + 0x40)
            val success = response.isNotEmpty() && response[0] == 0x44.toByte()
            Log.i(TAG, "Clear DTCs: ${if (success) "Success" else "Failed"}")
            Result.success(success)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear DTCs", e)
            Result.failure(e)
        }
    }

    /**
     * Read Vehicle Identification Number (VIN)
     * OBD2 Service 0x09 PID 0x02
     */
    suspend fun readVin(): Result<String> {
        return try {
            // Service 09, PID 02 (VIN)
            val response = sendIsoTp(0x7DF, 0x7E8, byteArrayOf(0x09, 0x02))
                ?: return Result.failure(Exception("Keine Antwort vom Steuergerät"))

            // Response: 49 02 01 <VIN 17 bytes>
            if (response.size >= 4 && response[0] == 0x49.toByte() && response[1] == 0x02.toByte()) {
                // Skip header bytes (49 02 01) and get VIN
                val vinBytes = response.drop(3).take(17).toByteArray()
                val vin = String(vinBytes, Charsets.US_ASCII).trim()
                Log.i(TAG, "Read VIN: $vin")
                Result.success(vin)
            } else {
                Result.failure(Exception("Ungültige VIN Antwort"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read VIN", e)
            Result.failure(e)
        }
    }

    /**
     * Parse DTC response bytes into human-readable DTC codes
     */
    private fun parseDtcResponse(response: ByteArray): List<String> {
        val dtcs = mutableListOf<String>()

        // Skip service byte (0x43) and number of DTCs byte
        var i = 2
        while (i + 1 < response.size) {
            val byte1 = response[i].toInt() and 0xFF
            val byte2 = response[i + 1].toInt() and 0xFF

            // Skip padding (00 00)
            if (byte1 == 0 && byte2 == 0) {
                i += 2
                continue
            }

            // Parse DTC format: AABB where AA is first byte, BB is second byte
            // First 2 bits of AA determine the type: P=00, C=01, B=10, U=11
            val type = when ((byte1 shr 6) and 0x03) {
                0 -> "P"  // Powertrain
                1 -> "C"  // Chassis
                2 -> "B"  // Body
                3 -> "U"  // Network
                else -> "P"
            }

            // Next 2 bits are first digit (0-3)
            val digit1 = (byte1 shr 4) and 0x03

            // Next 4 bits are second digit (0-F)
            val digit2 = byte1 and 0x0F

            // Third and fourth digits from second byte
            val digit3 = (byte2 shr 4) and 0x0F
            val digit4 = byte2 and 0x0F

            val dtc = "$type${digit1}${digit2.toString(16).uppercase()}${digit3.toString(16).uppercase()}${digit4.toString(16).uppercase()}"
            dtcs.add(dtc)

            i += 2
        }

        return dtcs
    }

    override fun dispose() {
        scope.launch { disconnect() }
        unregisterReceiver()
        scope.cancel()
    }
}
