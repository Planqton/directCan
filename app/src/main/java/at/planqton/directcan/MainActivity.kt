package at.planqton.directcan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import at.planqton.directcan.data.can.CanDataRepository
import at.planqton.directcan.data.settings.SettingsRepository
import at.planqton.directcan.ui.navigation.Screen
import at.planqton.directcan.data.dbc.DbcFileInfo
import at.planqton.directcan.ui.screens.dbc.DbcEditorScreen
import at.planqton.directcan.ui.screens.dbc.DbcManagerScreen
import java.net.URLDecoder
import at.planqton.directcan.ui.screens.home.HomeScreen
import at.planqton.directcan.ui.screens.logs.LogManagerScreen
import at.planqton.directcan.ui.screens.monitor.MonitorScreen
import at.planqton.directcan.ui.screens.settings.SettingsScreen
import at.planqton.directcan.ui.screens.signals.SignalGraphScreen
import at.planqton.directcan.ui.screens.signals.SignalViewerScreen
import at.planqton.directcan.ui.screens.sniffer.SnifferScreen
import at.planqton.directcan.ui.screens.gemini.AiSettingsScreen
import at.planqton.directcan.ui.screens.gemini.AiChatScreen
import at.planqton.directcan.ui.screens.gemini.FloatingAiChatWindow
import at.planqton.directcan.ui.screens.txscript.TxScriptManagerScreen
import at.planqton.directcan.ui.screens.txscript.ScriptEditorScreen
import at.planqton.directcan.ui.screens.device.DeviceManagerScreen
import at.planqton.directcan.ui.screens.obd2.DtcScreen
import at.planqton.directcan.ui.components.FloatingSerialMonitor
import at.planqton.directcan.ui.theme.DirectCanTheme
import at.planqton.directcan.util.LocaleHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // Read language directly from SharedPreferences (no coroutines, no blocking)
        // DataStore also writes to this for sync
        val prefs = newBase.getSharedPreferences("directcan_language", Context.MODE_PRIVATE)
        val language = prefs.getString("language", "system") ?: "system"
        val context = LocaleHelper.setLocale(newBase, language)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setContent {
            DirectCanTheme {
                MainApp()
            }
        }
    }
}

@Composable
fun MainApp() {
    val context = LocalContext.current
    var permissionsChecked by remember { mutableStateOf(false) }
    var permissionsDenied by remember { mutableStateOf<List<String>>(emptyList()) }

    // Build list of required permissions based on Android version
    val requiredPermissions = remember {
        buildList {
            // Notification permission (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Storage permissions (Android < 13)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
    }

    // Permission launcher for multiple permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsDenied = results.filter { !it.value }.keys.toList()
        permissionsChecked = true
    }

    // Check permissions on start
    LaunchedEffect(Unit) {
        if (requiredPermissions.isEmpty()) {
            permissionsChecked = true
        } else {
            val notGranted = requiredPermissions.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (notGranted.isEmpty()) {
                permissionsChecked = true
            } else {
                permissionLauncher.launch(notGranted.toTypedArray())
            }
        }
    }

    // Show permission dialog if not all granted
    if (permissionsChecked && permissionsDenied.isNotEmpty()) {
        PermissionDeniedDialog(
            deniedPermissions = permissionsDenied,
            onContinue = { permissionsDenied = emptyList() }
        )
    }

    // Main app content (always shown, even if some permissions denied)
    if (permissionsChecked) {
        MainAppContent()
    } else {
        // Loading while checking permissions
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
fun PermissionDeniedDialog(
    deniedPermissions: List<String>,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onContinue,
        icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Berechtigungen fehlen") },
        text = {
            Column {
                Text("Einige Funktionen sind möglicherweise eingeschränkt:")
                Spacer(Modifier.height(8.dp))
                deniedPermissions.forEach { permission ->
                    val name = when {
                        permission.contains("NOTIFICATION") -> "Benachrichtigungen (für Hintergrund-Logging)"
                        permission.contains("STORAGE") || permission.contains("EXTERNAL") -> "Speicher (für Log-Dateien)"
                        else -> permission.substringAfterLast(".")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(name, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Du kannst die Berechtigungen in den System-Einstellungen aktivieren.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text("Verstanden")
            }
        }
    )
}

@Composable
fun MainAppContent() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val activity = context as? Activity
    val app = DirectCanApplication.instance
    val canDataRepository = app.canDataRepository
    val deviceManager = app.deviceManager
    val aiChatRepository = app.aiChatRepository

    // Active AI Chat state
    val activeChatId by aiChatRepository.activeChatId.collectAsState()
    val hasActiveChat = activeChatId != null

    // Logging state for exit confirmation
    val isLogging by canDataRepository.isLogging.collectAsState()
    var showExitDialog by remember { mutableStateOf(false) }

    // Snapshot state
    var pendingSnapshot by remember { mutableStateOf<CanDataRepository.SnapshotData?>(null) }
    var showSnapshotDialog by remember { mutableStateOf(false) }

    // Serial Monitor state
    val serialMonitorVisible by app.serialMonitorVisible.collectAsState()
    val serialLogs by app.serialLogs.collectAsState()

    // Floating AI Chat overlays state
    var openAiChatOverlays by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Handle back button when logging is active
    BackHandler(enabled = isLogging) {
        showExitDialog = true
    }

    // Exit confirmation dialog when capturing is active
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Capturing aktiv") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Das Capturing läuft noch. Was möchtest du tun?")

                    // Option buttons
                    Button(
                        onClick = {
                            // Continue capturing in background, just minimize app
                            showExitDialog = false
                            activity?.moveTaskToBack(true)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Im Hintergrund fortsetzen")
                    }

                    OutlinedButton(
                        onClick = {
                            // Suspend capturing and exit
                            canDataRepository.setLoggingActive(false)
                            showExitDialog = false
                            activity?.moveTaskToBack(true)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Capturing pausieren & verlassen")
                    }

                    TextButton(
                        onClick = { showExitDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Abbrechen")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }

    // Chat sessions for navbar
    val chatSessions by aiChatRepository.chatSessions.collectAsState()

    Scaffold(
        bottomBar = {
            BottomNavBar(
                navController = navController,
                hasActiveChat = openAiChatOverlays.isNotEmpty(),
                onSnapshotClick = {
                    // Capture snapshot IMMEDIATELY
                    pendingSnapshot = canDataRepository.captureSnapshot()
                    showSnapshotDialog = true
                },
                onAiChatClick = { },
                openAiChatOverlays = openAiChatOverlays,
                chatSessions = chatSessions,
                onCloseOverlay = { chatId ->
                    openAiChatOverlays = openAiChatOverlays - chatId
                },
                onOpenOverlay = { chatId ->
                    openAiChatOverlays = openAiChatOverlays + chatId
                }
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToDeviceManager = {
                        navController.navigate(Screen.DeviceManager.route)
                    },
                    onNavigateToDtc = {
                        navController.navigate(Screen.Dtc.route)
                    },
                    onCloseApp = {
                        (context as? Activity)?.finishAffinity()
                    },
                    onOpenAiChat = { chatId ->
                        openAiChatOverlays = openAiChatOverlays + chatId
                    }
                )
            }
            composable(Screen.Monitor.route) {
                MonitorScreen(
                    onNavigateToChat = { chatId ->
                        // Open as floating overlay instead of navigating
                        openAiChatOverlays = openAiChatOverlays + chatId
                    }
                )
            }
            composable(Screen.Sniffer.route) { SnifferScreen() }
            composable(Screen.Signals.route) { SignalViewerScreen() }
            composable(Screen.SignalGraph.route) { SignalGraphScreen() }
            composable(Screen.DbcManager.route) {
                DbcManagerScreen(
                    onNavigateToEditor = { dbcInfo ->
                        navController.navigate(Screen.DbcEditor.createRoute(dbcInfo.path))
                    }
                )
            }
            composable(Screen.DbcEditor.route) { backStackEntry ->
                val encodedPath = backStackEntry.arguments?.getString("dbcPath") ?: ""
                val decodedPath = URLDecoder.decode(encodedPath, "UTF-8")

                // Find DbcFileInfo from path
                val dbcRepository = DirectCanApplication.instance.dbcRepository
                val dbcFiles = dbcRepository.dbcFiles.collectAsState()
                val dbcInfo = dbcFiles.value.find { it.path == decodedPath }

                if (dbcInfo != null) {
                    DbcEditorScreen(
                        dbcInfo = dbcInfo,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToLogManager = {
                        navController.navigate(Screen.LogManager.route)
                    },
                    onNavigateToAiSettings = {
                        navController.navigate(Screen.AiSettings.route)
                    },
                    onNavigateToTxScriptManager = {
                        navController.navigate(Screen.TxScriptManager.route)
                    }
                )
            }
            composable(Screen.LogManager.route) {
                LogManagerScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToChat = { chatId ->
                        navController.navigate(Screen.AiChat.createRoute(chatId))
                    },
                    onOpenAiChatOverlay = { chatId ->
                        openAiChatOverlays = openAiChatOverlays + chatId
                    }
                )
            }
            composable(Screen.AiSettings.route) {
                AiSettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToChat = { chatId ->
                        navController.navigate(Screen.AiChat.createRoute(chatId))
                    },
                    onOpenAiChatOverlay = { chatId ->
                        openAiChatOverlays = openAiChatOverlays + chatId
                    }
                )
            }
            composable(Screen.AiChat.route) { backStackEntry ->
                val encodedChatId = backStackEntry.arguments?.getString("chatId") ?: ""
                val chatId = URLDecoder.decode(encodedChatId, "UTF-8")
                AiChatScreen(
                    chatId = chatId,
                    onNavigateBack = { navController.popBackStack() },
                    onSwitchChat = { newChatId ->
                        navController.navigate(Screen.AiChat.createRoute(newChatId)) {
                            popUpTo(Screen.AiChat.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.TxScriptManager.route) {
                TxScriptManagerScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToEditor = { scriptPath ->
                        navController.navigate(Screen.TxScriptEditor.createRoute(scriptPath))
                    }
                )
            }
            composable(Screen.TxScriptEditor.route) { backStackEntry ->
                val encodedPath = backStackEntry.arguments?.getString("scriptPath") ?: ""
                val scriptPath = URLDecoder.decode(encodedPath, "UTF-8")
                ScriptEditorScreen(
                    scriptPath = scriptPath,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.DeviceManager.route) {
                DeviceManagerScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Dtc.route) {
                DtcScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }

    // Snapshot Dialog
    if (showSnapshotDialog && pendingSnapshot != null) {
        SnapshotDialog(
            snapshot = pendingSnapshot!!,
            onDismiss = {
                showSnapshotDialog = false
                pendingSnapshot = null
            },
            onSave = { logFile, description, ports ->
                // Filter snapshot frames by selected ports
                val filteredSnapshot = if (ports.size < 2) {
                    pendingSnapshot!!.copy(
                        frames = pendingSnapshot!!.frames.filterValues { it.port in ports },
                        ports = ports
                    )
                } else {
                    pendingSnapshot!!
                }
                canDataRepository.saveSnapshot(filteredSnapshot, logFile, description)
                showSnapshotDialog = false
                pendingSnapshot = null
            },
            onNavigateToLogManager = {
                showSnapshotDialog = false
                pendingSnapshot = null
                navController.navigate(Screen.LogManager.route)
            }
        )
    }

    // Floating Serial Monitor Overlay
    FloatingSerialMonitor(
        isVisible = serialMonitorVisible,
        logs = serialLogs,
        onSendCommand = { command -> app.sendSerialCommand(command) },
        onClearLogs = { app.clearSerialLogs() },
        onExport = { logText ->
            val sendIntent = android.content.Intent().apply {
                action = android.content.Intent.ACTION_SEND
                putExtra(android.content.Intent.EXTRA_TEXT, logText)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "DirectCAN Serial Log")
                type = "text/plain"
            }
            context.startActivity(android.content.Intent.createChooser(sendIntent, "Serial Log exportieren"))
        },
        onClose = { app.hideSerialMonitor() }
    )

    // Floating AI Chat Overlays
    openAiChatOverlays.forEachIndexed { index, chatId ->
        FloatingAiChatWindow(
            chatId = chatId,
            initialOffsetX = 50f + (index * 30f),
            initialOffsetY = 100f + (index * 30f),
            onClose = { openAiChatOverlays = openAiChatOverlays - chatId }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotDialog(
    snapshot: CanDataRepository.SnapshotData,
    onDismiss: () -> Unit,
    onSave: (CanDataRepository.LogFileInfo, String, Set<Int>) -> Unit,
    onNavigateToLogManager: () -> Unit = {}
) {
    val canDataRepository = DirectCanApplication.instance.canDataRepository
    val aiChatRepository = DirectCanApplication.instance.aiChatRepository
    val deviceManager = DirectCanApplication.instance.deviceManager
    val settingsRepository = DirectCanApplication.instance.settingsRepository
    val logFiles by canDataRepository.logFiles.collectAsState()
    val recentDescriptions by canDataRepository.recentDescriptions.collectAsState()
    val chatSessions by aiChatRepository.chatSessions.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Multi-port support
    val connectedDeviceCount by deviceManager.connectedDeviceCount.collectAsState()
    val showPortFilter = connectedDeviceCount > 1
    val port1Color by settingsRepository.port1Color.collectAsState(initial = SettingsRepository.DEFAULT_PORT_1_COLOR)
    val port2Color by settingsRepository.port2Color.collectAsState(initial = SettingsRepository.DEFAULT_PORT_2_COLOR)
    var snapshotPorts by remember { mutableStateOf(setOf(1, 2)) }

    // Helper function to get port color
    fun getPortColor(port: Int): Color {
        return when (port) {
            1 -> Color(port1Color.toInt())
            2 -> Color(port2Color.toInt())
            else -> Color.Gray
        }
    }

    // Calculate filtered frame count
    val filteredFrameCount = remember(snapshot.frames, snapshotPorts, showPortFilter) {
        if (showPortFilter) {
            snapshot.frames.values.count { it.port in snapshotPorts }
        } else {
            snapshot.frames.size
        }
    }

    var selectedLogFile by remember { mutableStateOf<CanDataRepository.LogFileInfo?>(null) }
    var description by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var showCreateNewLog by remember { mutableStateOf(false) }
    var newLogName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        canDataRepository.refreshLogFiles()
    }

    // Auto-select first log file if available
    LaunchedEffect(logFiles) {
        if (selectedLogFile == null && logFiles.isNotEmpty()) {
            selectedLogFile = logFiles.first()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Snapshot gespeichert!")
                Spacer(Modifier.width(8.dp))
                Text(
                    "($filteredFrameCount Frames)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column {
                // Port filter (only when multiple devices connected)
                if (showPortFilter) {
                    Text("Von welchen Ports?", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = 1 in snapshotPorts,
                            onClick = {
                                snapshotPorts = if (1 in snapshotPorts) {
                                    if (snapshotPorts.size > 1) snapshotPorts - 1 else snapshotPorts
                                } else {
                                    snapshotPorts + 1
                                }
                            },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(getPortColor(1), RoundedCornerShape(2.dp))
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Port 1")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = 2 in snapshotPorts,
                            onClick = {
                                snapshotPorts = if (2 in snapshotPorts) {
                                    if (snapshotPorts.size > 1) snapshotPorts - 2 else snapshotPorts
                                } else {
                                    snapshotPorts + 2
                                }
                            },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(getPortColor(2), RoundedCornerShape(2.dp))
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Port 2")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Log file selection
                Text("In welches Log speichern?", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))

                if (logFiles.isEmpty()) {
                    OutlinedButton(
                        onClick = { showCreateNewLog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Neues Log erstellen")
                    }
                } else {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedLogFile?.name ?: "Log auswählen...",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            logFiles.forEach { logFile ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(logFile.name)
                                            Text(
                                                "${logFile.snapshotCount} Snapshots",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedLogFile = logFile
                                        expanded = false
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("+ Neues Log erstellen") },
                                onClick = {
                                    expanded = false
                                    showCreateNewLog = true
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Description input
                Text("Beschreibung:", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))

                // Recent descriptions as suggestion chips
                if (recentDescriptions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        recentDescriptions.forEach { recentDesc ->
                            SuggestionChip(
                                onClick = { description = recentDesc },
                                label = {
                                    Text(
                                        recentDesc.take(20) + if (recentDesc.length > 20) "..." else "",
                                        maxLines = 1
                                    )
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = { Text("z.B. Wassertemperatur 10°C") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3
                )

                Spacer(Modifier.height(12.dp))

                // Button to Log Manager
                TextButton(
                    onClick = onNavigateToLogManager,
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Log Manager öffnen")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedLogFile?.let { logFile ->
                        onSave(logFile, description.ifBlank { "Unbenannter Snapshot" }, snapshotPorts)

                        // Update any open chat for this log file
                        val relatedChat = chatSessions.find { it.snapshotName == logFile.name }
                        if (relatedChat != null) {
                            scope.launch {
                                // Read the updated log file content (always store original)
                                // Delta compression is applied when sending to Gemini
                                try {
                                    val content = context.contentResolver
                                        .openInputStream(logFile.uri)?.bufferedReader()?.use { it.readText() }
                                        ?: "Keine Daten"
                                    aiChatRepository.updateSnapshotData(relatedChat.id, content)
                                } catch (e: Exception) {
                                    // Ignore errors
                                }
                            }
                        }

                        Toast.makeText(context, "Snapshot gespeichert!", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = selectedLogFile != null
            ) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Verwerfen")
            }
        }
    )

    // Create new log dialog
    if (showCreateNewLog) {
        AlertDialog(
            onDismissRequest = { showCreateNewLog = false },
            title = { Text("Neues Log erstellen") },
            text = {
                OutlinedTextField(
                    value = newLogName,
                    onValueChange = { newLogName = it },
                    label = { Text("Log-Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newLogName.isNotBlank()) {
                            val newLog = canDataRepository.createLogFile(newLogName)
                            if (newLog != null) {
                                selectedLogFile = newLog
                            }
                            newLogName = ""
                            showCreateNewLog = false
                        }
                    },
                    enabled = newLogName.isNotBlank()
                ) {
                    Text("Erstellen")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    newLogName = ""
                    showCreateNewLog = false
                }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

@Composable
fun BottomNavBar(
    navController: NavHostController,
    hasActiveChat: Boolean = false,
    onSnapshotClick: () -> Unit,
    onAiChatClick: () -> Unit = {},
    openAiChatOverlays: Set<String> = emptySet(),
    chatSessions: List<at.planqton.directcan.data.gemini.ChatSession> = emptyList(),
    onCloseOverlay: (String) -> Unit = {},
    onOpenOverlay: (String) -> Unit = {}
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Always show AI button if there are any chat sessions
    val navItems = if (chatSessions.isNotEmpty()) Screen.bottomNavItemsWithAiChat else Screen.bottomNavItems

    // State for AI chat dropdown
    var showAiChatMenu by remember { mutableStateOf(false) }

    Box {
        NavigationBar(
            modifier = Modifier.navigationBarsPadding(),
            tonalElevation = 2.dp
        ) {
            navItems.forEach { screen ->
                if (screen == Screen.Snapshot) {
                    // Special handling for snapshot button - just action, no navigation
                    NavigationBarItem(
                        icon = {
                            Icon(
                                screen.icon,
                                contentDescription = screen.title,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Text(
                                screen.title,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        },
                        selected = false,
                        onClick = onSnapshotClick,
                        alwaysShowLabel = true
                    )
                } else if (screen == Screen.ActiveAiChat) {
                    // Special handling for AI Chat button - shows active overlays
                    NavigationBarItem(
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (openAiChatOverlays.isNotEmpty()) {
                                        Badge {
                                            Text("${openAiChatOverlays.size}")
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    screen.icon,
                                    contentDescription = screen.title,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        label = {
                            Text(
                                screen.title,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        },
                        selected = false,
                        onClick = { showAiChatMenu = true },
                        alwaysShowLabel = true
                    )
                } else {
                    NavigationBarItem(
                        icon = {
                            Icon(
                                screen.icon,
                                contentDescription = screen.title,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Text(
                                screen.title,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        alwaysShowLabel = true
                    )
                }
            }
        }

        // AI Chat dropdown menu (positioned at bottom)
        DropdownMenu(
            expanded = showAiChatMenu,
            onDismissRequest = { showAiChatMenu = false },
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            if (chatSessions.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Keine Chats vorhanden") },
                    onClick = { showAiChatMenu = false },
                    enabled = false
                )
            } else {
                // Show all chat sessions
                chatSessions.forEach { session ->
                    val isOpen = session.id in openAiChatOverlays
                    DropdownMenuItem(
                        text = {
                            Text(
                                session.snapshotName,
                                fontWeight = if (isOpen) androidx.compose.ui.text.font.FontWeight.Bold else null
                            )
                        },
                        onClick = {
                            showAiChatMenu = false
                            if (!isOpen) {
                                onOpenOverlay(session.id)
                            }
                        },
                        leadingIcon = {
                            if (isOpen) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Offen",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        trailingIcon = {
                            if (isOpen) {
                                IconButton(
                                    onClick = {
                                        onCloseOverlay(session.id)
                                        showAiChatMenu = false
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Schließen",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    )
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("AI Einstellungen") },
                onClick = {
                    showAiChatMenu = false
                    navController.navigate(Screen.AiSettings.route)
                },
                leadingIcon = {
                    Icon(Icons.Filled.Settings, null, Modifier.size(20.dp))
                }
            )
        }
    }
}
