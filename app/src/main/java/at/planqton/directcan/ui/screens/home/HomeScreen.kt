package at.planqton.directcan.ui.screens.home

import android.content.Context
import android.hardware.usb.UsbManager
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
    onNavigateToDeviceManager: () -> Unit = {}
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
    val activeDevice by deviceManager.activeDevice.collectAsState()
    val deviceConnectionState by deviceManager.connectionState.collectAsState()
    val canBitrate by deviceManager.canBitrate.collectAsState()

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
                    activeDevice = activeDevice,
                    connectionState = deviceConnectionState,
                    autoStartLogging = autoStartLogging,
                    isLogging = isLogging,
                    canBitrate = canBitrate,
                    onConnect = { configId -> scope.launch { deviceManager.connect(configId) } },
                    onDisconnect = { scope.launch { deviceManager.disconnect() } },
                    onAutoStartChanged = { canDataRepository.setAutoStartLogging(it) },
                    onCanBitrateChanged = { scope.launch { deviceManager.setCanBitrate(it) } },
                    onOpenDeviceManager = onNavigateToDeviceManager
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
        DeviceType.USB_SERIAL -> Icons.Default.Usb
        DeviceType.SIMULATOR -> Icons.Default.DirectionsCar
        DeviceType.PEAK_CAN -> Icons.Default.Router
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
    activeDevice: CanDevice?,
    connectionState: ConnectionState,
    autoStartLogging: Boolean,
    isLogging: Boolean,
    canBitrate: Int,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onAutoStartChanged: (Boolean) -> Unit,
    onCanBitrateChanged: (Int) -> Unit,
    onOpenDeviceManager: () -> Unit
) {
    // Standard CAN bus bitrates
    val standardCanBitrates = listOf(
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
    var bitrateExpanded by remember { mutableStateOf(false) }
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

            // CAN-Bitrate Dropdown - vor der Geräteliste
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "CAN-Bitrate:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(100.dp)
                )
                Spacer(Modifier.width(8.dp))
                ExposedDropdownMenuBox(
                    expanded = bitrateExpanded,
                    onExpandedChange = { bitrateExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = standardCanBitrates.find { it.first == canBitrate }?.second
                            ?: "${canBitrate / 1000} kbit/s",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bitrateExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    ExposedDropdownMenu(
                        expanded = bitrateExpanded,
                        onDismissRequest = { bitrateExpanded = false }
                    ) {
                        standardCanBitrates.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onCanBitrateChanged(value)
                                    bitrateExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
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
                // List of configured devices - only one can be connected at a time
                val isAnyConnected = connectionState == ConnectionState.CONNECTED

                deviceConfigs.forEach { config ->
                    val isActive = activeDevice?.id == config.id
                    val state = if (isActive) connectionState else ConnectionState.DISCONNECTED

                    DeviceListItem(
                        config = config,
                        connectionState = state,
                        isActive = isActive,
                        canConnect = !isAnyConnected || isActive, // Only show connect if none connected
                        onConnect = { onConnect(config.id) },
                        onDisconnect = onDisconnect
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
 * Get the detected USB device name for a UsbSerialConfig.
 * Returns the product name of the matching USB device, or null if not found.
 */
private fun getDetectedUsbDeviceName(context: Context, config: UsbSerialConfig): String? {
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

/**
 * Get the detected USB device name for a PeakCanConfig.
 * Returns the product name of the PEAK CAN device, or null if not found.
 */
private fun getDetectedPeakCanDeviceName(context: Context): String? {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    // Check all connected USB devices for PEAK vendor ID
    return usbManager.deviceList.values.find { device ->
        device.vendorId == PeakCanConfig.PEAK_VENDOR_ID
    }?.productName
}

@Composable
private fun DeviceListItem(
    config: DeviceConfig,
    connectionState: ConnectionState,
    isActive: Boolean,
    canConnect: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current

    // Detect USB device name - live update every 2 seconds
    var detectedDeviceName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(config) {
        when (config) {
            is UsbSerialConfig -> {
                while (true) {
                    detectedDeviceName = getDetectedUsbDeviceName(context, config)
                    kotlinx.coroutines.delay(2000) // Check every 2 seconds
                }
            }
            is PeakCanConfig -> {
                while (true) {
                    detectedDeviceName = getDetectedPeakCanDeviceName(context)
                    kotlinx.coroutines.delay(2000) // Check every 2 seconds
                }
            }
            else -> { /* Simulator doesn't need detection */ }
        }
    }

    val icon = when (config.type) {
        DeviceType.USB_SERIAL -> Icons.Default.Usb
        DeviceType.SIMULATOR -> Icons.Default.DirectionsCar
        DeviceType.PEAK_CAN -> Icons.Default.Router
    }

    val statusColor = when (connectionState) {
        ConnectionState.CONNECTED -> Color(0xFF4CAF50)
        ConnectionState.CONNECTING -> Color(0xFFFFC107)
        ConnectionState.ERROR -> Color(0xFFF44336)
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
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
        Column(modifier = Modifier.weight(1f)) {
            // Show config name with detected device name
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(config.name, style = MaterialTheme.typography.bodyLarge)
                // Show detected device name for USB Serial and PEAK CAN configs
                if (config is UsbSerialConfig || config is PeakCanConfig) {
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
                if (canConnect) {
                    Button(onClick = onConnect) {
                        Text("Verbinden")
                    }
                }
                // If another device is connected, show nothing for this device
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
