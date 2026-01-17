package at.planqton.directcan.data.gemini

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import at.planqton.directcan.data.dbc.DbcRepository
import at.planqton.directcan.data.dbc.DbcMessage
import at.planqton.directcan.data.dbc.DbcSignal
import at.planqton.directcan.data.dbc.DbcFileInfo

private const val TAG = "GeminiRepository"

private val Context.geminiDataStore: DataStore<Preferences> by preferencesDataStore(name = "gemini_settings")

@Serializable
data class ChatMessage(
    val role: String,  // "user" or "model"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class ChatSession(
    val id: String,
    val snapshotName: String,
    val snapshotData: String,
    val messages: List<ChatMessage>,
    val createdAt: Long = System.currentTimeMillis(),
    val linkedDbcPath: String? = null  // Path to linked DBC file for AI-generated definitions
)

class GeminiRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private object Keys {
        val API_KEY = stringPreferencesKey("gemini_api_key")
        val SELECTED_MODEL = stringPreferencesKey("gemini_selected_model")
        val CHAT_SESSIONS = stringPreferencesKey("gemini_chat_sessions")
        val ACTIVE_CHAT_ID = stringPreferencesKey("gemini_active_chat_id")
        val DELTA_MODE = booleanPreferencesKey("gemini_delta_mode")
    }

    // Available models (will be populated after API key is set)
    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    // Loading/Error states
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Current generative model instance
    private var generativeModel: GenerativeModel? = null

    // Chat sessions
    private val _chatSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val chatSessions: StateFlow<List<ChatSession>> = _chatSessions.asStateFlow()

    private val _activeChatId = MutableStateFlow<String?>(null)
    val activeChatId: StateFlow<String?> = _activeChatId.asStateFlow()

    // DBC changes from AI responses
    private val _lastDbcChanges = MutableStateFlow<List<DbcChangeResult>>(emptyList())
    val lastDbcChanges: StateFlow<List<DbcChangeResult>> = _lastDbcChanges.asStateFlow()

    // Response parser for DBC commands
    private val responseParser = GeminiResponseParser()

    // API Key
    val apiKey: Flow<String?> = context.geminiDataStore.data.map { it[Keys.API_KEY] }

    // Selected Model
    val selectedModel: Flow<String?> = context.geminiDataStore.data.map { it[Keys.SELECTED_MODEL] }

    // Delta Mode - compress snapshots to only show changes
    val deltaMode: Flow<Boolean> = context.geminiDataStore.data.map { it[Keys.DELTA_MODE] ?: false }

    suspend fun setDeltaMode(enabled: Boolean) {
        context.geminiDataStore.edit { it[Keys.DELTA_MODE] = enabled }
    }

    suspend fun setApiKey(key: String) {
        Log.i(TAG, "Setting API key (length: ${key.length})")
        context.geminiDataStore.edit { it[Keys.API_KEY] = key }
        if (key.isNotBlank()) {
            loadAvailableModels(key)
        }
    }

    suspend fun setSelectedModel(model: String) {
        Log.i(TAG, "Setting selected model: $model")
        context.geminiDataStore.edit { it[Keys.SELECTED_MODEL] = model }
        initializeModel()
    }

    suspend fun loadAvailableModels(apiKey: String? = null) {
        val key = apiKey ?: context.geminiDataStore.data.first()[Keys.API_KEY]
        if (key.isNullOrBlank()) {
            _availableModels.value = emptyList()
            return
        }

        _isLoading.value = true
        _error.value = null

        try {
            // Fetch models from Gemini API
            val models = withContext(Dispatchers.IO) {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$key")
                val response = url.readText()
                val jsonResponse = Json.parseToJsonElement(response).jsonObject

                jsonResponse["models"]?.jsonArray?.mapNotNull { model ->
                    val modelObj = model.jsonObject
                    val name = modelObj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val supportedMethods = modelObj["supportedGenerationMethods"]?.jsonArray
                        ?.map { it.jsonPrimitive.content } ?: emptyList()

                    // Extract model ID from "models/gemini-1.5-flash" format
                    val modelId = name.removePrefix("models/")

                    // Only include Gemini models (not Gemma, etc.) that support generateContent
                    if (modelId.startsWith("gemini-") && supportedMethods.contains("generateContent")) {
                        modelId
                    } else null
                } ?: emptyList()
            }

            _availableModels.value = models.sortedDescending()
            Log.i(TAG, "Loaded ${models.size} models from API")

        } catch (e: kotlinx.coroutines.CancellationException) {
            // Don't show error for cancelled coroutines
            Log.d(TAG, "Model loading cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error loading models", e)
            _error.value = "Fehler beim Laden der Modelle: ${e.message}"
            _availableModels.value = emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun initializeModel() {
        val key = context.geminiDataStore.data.first()[Keys.API_KEY]
        val model = context.geminiDataStore.data.first()[Keys.SELECTED_MODEL]

        if (key.isNullOrBlank() || model.isNullOrBlank()) {
            Log.d(TAG, "Cannot initialize model - missing API key or model selection")
            generativeModel = null
            return
        }

        try {
            Log.d(TAG, "Initializing Gemini model: $model")
            generativeModel = GenerativeModel(
                modelName = model,
                apiKey = key,
                generationConfig = generationConfig {
                    temperature = 0.7f
                    topK = 40
                    topP = 0.95f
                    maxOutputTokens = 8192
                }
            )
            Log.i(TAG, "Gemini model initialized: $model")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing model", e)
            _error.value = "Fehler beim Initialisieren des Modells: ${e.message}"
        }
    }

    suspend fun testConnection(): Boolean {
        val model = generativeModel ?: run {
            initializeModel()
            generativeModel
        }

        if (model == null) {
            _error.value = "Kein Modell konfiguriert"
            return false
        }

        return try {
            _isLoading.value = true
            Log.d(TAG, "Testing Gemini connection...")
            val response = model.generateContent("Say 'OK' if you can hear me.")
            _error.value = null
            val success = response.text?.contains("OK", ignoreCase = true) == true || response.text != null
            Log.i(TAG, "Connection test ${if (success) "successful" else "failed"}")
            success
        } catch (e: com.google.ai.client.generativeai.type.GoogleGenerativeAIException) {
            Log.e(TAG, "Connection test failed (GoogleGenerativeAIException)", e)
            _error.value = "API Fehler: ${e.message}"
            false
        } catch (e: com.google.ai.client.generativeai.type.InvalidAPIKeyException) {
            Log.e(TAG, "Invalid API key", e)
            _error.value = "Ungültiger API-Key. Bitte überprüfen Sie den Key."
            false
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "Network error", e)
            _error.value = "Netzwerkfehler: Keine Internetverbindung"
            false
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed: ${e.javaClass.simpleName}", e)
            _error.value = "Verbindungstest fehlgeschlagen: ${e.javaClass.simpleName} - ${e.message}"
            false
        } finally {
            _isLoading.value = false
        }
    }

    // Chat functionality
    suspend fun loadChatSessions() {
        Log.d(TAG, "Loading chat sessions...")
        val sessionsJson = context.geminiDataStore.data.first()[Keys.CHAT_SESSIONS]
        if (!sessionsJson.isNullOrBlank()) {
            try {
                _chatSessions.value = json.decodeFromString(sessionsJson)
                Log.d(TAG, "Loaded ${_chatSessions.value.size} chat sessions")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading chat sessions", e)
                _chatSessions.value = emptyList()
            }
        }
        _activeChatId.value = context.geminiDataStore.data.first()[Keys.ACTIVE_CHAT_ID]
    }

    private suspend fun saveChatSessions() {
        val sessionsJson = json.encodeToString(_chatSessions.value)
        context.geminiDataStore.edit { it[Keys.CHAT_SESSIONS] = sessionsJson }
    }

    suspend fun createChatSession(snapshotName: String, snapshotData: String): String {
        val sessionId = "chat_${System.currentTimeMillis()}"
        Log.i(TAG, "Creating chat session: $snapshotName (id: $sessionId)")
        val session = ChatSession(
            id = sessionId,
            snapshotName = snapshotName,
            snapshotData = snapshotData,
            messages = emptyList()
        )
        _chatSessions.value = _chatSessions.value + session
        _activeChatId.value = sessionId
        context.geminiDataStore.edit { it[Keys.ACTIVE_CHAT_ID] = sessionId }
        saveChatSessions()
        return sessionId
    }

    /**
     * Create a chat session with a linked DBC file for AI-generated definitions.
     */
    suspend fun createChatSessionWithDbc(
        snapshotName: String,
        snapshotData: String,
        dbcRepository: DbcRepository
    ): String {
        val sessionId = "chat_${System.currentTimeMillis()}"
        val dbcName = "gemini_ai_${snapshotName.lowercase().replace(Regex("[^a-z0-9]"), "_")}"

        // Create new DBC file for this session
        val dbcResult = dbcRepository.createNewDbc(
            name = dbcName,
            description = "AI-generierte DBC für $snapshotName"
        )

        val dbcPath = dbcResult.getOrNull()?.path

        val session = ChatSession(
            id = sessionId,
            snapshotName = snapshotName,
            snapshotData = snapshotData,
            messages = emptyList(),
            linkedDbcPath = dbcPath
        )

        _chatSessions.value = _chatSessions.value + session
        _activeChatId.value = sessionId
        context.geminiDataStore.edit { it[Keys.ACTIVE_CHAT_ID] = sessionId }
        saveChatSessions()

        return sessionId
    }

    /**
     * Link an existing DBC file to a chat session.
     */
    suspend fun linkDbcToChat(chatId: String, dbcPath: String) {
        val session = _chatSessions.value.find { it.id == chatId } ?: return
        val updated = session.copy(linkedDbcPath = dbcPath)
        _chatSessions.value = _chatSessions.value.map {
            if (it.id == chatId) updated else it
        }
        saveChatSessions()
    }

    /**
     * Get the linked DBC info for a chat session.
     */
    fun getLinkedDbcInfo(chatId: String, dbcRepository: DbcRepository): DbcFileInfo? {
        val session = _chatSessions.value.find { it.id == chatId } ?: return null
        val path = session.linkedDbcPath ?: return null
        return dbcRepository.dbcFiles.value.find { it.path == path }
    }

    /**
     * Clear the last DBC changes (after user has seen them).
     */
    fun clearDbcChanges() {
        _lastDbcChanges.value = emptyList()
    }

    /**
     * Execute DBC commands extracted from Gemini responses.
     */
    suspend fun executeDbcCommands(
        dbcInfo: DbcFileInfo,
        commands: List<DbcCommand>,
        dbcRepository: DbcRepository
    ): List<DbcChangeResult> {
        val results = mutableListOf<DbcChangeResult>()

        for (command in commands) {
            val result = when (command) {
                is DbcCommand.AddMessage -> {
                    val message = DbcMessage(
                        id = command.id,
                        name = command.name,
                        length = command.length,
                        transmitter = command.transmitter,
                        description = command.description,
                        isExtended = command.isExtended
                    )
                    dbcRepository.addMessage(dbcInfo, message).fold(
                        onSuccess = { DbcChangeResult(command, true, "Message ${command.name} hinzugefügt") },
                        onFailure = { DbcChangeResult(command, false, it.message ?: "Fehler") }
                    )
                }

                is DbcCommand.UpdateMessage -> {
                    dbcRepository.getDbcFile(dbcInfo).fold(
                        onSuccess = { dbcFile ->
                            val existing = dbcFile.findMessage(command.id)
                            if (existing != null) {
                                val updated = existing.copy(
                                    name = command.name ?: existing.name,
                                    length = command.length ?: existing.length,
                                    transmitter = command.transmitter ?: existing.transmitter,
                                    description = command.description ?: existing.description
                                )
                                dbcRepository.updateMessage(dbcInfo, updated).fold(
                                    onSuccess = { DbcChangeResult(command, true, "Message aktualisiert") },
                                    onFailure = { DbcChangeResult(command, false, it.message ?: "Fehler") }
                                )
                            } else {
                                DbcChangeResult(command, false, "Message nicht gefunden")
                            }
                        },
                        onFailure = { DbcChangeResult(command, false, it.message ?: "Fehler") }
                    )
                }

                is DbcCommand.DeleteMessage -> {
                    dbcRepository.deleteMessage(dbcInfo, command.id).fold(
                        onSuccess = { DbcChangeResult(command, true, "Message gelöscht") },
                        onFailure = { DbcChangeResult(command, false, it.message ?: "Fehler") }
                    )
                }

                is DbcCommand.AddSignal -> {
                    val signal = DbcSignal(
                        name = command.name,
                        startBit = command.startBit,
                        length = command.length,
                        byteOrder = if (command.byteOrder == "BIG_ENDIAN")
                            DbcSignal.ByteOrder.BIG_ENDIAN else DbcSignal.ByteOrder.LITTLE_ENDIAN,
                        valueType = if (command.valueType == "SIGNED")
                            DbcSignal.ValueType.SIGNED else DbcSignal.ValueType.UNSIGNED,
                        factor = command.factor,
                        offset = command.offset,
                        min = command.min,
                        max = command.max,
                        unit = command.unit,
                        description = command.description,
                        valueDescriptions = command.valueDescriptions
                    )
                    dbcRepository.addSignal(dbcInfo, command.messageId, signal).fold(
                        onSuccess = { DbcChangeResult(command, true, "Signal ${command.name} hinzugefügt") },
                        onFailure = { DbcChangeResult(command, false, it.message ?: "Fehler") }
                    )
                }

                is DbcCommand.UpdateSignal -> {
                    dbcRepository.getDbcFile(dbcInfo).fold(
                        onSuccess = { dbcFile ->
                            val message = dbcFile.findMessage(command.messageId)
                            val existing = message?.signals?.find { it.name == command.signalName }
                            if (existing != null) {
                                val updated = existing.copy(
                                    name = command.newName ?: existing.name,
                                    startBit = command.startBit ?: existing.startBit,
                                    length = command.length ?: existing.length,
                                    byteOrder = command.byteOrder?.let {
                                        if (it == "BIG_ENDIAN") DbcSignal.ByteOrder.BIG_ENDIAN
                                        else DbcSignal.ByteOrder.LITTLE_ENDIAN
                                    } ?: existing.byteOrder,
                                    valueType = command.valueType?.let {
                                        if (it == "SIGNED") DbcSignal.ValueType.SIGNED
                                        else DbcSignal.ValueType.UNSIGNED
                                    } ?: existing.valueType,
                                    factor = command.factor ?: existing.factor,
                                    offset = command.offset ?: existing.offset,
                                    min = command.min ?: existing.min,
                                    max = command.max ?: existing.max,
                                    unit = command.unit ?: existing.unit,
                                    description = command.description ?: existing.description
                                )
                                dbcRepository.updateSignal(dbcInfo, command.messageId, command.signalName, updated).fold(
                                    onSuccess = { DbcChangeResult(command, true, "Signal aktualisiert") },
                                    onFailure = { DbcChangeResult(command, false, it.message ?: "Fehler") }
                                )
                            } else {
                                DbcChangeResult(command, false, "Signal nicht gefunden")
                            }
                        },
                        onFailure = { DbcChangeResult(command, false, it.message ?: "Fehler") }
                    )
                }

                is DbcCommand.DeleteSignal -> {
                    dbcRepository.deleteSignal(dbcInfo, command.messageId, command.signalName).fold(
                        onSuccess = { DbcChangeResult(command, true, "Signal gelöscht") },
                        onFailure = { DbcChangeResult(command, false, it.message ?: "Fehler") }
                    )
                }
            }
            results.add(result)
        }

        _lastDbcChanges.value = results
        return results
    }

    suspend fun setActiveChatId(chatId: String?) {
        _activeChatId.value = chatId
        context.geminiDataStore.edit {
            if (chatId != null) {
                it[Keys.ACTIVE_CHAT_ID] = chatId
            } else {
                it.remove(Keys.ACTIVE_CHAT_ID)
            }
        }
    }

    fun getActiveChat(): ChatSession? {
        val id = _activeChatId.value ?: return null
        return _chatSessions.value.find { it.id == id }
    }

    suspend fun sendMessage(chatId: String, userMessage: String): String? {
        val model = generativeModel ?: run {
            initializeModel()
            generativeModel
        }

        if (model == null) {
            _error.value = "Kein Modell konfiguriert"
            return null
        }

        val session = _chatSessions.value.find { it.id == chatId } ?: return null

        _isLoading.value = true
        _error.value = null

        try {
            // Build conversation history
            val history = mutableListOf<Content>()

            // Check if delta mode is enabled and apply compression
            val isDeltaMode = context.geminiDataStore.data.first()[Keys.DELTA_MODE] ?: false
            val snapshotForAI = if (isDeltaMode) {
                compressToDeltas(session.snapshotData)
            } else {
                session.snapshotData
            }

            // System context with snapshot data and DBC context
            val systemPrompt = buildString {
                appendLine("Du bist ein CAN-Bus Analyse-Assistent.")
                appendLine("Der Benutzer hat einen Snapshot von CAN-Bus Frames, den du analysieren sollst.")
                appendLine()
                appendLine("SNAPSHOT DATEN (${session.snapshotName}):")
                appendLine(snapshotForAI)
                appendLine()

                // Add DBC context if linked
                if (session.linkedDbcPath != null) {
                    appendLine("=== DBC-DATEI VERKNÜPFT ===")
                    appendLine()
                    appendLine("Eine DBC-Datei ist mit diesem Chat verknüpft.")
                    appendLine("Du KANNST CAN-Messages und Signale in die DBC speichern - aber NUR wenn der User es anfordert!")
                    appendLine()
                    appendLine("WICHTIGE REGELN:")
                    appendLine("1. Gib DBC-Befehle NUR aus, wenn der User es anfordert UND bestätigt hat!")
                    appendLine("2. ABLAUF wenn User DBC-Einträge will ('speichere', 'lade in DBC', 'erweitere DBC', 'füge hinzu', 'trag ein'):")
                    appendLine("   a) ERST: Zeige was du eintragen würdest (Message-Name, ID, Signale, Werte)")
                    appendLine("   b) FRAGE: 'Soll ich das so in die DBC eintragen? (ja/nein)'")
                    appendLine("   c) NUR bei 'ja'/'ok'/'passt'/'speichern': Gib die JSON-Befehle aus")
                    appendLine("3. Bei reinen Analyse-Fragen: Erkläre nur, keine DBC-Befehle")
                    appendLine("4. Gib NIEMALS rohen DBC-Code aus (VERSION, NS_, CM_, etc.) - nur JSON-Befehle")
                    appendLine("5. Bei Unsicherheit (Byte-Order, Faktor, Einheit): FRAGE erst den Benutzer!")
                    appendLine()
                    appendLine("WENN der User DBC-Einträge erstellen will, nutze dieses JSON-Format:")
                    appendLine("```json")
                    appendLine("""{
  "commands": [
    {"type": "addMessage", "id": 513, "name": "EngineRPM", "length": 8, "description": "Motor Drehzahl"},
    {"type": "addSignal", "messageId": 513, "name": "RPM", "startBit": 0, "length": 16, "factor": 0.25, "offset": 0, "unit": "rpm", "byteOrder": "LITTLE_ENDIAN", "valueType": "UNSIGNED"}
  ]
}""")
                    appendLine("```")
                    appendLine()
                    appendLine("VERFÜGBARE BEFEHLE:")
                    appendLine("• addMessage: id (DEZIMAL!), name, length (1-8), description, transmitter")
                    appendLine("• addSignal: messageId (DEZIMAL!), name, startBit, length, byteOrder (LITTLE_ENDIAN/BIG_ENDIAN), valueType (UNSIGNED/SIGNED), factor, offset, min, max, unit, description")
                    appendLine("• updateMessage/updateSignal: Wie add, aktualisiert bestehende Einträge")
                    appendLine("• deleteMessage/deleteSignal: Löscht Einträge")
                    appendLine()
                    appendLine("HINWEIS: IDs immer DEZIMAL angeben (0x201 = 513)")
                    appendLine()
                }

                appendLine("Hilf dem Benutzer bei der Analyse dieser CAN-Bus Daten.")
                appendLine("Erkläre Frame-IDs, Datenbytes, mögliche Signale und ihre Bedeutung.")
                appendLine("Antworte auf Deutsch.")
            }

            // Add history
            history.add(content("user") { text(systemPrompt) })
            val initialResponse = if (session.linkedDbcPath != null) {
                "Ich habe den Snapshot geladen und eine DBC-Datei ist verknüpft. Ich kann erkannte Signale direkt in die DBC eintragen. Was möchtest du wissen?"
            } else {
                "Ich habe den Snapshot geladen und bin bereit, dir bei der Analyse zu helfen. Was möchtest du wissen?"
            }
            history.add(content("model") { text(initialResponse) })

            // Add previous messages
            session.messages.forEach { msg ->
                history.add(content(msg.role) { text(msg.content) })
            }

            // Create chat with history
            val chat = model.startChat(history)

            // Send user message
            val response = chat.sendMessage(userMessage)
            val responseText = response.text ?: "Keine Antwort erhalten"

            // Update session with new messages
            val updatedMessages = session.messages + listOf(
                ChatMessage("user", userMessage),
                ChatMessage("model", responseText)
            )
            val updatedSession = session.copy(messages = updatedMessages)
            _chatSessions.value = _chatSessions.value.map {
                if (it.id == chatId) updatedSession else it
            }
            saveChatSessions()

            return responseText
        } catch (e: com.google.ai.client.generativeai.type.ServerException) {
            Log.e(TAG, "Server error", e)
            _error.value = "Server-Fehler: ${e.message}"
            return null
        } catch (e: com.google.ai.client.generativeai.type.InvalidAPIKeyException) {
            Log.e(TAG, "Invalid API key", e)
            _error.value = "Ungültiger API-Key"
            return null
        } catch (e: com.google.ai.client.generativeai.type.PromptBlockedException) {
            Log.e(TAG, "Prompt blocked", e)
            _error.value = "Anfrage wurde blockiert (Safety Filter)"
            return null
        } catch (e: com.google.ai.client.generativeai.type.SerializationException) {
            Log.e(TAG, "Serialization error", e)
            _error.value = "Antwort-Format-Fehler. Versuche ein anderes Modell (z.B. gemini-1.5-flash)"
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message: ${e.javaClass.simpleName}", e)
            _error.value = "Fehler: ${e.javaClass.simpleName} - ${e.message}"
            return null
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun deleteChatSession(chatId: String) {
        Log.i(TAG, "Deleting chat session: $chatId")
        _chatSessions.value = _chatSessions.value.filter { it.id != chatId }
        if (_activeChatId.value == chatId) {
            _activeChatId.value = null
            context.geminiDataStore.edit { it.remove(Keys.ACTIVE_CHAT_ID) }
        }
        saveChatSessions()
    }

    suspend fun updateSnapshotData(chatId: String, newSnapshotData: String) {
        val session = _chatSessions.value.find { it.id == chatId } ?: return
        val updatedSession = session.copy(snapshotData = newSnapshotData)
        _chatSessions.value = _chatSessions.value.map {
            if (it.id == chatId) updatedSession else it
        }
        saveChatSessions()
    }

    suspend fun clearError() {
        _error.value = null
    }

    // ==================== CHAT EXPORT ====================

    /**
     * Export format options for chat sessions.
     */
    enum class ChatExportFormat {
        JSON,      // Full JSON with all metadata
        MARKDOWN,  // Human-readable Markdown
        TEXT       // Plain text
    }

    /**
     * Export a single chat session.
     */
    fun exportChatSession(chatId: String, format: ChatExportFormat): String? {
        val session = _chatSessions.value.find { it.id == chatId } ?: return null
        return when (format) {
            ChatExportFormat.JSON -> exportChatAsJson(session)
            ChatExportFormat.MARKDOWN -> exportChatAsMarkdown(session)
            ChatExportFormat.TEXT -> exportChatAsText(session)
        }
    }

    /**
     * Export all chat sessions.
     */
    fun exportAllChats(format: ChatExportFormat): String {
        val sessions = _chatSessions.value
        if (sessions.isEmpty()) return "Keine Chat-Sessions vorhanden."

        return when (format) {
            ChatExportFormat.JSON -> {
                json.encodeToString(sessions)
            }
            ChatExportFormat.MARKDOWN -> {
                buildString {
                    appendLine("# DirectCAN AI Chat Export")
                    appendLine()
                    appendLine("Exportiert: ${formatTimestamp(System.currentTimeMillis())}")
                    appendLine("Anzahl Chats: ${sessions.size}")
                    appendLine()
                    appendLine("---")
                    appendLine()
                    sessions.forEachIndexed { index, session ->
                        append(exportChatAsMarkdown(session))
                        if (index < sessions.size - 1) {
                            appendLine()
                            appendLine("---")
                            appendLine()
                        }
                    }
                }
            }
            ChatExportFormat.TEXT -> {
                buildString {
                    appendLine("DIRECTCAN AI CHAT EXPORT")
                    appendLine("========================")
                    appendLine()
                    appendLine("Exportiert: ${formatTimestamp(System.currentTimeMillis())}")
                    appendLine("Anzahl Chats: ${sessions.size}")
                    appendLine()
                    sessions.forEachIndexed { index, session ->
                        append(exportChatAsText(session))
                        if (index < sessions.size - 1) {
                            appendLine()
                            appendLine("=" .repeat(60))
                            appendLine()
                        }
                    }
                }
            }
        }
    }

    private fun exportChatAsJson(session: ChatSession): String {
        return json.encodeToString(session)
    }

    private fun exportChatAsMarkdown(session: ChatSession): String {
        return buildString {
            appendLine("## ${session.snapshotName}")
            appendLine()
            appendLine("- **Chat ID:** `${session.id}`")
            appendLine("- **Erstellt:** ${formatTimestamp(session.createdAt)}")
            appendLine("- **Nachrichten:** ${session.messages.size}")
            if (session.linkedDbcPath != null) {
                appendLine("- **Verknüpfte DBC:** `${session.linkedDbcPath}`")
            }
            appendLine()

            // Snapshot data (collapsible in MD readers)
            appendLine("<details>")
            appendLine("<summary>Snapshot-Daten (klicken zum Öffnen)</summary>")
            appendLine()
            appendLine("```")
            appendLine(session.snapshotData)
            appendLine("```")
            appendLine()
            appendLine("</details>")
            appendLine()

            // Messages
            appendLine("### Konversation")
            appendLine()
            session.messages.forEach { message ->
                val role = if (message.role == "user") "👤 **User**" else "🤖 **Gemini**"
                val time = formatTimestamp(message.timestamp)
                appendLine("$role _(${time})_:")
                appendLine()
                // Indent message content
                message.content.lines().forEach { line ->
                    appendLine("> $line")
                }
                appendLine()
            }
        }
    }

    private fun exportChatAsText(session: ChatSession): String {
        return buildString {
            appendLine("CHAT: ${session.snapshotName}")
            appendLine("-".repeat(60))
            appendLine("Chat ID: ${session.id}")
            appendLine("Erstellt: ${formatTimestamp(session.createdAt)}")
            appendLine("Nachrichten: ${session.messages.size}")
            if (session.linkedDbcPath != null) {
                appendLine("Verknüpfte DBC: ${session.linkedDbcPath}")
            }
            appendLine()

            appendLine("SNAPSHOT-DATEN:")
            appendLine("-".repeat(40))
            appendLine(session.snapshotData)
            appendLine("-".repeat(40))
            appendLine()

            appendLine("KONVERSATION:")
            appendLine("-".repeat(40))
            session.messages.forEach { message ->
                val role = if (message.role == "user") "[USER]" else "[GEMINI]"
                val time = formatTimestamp(message.timestamp)
                appendLine("$role ($time):")
                appendLine(message.content)
                appendLine()
            }
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        val format = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMAN)
        return format.format(Date(timestamp))
    }

    /**
     * Get suggested filename for export.
     */
    fun getExportFilename(chatId: String?, format: ChatExportFormat): String {
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val extension = when (format) {
            ChatExportFormat.JSON -> "json"
            ChatExportFormat.MARKDOWN -> "md"
            ChatExportFormat.TEXT -> "txt"
        }

        return if (chatId != null) {
            val session = _chatSessions.value.find { it.id == chatId }
            val name = session?.snapshotName?.replace(Regex("[^a-zA-Z0-9]"), "_") ?: "chat"
            "directcan_chat_${name}_$dateStr.$extension"
        } else {
            "directcan_all_chats_$dateStr.$extension"
        }
    }

    /**
     * Compresses log data to delta format.
     * First snapshot remains complete, subsequent snapshots only show changed frames.
     */
    fun compressToDeltas(logContent: String): String {
        // Regex to match snapshots
        val snapshotPattern = Regex(
            """=== SNAPSHOT: (.+?) ===\s*\nTime: (.+?)\nFrames: (\d+)\n---\n([\s\S]*?)=== END SNAPSHOT ===""",
            RegexOption.MULTILINE
        )

        // Regex to match individual frames: "0x0B4 [8] 00 00 00 00 00 00 00 00 | ........"
        val framePattern = Regex("""(0x[0-9A-Fa-f]+)\s+\[(\d+)\]\s+([0-9A-Fa-f]{2}(?:\s+[0-9A-Fa-f]{2})*)\s*\|.*""")

        data class Frame(val id: String, val length: Int, val data: String, val fullLine: String)
        data class Snapshot(val name: String, val time: String, val frameCount: Int, val frames: Map<String, Frame>)

        fun parseFrames(frameData: String): Map<String, Frame> {
            val frames = mutableMapOf<String, Frame>()
            frameData.lines().forEach { line ->
                framePattern.find(line)?.let { match ->
                    val id = match.groupValues[1]
                    val length = match.groupValues[2].toInt()
                    val data = match.groupValues[3]
                    frames[id] = Frame(id, length, data, line.trim())
                }
            }
            return frames
        }

        // Parse all snapshots
        val snapshots = snapshotPattern.findAll(logContent).map { match ->
            Snapshot(
                name = match.groupValues[1],
                time = match.groupValues[2],
                frameCount = match.groupValues[3].toInt(),
                frames = parseFrames(match.groupValues[4])
            )
        }.toList()

        if (snapshots.isEmpty()) {
            return logContent // No snapshots found, return original
        }

        // Extract header (everything before first snapshot)
        val firstSnapshotStart = logContent.indexOf("=== SNAPSHOT:")
        val header = if (firstSnapshotStart > 0) {
            logContent.substring(0, firstSnapshotStart).trim()
                .replace("# Created:", "# Created:") + "\n# Format: Delta-Compressed\n\n"
        } else {
            "# Format: Delta-Compressed\n\n"
        }

        return buildString {
            append(header)

            snapshots.forEachIndexed { index, snapshot ->
                if (index == 0) {
                    // First snapshot: output completely
                    appendLine("=== SNAPSHOT: ${snapshot.name} ===")
                    appendLine("Time: ${snapshot.time}")
                    appendLine("Frames: ${snapshot.frameCount}")
                    appendLine("---")
                    snapshot.frames.values.forEach { frame ->
                        appendLine(frame.fullLine)
                    }
                    appendLine("=== END SNAPSHOT ===")
                } else {
                    // Subsequent snapshots: only output changed frames
                    val previousSnapshot = snapshots[index - 1]
                    val changedFrames = snapshot.frames.filter { (id, frame) ->
                        val prevFrame = previousSnapshot.frames[id]
                        prevFrame == null || prevFrame.data != frame.data
                    }

                    appendLine()
                    appendLine("=== DELTA: ${snapshot.name} ===")
                    appendLine("Time: ${snapshot.time}")
                    appendLine("Changes: ${changedFrames.size}")
                    appendLine("---")
                    changedFrames.values.forEach { frame ->
                        appendLine(frame.fullLine)
                    }
                    appendLine("=== END DELTA ===")
                }
            }
        }
    }
}
