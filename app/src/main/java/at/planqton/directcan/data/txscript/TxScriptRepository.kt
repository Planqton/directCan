package at.planqton.directcan.data.txscript

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

private const val TAG = "TxScriptRepository"

class TxScriptRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val scriptDir: File
        get() = File(context.filesDir, "txscripts").also { it.mkdirs() }

    private val _scripts = MutableStateFlow<List<TxScriptFileInfo>>(emptyList())
    val scripts: StateFlow<List<TxScriptFileInfo>> = _scripts.asStateFlow()

    private val _activeScript = MutableStateFlow<TxScript?>(null)
    val activeScript: StateFlow<TxScript?> = _activeScript.asStateFlow()

    init {
        Log.d(TAG, "Initializing TxScriptRepository, dir: ${scriptDir.absolutePath}")
        refreshScriptList()
    }

    fun refreshScriptList() {
        val files = scriptDir.listFiles()
            ?.filter { it.extension == "txs" }
            ?.map { file ->
                val metaFile = File(file.parentFile, "${file.nameWithoutExtension}.meta.json")
                val meta = if (metaFile.exists()) {
                    try {
                        json.decodeFromString<TxScriptMeta>(metaFile.readText())
                    } catch (e: Exception) {
                        TxScriptMeta()
                    }
                } else {
                    TxScriptMeta()
                }

                TxScriptFileInfo(
                    id = meta.id.ifEmpty { UUID.randomUUID().toString() },
                    name = file.nameWithoutExtension,
                    fileName = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    description = meta.description,
                    isActive = _activeScript.value?.id == meta.id
                )
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()

        Log.d(TAG, "Refreshed script list: ${files.size} files")
        _scripts.value = files
    }

    suspend fun createScript(name: String, description: String = ""): Result<TxScriptFileInfo> =
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Creating new script: $name")

                val cleanName = name.replace(Regex("[^a-zA-Z0-9_\\-äöüÄÖÜß ]"), "_")
                val script = TxScript.createEmpty(cleanName, description)

                val targetFile = File(scriptDir, "$cleanName${TxScript.FILE_EXTENSION}")
                if (targetFile.exists()) {
                    return@withContext Result.failure(Exception("Script '$cleanName' existiert bereits"))
                }

                targetFile.writeText(script.content)

                // Save metadata
                val meta = TxScriptMeta(
                    id = script.id,
                    description = description,
                    createdAt = script.createdAt,
                    modifiedAt = script.modifiedAt
                )
                val metaFile = File(scriptDir, "$cleanName.meta.json")
                metaFile.writeText(json.encodeToString(meta))

                refreshScriptList()

                val info = TxScriptFileInfo(
                    id = script.id,
                    name = cleanName,
                    fileName = targetFile.name,
                    path = targetFile.absolutePath,
                    size = targetFile.length(),
                    lastModified = targetFile.lastModified(),
                    description = description
                )

                Log.i(TAG, "Script created: $name")
                Result.success(info)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating script", e)
                Result.failure(e)
            }
        }

    suspend fun loadScript(info: TxScriptFileInfo): Result<TxScript> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Loading script: ${info.name}")

            val file = File(info.path)
            if (!file.exists()) {
                return@withContext Result.failure(Exception("Script file not found"))
            }

            val content = file.readText()
            val metaFile = File(file.parentFile, "${file.nameWithoutExtension}.meta.json")
            val meta = if (metaFile.exists()) {
                try {
                    json.decodeFromString<TxScriptMeta>(metaFile.readText())
                } catch (e: Exception) {
                    TxScriptMeta(id = info.id)
                }
            } else {
                TxScriptMeta(id = info.id)
            }

            val script = TxScript(
                id = meta.id.ifEmpty { info.id },
                name = info.name,
                description = meta.description,
                content = content,
                createdAt = meta.createdAt,
                modifiedAt = meta.modifiedAt
            )

            Log.i(TAG, "Script loaded: ${info.name}")
            Result.success(script)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading script", e)
            Result.failure(e)
        }
    }

    suspend fun saveScript(script: TxScript): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Saving script: ${script.name}")

            val targetFile = File(scriptDir, "${script.name}${TxScript.FILE_EXTENSION}")
            targetFile.writeText(script.content)

            val meta = TxScriptMeta(
                id = script.id,
                description = script.description,
                createdAt = script.createdAt,
                modifiedAt = System.currentTimeMillis()
            )
            val metaFile = File(scriptDir, "${script.name}.meta.json")
            metaFile.writeText(json.encodeToString(meta))

            // Update active script if same
            if (_activeScript.value?.id == script.id) {
                _activeScript.value = script.copy(modifiedAt = meta.modifiedAt)
            }

            refreshScriptList()
            Log.i(TAG, "Script saved: ${script.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving script", e)
            Result.failure(e)
        }
    }

    suspend fun deleteScript(info: TxScriptFileInfo): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Deleting script: ${info.name}")

            val file = File(info.path)
            val metaFile = File(file.parentFile, "${file.nameWithoutExtension}.meta.json")

            file.delete()
            metaFile.delete()

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

    suspend fun duplicateScript(info: TxScriptFileInfo, newName: String): Result<TxScriptFileInfo> =
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Duplicating script: ${info.name} -> $newName")

                val cleanName = newName.replace(Regex("[^a-zA-Z0-9_\\-äöüÄÖÜß ]"), "_")
                val targetFile = File(scriptDir, "$cleanName${TxScript.FILE_EXTENSION}")

                if (targetFile.exists()) {
                    return@withContext Result.failure(Exception("Script '$cleanName' existiert bereits"))
                }

                val sourceFile = File(info.path)
                sourceFile.copyTo(targetFile)

                val newId = UUID.randomUUID().toString()
                val meta = TxScriptMeta(
                    id = newId,
                    description = info.description,
                    createdAt = System.currentTimeMillis(),
                    modifiedAt = System.currentTimeMillis()
                )
                val metaFile = File(scriptDir, "$cleanName.meta.json")
                metaFile.writeText(json.encodeToString(meta))

                refreshScriptList()

                val newInfo = TxScriptFileInfo(
                    id = newId,
                    name = cleanName,
                    fileName = targetFile.name,
                    path = targetFile.absolutePath,
                    size = targetFile.length(),
                    lastModified = targetFile.lastModified(),
                    description = info.description
                )

                Log.i(TAG, "Script duplicated: ${info.name} -> $newName")
                Result.success(newInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Error duplicating script", e)
                Result.failure(e)
            }
        }

    suspend fun renameScript(info: TxScriptFileInfo, newName: String): Result<TxScriptFileInfo> =
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Renaming script: ${info.name} -> $newName")

                val cleanName = newName.replace(Regex("[^a-zA-Z0-9_\\-äöüÄÖÜß ]"), "_")
                val targetFile = File(scriptDir, "$cleanName${TxScript.FILE_EXTENSION}")

                if (targetFile.exists() && targetFile.absolutePath != info.path) {
                    return@withContext Result.failure(Exception("Script '$cleanName' existiert bereits"))
                }

                val sourceFile = File(info.path)
                val sourceMetaFile = File(sourceFile.parentFile, "${sourceFile.nameWithoutExtension}.meta.json")

                // Rename files
                sourceFile.renameTo(targetFile)
                val targetMetaFile = File(scriptDir, "$cleanName.meta.json")
                if (sourceMetaFile.exists()) {
                    sourceMetaFile.renameTo(targetMetaFile)
                }

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

    // =============== IMPORT/EXPORT ===============

    suspend fun importFromUri(uri: Uri, name: String? = null): Result<TxScriptFileInfo> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Importing script from: $uri")

                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(Exception("Cannot open file"))

                val content = inputStream.bufferedReader().readText()
                inputStream.close()

                // Determine name from URI or parameter
                val rawFileName = name ?: uri.lastPathSegment
                    ?.substringAfterLast("/")
                    ?.substringBeforeLast(".")
                    ?: "imported"
                val cleanName = rawFileName.replace(Regex("[^a-zA-Z0-9_\\-äöüÄÖÜß ]"), "_")

                // Create unique name if exists
                var finalName = cleanName
                var counter = 1
                while (File(scriptDir, "$finalName${TxScript.FILE_EXTENSION}").exists()) {
                    finalName = "${cleanName}_$counter"
                    counter++
                }

                val newId = UUID.randomUUID().toString()
                val targetFile = File(scriptDir, "$finalName${TxScript.FILE_EXTENSION}")
                targetFile.writeText(content)

                val meta = TxScriptMeta(
                    id = newId,
                    description = "",
                    createdAt = System.currentTimeMillis(),
                    modifiedAt = System.currentTimeMillis()
                )
                val metaFile = File(scriptDir, "$finalName.meta.json")
                metaFile.writeText(json.encodeToString(meta))

                refreshScriptList()

                val info = TxScriptFileInfo(
                    id = newId,
                    name = finalName,
                    fileName = targetFile.name,
                    path = targetFile.absolutePath,
                    size = targetFile.length(),
                    lastModified = targetFile.lastModified()
                )

                Log.i(TAG, "Script imported: $finalName")
                Result.success(info)
            } catch (e: Exception) {
                Log.e(TAG, "Error importing script", e)
                Result.failure(e)
            }
        }

    suspend fun exportToUri(info: TxScriptFileInfo, targetUri: Uri): Result<Unit> =
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

    suspend fun getScriptContent(info: TxScriptFileInfo): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val file = File(info.path)
                Result.success(file.readText())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // =============== ACTIVE SCRIPT ===============

    fun setActiveScript(script: TxScript?) {
        _activeScript.value = script
        refreshScriptList()
    }

    fun clearActiveScript() {
        _activeScript.value = null
        refreshScriptList()
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
        if (File(scriptDir, "$cleanName${TxScript.FILE_EXTENSION}").exists()) {
            return "Script '$cleanName' existiert bereits"
        }
        return null
    }
}

/**
 * Metadata for a TX Script file (stored separately from content).
 */
@kotlinx.serialization.Serializable
private data class TxScriptMeta(
    val id: String = "",
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis()
)
