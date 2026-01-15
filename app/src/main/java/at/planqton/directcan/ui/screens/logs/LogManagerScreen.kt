package at.planqton.directcan.ui.screens.logs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.can.CanDataRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogManagerScreen(
    onNavigateBack: () -> Unit
) {
    val canDataRepository = DirectCanApplication.instance.canDataRepository
    val logFiles by canDataRepository.logFiles.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var newLogName by remember { mutableStateOf("") }
    var deleteConfirmFile by remember { mutableStateOf<CanDataRepository.LogFileInfo?>(null) }
    var selectedFile by remember { mutableStateOf<CanDataRepository.LogFileInfo?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Show error in snackbar
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            errorMessage = null
        }
    }

    LaunchedEffect(Unit) {
        canDataRepository.refreshLogFiles()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log Manager") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { canDataRepository.refreshLogFiles() }) {
                        Icon(Icons.Default.Refresh, "Aktualisieren")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Default.Add, "Neues Log") },
                text = { Text("Neues Log") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Info card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Speicherort",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            canDataRepository.getLogDirectoryDisplayPath(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            if (logFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Keine Log-Dateien vorhanden",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Erstellen Sie eine neue Log-Datei um Snapshots zu speichern",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(logFiles, key = { it.name }) { logFile ->
                        LogFileCard(
                            logFile = logFile,
                            dateFormat = dateFormat,
                            onDelete = { deleteConfirmFile = logFile },
                            onView = { selectedFile = logFile }
                        )
                    }
                }
            }
        }
    }

    // Create new log dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Neues Log erstellen") },
            text = {
                Column {
                    Text(
                        "Geben Sie einen Namen für das Log ein:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newLogName,
                        onValueChange = { newLogName = it },
                        label = { Text("Log-Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newLogName.isNotBlank()) {
                            val result = canDataRepository.createLogFile(newLogName)
                            newLogName = ""
                            showCreateDialog = false
                            if (result == null) {
                                errorMessage = "Fehler beim Erstellen der Log-Datei"
                            }
                            // Force refresh after small delay to ensure file is visible
                            scope.launch {
                                delay(200)
                                canDataRepository.refreshLogFiles()
                            }
                        }
                    },
                    enabled = newLogName.isNotBlank()
                ) {
                    Text("Erstellen")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    newLogName = ""
                    showCreateDialog = false
                }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // Delete confirmation dialog
    deleteConfirmFile?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteConfirmFile = null },
            title = { Text("Log löschen?") },
            text = {
                Text("Möchten Sie das Log \"${file.name}\" wirklich löschen? Diese Aktion kann nicht rückgängig gemacht werden.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        canDataRepository.deleteLogFile(file)
                        deleteConfirmFile = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Löschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmFile = null }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // View log content dialog
    selectedFile?.let { file ->
        val context = DirectCanApplication.instance
        AlertDialog(
            onDismissRequest = { selectedFile = null },
            title = { Text(file.name) },
            text = {
                val content = remember(file) {
                    try {
                        context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                            val text = reader.readText()
                            if (text.length > 5000) text.take(5000) + "\n\n... (gekürzt)" else text
                        } ?: "Datei konnte nicht gelesen werden"
                    } catch (e: Exception) {
                        "Fehler beim Lesen: ${e.message}"
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    Text(
                        content,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedFile = null }) {
                    Text("Schließen")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogFileCard(
    logFile: CanDataRepository.LogFileInfo,
    dateFormat: SimpleDateFormat,
    onDelete: () -> Unit,
    onView: () -> Unit
) {
    Card(
        onClick = onView,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    logFile.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "${logFile.snapshotCount} Snapshots • ${logFile.displaySize}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    dateFormat.format(Date(logFile.lastModified)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Löschen",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
