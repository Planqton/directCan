package at.planqton.directcan.data.gemini

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

private const val TAG = "GeminiResponseParser"

/**
 * Parser for Gemini AI responses that can contain DBC commands or CAN commands as JSON blocks.
 *
 * Gemini is instructed to output commands in ```json blocks:
 * ```json
 * {"commands": [{"type": "addMessage", "id": 201, ...}]}  // DBC commands
 * {"canCommands": {"narration": "...", "commands": [...]}}  // CAN commands
 * ```
 *
 * This parser extracts these blocks, parses the commands, and returns
 * the cleaned text content along with the parsed commands.
 */
class GeminiResponseParser {

    private val dbcJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        serializersModule = SerializersModule {
            polymorphic(DbcCommand::class) {
                subclass(DbcCommand.AddMessage::class)
                subclass(DbcCommand.UpdateMessage::class)
                subclass(DbcCommand.DeleteMessage::class)
                subclass(DbcCommand.AddSignal::class)
                subclass(DbcCommand.UpdateSignal::class)
                subclass(DbcCommand.DeleteSignal::class)
            }
        }
        classDiscriminator = "type"
    }

    private val canJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        serializersModule = SerializersModule {
            polymorphic(CanCommand::class) {
                subclass(CanCommand.SendFrame::class)
                subclass(CanCommand.SendIsoTp::class)
                subclass(CanCommand.ReadDtcs::class)
                subclass(CanCommand.ClearDtcs::class)
                subclass(CanCommand.ReadVin::class)
                subclass(CanCommand.UdsRequest::class)
                subclass(CanCommand.Obd2Pid::class)
                subclass(CanCommand.ScanBus::class)
                subclass(CanCommand.ObserveIds::class)
                subclass(CanCommand.Delay::class)
                subclass(CanCommand.PeriodicFrame::class)
            }
        }
        classDiscriminator = "type"
    }

    /**
     * Parse a Gemini response for DBC commands and CAN commands.
     *
     * @param response The full response text from Gemini
     * @return ParsedResponse containing cleaned text, extracted commands, and any errors
     */
    fun parseResponse(response: String): ParsedResponse {
        val dbcCommands = mutableListOf<DbcCommand>()
        var canCommandBlock: CanCommandBlock? = null
        val errors = mutableListOf<String>()

        // Pattern to find JSON blocks: ```json ... ``` or ```dbc ... ```
        val jsonBlockPattern = Regex(
            """```(?:json|dbc)\s*\n?([\s\S]*?)\n?```""",
            RegexOption.MULTILINE
        )

        var cleanedText = response
        val blocksToRemove = mutableListOf<String>()

        jsonBlockPattern.findAll(response).forEach { match ->
            val jsonContent = match.groupValues[1].trim()

            if (jsonContent.isNotEmpty()) {
                var parsed = false

                // First check if it's a CAN command block (contains "canCommands" key)
                if (jsonContent.contains("canCommands")) {
                    try {
                        // Parse as JSON object first to extract canCommands
                        val jsonObj = canJson.parseToJsonElement(jsonContent).jsonObject
                        if (jsonObj.containsKey("canCommands")) {
                            val canCommandsJson = jsonObj["canCommands"]!!.jsonObject
                            canCommandBlock = canJson.decodeFromJsonElement(
                                CanCommandBlock.serializer(),
                                canCommandsJson
                            )
                            parsed = true
                            blocksToRemove.add(match.value)
                            Log.d(TAG, "Parsed CAN commands: ${canCommandBlock?.commands?.size}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse CAN commands: ${e.message}")
                        errors.add("CAN-Command Parse-Fehler: ${e.message?.take(100)}")
                    }
                }

                // Try parsing as DBC commands if not CAN
                if (!parsed) {
                    try {
                        // Try parsing as CommandBlock first
                        val commandBlock = dbcJson.decodeFromString<DbcCommandBlock>(jsonContent)
                        dbcCommands.addAll(commandBlock.commands)
                        parsed = true
                        blocksToRemove.add(match.value)
                    } catch (e: Exception) {
                        // Try parsing as single command
                        try {
                            val command = dbcJson.decodeFromString<DbcCommand>(jsonContent)
                            dbcCommands.add(command)
                            parsed = true
                            blocksToRemove.add(match.value)
                        } catch (e2: Exception) {
                            // Try parsing as list of commands
                            try {
                                val commandList = dbcJson.decodeFromString<List<DbcCommand>>(jsonContent)
                                dbcCommands.addAll(commandList)
                                parsed = true
                                blocksToRemove.add(match.value)
                            } catch (e3: Exception) {
                                // Not a DBC command block
                                if (jsonContent.contains("\"type\"") &&
                                    (jsonContent.contains("Message") || jsonContent.contains("Signal"))) {
                                    errors.add("DBC-Command Parse-Fehler: ${e.message?.take(100)}")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Remove parsed JSON blocks from text
        blocksToRemove.forEach { block ->
            cleanedText = cleanedText.replace(block, "").trim()
        }

        // Clean up extra whitespace
        cleanedText = cleanedText
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        return ParsedResponse(
            textContent = cleanedText,
            dbcCommands = dbcCommands,
            canCommandBlock = canCommandBlock,
            parseErrors = errors
        )
    }

    /**
     * Check if a response contains any DBC commands.
     */
    fun hasDbcCommands(response: String): Boolean {
        val jsonBlockPattern = Regex("""```(?:json|dbc)\s*\n?([\s\S]*?)\n?```""")
        return jsonBlockPattern.findAll(response).any { match ->
            val content = match.groupValues[1]
            content.contains("\"type\"") &&
            (content.contains("addMessage") || content.contains("addSignal") ||
             content.contains("updateMessage") || content.contains("updateSignal") ||
             content.contains("deleteMessage") || content.contains("deleteSignal"))
        }
    }

    /**
     * Check if a response contains CAN commands.
     */
    fun hasCanCommands(response: String): Boolean {
        val jsonBlockPattern = Regex("""```(?:json|dbc)\s*\n?([\s\S]*?)\n?```""")
        return jsonBlockPattern.findAll(response).any { match ->
            val content = match.groupValues[1]
            content.contains("canCommands")
        }
    }
}

/**
 * Result of parsing a Gemini response.
 */
data class ParsedResponse(
    /** The response text with JSON blocks removed */
    val textContent: String,
    /** Successfully parsed DBC commands */
    val dbcCommands: List<DbcCommand>,
    /** Successfully parsed CAN command block */
    val canCommandBlock: CanCommandBlock? = null,
    /** Any errors encountered during parsing */
    val parseErrors: List<String>
) {
    /** Whether any DBC commands were found */
    val hasDbcCommands: Boolean get() = dbcCommands.isNotEmpty()

    /** Whether any CAN commands were found */
    val hasCanCommands: Boolean get() = canCommandBlock != null && canCommandBlock.commands.isNotEmpty()

    /** Whether any commands (DBC or CAN) were found */
    val hasCommands: Boolean get() = hasDbcCommands || hasCanCommands

    /** Whether there were any parse errors */
    val hasErrors: Boolean get() = parseErrors.isNotEmpty()
}
