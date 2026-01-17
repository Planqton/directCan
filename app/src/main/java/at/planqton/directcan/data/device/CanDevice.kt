package at.planqton.directcan.data.device

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Supported device types for CAN communication
 */
enum class DeviceType {
    USB_SERIAL,
    SIMULATOR,
    PEAK_CAN
}

/**
 * Connection state for CAN devices
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Common interface for all CAN device types.
 * Implementations: UsbSerialDevice, SimulatorDevice, PeakCanDevice
 */
interface CanDevice {
    /** Unique identifier for this device instance */
    val id: String

    /** Type of this device */
    val type: DeviceType

    /** Display name for UI */
    val displayName: String

    /** Current connection state */
    val connectionState: StateFlow<ConnectionState>

    /** Whether device is currently connected */
    val isConnected: Boolean
        get() = connectionState.value == ConnectionState.CONNECTED

    /** Received CAN data as text lines (format: "t<timestamp> <id> <len> <data>") */
    val receivedLines: SharedFlow<String>

    /** Error messages */
    val errors: SharedFlow<String>

    /**
     * Connect to the device
     * @return Result.success if connected, Result.failure with exception otherwise
     */
    suspend fun connect(): Result<Unit>

    /**
     * Disconnect from the device
     */
    suspend fun disconnect()

    /**
     * Send data to the device
     * @param data Text command to send
     * @return true if sent successfully
     */
    suspend fun send(data: String): Boolean

    /**
     * Send raw bytes to the device
     * @param data Bytes to send
     * @return true if sent successfully
     */
    suspend fun sendBytes(data: ByteArray): Boolean = send(String(data))

    /**
     * Send a CAN frame
     * @param id CAN ID (11-bit or 29-bit)
     * @param data Frame data (0-8 bytes)
     * @param extended true for 29-bit extended ID
     * @return true if sent successfully
     */
    suspend fun sendCanFrame(id: Long, data: ByteArray, extended: Boolean = false): Boolean {
        val idHex = id.toString(16).uppercase()
        val extMarker = if (extended) "X" else ""
        val dataHex = data.joinToString(" ") { "%02X".format(it) }
        return send("s${idHex}${extMarker} ${data.size} $dataHex\n")
    }

    /**
     * Get device-specific status information for display
     * @return Map of status key-value pairs
     */
    fun getStatusInfo(): Map<String, String>

    /**
     * Set the CAN bus bitrate
     * @param bitrate Bitrate in bit/s (e.g. 500000 for 500 kbit/s)
     * @return true if set successfully
     */
    suspend fun setCanBitrate(bitrate: Int): Boolean

    /**
     * Clean up resources when device is no longer needed
     */
    fun dispose() {}
}
