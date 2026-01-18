package at.planqton.directcan.ui.screens.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.R
import at.planqton.directcan.data.settings.SettingsRepository
import at.planqton.directcan.util.LocaleHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToLogManager: () -> Unit = {},
    onNavigateToAiSettings: () -> Unit = {},
    onNavigateToTxScriptManager: () -> Unit = {}
) {
    val usbManager = DirectCanApplication.instance.usbSerialManager
    val canDataRepository = DirectCanApplication.instance.canDataRepository
    val settingsRepository = DirectCanApplication.instance.settingsRepository
    val txScriptRepository = DirectCanApplication.instance.txScriptRepository
    val logFiles by canDataRepository.logFiles.collectAsState()
    val logDirectoryUri by canDataRepository.logDirectoryUri.collectAsState()
    val txScripts by txScriptRepository.scripts.collectAsState()

    var selectedBaudrate by remember { mutableIntStateOf(500000) }
    var showBaudrateDialog by remember { mutableStateOf(false) }
    var autoConnect by remember { mutableStateOf(true) }
    var loopbackEnabled by remember { mutableStateOf(false) }
    var showTimestamps by remember { mutableStateOf(true) }
    var hexDisplay by remember { mutableStateOf(true) }
    var logToFile by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Language setting
    val currentLanguage by settingsRepository.language.collectAsState(initial = "system")
    var showLanguageDialog by remember { mutableStateOf(false) }

    // Dev settings
    val devLogEnabled by settingsRepository.devLogEnabled.collectAsState(initial = true)
    val devLogIntervalMinutes by settingsRepository.devLogIntervalMinutes.collectAsState(initial = 5)
    var showDevLogIntervalDialog by remember { mutableStateOf(false) }

    // Port color settings
    val port1Color by settingsRepository.port1Color.collectAsState(initial = SettingsRepository.DEFAULT_PORT_1_COLOR)
    val port2Color by settingsRepository.port2Color.collectAsState(initial = SettingsRepository.DEFAULT_PORT_2_COLOR)
    var showPort1ColorPicker by remember { mutableStateOf(false) }
    var showPort2ColorPicker by remember { mutableStateOf(false) }

    // Folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { selectedUri ->
            canDataRepository.setLogDirectoryUri(selectedUri)
        }
    }

    LaunchedEffect(Unit) {
        canDataRepository.refreshLogFiles()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // CAN Settings Section
            item {
                SettingsSectionHeader("CAN-Bus Einstellungen")
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Speed,
                    title = "Baudrate",
                    subtitle = "${selectedBaudrate / 1000} kbit/s",
                    onClick = { showBaudrateDialog = true }
                )
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Usb,
                    title = "Automatisch verbinden",
                    subtitle = "Automatisch mit bekannten Geräten verbinden",
                    checked = autoConnect,
                    onCheckedChange = { autoConnect = it }
                )
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Loop,
                    title = "Loopback-Modus",
                    subtitle = if (loopbackEnabled) "Aktiviert (nur für Tests)" else "Deaktiviert (Normal-Betrieb)",
                    checked = loopbackEnabled,
                    onCheckedChange = {
                        loopbackEnabled = it
                        usbManager.setLoopback(it)
                    }
                )
            }

            // Display Settings
            item {
                SettingsSectionHeader(stringResource(R.string.appearance))
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Language,
                    title = stringResource(R.string.language),
                    subtitle = LocaleHelper.getLanguageDisplayName(currentLanguage, context),
                    onClick = { showLanguageDialog = true }
                )
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Schedule,
                    title = "Zeitstempel anzeigen",
                    subtitle = "Zeitstempel in CAN-Monitor anzeigen",
                    checked = showTimestamps,
                    onCheckedChange = { showTimestamps = it }
                )
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Code,
                    title = "Hexadezimal-Anzeige",
                    subtitle = "Daten in Hex statt Dezimal anzeigen",
                    checked = hexDisplay,
                    onCheckedChange = { hexDisplay = it }
                )
            }

            // Multi-Port Colors Section
            item {
                SettingsSectionHeader("Multi-Port Farben")
            }

            item {
                PortColorSettingsItem(
                    port = 1,
                    color = Color(port1Color.toInt()),
                    onClick = { showPort1ColorPicker = true }
                )
            }

            item {
                PortColorSettingsItem(
                    port = 2,
                    color = Color(port2Color.toInt()),
                    onClick = { showPort2ColorPicker = true }
                )
            }

            // Logging Settings
            item {
                SettingsSectionHeader("Protokollierung")
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Folder,
                    title = "Log-Speicherort",
                    subtitle = if (logDirectoryUri != null)
                        canDataRepository.getLogDirectoryDisplayPath()
                    else
                        "Tippen um Ordner zu wählen",
                    onClick = { folderPickerLauncher.launch(null) }
                )
            }

            item {
                SettingsItem(
                    icon = Icons.Default.CameraAlt,
                    title = "Snapshot Manager",
                    subtitle = if (logDirectoryUri != null)
                        "${logFiles.size} Log-Dateien"
                    else
                        "Zuerst Speicherort festlegen",
                    onClick = {
                        if (logDirectoryUri != null) {
                            onNavigateToLogManager()
                        }
                    }
                )
            }

            // TX Scripts Section
            item {
                SettingsSectionHeader("TX Scripts")
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Code,
                    title = "TX Script Manager",
                    subtitle = "${txScripts.size} Scripts verwalten",
                    onClick = onNavigateToTxScriptManager
                )
            }

            // AI Section
            item {
                SettingsSectionHeader("Künstliche Intelligenz")
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Psychology,
                    title = "AI Chat",
                    subtitle = "API-Key und Modell konfigurieren",
                    onClick = onNavigateToAiSettings
                )
            }

            // Dev Menu Section
            item {
                SettingsSectionHeader("Entwickler")
            }

            item {
                SettingsSwitchItem(
                    icon = Icons.Default.BugReport,
                    title = "Status-Logging",
                    subtitle = if (devLogEnabled)
                        "Logcat-Ausgabe alle $devLogIntervalMinutes Min während Capture"
                    else
                        "Deaktiviert",
                    checked = devLogEnabled,
                    onCheckedChange = {
                        scope.launch { settingsRepository.setDevLogEnabled(it) }
                    }
                )
            }

            if (devLogEnabled) {
                item {
                    SettingsItem(
                        icon = Icons.Default.Timer,
                        title = "Log-Intervall",
                        subtitle = "$devLogIntervalMinutes Minuten",
                        onClick = { showDevLogIntervalDialog = true }
                    )
                }
            }

            // About Section
            item {
                SettingsSectionHeader("Info")
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "Über DirectCAN",
                    subtitle = "Version, Lizenzen und mehr",
                    onClick = { showAboutDialog = true }
                )
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Help,
                    title = "Hilfe & Dokumentation",
                    subtitle = "Anleitungen und FAQ",
                    onClick = { /* Open help */ }
                )
            }

            item {
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Baudrate Selection Dialog
    if (showBaudrateDialog) {
        BaudrateSelectionDialog(
            selectedBaudrate = selectedBaudrate,
            onBaudrateSelected = { baudrate ->
                selectedBaudrate = baudrate
                usbManager.setBaudrate(baudrate)
                showBaudrateDialog = false
            },
            onDismiss = { showBaudrateDialog = false }
        )
    }

    // About Dialog
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    // Language Selection Dialog
    if (showLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = currentLanguage,
            onLanguageSelected = { lang ->
                scope.launch {
                    settingsRepository.setLanguage(lang)
                    // Restart activity to apply language change
                    (context as? Activity)?.let { activity ->
                        activity.recreate()
                    }
                }
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    // Dev Log Interval Dialog
    if (showDevLogIntervalDialog) {
        DevLogIntervalDialog(
            currentInterval = devLogIntervalMinutes,
            onIntervalSelected = { interval ->
                scope.launch { settingsRepository.setDevLogIntervalMinutes(interval) }
                showDevLogIntervalDialog = false
            },
            onDismiss = { showDevLogIntervalDialog = false }
        )
    }

    // Port 1 Color Picker Dialog
    if (showPort1ColorPicker) {
        PortColorPickerDialog(
            port = 1,
            currentColor = Color(port1Color.toInt()),
            onColorSelected = { color ->
                scope.launch { settingsRepository.setPort1Color(color.value.toLong()) }
                showPort1ColorPicker = false
            },
            onDismiss = { showPort1ColorPicker = false }
        )
    }

    // Port 2 Color Picker Dialog
    if (showPort2ColorPicker) {
        PortColorPickerDialog(
            port = 2,
            currentColor = Color(port2Color.toInt()),
            onColorSelected = { color ->
                scope.launch { settingsRepository.setPort2Color(color.value.toLong()) }
                showPort2ColorPicker = false
            },
            onDismiss = { showPort2ColorPicker = false }
        )
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
fun BaudrateSelectionDialog(
    selectedBaudrate: Int,
    onBaudrateSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val baudrates = listOf(
        125000 to "125 kbit/s (Low-Speed CAN)",
        250000 to "250 kbit/s",
        500000 to "500 kbit/s (Standard)",
        1000000 to "1 Mbit/s (High-Speed)"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CAN Baudrate wählen") },
        text = {
            Column {
                baudrates.forEach { (baudrate, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onBaudrateSelected(baudrate) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = baudrate == selectedBaudrate,
                            onClick = { onBaudrateSelected(baudrate) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}

@Composable
fun LanguageSelectionDialog(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val languages = listOf(
        "system" to stringResource(R.string.language_system),
        "en" to stringResource(R.string.language_en),
        "de" to stringResource(R.string.language_de)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column {
                languages.forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLanguageSelected(code) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = code == currentLanguage,
                            onClick = { onLanguageSelected(code) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DirectionsCar, null) },
        title = { Text("DirectCAN") },
        text = {
            Column {
                Text("Version 1.0.0")
                Spacer(Modifier.height(8.dp))
                Text(
                    "CAN-Bus Diagnose-App für Android",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Funktionen:",
                    style = MaterialTheme.typography.titleSmall
                )
                Text("• CAN-Bus Monitoring")
                Text("• Fehlerspeicher auslesen/löschen")
                Text("• Live-Daten anzeigen")
                Text("• DBC-Datei Unterstützung")
                Text("• ISO-TP Protokoll")
                Spacer(Modifier.height(16.dp))
                Text(
                    "Hardware: Adafruit Feather M4 CAN",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@Composable
fun DevLogIntervalDialog(
    currentInterval: Int,
    onIntervalSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val intervals = listOf(
        1 to "1 Minute",
        2 to "2 Minuten",
        5 to "5 Minuten",
        10 to "10 Minuten",
        15 to "15 Minuten",
        30 to "30 Minuten"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log-Intervall wählen") },
        text = {
            Column {
                Text(
                    "Wie oft soll der Status ins Logcat geschrieben werden?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                intervals.forEach { (minutes, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onIntervalSelected(minutes) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = minutes == currentInterval,
                            onClick = { onIntervalSelected(minutes) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}

@Composable
fun PortColorSettingsItem(
    port: Int,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color preview box
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color, RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Port $port Farbe", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Farbe für Port $port im Monitor/Sniffer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PortColorPickerDialog(
    port: Int,
    currentColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = listOf(
        Color(0xFF4CAF50) to "Grün",
        Color(0xFF2196F3) to "Blau",
        Color(0xFFFF9800) to "Orange",
        Color(0xFF9C27B0) to "Lila",
        Color(0xFFF44336) to "Rot",
        Color(0xFF00BCD4) to "Cyan",
        Color(0xFFFFEB3B) to "Gelb",
        Color(0xFF795548) to "Braun"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Port $port Farbe wählen") },
        text = {
            Column {
                colors.chunked(4).forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        row.forEach { (color, _) ->
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(color, CircleShape)
                                    .then(
                                        if (color == currentColor) {
                                            Modifier.border(
                                                width = 3.dp,
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = CircleShape
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable { onColorSelected(color) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}
