package at.planqton.directcan.ui.screens.visualscript

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.planqton.directcan.data.visualscript.VisualScriptFileInfo
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screen showing list of visual scripts
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualScriptListScreen(
    scripts: List<VisualScriptFileInfo>,
    onScriptClick: (VisualScriptFileInfo) -> Unit,
    onCreateScript: () -> Unit,
    onDeleteScript: (VisualScriptFileInfo) -> Unit,
    onDuplicateScript: (VisualScriptFileInfo) -> Unit,
    onRenameScript: (VisualScriptFileInfo) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var scriptToDelete by remember { mutableStateOf<VisualScriptFileInfo?>(null) }
    var scriptMenuOpen by remember { mutableStateOf<String?>(null) }

    // Create dialog
    if (showCreateDialog) {
        CreateScriptDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, description ->
                showCreateDialog = false
                onCreateScript()
            }
        )
    }

    // Delete confirmation dialog
    scriptToDelete?.let { script ->
        AlertDialog(
            onDismissRequest = { scriptToDelete = null },
            title = { Text("Script löschen?") },
            text = { Text("Möchtest du \"${script.name}\" wirklich löschen? Diese Aktion kann nicht rückgängig gemacht werden.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteScript(script)
                        scriptToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Löschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { scriptToDelete = null }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Visual Scripts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Neues Script") }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        if (scripts.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        "Keine Visual Scripts",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Erstelle dein erstes Script mit dem Node-Editor",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    OutlinedButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Script erstellen")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scripts, key = { it.id }) { script ->
                    ScriptListItem(
                        script = script,
                        onClick = { onScriptClick(script) },
                        onMenuClick = { scriptMenuOpen = if (scriptMenuOpen == script.id) null else script.id },
                        isMenuOpen = scriptMenuOpen == script.id,
                        onDelete = {
                            scriptMenuOpen = null
                            scriptToDelete = script
                        },
                        onDuplicate = {
                            scriptMenuOpen = null
                            onDuplicateScript(script)
                        },
                        onRename = {
                            scriptMenuOpen = null
                            onRenameScript(script)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScriptListItem(
    script: VisualScriptFileInfo,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    isMenuOpen: Boolean,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(
                    imageVector = Icons.Default.AccountTree,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(Modifier.width(16.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = script.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (script.description.isNotBlank()) {
                    Text(
                        text = script.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "${script.nodeCount} Nodes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = dateFormat.format(Date(script.lastModified)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Menu
            Box {
                IconButton(onClick = onMenuClick) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menü")
                }

                DropdownMenu(
                    expanded = isMenuOpen,
                    onDismissRequest = onMenuClick
                ) {
                    DropdownMenuItem(
                        text = { Text("Umbenennen") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = onRename
                    )
                    DropdownMenuItem(
                        text = { Text("Duplizieren") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = onDuplicate
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Löschen", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateScriptDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neues Visual Script") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = null
                    },
                    label = { Text("Name") },
                    placeholder = { Text("z.B. Automatische Abfrage") },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Beschreibung (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        nameError = "Name darf nicht leer sein"
                    } else {
                        onCreate(name, description)
                    }
                }
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
