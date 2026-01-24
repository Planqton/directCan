package at.planqton.directcan.data.visualscript

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

private const val TAG = "VisualScriptRepository"

class VisualScriptRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val scriptDir: File
        get() = File(context.filesDir, "visualscripts").also { it.mkdirs() }

    private val _scripts = MutableStateFlow<List<VisualScriptFileInfo>>(emptyList())
    val scripts: StateFlow<List<VisualScriptFileInfo>> = _scripts.asStateFlow()

    private val _activeScript = MutableStateFlow<VisualScript?>(null)
    val activeScript: StateFlow<VisualScript?> = _activeScript.asStateFlow()

    init {
        Log.d(TAG, "Initializing VisualScriptRepository, dir: ${scriptDir.absolutePath}")
        refreshScriptList()
    }

    fun refreshScriptList() {
        val files = scriptDir.listFiles()
            ?.filter { it.extension == "vscript" }
            ?.mapNotNull { file ->
                try {
                    val script = json.decodeFromString<VisualScript>(file.readText())
                    VisualScriptFileInfo(
                        id = script.id,
                        name = script.name,
                        fileName = file.name,
                        path = file.absolutePath,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        description = script.description,
                        nodeCount = script.nodes.size
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading script: ${file.name}", e)
                    null
                }
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()

        Log.d(TAG, "Refreshed script list: ${files.size} files")
        _scripts.value = files
    }

    suspend fun createScript(name: String, description: String = ""): Result<VisualScriptFileInfo> =
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Creating new visual script: $name")

                val cleanName = name.replace(Regex("[^a-zA-Z0-9_\\-äöüÄÖÜß ]"), "_")

                val targetFile = File(scriptDir, "$cleanName${VisualScript.FILE_EXTENSION}")
                if (targetFile.exists()) {
                    return@withContext Result.failure(Exception("Script '$cleanName' existiert bereits"))
                }

                val script = VisualScript(
                    name = cleanName,
                    description = description
                )

                targetFile.writeText(json.encodeToString(script))

                refreshScriptList()

                val info = VisualScriptFileInfo(
                    id = script.id,
                    name = cleanName,
                    fileName = targetFile.name,
                    path = targetFile.absolutePath,
                    size = targetFile.length(),
                    lastModified = targetFile.lastModified(),
                    description = description,
                    nodeCount = 0
                )

                Log.i(TAG, "Script created: $name")
                Result.success(info)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating script", e)
                Result.failure(e)
            }
        }

    suspend fun loadScript(info: VisualScriptFileInfo): Result<VisualScript> =
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Loading script: ${info.name}")

                val file = File(info.path)
                if (!file.exists()) {
                    return@withContext Result.failure(Exception("Script file not found"))
                }

                val script = json.decodeFromString<VisualScript>(file.readText())

                Log.i(TAG, "Script loaded: ${info.name} with ${script.nodes.size} nodes")
                Result.success(script)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading script", e)
                Result.failure(e)
            }
        }

    suspend fun saveScript(script: VisualScript): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Saving script: ${script.name}")

            val updatedScript = script.copy(modifiedAt = System.currentTimeMillis())
            val targetFile = File(scriptDir, "${script.name}${VisualScript.FILE_EXTENSION}")
            targetFile.writeText(json.encodeToString(updatedScript))

            // Update active script if same
            if (_activeScript.value?.id == script.id) {
                _activeScript.value = updatedScript
            }

            refreshScriptList()
            Log.i(TAG, "Script saved: ${script.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving script", e)
            Result.failure(e)
        }
    }

    suspend fun deleteScript(info: VisualScriptFileInfo): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Deleting script: ${info.name}")

            val file = File(info.path)
            file.delete()

            if (_activeScript.value?.id == info.id) {
                _activeScript.value = null
            }

            refreshScriptList()
            Log.i(TAG, "Script deleted: ${info.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting script", e)
            Result.failure(e)
        }
    }

    suspend fun duplicateScript(info: VisualScriptFileInfo, newName: String): Result<VisualScriptFileInfo> =
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Duplicating script: ${info.name} -> $newName")

                val cleanName = newName.replace(Regex("[^a-zA-Z0-9_\\-äöüÄÖÜß ]"), "_")
                val targetFile = File(scriptDir, "$cleanName${VisualScript.FILE_EXTENSION}")

                if (targetFile.exists()) {
                    return@withContext Result.failure(Exception("Script '$cleanName' existiert bereits"))
                }

                val sourceFile = File(info.path)
                val sourceScript = json.decodeFromString<VisualScript>(sourceFile.readText())

                val newScript = sourceScript.copy(
                    id = UUID.randomUUID().toString(),
                    name = cleanName,
                    createdAt = System.currentTimeMillis(),
                    modifiedAt = System.currentTimeMillis()
                )

                targetFile.writeText(json.encodeToString(newScript))

                refreshScriptList()

                val newInfo = VisualScriptFileInfo(
                    id = newScript.id,
                    name = cleanName,
                    fileName = targetFile.name,
                    path = targetFile.absolutePath,
                    size = targetFile.length(),
                    lastModified = targetFile.lastModified(),
                    description = newScript.description,
                    nodeCount = newScript.nodes.size
                )

                Log.i(TAG, "Script duplicated: ${info.name} -> $newName")
                Result.success(newInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Error duplicating script", e)
                Result.failure(e)
            }
        }

    suspend fun renameScript(info: VisualScriptFileInfo, newName: String): Result<VisualScriptFileInfo> =
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Renaming script: ${info.name} -> $newName")

                val cleanName = newName.replace(Regex("[^a-zA-Z0-9_\\-äöüÄÖÜß ]"), "_")
                val targetFile = File(scriptDir, "$cleanName${VisualScript.FILE_EXTENSION}")

                if (targetFile.exists() && targetFile.absolutePath != info.path) {
                    return@withContext Result.failure(Exception("Script '$cleanName' existiert bereits"))
                }

                // Load, rename and save
                val sourceFile = File(info.path)
                val script = json.decodeFromString<VisualScript>(sourceFile.readText())
                val renamedScript = script.copy(
                    name = cleanName,
                    modifiedAt = System.currentTimeMillis()
                )

                // Delete old file
                sourceFile.delete()

                // Write new file
                targetFile.writeText(json.encodeToString(renamedScript))

                refreshScriptList()

                val newInfo = info.copy(
                    name = cleanName,
                    fileName = targetFile.name,
                    path = targetFile.absolutePath,
                    lastModified = System.currentTimeMillis()
                )

                Log.i(TAG, "Script renamed: ${info.name} -> $newName")
                Result.success(newInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Error renaming script", e)
                Result.failure(e)
            }
        }

    // =============== NODE OPERATIONS ===============

    suspend fun addNode(scriptId: String, node: VisualNode): Result<VisualScript> =
        withContext(Dispatchers.IO) {
            try {
                val scriptInfo = _scripts.value.find { it.id == scriptId }
                    ?: return@withContext Result.failure(Exception("Script not found"))

                val script = loadScript(scriptInfo).getOrThrow()
                val updatedScript = script.copy(
                    nodes = script.nodes + node,
                    modifiedAt = System.currentTimeMillis()
                )

                saveScript(updatedScript)
                Result.success(updatedScript)
            } catch (e: Exception) {
                Log.e(TAG, "Error adding node", e)
                Result.failure(e)
            }
        }

    suspend fun updateNode(scriptId: String, nodeId: String, config: NodeConfig): Result<VisualScript> =
        withContext(Dispatchers.IO) {
            try {
                val scriptInfo = _scripts.value.find { it.id == scriptId }
                    ?: return@withContext Result.failure(Exception("Script not found"))

                val script = loadScript(scriptInfo).getOrThrow()
                val updatedNodes = script.nodes.map { node ->
                    if (node.id == nodeId) node.copy(config = config) else node
                }
                val updatedScript = script.copy(
                    nodes = updatedNodes,
                    modifiedAt = System.currentTimeMillis()
                )

                saveScript(updatedScript)
                Result.success(updatedScript)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating node", e)
                Result.failure(e)
            }
        }

    suspend fun moveNode(scriptId: String, nodeId: String, newPosition: SerializableOffset): Result<VisualScript> =
        withContext(Dispatchers.IO) {
            try {
                val scriptInfo = _scripts.value.find { it.id == scriptId }
                    ?: return@withContext Result.failure(Exception("Script not found"))

                val script = loadScript(scriptInfo).getOrThrow()
                val updatedNodes = script.nodes.map { node ->
                    if (node.id == nodeId) node.copy(position = newPosition) else node
                }
                val updatedScript = script.copy(
                    nodes = updatedNodes,
                    modifiedAt = System.currentTimeMillis()
                )

                saveScript(updatedScript)
                Result.success(updatedScript)
            } catch (e: Exception) {
                Log.e(TAG, "Error moving node", e)
                Result.failure(e)
            }
        }

    suspend fun deleteNode(scriptId: String, nodeId: String): Result<VisualScript> =
        withContext(Dispatchers.IO) {
            try {
                val scriptInfo = _scripts.value.find { it.id == scriptId }
                    ?: return@withContext Result.failure(Exception("Script not found"))

                val script = loadScript(scriptInfo).getOrThrow()

                // Remove node and all connections to/from it
                val updatedNodes = script.nodes.filter { it.id != nodeId }
                val updatedConnections = script.connections.filter {
                    it.fromNodeId != nodeId && it.toNodeId != nodeId
                }

                val updatedScript = script.copy(
                    nodes = updatedNodes,
                    connections = updatedConnections,
                    modifiedAt = System.currentTimeMillis()
                )

                saveScript(updatedScript)
                Result.success(updatedScript)
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting node", e)
                Result.failure(e)
            }
        }

    // =============== CONNECTION OPERATIONS ===============

    suspend fun addConnection(scriptId: String, connection: NodeConnection): Result<VisualScript> =
        withContext(Dispatchers.IO) {
            try {
                val scriptInfo = _scripts.value.find { it.id == scriptId }
                    ?: return@withContext Result.failure(Exception("Script not found"))

                val script = loadScript(scriptInfo).getOrThrow()

                // Check if connection already exists
                val existingConnection = script.connections.find {
                    it.fromNodeId == connection.fromNodeId &&
                    it.fromPort == connection.fromPort &&
                    it.toNodeId == connection.toNodeId
                }

                if (existingConnection != null) {
                    return@withContext Result.success(script) // Already exists
                }

                // Validate nodes exist
                val fromNode = script.nodes.find { it.id == connection.fromNodeId }
                val toNode = script.nodes.find { it.id == connection.toNodeId }

                if (fromNode == null || toNode == null) {
                    return@withContext Result.failure(Exception("Invalid connection: node not found"))
                }

                if (!NodeHelpers.isValidConnection(fromNode, toNode)) {
                    return@withContext Result.failure(Exception("Invalid connection"))
                }

                val updatedScript = script.copy(
                    connections = script.connections + connection,
                    modifiedAt = System.currentTimeMillis()
                )

                saveScript(updatedScript)
                Result.success(updatedScript)
            } catch (e: Exception) {
                Log.e(TAG, "Error adding connection", e)
                Result.failure(e)
            }
        }

    suspend fun deleteConnection(scriptId: String, connectionId: String): Result<VisualScript> =
        withContext(Dispatchers.IO) {
            try {
                val scriptInfo = _scripts.value.find { it.id == scriptId }
                    ?: return@withContext Result.failure(Exception("Script not found"))

                val script = loadScript(scriptInfo).getOrThrow()
                val updatedConnections = script.connections.filter { it.id != connectionId }

                val updatedScript = script.copy(
                    connections = updatedConnections,
                    modifiedAt = System.currentTimeMillis()
                )

                saveScript(updatedScript)
                Result.success(updatedScript)
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting connection", e)
                Result.failure(e)
            }
        }

    // =============== IMPORT/EXPORT ===============

    suspend fun exportToUri(info: VisualScriptFileInfo, targetUri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Exporting script: ${info.name} to $targetUri")

                val sourceFile = File(info.path)
                val content = sourceFile.readText()

                context.contentResolver.openOutputStream(targetUri)?.use { output ->
                    output.write(content.toByteArray())
                } ?: return@withContext Result.failure(Exception("Cannot write to target"))

                Log.i(TAG, "Script exported: ${info.name}")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting script", e)
                Result.failure(e)
            }
        }

    suspend fun importFromUri(uri: Uri, name: String? = null): Result<VisualScriptFileInfo> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Importing script from: $uri")

                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(Exception("Cannot open file"))

                val content = inputStream.bufferedReader().readText()
                inputStream.close()

                // Parse the script
                val importedScript = try {
                    json.decodeFromString<VisualScript>(content)
                } catch (e: Exception) {
                    return@withContext Result.failure(Exception("Invalid script format"))
                }

                // Determine name
                val rawName = name ?: importedScript.name.ifBlank {
                    uri.lastPathSegment
                        ?.substringAfterLast("/")
                        ?.substringBeforeLast(".")
                        ?: "imported"
                }
                val cleanName = rawName.replace(Regex("[^a-zA-Z0-9_\\-äöüÄÖÜß ]"), "_")

                // Create unique name if exists
                var finalName = cleanName
                var counter = 1
                while (File(scriptDir, "$finalName${VisualScript.FILE_EXTENSION}").exists()) {
                    finalName = "${cleanName}_$counter"
                    counter++
                }

                // Create new script with new ID
                val newScript = importedScript.copy(
                    id = UUID.randomUUID().toString(),
                    name = finalName,
                    createdAt = System.currentTimeMillis(),
                    modifiedAt = System.currentTimeMillis()
                )

                val targetFile = File(scriptDir, "$finalName${VisualScript.FILE_EXTENSION}")
                targetFile.writeText(json.encodeToString(newScript))

                refreshScriptList()

                val info = VisualScriptFileInfo(
                    id = newScript.id,
                    name = finalName,
                    fileName = targetFile.name,
                    path = targetFile.absolutePath,
                    size = targetFile.length(),
                    lastModified = targetFile.lastModified(),
                    description = newScript.description,
                    nodeCount = newScript.nodes.size
                )

                Log.i(TAG, "Script imported: $finalName")
                Result.success(info)
            } catch (e: Exception) {
                Log.e(TAG, "Error importing script", e)
                Result.failure(e)
            }
        }

    // =============== ACTIVE SCRIPT ===============

    fun setActiveScript(script: VisualScript?) {
        _activeScript.value = script
    }

    fun clearActiveScript() {
        _activeScript.value = null
    }

    // =============== VALIDATION ===============

    fun validateScriptName(name: String): String? {
        if (name.isBlank()) {
            return "Name darf nicht leer sein"
        }
        if (name.length > 64) {
            return "Name zu lang (max. 64 Zeichen)"
        }
        val cleanName = name.replace(Regex("[^a-zA-Z0-9_\\-äöüÄÖÜß ]"), "_")
        if (File(scriptDir, "$cleanName${VisualScript.FILE_EXTENSION}").exists()) {
            return "Script '$cleanName' existiert bereits"
        }
        return null
    }
}
