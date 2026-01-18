package at.planqton.directcan.ui.screens.gemini

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.gemini.CanCommandBlock
import at.planqton.directcan.data.gemini.CanCommandExecutor
import at.planqton.directcan.data.gemini.ChatMessage
import at.planqton.directcan.data.gemini.ChatSession
import at.planqton.directcan.data.gemini.ChatType
import at.planqton.directcan.data.gemini.GeminiResponseParser
import at.planqton.directcan.data.gemini.formatForAi
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingAiChatWindow(
    chatId: String,
    initialOffsetX: Float,
    initialOffsetY: Float,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    var offsetX by remember { mutableFloatStateOf(initialOffsetX) }
    var offsetY by remember { mutableFloatStateOf(initialOffsetY) }
    var isMinimized by remember { mutableStateOf(false) }

    // Resizable window dimensions (in dp)
    var windowWidth by remember { mutableFloatStateOf(350f) }
    var windowHeight by remember { mutableFloatStateOf(500f) }

    // Chat state
    val aiRepository = DirectCanApplication.instance.aiChatRepository
    val deviceManager = DirectCanApplication.instance.deviceManager
    val canDataRepository = DirectCanApplication.instance.canDataRepository
    val dbcRepository = DirectCanApplication.instance.dbcRepository
    val chatSessions by aiRepository.chatSessions.collectAsState()
    val isLoading by aiRepository.isLoading.collectAsState()
    val error by aiRepository.error.collectAsState()
    val dbcFiles by dbcRepository.dbcFiles.collectAsState()
    val session = chatSessions.find { it.id == chatId }
    val linkedDbcInfo = session?.linkedDbcPath?.let { path ->
        dbcFiles.find { it.path == path }
    }

    var messageInput by remember { mutableStateOf("") }
    var showSendOptions by remember { mutableStateOf(false) }
    var showShareMenu by remember { mutableStateOf(false) }
    var showSnapshotDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // CAN command state
    val responseParser = remember { GeminiResponseParser() }
    val canExecutor = remember { CanCommandExecutor(deviceManager) }
    var pendingCanCommands by remember { mutableStateOf<CanCommandBlock?>(null) }
    var isExecutingCan by remember { mutableStateOf(false) }
    var executionLog by remember { mutableStateOf<List<String>>(emptyList()) }

    // Parse last AI message for CAN commands
    LaunchedEffect(session?.messages) {
        val lastMessage = session?.messages?.lastOrNull()
        if (lastMessage?.role == "model" && pendingCanCommands == null && !isExecutingCan) {
            val parsed = responseParser.parseResponse(lastMessage.content)
            if (parsed.hasCanCommands) {
                pendingCanCommands = parsed.canCommandBlock
            }
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(session?.messages?.size) {
        if (session?.messages?.isNotEmpty() == true) {
            listState.animateScrollToItem(session.messages.size - 1)
        }
    }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
    ) {
        Surface(
            modifier = Modifier
                .width(if (isMinimized) 200.dp else windowWidth.dp)
                .height(if (isMinimized) 40.dp else windowHeight.dp)
                .shadow(8.dp, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Box {
                Column {
                    // Title bar (draggable)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    offsetX = (offsetX + dragAmount.x).coerceIn(0f, screenWidthPx - 200f)
                                    offsetY = (offsetY + dragAmount.y).coerceIn(0f, screenHeightPx - 40f)
                                }
                            },
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                // Different icon based on chat type
                                val isExploreChat = session?.chatType == ChatType.EXPLORE
                                Icon(
                                    if (isExploreChat) Icons.Default.Explore else Icons.Default.Psychology,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (isExploreChat) "KI Explore" else (session?.snapshotName ?: "KI Analyse"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row {
                                // Share and Snapshot buttons for ANALYSIS chats
                                val isAnalysisChat = session?.chatType != ChatType.EXPLORE
                                if (isAnalysisChat && !isMinimized) {
                                    // Share button
                                    Box {
                                        IconButton(
                                            onClick = { showShareMenu = true },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Share,
                                                contentDescription = "Teilen",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showShareMenu,
                                            onDismissRequest = { showShareMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Als Markdown (.md)", style = MaterialTheme.typography.bodySmall) },
                                                leadingIcon = { Icon(Icons.Default.Description, null, Modifier.size(18.dp)) },
                                                onClick = {
                                                    showShareMenu = false
                                                    session?.let { s ->
                                                        val content = buildString {
                                                            appendLine("# ${s.snapshotName}")
                                                            appendLine()
                                                            s.messages.forEach { msg ->
                                                                appendLine("**${if (msg.role == "user") "User" else "AI"}:**")
                                                                appendLine(msg.content)
                                                                appendLine()
                                                            }
                                                        }
                                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                            type = "text/markdown"
                                                            putExtra(android.content.Intent.EXTRA_TEXT, content)
                                                            putExtra(android.content.Intent.EXTRA_SUBJECT, "${s.snapshotName}.md")
                                                        }
                                                        context.startActivity(android.content.Intent.createChooser(intent, "Exportieren"))
                                                    }
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Als Text (.txt)", style = MaterialTheme.typography.bodySmall) },
                                                leadingIcon = { Icon(Icons.Default.Description, null, Modifier.size(18.dp)) },
                                                onClick = {
                                                    showShareMenu = false
                                                    session?.let { s ->
                                                        val content = buildString {
                                                            appendLine(s.snapshotName)
                                                            appendLine("=".repeat(s.snapshotName.length))
                                                            appendLine()
                                                            s.messages.forEach { msg ->
                                                                appendLine("[${if (msg.role == "user") "User" else "AI"}]")
                                                                appendLine(msg.content)
                                                                appendLine()
                                                            }
                                                        }
                                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                            type = "text/plain"
                                                            putExtra(android.content.Intent.EXTRA_TEXT, content)
                                                        }
                                                        context.startActivity(android.content.Intent.createChooser(intent, "Exportieren"))
                                                    }
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Teilen...", style = MaterialTheme.typography.bodySmall) },
                                                leadingIcon = { Icon(Icons.Default.Share, null, Modifier.size(18.dp)) },
                                                onClick = {
                                                    showShareMenu = false
                                                    session?.let { s ->
                                                        val content = s.messages.joinToString("\n\n") { msg ->
                                                            "[${if (msg.role == "user") "User" else "AI"}]\n${msg.content}"
                                                        }
                                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                            type = "text/plain"
                                                            putExtra(android.content.Intent.EXTRA_TEXT, content)
                                                        }
                                                        context.startActivity(android.content.Intent.createChooser(intent, "Teilen"))
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    // Snapshot data button
                                    IconButton(
                                        onClick = { showSnapshotDialog = true },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.DataObject,
                                            contentDescription = "Snapshot-Daten",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { isMinimized = !isMinimized },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        if (isMinimized) Icons.Default.OpenInFull else Icons.Default.Minimize,
                                        contentDescription = if (isMinimized) "Maximieren" else "Minimieren",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                IconButton(
                                    onClick = onClose,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Schließen",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }

                    // Content (when not minimized)
                    AnimatedVisibility(visible = !isMinimized) {
                        Column(modifier = Modifier.weight(1f)) {
                            // Messages
                            if (session != null) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    // Welcome card based on chat type
                                    val isAnalysisChat = session.chatType == ChatType.ANALYSIS
                                    if (isAnalysisChat && session.snapshotData.isNotBlank()) {
                                        // Analysis chat - show snapshot info
                                        item {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                color = MaterialTheme.colorScheme.surfaceVariant,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(10.dp),
                                                    verticalAlignment = Alignment.Top
                                                ) {
                                                    Icon(
                                                        Icons.Default.Psychology,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Column {
                                                        Text(
                                                            "Snapshot geladen",
                                                            style = MaterialTheme.typography.titleSmall,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Spacer(Modifier.height(2.dp))
                                                        Text(
                                                            "Ich habe den Snapshot \"${session.snapshotName}\" geladen. Stelle mir Fragen zur Analyse der CAN-Bus Daten.",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else if (!isAnalysisChat) {
                                        // Explore chat - show explore info
                                        item {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                color = MaterialTheme.colorScheme.surfaceVariant,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(10.dp),
                                                    verticalAlignment = Alignment.Top
                                                ) {
                                                    Icon(
                                                        Icons.Default.Explore,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Column {
                                                        Text(
                                                            "CAN Explorer",
                                                            style = MaterialTheme.typography.titleSmall,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Spacer(Modifier.height(2.dp))
                                                        Text(
                                                            "Ich kann CAN-Befehle senden, Diagnose durchführen und den Bus analysieren.",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    items(session.messages) { message ->
                                        ChatMessageBubble(message = message)
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
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    "KI denkt nach...",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Chat nicht gefunden",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            // CAN Execution Card (when commands are pending)
                            pendingCanCommands?.let { commands ->
                                CanExecutionCard(
                                    commandBlock = commands,
                                    isExecuting = isExecutingCan,
                                    executionLog = executionLog,
                                    onExecute = {
                                        scope.launch {
                                            isExecutingCan = true
                                            executionLog = emptyList()

                                            // Execute commands with live log
                                            val results = canExecutor.executeWithCallback(commands.commands) { log ->
                                                executionLog = executionLog + log
                                            }

                                            // Format results for AI
                                            val resultText = buildString {
                                                appendLine("[CAN-BEFEHL ERGEBNISSE - Erkläre diese dem User:]")
                                                appendLine()
                                                results.forEach { result ->
                                                    appendLine(result.formatForAi())
                                                }
                                            }

                                            // Send results to AI for explanation
                                            aiRepository.sendMessage(
                                                chatId = chatId,
                                                userMessage = resultText,
                                                skipSnapshot = true
                                            )

                                            pendingCanCommands = null
                                            isExecutingCan = false
                                            executionLog = emptyList()
                                        }
                                    },
                                    onCancel = {
                                        pendingCanCommands = null
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            // Error display
                            error?.let { errorMsg ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        errorMsg,
                                        modifier = Modifier.padding(8.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }

                            // Input field
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = messageInput,
                                    onValueChange = { messageInput = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = {
                                        Text(
                                            "Nachricht...",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    singleLine = false,
                                    maxLines = 3,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(
                                        onSend = {
                                            if (messageInput.isNotBlank() && !isLoading) {
                                                val msg = messageInput
                                                messageInput = ""
                                                scope.launch {
                                                    aiRepository.sendMessage(chatId, msg)
                                                }
                                            }
                                        }
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )

                                // Options dropdown button - only for ANALYSIS chats
                                val isExploreChat = session?.chatType == ChatType.EXPLORE
                                if (!isExploreChat) {
                                    Box {
                                        IconButton(
                                            onClick = { showSendOptions = true },
                                            enabled = messageInput.isNotBlank() && !isLoading,
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.MoreVert,
                                                contentDescription = "Optionen",
                                                modifier = Modifier.size(18.dp),
                                                tint = if (messageInput.isNotBlank() && !isLoading)
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showSendOptions,
                                            onDismissRequest = { showSendOptions = false }
                                        ) {
                                            // Send with current snapshot
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        "Mit aktuellem Snapshot",
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.Refresh,
                                                        null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                },
                                                onClick = {
                                                    showSendOptions = false
                                                    if (messageInput.isNotBlank() && !isLoading) {
                                                        val message = messageInput
                                                        messageInput = ""
                                                        scope.launch {
                                                            // Capture current CAN data
                                                            val snapshot = canDataRepository.captureSnapshot()

                                                            // Format snapshot as text
                                                            val sb = StringBuilder()
                                                            sb.appendLine("=== AKTUELLER SNAPSHOT ===")
                                                            sb.appendLine("Time: ${SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(snapshot.captureTime))}")
                                                            sb.appendLine("Frames: ${snapshot.frames.size}")
                                                            sb.appendLine("---")
                                                            snapshot.frames.entries.sortedBy { it.key }.forEach { (id, data) ->
                                                                val idHex = "0x${id.toString(16).uppercase().padStart(3, '0')}"
                                                                val dataHex = data.data.joinToString(" ") {
                                                                    it.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0')
                                                                }
                                                                val ascii = data.data.map { b ->
                                                                    val c = b.toInt().and(0xFF)
                                                                    if (c in 32..126) c.toChar() else '.'
                                                                }.joinToString("")
                                                                sb.appendLine("$idHex [${data.data.size}] $dataHex | $ascii")
                                                            }
                                                            sb.appendLine("=== END SNAPSHOT ===")

                                                            // Update snapshot in chat session
                                                            aiRepository.updateSnapshotData(chatId, sb.toString())

                                                            // Send message with updated snapshot
                                                            val response = aiRepository.sendMessage(chatId, message)

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
                                                }
                                            )
                                            HorizontalDivider()
                                            // DBC erstellen
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        "DBC erstellen",
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.Storage,
                                                        null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                },
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
                                            // Ohne Snapshot
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        "Ohne Snapshot",
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.QuestionAnswer,
                                                        null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                },
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
                                            // Unabhängige Antwort
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        "Unabhängige Antwort",
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.Public,
                                                        null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                },
                                                onClick = {
                                                    showSendOptions = false
                                                    if (messageInput.isNotBlank() && !isLoading) {
                                                        val message = messageInput
                                                        messageInput = ""
                                                        scope.launch {
                                                            aiRepository.sendMessage(
                                                                chatId, message,
                                                                skipSnapshot = true,
                                                                requestDbcUpdate = false,
                                                                independentMode = true
                                                            )
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                // Send button
                                IconButton(
                                    onClick = {
                                        if (messageInput.isNotBlank() && !isLoading) {
                                            val msg = messageInput
                                            messageInput = ""
                                            scope.launch {
                                                aiRepository.sendMessage(chatId, msg)
                                            }
                                        }
                                    },
                                    enabled = messageInput.isNotBlank() && !isLoading,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Send,
                                        contentDescription = "Senden",
                                        modifier = Modifier.size(18.dp),
                                        tint = if (messageInput.isNotBlank() && !isLoading)
                                            MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    )
                                }
                            }
                        }
                    }
                }

                // Resize handle (bottom-right corner) - only when not minimized
                if (!isMinimized) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(24.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    windowWidth = (windowWidth + dragAmount.x / density.density)
                                        .coerceIn(280f, configuration.screenWidthDp.toFloat() - 50f)
                                    windowHeight = (windowHeight + dragAmount.y / density.density)
                                        .coerceIn(300f, configuration.screenHeightDp.toFloat() - 100f)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = "Größe ändern",
                            modifier = Modifier
                                .size(16.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                    RoundedCornerShape(4.dp)
                                ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Snapshot Data Dialog
    if (showSnapshotDialog && session != null) {
        AlertDialog(
            onDismissRequest = { showSnapshotDialog = false },
            title = { Text("Snapshot-Daten für AI") },
            text = {
                Column {
                    Text(
                        if (session.snapshotData.contains("DELTA")) "Delta" else "Original",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        val scrollState = rememberScrollState()
                        Text(
                            session.snapshotData.ifBlank { "Keine Snapshot-Daten vorhanden" },
                            modifier = Modifier
                                .padding(8.dp)
                                .verticalScroll(scrollState),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
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

@Composable
private fun ChatMessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isUser) 12.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 12.dp
            ),
            color = if (isUser)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.secondaryContainer
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = if (isUser)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}
