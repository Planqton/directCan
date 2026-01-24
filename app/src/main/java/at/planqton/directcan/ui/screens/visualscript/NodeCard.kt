package at.planqton.directcan.ui.screens.visualscript

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.data.visualscript.*

/**
 * Visual representation of a node in the script editor
 */
@Composable
fun NodeCard(
    node: VisualNode,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onOutputPortClick: (OutputPort) -> Unit,
    onInputPortClick: () -> Unit,
    onDoubleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nodeColor = Color(node.type.color)
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "border"
    )

    Box(
        modifier = modifier
            .width(180.dp)
            .shadow(if (isSelected) 8.dp else 4.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onSelect() },
                    onDoubleTap = { onDoubleClick() }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onSelect() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    },
                    onDragEnd = { onDragEnd() }
                )
            }
    ) {
        Column {
            // Header with node type color
            Surface(
                color = nodeColor,
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = getNodeIcon(node.type),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = node.type.displayName,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                // Node-specific content
                NodeConfigPreview(node)
            }
        }

        // Input port (left side) - only for non-trigger nodes
        if (!node.type.isTrigger) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-8).dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures { onInputPortClick() }
                    }
            )
        }

        // Output ports (right side)
        if (node.type.hasMultipleOutputs) {
            // Condition node: two outputs (true/false)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // True output (green)
                OutputPortDot(
                    color = Color(0xFF4CAF50),
                    label = "Ja",
                    onClick = { onOutputPortClick(OutputPort.TRUE_OUT) }
                )
                // False output (red)
                OutputPortDot(
                    color = Color(0xFFF44336),
                    label = "Nein",
                    onClick = { onOutputPortClick(OutputPort.FALSE_OUT) }
                )
            }
        } else {
            // Single output
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 8.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(nodeColor)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures { onOutputPortClick(OutputPort.FLOW_OUT) }
                    }
            )
        }
    }
}

@Composable
private fun OutputPortDot(
    color: Color,
    label: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures { onClick() }
        }
    ) {
        Text(
            text = label,
            fontSize = 8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.offset(x = (-24).dp)
        )
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
        )
    }
}

/**
 * Preview of node configuration
 */
@Composable
private fun NodeConfigPreview(node: VisualNode) {
    val config = node.config

    when (node.type) {
        // Triggers
        NodeType.TRIGGER_ON_START -> {
            Text(
                "Automatisch beim Start",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        NodeType.TRIGGER_ON_RECEIVE -> {
            ConfigLine("ID:", "0x${config.canId.ifBlank { "???" }}")
            if (config.dataPattern.isNotBlank()) {
                ConfigLine("Filter:", config.dataPattern)
            }
        }

        NodeType.TRIGGER_ON_INTERVAL -> {
            ConfigLine("Alle:", formatTime(config.intervalMs))
        }

        NodeType.TRIGGER_ON_TIME -> {
            ConfigLine("Um:", String.format("%02d:%02d", config.timeHour, config.timeMinute))
            if (config.timeRepeat) {
                Text("Täglich", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
            }
        }

        NodeType.TRIGGER_ON_COUNT -> {
            ConfigLine("ID:", "0x${config.canId.ifBlank { "???" }}")
            ConfigLine("Nach:", "${config.count}x")
        }

        // Conditions
        NodeType.CONDITION_IF_DATA -> {
            ConfigLine("Byte[${config.byteIndex}]:", "${config.compareOperator.symbol} 0x${config.compareValue}")
        }

        NodeType.CONDITION_IF_TIME -> {
            ConfigLine("Von:", config.timeRangeStart.ifBlank { "00:00" })
            ConfigLine("Bis:", config.timeRangeEnd.ifBlank { "23:59" })
        }

        NodeType.CONDITION_IF_COUNTER -> {
            ConfigLine("${config.variableName}:", "${config.compareOperator.symbol} ${config.compareValue}")
        }

        NodeType.CONDITION_IF_RECEIVED -> {
            ConfigLine("ID:", "0x${config.canId.ifBlank { "???" }}")
            ConfigLine("Timeout:", formatTime(config.timeoutMs))
        }

        // Actions
        NodeType.ACTION_SEND -> {
            ConfigLine("ID:", "0x${config.canId.ifBlank { "???" }}")
            if (config.canData.isNotBlank()) {
                Text(
                    "[${config.canData}]",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        NodeType.ACTION_DELAY -> {
            ConfigLine("Warten:", formatTime(config.delayMs))
        }

        NodeType.ACTION_SET_VARIABLE -> {
            ConfigLine("${config.variableName} =", config.variableValue.ifBlank { "0" })
        }

        NodeType.ACTION_INCREMENT -> {
            ConfigLine("${config.variableName} +=", "${config.incrementValue}")
        }

        NodeType.ACTION_PRINT -> {
            Text(
                "\"${config.printText.take(20)}${if (config.printText.length > 20) "..." else ""}\"",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Flow
        NodeType.FLOW_WAIT_FOR -> {
            ConfigLine("ID:", "0x${config.canId.ifBlank { "???" }}")
            ConfigLine("Max:", formatTime(config.timeoutMs))
        }

        NodeType.FLOW_REPEAT -> {
            ConfigLine("Anzahl:", "${config.count}x")
        }

        NodeType.FLOW_LOOP -> {
            Text(
                "Endlos wiederholen",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        NodeType.FLOW_STOP -> {
            Text(
                "Script beenden",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun ConfigLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Returns the appropriate icon for a node type
 */
fun getNodeIcon(type: NodeType): ImageVector {
    return when (type) {
        // Triggers
        NodeType.TRIGGER_ON_START -> Icons.Default.PlayArrow
        NodeType.TRIGGER_ON_RECEIVE -> Icons.Default.Download
        NodeType.TRIGGER_ON_INTERVAL -> Icons.Default.Timer
        NodeType.TRIGGER_ON_TIME -> Icons.Default.Schedule
        NodeType.TRIGGER_ON_COUNT -> Icons.Default.Numbers

        // Conditions
        NodeType.CONDITION_IF_DATA -> Icons.Default.Code
        NodeType.CONDITION_IF_TIME -> Icons.Default.AccessTime
        NodeType.CONDITION_IF_COUNTER -> Icons.Default.Calculate
        NodeType.CONDITION_IF_RECEIVED -> Icons.Default.QuestionMark

        // Actions
        NodeType.ACTION_SEND -> Icons.Default.Send
        NodeType.ACTION_DELAY -> Icons.Default.HourglassEmpty
        NodeType.ACTION_SET_VARIABLE -> Icons.Default.Edit
        NodeType.ACTION_INCREMENT -> Icons.Default.Add
        NodeType.ACTION_PRINT -> Icons.Default.Terminal

        // Flow
        NodeType.FLOW_WAIT_FOR -> Icons.Default.Pause
        NodeType.FLOW_REPEAT -> Icons.Default.Replay
        NodeType.FLOW_LOOP -> Icons.Default.AllInclusive
        NodeType.FLOW_STOP -> Icons.Default.Stop
    }
}

/**
 * Format time for display
 */
private fun formatTime(ms: Long): String {
    return when {
        ms >= 60000 && ms % 60000 == 0L -> "${ms / 60000} min"
        ms >= 1000 && ms % 1000 == 0L -> "${ms / 1000} s"
        else -> "$ms ms"
    }
}
