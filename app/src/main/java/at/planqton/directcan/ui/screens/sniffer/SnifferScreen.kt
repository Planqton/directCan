package at.planqton.directcan.ui.screens.sniffer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.can.CanFrame
import at.planqton.directcan.data.device.ConnectionState
import at.planqton.directcan.data.settings.SettingsRepository
import at.planqton.directcan.data.usb.UsbSerialManager
import kotlinx.coroutines.delay

// Farben für Byte-Änderungen
val ColorIncrease = Color(0xFF4CAF50) // Grün
val ColorDecrease = Color(0xFFF44336) // Rot
val ColorNoChange = Color.Transparent
val ColorNotched = Color.Gray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnifferScreen() {
    val usbManager = DirectCanApplication.instance.usbSerialManager
    val canDataRepository = DirectCanApplication.instance.canDataRepository
    val deviceManager = DirectCanApplication.instance.deviceManager
    val settingsRepository = DirectCanApplication.instance.settingsRepository
    val connectionState by deviceManager.connectionState.collectAsState()
    val isLogging by canDataRepository.isLogging.collectAsState()

    // Multi-port support
    val connectedDeviceCount by deviceManager.connectedDeviceCount.collectAsState()
    val showPortColumn = connectedDeviceCount > 1
    val port1Color by settingsRepository.port1Color.collectAsState(initial = SettingsRepository.DEFAULT_PORT_1_COLOR)
    val port2Color by settingsRepository.port2Color.collectAsState(initial = SettingsRepository.DEFAULT_PORT_2_COLOR)
    var portFilter by remember { mutableStateOf(setOf(1, 2)) }

    // Helper function to get port color
    fun getPortColor(port: Int): Color {
        return when (port) {
            1 -> Color(port1Color.toInt())
            2 -> Color(port2Color.toInt())
            else -> Color.Gray
        }
    }

    // Use centrally collected sniffer data from repository
    val snifferFrames by canDataRepository.snifferFrames.collectAsState()
    val totalFrames by canDataRepository.totalFramesCaptured.collectAsState()

    // Use shared filter state from repository
    val frameFilter by canDataRepository.frameFilter.collectAsState()
    val knownIds by canDataRepository.knownIds.collectAsState()

    // Sniffer options
    var neverExpireIds by remember { mutableStateOf(true) }
    var muteNotchedBits by remember { mutableStateOf(false) }
    var fadeInactiveBytes by remember { mutableStateOf(true) }
    var viewBits by remember { mutableStateOf(false) }
    var highlightDurationMs by remember { mutableFloatStateOf(500f) }

    var refreshTrigger by remember { mutableIntStateOf(0) }

    // Zeitfenster für Farbänderungen (ms) - aus Slider
    val changeHighlightDuration by remember { derivedStateOf { highlightDurationMs.toLong() } }

    // Build display list from repository data, applying shared filter and port filter
    val displayList = remember(snifferFrames, frameFilter, portFilter, showPortColumn) {
        snifferFrames.values
            .filter { frameFilter[it.id] ?: true }  // Show if enabled or not in filter map
            .filter { !showPortColumn || portFilter.contains(it.port) }  // Apply port filter when multi-port
            .sortedBy { it.id }
            .toList()
    }

    // Refresh trigger for color updates
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            refreshTrigger++
        }
    }

    fun clearAll() {
        canDataRepository.clearSnifferFrames()
    }

    // Helper für Farbberechnung
    @Composable
    fun getByteColor(changeTime: Long, changeDir: Int, isNotched: Boolean, isStale: Boolean): Color {
        val now = System.currentTimeMillis()
        val timeSinceChange = now - changeTime

        return when {
            muteNotchedBits && isNotched -> ColorNotched.copy(alpha = 0.3f)
            timeSinceChange < changeHighlightDuration && changeDir > 0 -> ColorIncrease.copy(alpha = 0.8f)
            timeSinceChange < changeHighlightDuration && changeDir < 0 -> ColorDecrease.copy(alpha = 0.8f)
            else -> ColorNoChange
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
                // IO column (only show when multiple devices connected)
                if (showPortColumn) {
                    Text("IO", Modifier.width(32.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Text("ID", Modifier.width(70.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("Count", Modifier.width(70.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(if (viewBits) "Data (Bits)" else "Data (Bytes)", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("ASCII", Modifier.width(80.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }

            HorizontalDivider()

            // Refresh trigger für Recomposition
            key(refreshTrigger) {
                if (connectionState != ConnectionState.CONNECTED) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.UsbOff, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Text("Kein Gerät verbunden")
                        }
                    }
                } else if (displayList.isEmpty() && isLogging) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Warte auf CAN-Frames...")
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(displayList, key = { "${it.id}_${it.port}" }) { data ->
                            val now = System.currentTimeMillis()
                            val isStale = now - data.lastUpdate > 1000
                            val portColor = getPortColor(data.port)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // IO (Port number) - only show when multiple devices connected
                                if (showPortColumn) {
                                    Box(
                                        modifier = Modifier
                                            .width(32.dp)
                                            .background(
                                                portColor.copy(alpha = 0.2f),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(vertical = 2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "${data.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = portColor,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                                // ID
                                Row(Modifier.width(70.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "0x${data.id.toString(16).uppercase().padStart(3, '0')}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isStale) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary
                                    )
                                    IconButton(
                                        onClick = { canDataRepository.setIdFilterEnabled(data.id, false) },
                                        modifier = Modifier.size(16.dp)
                                    ) {
                                        Icon(Icons.Default.FilterAlt, null, Modifier.size(12.dp))
                                    }
                                }

                                // Count
                                Text(
                                    "${data.updateCount}",
                                    modifier = Modifier.width(70.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )

                                // Data bytes/bits
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val maxBytes = minOf(data.currentData.size, 8)

                                    if (viewBits) {
                                        // Bit view
                                        for (byteIdx in 0 until maxBytes) {
                                            val byteVal = data.currentData.getOrNull(byteIdx)?.toInt()?.and(0xFF) ?: 0
                                            val prevByteVal = data.previousData.getOrNull(byteIdx)?.toInt()?.and(0xFF) ?: 0

                                            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                                for (bitIdx in 7 downTo 0) {
                                                    val bitValue = (byteVal shr bitIdx) and 1
                                                    val prevBitValue = (prevByteVal shr bitIdx) and 1
                                                    val notchIdx = byteIdx * 8 + (7 - bitIdx)
                                                    val isNotched = notchIdx in data.notchedBits.indices && data.notchedBits[notchIdx]
                                                    val changeTime = if (byteIdx in data.byteChangeTime.indices) data.byteChangeTime[byteIdx] else 0L
                                                    val timeSinceChange = now - changeTime

                                                    val bitColor = when {
                                                        muteNotchedBits && isNotched -> Color.Gray
                                                        timeSinceChange < changeHighlightDuration && bitValue > prevBitValue -> ColorIncrease
                                                        timeSinceChange < changeHighlightDuration && bitValue < prevBitValue -> ColorDecrease
                                                        bitValue == 1 -> Color.White
                                                        else -> Color.DarkGray
                                                    }

                                                    Box(
                                                        modifier = Modifier
                                                            .size(12.dp)
                                                            .background(bitColor, MaterialTheme.shapes.extraSmall)
                                                            .border(0.5.dp, Color.Gray, MaterialTheme.shapes.extraSmall)
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.width(4.dp))
                                        }
                                    } else {
                                        // Byte view
                                        for (i in 0 until maxBytes) {
                                            val byteVal = data.currentData.getOrNull(i)?.toInt()?.and(0xFF) ?: 0
                                            val changeTime = if (i in data.byteChangeTime.indices) data.byteChangeTime[i] else 0L
                                            val changeDir = if (i in data.byteChangeDir.indices) data.byteChangeDir[i] else 0
                                            val timeSinceChange = now - changeTime
                                            val isNotched = (0 until 8).any { bit ->
                                                val idx = i * 8 + bit
                                                idx in data.notchedBits.indices && data.notchedBits[idx]
                                            }

                                            val bgColor = when {
                                                muteNotchedBits && isNotched -> ColorNotched.copy(alpha = 0.3f)
                                                timeSinceChange < changeHighlightDuration && changeDir > 0 -> ColorIncrease.copy(alpha = 0.8f)
                                                timeSinceChange < changeHighlightDuration && changeDir < 0 -> ColorDecrease.copy(alpha = 0.8f)
                                                else -> Color.Transparent
                                            }

                                            val textAlpha = when {
                                                fadeInactiveBytes && isStale -> 0.4f
                                                else -> 1f
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .background(bgColor, MaterialTheme.shapes.extraSmall)
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    byteVal.toString(16).uppercase().padStart(2, '0'),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha)
                                                )
                                            }
                                        }
                                    }
                                }

                                // ASCII
                                Text(
                                    data.currentData.take(8).map { b ->
                                        val c = b.toInt() and 0xFF
                                        if (c in 32..126) c.toChar() else '.'
                                    }.joinToString(""),
                                    modifier = Modifier.width(80.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
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
                Text("Active IDs:", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text("${displayList.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

                Text("Total Frames:", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text("$totalFrames", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

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
                    enabled = connectionState == ConnectionState.CONNECTED,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLogging) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isLogging) "Stop" else "Start")
                }

                OutlinedButton(onClick = { clearAll() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear")
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Text("Notching", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { canDataRepository.notchSnifferChanges() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                        Text("Notch", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { canDataRepository.unNotchSnifferChanges() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                        Text("Un-Notch", fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Text("Options", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

                // Highlight Duration Slider
                Text("Highlight: ${highlightDurationMs.toInt()}ms", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = highlightDurationMs,
                    onValueChange = { highlightDurationMs = it },
                    valueRange = 100f..2000f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = neverExpireIds, onCheckedChange = { neverExpireIds = it }, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Never Expire IDs", style = MaterialTheme.typography.bodySmall)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = muteNotchedBits, onCheckedChange = { muteNotchedBits = it }, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Mute Notched Bits", style = MaterialTheme.typography.bodySmall)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = fadeInactiveBytes, onCheckedChange = { fadeInactiveBytes = it }, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Fade Inactive Bytes", style = MaterialTheme.typography.bodySmall)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = viewBits, onCheckedChange = { viewBits = it }, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("View Bits", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // Port Filtering (only show when multiple devices connected)
                if (showPortColumn) {
                    Text(
                        "Port Filter:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = 1 in portFilter,
                            onClick = {
                                portFilter = if (1 in portFilter) {
                                    if (portFilter.size > 1) portFilter - 1 else portFilter
                                } else {
                                    portFilter + 1
                                }
                            },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(getPortColor(1), RoundedCornerShape(2.dp))
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("P1", fontSize = 10.sp)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = 2 in portFilter,
                            onClick = {
                                portFilter = if (2 in portFilter) {
                                    if (portFilter.size > 1) portFilter - 2 else portFilter
                                } else {
                                    portFilter + 2
                                }
                            },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(getPortColor(2), RoundedCornerShape(2.dp))
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("P2", fontSize = 10.sp)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                }

                // Frame Filtering - same UI as MonitorScreen
                Text(
                    "Frame Filtering:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                // Filter list
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
                    shape = MaterialTheme.shapes.small
                ) {
                    LazyColumn(
                        modifier = Modifier.padding(4.dp)
                    ) {
                        val sortedIds = knownIds.sorted()
                        items(sortedIds) { id ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = frameFilter[id] ?: true,
                                    onCheckedChange = { checked ->
                                        canDataRepository.setIdFilterEnabled(id, checked)
                                    },
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "0x${id.toString(16).uppercase().padStart(3, '0')}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                // All / None buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            canDataRepository.setAllFiltersEnabled(true)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text("All", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            canDataRepository.setAllFiltersEnabled(false)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text("None", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
