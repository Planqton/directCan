package at.planqton.directcan.ui.screens.obd2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.device.UsbSlcanDevice
import kotlinx.coroutines.launch

// DTC Types for tab selection
enum class DtcType(val title: String, val description: String) {
    STORED("Gespeichert", "Bestätigte Fehlercodes"),
    PENDING("Ausstehend", "Noch nicht bestätigte Fehler"),
    PERMANENT("Permanent", "Nicht löschbare Fehler")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DtcScreen(
    onNavigateBack: () -> Unit
) {
    val deviceManager = DirectCanApplication.instance.deviceManager
    val activeDevice by deviceManager.activeDevice.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // DTC state - separate lists for each type
    var storedDtcs by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingDtcs by remember { mutableStateOf<List<String>>(emptyList()) }
    var permanentDtcs by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedDtcType by remember { mutableStateOf(DtcType.STORED) }

    var vin by remember { mutableStateOf<String?>(null) }
    var isLoadingDtcs by remember { mutableStateOf(false) }
    var isLoadingVin by remember { mutableStateOf(false) }
    var isClearingDtcs by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // Get current DTCs based on selected type
    val dtcs = when (selectedDtcType) {
        DtcType.STORED -> storedDtcs
        DtcType.PENDING -> pendingDtcs
        DtcType.PERMANENT -> permanentDtcs
    }

    // Cast to UsbSlcanDevice if available
    val slcanDevice = activeDevice as? UsbSlcanDevice
    val capabilities by slcanDevice?.capabilities?.collectAsState() ?: remember { mutableStateOf(null) }

    // Show error in snackbar
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            errorMessage = null
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DTC Diagnose")
                        capabilities?.let { caps ->
                            Text(
                                "${caps.name} v${caps.version}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Zurueck")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (slcanDevice == null || capabilities?.supportsIsoTp != true) {
            // No device or no ISO-TP support
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        "Firmware nicht kompatibel",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        "Diese Funktion benoetigt eine Firmware mit ISO-TP Unterstuetzung. " +
                                "Bitte flashen Sie die erweiterte DirectCAN Firmware.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = onNavigateBack) {
                        Text("Zurueck")
                    }
                }
            }
        } else {
            // Main DTC screen content
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // VIN Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        "Fahrzeug-Identifikation",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    if (vin != null) {
                                        Text(
                                            vin!!,
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                    } else {
                                        Text(
                                            "VIN nicht gelesen",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            isLoadingVin = true
                                            slcanDevice.readVin()
                                                .onSuccess { vin = it }
                                                .onFailure { errorMessage = "VIN Fehler: ${it.message}" }
                                            isLoadingVin = false
                                        }
                                    },
                                    enabled = !isLoadingVin
                                ) {
                                    if (isLoadingVin) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(Icons.Default.Refresh, "VIN lesen")
                                    }
                                }
                            }
                        }
                    }
                }

                // DTC Action Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                "Fehlercodes (DTCs)",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Read ALL DTCs Button (queries all ECUs for all types)
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isLoadingDtcs = true
                                            // Read all DTC types from all ECUs
                                            slcanDevice.readDtcs()
                                                .onSuccess { storedDtcs = it }
                                                .onFailure { errorMessage = "DTC Fehler: ${it.message}" }
                                            slcanDevice.readPendingDtcs()
                                                .onSuccess { pendingDtcs = it }
                                                .onFailure { /* Pending might not be supported */ }
                                            slcanDevice.readPermanentDtcs()
                                                .onSuccess { permanentDtcs = it }
                                                .onFailure { /* Permanent might not be supported */ }
                                            isLoadingDtcs = false
                                        }
                                    },
                                    enabled = !isLoadingDtcs,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isLoadingDtcs) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Search,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text("Alle DTCs lesen")
                                }

                                // Clear DTCs Button
                                OutlinedButton(
                                    onClick = { showClearConfirm = true },
                                    enabled = !isClearingDtcs && (storedDtcs.isNotEmpty() || pendingDtcs.isNotEmpty()),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isClearingDtcs) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text("DTCs loeschen")
                                }
                            }
                        }
                    }
                }

                // DTC Type Tabs
                item {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DtcType.entries.forEachIndexed { index, dtcType ->
                            val count = when (dtcType) {
                                DtcType.STORED -> storedDtcs.size
                                DtcType.PENDING -> pendingDtcs.size
                                DtcType.PERMANENT -> permanentDtcs.size
                            }
                            SegmentedButton(
                                selected = selectedDtcType == dtcType,
                                onClick = { selectedDtcType = dtcType },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = DtcType.entries.size
                                )
                            ) {
                                Text(
                                    "${dtcType.title}${if (count > 0) " ($count)" else ""}",
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // DTC Results
                item {
                    if (dtcs.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isLoadingDtcs) "Lese Fehlercodes von allen Steuergeraeten..."
                                    else "Keine ${selectedDtcType.description.lowercase()}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    } else {
                        Text(
                            "${dtcs.size} ${selectedDtcType.description} gefunden:",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selectedDtcType == DtcType.PERMANENT)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // DTC List
                items(dtcs) { dtc ->
                    DtcCard(dtc = dtc)
                }

                // Info Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    "OBD2 Diagnose (Multi-ECU)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Liest Fehlercodes von allen Steuergeraeten (Motor, Getriebe, ABS, Airbag, etc.). " +
                                            "Unterstuetzt alle OBD2-Fahrzeuge (EU ab 2001, USA ab 1996). " +
                                            "Permanente DTCs koennen nicht geloescht werden.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Clear DTC Confirmation Dialog
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Fehlercodes loeschen?") },
            text = {
                Text(
                    "Alle gespeicherten Fehlercodes werden geloescht. " +
                            "Die Warnleuchte im Fahrzeug erlischt ebenfalls. " +
                            "Dieser Vorgang kann nicht rueckgaengig gemacht werden."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        scope.launch {
                            isClearingDtcs = true
                            slcanDevice?.clearDtcs()
                                ?.onSuccess { success ->
                                    if (success) {
                                        // Clear stored and pending DTCs (permanent cannot be cleared)
                                        storedDtcs = emptyList()
                                        pendingDtcs = emptyList()
                                        snackbarHostState.showSnackbar("Fehlercodes auf allen Steuergeraeten geloescht")
                                    } else {
                                        errorMessage = "Loeschen fehlgeschlagen"
                                    }
                                }
                                ?.onFailure { errorMessage = "Fehler: ${it.message}" }
                            isClearingDtcs = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Loeschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

@Composable
fun DtcCard(dtc: String) {
    val dtcInfo = getDtcInfo(dtc)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // DTC Code
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    dtc,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    dtcInfo.category,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
                if (dtcInfo.description.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        dtcInfo.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // System icon
            Icon(
                dtcInfo.icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.6f)
            )
        }
    }
}

data class DtcInfo(
    val category: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

fun getDtcInfo(dtc: String): DtcInfo {
    if (dtc.isEmpty()) return DtcInfo("Unbekannt", "", Icons.Default.Error)

    val prefix = dtc.first()
    val category = when (prefix) {
        'P' -> "Antriebsstrang (Powertrain)"
        'C' -> "Fahrwerk (Chassis)"
        'B' -> "Karosserie (Body)"
        'U' -> "Netzwerk/Kommunikation"
        else -> "Unbekannt"
    }

    val icon = when (prefix) {
        'P' -> Icons.Default.Settings  // Engine/Powertrain
        'C' -> Icons.Default.DirectionsCar  // Chassis
        'B' -> Icons.Default.Sensors  // Body electronics
        'U' -> Icons.Default.Cable  // Network
        else -> Icons.Default.Error
    }

    // Common DTC descriptions (subset)
    val description = when {
        dtc.startsWith("P0") -> getGenericPowertrainDescription(dtc)
        dtc.startsWith("P1") -> "Herstellerspezifischer Antriebsfehler"
        dtc.startsWith("P2") -> "Generischer Antriebsfehler (erweitert)"
        dtc.startsWith("P3") -> "Reservierter Fehlercode"
        dtc.startsWith("C0") -> "Generischer Fahrwerkfehler"
        dtc.startsWith("B0") -> "Generischer Karosseriefehler"
        dtc.startsWith("U0") -> "Generischer Netzwerkfehler"
        else -> ""
    }

    return DtcInfo(category, description, icon)
}

private fun getGenericPowertrainDescription(dtc: String): String {
    return when (dtc) {
        "P0100" -> "Luftmassenmesser - Fehlfunktion"
        "P0101" -> "Luftmassenmesser - Signal ausserhalb Bereich"
        "P0102" -> "Luftmassenmesser - Signal zu niedrig"
        "P0103" -> "Luftmassenmesser - Signal zu hoch"
        "P0110" -> "Ansauglufttemperatursensor - Fehlfunktion"
        "P0120" -> "Drosselklappensensor - Fehlfunktion"
        "P0130" -> "O2-Sensor Bank 1 Sensor 1 - Fehlfunktion"
        "P0131" -> "O2-Sensor Bank 1 Sensor 1 - Spannung zu niedrig"
        "P0132" -> "O2-Sensor Bank 1 Sensor 1 - Spannung zu hoch"
        "P0171" -> "System zu mager (Bank 1)"
        "P0172" -> "System zu fett (Bank 1)"
        "P0300" -> "Zuenaussetzer erkannt - mehrere Zylinder"
        "P0301" -> "Zuendaussetzer Zylinder 1"
        "P0302" -> "Zuendaussetzer Zylinder 2"
        "P0303" -> "Zuendaussetzer Zylinder 3"
        "P0304" -> "Zuendaussetzer Zylinder 4"
        "P0400" -> "Abgasrueckfuehrung (AGR) - Fehlfunktion"
        "P0420" -> "Katalysator unter Wirkungsgrad (Bank 1)"
        "P0430" -> "Katalysator unter Wirkungsgrad (Bank 2)"
        "P0500" -> "Geschwindigkeitssensor - Fehlfunktion"
        "P0505" -> "Leerlaufregler - Fehlfunktion"
        else -> "Generischer Antriebsfehler"
    }
}
