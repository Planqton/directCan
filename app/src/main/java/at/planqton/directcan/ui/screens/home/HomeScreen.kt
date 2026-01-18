package at.planqton.directcan.ui.screens.home

import android.app.Activity
import android.content.Context
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CarRepair
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.device.*
import at.planqton.directcan.data.usb.UsbSerialManager
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToDeviceManager: () -> Unit = {},
    onNavigateToDtc: () -> Unit = {},
    onCloseApp: () -> Unit = {}
) {
    val usbManager = DirectCanApplication.instance.usbSerialManager
    val dbcRepository = DirectCanApplication.instance.dbcRepository
    val canDataRepository = DirectCanApplication.instance.canDataRepository
    val deviceManager = DirectCanApplication.instance.deviceManager

    val connectionState by usbManager.connectionState.collectAsState()
    val activeDbc by dbcRepository.activeDbc.collectAsState()
    val autoStartLogging by canDataRepository.autoStartLogging.collectAsState()
    val isLogging by canDataRepository.isLogging.collectAsState()

    // Device Manager state
    val deviceConfigs by deviceManager.deviceConfigs.collectAsState()
    val devices by deviceManager.devices.collectAsState()
    val activeDevices by deviceManager.activeDevices.collectAsState()
    val connectedDeviceCount by deviceManager.connectedDeviceCount.collectAsState()
    val deviceConnectionState by deviceManager.connectionState.collectAsState()
    val activeDevice by deviceManager.activeDevice.collectAsState()

    // Check for ISO-TP support
    val slcanDevice = activeDevice as? UsbSlcanDevice
    val capabilities by slcanDevice?.capabilities?.collectAsState() ?: remember { mutableStateOf(null) }
    val supportsIsoTp = capabilities?.supportsIsoTp == true

    val scope = rememberCoroutineScope()
    var firmwareInfo by remember { mutableStateOf<String?>(null) }
    var debugOutput by remember { mutableStateOf<String?>(null) }

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
                    // Serial Monitor Button - only show when connected
                    if (deviceConnectionState == ConnectionState.CONNECTED) {
                        IconButton(onClick = { DirectCanApplication.instance.showSerialMonitor() }) {
                            Icon(Icons.Default.Terminal, "Serial Monitor")
                        }
                    }
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
            // Connection Card with Device List
            item {
                DeviceConnectionCard(
                    deviceConfigs = deviceConfigs,
                    activeDevices = activeDevices,
                    connectedDeviceCount = connectedDeviceCount,
                    connectionState = deviceConnectionState,
                    autoStartLogging = autoStartLogging,
                    isLogging = isLogging,
                    onConnect = { configId -> scope.launch { deviceManager.connect(configId) } },
                    onDisconnectDevice = { configId -> scope.launch { deviceManager.disconnectDevice(configId) } },
                    onDisconnectAll = { scope.launch { deviceManager.disconnect() } },
                    onAutoStartChanged = { canDataRepository.setAutoStartLogging(it) },
                    onDeviceBitrateChanged = { configId, bitrate -> scope.launch { deviceManager.updateDeviceBitrate(configId, bitrate) } },
                    onOpenDeviceManager = onNavigateToDeviceManager
                )
            }

            // Active DBC Card
            item {
                DbcCard(
                    activeDbc = activeDbc?.name
                )
            }

            // DTC/OBD2 Card - only show when device is connected
            if (deviceConnectionState == ConnectionState.CONNECTED) {
                item {
                    DtcModeCard(
                        supportsIsoTp = supportsIsoTp,
                        firmwareName = capabilities?.name,
                        firmwareVersion = capabilities?.version,
                        onOpenDtc = onNavigateToDtc
                    )
                }
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
                        icon = Icons.Default.ExitToApp,
                        label = "App beenden",
                        enabled = true,
                        onClick = onCloseApp
                    )
                }
            }

        }
    }
}

@Composable
fun ConfiguredDeviceCard(
    config: DeviceConfig,
    connectionState: ConnectionState,
    isActive: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val icon = when (config.type) {
        DeviceType.USB_SLCAN -> Icons.Default.Usb
    }

    val statusColor = when (connectionState) {
        ConnectionState.CONNECTED -> Color(0xFF4CAF50)
        ConnectionState.CONNECTING -> Color(0xFFFFC107)
        ConnectionState.ERROR -> Color(0xFFF44336)
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val statusText = when (connectionState) {
        ConnectionState.CONNECTED -> "Verbunden"
        ConnectionState.CONNECTING -> "Verbinde..."
        ConnectionState.ERROR -> "Fehler"
        ConnectionState.DISCONNECTED -> "Getrennt"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive && connectionState == ConnectionState.CONNECTED)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (connectionState == ConnectionState.CONNECTED)
                    Color(0xFF4CAF50)
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(config.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
            }

            when (connectionState) {
                ConnectionState.CONNECTED -> {
                    FilledTonalButton(onClick = onDisconnect) {
                        Text("Trennen")
                    }
                }
                ConnectionState.CONNECTING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
                ConnectionState.DISCONNECTED, ConnectionState.ERROR -> {
                    Button(onClick = onConnect) {
                        Text("Verbinden")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConnectionCard(
    deviceConfigs: List<DeviceConfig>,
    activeDevices: Map<Int, CanDevice>,
    connectedDeviceCount: Int,
    connectionState: ConnectionState,
    autoStartLogging: Boolean,
    isLogging: Boolean,
    onConnect: (String) -> Unit,
    onDisconnectDevice: (String) -> Unit,
    onDisconnectAll: () -> Unit,
    onAutoStartChanged: (Boolean) -> Unit,
    onDeviceBitrateChanged: (String, Int) -> Unit,
    onOpenDeviceManager: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with title and Gerätemanager button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Verbindung",
                    style = MaterialTheme.typography.titleMedium
                )
                FilledTonalButton(onClick = onOpenDeviceManager) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Gerätemanager")
                }
            }

            Spacer(Modifier.height(12.dp))

            // Device List
            if (deviceConfigs.isEmpty()) {
                // No devices configured
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenDeviceManager
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Gerät im Gerätemanager anlegen...",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                // List of configured devices - up to 2 can be connected at a time
                val canConnectMore = connectedDeviceCount < 2

                deviceConfigs.forEach { config ->
                    // Find if this device is connected and on which port
                    val connectedPort = activeDevices.entries.find { it.value.id == config.id }?.key
                    val isConnected = connectedPort != null
                    val state = if (isConnected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED

                    DeviceListItem(
                        config = config,
                        connectionState = state,
                        connectedPort = connectedPort,
                        canConnect = canConnectMore && !isConnected,
                        onBitrateChanged = { bitrate -> onDeviceBitrateChanged(config.id, bitrate) },
                        onConnect = { onConnect(config.id) },
                        onDisconnect = { onDisconnectDevice(config.id) }
                    )
                    if (config != deviceConfigs.last()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }

            // Auto-start logging checkbox
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
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
        }
    }
}

/**
 * Get the detected USB device name for a UsbSlcanConfig.
 * Returns the product name of the matching USB device, or null if not found.
 */
private fun getDetectedUsbDeviceName(context: Context, config: UsbSlcanConfig): String? {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

    return if (config.vendorId != null && config.productId != null) {
        // Find specific device by VID:PID
        drivers.find { driver ->
            driver.device.vendorId == config.vendorId &&
                    driver.device.productId == config.productId
        }?.device?.productName
    } else {
        // Auto-detect: return first device name
        drivers.firstOrNull()?.device?.productName
    }
}

// Standard CAN bus bitrates for dropdown
private val standardCanBitrates = listOf(
    10000 to "10 kbit/s",
    20000 to "20 kbit/s",
    50000 to "50 kbit/s",
    100000 to "100 kbit/s",
    125000 to "125 kbit/s",
    250000 to "250 kbit/s",
    500000 to "500 kbit/s",
    800000 to "800 kbit/s",
    1000000 to "1 Mbit/s"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceListItem(
    config: DeviceConfig,
    connectionState: ConnectionState,
    connectedPort: Int?,
    canConnect: Boolean,
    onBitrateChanged: (Int) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current

    // Detect USB device name - live update every 2 seconds
    var detectedDeviceName by remember { mutableStateOf<String?>(null) }

    // Bitrate dropdown state
    var bitrateExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(config) {
        if (config is UsbSlcanConfig) {
            while (true) {
                detectedDeviceName = getDetectedUsbDeviceName(context, config)
                kotlinx.coroutines.delay(2000) // Check every 2 seconds
            }
        }
    }

    val icon = when (config.type) {
        DeviceType.USB_SLCAN -> Icons.Default.Usb
    }

    val statusColor = when (connectionState) {
        ConnectionState.CONNECTED -> Color(0xFF4CAF50)
        ConnectionState.CONNECTING -> Color(0xFFFFC107)
        ConnectionState.ERROR -> Color(0xFFF44336)
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // First row: Icon, Name + Device detection, Bitrate dropdown, Connect button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (connectionState == ConnectionState.CONNECTED)
                    Color(0xFF4CAF50)
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))

            // Device name and detection
            Column(modifier = Modifier.weight(1f)) {
                // Show config name with detected device name
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(config.name, style = MaterialTheme.typography.bodyLarge)
                    // Show detected device name for USB SLCAN configs
                    if (config is UsbSlcanConfig) {
                        if (detectedDeviceName != null) {
                            Text(
                                " - $detectedDeviceName",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                " - Nicht erkannt",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                // Status text with port info
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (connectedPort != null) {
                        Text(
                            "Port $connectedPort • Verbunden",
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                    } else {
                        Text(
                            when (connectionState) {
                                ConnectionState.CONNECTED -> "Verbunden"
                                ConnectionState.CONNECTING -> "Verbinde..."
                                ConnectionState.ERROR -> "Fehler"
                                ConnectionState.DISCONNECTED -> "Getrennt"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                        // Show hint if max devices reached
                        if (!canConnect && connectionState == ConnectionState.DISCONNECTED) {
                            Text(
                                " (Max. 2 erreicht)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // CAN Bitrate dropdown (only for USB SLCAN devices) - between name and button
            if (config is UsbSlcanConfig) {
                Spacer(Modifier.width(8.dp))
                ExposedDropdownMenuBox(
                    expanded = bitrateExpanded,
                    onExpandedChange = {
                        if (connectionState != ConnectionState.CONNECTED) {
                            bitrateExpanded = it
                        }
                    },
                    modifier = Modifier.width(130.dp)
                ) {
                    OutlinedTextField(
                        value = standardCanBitrates.find { it.first == config.canBitrate }?.second
                            ?: "${config.canBitrate / 1000} kbit/s",
                        onValueChange = {},
                        readOnly = true,
                        enabled = connectionState != ConnectionState.CONNECTED,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = bitrateExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = bitrateExpanded,
                        onDismissRequest = { bitrateExpanded = false }
                    ) {
                        standardCanBitrates.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onBitrateChanged(value)
                                    bitrateExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            // Connect/Disconnect button
            when (connectionState) {
                ConnectionState.CONNECTED -> {
                    FilledTonalButton(onClick = onDisconnect) {
                        Text("Trennen")
                    }
                }
                ConnectionState.CONNECTING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
                ConnectionState.DISCONNECTED, ConnectionState.ERROR -> {
                    if (canConnect) {
                        Button(onClick = onConnect) {
                            Text("Verbinden")
                        }
                    }
                    // If max devices connected, show nothing (hint shown in status text)
                }
            }
        }
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
fun DtcModeCard(
    supportsIsoTp: Boolean,
    firmwareName: String?,
    firmwareVersion: String?,
    onOpenDtc: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.CarRepair,
                    contentDescription = null,
                    tint = if (supportsIsoTp) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "OBD2 / DTC Diagnose",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (supportsIsoTp) {
                        Text(
                            if (firmwareName != null && firmwareVersion != null)
                                "$firmwareName v$firmwareVersion - ISO-TP verfuegbar"
                            else "ISO-TP verfuegbar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            "Firmware nicht kompatibel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (supportsIsoTp) {
                    Button(onClick = onOpenDtc) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("DTCs lesen")
                    }
                } else {
                    // Show info icon for incompatible firmware
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Info",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Show hint when firmware is not compatible
            if (!supportsIsoTp) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Fuer DTC-Diagnose wird die erweiterte DirectCAN Firmware benoetigt. " +
                            "Diese unterstuetzt ISO-TP fuer OBD2-Kommunikation.",
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
