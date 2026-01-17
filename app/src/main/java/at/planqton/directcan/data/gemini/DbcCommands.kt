package at.planqton.directcan.data.gemini

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DBC commands that Gemini can output to create/update DBC files.
 * These are parsed from JSON blocks in Gemini's responses.
 */
@Serializable
sealed class DbcCommand {

    @Serializable
    @SerialName("addMessage")
    data class AddMessage(
        val id: Long,
        val name: String,
        val length: Int = 8,
        val transmitter: String = "",
        val description: String = "",
        val isExtended: Boolean = false
    ) : DbcCommand()

    @Serializable
    @SerialName("updateMessage")
    data class UpdateMessage(
        val id: Long,
        val name: String? = null,
        val length: Int? = null,
        val transmitter: String? = null,
        val description: String? = null
    ) : DbcCommand()

    @Serializable
    @SerialName("deleteMessage")
    data class DeleteMessage(
        val id: Long
    ) : DbcCommand()

    @Serializable
    @SerialName("addSignal")
    data class AddSignal(
        val messageId: Long,
        val name: String,
        val startBit: Int,
        val length: Int,
        val byteOrder: String = "LITTLE_ENDIAN",  // "LITTLE_ENDIAN" or "BIG_ENDIAN"
        val valueType: String = "UNSIGNED",        // "UNSIGNED" or "SIGNED"
        val factor: Double = 1.0,
        val offset: Double = 0.0,
        val min: Double = 0.0,
        val max: Double = 0.0,
        val unit: String = "",
        val description: String = "",
        val valueDescriptions: Map<Int, String> = emptyMap()
    ) : DbcCommand()

    @Serializable
    @SerialName("updateSignal")
    data class UpdateSignal(
        val messageId: Long,
        val signalName: String,
        val newName: String? = null,
        val startBit: Int? = null,
        val length: Int? = null,
        val byteOrder: String? = null,
        val valueType: String? = null,
        val factor: Double? = null,
        val offset: Double? = null,
        val min: Double? = null,
        val max: Double? = null,
        val unit: String? = null,
        val description: String? = null
    ) : DbcCommand()

    @Serializable
    @SerialName("deleteSignal")
    data class DeleteSignal(
        val messageId: Long,
        val signalName: String
    ) : DbcCommand()
}

/**
 * Container for multiple DBC commands with optional explanation.
 */
@Serializable
data class DbcCommandBlock(
    val commands: List<DbcCommand>,
    val explanation: String = ""
)

/**
 * Result of executing a DBC command.
 */
data class DbcChangeResult(
    val command: DbcCommand,
    val success: Boolean,
    val message: String
) {
    val commandDescription: String
        get() = when (command) {
            is DbcCommand.AddMessage -> "Message ${command.name} (0x${command.id.toString(16).uppercase()})"
            is DbcCommand.UpdateMessage -> "Message 0x${command.id.toString(16).uppercase()} aktualisiert"
            is DbcCommand.DeleteMessage -> "Message 0x${command.id.toString(16).uppercase()} gelöscht"
            is DbcCommand.AddSignal -> "Signal ${command.name}"
            is DbcCommand.UpdateSignal -> "Signal ${command.signalName} aktualisiert"
            is DbcCommand.DeleteSignal -> "Signal ${command.signalName} gelöscht"
        }
}
