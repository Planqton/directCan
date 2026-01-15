package at.planqton.directcan.ui.screens.signals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.can.CanDataRepository
import at.planqton.directcan.data.usb.UsbSerialManager

// Colors for value changes
private val ColorIncrease = Color(0xFF4CAF50)
private val ColorDecrease = Color(0xFFF44336)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalViewerScreen() {
    val canDataRepository = DirectCanApplication.instance.canDataRepository
    val dbcRepository = DirectCanApplication.instance.dbcRepository
    val usbManager = DirectCanApplication.instance.usbSerialManager

    val connectionState by usbManager.connectionState.collectAsState()
    val isLogging by canDataRepository.isLogging.collectAsState()
    val activeDbc by dbcRepository.activeDbcFile.collectAsState()
    val signalValues by canDataRepository.signalValues.collectAsState()

    // UI state
    var searchQuery by remember { mutableStateOf("") }
    var groupByMessage by remember { mutableStateOf(true) }
    var showOnlyChanged by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    // Track previous values for change detection
    val previousValues = remember { mutableStateMapOf<String, Double>() }

    // Refresh trigger for UI updates
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(100)
            refreshTrigger++
        }
    }

    // Filter and group signals
    val filteredSignals = remember(signalValues, searchQuery, showOnlyChanged, refreshTrigger) {
        signalValues.values
            .filter { signal ->
                val matchesSearch = searchQuery.isEmpty() ||
                        signal.signalName.contains(searchQuery, ignoreCase = true) ||
                        signal.messageName.contains(searchQuery, ignoreCase = true) ||
                        signal.messageIdHex.contains(searchQuery, ignoreCase = true)

                val hasChanged = if (showOnlyChanged) {
                    val prev = previousValues[signal.signalKey]
                    prev == null || prev != signal.value
                } else true

                matchesSearch && hasChanged
            }
            .sortedWith(
                if (groupByMessage) {
                    compareBy({ it.messageId }, { it.signalName })
                } else {
                    compareBy { it.signalName }
                }
            )
    }

    // Group signals by message
    val groupedSignals = remember(filteredSignals, groupByMessage) {
        if (groupByMessage) {
            filteredSignals.groupBy { it.messageId }
        } else {
            mapOf(0L to filteredSignals)
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Main content
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (groupByMessage) {
                    Text("Message / Signal", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                } else {
                    Text("Signal", Modifier.weight(0.4f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text("Message", Modifier.weight(0.3f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Text("Value", Modifier.width(150.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                Text("Unit", Modifier.width(60.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }

            HorizontalDivider()

            key(refreshTrigger) {
                when {
                    connectionState != UsbSerialManager.ConnectionState.CONNECTED -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.UsbOff, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(16.dp))
                                Text("Kein Gerät verbunden")
                            }
                        }
                    }
                    activeDbc == null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Storage, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(16.dp))
                                Text("Keine DBC-Datei geladen")
                                Spacer(Modifier.height(8.dp))
                                Text("Lade eine DBC im DBC Manager", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    signalValues.isEmpty() && isLogging -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text("Warte auf Signale...")
                            }
                        }
                    }
                    signalValues.isEmpty() && !isLogging -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(16.dp))
                                Text("Starte Capturing um Signale zu sehen")
                            }
                        }
                    }
                    else -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            if (groupByMessage) {
                                groupedSignals.forEach { (messageId, signals) ->
                                    val firstSignal = signals.first()

                                    // Message header
                                    item(key = "header_$messageId") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                firstSignal.messageIdHex,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                firstSignal.messageName,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(Modifier.weight(1f))
                                            Text(
                                                "${signals.size} Signals",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    // Signals for this message
                                    items(signals, key = { it.signalKey }) { signal ->
                                        SignalRow(
                                            signal = signal,
                                            previousValue = previousValues[signal.signalKey],
                                            showMessage = false,
                                            onValueUpdated = { previousValues[signal.signalKey] = signal.value }
                                        )
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                    }
                                }
                            } else {
                                items(filteredSignals, key = { it.signalKey }) { signal ->
                                    SignalRow(
                                        signal = signal,
                                        previousValue = previousValues[signal.signalKey],
                                        showMessage = true,
                                        onValueUpdated = { previousValues[signal.signalKey] = signal.value }
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Sidebar
        Surface(
            modifier = Modifier.width(200.dp).fillMaxHeight(),
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Statistics
                Text("Signale:", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(
                    "${signalValues.size}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Messages:", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(
                    "${signalValues.values.map { it.messageId }.distinct().size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // Control buttons
                Button(
                    onClick = {
                        if (isLogging) {
                            usbManager.stopLogging()
                            canDataRepository.setLoggingActive(false)
                        } else {
                            usbManager.startLogging()
                            canDataRepository.setLoggingActive(true)
                        }
                    },
                    enabled = connectionState == UsbSerialManager.ConnectionState.CONNECTED,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLogging) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isLogging) "Stop" else "Start")
                }

                OutlinedButton(
                    onClick = { canDataRepository.clearSignalHistory() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear")
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // Search
                Text("Suche:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Signal/Message...", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Clear, null, Modifier.size(16.dp))
                            }
                        }
                    }
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // Options
                Text("Optionen:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = groupByMessage,
                        onCheckedChange = { groupByMessage = it },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Nach Message gruppieren", style = MaterialTheme.typography.bodySmall)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = showOnlyChanged,
                        onCheckedChange = { showOnlyChanged = it },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Nur geänderte", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // DBC Info
                Text("Aktive DBC:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(
                    activeDbc?.description?.ifEmpty { "Unnamed" } ?: "Keine",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (activeDbc != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SignalRow(
    signal: CanDataRepository.SignalValue,
    previousValue: Double?,
    showMessage: Boolean,
    onValueUpdated: () -> Unit
) {
    val now = System.currentTimeMillis()
    val timeSinceUpdate = now - signal.timestamp
    val isRecent = timeSinceUpdate < 500

    // Determine change direction
    val changeDir = when {
        previousValue == null -> 0
        signal.value > previousValue -> 1
        signal.value < previousValue -> -1
        else -> 0
    }

    // Update previous value
    LaunchedEffect(signal.value) {
        onValueUpdated()
    }

    val bgColor = when {
        isRecent && changeDir > 0 -> ColorIncrease.copy(alpha = 0.3f)
        isRecent && changeDir < 0 -> ColorDecrease.copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showMessage) {
            // Signal name
            Text(
                signal.signalName,
                modifier = Modifier.weight(0.4f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            // Message info
            Column(modifier = Modifier.weight(0.3f)) {
                Text(
                    signal.messageIdHex,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp
                )
                Text(
                    signal.messageName,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Just signal name (indented)
            Spacer(Modifier.width(16.dp))
            Text(
                signal.signalName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Value
        Text(
            signal.formattedValue.substringBefore(" ").trim(),
            modifier = Modifier.width(150.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            color = when {
                isRecent && changeDir > 0 -> ColorIncrease
                isRecent && changeDir < 0 -> ColorDecrease
                else -> MaterialTheme.colorScheme.onSurface
            }
        )

        // Unit
        Text(
            signal.unit,
            modifier = Modifier.width(60.dp),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
