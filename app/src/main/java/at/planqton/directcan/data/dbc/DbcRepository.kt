package at.planqton.directcan.data.dbc

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class DbcRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val dbcDir: File
        get() = File(context.filesDir, "dbc").also { it.mkdirs() }

    private val _dbcFiles = MutableStateFlow<List<DbcFileInfo>>(emptyList())
    val dbcFiles: StateFlow<List<DbcFileInfo>> = _dbcFiles.asStateFlow()

    private val _activeDbcFile = MutableStateFlow<DbcFile?>(null)
    val activeDbcFile: StateFlow<DbcFile?> = _activeDbcFile.asStateFlow()

    private val _activeDbc = MutableStateFlow<DbcFileInfo?>(null)
    val activeDbc: StateFlow<DbcFileInfo?> = _activeDbc.asStateFlow()

    init {
        refreshDbcList()
    }

    fun refreshDbcList() {
        val files = dbcDir.listFiles()
            ?.filter { it.extension == "dbc" || it.extension == "json" }
            ?.map { file ->
                DbcFileInfo(
                    name = file.nameWithoutExtension,
                    fileName = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    isActive = _activeDbc.value?.path == file.absolutePath
                )
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()

        _dbcFiles.value = files
    }

    suspend fun importDbcFromUri(uri: Uri, name: String? = null): Result<DbcFileInfo> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(Exception("Cannot open file"))

            val content = inputStream.bufferedReader().readText()
            inputStream.close()

            val parser = DbcParser()
            val dbcFile = parser.parse(content)

            // Ensure directory exists
            val dir = dbcDir
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val fileName = name ?: uri.lastPathSegment?.substringBeforeLast(".") ?: "imported"
            val targetFile = File(dir, "$fileName.dbc")

            // Save original DBC
            targetFile.writeText(content)

            // Also save parsed JSON for faster loading
            val jsonFile = File(dir, "$fileName.json")
            jsonFile.writeText(json.encodeToString(dbcFile))

            refreshDbcList()

            val info = DbcFileInfo(
                name = fileName,
                fileName = targetFile.name,
                path = targetFile.absolutePath,
                size = targetFile.length(),
                lastModified = targetFile.lastModified()
            )

            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importDbcFromText(content: String, name: String): Result<DbcFileInfo> = withContext(Dispatchers.IO) {
        try {
            val parser = DbcParser()
            val dbcFile = parser.parse(content)

            val targetFile = File(dbcDir, "$name.dbc")
            targetFile.writeText(content)

            val jsonFile = File(dbcDir, "$name.json")
            jsonFile.writeText(json.encodeToString(dbcFile))

            refreshDbcList()

            val info = DbcFileInfo(
                name = name,
                fileName = targetFile.name,
                path = targetFile.absolutePath,
                size = targetFile.length(),
                lastModified = targetFile.lastModified()
            )

            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadDbc(info: DbcFileInfo): Result<DbcFile> = withContext(Dispatchers.IO) {
        try {
            val jsonFile = File(info.path.replace(".dbc", ".json"))

            val dbcFile = if (jsonFile.exists()) {
                // Load from cached JSON (faster)
                json.decodeFromString<DbcFile>(jsonFile.readText())
            } else {
                // Parse DBC
                val parser = DbcParser()
                val file = File(info.path)
                parser.parse(file.readText())
            }

            _activeDbcFile.value = dbcFile
            _activeDbc.value = info.copy(isActive = true)
            refreshDbcList()

            Result.success(dbcFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportDbc(info: DbcFileInfo, targetUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(info.path)
            val content = sourceFile.readText()

            context.contentResolver.openOutputStream(targetUri)?.use { output ->
                output.write(content.toByteArray())
            } ?: return@withContext Result.failure(Exception("Cannot write to target"))

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteDbc(info: DbcFileInfo): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            File(info.path).delete()
            File(info.path.replace(".dbc", ".json")).delete()

            if (_activeDbc.value?.path == info.path) {
                _activeDbc.value = null
                _activeDbcFile.value = null
            }

            refreshDbcList()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createNewDbc(name: String, description: String = ""): Result<DbcFileInfo> = withContext(Dispatchers.IO) {
        try {
            val dbcFile = DbcFile(
                version = "1.0",
                description = description
            )

            val content = DbcParser.generateDbc(dbcFile)
            val targetFile = File(dbcDir, "$name.dbc")
            targetFile.writeText(content)

            val jsonFile = File(dbcDir, "$name.json")
            jsonFile.writeText(json.encodeToString(dbcFile))

            refreshDbcList()

            val info = DbcFileInfo(
                name = name,
                fileName = targetFile.name,
                path = targetFile.absolutePath,
                size = targetFile.length(),
                lastModified = targetFile.lastModified()
            )

            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // =============== DBC EDITING METHODS ===============

    /**
     * Save updated DbcFile to disk and refresh state
     */
    private suspend fun saveDbcFile(info: DbcFileInfo, dbcFile: DbcFile): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Save JSON (fast reload)
            val jsonFile = File(info.path.replace(".dbc", ".json"))
            jsonFile.writeText(json.encodeToString(dbcFile))

            // Generate and save DBC (for export compatibility)
            val dbcContent = DbcParser.generateDbc(dbcFile)
            val dbcFileTarget = File(info.path)
            dbcFileTarget.writeText(dbcContent)

            // Update active file if this is the active one
            if (_activeDbc.value?.path == info.path) {
                _activeDbcFile.value = dbcFile
            }

            refreshDbcList()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get the current DbcFile for editing
     */
    suspend fun getDbcFile(info: DbcFileInfo): Result<DbcFile> = withContext(Dispatchers.IO) {
        try {
            val jsonFile = File(info.path.replace(".dbc", ".json"))
            val dbcFile = if (jsonFile.exists()) {
                json.decodeFromString<DbcFile>(jsonFile.readText())
            } else {
                val parser = DbcParser()
                parser.parse(File(info.path).readText())
            }
            Result.success(dbcFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- MESSAGE OPERATIONS ----

    suspend fun addMessage(info: DbcFileInfo, message: DbcMessage): Result<Unit> {
        return getDbcFile(info).fold(
            onSuccess = { dbcFile ->
                if (dbcFile.messages.any { it.id == message.id }) {
                    return Result.failure(Exception("Message mit ID ${message.idHex} existiert bereits"))
                }
                val updated = dbcFile.copy(messages = dbcFile.messages + message)
                saveDbcFile(info, updated)
            },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun updateMessage(info: DbcFileInfo, message: DbcMessage): Result<Unit> {
        return getDbcFile(info).fold(
            onSuccess = { dbcFile ->
                val index = dbcFile.messages.indexOfFirst { it.id == message.id }
                if (index == -1) {
                    return Result.failure(Exception("Message nicht gefunden"))
                }
                val newMessages = dbcFile.messages.toMutableList()
                newMessages[index] = message
                val updated = dbcFile.copy(messages = newMessages)
                saveDbcFile(info, updated)
            },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun deleteMessage(info: DbcFileInfo, messageId: Long): Result<Unit> {
        return getDbcFile(info).fold(
            onSuccess = { dbcFile ->
                val newMessages = dbcFile.messages.filter { it.id != messageId }
                if (newMessages.size == dbcFile.messages.size) {
                    return Result.failure(Exception("Message nicht gefunden"))
                }
                val updated = dbcFile.copy(messages = newMessages)
                saveDbcFile(info, updated)
            },
            onFailure = { Result.failure(it) }
        )
    }

    // ---- SIGNAL OPERATIONS ----

    suspend fun addSignal(info: DbcFileInfo, messageId: Long, signal: DbcSignal): Result<Unit> {
        return getDbcFile(info).fold(
            onSuccess = { dbcFile ->
                val msgIndex = dbcFile.messages.indexOfFirst { it.id == messageId }
                if (msgIndex == -1) {
                    return Result.failure(Exception("Message nicht gefunden"))
                }
                val message = dbcFile.messages[msgIndex]
                if (message.signals.any { it.name == signal.name }) {
                    return Result.failure(Exception("Signal '${signal.name}' existiert bereits"))
                }
                val updatedMessage = message.copy(signals = message.signals + signal)
                val newMessages = dbcFile.messages.toMutableList()
                newMessages[msgIndex] = updatedMessage
                val updated = dbcFile.copy(messages = newMessages)
                saveDbcFile(info, updated)
            },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun updateSignal(info: DbcFileInfo, messageId: Long, oldSignalName: String, signal: DbcSignal): Result<Unit> {
        return getDbcFile(info).fold(
            onSuccess = { dbcFile ->
                val msgIndex = dbcFile.messages.indexOfFirst { it.id == messageId }
                if (msgIndex == -1) {
                    return Result.failure(Exception("Message nicht gefunden"))
                }
                val message = dbcFile.messages[msgIndex]
                val sigIndex = message.signals.indexOfFirst { it.name == oldSignalName }
                if (sigIndex == -1) {
                    return Result.failure(Exception("Signal nicht gefunden"))
                }
                // Check for name collision if renamed
                if (signal.name != oldSignalName && message.signals.any { it.name == signal.name }) {
                    return Result.failure(Exception("Signal '${signal.name}' existiert bereits"))
                }
                val newSignals = message.signals.toMutableList()
                newSignals[sigIndex] = signal
                val updatedMessage = message.copy(signals = newSignals)
                val newMessages = dbcFile.messages.toMutableList()
                newMessages[msgIndex] = updatedMessage
                val updated = dbcFile.copy(messages = newMessages)
                saveDbcFile(info, updated)
            },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun deleteSignal(info: DbcFileInfo, messageId: Long, signalName: String): Result<Unit> {
        return getDbcFile(info).fold(
            onSuccess = { dbcFile ->
                val msgIndex = dbcFile.messages.indexOfFirst { it.id == messageId }
                if (msgIndex == -1) {
                    return Result.failure(Exception("Message nicht gefunden"))
                }
                val message = dbcFile.messages[msgIndex]
                val newSignals = message.signals.filter { it.name != signalName }
                if (newSignals.size == message.signals.size) {
                    return Result.failure(Exception("Signal nicht gefunden"))
                }
                val updatedMessage = message.copy(signals = newSignals)
                val newMessages = dbcFile.messages.toMutableList()
                newMessages[msgIndex] = updatedMessage
                val updated = dbcFile.copy(messages = newMessages)
                saveDbcFile(info, updated)
            },
            onFailure = { Result.failure(it) }
        )
    }

    // ---- NODE OPERATIONS ----

    suspend fun addNode(info: DbcFileInfo, node: DbcNode): Result<Unit> {
        return getDbcFile(info).fold(
            onSuccess = { dbcFile ->
                if (dbcFile.nodes.any { it.name == node.name }) {
                    return Result.failure(Exception("Node '${node.name}' existiert bereits"))
                }
                val updated = dbcFile.copy(nodes = dbcFile.nodes + node)
                saveDbcFile(info, updated)
            },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun deleteNode(info: DbcFileInfo, nodeName: String): Result<Unit> {
        return getDbcFile(info).fold(
            onSuccess = { dbcFile ->
                val newNodes = dbcFile.nodes.filter { it.name != nodeName }
                if (newNodes.size == dbcFile.nodes.size) {
                    return Result.failure(Exception("Node nicht gefunden"))
                }
                val updated = dbcFile.copy(nodes = newNodes)
                saveDbcFile(info, updated)
            },
            onFailure = { Result.failure(it) }
        )
    }
}

data class DbcFileInfo(
    val name: String,
    val fileName: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val isActive: Boolean = false
) {
    val sizeFormatted: String
        get() = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / (1024 * 1024)} MB"
        }
}
