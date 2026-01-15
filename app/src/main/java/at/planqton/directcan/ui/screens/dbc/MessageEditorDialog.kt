package at.planqton.directcan.ui.screens.dbc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import at.planqton.directcan.data.dbc.DbcMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageEditorDialog(
    message: DbcMessage?,
    existingIds: List<Long>,
    onDismiss: () -> Unit,
    onSave: (DbcMessage) -> Unit
) {
    val isNew = message == null

    // Form fields
    var idHex by remember { mutableStateOf(message?.idHex ?: "") }
    var name by remember { mutableStateOf(message?.name ?: "") }
    var length by remember { mutableStateOf(message?.length?.toString() ?: "8") }
    var transmitter by remember { mutableStateOf(message?.transmitter ?: "") }
    var description by remember { mutableStateOf(message?.description ?: "") }
    var isExtended by remember { mutableStateOf(message?.isExtended ?: false) }

    // Validation
    var idError by remember { mutableStateOf<String?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var lengthError by remember { mutableStateOf<String?>(null) }

    fun validate(): Boolean {
        var valid = true

        // Validate ID
        val parsedId = idHex.toLongOrNull(16)
        if (parsedId == null) {
            idError = "Ungültige Hex-ID"
            valid = false
        } else if (isNew && existingIds.contains(parsedId)) {
            idError = "ID existiert bereits"
            valid = false
        } else if (!isNew && message!!.id != parsedId && existingIds.contains(parsedId)) {
            idError = "ID existiert bereits"
            valid = false
        } else {
            idError = null
        }

        // Validate name
        if (name.isBlank()) {
            nameError = "Name erforderlich"
            valid = false
        } else if (!name.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$"))) {
            nameError = "Nur Buchstaben, Zahlen, _ erlaubt"
            valid = false
        } else {
            nameError = null
        }

        // Validate length
        val parsedLength = length.toIntOrNull()
        if (parsedLength == null || parsedLength < 1 || parsedLength > 64) {
            lengthError = "1-64 Bytes"
            valid = false
        } else {
            lengthError = null
        }

        return valid
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Neue Message" else "Message bearbeiten") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Message ID
                OutlinedTextField(
                    value = idHex,
                    onValueChange = { idHex = it.uppercase().filter { c -> c.isDigit() || c in 'A'..'F' } },
                    label = { Text("ID (Hex)") },
                    placeholder = { Text("z.B. 7E0") },
                    isError = idError != null,
                    supportingText = idError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    prefix = { Text("0x") }
                )

                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.filter { c -> c.isLetterOrDigit() || c == '_' } },
                    label = { Text("Name") },
                    placeholder = { Text("z.B. ECM_REQUEST") },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Length
                OutlinedTextField(
                    value = length,
                    onValueChange = { length = it.filter { c -> c.isDigit() } },
                    label = { Text("Länge (Bytes)") },
                    isError = lengthError != null,
                    supportingText = lengthError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                // Transmitter
                OutlinedTextField(
                    value = transmitter,
                    onValueChange = { transmitter = it.filter { c -> c.isLetterOrDigit() || c == '_' } },
                    label = { Text("Transmitter (optional)") },
                    placeholder = { Text("z.B. ECM") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Beschreibung (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )

                // Extended ID checkbox
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isExtended,
                        onCheckedChange = { isExtended = it }
                    )
                    Text("Extended ID (29-bit)")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (validate()) {
                        val newMessage = DbcMessage(
                            id = idHex.toLong(16),
                            name = name,
                            length = length.toInt(),
                            transmitter = transmitter,
                            description = description,
                            isExtended = isExtended,
                            signals = message?.signals ?: emptyList(),
                            attributes = message?.attributes ?: emptyMap()
                        )
                        onSave(newMessage)
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
