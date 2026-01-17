package at.planqton.directcan.ui.screens.txscript

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.txscript.ScriptError
import at.planqton.directcan.data.txscript.TxScript
import at.planqton.directcan.data.txscript.TxScriptFileInfo
import at.planqton.directcan.data.txscript.parser.parseScript
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditorScreen(
    scriptPath: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = DirectCanApplication.instance.txScriptRepository

    var script by remember { mutableStateOf<TxScript?>(null) }
    var content by remember { mutableStateOf("") }
    var originalContent by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf<List<ScriptError>>(emptyList()) }
    var showErrorsDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    val hasChanges = content != originalContent
    val hasErrors = errors.isNotEmpty()

    // Load script
    LaunchedEffect(scriptPath) {
        val file = File(scriptPath)
        val info = TxScriptFileInfo(
            id = "",
            name = file.nameWithoutExtension,
            fileName = file.name,
            path = scriptPath,
            size = file.length(),
            lastModified = file.lastModified()
        )

        repository.loadScript(info).fold(
            onSuccess = { loadedScript ->
                script = loadedScript
                content = loadedScript.content
                originalContent = loadedScript.content
                // Initial validation
                val result = parseScript(loadedScript.content)
                errors = result.errors
            },
            onFailure = { error ->
                Toast.makeText(context, "Fehler beim Laden: ${error.message}", Toast.LENGTH_LONG).show()
                onNavigateBack()
            }
        )
        isLoading = false
    }

    // Handle back press
    BackHandler {
        if (hasChanges) {
            showUnsavedDialog = true
        } else {
            onNavigateBack()
        }
    }

    fun save() {
        script?.let { s ->
            scope.launch {
                repository.saveScript(s.withUpdatedContent(content)).fold(
                    onSuccess = {
                        originalContent = content
                        Toast.makeText(context, "Gespeichert", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { error ->
                        Toast.makeText(context, "Speichern fehlgeschlagen: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    fun validate() {
        val result = parseScript(content)
        errors = result.errors
        if (result.isValid) {
            Toast.makeText(context, "Script ist gültig", Toast.LENGTH_SHORT).show()
        } else {
            showErrorsDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            script?.name ?: "Script Editor",
                            maxLines = 1
                        )
                        if (hasChanges) {
                            Text(
                                "Ungespeicherte Änderungen",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasChanges) {
                            showUnsavedDialog = true
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    // Validate button
                    IconButton(onClick = { validate() }) {
                        Icon(
                            if (hasErrors) Icons.Default.ErrorOutline else Icons.Default.CheckCircleOutline,
                            contentDescription = "Validieren",
                            tint = if (hasErrors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }

                    // Save button
                    IconButton(
                        onClick = { save() },
                        enabled = hasChanges
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "Speichern",
                            tint = if (hasChanges) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Error bar
                if (hasErrors) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "${errors.size} Fehler gefunden",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { showErrorsDialog = true }) {
                                Text("Details")
                            }
                        }
                    }
                }

                // Editor
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                ) {
                    val verticalScrollState = rememberScrollState()
                    val horizontalScrollState = rememberScrollState()

                    Row(modifier = Modifier.fillMaxSize()) {
                        // Line numbers
                        val lineCount = content.lines().size
                        Column(
                            modifier = Modifier
                                .width(48.dp)
                                .fillMaxHeight()
                                .verticalScroll(verticalScrollState)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            for (i in 1..maxOf(lineCount, 1)) {
                                val hasError = errors.any { it.line == i }
                                Text(
                                    text = i.toString(),
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp,
                                        color = if (hasError)
                                            MaterialTheme.colorScheme.error
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    ),
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .height(20.dp)
                                )
                            }
                        }

                        // Code editor
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(verticalScrollState)
                                .horizontalScroll(horizontalScrollState)
                        ) {
                            BasicTextField(
                                value = content,
                                onValueChange = {
                                    content = it
                                    // Re-validate on change
                                    val result = parseScript(it)
                                    errors = result.errors
                                },
                                textStyle = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 20.sp
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 500.dp)
                                    .padding(8.dp),
                                decorationBox = { innerTextField ->
                                    if (content.isEmpty()) {
                                        Text(
                                            text = "// Script hier eingeben...",
                                            style = TextStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                            )
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Unsaved changes dialog
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("Ungespeicherte Änderungen") },
            text = { Text("Möchtest du die Änderungen speichern?") },
            confirmButton = {
                TextButton(onClick = {
                    save()
                    showUnsavedDialog = false
                    onNavigateBack()
                }) {
                    Text("Speichern")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        onNavigateBack()
                    }) {
                        Text("Verwerfen")
                    }
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text("Abbrechen")
                    }
                }
            }
        )
    }

    // Errors dialog
    if (showErrorsDialog) {
        ScriptErrorsDialog(
            errors = errors,
            onDismiss = { showErrorsDialog = false }
        )
    }
}

@Composable
private fun ScriptErrorsDialog(
    errors: List<ScriptError>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Script Fehler") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                errors.forEach { error ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = error.locationString,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = error.type.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = error.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Schließen")
            }
        }
    )
}
