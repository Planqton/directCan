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
                subclass(UsbSerialConfig::class)
                subclass(SimulatorConfig::class)
                subclass(PeakCanConfig::class)
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

    // Currently connected device
    private val _activeDevice = MutableStateFlow<CanDevice?>(null)
    val activeDevice: StateFlow<CanDevice?> = _activeDevice.asStateFlow()

    // Connection state (mirrors active device state)
    val connectionState: StateFlow<ConnectionState> = _activeDevice.flatMapLatest { device ->
        device?.connectionState ?: flowOf(ConnectionState.DISCONNECTED)
    }.stateIn(scope, SharingStarted.Eagerly, ConnectionState.DISCONNECTED)

    // Unified data stream from active device
    private val _receivedLines = MutableSharedFlow<String>(extraBufferCapacity = 1000)
    val receivedLines: SharedFlow<String> = _receivedLines.asSharedFlow()

    // Error stream from active device
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    // CAN bus bitrate (global setting)
    private val _canBitrate = MutableStateFlow(500000)  // Default 500 kbit/s
    val canBitrate: StateFlow<Int> = _canBitrate.asStateFlow()

    // USB Manager for device detection
    private val usbManager: UsbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
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
            is UsbSerialConfig -> UsbSerialDevice(context, config)
            is SimulatorConfig -> SimulatorDevice(config)
            is PeakCanConfig -> PeakCanDevice(context, config)
        }

        _devices.value = _devices.value + (config.id to device)

        // Subscribe to device data streams
        scope.launch {
            device.receivedLines.collect { line ->
                if (_activeDevice.value?.id == device.id) {
                    _receivedLines.emit(line)
                }
            }
        }
        scope.launch {
            device.errors.collect { error ->
                if (_activeDevice.value?.id == device.id) {
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
        // Disconnect if this device is active
        if (_activeDevice.value?.id == config.id) {
            disconnect()
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
     * Remove a device configuration
     */
    suspend fun removeDevice(deviceId: String) {
        // Disconnect if this device is active
        if (_activeDevice.value?.id == deviceId) {
            disconnect()
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
     * Connect to a device by ID
     */
    suspend fun connect(deviceId: String): Result<Unit> {
        val device = _devices.value[deviceId]
            ?: return Result.failure(IllegalArgumentException("Device not found: $deviceId"))

        // Disconnect current device first
        disconnect()

        Log.i(TAG, "Connecting to device: ${device.displayName}")
        val result = device.connect()

        if (result.isSuccess) {
            _activeDevice.value = device
            // Save as last connected device
            context.deviceDataStore.edit { prefs ->
                prefs[Keys.LAST_CONNECTED_DEVICE] = deviceId
            }
            Log.i(TAG, "Connected to: ${device.displayName}")
        } else {
            Log.e(TAG, "Connection failed: ${result.exceptionOrNull()?.message}")
        }

        return result
    }

    /**
     * Disconnect from the current device
     */
    suspend fun disconnect() {
        _activeDevice.value?.let { device ->
            Log.i(TAG, "Disconnecting from: ${device.displayName}")
            device.disconnect()
            _activeDevice.value = null
        }
    }

    /**
     * Send data through the active device
     */
    suspend fun send(data: String): Boolean {
        return _activeDevice.value?.send(data) ?: false
    }

    /**
     * Send a CAN frame through the active device
     */
    suspend fun sendCanFrame(id: Long, data: ByteArray, extended: Boolean = false): Boolean {
        return _activeDevice.value?.sendCanFrame(id, data, extended) ?: false
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
     * Set the CAN bus bitrate
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

        // If connected, update the device bitrate
        _activeDevice.value?.let { device ->
            device.setCanBitrate(bitrate)
        }
    }

    /**
     * Test connection to a device
     * @param deviceId Device ID to test
     * @return Test result with success status and info
     */
    suspend fun testConnection(deviceId: String): ConnectionTestResult {
        val device = _devices.value[deviceId]
            ?: return ConnectionTestResult(false, "Gerät nicht gefunden")

        // Don't test if already connected
        if (_activeDevice.value?.id == deviceId) {
            return ConnectionTestResult(true, "Gerät ist bereits verbunden", device.getStatusInfo())
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
