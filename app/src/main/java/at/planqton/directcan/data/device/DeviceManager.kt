package at.planqton.directcan.data.device

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

private const val TAG = "DeviceManager"

private val Context.deviceDataStore: DataStore<Preferences> by preferencesDataStore(name = "device_manager")

/**
 * Information about a detected USB device
 */
data class UsbDeviceInfo(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val serialNumber: String? = null
)

/**
 * Result of a connection test
 */
data class ConnectionTestResult(
    val success: Boolean,
    val message: String,
    val info: Map<String, String> = emptyMap()
)

/**
 * Central manager for all CAN devices.
 * Handles device configuration persistence, connection management,
 * and provides unified data streams.
 */
class DeviceManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        classDiscriminator = "_class"  // Avoid conflict with 'type' property
        serializersModule = SerializersModule {
            polymorphic(DeviceConfig::class) {
                subclass(UsbSlcanConfig::class)
                subclass(SimulatorConfig::class)
            }
        }
    }

    private object Keys {
        val DEVICE_CONFIGS = stringPreferencesKey("device_configs")
        val LAST_CONNECTED_DEVICE = stringPreferencesKey("last_connected_device")
        val CAN_BITRATE = intPreferencesKey("can_bitrate")
    }

    // Device configurations (persisted)
    private val _deviceConfigs = MutableStateFlow<List<DeviceConfig>>(emptyList())
    val deviceConfigs: StateFlow<List<DeviceConfig>> = _deviceConfigs.asStateFlow()

    // Active device instances (created from configs)
    private val _devices = MutableStateFlow<Map<String, CanDevice>>(emptyMap())
    val devices: StateFlow<List<CanDevice>> = _devices.map { it.values.toList() }.stateIn(
        scope, SharingStarted.Eagerly, emptyList()
    )

    // Currently connected devices (port -> device), max 2 ports
    private val _activeDevices = MutableStateFlow<Map<Int, CanDevice>>(emptyMap())
    val activeDevices: StateFlow<Map<Int, CanDevice>> = _activeDevices.asStateFlow()

    // Number of connected devices
    val connectedDeviceCount: StateFlow<Int> = _activeDevices.map { it.size }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    // Legacy single-device access (returns first connected device)
    val activeDevice: StateFlow<CanDevice?> = _activeDevices.map { devices ->
        devices.values.firstOrNull()
    }.stateIn(scope, SharingStarted.Eagerly, null)

    // Connection state (CONNECTED if at least one device is in activeDevices and connected)
    val connectionState: StateFlow<ConnectionState> = _activeDevices.map { devices ->
        if (devices.isEmpty()) {
            ConnectionState.DISCONNECTED
        } else {
            // Check device states directly
            val states = devices.values.map { it.connectionState.value }
            when {
                states.any { it == ConnectionState.CONNECTED } -> ConnectionState.CONNECTED
                states.any { it == ConnectionState.CONNECTING } -> ConnectionState.CONNECTING
                states.any { it == ConnectionState.ERROR } -> ConnectionState.ERROR
                else -> ConnectionState.DISCONNECTED
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.DISCONNECTED)

    // Unified data stream from all active devices (legacy, without port info)
    private val _receivedLines = MutableSharedFlow<String>(extraBufferCapacity = 1000)
    val receivedLines: SharedFlow<String> = _receivedLines.asSharedFlow()

    // Data stream with port info: Pair(port, line)
    private val _receivedLinesWithPort = MutableSharedFlow<Pair<Int, String>>(extraBufferCapacity = 1000)
    val receivedLinesWithPort: SharedFlow<Pair<Int, String>> = _receivedLinesWithPort.asSharedFlow()

    // Error stream from all active devices
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    // CAN bus bitrate (global setting)
    private val _canBitrate = MutableStateFlow(500000)  // Default 500 kbit/s
    val canBitrate: StateFlow<Int> = _canBitrate.asStateFlow()

    // USB Manager for device detection
    private val usbManager: UsbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    /**
     * Get the next available port number (1 or 2).
     * Returns null if both ports are occupied.
     */
    private fun getNextAvailablePort(): Int? {
        val usedPorts = _activeDevices.value.keys
        return when {
            1 !in usedPorts -> 1
            2 !in usedPorts -> 2
            else -> null  // Both ports occupied
        }
    }

    /**
     * Get the port number for a connected device
     */
    fun getPortForDevice(deviceId: String): Int? {
        return _activeDevices.value.entries.find { it.value.id == deviceId }?.key
    }

    /**
     * Get the device connected to a specific port
     */
    fun getDeviceOnPort(port: Int): CanDevice? {
        return _activeDevices.value[port]
    }

    init {
        // Load saved configurations and settings
        scope.launch {
            loadDeviceConfigs()
            loadCanBitrate()
        }
    }

    /**
     * Load saved CAN bitrate from DataStore
     */
    private suspend fun loadCanBitrate() {
        try {
            context.deviceDataStore.data.first()[Keys.CAN_BITRATE]?.let { bitrate ->
                _canBitrate.value = bitrate
                Log.d(TAG, "Loaded CAN bitrate: $bitrate")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading CAN bitrate", e)
        }
    }

    /**
     * Load device configurations from DataStore
     */
    private suspend fun loadDeviceConfigs() {
        try {
            context.deviceDataStore.data.first()[Keys.DEVICE_CONFIGS]?.let { jsonStr ->
                val configList = json.decodeFromString<DeviceConfigList>(jsonStr)
                _deviceConfigs.value = configList.devices
                Log.i(TAG, "Loaded ${configList.devices.size} device configs")

                // Create device instances for each config
                configList.devices.forEach { config ->
                    createDeviceFromConfig(config)
                }
            }

            // Ensure simulator is always available
            if (_deviceConfigs.value.none { it.type == DeviceType.SIMULATOR }) {
                addDevice(SimulatorConfig.DEFAULT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading device configs", e)
            // Add default simulator if loading fails
            addDevice(SimulatorConfig.DEFAULT)
        }
    }

    /**
     * Save device configurations to DataStore
     */
    private suspend fun saveDeviceConfigs() {
        try {
            val configList = DeviceConfigList(_deviceConfigs.value)
            val jsonStr = json.encodeToString(configList)
            context.deviceDataStore.edit { prefs ->
                prefs[Keys.DEVICE_CONFIGS] = jsonStr
            }
            Log.d(TAG, "Saved ${_deviceConfigs.value.size} device configs")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving device configs", e)
        }
    }

    /**
     * Create a CanDevice instance from a config
     */
    private fun createDeviceFromConfig(config: DeviceConfig): CanDevice? {
        val device = when (config) {
            is UsbSlcanConfig -> UsbSlcanDevice(context, config)
            is SimulatorConfig -> SimulatorDevice(config)
        }

        _devices.value = _devices.value + (config.id to device)

        // Subscribe to device data streams
        scope.launch {
            device.receivedLines.collect { line ->
                // Check if this device is connected on any port
                val port = getPortForDevice(device.id)
                Log.i(TAG, "RX from ${device.id}: port=$port, activeDevices=${_activeDevices.value.keys}, line=${line.take(40)}")

                // Always emit data if we have any port (fallback to port 1)
                val effectivePort = port ?: 1
                _receivedLinesWithPort.emit(effectivePort to line)
                _receivedLines.emit(line)
            }
        }
        scope.launch {
            device.errors.collect { error ->
                // Check if this device is connected on any port
                val port = getPortForDevice(device.id)
                if (port != null) {
                    _errors.emit(error)
                }
            }
        }

        return device
    }

    /**
     * Add a new device configuration
     */
    suspend fun addDevice(config: DeviceConfig) {
        _deviceConfigs.value = _deviceConfigs.value + config
        createDeviceFromConfig(config)
        saveDeviceConfigs()
        Log.i(TAG, "Added device: ${config.name} (${config.type})")
    }

    /**
     * Update an existing device configuration
     */
    suspend fun updateDevice(config: DeviceConfig) {
        // Disconnect if this device is active on any port
        val port = getPortForDevice(config.id)
        if (port != null) {
            disconnectPort(port)
        }

        // Remove old device instance
        _devices.value[config.id]?.dispose()
        _devices.value = _devices.value - config.id

        // Update config
        _deviceConfigs.value = _deviceConfigs.value.map {
            if (it.id == config.id) config else it
        }

        // Create new device instance
        createDeviceFromConfig(config)
        saveDeviceConfigs()
        Log.i(TAG, "Updated device: ${config.name}")
    }

    /**
     * Update the CAN bitrate for a specific device configuration.
     * This only updates the config; the actual bitrate is applied when connecting.
     */
    suspend fun updateDeviceBitrate(deviceId: String, bitrate: Int) {
        val config = _deviceConfigs.value.find { it.id == deviceId } ?: return

        if (config is UsbSlcanConfig) {
            val updatedConfig = config.copy(canBitrate = bitrate)
            _deviceConfigs.value = _deviceConfigs.value.map {
                if (it.id == deviceId) updatedConfig else it
            }

            // Also update the device instance so changes are reflected immediately
            _devices.value[deviceId]?.let { device ->
                _devices.value = _devices.value - deviceId
                createDeviceFromConfig(updatedConfig)
            }

            saveDeviceConfigs()
            Log.d(TAG, "Updated bitrate for ${config.name}: $bitrate")
        }
    }

    /**
     * Remove a device configuration
     */
    suspend fun removeDevice(deviceId: String) {
        // Disconnect if this device is active on any port
        val port = getPortForDevice(deviceId)
        if (port != null) {
            disconnectPort(port)
        }

        // Remove device instance
        _devices.value[deviceId]?.dispose()
        _devices.value = _devices.value - deviceId

        // Remove config
        _deviceConfigs.value = _deviceConfigs.value.filter { it.id != deviceId }
        saveDeviceConfigs()
        Log.i(TAG, "Removed device: $deviceId")
    }

    /**
     * Connect to a device by ID.
     * Returns the assigned port number (1 or 2) on success.
     */
    suspend fun connect(deviceId: String): Result<Int> {
        val device = _devices.value[deviceId]
            ?: return Result.failure(IllegalArgumentException("Device not found: $deviceId"))

        // Check if device is already connected
        val existingPort = getPortForDevice(deviceId)
        if (existingPort != null) {
            Log.w(TAG, "Device already connected on port $existingPort: ${device.displayName}")
            return Result.success(existingPort)
        }

        // Get next available port
        val port = getNextAvailablePort()
            ?: return Result.failure(IllegalStateException("Maximal 2 Geräte können gleichzeitig verbunden sein"))

        Log.i(TAG, "Connecting to device: ${device.displayName} on port $port")
        val result = device.connect()

        if (result.isSuccess) {
            // Set the CAN bitrate from the device config
            val config = getDeviceConfig(deviceId)
            if (config is UsbSlcanConfig) {
                Log.d(TAG, "Setting CAN bitrate for ${device.displayName}: ${config.canBitrate}")
                device.setCanBitrate(config.canBitrate)
            }

            _activeDevices.value = _activeDevices.value + (port to device)
            // Save as last connected device
            context.deviceDataStore.edit { prefs ->
                prefs[Keys.LAST_CONNECTED_DEVICE] = deviceId
            }
            Log.i(TAG, "Connected to: ${device.displayName} on port $port")
            return Result.success(port)
        } else {
            Log.e(TAG, "Connection failed: ${result.exceptionOrNull()?.message}")
            return Result.failure(result.exceptionOrNull() ?: Exception("Connection failed"))
        }
    }

    /**
     * Disconnect from all devices
     */
    suspend fun disconnect() {
        _activeDevices.value.forEach { (port, device) ->
            Log.i(TAG, "Disconnecting from: ${device.displayName} (port $port)")
            device.disconnect()
        }
        _activeDevices.value = emptyMap()
    }

    /**
     * Disconnect a specific port
     */
    suspend fun disconnectPort(port: Int) {
        val device = _activeDevices.value[port] ?: return
        Log.i(TAG, "Disconnecting port $port: ${device.displayName}")
        device.disconnect()
        _activeDevices.value = _activeDevices.value - port
    }

    /**
     * Disconnect a specific device by ID
     */
    suspend fun disconnectDevice(deviceId: String) {
        val port = getPortForDevice(deviceId) ?: return
        disconnectPort(port)
    }

    /**
     * Send data through the first active device (legacy)
     */
    suspend fun send(data: String): Boolean {
        return _activeDevices.value.values.firstOrNull()?.send(data) ?: false
    }

    /**
     * Send data through a specific port
     */
    suspend fun sendToPort(port: Int, data: String): Boolean {
        return _activeDevices.value[port]?.send(data) ?: false
    }

    /**
     * Send data through multiple ports
     */
    suspend fun sendToPorts(ports: Set<Int>, data: String): Map<Int, Boolean> {
        return ports.associateWith { port ->
            _activeDevices.value[port]?.send(data) ?: false
        }
    }

    /**
     * Send a CAN frame through the first active device (legacy)
     */
    suspend fun sendCanFrame(id: Long, data: ByteArray, extended: Boolean = false): Boolean {
        return _activeDevices.value.values.firstOrNull()?.sendCanFrame(id, data, extended) ?: false
    }

    /**
     * Send a CAN frame to a specific port
     */
    suspend fun sendCanFrameToPort(port: Int, id: Long, data: ByteArray, extended: Boolean = false): Boolean {
        return _activeDevices.value[port]?.sendCanFrame(id, data, extended) ?: false
    }

    /**
     * Send a CAN frame to multiple ports
     */
    suspend fun sendCanFrameToPorts(ports: Set<Int>, id: Long, data: ByteArray, extended: Boolean = false): Map<Int, Boolean> {
        return ports.associateWith { port ->
            _activeDevices.value[port]?.sendCanFrame(id, data, extended) ?: false
        }
    }

    /**
     * Scan for available USB devices
     */
    fun scanUsbDevices(): List<UsbDeviceInfo> {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        return drivers.map { driver ->
            UsbDeviceInfo(
                name = driver.device.productName ?: "USB Device",
                vendorId = driver.device.vendorId,
                productId = driver.device.productId,
                serialNumber = driver.device.serialNumber
            )
        }
    }

    /**
     * Get a device by ID
     */
    fun getDevice(deviceId: String): CanDevice? = _devices.value[deviceId]

    /**
     * Get config for a device
     */
    fun getDeviceConfig(deviceId: String): DeviceConfig? =
        _deviceConfigs.value.find { it.id == deviceId }

    /**
     * Check if a specific USB device is connected
     */
    fun isUsbDeviceConnected(vendorId: Int, productId: Int): Boolean {
        return scanUsbDevices().any { it.vendorId == vendorId && it.productId == productId }
    }

    /**
     * Set the CAN bus bitrate for all connected devices
     * @param bitrate Bitrate in bit/s
     */
    suspend fun setCanBitrate(bitrate: Int) {
        _canBitrate.value = bitrate
        Log.d(TAG, "CAN bitrate set to: $bitrate")

        // Persist to DataStore
        try {
            context.deviceDataStore.edit { prefs ->
                prefs[Keys.CAN_BITRATE] = bitrate
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving CAN bitrate", e)
        }

        // Update bitrate on all connected devices
        _activeDevices.value.forEach { (port, device) ->
            Log.d(TAG, "Setting bitrate on port $port: ${device.displayName}")
            device.setCanBitrate(bitrate)
        }
    }

    /**
     * Set the CAN bus bitrate for a specific port
     */
    suspend fun setCanBitrateForPort(port: Int, bitrate: Int): Boolean {
        val device = _activeDevices.value[port] ?: return false
        return device.setCanBitrate(bitrate)
    }

    /**
     * Test connection to a device
     * @param deviceId Device ID to test
     * @return Test result with success status and info
     */
    suspend fun testConnection(deviceId: String): ConnectionTestResult {
        val device = _devices.value[deviceId]
            ?: return ConnectionTestResult(false, "Gerät nicht gefunden")

        // Don't test if already connected on any port
        val connectedPort = getPortForDevice(deviceId)
        if (connectedPort != null) {
            return ConnectionTestResult(true, "Gerät ist bereits verbunden (Port $connectedPort)", device.getStatusInfo())
        }

        return try {
            Log.i(TAG, "Testing connection to: ${device.displayName}")

            // Try to connect
            val result = device.connect()
            if (result.isFailure) {
                return ConnectionTestResult(
                    false,
                    "Verbindung fehlgeschlagen: ${result.exceptionOrNull()?.message ?: "Unbekannter Fehler"}"
                )
            }

            // Wait a moment for the device to stabilize
            delay(500)

            // Get device status
            val info = device.getStatusInfo()

            // Disconnect again
            device.disconnect()

            Log.i(TAG, "Connection test successful for: ${device.displayName}")
            ConnectionTestResult(true, "Verbindung erfolgreich!", info)
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            ConnectionTestResult(false, "Fehler: ${e.message ?: "Unbekannter Fehler"}")
        }
    }
}
