package at.planqton.directcan.ui.screens.gemini

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.data.gemini.CanCommand
import at.planqton.directcan.data.gemini.CanCommandBlock

/**
 * Displays a pending CAN command block with Execute/Cancel actions.
 */
@Composable
fun CanExecutionCard(
    commandBlock: CanCommandBlock,
    isExecuting: Boolean,
    executionLog: List<String>,
    onExecute: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isExecuting) Icons.Default.Sync else Icons.Default.DirectionsCar,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isExecuting) "CAN-Aktion wird ausgeführt..." else "CAN-Aktion bereit",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            // Narration
            if (commandBlock.narration.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "\"${commandBlock.narration}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Medium
                )
            }

            // Expanded content
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))

                    // Command list
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            commandBlock.commands.forEachIndexed { index, command ->
                                CommandItem(
                                    index = index + 1,
                                    command = command
                                )
                                if (index < commandBlock.commands.size - 1) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        }
                    }

                    // Execution log (when executing)
                    if (isExecuting && executionLog.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                executionLog.forEach { log ->
                                    Text(
                                        log,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = when {
                                            log.startsWith("✓") -> MaterialTheme.colorScheme.primary
                                            log.startsWith("✗") || log.startsWith("❌") -> MaterialTheme.colorScheme.error
                                            log.startsWith("⏱️") -> MaterialTheme.colorScheme.secondary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Buttons
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (!isExecuting) {
                            TextButton(onClick = onCancel) {
                                Text("Abbrechen")
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Button(
                            onClick = onExecute,
                            enabled = !isExecuting,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            if (isExecuting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (isExecuting) "Wird ausgeführt..." else "Ausführen")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandItem(
    index: Int,
    command: CanCommand
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            "$index.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp)
        )

        Column {
            Text(
                getCommandTypeName(command),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                getCommandDescription(command),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun getCommandTypeName(command: CanCommand): String = when (command) {
    is CanCommand.SendFrame -> "CAN Frame senden"
    is CanCommand.SendIsoTp -> "ISO-TP Request"
    is CanCommand.ReadDtcs -> "Fehlercodes lesen"
    is CanCommand.ClearDtcs -> "⚠️ Fehlercodes löschen"
    is CanCommand.ReadVin -> "VIN lesen"
    is CanCommand.UdsRequest -> "UDS Request"
    is CanCommand.Obd2Pid -> "OBD2 PID Abfrage"
    is CanCommand.ScanBus -> "Bus scannen"
    is CanCommand.ObserveIds -> "IDs beobachten"
    is CanCommand.Delay -> "Warten"
    is CanCommand.PeriodicFrame -> "Periodisches Senden"
}

private fun getCommandDescription(command: CanCommand): String = when (command) {
    is CanCommand.SendFrame -> {
        "ID: 0x${command.id.toString(16).uppercase()} | Data: ${command.data}"
    }
    is CanCommand.SendIsoTp -> {
        "TX: 0x${command.txId.toString(16).uppercase()} → RX: 0x${command.rxId.toString(16).uppercase()}\n" +
        "Data: ${command.data}"
    }
    is CanCommand.ReadDtcs -> {
        "TX: 0x${command.txId.toString(16).uppercase()} → RX: 0x${command.rxId.toString(16).uppercase()}"
    }
    is CanCommand.ClearDtcs -> {
        "TX: 0x${command.txId.toString(16).uppercase()} → RX: 0x${command.rxId.toString(16).uppercase()}"
    }
    is CanCommand.ReadVin -> {
        "TX: 0x${command.txId.toString(16).uppercase()} → RX: 0x${command.rxId.toString(16).uppercase()}"
    }
    is CanCommand.UdsRequest -> {
        "Service: 0x${command.service.toString(16).uppercase()}" +
        (command.subFunction?.let { " Sub: 0x${it.toString(16).uppercase()}" } ?: "") +
        (command.data?.let { "\nData: $it" } ?: "")
    }
    is CanCommand.Obd2Pid -> {
        "Service: 0x${command.service.toString(16).uppercase()} | PID: 0x${command.pid.toString(16).uppercase()}"
    }
    is CanCommand.ScanBus -> {
        "Dauer: ${command.durationMs}ms"
    }
    is CanCommand.ObserveIds -> {
        "IDs: ${command.ids.map { "0x${it.toString(16).uppercase()}" }.joinToString(", ")}\n" +
        "Dauer: ${command.durationMs}ms"
    }
    is CanCommand.Delay -> {
        "${command.milliseconds}ms"
    }
    is CanCommand.PeriodicFrame -> {
        "ID: 0x${command.id.toString(16).uppercase()} | Interval: ${command.intervalMs}ms | ${if (command.enable) "Start" else "Stop"}"
    }
}
