package at.planqton.directcan.data.dbc

import kotlinx.serialization.Serializable

@Serializable
data class DbcFile(
    val version: String = "",
    val description: String = "",
    val nodes: List<DbcNode> = emptyList(),
    val messages: List<DbcMessage> = emptyList(),
    val valueTables: Map<String, Map<Int, String>> = emptyMap(),
    val attributes: Map<String, String> = emptyMap()
) {
    fun findMessage(id: Long): DbcMessage? = messages.find { it.id == id }
    fun findMessageByName(name: String): DbcMessage? = messages.find { it.name == name }
}

@Serializable
data class DbcNode(
    val name: String,
    val description: String = "",
    val attributes: Map<String, String> = emptyMap()
)

@Serializable
data class DbcMessage(
    val id: Long,
    val name: String,
    val length: Int,
    val transmitter: String = "",
    val signals: List<DbcSignal> = emptyList(),
    val description: String = "",
    val isExtended: Boolean = false,
    val attributes: Map<String, String> = emptyMap()
) {
    val idHex: String get() = id.toString(16).uppercase().padStart(3, '0')

    fun decodeSignals(data: ByteArray): Map<DbcSignal, Double> {
        return signals.associateWith { signal ->
            signal.decode(data)
        }
    }
}

@Serializable
data class DbcSignal(
    val name: String,
    val startBit: Int,
    val length: Int,
    val byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    val valueType: ValueType = ValueType.UNSIGNED,
    val factor: Double = 1.0,
    val offset: Double = 0.0,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val unit: String = "",
    val receivers: List<String> = emptyList(),
    val description: String = "",
    val valueDescriptions: Map<Int, String> = emptyMap(),
    val attributes: Map<String, String> = emptyMap()
) {
    enum class ByteOrder { BIG_ENDIAN, LITTLE_ENDIAN }
    enum class ValueType { UNSIGNED, SIGNED }

    fun decode(data: ByteArray): Double {
        if (data.isEmpty()) return 0.0

        val rawValue = extractBits(data, startBit, length, byteOrder)
        val signedValue = if (valueType == ValueType.SIGNED && length > 1) {
            val signBit = 1L shl (length - 1)
            if ((rawValue and signBit) != 0L) {
                rawValue - (1L shl length)
            } else {
                rawValue
            }
        } else {
            rawValue
        }

        return signedValue * factor + offset
    }

    fun encode(physicalValue: Double): Long {
        val rawValue = ((physicalValue - offset) / factor).toLong()
        return rawValue and ((1L shl length) - 1)
    }

    fun getValueDescription(rawValue: Int): String? = valueDescriptions[rawValue]

    private fun extractBits(data: ByteArray, startBit: Int, length: Int, order: ByteOrder): Long {
        var result = 0L

        if (order == ByteOrder.LITTLE_ENDIAN) {
            var bitPos = startBit
            for (i in 0 until length) {
                val byteIdx = bitPos / 8
                val bitIdx = bitPos % 8
                if (byteIdx < data.size) {
                    val bit = (data[byteIdx].toInt() ushr bitIdx) and 1
                    result = result or (bit.toLong() shl i)
                }
                bitPos++
            }
        } else {
            // Big endian (Motorola)
            val startByte = startBit / 8
            val startBitInByte = startBit % 8

            var bitsRead = 0
            var currentByte = startByte
            var currentBit = startBitInByte

            while (bitsRead < length && currentByte < data.size) {
                val bit = (data[currentByte].toInt() ushr currentBit) and 1
                result = result or (bit.toLong() shl (length - 1 - bitsRead))
                bitsRead++

                currentBit--
                if (currentBit < 0) {
                    currentBit = 7
                    currentByte++
                }
            }
        }

        return result
    }
}

