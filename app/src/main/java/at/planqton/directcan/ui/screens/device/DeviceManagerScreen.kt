package at.planqton.directcan.ui.screens.device

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.device.*
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.launch

/**
 * Device Manager Screen - Similar to SavvyCAN's Connection Settings
 * Full screen for managing CAN device connections.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceManagerScreen(
    onNavigateBack: () -> Unit
) {
    val deviceManager = DirectCanApplication.instance.deviceManager
    val scope = rememberCoroutineScope()

    val deviceConfigs by deviceManager.deviceConfigs.collectAsState()
    val devices by deviceManager.devices.collectAsState()
    val activeDevice by deviceManager.activeDevice.collectAsState()
    val connectionState by deviceManager.connectionState.collectAsState()

    var selectedDeviceId by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<DeviceConfig?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<DeviceConfig?>(null) }

    // Connection test state
    var testResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    // Auto-select active device
    LaunchedEffect(activeDevice) {
        if (activeDevice != null) {
            selectedDeviceId = activeDevice?.id
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verbindungen") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, "Gerät hinzufügen")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Connected Devices Section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .weight(1f)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Typ",
                            modifier = Modifier.weight(0.8f),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            "Name",
                            modifier = Modifier.weight(1.2f),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            "Status",
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    HorizontalDivider()

                    // Device List
                    if (deviceConfigs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Keine Geräte konfiguriert",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f)
                        ) {
                            items(deviceConfigs, key = { it.id }) { config ->
                                val device = devices.find { it.id == config.id }
                                val isActive = activeDevice?.id == config.id
                                val isSelected = selectedDeviceId == config.id
                                val deviceState = if (isActive) connectionState else ConnectionState.DISCONNECTED

                                DeviceRow(
                                    config = config,
                                    connectionState = deviceState,
                                    isSelected = isSelected,
                                    onClick = { selectedDeviceId = config.id }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            // Selected device config (used for actions and bus details)
            val selectedConfig = deviceConfigs.find { it.id == selectedDeviceId }
            val isSelectedActive = activeDevice?.id == selectedDeviceId

            // Action Buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Add New Device Connection
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Neue Verbindung hinzufügen")
                }

                if (isSelectedActive && connectionState == ConnectionState.CONNECTED) {
                    OutlinedButton(
                        onClick = {
                            scope.launch { deviceManager.disconnect() }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.LinkOff, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Gerät trennen")
                    }
                } else if (selectedConfig != null) {
                    OutlinedButton(
                        onClick = {
                            scope.launch { deviceManager.connect(selectedConfig.id) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = connectionState != ConnectionState.CONNECTING
                    ) {
                        Icon(Icons.Default.Link, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Mit Gerät verbinden")
                    }
                }

                // Edit/Delete buttons for selected device
                if (selectedConfig != null && selectedConfig.id != SimulatorConfig.DEFAULT.id) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { editingConfig = selectedConfig },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Bearbeiten")
                        }
                        OutlinedButton(
                            onClick = { showDeleteConfirm = selectedConfig },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Löschen")
                        }
                    }
                }

                // Connection Test Button
                if (selectedConfig != null && selectedConfig.type != DeviceType.SIMULATOR) {
                    OutlinedButton(
                        onClick = {
                            testResult = null
                            isTesting = true
                            scope.launch {
                                testResult = deviceManager.testConnection(selectedConfig.id)
                                isTesting = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isTesting && connectionState == ConnectionState.DISCONNECTED
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.NetworkCheck, null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (isTesting) "Teste Verbindung..." else "Verbindung testen")
                    }

                    // Test Result Display
                    testResult?.let { result ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (result.success)
                                    Color(0xFF4CAF50).copy(alpha = 0.1f)
                                else
                                    Color(0xFFF44336).copy(alpha = 0.1f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                                    contentDescription = null,
                                    tint = if (result.success) Color(0xFF4CAF50) else Color(0xFFF44336),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        result.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    result.info.forEach { (key, value) ->
                                        Text(
                                            "$key: $value",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            // Bus Details Section (for selected device)
            selectedConfig?.let { config ->
                BusDetailsCard(
                    config = config,
                    connectionState = if (activeDevice?.id == config.id) connectionState else ConnectionState.DISCONNECTED,
                    device = devices.find { it.id == config.id }
                )
            }
        }
    }

    // Add Device Dialog
    if (showAddDialog) {
        AddDeviceDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { config ->
                scope.launch {
                    deviceManager.addDevice(config)
                    showAddDialog = false
                }
            }
        )
    }

    // Edit Device Dialog
    editingConfig?.let { config ->
        EditDeviceDialog(
            config = config,
            onDismiss = { editingConfig = null },
            onSave = { updatedConfig ->
                scope.launch {
                    deviceManager.updateDevice(updatedConfig)
                    editingConfig = null
                }
            }
        )
    }

    // Delete Confirmation
    showDeleteConfirm?.let { config ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Gerät löschen?") },
            text = { Text("\"${config.name}\" wirklich löschen? Diese Aktion kann nicht rückgängig gemacht werden.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            deviceManager.removeDevice(config.id)
                            if (selectedDeviceId == config.id) {
                                selectedDeviceId = null
                            }
                            showDeleteConfirm = null
                        }
                    }
                ) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

@Composable
private fun DeviceRow(
    config: DeviceConfig,
    connectionState: ConnectionState,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val typeIcon = when (config.type) {
        DeviceType.USB_SERIAL -> Icons.Default.Usb
        DeviceType.SIMULATOR -> Icons.Default.DirectionsCar
        DeviceType.PEAK_CAN -> Icons.Default.Router
    }

    val typeName = when (config.type) {
        DeviceType.USB_SERIAL -> "USB Serial"
        DeviceType.SIMULATOR -> "Simulator"
        DeviceType.PEAK_CAN -> "PEAK CAN"
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Type with icon
        Row(
            modifier = Modifier.weight(0.8f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                typeIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (connectionState == ConnectionState.CONNECTED)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                typeName,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Name
        Text(
            config.name,
            modifier = Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )

        // Status
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (connectionState) {
                    ConnectionState.CONNECTED -> Icons.Default.CheckCircle
                    ConnectionState.CONNECTING -> Icons.Default.Sync
                    ConnectionState.ERROR -> Icons.Default.Error
                    ConnectionState.DISCONNECTED -> Icons.Default.Cancel
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = statusColor
            )
            Spacer(Modifier.width(4.dp))
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor
            )
        }
    }
}

@Composable
private fun BusDetailsCard(
    config: DeviceConfig,
    connectionState: ConnectionState,
    device: CanDevice?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                "Bus Details: ${config.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(12.dp))

            // Show device-specific info
            val statusInfo = device?.getStatusInfo() ?: emptyMap()

            // Add config-specific info
            val allInfo = buildMap {
                putAll(statusInfo)
                when (config) {
                    is UsbSerialConfig -> {
                        put("CAN-Bitrate", "${config.canBitrate / 1000} kbit/s")
                        put("Serielle Baudrate", "${config.baudRate}")
                        if (config.vendorId != null) {
                            put("VID:PID", "%04X:%04X".format(config.vendorId, config.productId))
                        } else {
                            put("Modus", "Auto-Detect")
                        }
                    }
                    is PeakCanConfig -> {
                        put("Kanal", "${config.channel}")
                        put("Bitrate", "${config.bitrate / 1000} kbit/s")
                    }
                    is SimulatorConfig -> {
                        put("Modus", "Simulation")
                    }
                }
            }

            allInfo.forEach { (key, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "$key:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Connection status
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Status:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (connectionState) {
                            ConnectionState.CONNECTED -> Icons.Default.CheckCircle
                            ConnectionState.CONNECTING -> Icons.Default.Sync
                            ConnectionState.ERROR -> Icons.Default.Error
                            ConnectionState.DISCONNECTED -> Icons.Default.Cancel
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = when (connectionState) {
                            ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                            ConnectionState.CONNECTING -> Color(0xFFFFC107)
                            ConnectionState.ERROR -> Color(0xFFF44336)
                            ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when (connectionState) {
                            ConnectionState.CONNECTED -> "Verbunden"
                            ConnectionState.CONNECTING -> "Verbinde..."
                            ConnectionState.ERROR -> "Fehler"
                            ConnectionState.DISCONNECTED -> "Getrennt"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDeviceDialog(
    onDismiss: () -> Unit,
    onAdd: (DeviceConfig) -> Unit
) {
    var selectedType by remember { mutableStateOf<DeviceType?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (selectedType == null) "Verbindungstyp wählen" else "Gerät konfigurieren") },
        text = {
            Column {
                if (selectedType == null) {
                    // Type Selection
                    DeviceType.entries.forEach { type ->
                        val (icon, name, description) = when (type) {
                            DeviceType.USB_SERIAL -> Triple(
                                Icons.Default.Usb,
                                "USB Serial",
                                "USB-to-Serial CAN Adapter (z.B. Feather M4 CAN)"
                            )
                            DeviceType.SIMULATOR -> Triple(
                                Icons.Default.DirectionsCar,
                                "Simulator",
                                "Simulierte Fahrzeugdaten für Tests"
                            )
                            DeviceType.PEAK_CAN -> Triple(
                                Icons.Default.Router,
                                "PEAK CAN",
                                "PEAK PCAN-USB Adapter"
                            )
                        }

                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = { Text(description) },
                            leadingContent = { Icon(icon, null) },
                            modifier = Modifier.clickable { selectedType = type }
                        )
                        if (type != DeviceType.entries.last()) {
                            HorizontalDivider()
                        }
                    }
                } else {
                    // Type-specific config
                    when (selectedType) {
                        DeviceType.USB_SERIAL -> UsbSerialConfigForm(
                            onSave = { onAdd(it) },
                            onBack = { selectedType = null }
                        )
                        DeviceType.SIMULATOR -> {
                            // Simulator has no config, just add it
                            LaunchedEffect(Unit) {
                                onAdd(SimulatorConfig())
                            }
                        }
                        DeviceType.PEAK_CAN -> PeakCanConfigForm(
                            onSave = { onAdd(it) },
                            onBack = { selectedType = null }
                        )
                        null -> {}
                    }
                }
            }
        },
        confirmButton = {
            if (selectedType == null) {
                TextButton(onClick = onDismiss) {
                    Text("Abbrechen")
                }
            }
        },
        dismissButton = {}
    )
}

// Data class for USB device info
private data class UsbDeviceInfo(
    val name: String,
    val vendorId: Int,
    val productId: Int
)

// Helper function to scan USB devices
private fun scanUsbDevices(context: Context): List<UsbDeviceInfo> {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    return drivers.map { driver ->
        UsbDeviceInfo(
            name = driver.device.productName ?: "USB Device",
            vendorId = driver.device.vendorId,
            productId = driver.device.productId
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsbSerialConfigForm(
    initialConfig: UsbSerialConfig? = null,
    onSave: (UsbSerialConfig) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var name by remember { mutableStateOf(initialConfig?.name ?: "USB Serial") }
    var canBitrate by remember { mutableStateOf(initialConfig?.canBitrate ?: 500000) }
    var baudRate by remember { mutableStateOf(initialConfig?.baudRate ?: 2000000) }
    var autoDetect by remember { mutableStateOf(initialConfig?.vendorId == null) }
    var showAdvanced by remember { mutableStateOf(false) }
    var canBitrateExpanded by remember { mutableStateOf(false) }
    var baudRateExpanded by remember { mutableStateOf(false) }

    // USB device selection states
    var selectedVendorId by remember { mutableStateOf(initialConfig?.vendorId) }
    var selectedProductId by remember { mutableStateOf(initialConfig?.productId) }
    var availableDevices by remember { mutableStateOf<List<UsbDeviceInfo>>(emptyList()) }

    // Scan USB devices when auto-detect is disabled
    LaunchedEffect(autoDetect) {
        if (!autoDetect) {
            availableDevices = scanUsbDevices(context)
        }
    }

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

    // Standard serial baudrates for USB communication
    val standardSerialBaudRates = listOf(
        115200 to "115200",
        230400 to "230400",
        460800 to "460800",
        500000 to "500000",
        921600 to "921600",
        1000000 to "1 Mbit/s",
        2000000 to "2 Mbit/s",
        3000000 to "3 Mbit/s"
    )

    Column {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // CAN Bus Bitrate Dropdown (main setting)
        ExposedDropdownMenuBox(
            expanded = canBitrateExpanded,
            onExpandedChange = { canBitrateExpanded = it }
        ) {
            OutlinedTextField(
                value = standardCanBitrates.find { it.first == canBitrate }?.second ?: "${canBitrate / 1000} kbit/s",
                onValueChange = {},
                readOnly = true,
                label = { Text("CAN-Bus Bitrate") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = canBitrateExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = canBitrateExpanded,
                onDismissRequest = { canBitrateExpanded = false }
            ) {
                standardCanBitrates.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            canBitrate = value
                            canBitrateExpanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = autoDetect,
                onCheckedChange = { autoDetect = it }
            )
            Text("Auto-Detect (erstes USB-Gerät)")
        }

        // USB Device Selection (shown when auto-detect is disabled)
        if (!autoDetect) {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "USB-Gerät auswählen",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = { availableDevices = scanUsbDevices(context) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Aktualisieren",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    if (availableDevices.isEmpty()) {
                        Text(
                            "Keine USB-Geräte gefunden.\nSchließe ein Gerät an und tippe auf Aktualisieren.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        availableDevices.forEach { device ->
                            val isSelected = selectedVendorId == device.vendorId &&
                                    selectedProductId == device.productId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedVendorId = device.vendorId
                                        selectedProductId = device.productId
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        selectedVendorId = device.vendorId
                                        selectedProductId = device.productId
                                    }
                                )
                                Spacer(Modifier.width(4.dp))
                                Column {
                                    Text(
                                        device.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        "VID:%04X PID:%04X".format(device.vendorId, device.productId),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Advanced Settings Toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { showAdvanced = !showAdvanced }
        ) {
            Icon(
                if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Erweiterte Einstellungen",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Advanced Settings
        if (showAdvanced) {
            Spacer(Modifier.height(8.dp))

            // Serial Baudrate Dropdown
            ExposedDropdownMenuBox(
                expanded = baudRateExpanded,
                onExpandedChange = { baudRateExpanded = it }
            ) {
                OutlinedTextField(
                    value = standardSerialBaudRates.find { it.first == baudRate }?.second ?: baudRate.toString(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Serielle Baudrate (USB)") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = baudRateExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = baudRateExpanded,
                    onDismissRequest = { baudRateExpanded = false }
                ) {
                    standardSerialBaudRates.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                baudRate = value
                                baudRateExpanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }

            Text(
                "Die serielle Baudrate ist die Kommunikationsgeschwindigkeit zwischen Android und dem USB-Adapter. Standard: 2 Mbit/s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) {
                Text("Zurück")
            }
            Button(
                onClick = {
                    val config = UsbSerialConfig(
                        id = initialConfig?.id ?: DeviceConfig.generateId(),
                        name = name,
                        canBitrate = canBitrate,
                        baudRate = baudRate,
                        vendorId = if (autoDetect) null else selectedVendorId,
                        productId = if (autoDetect) null else selectedProductId
                    )
                    onSave(config)
                },
                enabled = name.isNotBlank() && (autoDetect || selectedVendorId != null)
            ) {
                Text("Speichern")
            }
        }
    }
}

// Helper function to scan for PEAK CAN devices
private fun scanPeakCanDevices(context: Context): List<UsbDeviceInfo> {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    return usbManager.deviceList.values
        .filter { device -> device.vendorId == PeakCanConfig.PEAK_VENDOR_ID }
        .map { device ->
            UsbDeviceInfo(
                name = device.productName ?: "PEAK CAN Device",
                vendorId = device.vendorId,
                productId = device.productId
            )
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeakCanConfigForm(
    initialConfig: PeakCanConfig? = null,
    onSave: (PeakCanConfig) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var name by remember { mutableStateOf(initialConfig?.name ?: "PEAK CAN") }
    var channel by remember { mutableStateOf(initialConfig?.channel?.toString() ?: "1") }
    var bitrate by remember { mutableStateOf(initialConfig?.bitrate ?: 500000) }
    var bitrateExpanded by remember { mutableStateOf(false) }

    // PEAK CAN device detection
    var detectedDevices by remember { mutableStateOf<List<UsbDeviceInfo>>(emptyList()) }

    // Scan for PEAK CAN devices on start
    LaunchedEffect(Unit) {
        detectedDevices = scanPeakCanDevices(context)
    }

    // Standard CAN bus bitrates
    val standardBitrates = listOf(
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

    Column {
        // Show detected PEAK CAN devices
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (detectedDevices.isNotEmpty())
                    Color(0xFF4CAF50).copy(alpha = 0.1f)
                else
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (detectedDevices.isNotEmpty()) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (detectedDevices.isNotEmpty()) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (detectedDevices.isNotEmpty()) {
                        detectedDevices.forEach { device ->
                            Text(
                                device.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "VID:%04X PID:%04X".format(device.vendorId, device.productId),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            "Kein PEAK CAN Gerät erkannt",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            "Schließe einen PCAN-USB Adapter an",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = { detectedDevices = scanPeakCanDevices(context) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Aktualisieren",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = channel,
            onValueChange = { channel = it.filter { c -> c.isDigit() } },
            label = { Text("Kanal") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // Bitrate Dropdown
        ExposedDropdownMenuBox(
            expanded = bitrateExpanded,
            onExpandedChange = { bitrateExpanded = it }
        ) {
            OutlinedTextField(
                value = standardBitrates.find { it.first == bitrate }?.second ?: "${bitrate / 1000} kbit/s",
                onValueChange = {},
                readOnly = true,
                label = { Text("Bitrate") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bitrateExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = bitrateExpanded,
                onDismissRequest = { bitrateExpanded = false }
            ) {
                standardBitrates.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            bitrate = value
                            bitrateExpanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) {
                Text("Zurück")
            }
            Button(
                onClick = {
                    val config = PeakCanConfig(
                        id = initialConfig?.id ?: DeviceConfig.generateId(),
                        name = name,
                        channel = channel.toIntOrNull() ?: 1,
                        bitrate = bitrate
                    )
                    onSave(config)
                },
                enabled = name.isNotBlank()
            ) {
                Text("Speichern")
            }
        }
    }
}

@Composable
private fun EditDeviceDialog(
    config: DeviceConfig,
    onDismiss: () -> Unit,
    onSave: (DeviceConfig) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gerät bearbeiten") },
        text = {
            when (config) {
                is UsbSerialConfig -> UsbSerialConfigForm(
                    initialConfig = config,
                    onSave = onSave,
                    onBack = onDismiss
                )
                is PeakCanConfig -> PeakCanConfigForm(
                    initialConfig = config,
                    onSave = onSave,
                    onBack = onDismiss
                )
                is SimulatorConfig -> {
                    Text("Simulator hat keine konfigurierbaren Einstellungen.")
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}
