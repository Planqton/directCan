package at.planqton.directcan.ui.screens.visualscript

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import at.planqton.directcan.data.visualscript.*

/**
 * Dialog for configuring a node's parameters
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeConfigDialog(
    node: VisualNode,
    onDismiss: () -> Unit,
    onSave: (NodeConfig) -> Unit,
    onDelete: () -> Unit
) {
    var config by remember { mutableStateOf(node.config) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = getNodeIcon(node.type),
                    contentDescription = null,
                    tint = Color(node.type.color)
                )
                Column {
                    Text(node.type.displayName)
                    Text(
                        text = node.type.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Node-specific configuration fields
                when (node.type) {
                    // === TRIGGERS ===
                    NodeType.TRIGGER_ON_START -> {
                        Text(
                            "Dieser Node wird automatisch beim Script-Start ausgeführt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    NodeType.TRIGGER_ON_RECEIVE -> {
                        CanIdField(
                            value = config.canId,
                            onValueChange = { config = config.copy(canId = it) },
                            label = "CAN-ID (hex)"
                        )
                        OutlinedTextField(
                            value = config.dataPattern,
                            onValueChange = { config = config.copy(dataPattern = it) },
                            label = { Text("Daten-Filter (optional)") },
                            placeholder = { Text("z.B. 02,*,00") },
                            supportingText = { Text("* = Wildcard für beliebiges Byte") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    NodeType.TRIGGER_ON_INTERVAL -> {
                        TimeInputField(
                            valueMs = config.intervalMs,
                            onValueChange = { config = config.copy(intervalMs = it) },
                            label = "Intervall"
                        )
                    }

                    NodeType.TRIGGER_ON_TIME -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = config.timeHour.toString(),
                                onValueChange = {
                                    val hour = it.toIntOrNull()?.coerceIn(0, 23) ?: 0
                                    config = config.copy(timeHour = hour)
                                },
                                label = { Text("Stunde") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = config.timeMinute.toString(),
                                onValueChange = {
                                    val minute = it.toIntOrNull()?.coerceIn(0, 59) ?: 0
                                    config = config.copy(timeMinute = minute)
                                },
                                label = { Text("Minute") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = config.timeRepeat,
                                onCheckedChange = { config = config.copy(timeRepeat = it) }
                            )
                            Text("Täglich wiederholen")
                        }
                    }

                    NodeType.TRIGGER_ON_COUNT -> {
                        CanIdField(
                            value = config.canId,
                            onValueChange = { config = config.copy(canId = it) },
                            label = "CAN-ID (hex)"
                        )
                        OutlinedTextField(
                            value = config.count.toString(),
                            onValueChange = {
                                val count = it.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                config = config.copy(count = count)
                            },
                            label = { Text("Nach Anzahl Frames") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }

                    // === CONDITIONS ===
                    NodeType.CONDITION_IF_DATA -> {
                        OutlinedTextField(
                            value = config.byteIndex.toString(),
                            onValueChange = {
                                val index = it.toIntOrNull()?.coerceIn(0, 7) ?: 0
                                config = config.copy(byteIndex = index)
                            },
                            label = { Text("Byte-Index (0-7)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        CompareOperatorSelector(
                            selected = config.compareOperator,
                            onSelect = { config = config.copy(compareOperator = it) }
                        )
                        OutlinedTextField(
                            value = config.compareValue,
                            onValueChange = { config = config.copy(compareValue = it.uppercase()) },
                            label = { Text("Vergleichswert (hex)") },
                            placeholder = { Text("z.B. FF") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    NodeType.CONDITION_IF_TIME -> {
                        OutlinedTextField(
                            value = config.timeRangeStart,
                            onValueChange = { config = config.copy(timeRangeStart = it) },
                            label = { Text("Von (HH:MM)") },
                            placeholder = { Text("08:00") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = config.timeRangeEnd,
                            onValueChange = { config = config.copy(timeRangeEnd = it) },
                            label = { Text("Bis (HH:MM)") },
                            placeholder = { Text("18:00") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    NodeType.CONDITION_IF_COUNTER -> {
                        VariableNameField(
                            value = config.variableName,
                            onValueChange = { config = config.copy(variableName = it) }
                        )
                        CompareOperatorSelector(
                            selected = config.compareOperator,
                            onSelect = { config = config.copy(compareOperator = it) }
                        )
                        OutlinedTextField(
                            value = config.compareValue,
                            onValueChange = { config = config.copy(compareValue = it) },
                            label = { Text("Vergleichswert") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }

                    NodeType.CONDITION_IF_RECEIVED -> {
                        CanIdField(
                            value = config.canId,
                            onValueChange = { config = config.copy(canId = it) },
                            label = "CAN-ID (hex)"
                        )
                        TimeInputField(
                            valueMs = config.timeoutMs,
                            onValueChange = { config = config.copy(timeoutMs = it) },
                            label = "In letzten (Timeout)"
                        )
                    }

                    // === ACTIONS ===
                    NodeType.ACTION_SEND -> {
                        CanIdField(
                            value = config.canId,
                            onValueChange = { config = config.copy(canId = it) },
                            label = "CAN-ID (hex)"
                        )
                        OutlinedTextField(
                            value = config.canData,
                            onValueChange = { config = config.copy(canData = it.uppercase()) },
                            label = { Text("Daten (hex)") },
                            placeholder = { Text("z.B. 02,01,00") },
                            supportingText = { Text("Komma- oder Leerzeichen-getrennt") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = config.isExtended,
                                onCheckedChange = { config = config.copy(isExtended = it) }
                            )
                            Text("Extended Frame (29-bit ID)")
                        }
                    }

                    NodeType.ACTION_DELAY -> {
                        TimeInputField(
                            valueMs = config.delayMs,
                            onValueChange = { config = config.copy(delayMs = it) },
                            label = "Wartezeit"
                        )
                    }

                    NodeType.ACTION_SET_VARIABLE -> {
                        VariableNameField(
                            value = config.variableName,
                            onValueChange = { config = config.copy(variableName = it) }
                        )
                        OutlinedTextField(
                            value = config.variableValue,
                            onValueChange = { config = config.copy(variableValue = it) },
                            label = { Text("Wert") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    NodeType.ACTION_INCREMENT -> {
                        VariableNameField(
                            value = config.variableName,
                            onValueChange = { config = config.copy(variableName = it) }
                        )
                        OutlinedTextField(
                            value = config.incrementValue.toString(),
                            onValueChange = {
                                val value = it.toIntOrNull() ?: 1
                                config = config.copy(incrementValue = value)
                            },
                            label = { Text("Erhöhen um") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }

                    NodeType.ACTION_PRINT -> {
                        OutlinedTextField(
                            value = config.printText,
                            onValueChange = { config = config.copy(printText = it) },
                            label = { Text("Ausgabetext") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 3
                        )
                    }

                    // === FLOW ===
                    NodeType.FLOW_WAIT_FOR -> {
                        CanIdField(
                            value = config.canId,
                            onValueChange = { config = config.copy(canId = it) },
                            label = "CAN-ID (hex)"
                        )
                        TimeInputField(
                            valueMs = config.timeoutMs,
                            onValueChange = { config = config.copy(timeoutMs = it) },
                            label = "Timeout"
                        )
                        OutlinedTextField(
                            value = config.dataPattern,
                            onValueChange = { config = config.copy(dataPattern = it) },
                            label = { Text("Daten-Filter (optional)") },
                            placeholder = { Text("z.B. 02,*,00") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    NodeType.FLOW_REPEAT -> {
                        OutlinedTextField(
                            value = config.count.toString(),
                            onValueChange = {
                                val count = it.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                config = config.copy(count = count)
                            },
                            label = { Text("Wiederholungen") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }

                    NodeType.FLOW_LOOP -> {
                        Text(
                            "Dieser Block wird endlos wiederholt bis das Script gestoppt wird.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    NodeType.FLOW_STOP -> {
                        Text(
                            "Beendet das Script an dieser Stelle.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Löschen")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("Abbrechen")
                }
                Button(onClick = { onSave(config) }) {
                    Text("Speichern")
                }
            }
        }
    )
}

@Composable
private fun CanIdField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.uppercase().filter { c -> c.isLetterOrDigit() }) },
        label = { Text(label) },
        placeholder = { Text("z.B. 7DF") },
        prefix = { Text("0x") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun VariableNameField(
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { c -> c.isLetterOrDigit() || c == '_' }) },
        label = { Text("Variablenname") },
        placeholder = { Text("z.B. counter") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompareOperatorSelector(
    selected: CompareOperator,
    onSelect: (CompareOperator) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = "${selected.symbol} (${selected.displayName})",
            onValueChange = {},
            readOnly = true,
            label = { Text("Operator") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            CompareOperator.entries.forEach { operator ->
                DropdownMenuItem(
                    text = { Text("${operator.symbol} (${operator.displayName})") },
                    onClick = {
                        onSelect(operator)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun TimeInputField(
    valueMs: Long,
    onValueChange: (Long) -> Unit,
    label: String
) {
    var textValue by remember(valueMs) {
        mutableStateOf(
            when {
                valueMs >= 60000 && valueMs % 60000 == 0L -> "${valueMs / 60000}"
                valueMs >= 1000 && valueMs % 1000 == 0L -> "${valueMs / 1000}"
                else -> "$valueMs"
            }
        )
    }
    var unit by remember(valueMs) {
        mutableStateOf(
            when {
                valueMs >= 60000 && valueMs % 60000 == 0L -> TimeUnit.MINUTES
                valueMs >= 1000 && valueMs % 1000 == 0L -> TimeUnit.SECONDS
                else -> TimeUnit.MILLISECONDS
            }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = textValue,
            onValueChange = { newValue ->
                textValue = newValue
                val numValue = newValue.toLongOrNull() ?: 0L
                val ms = when (unit) {
                    TimeUnit.MILLISECONDS -> numValue
                    TimeUnit.SECONDS -> numValue * 1000
                    TimeUnit.MINUTES -> numValue * 60000
                }
                onValueChange(ms)
            },
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        SingleChoiceSegmentedButtonRow {
            TimeUnit.entries.forEachIndexed { index, timeUnit ->
                SegmentedButton(
                    selected = unit == timeUnit,
                    onClick = {
                        unit = timeUnit
                        val numValue = textValue.toLongOrNull() ?: 0L
                        val ms = when (timeUnit) {
                            TimeUnit.MILLISECONDS -> numValue
                            TimeUnit.SECONDS -> numValue * 1000
                            TimeUnit.MINUTES -> numValue * 60000
                        }
                        onValueChange(ms)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, TimeUnit.entries.size)
                ) {
                    Text(timeUnit.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private enum class TimeUnit(val label: String) {
    MILLISECONDS("ms"),
    SECONDS("s"),
    MINUTES("min")
}
