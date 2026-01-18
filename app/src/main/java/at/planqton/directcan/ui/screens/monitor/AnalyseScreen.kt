package at.planqton.directcan.ui.screens.monitor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.data.can.CanFrame
import at.planqton.directcan.data.can.IsoTpMessage
import at.planqton.directcan.data.can.IsoTpReassembler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyseScreen(
    canId: Long,
    frames: List<CanFrame>,
    isoTpColor: Color,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var hideIdentical by remember { mutableStateOf(false) }

    // Filter out identical frames (keep only unique data)
    val uniqueFrames = remember(frames, hideIdentical) {
        if (hideIdentical) {
            frames.distinctBy { it.dataHex }
        } else {
            frames
        }
    }

    // Reassemble ISO-TP messages (from unique frames if filter active)
    val isoTpMessages = remember(uniqueFrames) {
        IsoTpReassembler.reassemble(uniqueFrames)
    }

    // Filter out identical ISO-TP messages
    val uniqueIsoTpMessages = remember(isoTpMessages, hideIdentical) {
        if (hideIdentical) {
            isoTpMessages.distinctBy { it.payloadHex }
        } else {
            isoTpMessages
        }
    }

    // Count ISO-TP frames
    val isoTpFrameCount = remember(frames) {
        frames.count { IsoTpReassembler.isIsoTpFrame(it.data) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Analytics,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("Analyse: 0x${canId.toString(16).uppercase()}")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxSize()) {
                // Tab Row
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Frames (${uniqueFrames.size})") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("ISO-TP (${uniqueIsoTpMessages.size})") },
                        enabled = isoTpFrameCount > 0
                    )
                }

                // Filter checkbox
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = hideIdentical,
                        onCheckedChange = { hideIdentical = it },
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        "Identische ausblenden",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (hideIdentical && frames.size != uniqueFrames.size) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "(${frames.size - uniqueFrames.size} ausgeblendet)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                when (selectedTab) {
                    0 -> FramesTab(uniqueFrames, isoTpColor)
                    1 -> IsoTpTab(uniqueIsoTpMessages, isoTpColor)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Schließen")
            }
        }
    )
}

@Composable
private fun FramesTab(
    frames: List<CanFrame>,
    isoTpColor: Color
) {
    if (frames.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Keine Frames in der History",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(frames.sortedByDescending { it.timestamp }) { frame ->
                val isIsoTp = IsoTpReassembler.isIsoTpFrame(frame.data)
                FrameItem(frame, isIsoTp, isoTpColor)
            }
        }
    }
}

@Composable
private fun FrameItem(
    frame: CanFrame,
    isIsoTp: Boolean,
    isoTpColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isIsoTp) isoTpColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Timestamp (relative ms)
            Text(
                "${frame.timestamp % 1000000}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(70.dp)
            )

            // Direction
            Text(
                if (frame.direction == CanFrame.Direction.TX) "TX" else "RX",
                style = MaterialTheme.typography.labelSmall,
                color = if (frame.direction == CanFrame.Direction.TX)
                    MaterialTheme.colorScheme.tertiary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp)
            )

            Spacer(Modifier.width(8.dp))

            // Data
            Text(
                frame.dataHex,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )

            // ISO-TP type badge
            if (isIsoTp) {
                Surface(
                    color = isoTpColor.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        IsoTpReassembler.getFrameTypeName(frame.data),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun IsoTpTab(
    messages: List<IsoTpMessage>,
    isoTpColor: Color
) {
    if (messages.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Keine ISO-TP Nachrichten",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "ISO-TP Frames beginnen mit 0x0-3",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                IsoTpMessageItem(message, isoTpColor)
            }
        }
    }
}

@Composable
private fun IsoTpMessageItem(
    message: IsoTpMessage,
    isoTpColor: Color
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = isoTpColor.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${message.actualLength} bytes",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Frame count
                    Text(
                        "${message.frames.size} Frames",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    // Status badge
                    Surface(
                        color = if (message.isComplete)
                            Color(0xFF4CAF50).copy(alpha = 0.3f)
                        else
                            Color(0xFFF44336).copy(alpha = 0.3f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            if (message.isComplete) "OK" else "Unvollst.",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Payload Hex
            Text(
                "Hex:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    message.payloadHex,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.height(4.dp))

            // Payload ASCII
            Text(
                "ASCII:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    message.payloadAscii,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Expanded: Show original frames
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        "Original Frames:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            message.frames.forEach { frame ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        IsoTpReassembler.getFrameTypeName(frame.data),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = isoTpColor
                                    )
                                    Text(
                                        frame.dataHex,
                                        style = MaterialTheme.typography.bodySmall,
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
