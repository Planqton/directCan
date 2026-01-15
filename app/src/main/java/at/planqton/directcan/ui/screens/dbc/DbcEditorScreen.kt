package at.planqton.directcan.ui.screens.dbc

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.dbc.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DbcEditorScreen(
    dbcInfo: DbcFileInfo,
    onNavigateBack: () -> Unit
) {
    val dbcRepository = DirectCanApplication.instance.dbcRepository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var dbcFile by remember { mutableStateOf<DbcFile?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Expanded state for messages
    val expandedMessages = remember { mutableStateMapOf<Long, Boolean>() }

    // Dialog states
    var showAddMessageDialog by remember { mutableStateOf(false) }
    var editingMessage by remember { mutableStateOf<DbcMessage?>(null) }
    var showAddSignalDialog by remember { mutableStateOf<Long?>(null) } // messageId
    var editingSignal by remember { mutableStateOf<Pair<Long, DbcSignal>?>(null) } // messageId, signal

    // Delete confirmation
    var deleteMessageConfirm by remember { mutableStateOf<DbcMessage?>(null) }
    var deleteSignalConfirm by remember { mutableStateOf<Pair<Long, DbcSignal>?>(null) }

    // Load DBC file
    LaunchedEffect(dbcInfo) {
        isLoading = true
        dbcRepository.getDbcFile(dbcInfo).fold(
            onSuccess = {
                dbcFile = it
                isLoading = false
            },
            onFailure = {
                error = it.message
                isLoading = false
            }
        )
    }

    // Reload function
    fun reload() {
        scope.launch {
            dbcRepository.getDbcFile(dbcInfo).fold(
                onSuccess = { dbcFile = it },
                onFailure = { error = it.message }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(dbcInfo.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${dbcFile?.messages?.size ?: 0} Messages, ${dbcFile?.messages?.sumOf { it.signals.size } ?: 0} Signals",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddMessageDialog = true }) {
                        Icon(Icons.Default.Add, "Message hinzufügen")
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Error, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Text("Fehler: $error")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { reload() }) {
                            Text("Erneut versuchen")
                        }
                    }
                }
            }
            dbcFile != null -> {
                val dbc = dbcFile!!

                if (dbc.messages.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Inbox, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Text("Keine Messages vorhanden")
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { showAddMessageDialog = true }) {
                                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Message hinzufügen")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        dbc.messages.sortedBy { it.id }.forEach { message ->
                            val isExpanded = expandedMessages[message.id] == true

                            // Message header
                            item(key = "msg_${message.id}") {
                                MessageRow(
                                    message = message,
                                    isExpanded = isExpanded,
                                    onToggleExpand = {
                                        expandedMessages[message.id] = !isExpanded
                                    },
                                    onEdit = { editingMessage = message },
                                    onDelete = { deleteMessageConfirm = message },
                                    onAddSignal = { showAddSignalDialog = message.id }
                                )
                            }

                            // Signals (animated)
                            if (isExpanded) {
                                items(message.signals, key = { "sig_${message.id}_${it.name}" }) { signal ->
                                    SignalRow(
                                        signal = signal,
                                        onEdit = { editingSignal = Pair(message.id, signal) },
                                        onDelete = { deleteSignalConfirm = Pair(message.id, signal) }
                                    )
                                }

                                // Add signal button at bottom of expanded message
                                item(key = "add_sig_${message.id}") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showAddSignalDialog = message.id }
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                            .padding(start = 48.dp, top = 8.dp, bottom = 8.dp, end = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Add,
                                            null,
                                            Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Signal hinzufügen",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
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

    // Add Message Dialog
    if (showAddMessageDialog) {
        MessageEditorDialog(
            message = null,
            existingIds = dbcFile?.messages?.map { it.id } ?: emptyList(),
            onDismiss = { showAddMessageDialog = false },
            onSave = { newMessage ->
                scope.launch {
                    dbcRepository.addMessage(dbcInfo, newMessage).fold(
                        onSuccess = {
                            reload()
                            Toast.makeText(context, "Message hinzugefügt", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = {
                            Toast.makeText(context, "Fehler: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                showAddMessageDialog = false
            }
        )
    }

    // Edit Message Dialog
    editingMessage?.let { message ->
        MessageEditorDialog(
            message = message,
            existingIds = dbcFile?.messages?.filter { it.id != message.id }?.map { it.id } ?: emptyList(),
            onDismiss = { editingMessage = null },
            onSave = { updatedMessage ->
                scope.launch {
                    dbcRepository.updateMessage(dbcInfo, updatedMessage).fold(
                        onSuccess = {
                            reload()
                            Toast.makeText(context, "Message aktualisiert", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = {
                            Toast.makeText(context, "Fehler: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                editingMessage = null
            }
        )
    }

    // Add Signal Dialog
    showAddSignalDialog?.let { messageId ->
        val message = dbcFile?.messages?.find { it.id == messageId }
        if (message != null) {
            SignalEditorDialog(
                signal = null,
                messageLength = message.length,
                existingNames = message.signals.map { it.name },
                onDismiss = { showAddSignalDialog = null },
                onSave = { newSignal ->
                    scope.launch {
                        dbcRepository.addSignal(dbcInfo, messageId, newSignal).fold(
                            onSuccess = {
                                reload()
                                Toast.makeText(context, "Signal hinzugefügt", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = {
                                Toast.makeText(context, "Fehler: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    showAddSignalDialog = null
                }
            )
        }
    }

    // Edit Signal Dialog
    editingSignal?.let { (messageId, signal) ->
        val message = dbcFile?.messages?.find { it.id == messageId }
        if (message != null) {
            SignalEditorDialog(
                signal = signal,
                messageLength = message.length,
                existingNames = message.signals.filter { it.name != signal.name }.map { it.name },
                onDismiss = { editingSignal = null },
                onSave = { updatedSignal ->
                    scope.launch {
                        dbcRepository.updateSignal(dbcInfo, messageId, signal.name, updatedSignal).fold(
                            onSuccess = {
                                reload()
                                Toast.makeText(context, "Signal aktualisiert", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = {
                                Toast.makeText(context, "Fehler: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    editingSignal = null
                }
            )
        }
    }

    // Delete Message Confirmation
    deleteMessageConfirm?.let { message ->
        AlertDialog(
            onDismissRequest = { deleteMessageConfirm = null },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("Message löschen?") },
            text = {
                Text("Message '${message.name}' (${message.idHex}) mit ${message.signals.size} Signals löschen?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            dbcRepository.deleteMessage(dbcInfo, message.id).fold(
                                onSuccess = {
                                    reload()
                                    Toast.makeText(context, "Message gelöscht", Toast.LENGTH_SHORT).show()
                                },
                                onFailure = {
                                    Toast.makeText(context, "Fehler: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        deleteMessageConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Löschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteMessageConfirm = null }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // Delete Signal Confirmation
    deleteSignalConfirm?.let { (messageId, signal) ->
        AlertDialog(
            onDismissRequest = { deleteSignalConfirm = null },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("Signal löschen?") },
            text = { Text("Signal '${signal.name}' löschen?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            dbcRepository.deleteSignal(dbcInfo, messageId, signal.name).fold(
                                onSuccess = {
                                    reload()
                                    Toast.makeText(context, "Signal gelöscht", Toast.LENGTH_SHORT).show()
                                },
                                onFailure = {
                                    Toast.makeText(context, "Fehler: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        deleteSignalConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Löschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteSignalConfirm = null }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

@Composable
private fun MessageRow(
    message: DbcMessage,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddSignal: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
            .background(
                if (isExpanded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            Modifier.size(24.dp)
        )

        Spacer(Modifier.width(8.dp))

        // Message ID
        Text(
            message.idHex,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.width(12.dp))

        // Message name and info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                message.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Row {
                Text(
                    "${message.length} Bytes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (message.signals.isNotEmpty()) {
                    Text(
                        " • ${message.signals.size} Signals",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (message.transmitter.isNotEmpty()) {
                    Text(
                        " • ${message.transmitter}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Action buttons
        IconButton(onClick = onAddSignal, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Add, "Signal hinzufügen", Modifier.size(18.dp))
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Edit, "Bearbeiten", Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Delete, "Löschen", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
        }
    }

    HorizontalDivider()
}

@Composable
private fun SignalRow(
    signal: DbcSignal,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
            .padding(start = 48.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Timeline,
            null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.secondary
        )

        Spacer(Modifier.width(8.dp))

        // Signal info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                signal.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Bit position
                Text(
                    "Bit ${signal.startBit}:${signal.length}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Byte order
                Text(
                    if (signal.byteOrder == DbcSignal.ByteOrder.LITTLE_ENDIAN) "LE" else "BE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Factor/Offset
                if (signal.factor != 1.0 || signal.offset != 0.0) {
                    Text(
                        "×${signal.factor}+${signal.offset}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Unit
                if (signal.unit.isNotEmpty()) {
                    Text(
                        "[${signal.unit}]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Action buttons
        IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Edit, "Bearbeiten", Modifier.size(16.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Delete, "Löschen", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        }
    }
}
