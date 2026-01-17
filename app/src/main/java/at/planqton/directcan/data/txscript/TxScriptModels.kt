package at.planqton.directcan.data.txscript

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Represents a TX Script file that can be executed to send CAN frames.
 */
@Serializable
data class TxScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val version: Int = 1,
    val tags: List<String> = emptyList()
) {
    companion object {
        const val FILE_EXTENSION = ".txs"
        const val MIME_TYPE = "text/plain"

        fun createEmpty(name: String, description: String = ""): TxScript {
            return TxScript(
                name = name,
                description = description,
                content = """
                    // $name
                    // ${if (description.isNotEmpty()) description else "TX Script"}

                    // Example: Send a single frame
                    // send(0x7DF, [02, 01, 00])

                    // Example: Send with delay
                    // send(0x7DF, [02, 01, 0C])
                    // delay(100)

                """.trimIndent()
            )
        }
    }

    fun withUpdatedContent(newContent: String): TxScript {
        return copy(
            content = newContent,
            modifiedAt = System.currentTimeMillis()
        )
    }
}

/**
 * File information for a stored TX Script.
 */
@Serializable
data class TxScriptFileInfo(
    val id: String,
    val name: String,
    val fileName: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val description: String = "",
    val isActive: Boolean = false
) {
    val displaySize: String
        get() = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / (1024 * 1024)} MB"
        }
}

/**
 * Represents a CAN frame received during script execution.
 */
data class ReceivedCanFrame(
    val id: Long,
    val data: ByteArray,
    val timestamp: Long,
    val isExtended: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ReceivedCanFrame
        return id == other.id && data.contentEquals(other.data) && timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
