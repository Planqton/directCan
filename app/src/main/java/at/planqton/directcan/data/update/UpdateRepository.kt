package at.planqton.directcan.data.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "UpdateRepository"
private const val GITHUB_USER = "Planqton"
private const val GITHUB_REPO = "directCan"

@Serializable
data class GitHubRelease(
    val tag_name: String,
    val name: String,
    val body: String? = null,
    val published_at: String,
    val assets: List<GitHubAsset>
)

@Serializable
data class GitHubAsset(
    val name: String,
    val browser_download_url: String,
    val size: Long
)

data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseNotes: String?,
    val downloadUrl: String,
    val fileSize: Long,
    val isUpdateAvailable: Boolean
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateState()
    object NoUpdate : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    data class ReadyToInstall(val apkUri: Uri) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class UpdateRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private var currentDownloadId: Long = -1

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
            if (id == currentDownloadId) {
                handleDownloadComplete()
            }
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    fun getCurrentVersion(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting version", e)
            "1.0.0"
        }
    }

    suspend fun checkForUpdates() {
        _updateState.value = UpdateState.Checking

        try {
            val release = fetchLatestRelease()
            if (release == null) {
                // No releases found - treat as "up to date"
                _updateState.value = UpdateState.NoUpdate
                return
            }

            val currentVersion = getCurrentVersion()
            val latestVersion = release.tag_name.removePrefix("v")

            // Find APK asset
            val apkAsset = release.assets.find { it.name.endsWith(".apk") }
            if (apkAsset == null) {
                _updateState.value = UpdateState.Error("Keine APK im Release gefunden")
                return
            }

            val isNewer = isVersionNewer(latestVersion, currentVersion)

            val updateInfo = UpdateInfo(
                currentVersion = currentVersion,
                latestVersion = latestVersion,
                releaseNotes = release.body,
                downloadUrl = apkAsset.browser_download_url,
                fileSize = apkAsset.size,
                isUpdateAvailable = isNewer
            )

            _updateState.value = if (isNewer) {
                UpdateState.UpdateAvailable(updateInfo)
            } else {
                UpdateState.NoUpdate
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
            _updateState.value = UpdateState.Error("Fehler: ${e.message}")
        }
    }

    private suspend fun fetchLatestRelease(): GitHubRelease? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/$GITHUB_USER/$GITHUB_REPO/releases/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            when (connection.responseCode) {
                200 -> {
                    val response = connection.inputStream.bufferedReader().readText()
                    json.decodeFromString<GitHubRelease>(response)
                }
                404 -> {
                    Log.i(TAG, "No releases found (404)")
                    null // Will be handled as "no update available"
                }
                else -> {
                    Log.e(TAG, "GitHub API error: ${connection.responseCode}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching release", e)
            null
        }
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        try {
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }

            for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing versions", e)
            return false
        }
    }

    fun downloadUpdate(downloadUrl: String) {
        try {
            _updateState.value = UpdateState.Downloading(0)

            // Delete old APK files
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            downloadDir?.listFiles()?.filter { it.name.startsWith("DirectCAN") && it.name.endsWith(".apk") }
                ?.forEach { it.delete() }

            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle("DirectCAN Update")
                .setDescription("Update wird heruntergeladen...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "DirectCAN-update.apk")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            currentDownloadId = downloadManager.enqueue(request)

            Log.i(TAG, "Download started with ID: $currentDownloadId")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting download", e)
            _updateState.value = UpdateState.Error("Download fehlgeschlagen: ${e.message}")
        }
    }

    private fun handleDownloadComplete() {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(currentDownloadId)
            val cursor = downloadManager.query(query)

            if (cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = cursor.getInt(statusIndex)

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val apkFile = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                        "DirectCAN-update.apk"
                    )

                    if (apkFile.exists()) {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            apkFile
                        )
                        _updateState.value = UpdateState.ReadyToInstall(uri)
                        Log.i(TAG, "Download complete, ready to install")
                    } else {
                        _updateState.value = UpdateState.Error("APK-Datei nicht gefunden")
                    }
                } else {
                    _updateState.value = UpdateState.Error("Download fehlgeschlagen")
                }
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling download complete", e)
            _updateState.value = UpdateState.Error("Fehler: ${e.message}")
        }
    }

    fun installUpdate(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error installing update", e)
            _updateState.value = UpdateState.Error("Installation fehlgeschlagen: ${e.message}")
        }
    }

    fun resetState() {
        _updateState.value = UpdateState.Idle
    }

    fun destroy() {
        try {
            context.unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}
