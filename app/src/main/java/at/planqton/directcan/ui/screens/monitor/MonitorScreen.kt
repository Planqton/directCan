package at.planqton.directcan.ui.screens.monitor

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.settings.SettingsRepository
import at.planqton.directcan.data.can.CanFrame
import at.planqton.directcan.data.dbc.DbcFile
import at.planqton.directcan.data.device.ConnectionState
import at.planqton.directcan.data.txscript.TxScript
import at.planqton.directcan.data.txscript.TxScriptFileInfo
import at.planqton.directcan.data.usb.UsbSerialManager
import at.planqton.directcan.ui.screens.txscript.ScriptErrorLogDialog
import at.planqton.directcan.ui.screens.txscript.components.ScriptControlPanel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data class for a sendable CAN frame row (like SavvyCAN)
 */
@Serializable
data class SendableFrame(
    val id: Int,                    // Row ID (not CAN ID)
    var enabled: Boolean = false,   // En checkbox
    var bus: Int = 0,               // Bus number
    var canId: String = "",         // CAN ID (hex string)
    var extended: Boolean = false,  // Extended ID
    var remote: Boolean = false,    // Remote/RTR frame
    var data: String = "",          // Data bytes (hex string)
    var intervalMs: Int = 0,        // Interval in ms (0 = manual only)
    var count: Int = 0,             // Send count (0 = infinite when enabled)
    var sentCount: Int = 0,         // How many times sent so far
    var targetPorts: Set<Int> = setOf(1, 2)  // Target ports for sending (default: both)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen() {
    val usbManager = DirectCanApplication.instance.usbSerialManager
    val dbcRepository = DirectCanApplication.instance.dbcRepository
    val canDataRepository = DirectCanApplication.instance.canDataRepository
    val txScriptRepository = DirectCanApplication.instance.txScriptRepository
    val txScriptExecutor = DirectCanApplication.instance.txScriptExecutor
    val deviceManager = DirectCanApplication.instance.deviceManager
    val settingsRepository = DirectCanApplication.instance.settingsRepository

    val connectionState by deviceManager.connectionState.collectAsState()
    val activeDbc by dbcRepository.activeDbcFile.collectAsState()
    val isLogging by canDataRepository.isLogging.collectAsState()

    // Multi-port support
    val connectedDeviceCount by deviceManager.connectedDeviceCount.collectAsState()
    val showPortColumn = connectedDeviceCount > 1
    val port1Color by settingsRepository.port1Color.collectAsState(initial = SettingsRepository.DEFAULT_PORT_1_COLOR)
    val port2Color by settingsRepository.port2Color.collectAsState(initial = SettingsRepository.DEFAULT_PORT_2_COLOR)
    var portFilter by remember { mutableStateOf(setOf(1, 2)) }  // Both ports enabled by default

    // Use centrally collected frames from repository
    val allFrames by canDataRepository.monitorFrames.collectAsState()
    val totalFramesCaptured by canDataRepository.totalFramesCaptured.collectAsState()
    val framesPerSecond by canDataRepository.framesPerSecond.collectAsState()

    var autoScroll by remember { mutableStateOf(true) }
    var overwriteMode by remember { mutableStateOf(true) }
    var loopbackMode by remember { mutableStateOf(false) }
    var interpretFrames by remember { mutableStateOf(true) }
    var keepFiltersWhenClearing by remember { mutableStateOf(false) }
    var expandAllRows by remember { mutableStateOf(false) }
    var inlineDecodeMode by remember { mutableStateOf(true) }  // true = inline, false = expanded

    // CAN Send Panel state
    var showSendPanel by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // TX Script state
    var showScriptPanel by remember { mutableStateOf(false) }
    var showScriptErrorLog by remember { mutableStateOf(false) }
    val availableScripts by txScriptRepository.scripts.collectAsState()
    val scriptExecutionState by txScriptExecutor.state.collectAsState()
    var selectedScript by remember { mutableStateOf<TxScriptFileInfo?>(null) }
    var loadedScript by remember { mutableStateOf<TxScript?>(null) }

    // Sendable frame rows (like SavvyCAN)
    var sendFrames by remember {
        mutableStateOf(listOf(
            SendableFrame(id = 1)  // Start with one empty row
        ))
    }

    // Active sending jobs
    var activeSendJobs by remember { mutableStateOf(mapOf<Int, kotlinx.coroutines.Job>()) }

    // JSON for export/import
    val json = remember { Json { prettyPrint = true; ignoreUnknownKeys = true } }

    // Export launcher
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                val jsonString = json.encodeToString(sendFrames)
                context.contentResolver.openOutputStream(it)?.use { stream ->
                    stream.write(jsonString.toByteArray())
                }
                Toast.makeText(context, "Exportiert: ${sendFrames.size} Frames", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export fehlgeschlagen: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Import launcher
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val jsonString = context.contentResolver.openInputStream(it)?.bufferedReader()?.readText() ?: ""
                val importedFrames = json.decodeFromString<List<SendableFrame>>(jsonString)

                // Merge: imported frames overwrite existing by canId
                val existingByCanId = sendFrames.associateBy { frame -> frame.canId }
                val importedByCanId = importedFrames.associateBy { frame -> frame.canId }
                val merged = (existingByCanId + importedByCanId).values.toList()

                // Reassign IDs to avoid conflicts
                var nextId = 1
                sendFrames = merged.map { frame -> frame.copy(id = nextId++) }

                Toast.makeText(context, "Importiert: ${importedFrames.size} Frames", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Import fehlgeschlagen: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Track individually expanded rows by frame ID
    var expandedRowIds by remember { mutableStateOf(setOf<Long>()) }

    // Use shared filter state from repository
    val frameFilter by canDataRepository.frameFilter.collectAsState()
    val knownIds by canDataRepository.knownIds.collectAsState()
    var displayFrames by remember { mutableStateOf<List<CanFrame>>(emptyList()) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    // Sync overwrite mode to repository
    LaunchedEffect(overwriteMode) {
        canDataRepository.setOverwriteMode(overwriteMode)
    }

    // Helper function to get port color
    fun getPortColor(port: Int): Color {
        return when (port) {
            1 -> Color(port1Color.toInt())
            2 -> Color(port2Color.toInt())
            else -> Color.Gray
        }
    }

    // Update display frames when allFrames or filter changes
    LaunchedEffect(allFrames, frameFilter, portFilter, showPortColumn) {
        // Filter frames based on shared filter state
        val enabledIds = frameFilter.filter { it.value }.keys
        var filteredFrames = if (enabledIds.isEmpty() || enabledIds.size == knownIds.size) {
            allFrames
        } else {
            allFrames.filter { enabledIds.contains(it.id) }
        }

        // Apply port filter when multiple devices connected
        if (showPortColumn && portFilter.size < 2) {
            filteredFrames = filteredFrames.filter { portFilter.contains(it.port) }
        }

        displayFrames = filteredFrames

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

    // Send a single CAN frame to target ports
    fun sendSingleFrame(frame: SendableFrame): Boolean {
        try {
            val canIdClean = frame.canId.trim().removePrefix("0x").removePrefix("0X")
            if (canIdClean.isEmpty()) return false
            val canId = canIdClean.toLong(16)

            val dataStr = frame.data.trim()
            val dataBytes = if (dataStr.isEmpty()) {
                byteArrayOf()
            } else {
                dataStr.replace(",", " ")
                    .split(" ")
                    .filter { it.isNotBlank() }
                    .map { it.trim().removePrefix("0x").removePrefix("0X").toInt(16).toByte() }
                    .toByteArray()
            }

            if (dataBytes.size > 8) return false

            // Determine which ports to send to
            val activeDeviceKeys = deviceManager.activeDevices.value.keys
            val portsToSend = if (connectedDeviceCount > 1) {
                frame.targetPorts.filter { activeDeviceKeys.contains(it) }.toSet()
            } else if (activeDeviceKeys.isNotEmpty()) {
                setOf(activeDeviceKeys.first())  // Use actual connected port
            } else {
                return false  // No device connected
            }

            if (portsToSend.isEmpty()) return false

            // Send to device(s) - always use deviceManager
            scope.launch {
                val results = deviceManager.sendCanFrameToPorts(portsToSend, canId, dataBytes, frame.extended)
                val sendSuccess = results.values.any { it }

                // Add TX frame to monitor for each port we sent to (since devices don't echo)
                if (sendSuccess) {
                    var timestampOffset = 0L
                    portsToSend.forEach { port ->
                        val txFrame = CanFrame(
                            timestamp = System.currentTimeMillis() * 1000 + timestampOffset,
                            id = canId,
                            data = dataBytes,
                            isExtended = frame.extended,
                            isRtr = frame.remote,
                            direction = CanFrame.Direction.TX,
                            port = port
                        )
                        canDataRepository.processFrame(txFrame)
                        timestampOffset += 1  // Ensure unique timestamps for overwrite mode
                    }
                }
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    // Start/stop periodic sending for a frame
    fun toggleFrameSending(frame: SendableFrame, enabled: Boolean) {
        val existingJob = activeSendJobs[frame.id]
        existingJob?.cancel()

        if (enabled && frame.intervalMs > 0) {
            val job = scope.launch {
                var sent = 0
                while (isActive) {
                    if (sendSingleFrame(frame)) {
                        sent++
                        sendFrames = sendFrames.map {
                            if (it.id == frame.id) it.copy(sentCount = sent) else it
                        }
                        // Stop if count reached (0 = infinite)
                        if (frame.count > 0 && sent >= frame.count) {
                            sendFrames = sendFrames.map {
                                if (it.id == frame.id) it.copy(enabled = false) else it
                            }
                            break
                        }
                    }
                    delay(frame.intervalMs.toLong())
                }
            }
            activeSendJobs = activeSendJobs + (frame.id to job)
        } else {
            activeSendJobs = activeSendJobs - frame.id
        }
    }

    // Add a new frame row
    fun addFrameRow() {
        val newId = (sendFrames.maxOfOrNull { it.id } ?: 0) + 1
        sendFrames = sendFrames + SendableFrame(id = newId)
    }

    // Add a frame from received CanFrame to send list
    fun addFrameFromCanFrame(canFrame: CanFrame) {
        val newId = (sendFrames.maxOfOrNull { it.id } ?: 0) + 1
        sendFrames = sendFrames + SendableFrame(
            id = newId,
            canId = canFrame.idHex,
            extended = canFrame.isExtended,
            remote = canFrame.isRtr,
            data = canFrame.dataHex,
            targetPorts = setOf(canFrame.port)
        )
        // Auto-expand send panel when adding frame
        showSendPanel = true
    }

    // Remove a frame row
    fun removeFrameRow(frameId: Int) {
        activeSendJobs[frameId]?.cancel()
        activeSendJobs = activeSendJobs - frameId
        sendFrames = sendFrames.filter { it.id != frameId }
    }

    // Update a frame row
    fun updateFrame(frameId: Int, update: (SendableFrame) -> SendableFrame) {
        sendFrames = sendFrames.map { if (it.id == frameId) update(it) else it }
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
                // IO column (only show when multiple devices connected)
                if (showPortColumn) {
                    Text("IO", Modifier.width(32.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    VerticalDivider(Modifier.height(16.dp))
                }
                Text("#", Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                VerticalDivider(Modifier.height(16.dp))
                Text("Time", Modifier.width(70.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
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
                if (inlineDecodeMode && interpretFrames) {
                    VerticalDivider(Modifier.height(16.dp))
                    Text("Decoded", Modifier.width(300.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Start)
                }
            }

            HorizontalDivider()

            // Frame list
            if (connectionState != ConnectionState.CONNECTED) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
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
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    itemsIndexed(displayFrames, key = { index, frame -> if (overwriteMode) "${frame.id}_${frame.port}" else "${frame.timestamp}_${frame.id}_${frame.port}" }) { index, frame ->
                        val isExpanded = expandAllRows || expandedRowIds.contains(frame.id)
                        CanFrameRow(
                            index = index + 1,
                            frame = frame,
                            dbc = if (interpretFrames) activeDbc else null,
                            dbcForCopy = activeDbc,  // Always pass DBC for copy, regardless of display setting
                            expanded = isExpanded,
                            inlineMode = inlineDecodeMode,
                            showPortColumn = showPortColumn,
                            portColor = getPortColor(frame.port),
                            onClick = {
                                expandedRowIds = if (expandedRowIds.contains(frame.id)) {
                                    expandedRowIds - frame.id
                                } else {
                                    expandedRowIds + frame.id
                                }
                            },
                            onCopy = { text ->
                                clipboardManager.setText(AnnotatedString(text))
                                Toast.makeText(context, "Kopiert!", Toast.LENGTH_SHORT).show()
                            },
                            onAddToSend = { addFrameFromCanFrame(frame) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }

            // Toggle button for send panel (at bottom, above navbar)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "CAN Senden",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                )
                IconButton(
                    onClick = { showSendPanel = !showSendPanel },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (showSendPanel) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = "CAN Senden ${if (showSendPanel) "einklappen" else "aufklappen"}",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // CAN Send Panel (collapsible, expands upward from bottom)
            AnimatedVisibility(visible = showSendPanel) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 2.dp,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        // Header row - no scroll, use weight
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("En", Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            if (showPortColumn) {
                                Text("Port", Modifier.width(50.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            }
                            Text("ID", Modifier.width(80.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("X", Modifier.width(32.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("R", Modifier.width(32.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("Data (hex, space-separated)", Modifier.weight(1f).padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text("ms", Modifier.width(60.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("Cnt", Modifier.width(50.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("Tx", Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("", Modifier.width(70.dp)) // Actions
                        }

                        HorizontalDivider()

                        // Frame rows - fill width
                        sendFrames.forEachIndexed { index, frame ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // En checkbox
                                Checkbox(
                                    checked = frame.enabled,
                                    onCheckedChange = { enabled ->
                                        updateFrame(frame.id) { it.copy(enabled = enabled, sentCount = if (!enabled) 0 else it.sentCount) }
                                        toggleFrameSending(frame.copy(enabled = enabled), enabled)
                                    },
                                    modifier = Modifier.size(40.dp),
                                    enabled = connectedDeviceCount > 0 && frame.canId.isNotBlank()
                                )

                                // Port selection (only when multi-port)
                                if (showPortColumn) {
                                    Row(
                                        modifier = Modifier.width(50.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Port 1
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .background(
                                                    if (1 in frame.targetPorts) getPortColor(1).copy(alpha = 0.3f) else Color.Transparent,
                                                    RoundedCornerShape(3.dp)
                                                )
                                                .border(1.dp, getPortColor(1), RoundedCornerShape(3.dp))
                                                .clickable {
                                                    val newPorts = if (1 in frame.targetPorts) {
                                                        if (frame.targetPorts.size > 1) frame.targetPorts - 1 else frame.targetPorts
                                                    } else {
                                                        frame.targetPorts + 1
                                                    }
                                                    updateFrame(frame.id) { it.copy(targetPorts = newPorts) }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (1 in frame.targetPorts) {
                                                Text("1", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = getPortColor(1))
                                            }
                                        }
                                        Spacer(Modifier.width(2.dp))
                                        // Port 2
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .background(
                                                    if (2 in frame.targetPorts) getPortColor(2).copy(alpha = 0.3f) else Color.Transparent,
                                                    RoundedCornerShape(3.dp)
                                                )
                                                .border(1.dp, getPortColor(2), RoundedCornerShape(3.dp))
                                                .clickable {
                                                    val newPorts = if (2 in frame.targetPorts) {
                                                        if (frame.targetPorts.size > 1) frame.targetPorts - 2 else frame.targetPorts
                                                    } else {
                                                        frame.targetPorts + 2
                                                    }
                                                    updateFrame(frame.id) { it.copy(targetPorts = newPorts) }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (2 in frame.targetPorts) {
                                                Text("2", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = getPortColor(2))
                                            }
                                        }
                                    }
                                }

                                // ID
                                OutlinedTextField(
                                    value = frame.canId,
                                    onValueChange = { v -> updateFrame(frame.id) { it.copy(canId = v.filter { c -> c.isLetterOrDigit() }) } },
                                    modifier = Modifier.width(80.dp),
                                    singleLine = true,
                                    placeholder = { Text("7DF", fontSize = 13.sp) },
                                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                    )
                                )

                                // Ext checkbox (X = Extended ID)
                                Checkbox(
                                    checked = frame.extended,
                                    onCheckedChange = { v -> updateFrame(frame.id) { it.copy(extended = v) } },
                                    modifier = Modifier.size(32.dp)
                                )

                                // Rem/RTR checkbox (R = Remote)
                                Checkbox(
                                    checked = frame.remote,
                                    onCheckedChange = { v -> updateFrame(frame.id) { it.copy(remote = v) } },
                                    modifier = Modifier.size(32.dp)
                                )

                                // Data - weight to fill remaining space
                                OutlinedTextField(
                                    value = frame.data,
                                    onValueChange = { v -> updateFrame(frame.id) { it.copy(data = v) } },
                                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                                    singleLine = true,
                                    placeholder = { Text("02 01 00 00 00 00 00 00", fontSize = 13.sp) },
                                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                    )
                                )

                                // Interval (ms)
                                OutlinedTextField(
                                    value = if (frame.intervalMs == 0) "" else frame.intervalMs.toString(),
                                    onValueChange = { v -> updateFrame(frame.id) { it.copy(intervalMs = v.toIntOrNull() ?: 0) } },
                                    modifier = Modifier.width(60.dp),
                                    singleLine = true,
                                    placeholder = { Text("0", fontSize = 13.sp) },
                                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                    )
                                )

                                // Count
                                OutlinedTextField(
                                    value = if (frame.count == 0) "" else frame.count.toString(),
                                    onValueChange = { v -> updateFrame(frame.id) { it.copy(count = v.toIntOrNull() ?: 0) } },
                                    modifier = Modifier.width(50.dp),
                                    singleLine = true,
                                    placeholder = { Text("∞", fontSize = 13.sp) },
                                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                    )
                                )

                                // Sent count display
                                Text(
                                    "${frame.sentCount}",
                                    Modifier.width(40.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Center,
                                    color = if (frame.sentCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // Send once button & Delete button
                                IconButton(
                                    onClick = {
                                        if (sendSingleFrame(frame)) {
                                            updateFrame(frame.id) { it.copy(sentCount = it.sentCount + 1) }
                                            Toast.makeText(context, "Gesendet", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Fehler", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    enabled = connectedDeviceCount > 0 && frame.canId.isNotBlank(),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = "Einmal senden", modifier = Modifier.size(18.dp))
                                }

                                IconButton(
                                    onClick = { removeFrameRow(frame.id) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Löschen", modifier = Modifier.size(18.dp))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }

                        // Add row button
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { addFrameRow() }) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Zeile hinzufügen")
                                }
                                TextButton(onClick = { showScriptPanel = !showScriptPanel }) {
                                    Icon(
                                        if (showScriptPanel) Icons.Default.ExpandLess else Icons.Default.Code,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Script")
                                }
                                // Import/Export buttons
                                TextButton(
                                    onClick = {
                                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                        exportLauncher.launch("can_send_$timestamp.json")
                                    },
                                    enabled = sendFrames.any { it.canId.isNotBlank() }
                                ) {
                                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Export")
                                }
                                TextButton(
                                    onClick = { importLauncher.launch(arrayOf("application/json")) }
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Import")
                                }
                                // DEBUG: Inject test frame directly into repository
                                TextButton(
                                    onClick = {
                                        val testFrame = CanFrame(
                                            timestamp = System.currentTimeMillis() * 1000,
                                            id = 0x7DF,
                                            data = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
                                            direction = CanFrame.Direction.TX,
                                            port = 1
                                        )
                                        canDataRepository.processFrame(testFrame)
                                        Toast.makeText(context, "Debug Frame injected!", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("DEBUG")
                                }
                            }
                            Text(
                                "ms=0: manuell | Cnt=0: endlos",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // TX Script Control Panel
            ScriptControlPanel(
                isExpanded = showScriptPanel,
                availableScripts = availableScripts,
                selectedScript = selectedScript,
                executionState = scriptExecutionState,
                onSelectScript = { script ->
                    selectedScript = script
                    if (script != null) {
                        scope.launch {
                            txScriptRepository.loadScript(script).fold(
                                onSuccess = { loadedScript = it },
                                onFailure = {
                                    Toast.makeText(context, "Fehler beim Laden des Scripts", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    } else {
                        loadedScript = null
                    }
                },
                onStart = { ports ->
                    loadedScript?.let { script ->
                        txScriptExecutor.start(script, ports)
                    }
                },
                onPause = { txScriptExecutor.pause() },
                onResume = { txScriptExecutor.resume() },
                onStop = { txScriptExecutor.stop() },
                onShowErrors = { showScriptErrorLog = true },
                showPortSelection = showPortColumn,
                port1Color = getPortColor(1),
                port2Color = getPortColor(2)
            )
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
                    enabled = connectionState == ConnectionState.CONNECTED,
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = loopbackMode,
                        onCheckedChange = { enabled ->
                            loopbackMode = enabled
                            scope.launch {
                                deviceManager.send(if (enabled) "K1\r" else "K0\r")
                            }
                        },
                        enabled = connectionState == ConnectionState.CONNECTED
                    )
                    Text("Loopback (Test ohne Bus)", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(8.dp))

                // Decode Mode Toggle: Inline vs Expanded
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = inlineDecodeMode,
                        onClick = { inlineDecodeMode = true },
                        label = { Text("Inline", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = !inlineDecodeMode,
                        onClick = {
                            inlineDecodeMode = false
                            expandAllRows = true
                        },
                        label = { Text("Expanded", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                            .size(10.dp)
                                            .background(getPortColor(1), RoundedCornerShape(2.dp))
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Port 1", fontSize = 11.sp)
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
                                            .size(10.dp)
                                            .background(getPortColor(2), RoundedCornerShape(2.dp))
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Port 2", fontSize = 11.sp)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                }

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
                            val dbcMessage = activeDbc?.findMessage(id)
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
                                Column {
                                    Text(
                                        "0x${id.toString(16).uppercase().padStart(3, '0')}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (dbcMessage != null) {
                                        Text(
                                            dbcMessage.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 9.sp
                                        )
                                    }
                                }
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

    // Script Error Log Dialog
    if (showScriptErrorLog) {
        ScriptErrorLogDialog(
            executionState = scriptExecutionState,
            onDismiss = { showScriptErrorLog = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CanFrameRow(
    index: Int,
    frame: CanFrame,
    dbc: DbcFile?,
    dbcForCopy: DbcFile? = dbc,
    expanded: Boolean,
    inlineMode: Boolean = true,
    showPortColumn: Boolean = false,
    portColor: Color = Color.Gray,
    onClick: () -> Unit = {},
    onCopy: (String) -> Unit = {},
    onAddToSend: (() -> Unit)? = null
) {
    val message = dbc?.findMessage(frame.id)
    val decoded = message?.let { frame.decode(it) }

    // For copy: always decode if DBC available
    val messageForCopy = dbcForCopy?.findMessage(frame.id)
    val decodedForCopy = messageForCopy?.let { frame.decode(it) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    // Copy frame to clipboard - always include signals if DBC available
                    val copyText = buildString {
                        append("${frame.idHex} [${frame.length}] ${frame.dataHex}")
                        if (decodedForCopy != null && decodedForCopy.signals.isNotEmpty()) {
                            append(" | ")
                            append(decodedForCopy.signals.joinToString(", ") { "${it.name}=${it.formattedValue}" })
                        }
                    }
                    onCopy(copyText)
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
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
                        "${frame.port}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = portColor,
                        textAlign = TextAlign.Center
                    )
                }
                VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            // # (Row number)
            Text(
                "$index",
                modifier = Modifier.width(40.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // Timestamp - formatted as seconds.milliseconds
            val timeSeconds = (frame.timestamp / 1000000.0) % 1000  // Last 3 digits of seconds + millis
            Text(
                String.format("%.3f", timeSeconds),
                modifier = Modifier.width(70.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                maxLines = 1
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
            // Decoded Signals (inline) - only show column when inlineMode is true
            if (inlineMode) {
                VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)

                if (decoded != null && decoded.signals.isNotEmpty()) {
                    Text(
                        decoded.signals.joinToString(" | ") { "${it.name}=${it.formattedValue}" },
                        modifier = Modifier.width(300.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1
                    )
                } else if (dbc != null) {
                    Spacer(Modifier.width(300.dp))
                }
            }

            // Add to Send button
            if (onAddToSend != null) {
                IconButton(
                    onClick = onAddToSend,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Zu Senden hinzufügen",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
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
