package at.planqton.directcan.ui.screens.dbc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.data.dbc.DbcSignal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalEditorDialog(
    signal: DbcSignal?,
    messageLength: Int,
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (DbcSignal) -> Unit
) {
    val isNew = signal == null
    val maxBits = messageLength * 8

    // Form fields
    var name by remember { mutableStateOf(signal?.name ?: "") }
    var startBit by remember { mutableStateOf(signal?.startBit?.toString() ?: "0") }
    var length by remember { mutableStateOf(signal?.length?.toString() ?: "8") }
    var byteOrder by remember { mutableStateOf(signal?.byteOrder ?: DbcSignal.ByteOrder.LITTLE_ENDIAN) }
    var valueType by remember { mutableStateOf(signal?.valueType ?: DbcSignal.ValueType.UNSIGNED) }
    var factor by remember { mutableStateOf(signal?.factor?.toString() ?: "1") }
    var offset by remember { mutableStateOf(signal?.offset?.toString() ?: "0") }
    var min by remember { mutableStateOf(signal?.min?.toString() ?: "0") }
    var max by remember { mutableStateOf(signal?.max?.toString() ?: "0") }
    var unit by remember { mutableStateOf(signal?.unit ?: "") }
    var description by remember { mutableStateOf(signal?.description ?: "") }

    // Validation
    var nameError by remember { mutableStateOf<String?>(null) }
    var startBitError by remember { mutableStateOf<String?>(null) }
    var lengthError by remember { mutableStateOf<String?>(null) }
    var factorError by remember { mutableStateOf<String?>(null) }

    fun validate(): Boolean {
        var valid = true

        // Name
        if (name.isBlank()) {
            nameError = "Name erforderlich"
            valid = false
        } else if (!name.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$"))) {
            nameError = "Nur Buchstaben, Zahlen, _ erlaubt"
            valid = false
        } else if (isNew && existingNames.contains(name)) {
            nameError = "Name existiert bereits"
            valid = false
        } else {
            nameError = null
        }

        // Start bit
        val parsedStartBit = startBit.toIntOrNull()
        if (parsedStartBit == null || parsedStartBit < 0 || parsedStartBit >= maxBits) {
            startBitError = "0-${maxBits - 1}"
            valid = false
        } else {
            startBitError = null
        }

        // Length
        val parsedLength = length.toIntOrNull()
        if (parsedLength == null || parsedLength < 1 || parsedLength > 64) {
            lengthError = "1-64 Bits"
            valid = false
        } else {
            lengthError = null
        }

        // Factor
        val parsedFactor = factor.toDoubleOrNull()
        if (parsedFactor == null || parsedFactor == 0.0) {
            factorError = "Faktor darf nicht 0 sein"
            valid = false
        } else {
            factorError = null
        }

        return valid
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Neues Signal" else "Signal bearbeiten") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.filter { c -> c.isLetterOrDigit() || c == '_' } },
                    label = { Text("Name") },
                    placeholder = { Text("z.B. EngineRPM") },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Bit position visualization
                BitMapVisualization(
                    messageLength = messageLength,
                    startBit = startBit.toIntOrNull() ?: 0,
                    signalLength = length.toIntOrNull() ?: 1,
                    byteOrder = byteOrder
                )

                // Start bit and length in row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = startBit,
                        onValueChange = { startBit = it.filter { c -> c.isDigit() } },
                        label = { Text("Start Bit") },
                        isError = startBitError != null,
                        supportingText = startBitError?.let { { Text(it) } },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    OutlinedTextField(
                        value = length,
                        onValueChange = { length = it.filter { c -> c.isDigit() } },
                        label = { Text("Länge (Bits)") },
                        isError = lengthError != null,
                        supportingText = lengthError?.let { { Text(it) } },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                // Byte order
                Text("Byte Order:", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = byteOrder == DbcSignal.ByteOrder.LITTLE_ENDIAN,
                        onClick = { byteOrder = DbcSignal.ByteOrder.LITTLE_ENDIAN },
                        label = { Text("Little Endian (Intel)") }
                    )
                    FilterChip(
                        selected = byteOrder == DbcSignal.ByteOrder.BIG_ENDIAN,
                        onClick = { byteOrder = DbcSignal.ByteOrder.BIG_ENDIAN },
                        label = { Text("Big Endian (Motorola)") }
                    )
                }

                // Value type
                Text("Werttyp:", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = valueType == DbcSignal.ValueType.UNSIGNED,
                        onClick = { valueType = DbcSignal.ValueType.UNSIGNED },
                        label = { Text("Unsigned") }
                    )
                    FilterChip(
                        selected = valueType == DbcSignal.ValueType.SIGNED,
                        onClick = { valueType = DbcSignal.ValueType.SIGNED },
                        label = { Text("Signed") }
                    )
                }

                HorizontalDivider()

                // Factor and Offset
                Text("Umrechnung: physikalisch = raw × Faktor + Offset", style = MaterialTheme.typography.labelSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = factor,
                        onValueChange = { factor = it },
                        label = { Text("Faktor") },
                        isError = factorError != null,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )

                    OutlinedTextField(
                        value = offset,
                        onValueChange = { offset = it },
                        label = { Text("Offset") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }

                // Min/Max
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = min,
                        onValueChange = { min = it },
                        label = { Text("Min") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )

                    OutlinedTextField(
                        value = max,
                        onValueChange = { max = it },
                        label = { Text("Max") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }

                // Unit
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text("Einheit") },
                    placeholder = { Text("z.B. rpm, km/h, °C") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Beschreibung (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (validate()) {
                        val newSignal = DbcSignal(
                            name = name,
                            startBit = startBit.toInt(),
                            length = length.toInt(),
                            byteOrder = byteOrder,
                            valueType = valueType,
                            factor = factor.toDoubleOrNull() ?: 1.0,
                            offset = offset.toDoubleOrNull() ?: 0.0,
                            min = min.toDoubleOrNull() ?: 0.0,
                            max = max.toDoubleOrNull() ?: 0.0,
                            unit = unit,
                            description = description,
                            receivers = signal?.receivers ?: emptyList(),
                            valueDescriptions = signal?.valueDescriptions ?: emptyMap(),
                            attributes = signal?.attributes ?: emptyMap()
                        )
                        onSave(newSignal)
                    }
                }
            ) {
                Text(if (isNew) "Erstellen" else "Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}

@Composable
private fun BitMapVisualization(
    messageLength: Int,
    startBit: Int,
    signalLength: Int,
    byteOrder: DbcSignal.ByteOrder
) {
    val bytesToShow = minOf(messageLength, 8)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(8.dp)
    ) {
        Text(
            "Bit-Position:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(4.dp))

        // Header with bit numbers
        Row {
            Text("Byte", Modifier.width(36.dp), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (bit in 7 downTo 0) {
                Text(
                    bit.toString(),
                    modifier = Modifier.weight(1f),
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Bytes with bits
        for (byteIdx in 0 until bytesToShow) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    byteIdx.toString(),
                    modifier = Modifier.width(36.dp),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )

                for (bitInByte in 7 downTo 0) {
                    val absoluteBit = byteIdx * 8 + bitInByte

                    val isInSignal = if (byteOrder == DbcSignal.ByteOrder.LITTLE_ENDIAN) {
                        // Little endian: bits are sequential from startBit
                        absoluteBit >= startBit && absoluteBit < startBit + signalLength
                    } else {
                        // Big endian: more complex calculation
                        // For simplicity, show approximate
                        absoluteBit >= startBit && absoluteBit < startBit + signalLength
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(1.dp)
                            .background(
                                if (isInSignal) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface,
                                MaterialTheme.shapes.extraSmall
                            )
                            .border(
                                0.5.dp,
                                MaterialTheme.colorScheme.outline,
                                MaterialTheme.shapes.extraSmall
                            )
                    )
                }
            }
        }

        if (messageLength > 8) {
            Text(
                "... (${messageLength - 8} weitere Bytes)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
