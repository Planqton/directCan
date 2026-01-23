package at.planqton.directcan.ui.screens.monitor

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.can.CanFrame
import at.planqton.directcan.data.can.IsoTpMessage
import at.planqton.directcan.data.can.IsoTpReassembler
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingAnalyseWindow(
    canId: Long,
    frames: List<CanFrame>,
    isoTpColor: Color,
    initialOffsetX: Float,
    initialOffsetY: Float,
    onClose: () -> Unit,
    onAiChat: ((String, String) -> Unit)? = null,  // snapshotName, snapshotData
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    var offsetX by remember { mutableFloatStateOf(initialOffsetX) }
    var offsetY by remember { mutableFloatStateOf(initialOffsetY) }
    var isMinimized by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var hideIdentical by remember { mutableStateOf(true) }  // Default: on

    // Resizable window dimensions (in dp)
    var windowWidth by remember { mutableFloatStateOf(420f) }
    var windowHeight by remember { mutableFloatStateOf(400f) }

    // Filter unique frames for display and compute counts
    val (uniqueFrames, frameCounts) = if (hideIdentical) {
        val counts = frames.groupingBy { it.dataHex }.eachCount()
        val unique = frames.distinctBy { it.dataHex }
        unique to counts
    } else {
        frames to emptyMap()
    }

    // Get real-time ISO-TP messages from repository (reassembled as frames arrive)
    val canDataRepository = DirectCanApplication.instance.canDataRepository
    val allIsoTpMessages by canDataRepository.isoTpMessages.collectAsState()
    val messagesForId = allIsoTpMessages[canId] ?: emptyList()

    // Apply hideIdentical filter and compute counts
    val (uniqueIsoTpMessages, isoTpMessageCounts) = if (hideIdentical) {
        val counts = messagesForId.groupingBy { it.payloadHex }.eachCount()
        val unique = messagesForId.distinctBy { it.payloadHex }
        unique to counts
    } else {
        messagesForId to emptyMap()
    }

    val isoTpFrameCount = remember(frames) {
        frames.count { IsoTpReassembler.isIsoTpFrame(it.data) }
    }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
    ) {
        Surface(
            modifier = Modifier
                .width(if (isMinimized) 140.dp else windowWidth.dp)
                .height(if (isMinimized) 40.dp else windowHeight.dp)
                .shadow(8.dp, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Box {
            Column {
                // Title bar (draggable)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                offsetX = (offsetX + dragAmount.x).coerceIn(0f, screenWidthPx - 140f)
                                offsetY = (offsetY + dragAmount.y).coerceIn(0f, screenHeightPx - 40f)
                            }
                        },
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Analytics,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "0x${canId.toString(16).uppercase()}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (!isMinimized) {
                                Text(
                                    " (${uniqueFrames.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Row {
                            IconButton(
                                onClick = { isMinimized = !isMinimized },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    if (isMinimized) Icons.Default.OpenInFull else Icons.Default.Minimize,
                                    contentDescription = if (isMinimized) "Maximieren" else "Minimieren",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Schließen",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                // Content (when not minimized)
                AnimatedVisibility(visible = !isMinimized) {
                    Column(modifier = Modifier.padding(8.dp).weight(1f)) {
                        // Tabs
                        TabRow(
                            selectedTabIndex = selectedTab,
                            modifier = Modifier.height(36.dp)
                        ) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("Frames", style = MaterialTheme.typography.labelSmall)
                            }
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                enabled = isoTpFrameCount > 0,
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("ISO-TP", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // Filter checkbox and AI button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = hideIdentical,
                                    onCheckedChange = { hideIdentical = it },
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    "Identische ausblenden",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }

                            // AI Chat button
                            if (onAiChat != null) {
                                Surface(
                                    onClick = {
                                        val snapshotName = "0x${canId.toString(16).uppercase()} " +
                                            if (selectedTab == 0) "Frames" else "ISO-TP"
                                        val snapshotData = buildString {
                                            appendLine("CAN ID: 0x${canId.toString(16).uppercase()}")
                                            appendLine()
                                            if (selectedTab == 0) {
                                                // Frame history
                                                appendLine("=== Frame Historie (${uniqueFrames.size} Frames) ===")
                                                appendLine()
                                                uniqueFrames.sortedByDescending { it.timestamp }.forEach { frame ->
                                                    val dir = if (frame.direction == CanFrame.Direction.TX) "TX" else "RX"
                                                    val isoTpType = if (IsoTpReassembler.isIsoTpFrame(frame.data))
                                                        " [${IsoTpReassembler.getFrameTypeName(frame.data)}]" else ""
                                                    appendLine("$dir: ${frame.dataHex}$isoTpType")
                                                }
                                            } else {
                                                // ISO-TP messages
                                                appendLine("=== ISO-TP Nachrichten (${uniqueIsoTpMessages.size}) ===")
                                                appendLine()
                                                uniqueIsoTpMessages.forEachIndexed { index, message ->
                                                    appendLine("--- Message ${index + 1} (${message.actualLength} bytes, ${if (message.isComplete) "complete" else "incomplete"}) ---")
                                                    appendLine("Hex: ${message.payloadHex}")
                                                    appendLine("ASCII: ${message.payloadAscii}")
                                                    appendLine("Frames:")
                                                    message.frames.forEach { frame ->
                                                        appendLine("  ${IsoTpReassembler.getFrameTypeName(frame.data)}: ${frame.dataHex}")
                                                    }
                                                    appendLine()
                                                }
                                            }
                                        }
                                        onAiChat(snapshotName, snapshotData)
                                    },
                                    modifier = Modifier.height(24.dp),
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Psychology,
                                            contentDescription = "KI Chat",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "KI",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }
                        }

                        // Content - fills remaining space
                        Box(modifier = Modifier.weight(1f)) {
                            when (selectedTab) {
                                0 -> FramesList(uniqueFrames, isoTpColor, frameCounts)
                                1 -> IsoTpList(uniqueIsoTpMessages, isoTpColor, isoTpMessageCounts)
                            }
                        }
                    }
                }
            }

            // Resize handle (bottom-right corner) - only when not minimized
            if (!isMinimized) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                windowWidth = (windowWidth + dragAmount.x / density.density)
                                    .coerceIn(250f, configuration.screenWidthDp.toFloat() - 50f)
                                windowHeight = (windowHeight + dragAmount.y / density.density)
                                    .coerceIn(200f, configuration.screenHeightDp.toFloat() - 100f)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Größe ändern",
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                RoundedCornerShape(4.dp)
                            ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun FramesList(
    frames: List<CanFrame>,
    isoTpColor: Color,
    frameCounts: Map<String, Int> = emptyMap()
) {
    if (frames.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Keine Frames", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(frames.sortedByDescending { it.timestamp }) { frame ->
                val isIsoTp = IsoTpReassembler.isIsoTpFrame(frame.data)
                val count = frameCounts[frame.dataHex] ?: 1
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (isIsoTp) isoTpColor.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (frame.direction == CanFrame.Direction.TX) "TX" else "RX",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = if (frame.direction == CanFrame.Direction.TX)
                                MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(18.dp)
                        )
                        Text(
                            frame.dataHex,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        // Show count when hiding identical
                        if (frameCounts.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(2.dp)
                            ) {
                                Text(
                                    "×$count",
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 8.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        if (isIsoTp) {
                            Surface(
                                color = isoTpColor.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(2.dp)
                            ) {
                                Text(
                                    IsoTpReassembler.getFrameTypeName(frame.data),
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IsoTpList(
    messages: List<IsoTpMessage>,
    isoTpColor: Color,
    messageCounts: Map<String, Int> = emptyMap()
) {
    if (messages.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Keine ISO-TP Nachrichten", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Frames mit 0x0-3 als erstes Nibble",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages) { message ->
                var expanded by remember { mutableStateOf(false) }
                val count = messageCounts[message.payloadHex] ?: 1
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    color = isoTpColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "${message.actualLength} bytes",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Show count when hiding identical (count > 0 means we have counts)
                                if (messageCounts.isNotEmpty()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(2.dp)
                                    ) {
                                        Text(
                                            "×$count",
                                            modifier = Modifier.padding(horizontal = 4.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                                Surface(
                                    color = if (message.isComplete) Color(0xFF4CAF50).copy(alpha = 0.3f)
                                            else Color(0xFFF44336).copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(2.dp)
                                ) {
                                    Text(
                                        if (message.isComplete) "OK" else "?",
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }

                        // Hex
                        Text(
                            message.payloadHex,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = if (expanded) Int.MAX_VALUE else 1
                        )

                        // ASCII
                        Text(
                            message.payloadAscii,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = if (expanded) Int.MAX_VALUE else 1
                        )

                        // Expanded: show frames
                        AnimatedVisibility(visible = expanded) {
                            Column(modifier = Modifier.padding(top = 4.dp)) {
                                Text(
                                    "Frames:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                message.frames.forEach { frame ->
                                    Text(
                                        "${IsoTpReassembler.getFrameTypeName(frame.data)}: ${frame.dataHex}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
