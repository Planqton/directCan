package at.planqton.directcan.ui.screens.txscript

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import at.planqton.directcan.data.txscript.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptErrorLogDialog(
    executionState: ScriptExecutionState,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Fehler", "Log")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.8f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column {
                // Header
                TopAppBar(
                    title = {
                        Text(
                            if (executionState.scriptName.isNotEmpty())
                                "Script: ${executionState.scriptName}"
                            else
                                "Script Log"
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Schließen")
                        }
                    }
                )

                // Tabs
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(title)
                                    if (index == 0 && executionState.errorCount > 0) {
                                        Badge {
                                            Text(executionState.errorCount.toString())
                                        }
                                    }
                                }
                            }
                        )
                    }
                }

                // Content
                when (selectedTab) {
                    0 -> ErrorsTab(executionState.errors)
                    1 -> LogTab(executionState.debugLog)
                }
            }
        }
    }
}

@Composable
private fun ErrorsTab(errors: List<ScriptError>) {
    if (errors.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Keine Fehler",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(errors) { error ->
                ErrorItem(error)
            }
        }
    }
}

@Composable
private fun ErrorItem(error: ScriptError) {
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )

                Text(
                    text = error.locationString,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = error.type.name.replace("_", " "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun LogTab(log: List<ScriptLogEntry>) {
    val listState = rememberLazyListState()
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    // Auto-scroll to bottom
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) {
            listState.animateScrollToItem(log.size - 1)
        }
    }

    if (log.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Notes,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    "Kein Log vorhanden",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(log) { entry ->
                LogEntryItem(entry, dateFormat)
            }
        }
    }
}

@Composable
private fun LogEntryItem(
    entry: ScriptLogEntry,
    dateFormat: SimpleDateFormat
) {
    val (icon, color) = when (entry.type) {
        LogEntryType.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.primary
        LogEntryType.DEBUG -> Icons.Default.BugReport to MaterialTheme.colorScheme.secondary
        LogEntryType.WARN -> Icons.Default.Warning to MaterialTheme.colorScheme.tertiary
        LogEntryType.ERROR -> Icons.Default.Error to MaterialTheme.colorScheme.error
        LogEntryType.SEND -> Icons.Default.Send to MaterialTheme.colorScheme.primary
        LogEntryType.RECEIVE -> Icons.Default.CallReceived to MaterialTheme.colorScheme.tertiary
        LogEntryType.STATE -> Icons.Default.SwapVert to MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Timestamp
        Text(
            text = dateFormat.format(Date(entry.timestamp)),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )

        // Line number
        if (entry.line > 0) {
            Text(
                text = "L${entry.line}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.width(32.dp)
            )
        } else {
            Spacer(modifier = Modifier.width(32.dp))
        }

        // Icon
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp)
        )

        // Message
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            ),
            color = when (entry.type) {
                LogEntryType.ERROR -> MaterialTheme.colorScheme.error
                LogEntryType.WARN -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f)
        )
    }
}
