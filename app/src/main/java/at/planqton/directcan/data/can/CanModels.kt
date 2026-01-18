package at.planqton.directcan.data.can

import at.planqton.directcan.data.dbc.DbcFile
import at.planqton.directcan.data.dbc.DbcMessage
import at.planqton.directcan.data.dbc.DbcSignal

data class CanFrame(
    val timestamp: Long,
    val id: Long,
    val data: ByteArray,
    val isExtended: Boolean = false,
    val isRtr: Boolean = false,
    val direction: Direction = Direction.RX,
    val bus: Int = 0,
    val port: Int = 1  // Port 1 or 2 for multi-device support
) {
    enum class Direction { TX, RX }

    val idHex: String get() = "0x${id.toString(16).uppercase().padStart(3, '0')}"

    val dataHex: String
        get() = data.joinToString(" ") { byte ->
            byte.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0')
        }

    val dataAscii: String
        get() = data.map { byte ->
            val c = byte.toInt().and(0xFF)
            if (c in 32..126) c.toChar() else '.'
        }.joinToString("")

    val length: Int get() = data.size

    fun decode(dbcFile: DbcFile): DecodedCanFrame? {
        val message = dbcFile.findMessage(id) ?: return null
        return decode(message)
    }

    fun decode(message: DbcMessage): DecodedCanFrame {
        val signals = message.decodeSignals(data).map { (signal, value) ->
            DecodedSignal(
                name = signal.name,
                value = value,
                unit = signal.unit,
                description = signal.description,
                valueDescription = signal.valueDescriptions[value.toInt()]
            )
        }
        return DecodedCanFrame(
            frame = this,
            message = message,
            signals = signals
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CanFrame
        return timestamp == other.timestamp && id == other.id && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    companion object {
        // CAN ID limits
        private const val MAX_STANDARD_ID = 0x7FFL          // 11-bit
        private const val MAX_EXTENDED_ID = 0x1FFFFFFFL     // 29-bit
        private const val MAX_CAN_DATA_LENGTH = 8           // Standard CAN
        private const val MAX_CANFD_DATA_LENGTH = 64        // CAN-FD

        /**
         * Parse SLCAN/LAWICEL format line
         * Format:
         *   t<id:3><len:1><data:2*len>  - Standard frame (11-bit ID)
         *   T<id:8><len:1><data:2*len>  - Extended frame (29-bit ID)
         *   r<id:3><len:1>              - Standard RTR
         *   R<id:8><len:1>              - Extended RTR
         *
         * Examples:
         *   t1238AABBCCDDEEFF0011  - Standard: ID=0x123, Len=8, Data=AA BB CC DD EE FF 00 11
         *   T000001238AABBCCDD     - Extended: ID=0x00000123, Len=8
         *   r1230                  - Standard RTR: ID=0x123, Len=0
         */
        fun fromTextLine(line: String, port: Int = 1): CanFrame? {
            if (line.isEmpty()) return null

            val type = line[0]

            return try {
                when (type) {
                    't' -> parseSlcanStandard(line, port, rtr = false)
                    'T' -> parseSlcanExtended(line, port, rtr = false)
                    'r' -> parseSlcanStandard(line, port, rtr = true)
                    'R' -> parseSlcanExtended(line, port, rtr = true)
                    else -> null
                }
            } catch (e: Exception) {
                android.util.Log.e("CanFrame", "Error parsing SLCAN line: $line", e)
                null
            }
        }

        private fun parseSlcanStandard(line: String, port: Int, rtr: Boolean): CanFrame? {
            // t<id:3><len:1><data:2*len> or r<id:3><len:1>
            // Minimum length: 1 (type) + 3 (id) + 1 (len) = 5
            if (line.length < 5) return null

            val idStr = line.substring(1, 4)
            val id = idStr.toLongOrNull(16) ?: return null

            if (id < 0 || id > MAX_STANDARD_ID) {
                android.util.Log.w("CanFrame", "Invalid standard CAN ID: 0x${id.toString(16)}")
                return null
            }

            val len = line[4].digitToIntOrNull() ?: return null
            if (len < 0 || len > MAX_CAN_DATA_LENGTH) {
                android.util.Log.w("CanFrame", "Invalid data length: $len")
                return null
            }

            val data = if (rtr) {
                ByteArray(0)  // RTR has no data
            } else {
                parseSlcanData(line.substring(5), len) ?: return null
            }

            return CanFrame(
                timestamp = System.currentTimeMillis() * 1000,
                id = id,
                data = data,
                isExtended = false,
                isRtr = rtr,
                port = port
            )
        }

        private fun parseSlcanExtended(line: String, port: Int, rtr: Boolean): CanFrame? {
            // T<id:8><len:1><data:2*len> or R<id:8><len:1>
            // Minimum length: 1 (type) + 8 (id) + 1 (len) = 10
            if (line.length < 10) return null

            val idStr = line.substring(1, 9)
            val id = idStr.toLongOrNull(16) ?: return null

            if (id < 0 || id > MAX_EXTENDED_ID) {
                android.util.Log.w("CanFrame", "Invalid extended CAN ID: 0x${id.toString(16)}")
                return null
            }

            val len = line[9].digitToIntOrNull() ?: return null
            if (len < 0 || len > MAX_CAN_DATA_LENGTH) {
                android.util.Log.w("CanFrame", "Invalid data length: $len")
                return null
            }

            val data = if (rtr) {
                ByteArray(0)  // RTR has no data
            } else {
                parseSlcanData(line.substring(10), len) ?: return null
            }

            return CanFrame(
                timestamp = System.currentTimeMillis() * 1000,
                id = id,
                data = data,
                isExtended = true,
                isRtr = rtr,
                port = port
            )
        }

        private fun parseSlcanData(hexString: String, expectedLen: Int): ByteArray? {
            if (hexString.length < expectedLen * 2) return null

            return try {
                ByteArray(expectedLen) { i ->
                    hexString.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } catch (e: Exception) {
                null
            }
        }

        fun create(id: Long, data: ByteArray, extended: Boolean = false, port: Int = 1): CanFrame {
            return CanFrame(
                timestamp = System.currentTimeMillis() * 1000,
                id = id,
                data = data,
                isExtended = extended,
                port = port
            )
        }
    }
}

data class DecodedCanFrame(
    val frame: CanFrame,
    val message: DbcMessage,
    val signals: List<DecodedSignal>
)

data class DecodedSignal(
    val name: String,
    val value: Double,
    val unit: String,
    val description: String,
    val valueDescription: String? = null
) {
    val formattedValue: String
        get() = when {
            valueDescription != null -> "$valueDescription ($value)"
            value == value.toLong().toDouble() -> "${value.toLong()} $unit"
            else -> String.format("%.2f %s", value, unit)
        }
}

// UDS/OBD-II Hilfsfunktionen
object UdsHelper {

    // OBD-II Service IDs
    const val OBD_SHOW_CURRENT_DATA = 0x01
    const val OBD_SHOW_FREEZE_FRAME = 0x02
    const val OBD_READ_DTC = 0x03
    const val OBD_CLEAR_DTC = 0x04
    const val OBD_READ_PENDING_DTC = 0x07
    const val OBD_VEHICLE_INFO = 0x09

    // UDS Service IDs
    const val UDS_DIAG_SESSION_CONTROL = 0x10
    const val UDS_ECU_RESET = 0x11
    const val UDS_CLEAR_DIAG_INFO = 0x14
    const val UDS_READ_DTC_INFO = 0x19
    const val UDS_READ_DATA_BY_ID = 0x22
    const val UDS_WRITE_DATA_BY_ID = 0x2E
    const val UDS_SECURITY_ACCESS = 0x27
    const val UDS_ROUTINE_CONTROL = 0x31
    const val UDS_TESTER_PRESENT = 0x3E

    // Common DIDs
    const val DID_VIN = 0xF190
    const val DID_ECU_SERIAL = 0xF18C
    const val DID_ECU_SW_VERSION = 0xF189

    fun createReadDtcRequest(): ByteArray = byteArrayOf(0x01, OBD_READ_DTC.toByte())

    fun createClearDtcRequest(): ByteArray = byteArrayOf(0x01, OBD_CLEAR_DTC.toByte())

    fun createReadPidRequest(pid: Int): ByteArray =
        byteArrayOf(0x02, OBD_SHOW_CURRENT_DATA.toByte(), pid.toByte())

    fun createReadDidRequest(did: Int): ByteArray =
        byteArrayOf(0x03, UDS_READ_DATA_BY_ID.toByte(), (did shr 8).toByte(), (did and 0xFF).toByte())

    fun createTesterPresentRequest(): ByteArray =
        byteArrayOf(0x02, UDS_TESTER_PRESENT.toByte(), 0x00)

    fun createDiagSessionRequest(session: Int): ByteArray =
        byteArrayOf(0x02, UDS_DIAG_SESSION_CONTROL.toByte(), session.toByte())

    fun decodeDtc(byte1: Int, byte2: Int): String {
        val prefix = when ((byte1 shr 6) and 0x03) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            3 -> 'U'
            else -> 'P'
        }
        val digit1 = (byte1 shr 4) and 0x03
        val digit2 = byte1 and 0x0F
        val digit3 = (byte2 shr 4) and 0x0F
        val digit4 = byte2 and 0x0F

        return "$prefix${digit1}${digit2.toString(16).uppercase()}${digit3.toString(16).uppercase()}${digit4.toString(16).uppercase()}"
    }

    fun parseDtcResponse(data: ByteArray): List<String> {
        if (data.size < 2) return emptyList()
        if (data[1].toInt() != 0x43) return emptyList()  // Not a DTC response

        val dtcs = mutableListOf<String>()
        var i = 3  // Skip length, response SID, count

        while (i + 1 < data.size) {
            val byte1 = data[i].toInt() and 0xFF
            val byte2 = data[i + 1].toInt() and 0xFF

            if (byte1 != 0 || byte2 != 0) {
                dtcs.add(decodeDtc(byte1, byte2))
            }
            i += 2
        }

        return dtcs
    }

    // PID Dekodierung
    fun decodePid(pid: Int, data: ByteArray): Pair<Double, String>? {
        if (data.isEmpty()) return null

        return when (pid) {
            0x04 -> Pair(data[0].toInt().and(0xFF) * 100.0 / 255.0, "%")  // Engine Load
            0x05 -> Pair(data[0].toInt().and(0xFF) - 40.0, "°C")  // Coolant Temp
            0x0B -> Pair(data[0].toInt().and(0xFF).toDouble(), "kPa")  // Intake Pressure
            0x0C -> {  // RPM
                if (data.size >= 2) {
                    val rpm = ((data[0].toInt().and(0xFF) * 256) + data[1].toInt().and(0xFF)) / 4.0
                    Pair(rpm, "rpm")
                } else null
            }
            0x0D -> Pair(data[0].toInt().and(0xFF).toDouble(), "km/h")  // Speed
            0x0F -> Pair(data[0].toInt().and(0xFF) - 40.0, "°C")  // Intake Air Temp
            0x11 -> Pair(data[0].toInt().and(0xFF) * 100.0 / 255.0, "%")  // Throttle
            0x2F -> Pair(data[0].toInt().and(0xFF) * 100.0 / 255.0, "%")  // Fuel Level
            0x46 -> Pair(data[0].toInt().and(0xFF) - 40.0, "°C")  // Ambient Temp
            0x5C -> Pair(data[0].toInt().and(0xFF) - 40.0, "°C")  // Oil Temp
            else -> Pair(data[0].toInt().and(0xFF).toDouble(), "")
        }
    }

    val commonPids = mapOf(
        0x04 to "Engine Load",
        0x05 to "Coolant Temperature",
        0x0B to "Intake Manifold Pressure",
        0x0C to "Engine RPM",
        0x0D to "Vehicle Speed",
        0x0F to "Intake Air Temperature",
        0x10 to "MAF Air Flow Rate",
        0x11 to "Throttle Position",
        0x1F to "Run Time Since Start",
        0x2F to "Fuel Tank Level",
        0x46 to "Ambient Air Temperature",
        0x5C to "Engine Oil Temperature"
    )
}

// Renault Trafic III ECU Adressen
object RenaultTraficEcus {
    // Direct ECU references
    val ECM = EcuInfo("Engine Control Module", 0x7E0, 0x7E8)
    val TCM = EcuInfo("Transmission Control", 0x7E1, 0x7E9)
    val ABS_ESP = EcuInfo("ABS/ESP", 0x740, 0x760)
    val UCH = EcuInfo("Body Control Module", 0x745, 0x765)
    val HVAC = EcuInfo("Climate Control", 0x744, 0x764)
    val AIRBAG = EcuInfo("Airbag/SRS", 0x752, 0x772)
    val CLUSTER = EcuInfo("Instrument Cluster", 0x743, 0x763)
    val AUDIO = EcuInfo("Audio Unit", 0x712, 0x732)
    val EPS = EcuInfo("Electric Power Steering", 0x742, 0x762)
    val TPMS = EcuInfo("Tire Pressure Monitor", 0x758, 0x778)
    val PARKING = EcuInfo("Parking Sensors", 0x74E, 0x76E)
    val GW = EcuInfo("Gateway", 0x710, 0x730)

    val allEcus = listOf(ECM, TCM, ABS_ESP, UCH, HVAC, AIRBAG, CLUSTER, AUDIO, EPS, TPMS, PARKING, GW)

    val ecus = mapOf(
        "ECM" to ECM,
        "TCM" to TCM,
        "ABS" to ABS_ESP,
        "UCH" to UCH,
        "HVAC" to HVAC,
        "AIRBAG" to AIRBAG,
        "CLUSTER" to CLUSTER,
        "AUDIO" to AUDIO,
        "EPS" to EPS,
        "TPMS" to TPMS,
        "PARKING" to PARKING,
        "GW" to GW
    )

    data class EcuInfo(
        val name: String,
        val requestId: Int,
        val responseId: Int
    )
}
