package at.planqton.directcan.data.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Parser for Gemini AI responses that can contain DBC commands as JSON blocks.
 *
 * Gemini is instructed to output DBC commands in ```json blocks:
 * ```json
 * {"commands": [{"type": "addMessage", "id": 201, ...}]}
 * ```
 *
 * This parser extracts these blocks, parses the commands, and returns
 * the cleaned text content along with the parsed commands.
 */
class GeminiResponseParser {

    private val json = Json {
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

    /**
     * Parse a Gemini response for DBC commands.
     *
     * @param response The full response text from Gemini
     * @return ParsedResponse containing cleaned text, extracted commands, and any errors
     */
    fun parseResponse(response: String): ParsedResponse {
        val commands = mutableListOf<DbcCommand>()
        val errors = mutableListOf<String>()

        // Pattern to find JSON blocks: ```json ... ``` or ```dbc ... ```
        val jsonBlockPattern = Regex(
            """```(?:json|dbc)\s*\n?([\s\S]*?)\n?```""",
            RegexOption.MULTILINE
        )

        var cleanedText = response

        jsonBlockPattern.findAll(response).forEach { match ->
            val jsonContent = match.groupValues[1].trim()

            if (jsonContent.isNotEmpty()) {
                try {
                    // Try parsing as CommandBlock first
                    val commandBlock = json.decodeFromString<DbcCommandBlock>(jsonContent)
                    commands.addAll(commandBlock.commands)
                } catch (e: Exception) {
                    // Try parsing as single command
                    try {
                        val command = json.decodeFromString<DbcCommand>(jsonContent)
                        commands.add(command)
                    } catch (e2: Exception) {
                        // Try parsing as list of commands
                        try {
                            val commandList = json.decodeFromString<List<DbcCommand>>(jsonContent)
                            commands.addAll(commandList)
                        } catch (e3: Exception) {
                            // Not a DBC command block, keep it in text
                            // Only log error if it looks like it should be a DBC command
                            if (jsonContent.contains("\"type\"") &&
                                (jsonContent.contains("Message") || jsonContent.contains("Signal"))) {
                                errors.add("JSON Parse-Fehler: ${e.message?.take(100)}")
                            }
                        }
                    }
                }
            }

            // Remove the JSON block from text only if we successfully parsed commands
            if (commands.isNotEmpty()) {
                cleanedText = cleanedText.replace(match.value, "").trim()
            }
        }

        // Clean up extra whitespace
        cleanedText = cleanedText
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        return ParsedResponse(
            textContent = cleanedText,
            dbcCommands = commands,
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
}

/**
 * Result of parsing a Gemini response.
 */
data class ParsedResponse(
    /** The response text with JSON blocks removed */
    val textContent: String,
    /** Successfully parsed DBC commands */
    val dbcCommands: List<DbcCommand>,
    /** Any errors encountered during parsing */
    val parseErrors: List<String>
) {
    /** Whether any DBC commands were found */
    val hasCommands: Boolean get() = dbcCommands.isNotEmpty()

    /** Whether there were any parse errors */
    val hasErrors: Boolean get() = parseErrors.isNotEmpty()
}
