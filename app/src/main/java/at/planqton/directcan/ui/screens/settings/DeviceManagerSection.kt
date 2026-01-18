package at.planqton.directcan.ui.screens.settings

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.planqton.directcan.data.device.*
import kotlinx.coroutines.launch

/**
 * Device Manager UI Section for Settings Screen
 */
@Composable
fun DeviceManagerSection(
    deviceManager: DeviceManager,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    val deviceConfigs by deviceManager.deviceConfigs.collectAsState()
    val devices by deviceManager.devices.collectAsState()
    val activeDevice by deviceManager.activeDevice.collectAsState()
    val connectionState by deviceManager.connectionState.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<DeviceConfig?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<DeviceConfig?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Geräte",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "Gerät hinzufügen")
            }
        }

        // Device List
        if (deviceConfigs.isEmpty()) {
            Text(
                "Keine Geräte konfiguriert",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            deviceConfigs.forEach { config ->
                val device = devices.find { it.id == config.id }
                val isActive = activeDevice?.id == config.id

                DeviceListItem(
                    config = config,
                    device = device,
                    isActive = isActive,
                    connectionState = if (isActive) connectionState else ConnectionState.DISCONNECTED,
                    onConnect = {
                        scope.launch { deviceManager.connect(config.id) }
                    },
                    onDisconnect = {
                        scope.launch { deviceManager.disconnect() }
                    },
                    onEdit = { editingConfig = config },
                    onDelete = { showDeleteConfirm = config }
                )
            }
        }

        // Detected USB Devices Info
        val usbDevices = remember { deviceManager.scanUsbDevices() }
        if (usbDevices.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Erkannte USB-Geräte: ${usbDevices.size}",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            title = { Text("Gerät löschen?") },
            text = { Text("\"${config.name}\" wirklich löschen?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            deviceManager.removeDevice(config.id)
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
private fun DeviceListItem(
    config: DeviceConfig,
    device: CanDevice?,
    isActive: Boolean,
    connectionState: ConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val icon = when (config.type) {
        DeviceType.USB_SLCAN -> Icons.Default.Usb
    }

    val statusColor = when (connectionState) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
        ConnectionState.ERROR -> MaterialTheme.colorScheme.error
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val statusText = when (connectionState) {
        ConnectionState.CONNECTED -> "Verbunden"
        ConnectionState.CONNECTING -> "Verbinde..."
        ConnectionState.ERROR -> "Fehler"
        ConnectionState.DISCONNECTED -> "Getrennt"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (isActive) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.width(12.dp))

            // Name and Status
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    config.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
            }

            // Action Buttons
            if (isActive && connectionState == ConnectionState.CONNECTED) {
                IconButton(onClick = onDisconnect) {
                    Icon(
                        Icons.Default.LinkOff,
                        "Trennen",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else if (!isActive || connectionState == ConnectionState.DISCONNECTED) {
                IconButton(onClick = onConnect) {
                    Icon(Icons.Default.Link, "Verbinden")
                }
            }

            // Edit and Delete buttons
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, "Bearbeiten")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    "Löschen",
                    tint = MaterialTheme.colorScheme.error
                )
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
        title = { Text("Gerät hinzufügen") },
        text = {
            Column {
                if (selectedType == null) {
                    // Type Selection
                    Text(
                        "Gerätetyp wählen:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    DeviceType.entries.forEach { type ->
                        val (icon, name, description) = when (type) {
                            DeviceType.USB_SLCAN -> Triple(
                                Icons.Default.Usb,
                                "USB SLCAN",
                                "USB SLCAN/LAWICEL CAN Adapter"
                            )
                        }

                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = { Text(description) },
                            leadingContent = { Icon(icon, null) },
                            modifier = Modifier.clickable { selectedType = type }
                        )
                    }
                } else {
                    // Type-specific config
                    when (selectedType) {
                        DeviceType.USB_SLCAN -> UsbSlcanConfigForm(
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

@Composable
private fun UsbSlcanConfigForm(
    initialConfig: UsbSlcanConfig? = null,
    onSave: (UsbSlcanConfig) -> Unit,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf(initialConfig?.name ?: "USB SLCAN") }
    var baudRate by remember { mutableStateOf(initialConfig?.baudRate?.toString() ?: "2000000") }
    var autoDetect by remember { mutableStateOf(initialConfig?.vendorId == null) }

    Column {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = baudRate,
            onValueChange = { baudRate = it.filter { c -> c.isDigit() } },
            label = { Text("Baudrate") },
            modifier = Modifier.fillMaxWidth()
        )

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
                    val config = UsbSlcanConfig(
                        id = initialConfig?.id ?: DeviceConfig.generateId(),
                        name = name,
                        baudRate = baudRate.toIntOrNull() ?: 2000000,
                        vendorId = if (autoDetect) null else initialConfig?.vendorId,
                        productId = if (autoDetect) null else initialConfig?.productId
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
                is UsbSlcanConfig -> UsbSlcanConfigForm(
                    initialConfig = config,
                    onSave = onSave,
                    onBack = onDismiss
                )
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}
