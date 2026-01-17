package at.planqton.directcan.ui.screens.monitor

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.DirectCanApplication
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

/**
 * Data class for a sendable CAN frame row (like SavvyCAN)
 */
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
    var sentCount: Int = 0          // How many times sent so far
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

    val connectionState by deviceManager.connectionState.collectAsState()
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

    // Send a single CAN frame
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

            usbManager.sendCanFrame(canId, dataBytes, frame.extended)
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
                    itemsIndexed(displayFrames, key = { index, frame -> if (overwriteMode) frame.id else "${frame.timestamp}_${frame.id}" }) { index, frame ->
                        val isExpanded = expandAllRows || expandedRowIds.contains(frame.id)
                        CanFrameRow(
                            index = index + 1,
                            frame = frame,
                            dbc = if (interpretFrames) activeDbc else null,
                            dbcForCopy = activeDbc,  // Always pass DBC for copy, regardless of display setting
                            expanded = isExpanded,
                            inlineMode = inlineDecodeMode,
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
                            }
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
                        // Header row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("", Modifier.width(28.dp)) // Row number
                            Text("En", Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("Bus", Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("ID", Modifier.width(70.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("Ext", Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("Rem", Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("Data", Modifier.width(180.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("Interval", Modifier.width(70.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("Count", Modifier.width(60.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("Sent", Modifier.width(50.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("", Modifier.width(80.dp)) // Actions
                        }

                        HorizontalDivider()

                        // Frame rows
                        sendFrames.forEachIndexed { index, frame ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(vertical = 2.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Row number
                                Text(
                                    "${index + 1}",
                                    Modifier.width(28.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )

                                // En checkbox
                                Checkbox(
                                    checked = frame.enabled,
                                    onCheckedChange = { enabled ->
                                        updateFrame(frame.id) { it.copy(enabled = enabled, sentCount = if (!enabled) 0 else it.sentCount) }
                                        toggleFrameSending(frame.copy(enabled = enabled), enabled)
                                    },
                                    modifier = Modifier.size(36.dp),
                                    enabled = connectionState == ConnectionState.CONNECTED && frame.canId.isNotBlank()
                                )

                                // Bus
                                OutlinedTextField(
                                    value = frame.bus.toString(),
                                    onValueChange = { v -> updateFrame(frame.id) { it.copy(bus = v.toIntOrNull() ?: 0) } },
                                    modifier = Modifier.width(40.dp).height(40.dp),
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                                )

                                Spacer(Modifier.width(2.dp))

                                // ID
                                OutlinedTextField(
                                    value = frame.canId,
                                    onValueChange = { v -> updateFrame(frame.id) { it.copy(canId = v.filter { c -> c.isLetterOrDigit() }) } },
                                    modifier = Modifier.width(70.dp).height(40.dp),
                                    singleLine = true,
                                    placeholder = { Text("7DF", fontSize = 10.sp) },
                                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                )

                                // Ext checkbox
                                Checkbox(
                                    checked = frame.extended,
                                    onCheckedChange = { v -> updateFrame(frame.id) { it.copy(extended = v) } },
                                    modifier = Modifier.size(36.dp)
                                )

                                // Rem/RTR checkbox
                                Checkbox(
                                    checked = frame.remote,
                                    onCheckedChange = { v -> updateFrame(frame.id) { it.copy(remote = v) } },
                                    modifier = Modifier.size(36.dp)
                                )

                                // Data
                                OutlinedTextField(
                                    value = frame.data,
                                    onValueChange = { v -> updateFrame(frame.id) { it.copy(data = v) } },
                                    modifier = Modifier.width(180.dp).height(40.dp),
                                    singleLine = true,
                                    placeholder = { Text("02 01 00", fontSize = 10.sp) },
                                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                )

                                Spacer(Modifier.width(2.dp))

                                // Interval (ms)
                                OutlinedTextField(
                                    value = if (frame.intervalMs == 0) "" else frame.intervalMs.toString(),
                                    onValueChange = { v -> updateFrame(frame.id) { it.copy(intervalMs = v.toIntOrNull() ?: 0) } },
                                    modifier = Modifier.width(70.dp).height(40.dp),
                                    singleLine = true,
                                    placeholder = { Text("ms", fontSize = 10.sp) },
                                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                                )

                                Spacer(Modifier.width(2.dp))

                                // Count (0 = infinite)
                                OutlinedTextField(
                                    value = if (frame.count == 0) "" else frame.count.toString(),
                                    onValueChange = { v -> updateFrame(frame.id) { it.copy(count = v.toIntOrNull() ?: 0) } },
                                    modifier = Modifier.width(60.dp).height(40.dp),
                                    singleLine = true,
                                    placeholder = { Text("∞", fontSize = 10.sp) },
                                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
                                )

                                // Sent count display
                                Text(
                                    "${frame.sentCount}",
                                    Modifier.width(50.dp),
                                    style = MaterialTheme.typography.bodySmall,
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
                                    enabled = connectionState == ConnectionState.CONNECTED && frame.canId.isNotBlank(),
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = "Einmal senden", modifier = Modifier.size(16.dp))
                                }

                                IconButton(
                                    onClick = { removeFrameRow(frame.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Löschen", modifier = Modifier.size(16.dp))
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
                                    Text("Script einhängen")
                                }
                            }
                            Text(
                                "Interval=0: manuell | Count=0: endlos",
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
                onStart = {
                    loadedScript?.let { script ->
                        txScriptExecutor.start(script)
                    }
                },
                onPause = { txScriptExecutor.pause() },
                onResume = { txScriptExecutor.resume() },
                onStop = { txScriptExecutor.stop() },
                onShowErrors = { showScriptErrorLog = true }
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
    onClick: () -> Unit = {},
    onCopy: (String) -> Unit = {}
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
