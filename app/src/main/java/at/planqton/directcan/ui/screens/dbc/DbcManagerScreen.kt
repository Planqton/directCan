package at.planqton.directcan.ui.screens.dbc

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.dbc.DbcFile
import at.planqton.directcan.data.dbc.DbcFileInfo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DbcManagerScreen(
    onNavigateToEditor: ((DbcFileInfo) -> Unit)? = null
) {
    val context = LocalContext.current
    val dbcRepository = DirectCanApplication.instance.dbcRepository
    val scope = rememberCoroutineScope()

    val dbcFiles by dbcRepository.dbcFiles.collectAsState()
    val activeDbcInfo by dbcRepository.activeDbc.collectAsState()
    val activeDbcFile by dbcRepository.activeDbcFile.collectAsState()

    var showImportDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<DbcFileInfo?>(null) }
    var showDbcDetails by remember { mutableStateOf<DbcFileInfo?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    // File picker for import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                dbcRepository.importDbcFromUri(it).fold(
                    onSuccess = { importError = null },
                    onFailure = { e -> importError = "Import fehlgeschlagen: ${e.message}" }
                )
            }
        }
    }

    // File picker for export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                activeDbcInfo?.let { info ->
                    dbcRepository.exportDbc(info, it)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DBC Manager") },
                actions = {
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Default.Add, "DBC importieren")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { importLauncher.launch("*/*") },
                icon = { Icon(Icons.Default.FileUpload, null) },
                text = { Text("Importieren") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Error message
            importError?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { importError = null }) {
                            Icon(Icons.Default.Close, "Schließen")
                        }
                    }
                }
            }

            // Active DBC Card
            activeDbcInfo?.let { info ->
                activeDbcFile?.let { dbc ->
                    ActiveDbcCard(
                        info = info,
                        dbc = dbc,
                        onExport = { exportLauncher.launch("${info.name}.dbc") },
                        onViewDetails = { showDbcDetails = info },
                        onDeactivate = { dbcRepository.deactivateDbc() }
                    )
                }
            }

            // Info card when no DBCs loaded
            if (dbcFiles.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Storage,
                            null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Keine DBC-Dateien vorhanden",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Importiere eine DBC-Datei um CAN-Messages zu dekodieren.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // DBC file list
            if (dbcFiles.isNotEmpty()) {
                Text(
                    "Verfügbare DBC-Dateien",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(dbcFiles) { info ->
                        DbcFileCard(
                            info = info,
                            isActive = info.path == activeDbcInfo?.path,
                            onActivate = {
                                scope.launch {
                                    dbcRepository.loadDbc(info)
                                }
                            },
                            onDelete = { showDeleteDialog = info },
                            onViewDetails = { showDbcDetails = info },
                            onEdit = onNavigateToEditor?.let { { it(info) } }
                        )
                    }

                    // Spacer for FAB
                    item {
                        Spacer(Modifier.height(80.dp))
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { info ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("DBC löschen?") },
            text = {
                Text("Möchtest du \"${info.name}\" wirklich löschen?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            dbcRepository.deleteDbc(info)
                            showDeleteDialog = null
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
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // DBC Details bottom sheet
    showDbcDetails?.let { info ->
        DbcDetailsBottomSheet(
            info = info,
            dbc = if (info.path == activeDbcInfo?.path) activeDbcFile else null,
            dbcRepository = dbcRepository,
            onDismiss = { showDbcDetails = null }
        )
    }
}

@Composable
fun ActiveDbcCard(
    info: DbcFileInfo,
    dbc: DbcFile,
    onExport: () -> Unit,
    onViewDetails: () -> Unit,
    onDeactivate: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Aktive DBC",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onDeactivate,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Deaktivieren")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                info.name,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "${dbc.messages.size} Nachrichten, ${dbc.messages.sumOf { it.signals.size }} Signale",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onViewDetails) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Details")
                }
                OutlinedButton(onClick = onExport) {
                    Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Exportieren")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DbcFileCard(
    info: DbcFileInfo,
    isActive: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onViewDetails: () -> Unit,
    onEdit: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onActivate
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Storage,
                null,
                tint = if (isActive)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    info.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    info.sizeFormatted,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isActive) {
                AssistChip(
                    onClick = {},
                    label = { Text("Aktiv") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Check,
                            null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
            onEdit?.let {
                IconButton(onClick = it) {
                    Icon(Icons.Default.Edit, "Bearbeiten")
                }
            }
            IconButton(onClick = onViewDetails) {
                Icon(Icons.Default.Info, "Details")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    "Löschen",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DbcDetailsBottomSheet(
    info: DbcFileInfo,
    dbc: DbcFile?,
    dbcRepository: at.planqton.directcan.data.dbc.DbcRepository,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var loadedDbc by remember { mutableStateOf(dbc) }

    // Load DBC if not already loaded
    LaunchedEffect(info) {
        if (loadedDbc == null) {
            dbcRepository.loadDbc(info).onSuccess { loadedDbc = it }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                info.name,
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(16.dp))

            loadedDbc?.let { dbcFile ->
                // Statistics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem("Nachrichten", dbcFile.messages.size.toString())
                    StatItem("Signale", dbcFile.messages.sumOf { it.signals.size }.toString())
                    StatItem("Nodes", dbcFile.nodes.size.toString())
                }

                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                // Message list
                Text(
                    "Nachrichten",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(dbcFile.messages.take(20)) { message ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row {
                                    Text(
                                        String.format("0x%03X", message.id),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        message.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${message.length} Bytes",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (message.signals.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        message.signals.joinToString(", ") { it.name },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    if (dbcFile.messages.size > 20) {
                        item {
                            Text(
                                "... und ${dbcFile.messages.size - 20} weitere",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            } ?: run {
                // Loading indicator
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
