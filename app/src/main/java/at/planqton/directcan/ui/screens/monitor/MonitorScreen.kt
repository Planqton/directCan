package at.planqton.directcan.ui.screens.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.can.CanFrame
import at.planqton.directcan.data.dbc.DbcFile
import at.planqton.directcan.data.usb.UsbSerialManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen() {
    val usbManager = DirectCanApplication.instance.usbSerialManager
    val dbcRepository = DirectCanApplication.instance.dbcRepository
    val canDataRepository = DirectCanApplication.instance.canDataRepository

    val connectionState by usbManager.connectionState.collectAsState()
    val activeDbc by dbcRepository.activeDbcFile.collectAsState()
    val isLogging by canDataRepository.isLogging.collectAsState()

    // Use centrally collected frames from repository
    val allFrames by canDataRepository.monitorFrames.collectAsState()
    val totalFramesCaptured by canDataRepository.totalFramesCaptured.collectAsState()
    val framesPerSecond by canDataRepository.framesPerSecond.collectAsState()

    var autoScroll by remember { mutableStateOf(true) }
    var overwriteMode by remember { mutableStateOf(true) }
    var interpretFrames by remember { mutableStateOf(true) }
    var keepFiltersWhenClearing by remember { mutableStateOf(false) }
    var expandedRows by remember { mutableStateOf(false) }

    // Use shared filter state from repository
    val frameFilter by canDataRepository.frameFilter.collectAsState()
    val knownIds by canDataRepository.knownIds.collectAsState()
    var displayFrames by remember { mutableStateOf<List<CanFrame>>(emptyList()) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Sync overwrite mode to repository
    LaunchedEffect(overwriteMode) {
        canDataRepository.setOverwriteMode(overwriteMode)
    }

    // Update display frames when allFrames or filter changes
    LaunchedEffect(allFrames, frameFilter) {
        // Filter frames based on shared filter state
        val enabledIds = frameFilter.filter { it.value }.keys
        displayFrames = if (enabledIds.isEmpty() || enabledIds.size == knownIds.size) {
            allFrames
        } else {
            allFrames.filter { enabledIds.contains(it.id) }
        }

        // Auto scroll
        if (autoScroll && displayFrames.isNotEmpty() && isLogging) {
            scope.launch {
                listState.scrollToItem(maxOf(0, displayFrames.size - 1))
            }
        }
    }

    // Clear function
    fun clearFrames() {
        canDataRepository.clearMonitorFrames()
        canDataRepository.clearFiltersOnClear(!keepFiltersWhenClearing)
        displayFrames = emptyList()
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Main content area - Frame list
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            // Header row - SavvyCAN style
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("#", Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                VerticalDivider(Modifier.height(16.dp))
                Text("Timestamp", Modifier.width(100.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                VerticalDivider(Modifier.height(16.dp))
                Text("ID", Modifier.width(70.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                VerticalDivider(Modifier.height(16.dp))
                Text("Ext", Modifier.width(35.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                VerticalDivider(Modifier.height(16.dp))
                Text("RTR", Modifier.width(35.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                VerticalDivider(Modifier.height(16.dp))
                Text("Dir", Modifier.width(35.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                VerticalDivider(Modifier.height(16.dp))
                Text("Bus", Modifier.width(35.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                VerticalDivider(Modifier.height(16.dp))
                Text("Len", Modifier.width(35.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                VerticalDivider(Modifier.height(16.dp))
                Text("ASCII", Modifier.width(80.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                VerticalDivider(Modifier.height(16.dp))
                Text("Data", Modifier.width(200.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }

            HorizontalDivider()

            // Frame list
            if (connectionState != UsbSerialManager.ConnectionState.CONNECTED) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.UsbOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Kein Gerät verbunden", style = MaterialTheme.typography.titleMedium)
                        Text("Verbinde einen CAN-Adapter über USB", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(displayFrames, key = { index, frame -> if (overwriteMode) frame.id else "${frame.timestamp}_${frame.id}" }) { index, frame ->
                        CanFrameRow(
                            index = index + 1,
                            frame = frame,
                            dbc = if (interpretFrames) activeDbc else null,
                            expanded = expandedRows
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }

        // Sidebar - Control Panel
        Surface(
            modifier = Modifier
                .width(220.dp)
                .fillMaxHeight(),
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
                Text(
                    "Total Frames Captured:",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "$totalFramesCaptured",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    "Frames Per Second:",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "$framesPerSecond",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // Control Buttons
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
                    Text(if (isLogging) "Suspend Capturing" else "Start Capturing")
                }

                OutlinedButton(
                    onClick = { clearFrames() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear Frames")
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // Checkboxes
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = keepFiltersWhenClearing,
                        onCheckedChange = { keepFiltersWhenClearing = it }
                    )
                    Text("Keep Filters When Clearing", style = MaterialTheme.typography.bodySmall)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = autoScroll,
                        onCheckedChange = { autoScroll = it }
                    )
                    Text("Auto Scroll Window", style = MaterialTheme.typography.bodySmall)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = overwriteMode,
                        onCheckedChange = { overwriteMode = it }
                    )
                    Text("Overwrite Mode", style = MaterialTheme.typography.bodySmall)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = interpretFrames,
                        onCheckedChange = { interpretFrames = it },
                        enabled = activeDbc != null
                    )
                    Text("Interpret Frames", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(8.dp))

                // Expand/Collapse buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedButton(
                        onClick = { expandedRows = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text("Expand All", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { expandedRows = false },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text("Collapse All", fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // Frame Filtering
                Text(
                    "Frame Filtering:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                // Filter list
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
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
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("All")
                    }
                    OutlinedButton(
                        onClick = {
                            canDataRepository.setAllFiltersEnabled(false)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("None")
                    }
                }
            }
        }
    }
}

@Composable
fun CanFrameRow(
    index: Int,
    frame: CanFrame,
    dbc: DbcFile?,
    expanded: Boolean
) {
    val message = dbc?.findMessage(frame.id)
    val decoded = message?.let { frame.decode(it) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // # (Row number)
            Text(
                "$index",
                modifier = Modifier.width(40.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // Timestamp
            Text(
                "${frame.timestamp}",
                modifier = Modifier.width(100.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // ID
            Text(
                frame.idHex,
                modifier = Modifier.width(70.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )
            VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // Ext (Extended ID)
            Text(
                if (frame.isExtended) "1" else "0",
                modifier = Modifier.width(35.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // RTR
            Text(
                if (frame.isRtr) "1" else "0",
                modifier = Modifier.width(35.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // Dir (Direction)
            Text(
                if (frame.direction == CanFrame.Direction.TX) "Tx" else "Rx",
                modifier = Modifier.width(35.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                color = if (frame.direction == CanFrame.Direction.TX)
                    MaterialTheme.colorScheme.tertiary
                else
                    MaterialTheme.colorScheme.onSurface
            )
            VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // Bus
            Text(
                "${frame.bus}",
                modifier = Modifier.width(35.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // Len
            Text(
                "${frame.length}",
                modifier = Modifier.width(35.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // ASCII
            Text(
                frame.dataAscii,
                modifier = Modifier.width(80.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // Data (Hex)
            Text(
                frame.dataHex,
                modifier = Modifier.width(200.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Start
            )
        }

        // Expanded view - show decoded signals
        if (expanded && decoded != null && decoded.signals.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 44.dp, end = 8.dp, bottom = 4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.small
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        message?.name ?: "Unknown",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    decoded.signals.forEach { signal ->
                        Text(
                            "${signal.name}: ${signal.formattedValue}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }
    }
}
