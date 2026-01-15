package at.planqton.directcan

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import at.planqton.directcan.data.can.CanDataRepository
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
import at.planqton.directcan.ui.theme.DirectCanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
    val navController = rememberNavController()
    val canDataRepository = DirectCanApplication.instance.canDataRepository

    // Snapshot state
    var pendingSnapshot by remember { mutableStateOf<CanDataRepository.SnapshotData?>(null) }
    var showSnapshotDialog by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            BottomNavBar(
                navController = navController,
                onSnapshotClick = {
                    // Capture snapshot IMMEDIATELY
                    pendingSnapshot = canDataRepository.captureSnapshot()
                    showSnapshotDialog = true
                }
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) { HomeScreen() }
            composable(Screen.Monitor.route) { MonitorScreen() }
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
                    }
                )
            }
            composable(Screen.LogManager.route) {
                LogManagerScreen(
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
            onSave = { logFile, description ->
                canDataRepository.saveSnapshot(pendingSnapshot!!, logFile, description)
                showSnapshotDialog = false
                pendingSnapshot = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotDialog(
    snapshot: CanDataRepository.SnapshotData,
    onDismiss: () -> Unit,
    onSave: (CanDataRepository.LogFileInfo, String) -> Unit
) {
    val canDataRepository = DirectCanApplication.instance.canDataRepository
    val logFiles by canDataRepository.logFiles.collectAsState()
    val recentDescriptions by canDataRepository.recentDescriptions.collectAsState()
    val context = LocalContext.current

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
                    "(${snapshot.frames.size} Frames)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column {
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedLogFile?.let { logFile ->
                        onSave(logFile, description.ifBlank { "Unbenannter Snapshot" })
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
    onSnapshotClick: () -> Unit
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar {
        Screen.bottomNavItems.forEach { screen ->
            if (screen == Screen.Snapshot) {
                // Special handling for snapshot button - just action, no navigation
                NavigationBarItem(
                    icon = { Icon(screen.icon, contentDescription = screen.title) },
                    label = { Text(screen.title) },
                    selected = false,
                    onClick = onSnapshotClick
                )
            } else {
                NavigationBarItem(
                    icon = { Icon(screen.icon, contentDescription = screen.title) },
                    label = { Text(screen.title) },
                    selected = currentRoute == screen.route,
                    onClick = {
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    }
}
