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
 * Configuration for USB SLCAN adapters (LAWICEL protocol)
 */
@Serializable
@SerialName("usb_slcan")
data class UsbSlcanConfig(
    override val id: String = DeviceConfig.generateId(),
    override val name: String = "USB SLCAN",
    val vendorId: Int? = null,       // null = auto-detect any USB device
    val productId: Int? = null,      // null = auto-detect
    val baudRate: Int = 2000000,     // Serial baudrate (USB communication speed)
    val canBitrate: Int = 500000,    // CAN bus bitrate (actual bus speed)
    val dataBits: Int = 8,
    val stopBits: Int = 1,
    val parity: Int = 0              // 0 = none, 1 = odd, 2 = even
) : DeviceConfig() {
    override val type: DeviceType = DeviceType.USB_SLCAN

    companion object {
        // Standard CAN bus bitrates (SLCAN S0-S8)
        const val CAN_BITRATE_10K = 10000    // S0
        const val CAN_BITRATE_20K = 20000    // S1
        const val CAN_BITRATE_50K = 50000    // S2
        const val CAN_BITRATE_100K = 100000  // S3
        const val CAN_BITRATE_125K = 125000  // S4
        const val CAN_BITRATE_250K = 250000  // S5
        const val CAN_BITRATE_500K = 500000  // S6
        const val CAN_BITRATE_800K = 800000  // S7
        const val CAN_BITRATE_1M = 1000000   // S8

        /** Create config for a specific USB device */
        fun forDevice(name: String, vendorId: Int, productId: Int): UsbSlcanConfig {
            return UsbSlcanConfig(
                name = name,
                vendorId = vendorId,
                productId = productId
            )
        }

        /** Create config that auto-detects any USB SLCAN device */
        fun autoDetect(name: String = "USB SLCAN (Auto)"): UsbSlcanConfig {
            return UsbSlcanConfig(name = name)
        }
    }
}

/**
 * Wrapper for storing multiple device configs
 */
@Serializable
data class DeviceConfigList(
    val devices: List<DeviceConfig> = emptyList()
)
