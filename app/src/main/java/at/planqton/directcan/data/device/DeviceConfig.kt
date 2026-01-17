package at.planqton.directcan.data.device

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Base configuration for all device types.
 * Serializable for persistence in DataStore.
 */
@Serializable
sealed class DeviceConfig {
    abstract val id: String
    abstract val name: String
    abstract val type: DeviceType

    companion object {
        fun generateId(): String = UUID.randomUUID().toString()
    }
}

/**
 * Configuration for USB Serial CAN adapters
 */
@Serializable
@SerialName("usb_serial")
data class UsbSerialConfig(
    override val id: String = DeviceConfig.generateId(),
    override val name: String = "USB Serial",
    val vendorId: Int? = null,       // null = auto-detect any USB device
    val productId: Int? = null,      // null = auto-detect
    val baudRate: Int = 2000000,     // Serial baudrate (USB communication speed)
    val canBitrate: Int = 500000,    // CAN bus bitrate (actual bus speed)
    val dataBits: Int = 8,
    val stopBits: Int = 1,
    val parity: Int = 0              // 0 = none, 1 = odd, 2 = even
) : DeviceConfig() {
    override val type: DeviceType = DeviceType.USB_SERIAL

    companion object {
        // Standard CAN bus bitrates
        const val CAN_BITRATE_10K = 10000
        const val CAN_BITRATE_20K = 20000
        const val CAN_BITRATE_50K = 50000
        const val CAN_BITRATE_100K = 100000
        const val CAN_BITRATE_125K = 125000
        const val CAN_BITRATE_250K = 250000
        const val CAN_BITRATE_500K = 500000
        const val CAN_BITRATE_800K = 800000
        const val CAN_BITRATE_1M = 1000000

        /** Create config for a specific USB device */
        fun forDevice(name: String, vendorId: Int, productId: Int): UsbSerialConfig {
            return UsbSerialConfig(
                name = name,
                vendorId = vendorId,
                productId = productId
            )
        }

        /** Create config that auto-detects any USB serial device */
        fun autoDetect(name: String = "USB Serial (Auto)"): UsbSerialConfig {
            return UsbSerialConfig(name = name)
        }
    }
}

/**
 * Configuration for the built-in simulator
 */
@Serializable
@SerialName("simulator")
data class SimulatorConfig(
    override val id: String = DeviceConfig.generateId(),
    override val name: String = "Simulator"
) : DeviceConfig() {
    override val type: DeviceType = DeviceType.SIMULATOR

    companion object {
        /** Default simulator configuration */
        val DEFAULT = SimulatorConfig(
            id = "simulator_default",
            name = "Fahrzeug-Simulator"
        )
    }
}

/**
 * Configuration for PEAK CAN adapters (PCAN-USB)
 */
@Serializable
@SerialName("peak_can")
data class PeakCanConfig(
    override val id: String = DeviceConfig.generateId(),
    override val name: String = "PEAK CAN",
    val channel: Int = 1,            // PCAN channel (1-8 for multi-channel adapters)
    val bitrate: Int = 500000,       // CAN bitrate in bit/s
    val listenOnly: Boolean = false  // Listen-only mode (no ACK)
) : DeviceConfig() {
    override val type: DeviceType = DeviceType.PEAK_CAN

    companion object {
        // Common CAN bitrates
        const val BITRATE_125K = 125000
        const val BITRATE_250K = 250000
        const val BITRATE_500K = 500000
        const val BITRATE_1M = 1000000

        // PEAK USB Vendor/Product IDs
        const val PEAK_VENDOR_ID = 0x0C72
        const val PCAN_USB_PRODUCT_ID = 0x000C
        const val PCAN_USB_PRO_PRODUCT_ID = 0x0014
    }
}

/**
 * Wrapper for storing multiple device configs
 */
@Serializable
data class DeviceConfigList(
    val devices: List<DeviceConfig> = emptyList()
)
