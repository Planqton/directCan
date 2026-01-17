package at.planqton.directcan.ui.screens.txscript

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.txscript.TxScript
import at.planqton.directcan.data.txscript.TxScriptFileInfo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TxScriptManagerScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEditor: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = DirectCanApplication.instance.txScriptRepository

    val scripts by repository.scripts.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var selectedScript by remember { mutableStateOf<TxScriptFileInfo?>(null) }
    var showContextMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDuplicateDialog by remember { mutableStateOf(false) }

    // Import launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                repository.importFromUri(it).fold(
                    onSuccess = { info ->
                        Toast.makeText(context, "Script '${info.name}' importiert", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { error ->
                        Toast.makeText(context, "Import fehlgeschlagen: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    // Export launcher
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { targetUri ->
            selectedScript?.let { script ->
                scope.launch {
                    repository.exportToUri(script, targetUri).fold(
                        onSuccess = {
                            Toast.makeText(context, "Script exportiert", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { error ->
                            Toast.makeText(context, "Export fehlgeschlagen: ${error.message}", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        repository.refreshScriptList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TX Script Manager") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch("*/*") }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Importieren")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Neues Script")
            }
        }
    ) { padding ->
        if (scripts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        Icons.Default.Code,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        "Keine TX Scripts vorhanden",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Erstelle ein neues Script oder importiere eine .txs Datei",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scripts, key = { it.id }) { script ->
                    ScriptListItem(
                        script = script,
                        onClick = {
                            onNavigateToEditor(script.path)
                        },
                        onLongClick = {
                            selectedScript = script
                            showContextMenu = true
                        }
                    )
                }
            }
        }
    }

    // Context Menu
    if (showContextMenu && selectedScript != null) {
        ModalBottomSheet(
            onDismissRequest = { showContextMenu = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = selectedScript!!.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )

                HorizontalDivider()

                ListItem(
                    headlineContent = { Text("Bearbeiten") },
                    leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            showContextMenu = false
                            onNavigateToEditor(selectedScript!!.path)
                        }
                    )
                )

                ListItem(
                    headlineContent = { Text("Exportieren") },
                    leadingContent = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            showContextMenu = false
                            exportLauncher.launch("${selectedScript!!.name}.txs")
                        }
                    )
                )

                ListItem(
                    headlineContent = { Text("Duplizieren") },
                    leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            showContextMenu = false
                            showDuplicateDialog = true
                        }
                    )
                )

                ListItem(
                    headlineContent = { Text("Umbenennen") },
                    leadingContent = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            showContextMenu = false
                            showRenameDialog = true
                        }
                    )
                )

                ListItem(
                    headlineContent = { Text("Löschen") },
                    leadingContent = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            showContextMenu = false
                            showDeleteConfirm = true
                        }
                    )
                )
            }
        }
    }

    // Create Dialog
    if (showCreateDialog) {
        CreateScriptDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, description ->
                scope.launch {
                    repository.createScript(name, description).fold(
                        onSuccess = { info ->
                            showCreateDialog = false
                            onNavigateToEditor(info.path)
                        },
                        onFailure = { error ->
                            Toast.makeText(context, "Fehler: ${error.message}", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            },
            validateName = { repository.validateScriptName(it) }
        )
    }

    // Delete Confirmation
    if (showDeleteConfirm && selectedScript != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Script löschen?") },
            text = { Text("Möchtest du '${selectedScript!!.name}' wirklich löschen? Diese Aktion kann nicht rückgängig gemacht werden.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            repository.deleteScript(selectedScript!!)
                            showDeleteConfirm = false
                            selectedScript = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Löschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // Rename Dialog
    if (showRenameDialog && selectedScript != null) {
        RenameScriptDialog(
            currentName = selectedScript!!.name,
            onDismiss = { showRenameDialog = false },
            onRename = { newName ->
                scope.launch {
                    repository.renameScript(selectedScript!!, newName).fold(
                        onSuccess = {
                            showRenameDialog = false
                            selectedScript = null
                        },
                        onFailure = { error ->
                            Toast.makeText(context, "Fehler: ${error.message}", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            },
            validateName = { name ->
                if (name == selectedScript!!.name) null
                else repository.validateScriptName(name)
            }
        )
    }

    // Duplicate Dialog
    if (showDuplicateDialog && selectedScript != null) {
        DuplicateScriptDialog(
            originalName = selectedScript!!.name,
            onDismiss = { showDuplicateDialog = false },
            onDuplicate = { newName ->
                scope.launch {
                    repository.duplicateScript(selectedScript!!, newName).fold(
                        onSuccess = { info ->
                            showDuplicateDialog = false
                            selectedScript = null
                            Toast.makeText(context, "Script dupliziert", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { error ->
                            Toast.makeText(context, "Fehler: ${error.message}", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            },
            validateName = { repository.validateScriptName(it) }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScriptListItem(
    script: TxScriptFileInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Code,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = script.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (script.description.isNotEmpty()) {
                    Text(
                        text = script.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = "${script.displaySize} • ${dateFormat.format(Date(script.lastModified))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CreateScriptDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String) -> Unit,
    validateName: (String) -> String?
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neues TX Script") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = validateName(it)
                    },
                    label = { Text("Name") },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Beschreibung (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, description) },
                enabled = name.isNotBlank() && nameError == null
            ) {
                Text("Erstellen")
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
private fun RenameScriptDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (newName: String) -> Unit,
    validateName: (String) -> String?
) {
    var name by remember { mutableStateOf(currentName) }
    var nameError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Script umbenennen") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = validateName(it)
                },
                label = { Text("Neuer Name") },
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name) },
                enabled = name.isNotBlank() && nameError == null && name != currentName
            ) {
                Text("Umbenennen")
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
private fun DuplicateScriptDialog(
    originalName: String,
    onDismiss: () -> Unit,
    onDuplicate: (newName: String) -> Unit,
    validateName: (String) -> String?
) {
    var name by remember { mutableStateOf("${originalName}_copy") }
    var nameError by remember { mutableStateOf<String?>(validateName("${originalName}_copy")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Script duplizieren") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = validateName(it)
                },
                label = { Text("Name der Kopie") },
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onDuplicate(name) },
                enabled = name.isNotBlank() && nameError == null
            ) {
                Text("Duplizieren")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}
