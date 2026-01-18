package at.planqton.directcan.data.gemini

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * CAN commands that the AI can output for direct bus communication.
 * These are parsed from JSON blocks in AI responses.
 */
@Serializable
sealed class CanCommand {

    /**
     * Send a single CAN frame - no response expected.
     * Used for broadcast commands or one-way communication.
     */
    @Serializable
    @SerialName("sendFrame")
    data class SendFrame(
        val id: Long,
        val data: String,           // Hex string, e.g. "02 01 0C" or "02010C"
        val extended: Boolean = false
    ) : CanCommand()

    /**
     * Send ISO-TP request and wait for response.
     * Used for UDS/OBD2 communication.
     */
    @Serializable
    @SerialName("sendIsoTp")
    data class SendIsoTp(
        val txId: Long,             // Request ID
        val rxId: Long,             // Response ID to listen for
        val data: String,           // Hex string payload
        val timeoutMs: Int = 2000   // Response timeout
    ) : CanCommand()

    /**
     * Read Diagnostic Trouble Codes (DTCs) via UDS Service 0x19.
     */
    @Serializable
    @SerialName("readDtcs")
    data class ReadDtcs(
        val txId: Long = 0x7DF,     // OBD2 broadcast
        val rxId: Long = 0x7E8,     // ECM response
        val subFunction: Int = 0x02 // reportDTCByStatusMask (all DTCs)
    ) : CanCommand()

    /**
     * Clear Diagnostic Trouble Codes via UDS Service 0x14.
     * SAFETY: Requires explicit user confirmation!
     */
    @Serializable
    @SerialName("clearDtcs")
    data class ClearDtcs(
        val txId: Long = 0x7DF,
        val rxId: Long = 0x7E8
    ) : CanCommand()

    /**
     * Read Vehicle Identification Number via UDS Service 0x09 PID 0x02.
     */
    @Serializable
    @SerialName("readVin")
    data class ReadVin(
        val txId: Long = 0x7DF,
        val rxId: Long = 0x7E8
    ) : CanCommand()

    /**
     * Generic UDS request for any service.
     */
    @Serializable
    @SerialName("udsRequest")
    data class UdsRequest(
        val txId: Long,
        val rxId: Long,
        val service: Int,           // UDS service ID (e.g. 0x01 for OBD2, 0x22 for ReadDataById)
        val subFunction: Int? = null,
        val data: String? = null,   // Additional data as hex string
        val timeoutMs: Int = 2000
    ) : CanCommand()

    /**
     * OBD2 PID request (Service 0x01/0x02).
     */
    @Serializable
    @SerialName("obd2Pid")
    data class Obd2Pid(
        val pid: Int,               // PID to request (e.g. 0x0C for RPM)
        val service: Int = 0x01,    // 0x01 = current, 0x02 = freeze frame
        val txId: Long = 0x7DF,
        val rxId: Long = 0x7E8,
        val timeoutMs: Int = 2000
    ) : CanCommand()

    /**
     * Scan the bus for active CAN IDs.
     */
    @Serializable
    @SerialName("scanBus")
    data class ScanBus(
        val durationMs: Int = 2000  // How long to listen
    ) : CanCommand()

    /**
     * Observe specific CAN IDs for a duration.
     */
    @Serializable
    @SerialName("observeIds")
    data class ObserveIds(
        val ids: List<Long>,        // CAN IDs to observe
        val durationMs: Int = 2000
    ) : CanCommand()

    /**
     * Wait for a specified duration.
     */
    @Serializable
    @SerialName("delay")
    data class Delay(
        val milliseconds: Int
    ) : CanCommand()

    /**
     * Start/stop sending a periodic frame (simulation).
     */
    @Serializable
    @SerialName("periodicFrame")
    data class PeriodicFrame(
        val id: Long,
        val data: String,
        val intervalMs: Int,
        val enable: Boolean = true
    ) : CanCommand()
}

/**
 * Container for CAN commands with AI narration.
 * The narration explains what the AI is about to do.
 */
@Serializable
data class CanCommandBlock(
    val commands: List<CanCommand>,
    val narration: String = ""  // AI explains what it's doing
)

/**
 * Result of executing a single CAN command.
 */
sealed class CanCommandResult {
    abstract val command: CanCommand
    abstract val success: Boolean
    abstract val message: String

    /**
     * Success with response data (for ISO-TP requests).
     */
    data class IsoTpResponse(
        override val command: CanCommand,
        override val success: Boolean,
        override val message: String,
        val responseData: ByteArray,
        val responseHex: String
    ) : CanCommandResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IsoTpResponse) return false
            return command == other.command && responseData.contentEquals(other.responseData)
        }

        override fun hashCode(): Int {
            var result = command.hashCode()
            result = 31 * result + responseData.contentHashCode()
            return result
        }
    }

    /**
     * Success for fire-and-forget commands.
     */
    data class Sent(
        override val command: CanCommand,
        override val success: Boolean,
        override val message: String
    ) : CanCommandResult()

    /**
     * Timeout waiting for response.
     */
    data class Timeout(
        override val command: CanCommand,
        override val success: Boolean = false,
        override val message: String
    ) : CanCommandResult()

    /**
     * Bus scan results.
     */
    data class BusScanResult(
        override val command: CanCommand,
        override val success: Boolean,
        override val message: String,
        val foundIds: List<Long>,
        val frameCountById: Map<Long, Int>
    ) : CanCommandResult()

    /**
     * Observation results.
     */
    data class ObservationResult(
        override val command: CanCommand,
        override val success: Boolean,
        override val message: String,
        val frames: List<FrameSnapshot>
    ) : CanCommandResult() {
        data class FrameSnapshot(
            val id: Long,
            val data: String,
            val timestamp: Long
        )
    }

    /**
     * Error during execution.
     */
    data class Error(
        override val command: CanCommand,
        override val success: Boolean = false,
        override val message: String
    ) : CanCommandResult()
}

/**
 * Helper to format command results for AI context.
 */
fun CanCommandResult.formatForAi(): String = buildString {
    when (val result = this@formatForAi) {
        is CanCommandResult.IsoTpResponse -> {
            if (result.success) {
                appendLine("✓ ISO-TP Antwort erhalten:")
                appendLine("  Hex: ${result.responseHex}")
                // Interpret response if it's UDS
                val data = result.responseData
                if (data.isNotEmpty()) {
                    val firstByte = data[0].toInt() and 0xFF
                    when {
                        firstByte == 0x7F -> {
                            appendLine("  ⚠️ Negative Response!")
                            if (data.size >= 3) {
                                val service = data[1].toInt() and 0xFF
                                val nrc = data[2].toInt() and 0xFF
                                appendLine("  Service: 0x${service.toString(16).uppercase()}")
                                appendLine("  NRC: 0x${nrc.toString(16).uppercase()} (${getNrcDescription(nrc)})")
                            }
                        }
                        firstByte >= 0x41 && firstByte <= 0x7E -> {
                            appendLine("  ✓ Positive Response (Service 0x${(firstByte - 0x40).toString(16).uppercase()})")
                        }
                    }
                }
            } else {
                appendLine("✗ ISO-TP Fehler: ${result.message}")
            }
        }
        is CanCommandResult.Sent -> {
            appendLine(if (result.success) "✓ ${result.message}" else "✗ ${result.message}")
        }
        is CanCommandResult.Timeout -> {
            appendLine("⏱️ Timeout: ${result.message}")
        }
        is CanCommandResult.BusScanResult -> {
            appendLine("🔍 Bus-Scan Ergebnis:")
            appendLine("  Gefundene IDs: ${result.foundIds.size}")
            result.frameCountById.entries
                .sortedByDescending { it.value }
                .take(10)
                .forEach { (id, count) ->
                    appendLine("  • 0x${id.toString(16).uppercase()}: $count Frames")
                }
        }
        is CanCommandResult.ObservationResult -> {
            appendLine("👁️ Beobachtung (${result.frames.size} Frames):")
            result.frames.take(10).forEach { frame ->
                appendLine("  • 0x${frame.id.toString(16).uppercase()}: ${frame.data}")
            }
            if (result.frames.size > 10) {
                appendLine("  ... und ${result.frames.size - 10} weitere")
            }
        }
        is CanCommandResult.Error -> {
            appendLine("❌ Fehler: ${result.message}")
        }
    }
}

/**
 * Get human-readable NRC (Negative Response Code) description.
 */
private fun getNrcDescription(nrc: Int): String = when (nrc) {
    0x10 -> "General Reject"
    0x11 -> "Service Not Supported"
    0x12 -> "Sub-Function Not Supported"
    0x13 -> "Incorrect Message Length"
    0x14 -> "Response Too Long"
    0x21 -> "Busy Repeat Request"
    0x22 -> "Conditions Not Correct"
    0x24 -> "Request Sequence Error"
    0x25 -> "No Response From Subnet"
    0x26 -> "Failure Prevents Execution"
    0x31 -> "Request Out Of Range"
    0x33 -> "Security Access Denied"
    0x35 -> "Invalid Key"
    0x36 -> "Exceeded Attempts"
    0x37 -> "Required Time Delay"
    0x70 -> "Upload/Download Not Accepted"
    0x71 -> "Transfer Data Suspended"
    0x72 -> "Programming Failure"
    0x73 -> "Wrong Block Sequence"
    0x78 -> "Response Pending"
    0x7E -> "Sub-Function Not Supported In Session"
    0x7F -> "Service Not Supported In Session"
    else -> "Unknown (0x${nrc.toString(16).uppercase()})"
}
