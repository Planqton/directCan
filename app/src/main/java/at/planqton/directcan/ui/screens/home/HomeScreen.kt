package at.planqton.directcan.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.usb.UsbSerialManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val usbManager = DirectCanApplication.instance.usbSerialManager
    val dbcRepository = DirectCanApplication.instance.dbcRepository
    val canDataRepository = DirectCanApplication.instance.canDataRepository

    val connectionState by usbManager.connectionState.collectAsState()
    val availableDevices by usbManager.availableDevices.collectAsState()
    val activeDbc by dbcRepository.activeDbc.collectAsState()
    val autoStartLogging by canDataRepository.autoStartLogging.collectAsState()
    val isLogging by canDataRepository.isLogging.collectAsState()

    val scope = rememberCoroutineScope()
    var firmwareInfo by remember { mutableStateOf<String?>(null) }
    var debugOutput by remember { mutableStateOf<String?>(null) }
    var showDeviceDialog by remember { mutableStateOf(false) }

    // Listen for firmware responses
    LaunchedEffect(Unit) {
        usbManager.receivedLines.collect { line ->
            if (line.contains("firmware") || line.contains("version")) {
                firmwareInfo = line
            }
            // Capture all responses for debug
            if (line.contains("baudrate") || line.contains("logging") || line.contains("error")) {
                debugOutput = line
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DirectCAN") },
                actions = {
                    IconButton(onClick = { usbManager.refreshDeviceList() }) {
                        Icon(Icons.Default.Refresh, "Aktualisieren")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection Status Card
            item {
                ConnectionCard(
                    state = connectionState,
                    firmwareInfo = firmwareInfo,
                    autoStartLogging = autoStartLogging,
                    isLogging = isLogging,
                    onConnect = { showDeviceDialog = true },
                    onDisconnect = { usbManager.disconnect() },
                    onAutoStartChanged = { canDataRepository.setAutoStartLogging(it) }
                )
            }

            // Active DBC Card
            item {
                DbcCard(
                    activeDbc = activeDbc?.name
                )
            }

            // Debug Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Debug", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { usbManager.getStatus() },
                                enabled = connectionState == UsbSerialManager.ConnectionState.CONNECTED
                            ) {
                                Text("Status ?")
                            }
                            OutlinedButton(
                                onClick = { usbManager.getInfo() },
                                enabled = connectionState == UsbSerialManager.ConnectionState.CONNECTED
                            ) {
                                Text("Info i")
                            }
                        }
                        debugOutput?.let { output ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                output,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Reset Stats Card
            item {
                var showResetDialog by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.DeleteForever,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = Color(0xFFF44336)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Statistiken zurücksetzen",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "Filter, aktive DBC und alle Daten löschen",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { showResetDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF44336)
                                )
                            ) {
                                Text("Reset Stats")
                            }
                        }
                    }
                }

                if (showResetDialog) {
                    AlertDialog(
                        onDismissRequest = { showResetDialog = false },
                        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
                        title = { Text("Alle Daten zurücksetzen?") },
                        text = {
                            Text("Dies löscht alle Filter, die aktive DBC-Zuordnung, alle gespeicherten Einstellungen und alle erfassten Daten. Diese Aktion kann nicht rückgängig gemacht werden.")
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    DirectCanApplication.instance.resetAllStats()
                                    showResetDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF44336)
                                )
                            ) {
                                Text("Zurücksetzen")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetDialog = false }) {
                                Text("Abbrechen")
                            }
                        }
                    )
                }
            }

            // Quick Actions
            item {
                Text(
                    "Schnellzugriff",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.List,
                        label = "CAN Monitor",
                        enabled = connectionState == UsbSerialManager.ConnectionState.CONNECTED
                    )
                    QuickActionButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Build,
                        label = "DTCs lesen",
                        enabled = connectionState == UsbSerialManager.ConnectionState.CONNECTED
                    )
                }
            }

            // Available Devices
            if (availableDevices.isNotEmpty()) {
                item {
                    Text(
                        "Verfügbare Geräte",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(availableDevices) { device ->
                    DeviceCard(
                        device = device,
                        onConnect = { usbManager.connect(device) }
                    )
                }
            }
        }
    }

    // Device Selection Dialog
    if (showDeviceDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceDialog = false },
            title = { Text("Gerät auswählen") },
            text = {
                Column {
                    if (availableDevices.isEmpty()) {
                        Text("Keine Geräte gefunden")
                    } else {
                        availableDevices.forEach { device ->
                            TextButton(
                                onClick = {
                                    usbManager.connect(device)
                                    showDeviceDialog = false
                                }
                            ) {
                                Text(device.displayName)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeviceDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

@Composable
fun ConnectionCard(
    state: UsbSerialManager.ConnectionState,
    firmwareInfo: String?,
    autoStartLogging: Boolean,
    isLogging: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAutoStartChanged: (Boolean) -> Unit
) {
    val (statusColor, statusText, statusIcon) = when (state) {
        UsbSerialManager.ConnectionState.CONNECTED ->
            Triple(Color(0xFF4CAF50), "Verbunden", Icons.Default.CheckCircle)
        UsbSerialManager.ConnectionState.CONNECTING ->
            Triple(Color(0xFFFFC107), "Verbinde...", Icons.Default.Sync)
        UsbSerialManager.ConnectionState.ERROR ->
            Triple(Color(0xFFF44336), "Fehler", Icons.Default.Error)
        UsbSerialManager.ConnectionState.DISCONNECTED ->
            Triple(Color(0xFF9E9E9E), "Nicht verbunden", Icons.Default.Cancel)
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Verbindungsstatus",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor
                    )
                }
                when (state) {
                    UsbSerialManager.ConnectionState.CONNECTED -> {
                        FilledTonalButton(onClick = onDisconnect) {
                            Text("Trennen")
                        }
                    }
                    UsbSerialManager.ConnectionState.DISCONNECTED,
                    UsbSerialManager.ConnectionState.ERROR -> {
                        Button(onClick = onConnect) {
                            Text("Verbinden")
                        }
                    }
                    else -> {}
                }
            }

            // Auto-start logging checkbox
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked = autoStartLogging,
                    onCheckedChange = onAutoStartChanged
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Loggen direkt starten",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (isLogging) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Default.FiberManualRecord,
                        contentDescription = "Logging aktiv",
                        tint = Color(0xFFF44336),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Logging aktiv",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF44336)
                    )
                }
            }

            if (firmwareInfo != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    firmwareInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun DbcCard(
    activeDbc: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Aktive DBC",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        activeDbc ?: "Keine DBC geladen",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (activeDbc != null) MaterialTheme.colorScheme.onSurface
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun QuickActionButton(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    OutlinedCard(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun DeviceCard(
    device: UsbSerialManager.UsbDeviceInfo,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onConnect
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (device.isConnected) Icons.Default.Usb else Icons.Default.UsbOff,
                contentDescription = null,
                tint = if (device.isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "VID: ${device.vendorId.toString(16)} PID: ${device.productId.toString(16)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (device.isConnected) {
                Text(
                    "Verbunden",
                    color = Color(0xFF4CAF50),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
