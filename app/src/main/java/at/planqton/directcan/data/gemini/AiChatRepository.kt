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
import kotlinx.coroutines.runBlocking
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

private const val TAG = "AiChatRepository"

private val Context.aiChatDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_chat_settings")

@Serializable
data class ChatMessage(
    val role: String,  // "user" or "model"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toAiMessage(): AiMessage = AiMessage(
        role = if (role == "model") "assistant" else role,
        content = content
    )
}

/**
 * Type of AI chat session
 */
@Serializable
enum class ChatType {
    ANALYSIS,  // Snapshot/log analysis chat (read-only analysis)
    EXPLORE    // Live CAN bus exploration chat (can send commands)
}

@Serializable
data class ChatSession(
    val id: String,
    val snapshotName: String,
    val snapshotData: String,
    val messages: List<ChatMessage>,
    val createdAt: Long = System.currentTimeMillis(),
    val linkedDbcPath: String? = null,  // Path to linked DBC file for AI-generated definitions
    val providerType: String? = null,   // Provider used for this chat (e.g. "OPENCODE_ZEN")
    val modelId: String? = null,        // Model ID used for this chat
    val apiKey: String? = null,         // API key used for this chat (encrypted in real app)
    val chatType: ChatType = ChatType.ANALYSIS  // Type of chat (analysis or explore)
)

class AiChatRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private object Keys {
        val PROVIDER = stringPreferencesKey("ai_provider")
        val API_KEY = stringPreferencesKey("gemini_api_key")
        val OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val ANTHROPIC_API_KEY = stringPreferencesKey("anthropic_api_key")
        val OPENROUTER_API_KEY = stringPreferencesKey("openrouter_api_key")
        val OPENCODE_ZEN_API_KEY = stringPreferencesKey("opencode_zen_api_key")
        val SELECTED_MODEL = stringPreferencesKey("gemini_selected_model")
        val CHAT_SESSIONS = stringPreferencesKey("gemini_chat_sessions")
        val ACTIVE_CHAT_ID = stringPreferencesKey("gemini_active_chat_id")
        val DELTA_MODE = booleanPreferencesKey("gemini_delta_mode")
        val AI_TIMEOUT_SECONDS = intPreferencesKey("ai_timeout_seconds")
    }

    // Current AI provider
    private val _currentProvider = MutableStateFlow(AiProviderType.GEMINI)
    val currentProvider: StateFlow<AiProviderType> = _currentProvider.asStateFlow()

    // Available models (will be populated after API key is set)
    private val _availableModels = MutableStateFlow<List<AiModelInfo>>(emptyList())
    val availableModels: StateFlow<List<AiModelInfo>> = _availableModels.asStateFlow()

    // Loading/Error states
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Current generative model instance (for Gemini backward compatibility)
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

    // Provider selection
    val provider: Flow<AiProviderType> = context.aiChatDataStore.data.map { prefs ->
        prefs[Keys.PROVIDER]?.let {
            try { AiProviderType.valueOf(it) } catch (e: Exception) { AiProviderType.GEMINI }
        } ?: AiProviderType.GEMINI
    }

    // API Keys for each provider
    val apiKey: Flow<String?> = context.aiChatDataStore.data.map { it[Keys.API_KEY] }
    val openAiApiKey: Flow<String?> = context.aiChatDataStore.data.map { it[Keys.OPENAI_API_KEY] }
    val anthropicApiKey: Flow<String?> = context.aiChatDataStore.data.map { it[Keys.ANTHROPIC_API_KEY] }
    val openRouterApiKey: Flow<String?> = context.aiChatDataStore.data.map { it[Keys.OPENROUTER_API_KEY] }
    val openCodeZenApiKey: Flow<String?> = context.aiChatDataStore.data.map { it[Keys.OPENCODE_ZEN_API_KEY] }

    // Get API key for current provider
    fun getApiKeyForProvider(provider: AiProviderType): Flow<String?> = when (provider) {
        AiProviderType.GEMINI -> apiKey
        AiProviderType.OPENAI -> openAiApiKey
        AiProviderType.ANTHROPIC -> anthropicApiKey
        AiProviderType.OPENROUTER -> openRouterApiKey
        AiProviderType.OPENCODE_ZEN -> openCodeZenApiKey
    }

    // Selected Model
    val selectedModel: Flow<String?> = context.aiChatDataStore.data.map { it[Keys.SELECTED_MODEL] }

    // Combined check: Is AI properly configured (provider + API key + model)?
    // Note: OpenCode Zen doesn't require an API key
    val isAiConfigured: Flow<Boolean> = context.aiChatDataStore.data.map { prefs ->
        val provider = prefs[Keys.PROVIDER]?.let {
            try { AiProviderType.valueOf(it) } catch (e: Exception) { null }
        } ?: AiProviderType.GEMINI

        val apiKey = when (provider) {
            AiProviderType.GEMINI -> prefs[Keys.API_KEY]
            AiProviderType.OPENAI -> prefs[Keys.OPENAI_API_KEY]
            AiProviderType.ANTHROPIC -> prefs[Keys.ANTHROPIC_API_KEY]
            AiProviderType.OPENROUTER -> prefs[Keys.OPENROUTER_API_KEY]
            AiProviderType.OPENCODE_ZEN -> prefs[Keys.OPENCODE_ZEN_API_KEY]
        }

        val model = prefs[Keys.SELECTED_MODEL]

        // OpenCode Zen doesn't require API key, others do
        val hasApiKey = !apiKey.isNullOrBlank() || provider == AiProviderType.OPENCODE_ZEN
        val hasModel = !model.isNullOrBlank()

        hasApiKey && hasModel
    }

    // Delta Mode - compress snapshots to only show changes
    val deltaMode: Flow<Boolean> = context.aiChatDataStore.data.map { it[Keys.DELTA_MODE] ?: false }

    // AI Timeout in seconds (default: 120s = 2 minutes)
    val aiTimeout: Flow<Int> = context.aiChatDataStore.data.map { it[Keys.AI_TIMEOUT_SECONDS] ?: 120 }

    suspend fun setAiTimeout(seconds: Int) {
        context.aiChatDataStore.edit { it[Keys.AI_TIMEOUT_SECONDS] = seconds }
    }

    fun getAiTimeoutSync(): Int {
        return runBlocking { aiTimeout.first() }
    }

    suspend fun setProvider(providerType: AiProviderType) {
        Log.i(TAG, "Setting provider: $providerType")
        context.aiChatDataStore.edit {
            it[Keys.PROVIDER] = providerType.name
            // Clear selected model when provider changes to avoid invalid combinations
            it.remove(Keys.SELECTED_MODEL)
        }
        _currentProvider.value = providerType
        // Clear models and reload for new provider
        _availableModels.value = emptyList()
        loadAvailableModels()
    }

    suspend fun setDeltaMode(enabled: Boolean) {
        context.aiChatDataStore.edit { it[Keys.DELTA_MODE] = enabled }
    }

    suspend fun setApiKey(key: String, providerType: AiProviderType = _currentProvider.value) {
        Log.i(TAG, "Setting API key for $providerType (length: ${key.length})")
        val keyPref = when (providerType) {
            AiProviderType.GEMINI -> Keys.API_KEY
            AiProviderType.OPENAI -> Keys.OPENAI_API_KEY
            AiProviderType.ANTHROPIC -> Keys.ANTHROPIC_API_KEY
            AiProviderType.OPENROUTER -> Keys.OPENROUTER_API_KEY
            AiProviderType.OPENCODE_ZEN -> Keys.OPENCODE_ZEN_API_KEY
        }
        context.aiChatDataStore.edit { it[keyPref] = key }
        if (key.isNotBlank() && providerType == _currentProvider.value) {
            loadAvailableModels(key)
        }
    }

    suspend fun setSelectedModel(model: String) {
        Log.i(TAG, "Setting selected model: $model")
        context.aiChatDataStore.edit { it[Keys.SELECTED_MODEL] = model }
        initializeModel()
    }

    suspend fun loadAvailableModels(apiKey: String? = null) {
        val providerType = _currentProvider.value
        val keyPref = when (providerType) {
            AiProviderType.GEMINI -> Keys.API_KEY
            AiProviderType.OPENAI -> Keys.OPENAI_API_KEY
            AiProviderType.ANTHROPIC -> Keys.ANTHROPIC_API_KEY
            AiProviderType.OPENROUTER -> Keys.OPENROUTER_API_KEY
            AiProviderType.OPENCODE_ZEN -> Keys.OPENCODE_ZEN_API_KEY
        }
        val key = apiKey ?: context.aiChatDataStore.data.first()[keyPref] ?: ""

        // OpenCode Zen doesn't require API key, others do
        if (key.isBlank() && providerType != AiProviderType.OPENCODE_ZEN) {
            _availableModels.value = emptyList()
            return
        }

        _isLoading.value = true
        _error.value = null

        try {
            val provider = AiProviderFactory.getProvider(providerType)
            val result = withContext(Dispatchers.IO) {
                provider.loadModels(key)
            }

            result.fold(
                onSuccess = { models ->
                    _availableModels.value = models
                    Log.i(TAG, "Loaded ${models.size} models from ${provider.name}")
                },
                onFailure = { e ->
                    Log.e(TAG, "Error loading models from ${provider.name}", e)
                    _error.value = "Fehler beim Laden der Modelle: ${e.message}"
                    _availableModels.value = emptyList()
                }
            )

        } catch (e: kotlinx.coroutines.CancellationException) {
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

    /**
     * Initialize provider from saved settings
     */
    suspend fun initializeProvider() {
        val savedProvider = context.aiChatDataStore.data.first()[Keys.PROVIDER]
        _currentProvider.value = savedProvider?.let {
            try { AiProviderType.valueOf(it) } catch (e: Exception) { AiProviderType.GEMINI }
        } ?: AiProviderType.GEMINI
    }

    suspend fun initializeModel() {
        val key = context.aiChatDataStore.data.first()[Keys.API_KEY]
        val model = context.aiChatDataStore.data.first()[Keys.SELECTED_MODEL]

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
        val providerType = _currentProvider.value
        val keyPref = when (providerType) {
            AiProviderType.GEMINI -> Keys.API_KEY
            AiProviderType.OPENAI -> Keys.OPENAI_API_KEY
            AiProviderType.ANTHROPIC -> Keys.ANTHROPIC_API_KEY
            AiProviderType.OPENROUTER -> Keys.OPENROUTER_API_KEY
            AiProviderType.OPENCODE_ZEN -> Keys.OPENCODE_ZEN_API_KEY
        }
        val key = context.aiChatDataStore.data.first()[keyPref] ?: ""
        val model = context.aiChatDataStore.data.first()[Keys.SELECTED_MODEL]

        // OpenCode Zen doesn't require API key
        if ((key.isBlank() && providerType != AiProviderType.OPENCODE_ZEN) || model.isNullOrBlank()) {
            _error.value = "Kein API-Key oder Modell konfiguriert"
            return false
        }

        _isLoading.value = true
        _error.value = null

        return try {
            val provider = AiProviderFactory.getProvider(providerType)
            Log.d(TAG, "Testing ${provider.name} connection with model $model...")

            val result = withContext(Dispatchers.IO) {
                provider.testConnection(key, model)
            }

            result.fold(
                onSuccess = { success ->
                    Log.i(TAG, "Connection test ${if (success) "successful" else "failed"}")
                    success
                },
                onFailure = { e ->
                    Log.e(TAG, "Connection test failed", e)
                    _error.value = "Verbindungstest fehlgeschlagen: ${e.message}"
                    false
                }
            )
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "Network error", e)
            _error.value = "Netzwerkfehler: Keine Internetverbindung"
            false
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed: ${e.javaClass.simpleName}", e)
            _error.value = "Verbindungstest fehlgeschlagen: ${e.message}"
            false
        } finally {
            _isLoading.value = false
        }
    }

    // Chat functionality
    suspend fun loadChatSessions() {
        Log.d(TAG, "Loading chat sessions...")
        val sessionsJson = context.aiChatDataStore.data.first()[Keys.CHAT_SESSIONS]
        if (!sessionsJson.isNullOrBlank()) {
            try {
                _chatSessions.value = json.decodeFromString(sessionsJson)
                Log.d(TAG, "Loaded ${_chatSessions.value.size} chat sessions")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading chat sessions", e)
                _chatSessions.value = emptyList()
            }
        }
        _activeChatId.value = context.aiChatDataStore.data.first()[Keys.ACTIVE_CHAT_ID]
    }

    private suspend fun saveChatSessions() {
        val sessionsJson = json.encodeToString(_chatSessions.value)
        context.aiChatDataStore.edit { it[Keys.CHAT_SESSIONS] = sessionsJson }
    }

    suspend fun createChatSession(snapshotName: String, snapshotData: String, chatType: ChatType = ChatType.ANALYSIS): String {
        val sessionId = "chat_${System.currentTimeMillis()}"
        Log.i(TAG, "Creating chat session: $snapshotName (id: $sessionId, type: $chatType)")

        // Get current provider, model, and API key
        val prefs = context.aiChatDataStore.data.first()
        val currentProviderType = _currentProvider.value
        val currentModelId = prefs[Keys.SELECTED_MODEL]
        val currentApiKey = when (currentProviderType) {
            AiProviderType.GEMINI -> prefs[Keys.API_KEY]
            AiProviderType.OPENAI -> prefs[Keys.OPENAI_API_KEY]
            AiProviderType.ANTHROPIC -> prefs[Keys.ANTHROPIC_API_KEY]
            AiProviderType.OPENROUTER -> prefs[Keys.OPENROUTER_API_KEY]
            AiProviderType.OPENCODE_ZEN -> prefs[Keys.OPENCODE_ZEN_API_KEY]
        }

        val session = ChatSession(
            id = sessionId,
            snapshotName = snapshotName,
            snapshotData = snapshotData,
            messages = emptyList(),
            providerType = currentProviderType.name,
            modelId = currentModelId,
            apiKey = currentApiKey ?: "",
            chatType = chatType
        )
        _chatSessions.value = _chatSessions.value + session
        _activeChatId.value = sessionId
        context.aiChatDataStore.edit { it[Keys.ACTIVE_CHAT_ID] = sessionId }
        saveChatSessions()
        return sessionId
    }

    /**
     * Create an explore chat session for live CAN bus interaction
     */
    suspend fun createExploreChatSession(name: String = "CAN Explorer"): String {
        return createChatSession(
            snapshotName = name,
            snapshotData = "", // No initial snapshot data for explore chats
            chatType = ChatType.EXPLORE
        )
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

        // Get current provider, model, and API key
        val prefs = context.aiChatDataStore.data.first()
        val currentProviderType = _currentProvider.value
        val currentModelId = prefs[Keys.SELECTED_MODEL]
        val currentApiKey = when (currentProviderType) {
            AiProviderType.GEMINI -> prefs[Keys.API_KEY]
            AiProviderType.OPENAI -> prefs[Keys.OPENAI_API_KEY]
            AiProviderType.ANTHROPIC -> prefs[Keys.ANTHROPIC_API_KEY]
            AiProviderType.OPENROUTER -> prefs[Keys.OPENROUTER_API_KEY]
            AiProviderType.OPENCODE_ZEN -> prefs[Keys.OPENCODE_ZEN_API_KEY]
        }

        val session = ChatSession(
            id = sessionId,
            snapshotName = snapshotName,
            snapshotData = snapshotData,
            messages = emptyList(),
            linkedDbcPath = dbcPath,
            providerType = currentProviderType.name,
            modelId = currentModelId,
            apiKey = currentApiKey ?: ""
        )

        _chatSessions.value = _chatSessions.value + session
        _activeChatId.value = sessionId
        context.aiChatDataStore.edit { it[Keys.ACTIVE_CHAT_ID] = sessionId }
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
        context.aiChatDataStore.edit {
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

    suspend fun sendMessage(
        chatId: String,
        userMessage: String,
        skipSnapshot: Boolean = false,
        requestDbcUpdate: Boolean = false,
        independentMode: Boolean = false
    ): String? {
        val session = _chatSessions.value.find { it.id == chatId } ?: return null

        // Use session's stored provider/model/key, or fall back to current settings for old chats
        val providerType = session.providerType?.let {
            try { AiProviderType.valueOf(it) } catch (e: Exception) { null }
        } ?: _currentProvider.value

        val modelId = session.modelId ?: context.aiChatDataStore.data.first()[Keys.SELECTED_MODEL]

        val key = if (session.apiKey != null) {
            session.apiKey
        } else {
            // Fall back to current settings for old chats
            val keyPref = when (providerType) {
                AiProviderType.GEMINI -> Keys.API_KEY
                AiProviderType.OPENAI -> Keys.OPENAI_API_KEY
                AiProviderType.ANTHROPIC -> Keys.ANTHROPIC_API_KEY
                AiProviderType.OPENROUTER -> Keys.OPENROUTER_API_KEY
                AiProviderType.OPENCODE_ZEN -> Keys.OPENCODE_ZEN_API_KEY
            }
            context.aiChatDataStore.data.first()[keyPref] ?: ""
        }

        // OpenCode Zen doesn't require API key
        if ((key.isBlank() && providerType != AiProviderType.OPENCODE_ZEN) || modelId.isNullOrBlank()) {
            _error.value = "Kein API-Key oder Modell konfiguriert"
            return null
        }

        _isLoading.value = true
        _error.value = null

        // Add user message immediately so it appears in UI right away
        val userChatMessage = ChatMessage("user", userMessage)
        val sessionWithUserMessage = session.copy(messages = session.messages + userChatMessage)
        _chatSessions.value = _chatSessions.value.map {
            if (it.id == chatId) sessionWithUserMessage else it
        }

        try {
            // Check if delta mode is enabled and apply compression
            val isDeltaMode = context.aiChatDataStore.data.first()[Keys.DELTA_MODE] ?: false
            val snapshotForAI = if (isDeltaMode) {
                compressToDeltas(session.snapshotData)
            } else {
                session.snapshotData
            }

            // System context with snapshot data and DBC context
            val systemPrompt = buildString {
                if (independentMode) {
                    // Completely independent mode - no app context at all
                    appendLine("Du bist ein freundlicher und hilfreicher KI-Assistent.")
                    appendLine("Du hast KEINEN speziellen Kontext zu CAN-Bus, Fahrzeugen oder dieser App.")
                    appendLine("Beantworte alle Fragen wie ein allgemeiner Assistent (ähnlich ChatGPT).")
                    appendLine()
                    appendLine("STIL:")
                    appendLine("- Sei freundlich, hilfsbereit und gesprächig.")
                    appendLine("- Erkläre Dinge verständlich und gib bei Bedarf zusätzliche Infos.")
                    appendLine("- Bei Rechenaufgaben: Zeige den Rechenweg und erkläre das Ergebnis.")
                    appendLine("- Bei Wissensfragen: Gib informative und interessante Antworten.")
                    appendLine("- Antworte in der Sprache des Benutzers (Deutsch oder Englisch).")
                    appendLine("- Sei nicht roboterhaft oder zu knapp - antworte natürlich und menschlich.")
                } else {
                    appendLine("Du bist ein CAN-Bus Analyse-Assistent.")

                    if (skipSnapshot) {
                        appendLine("Beantworte die Frage des Benutzers ohne auf spezifische Snapshot-Daten einzugehen.")
                        appendLine("Dies ist eine allgemeine Frage.")
                        appendLine()
                    } else {
                    appendLine("Der Benutzer hat einen Snapshot von CAN-Bus Frames, den du analysieren sollst.")
                    appendLine()
                    appendLine("SNAPSHOT DATEN (${session.snapshotName}):")
                    appendLine(snapshotForAI)
                    appendLine()
                }

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

                // CAN Commands Section - Always available when device is connected
                appendLine("=== CAN-BUS KOMMUNIKATION ===")
                appendLine()
                appendLine("Du kannst DIREKT mit dem CAN-Bus kommunizieren! Frames senden, UDS/ISO-TP Diagnose durchführen, etc.")
                appendLine()
                appendLine("WICHTIG - KOMMUNIKATIONSSTIL:")
                appendLine("- Erkläre IMMER was du vorhast, BEVOR du einen Befehl ausgibst")
                appendLine("- Beschreibe jeden Schritt wie ein Experte der einem Lehrling erklärt")
                appendLine("- Nach Empfang: Erkläre die Rohdaten UND ihre Bedeutung ausführlich")
                appendLine()
                appendLine("BEISPIEL-DIALOG:")
                appendLine("User: 'Was ist die Drehzahl?'")
                appendLine("Du: 'Ich lese die Motor-Drehzahl über OBD2 Service 01, PID 0x0C.")
                appendLine("     Dazu sende ich einen ISO-TP Request:")
                appendLine("     - Ziel: 0x7DF (OBD2 Broadcast)")
                appendLine("     - Daten: 02 01 0C (2 Bytes: Service 01, PID 0C)")
                appendLine("     - Erwarte Antwort von: 0x7E8 (Motor-ECU)")
                appendLine("     ```json")
                appendLine("     {\"canCommands\": {\"narration\": \"Lese Motor-Drehzahl...\", \"commands\": [")
                appendLine("       {\"type\": \"obd2Pid\", \"pid\": 12, \"txId\": 2015, \"rxId\": 2024}")
                appendLine("     ]}}")
                appendLine("     ```'")
                appendLine("[Nach Ausführung erkläre die Antwort detailliert]")
                appendLine()
                appendLine("VERFÜGBARE CAN-BEFEHLE (JSON-Format):")
                appendLine("```json")
                appendLine("{\"canCommands\": {\"narration\": \"Beschreibung\", \"commands\": [...]}}")
                appendLine("```")
                appendLine()
                appendLine("BEFEHLSTYPEN:")
                appendLine("• sendFrame: Einzelnen CAN-Frame senden (keine Antwort)")
                appendLine("  {\"type\": \"sendFrame\", \"id\": 1234, \"data\": \"02 01 0C\", \"extended\": false}")
                appendLine()
                appendLine("• sendIsoTp: ISO-TP Request mit Antwort-Erwartung")
                appendLine("  {\"type\": \"sendIsoTp\", \"txId\": 2015, \"rxId\": 2024, \"data\": \"02 01 0C\", \"timeoutMs\": 2000}")
                appendLine()
                appendLine("• obd2Pid: OBD2 PID-Abfrage (Service 01/02)")
                appendLine("  {\"type\": \"obd2Pid\", \"pid\": 12, \"service\": 1, \"txId\": 2015, \"rxId\": 2024}")
                appendLine()
                appendLine("• readDtcs: Fehlercodes lesen (UDS Service 0x19)")
                appendLine("  {\"type\": \"readDtcs\", \"txId\": 2015, \"rxId\": 2024}")
                appendLine()
                appendLine("• clearDtcs: Fehlercodes löschen (⚠️ ERST FRAGEN!)")
                appendLine("  {\"type\": \"clearDtcs\", \"txId\": 2015, \"rxId\": 2024}")
                appendLine()
                appendLine("• readVin: Fahrzeug-Identifikationsnummer lesen")
                appendLine("  {\"type\": \"readVin\", \"txId\": 2015, \"rxId\": 2024}")
                appendLine()
                appendLine("• udsRequest: Beliebiger UDS-Service")
                appendLine("  {\"type\": \"udsRequest\", \"txId\": 2015, \"rxId\": 2024, \"service\": 34, \"subFunction\": 1, \"data\": \"00 01 00\"}")
                appendLine()
                appendLine("• scanBus: Bus beobachten und aktive IDs finden")
                appendLine("  {\"type\": \"scanBus\", \"durationMs\": 2000}")
                appendLine()
                appendLine("• observeIds: Bestimmte CAN-IDs beobachten")
                appendLine("  {\"type\": \"observeIds\", \"ids\": [513, 514, 515], \"durationMs\": 2000}")
                appendLine()
                appendLine("• delay: Wartezeit zwischen Befehlen")
                appendLine("  {\"type\": \"delay\", \"milliseconds\": 500}")
                appendLine()
                appendLine("STANDARD OBD2 IDs:")
                appendLine("- TX: 0x7DF (2015) = Broadcast an alle ECUs")
                appendLine("- RX: 0x7E8 (2024) = Motor-ECU Antwort")
                appendLine()
                appendLine("WICHTIGE PIDS (Service 01):")
                appendLine("- 0x0C (12) = RPM (Formel: (A*256+B)/4)")
                appendLine("- 0x0D (13) = Geschwindigkeit (km/h)")
                appendLine("- 0x05 (5) = Kühlmitteltemperatur (°C = Wert - 40)")
                appendLine("- 0x0F (15) = Ansauglufttemperatur (°C = Wert - 40)")
                appendLine("- 0x11 (17) = Drosselklappenstellung (%)")
                appendLine("- 0x2F (47) = Kraftstoffstand (%)")
                appendLine()
                appendLine("SICHERHEITSREGELN:")
                appendLine("- clearDtcs: IMMER erst fragen ob der User wirklich löschen will!")
                appendLine("- ECU-Reset, Security Access: Nur mit expliziter Bestätigung")
                appendLine("- Unbekannte ECUs: Erst mit scanBus erkunden")
                appendLine()

                appendLine("Hilf dem Benutzer bei der Analyse dieser CAN-Bus Daten.")
                appendLine("Erkläre Frame-IDs, Datenbytes, mögliche Signale und ihre Bedeutung.")
                appendLine()
                appendLine("STIL:")
                appendLine("- Antworte kurz, direkt und technisch.")
                appendLine("- Keine Floskeln, keine Höflichkeitsphrasen.")
                appendLine("- Komm direkt zum Punkt.")
                appendLine("- Antworte in der Sprache des Benutzers (Deutsch oder Englisch).")
                } // end else independentMode
            }

            // Build message history
            val messages = mutableListOf<AiMessage>()
            session.messages.forEach { msg ->
                messages.add(msg.toAiMessage())
            }

            // Add DBC update request suffix if requested
            val finalMessage = if (requestDbcUpdate && session.linkedDbcPath != null) {
                "$userMessage\n\nBitte erstelle die passenden DBC-Einträge (Messages und Signale) basierend auf dieser Analyse und gib die JSON-Befehle aus."
            } else {
                userMessage
            }
            messages.add(AiMessage("user", finalMessage))

            // Use the provider abstraction
            val provider = AiProviderFactory.getProvider(providerType)
            val timeoutMs = getAiTimeoutSync() * 1000  // Convert seconds to milliseconds
            val result = withContext(Dispatchers.IO) {
                provider.generateContent(key, modelId, messages, systemPrompt, timeoutMs)
            }

            val responseText = when (result) {
                is AiResult.Success -> result.text
                is AiResult.Error -> {
                    _error.value = result.message
                    return null
                }
            }

            // Update session with AI response (user message was already added above)
            val currentSession = _chatSessions.value.find { it.id == chatId } ?: return null
            val updatedMessages = currentSession.messages + ChatMessage("model", responseText)
            val updatedSession = currentSession.copy(messages = updatedMessages)
            _chatSessions.value = _chatSessions.value.map {
                if (it.id == chatId) updatedSession else it
            }
            saveChatSessions()

            return responseText
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message: ${e.javaClass.simpleName}", e)
            _error.value = "Fehler: ${e.message}"
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
            context.aiChatDataStore.edit { it.remove(Keys.ACTIVE_CHAT_ID) }
        }
        saveChatSessions()
    }

    /**
     * Delete chats older than specified time.
     * @param maxAgeMinutes Maximum age in minutes (0 = delete all)
     * @return Number of deleted chats
     */
    suspend fun deleteOldChats(maxAgeMinutes: Int): Int {
        val now = System.currentTimeMillis()
        val maxAgeMs = maxAgeMinutes * 60 * 1000L

        val toDelete = if (maxAgeMinutes == 0) {
            // Delete all
            _chatSessions.value
        } else {
            // Delete chats where last message is older than maxAge
            _chatSessions.value.filter { session ->
                val lastMessageTime = session.messages.lastOrNull()?.timestamp ?: session.createdAt
                (now - lastMessageTime) > maxAgeMs
            }
        }

        val deletedCount = toDelete.size
        if (deletedCount > 0) {
            Log.i(TAG, "Deleting $deletedCount old chat sessions (maxAge: ${maxAgeMinutes}min)")
            val toDeleteIds = toDelete.map { it.id }.toSet()
            _chatSessions.value = _chatSessions.value.filter { it.id !in toDeleteIds }

            if (_activeChatId.value in toDeleteIds) {
                _activeChatId.value = null
                context.aiChatDataStore.edit { it.remove(Keys.ACTIVE_CHAT_ID) }
            }
            saveChatSessions()
        }
        return deletedCount
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
