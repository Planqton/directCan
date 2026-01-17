package at.planqton.directcan.ui.screens.gemini

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.gemini.AiChatRepository
import at.planqton.directcan.data.gemini.ChatMessage
import at.planqton.directcan.data.gemini.DbcChangeResult
import at.planqton.directcan.data.gemini.GeminiResponseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    chatId: String,
    onNavigateBack: () -> Unit,
    onSwitchChat: (String) -> Unit = {}
) {
    val aiRepository = DirectCanApplication.instance.aiChatRepository
    val dbcRepository = DirectCanApplication.instance.dbcRepository
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Export state
    var showExportDialog by remember { mutableStateOf(false) }
    var selectedExportFormat by remember { mutableStateOf(AiChatRepository.ChatExportFormat.MARKDOWN) }
    var showExportMenu by remember { mutableStateOf(false) }

    // File saver launcher
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let {
            scope.launch {
                val content = aiRepository.exportChatSession(chatId, selectedExportFormat)
                if (content != null) {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(content.toByteArray())
                        }
                    }
                    Toast.makeText(context, "Chat exportiert", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val chatSessions by aiRepository.chatSessions.collectAsState()
    val isLoading by aiRepository.isLoading.collectAsState()
    val error by aiRepository.error.collectAsState()
    val deltaMode by aiRepository.deltaMode.collectAsState(initial = false)
    val lastDbcChanges by aiRepository.lastDbcChanges.collectAsState()
    val dbcFiles by dbcRepository.dbcFiles.collectAsState()

    val currentSession = chatSessions.find { it.id == chatId }
    val linkedDbcInfo = currentSession?.linkedDbcPath?.let { path ->
        dbcFiles.find { it.path == path }
    }
    var messageInput by remember { mutableStateOf("") }
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var showChatSwitcher by remember { mutableStateOf(false) }
    var showSendOptions by remember { mutableStateOf(false) }

    // Response parser for DBC commands
    val responseParser = remember { GeminiResponseParser() }

    // Scroll to bottom when new messages arrive
    LaunchedEffect(currentSession?.messages?.size) {
        if (currentSession != null && currentSession.messages.isNotEmpty()) {
            listState.animateScrollToItem(currentSession.messages.size - 1)
        }
    }

    // Set active chat
    LaunchedEffect(chatId) {
        aiRepository.setActiveChatId(chatId)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // Prevent consuming insets (outer Scaffold handles bottom bar)
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Row(
                            modifier = Modifier
                                .clickable(enabled = chatSessions.size > 1) { showChatSwitcher = true },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    currentSession?.snapshotName ?: "AI Chat",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (currentSession != null) {
                                    val modelInfo = currentSession.modelId ?: "Kein Modell"
                                    Text(
                                        modelInfo,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (chatSessions.size > 1) {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Chat wechseln",
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // Chat switcher dropdown
                        DropdownMenu(
                            expanded = showChatSwitcher,
                            onDismissRequest = { showChatSwitcher = false }
                        ) {
                            chatSessions.forEach { session ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (session.id == chatId) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(Modifier.width(8.dp))
                                            }
                                            Column {
                                                Text(
                                                    session.snapshotName,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    session.modelId ?: "Kein Modell",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        showChatSwitcher = false
                                        if (session.id != chatId) {
                                            onSwitchChat(session.id)
                                        }
                                    }
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Zurück")
                    }
                },
                actions = {
                    // DBC link indicator
                    if (linkedDbcInfo != null) {
                        IconButton(onClick = {
                            // TODO: Navigate to DBC editor
                        }) {
                            Icon(
                                Icons.Default.Storage,
                                contentDescription = "Verknüpfte DBC: ${linkedDbcInfo.name}",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Export button with dropdown
                    Box {
                        IconButton(onClick = { showExportMenu = true }) {
                            Icon(Icons.Default.Share, "Chat exportieren")
                        }
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Als Markdown (.md)") },
                                leadingIcon = { Icon(Icons.Default.Description, null) },
                                onClick = {
                                    showExportMenu = false
                                    selectedExportFormat = AiChatRepository.ChatExportFormat.MARKDOWN
                                    showExportDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Als Text (.txt)") },
                                leadingIcon = { Icon(Icons.Default.TextSnippet, null) },
                                onClick = {
                                    showExportMenu = false
                                    selectedExportFormat = AiChatRepository.ChatExportFormat.TEXT
                                    showExportDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Als JSON (.json)") },
                                leadingIcon = { Icon(Icons.Default.Code, null) },
                                onClick = {
                                    showExportMenu = false
                                    selectedExportFormat = AiChatRepository.ChatExportFormat.JSON
                                    showExportDialog = true
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Teilen...") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = {
                                    showExportMenu = false
                                    scope.launch {
                                        val content = aiRepository.exportChatSession(
                                            chatId,
                                            AiChatRepository.ChatExportFormat.TEXT
                                        )
                                        if (content != null) {
                                            val sendIntent = Intent().apply {
                                                action = Intent.ACTION_SEND
                                                putExtra(Intent.EXTRA_TEXT, content)
                                                putExtra(Intent.EXTRA_SUBJECT, "DirectCAN Chat: ${currentSession?.snapshotName}")
                                                type = "text/plain"
                                            }
                                            val shareIntent = Intent.createChooser(sendIntent, "Chat teilen")
                                            context.startActivity(shareIntent)
                                        }
                                    }
                                }
                            )
                        }
                    }

                    // Show snapshot data as it would be sent to AI
                    var showSnapshotDialog by remember { mutableStateOf(false) }
                    IconButton(onClick = { showSnapshotDialog = true }) {
                        Icon(Icons.Default.DataObject, "Snapshot-Daten")
                    }

                    if (showSnapshotDialog && currentSession != null) {
                        // Apply delta compression if enabled (same as when sending to AI)
                        val displayData = if (deltaMode) {
                            aiRepository.compressToDeltas(currentSession.snapshotData)
                        } else {
                            currentSession.snapshotData
                        }

                        AlertDialog(
                            onDismissRequest = { showSnapshotDialog = false },
                            title = {
                                Column {
                                    Text("Snapshot-Daten für AI")
                                    Text(
                                        if (deltaMode) "Delta-Komprimiert" else "Original",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (deltaMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            text = {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 400.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        displayData,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showSnapshotDialog = false }) {
                                    Text("Schließen")
                                }
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        // Export Dialog
        if (showExportDialog) {
            val formatName = when (selectedExportFormat) {
                AiChatRepository.ChatExportFormat.JSON -> "JSON"
                AiChatRepository.ChatExportFormat.MARKDOWN -> "Markdown"
                AiChatRepository.ChatExportFormat.TEXT -> "Text"
            }
            val filename = aiRepository.getExportFilename(chatId, selectedExportFormat)

            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                icon = { Icon(Icons.Default.FileDownload, null) },
                title = { Text("Chat als $formatName exportieren") },
                text = {
                    Column {
                        Text("Der Chat \"${currentSession?.snapshotName}\" wird als $formatName-Datei exportiert.")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Dateiname: $filename",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showExportDialog = false
                            val mimeType = when (selectedExportFormat) {
                                AiChatRepository.ChatExportFormat.JSON -> "application/json"
                                AiChatRepository.ChatExportFormat.MARKDOWN -> "text/markdown"
                                AiChatRepository.ChatExportFormat.TEXT -> "text/plain"
                            }
                            exportLauncher.launch(filename)
                        }
                    ) {
                        Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Speichern")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExportDialog = false }) {
                        Text("Abbrechen")
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Messages
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (currentSession == null) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Chat nicht gefunden",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // Welcome message
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "Snapshot geladen",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Ich habe den Snapshot \"${currentSession.snapshotName}\" geladen. " +
                                                "Stelle mir Fragen zur Analyse der CAN-Bus Daten.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }

                    // Chat messages - use index + timestamp for unique key
                    items(
                        items = currentSession.messages,
                        key = { "${it.role}_${it.timestamp}_${it.content.hashCode()}" }
                    ) { message ->
                        ChatMessageBubble(
                            message = message,
                            dateFormat = dateFormat
                        )
                    }

                    // Loading indicator
                    if (isLoading) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "AI denkt...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // DBC changes display
            if (lastDbcChanges.isNotEmpty()) {
                DbcChangesCard(
                    changes = lastDbcChanges,
                    onDismiss = { aiRepository.clearDbcChanges() }
                )
            }

            // Error display
            error?.let { errorMsg ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            errorMsg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { scope.launch { aiRepository.clearError() } }
                        ) {
                            Icon(Icons.Default.Close, "Schließen")
                        }
                    }
                }
            }

            // Input field
            if (currentSession != null) {
                Surface(
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = messageInput,
                            onValueChange = { messageInput = it },
                            placeholder = { Text("Nachricht eingeben...") },
                            modifier = Modifier.weight(1f),
                            maxLines = 4,
                            enabled = !isLoading
                        )
                        Spacer(Modifier.width(8.dp))
                        // Options dropdown button
                        Box {
                            IconButton(
                                onClick = { showSendOptions = true },
                                enabled = messageInput.isNotBlank() && !isLoading
                            ) {
                                Icon(Icons.Default.MoreVert, "Optionen")
                            }
                            DropdownMenu(
                                expanded = showSendOptions,
                                onDismissRequest = { showSendOptions = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("DBC erstellen") },
                                    leadingIcon = { Icon(Icons.Default.Storage, null) },
                                    onClick = {
                                        showSendOptions = false
                                        if (messageInput.isNotBlank() && !isLoading) {
                                            val message = messageInput
                                            messageInput = ""
                                            scope.launch {
                                                val response = aiRepository.sendMessage(
                                                    chatId, message,
                                                    skipSnapshot = false,
                                                    requestDbcUpdate = true
                                                )
                                                if (response != null && linkedDbcInfo != null) {
                                                    val parsed = responseParser.parseResponse(response)
                                                    if (parsed.hasCommands) {
                                                        aiRepository.executeDbcCommands(
                                                            linkedDbcInfo,
                                                            parsed.dbcCommands,
                                                            dbcRepository
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    enabled = linkedDbcInfo != null
                                )
                                DropdownMenuItem(
                                    text = { Text("Ohne Snapshot") },
                                    leadingIcon = { Icon(Icons.Default.QuestionAnswer, null) },
                                    onClick = {
                                        showSendOptions = false
                                        if (messageInput.isNotBlank() && !isLoading) {
                                            val message = messageInput
                                            messageInput = ""
                                            scope.launch {
                                                aiRepository.sendMessage(
                                                    chatId, message,
                                                    skipSnapshot = true,
                                                    requestDbcUpdate = false
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        // Send button
                        FilledIconButton(
                            onClick = {
                                if (messageInput.isNotBlank() && !isLoading) {
                                    val message = messageInput
                                    messageInput = ""
                                    scope.launch {
                                        val response = aiRepository.sendMessage(chatId, message)

                                        // Parse response for DBC commands and execute them
                                        if (response != null && linkedDbcInfo != null) {
                                            val parsed = responseParser.parseResponse(response)
                                            if (parsed.hasCommands) {
                                                aiRepository.executeDbcCommands(
                                                    linkedDbcInfo,
                                                    parsed.dbcCommands,
                                                    dbcRepository
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = messageInput.isNotBlank() && !isLoading
                        ) {
                            Icon(Icons.Default.Send, "Senden")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    dateFormat: SimpleDateFormat
) {
    val isUser = message.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(
                    if (isUser)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(12.dp)
        ) {
            Text(
                message.content,
                color = if (isUser)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                dateFormat.format(Date(message.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = if (isUser)
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun DbcChangesCard(
    changes: List<DbcChangeResult>,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "DBC Änderungen",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Schließen",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            changes.forEach { change ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Icon(
                        if (change.success) Icons.Default.Check else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (change.success)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        change.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}
