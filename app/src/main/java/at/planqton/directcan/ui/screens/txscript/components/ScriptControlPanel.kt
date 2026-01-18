package at.planqton.directcan.ui.screens.txscript.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.planqton.directcan.data.txscript.ScriptExecutionState
import at.planqton.directcan.data.txscript.ScriptState
import at.planqton.directcan.data.txscript.TxScriptFileInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptControlPanel(
    isExpanded: Boolean,
    availableScripts: List<TxScriptFileInfo>,
    selectedScript: TxScriptFileInfo?,
    executionState: ScriptExecutionState,
    onSelectScript: (TxScriptFileInfo?) -> Unit,
    onStart: (Set<Int>) -> Unit,  // Now accepts target ports
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onShowErrors: () -> Unit,
    modifier: Modifier = Modifier,
    // Multi-port support
    showPortSelection: Boolean = false,
    port1Color: Color = Color(0xFF4CAF50),
    port2Color: Color = Color(0xFF2196F3)
) {
    // Script port selection state
    var scriptPorts by remember { mutableStateOf(setOf(1, 2)) }

    fun getPortColor(port: Int): Color {
        return when (port) {
            1 -> port1Color
            2 -> port2Color
            else -> Color.Gray
        }
    }
    AnimatedVisibility(
        visible = isExpanded,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "TX Script",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Port selection (only when multi-port)
                if (showPortSelection) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Senden an:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilterChip(
                            selected = 1 in scriptPorts,
                            onClick = {
                                scriptPorts = if (1 in scriptPorts) {
                                    if (scriptPorts.size > 1) scriptPorts - 1 else scriptPorts
                                } else {
                                    scriptPorts + 1
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
                                    Text("P1")
                                }
                            }
                        )
                        FilterChip(
                            selected = 2 in scriptPorts,
                            onClick = {
                                scriptPorts = if (2 in scriptPorts) {
                                    if (scriptPorts.size > 1) scriptPorts - 2 else scriptPorts
                                } else {
                                    scriptPorts + 2
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
                                    Text("P2")
                                }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Script selector and controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Script dropdown
                    var expanded by remember { mutableStateOf(false) }

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = selectedScript?.name ?: "Script wählen...",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall,
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface
                            )
                        )

                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            if (availableScripts.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Keine Scripts vorhanden") },
                                    onClick = { expanded = false },
                                    enabled = false
                                )
                            } else {
                                availableScripts.forEach { script ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                script.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        onClick = {
                                            onSelectScript(script)
                                            expanded = false
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Code,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Control buttons
                    when (executionState.state) {
                        ScriptState.IDLE, ScriptState.STOPPED, ScriptState.COMPLETED, ScriptState.ERROR -> {
                            // Play button
                            FilledIconButton(
                                onClick = { onStart(scriptPorts) },
                                enabled = selectedScript != null,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                            }
                        }
                        ScriptState.RUNNING -> {
                            // Pause button
                            FilledIconButton(
                                onClick = onPause,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = "Pause")
                            }
                        }
                        ScriptState.PAUSED -> {
                            // Resume button
                            FilledIconButton(
                                onClick = onResume,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Fortsetzen")
                            }
                        }
                    }

                    // Stop button (only when active)
                    if (executionState.isActive) {
                        FilledIconButton(
                            onClick = onStop,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop")
                        }
                    }

                    // Error button
                    if (executionState.hasErrors) {
                        BadgedBox(
                            badge = {
                                Badge {
                                    Text(executionState.errorCount.toString())
                                }
                            }
                        ) {
                            IconButton(onClick = onShowErrors) {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = "Fehler anzeigen",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                // Status bar (when running)
                if (executionState.isActive || executionState.isCompleted) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Status indicator
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ScriptStatusIndicator(executionState.state)
                            Text(
                                when (executionState.state) {
                                    ScriptState.RUNNING -> "Läuft"
                                    ScriptState.PAUSED -> "Pausiert"
                                    ScriptState.COMPLETED -> "Fertig"
                                    ScriptState.ERROR -> "Fehler"
                                    else -> ""
                                },
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        // Progress info
                        if (executionState.totalLines > 0) {
                            Text(
                                "Zeile ${executionState.currentLine}/${executionState.totalLines}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Frames sent
                        Text(
                            "${executionState.framesSent} gesendet",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Elapsed time
                        Text(
                            formatTime(executionState.elapsedTime),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Progress bar
                    if (executionState.totalLines > 0 && executionState.isActive) {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { executionState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = when (executionState.state) {
                                ScriptState.PAUSED -> MaterialTheme.colorScheme.secondary
                                ScriptState.ERROR -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptStatusIndicator(state: ScriptState) {
    val color = when (state) {
        ScriptState.RUNNING -> MaterialTheme.colorScheme.primary
        ScriptState.PAUSED -> MaterialTheme.colorScheme.secondary
        ScriptState.COMPLETED -> MaterialTheme.colorScheme.tertiary
        ScriptState.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, shape = MaterialTheme.shapes.extraSmall)
    )
}

private fun formatTime(millis: Long): String {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val secs = seconds % 60
    return if (minutes > 0) {
        "${minutes}m ${secs}s"
    } else {
        "${secs}s"
    }
}
