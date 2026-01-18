package at.planqton.directcan.ui.screens.gemini

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.gemini.AiChatRepository
import at.planqton.directcan.data.gemini.AiProviderFactory
import at.planqton.directcan.data.gemini.AiProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToChat: (String) -> Unit,
    onOpenAiChatOverlay: ((String) -> Unit)? = null  // Opens chat as floating overlay
) {
    val aiRepository = DirectCanApplication.instance.aiChatRepository
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val currentProvider by aiRepository.currentProvider.collectAsState()
    val geminiApiKey by aiRepository.apiKey.collectAsState(initial = null)
    val openAiApiKey by aiRepository.openAiApiKey.collectAsState(initial = null)
    val anthropicApiKey by aiRepository.anthropicApiKey.collectAsState(initial = null)
    val openRouterApiKey by aiRepository.openRouterApiKey.collectAsState(initial = null)
    val openCodeZenApiKey by aiRepository.openCodeZenApiKey.collectAsState(initial = null)

    // Get current API key based on provider (OpenCode Zen doesn't need one)
    val apiKey = when (currentProvider) {
        AiProviderType.GEMINI -> geminiApiKey
        AiProviderType.OPENAI -> openAiApiKey
        AiProviderType.ANTHROPIC -> anthropicApiKey
        AiProviderType.OPENROUTER -> openRouterApiKey
        AiProviderType.OPENCODE_ZEN -> openCodeZenApiKey ?: ""  // Optional, default to empty
    }

    val selectedModel by aiRepository.selectedModel.collectAsState(initial = null)
    val availableModels by aiRepository.availableModels.collectAsState()
    val isLoading by aiRepository.isLoading.collectAsState()
    val error by aiRepository.error.collectAsState()
    val chatSessions by aiRepository.chatSessions.collectAsState()
    val deltaMode by aiRepository.deltaMode.collectAsState(initial = false)
    val aiTimeout by aiRepository.aiTimeout.collectAsState(initial = 120)

    var showProviderDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var showDeleteChatDialog by remember { mutableStateOf<String?>(null) }
    var showDeleteOldChatsDialog by remember { mutableStateOf(false) }

    // Export state
    var showExportAllDialog by remember { mutableStateOf(false) }
    var selectedExportFormat by remember { mutableStateOf(AiChatRepository.ChatExportFormat.MARKDOWN) }
    var showExportMenu by remember { mutableStateOf(false) }

    // File saver launcher for export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let {
            scope.launch {
                val content = aiRepository.exportAllChats(selectedExportFormat)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(content.toByteArray())
                    }
                }
                Toast.makeText(context, "Alle Chats exportiert", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Load models when API key is available or for OpenCode Zen (no key needed)
    LaunchedEffect(apiKey, currentProvider) {
        if (!apiKey.isNullOrBlank() || currentProvider == AiProviderType.OPENCODE_ZEN) {
            try {
                aiRepository.loadAvailableModels()
            } catch (e: Exception) {
                // Ignore - error is handled in repository
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("AI Chat") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // API Configuration Section
            item {
                Text(
                    "AI Provider",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
            }

            // Provider Selection
            item {
                val providerName = when (currentProvider) {
                    AiProviderType.GEMINI -> "Google Gemini"
                    AiProviderType.OPENAI -> "OpenAI"
                    AiProviderType.ANTHROPIC -> "Anthropic Claude"
                    AiProviderType.OPENROUTER -> "OpenRouter (Free Models!)"
                    AiProviderType.OPENCODE_ZEN -> "OpenCode Zen (Free!)"
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showProviderDialog = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Provider", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                providerName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }
            }

            // API Key (not shown for OpenCode Zen as it's optional)
            if (currentProvider != AiProviderType.OPENCODE_ZEN) {
                item {
                    val keyHint = when (currentProvider) {
                        AiProviderType.GEMINI -> "ai.google.dev"
                        AiProviderType.OPENAI -> "platform.openai.com"
                        AiProviderType.ANTHROPIC -> "console.anthropic.com"
                        AiProviderType.OPENROUTER -> "openrouter.ai (kostenlos!)"
                        AiProviderType.OPENCODE_ZEN -> "optional"
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showApiKeyDialog = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("API Key", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (apiKey.isNullOrBlank()) "Nicht konfiguriert ($keyHint)"
                                    else "****${apiKey.takeLast(4)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        }
                    }
                }
            }

            // Model Selection
            item {
                val selectedModelInfo = availableModels.find { it.id == selectedModel }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        // OpenCode Zen doesn't need API key
                        if (!apiKey.isNullOrBlank() || currentProvider == AiProviderType.OPENCODE_ZEN) {
                            showModelDialog = true
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Modell", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                selectedModelInfo?.name ?: selectedModel ?: "Nicht ausgewählt",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        }
                    }
                }
            }

            // Delta Mode Toggle
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Compress,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Delta-Komprimierung", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Nur geänderte Frames zwischen Snapshots senden",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = deltaMode,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    aiRepository.setDeltaMode(enabled)
                                }
                            }
                        )
                    }
                }
            }

            // AI Timeout Setting
            item {
                var showTimeoutMenu by remember { mutableStateOf(false) }
                val timeoutOptions = listOf(
                    30 to "30 Sekunden",
                    60 to "1 Minute",
                    120 to "2 Minuten",
                    180 to "3 Minuten",
                    300 to "5 Minuten"
                )
                val currentTimeoutLabel = timeoutOptions.find { it.first == aiTimeout }?.second ?: "$aiTimeout Sek."

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showTimeoutMenu = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("AI Timeout", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                currentTimeoutLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    }
                }

                DropdownMenu(
                    expanded = showTimeoutMenu,
                    onDismissRequest = { showTimeoutMenu = false }
                ) {
                    timeoutOptions.forEach { (seconds, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                scope.launch {
                                    aiRepository.setAiTimeout(seconds)
                                }
                                showTimeoutMenu = false
                            },
                            leadingIcon = {
                                if (seconds == aiTimeout) {
                                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                    }
                }
            }

            // Test Connection & Test Chat Buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // OpenCode Zen doesn't require API key
                    val canUseAi = (!apiKey.isNullOrBlank() || currentProvider == AiProviderType.OPENCODE_ZEN)
                        && !selectedModel.isNullOrBlank() && !isLoading

                    Button(
                        onClick = {
                            scope.launch {
                                testResult = null
                                testResult = aiRepository.testConnection()
                            }
                        },
                        enabled = canUseAi,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Testen")
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                // Create a simple test chat to verify API connection
                                val testData = "API Verbindungstest - Bitte bestätige dass alles funktioniert."
                                val chatId = aiRepository.createChatSession(
                                    snapshotName = "API-Test",
                                    snapshotData = testData
                                )
                                // Open as overlay if available
                                if (onOpenAiChatOverlay != null) {
                                    onOpenAiChatOverlay(chatId)
                                } else {
                                    onNavigateToChat(chatId)
                                }
                            }
                        },
                        enabled = canUseAi,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Chat, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Test-Chat")
                    }
                }

                testResult?.let { success ->
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (success)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (success) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (success)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (success) "Verbindung erfolgreich!" else "Verbindung fehlgeschlagen"
                            )
                        }
                    }
                }
            }

            // Error display
            error?.let { errorMsg ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(errorMsg, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            // Chat Sessions Section
            if (chatSessions.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Chat-Verlauf",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        // Delete old chats button
                        TextButton(
                            onClick = { showDeleteOldChatsDialog = true },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Aufräumen")
                        }
                        // Export all chats button
                        Box {
                            TextButton(onClick = { showExportMenu = true }) {
                                Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Exportieren")
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
                                        showExportAllDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Als Text (.txt)") },
                                    leadingIcon = { Icon(Icons.Default.TextSnippet, null) },
                                    onClick = {
                                        showExportMenu = false
                                        selectedExportFormat = AiChatRepository.ChatExportFormat.TEXT
                                        showExportAllDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Als JSON (.json)") },
                                    leadingIcon = { Icon(Icons.Default.Code, null) },
                                    onClick = {
                                        showExportMenu = false
                                        selectedExportFormat = AiChatRepository.ChatExportFormat.JSON
                                        showExportAllDialog = true
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Teilen...") },
                                    leadingIcon = { Icon(Icons.Default.Share, null) },
                                    onClick = {
                                        showExportMenu = false
                                        scope.launch {
                                            val content = aiRepository.exportAllChats(
                                                AiChatRepository.ChatExportFormat.TEXT
                                            )
                                            val sendIntent = Intent().apply {
                                                action = Intent.ACTION_SEND
                                                putExtra(Intent.EXTRA_TEXT, content)
                                                putExtra(Intent.EXTRA_SUBJECT, "DirectCAN - Alle AI Chats")
                                                type = "text/plain"
                                            }
                                            val shareIntent = Intent.createChooser(sendIntent, "Chats teilen")
                                            context.startActivity(shareIntent)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                items(chatSessions, key = { it.id }) { session ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            // Open as overlay if available, otherwise navigate to full screen
                            if (onOpenAiChatOverlay != null) {
                                onOpenAiChatOverlay(session.id)
                            } else {
                                onNavigateToChat(session.id)
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Chat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    session.snapshotName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    "${session.messages.size} Nachrichten",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { showDeleteChatDialog = session.id }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Löschen",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Info section
            item {
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Hinweis",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Verschiedene AI-Provider werden unterstützt: Google Gemini, OpenAI, " +
                                        "Anthropic, OpenRouter und OpenCode Zen. Für kostenlose Nutzung " +
                                        "empfehlen wir OpenCode Zen mit dem BigPickle-Modell - kein API-Key erforderlich!",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // API Key Dialog
    if (showApiKeyDialog) {
        var newApiKey by remember { mutableStateOf(apiKey ?: "") }
        var showKey by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text("API Key eingeben") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newApiKey,
                        onValueChange = { newApiKey = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = if (showKey)
                            VisualTransformation.None
                        else
                            PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Den API Key erhalten Sie unter ai.google.dev",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            aiRepository.setApiKey(newApiKey)
                            showApiKeyDialog = false
                        }
                    }
                ) {
                    Text("Speichern")
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // Provider Selection Dialog
    if (showProviderDialog) {
        AlertDialog(
            onDismissRequest = { showProviderDialog = false },
            title = { Text("AI Provider auswählen") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AiProviderType.values().forEach { provider ->
                        val providerInfo = AiProviderFactory.getProvider(provider)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        aiRepository.setProvider(provider)
                                        showProviderDialog = false
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = provider == currentProvider,
                                onClick = {
                                    scope.launch {
                                        aiRepository.setProvider(provider)
                                        showProviderDialog = false
                                    }
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(providerInfo.name)
                                if (provider == AiProviderType.OPENROUTER) {
                                    Text(
                                        "Kostenlose Modelle verfügbar!",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProviderDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // Model Selection Dialog
    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("Modell auswählen") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (availableModels.isEmpty()) {
                        Text(
                            "Keine Modelle gefunden. Bitte API-Key überprüfen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    availableModels.forEach { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        aiRepository.setSelectedModel(model.id)
                                        showModelDialog = false
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = model.id == selectedModel,
                                onClick = {
                                    scope.launch {
                                        aiRepository.setSelectedModel(model.id)
                                        showModelDialog = false
                                    }
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(model.name)
                                if (model.id != model.name) {
                                    Text(
                                        model.id,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // Delete Chat Confirmation Dialog
    showDeleteChatDialog?.let { chatId ->
        AlertDialog(
            onDismissRequest = { showDeleteChatDialog = null },
            title = { Text("Chat löschen?") },
            text = { Text("Möchten Sie diesen Chat wirklich löschen?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            aiRepository.deleteChatSession(chatId)
                            showDeleteChatDialog = null
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
                TextButton(onClick = { showDeleteChatDialog = null }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // Delete Old Chats Dialog
    if (showDeleteOldChatsDialog) {
        var selectedTimeFilter by remember { mutableStateOf(-1) }  // -1 = not selected
        val timeOptions = listOf(
            10 to "10 Minuten",
            30 to "30 Minuten",
            60 to "1 Stunde",
            360 to "6 Stunden",
            1440 to "24 Stunden",
            0 to "Alle löschen"
        )

        AlertDialog(
            onDismissRequest = { showDeleteOldChatsDialog = false },
            icon = {
                Icon(
                    Icons.Default.DeleteSweep,
                    null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Alte Chats löschen") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Chats löschen, deren letzte Nachricht älter ist als:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    timeOptions.forEach { (minutes, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTimeFilter = minutes }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedTimeFilter == minutes,
                                onClick = { selectedTimeFilter = minutes }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                label,
                                color = if (minutes == 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedTimeFilter >= 0) {
                            scope.launch {
                                val deleted = aiRepository.deleteOldChats(selectedTimeFilter)
                                showDeleteOldChatsDialog = false
                                Toast.makeText(
                                    context,
                                    "$deleted Chat(s) gelöscht",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    enabled = selectedTimeFilter >= 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Löschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteOldChatsDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // Export All Chats Dialog
    if (showExportAllDialog) {
        val formatName = when (selectedExportFormat) {
            AiChatRepository.ChatExportFormat.JSON -> "JSON"
            AiChatRepository.ChatExportFormat.MARKDOWN -> "Markdown"
            AiChatRepository.ChatExportFormat.TEXT -> "Text"
        }
        val filename = aiRepository.getExportFilename(null, selectedExportFormat)

        AlertDialog(
            onDismissRequest = { showExportAllDialog = false },
            icon = { Icon(Icons.Default.FileDownload, null) },
            title = { Text("Alle Chats als $formatName exportieren") },
            text = {
                Column {
                    Text("${chatSessions.size} Chat(s) werden als $formatName-Datei exportiert.")
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
                        showExportAllDialog = false
                        exportLauncher.launch(filename)
                    }
                ) {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Speichern")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportAllDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}
