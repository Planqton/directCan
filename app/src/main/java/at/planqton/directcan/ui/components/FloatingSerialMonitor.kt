package at.planqton.directcan.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.ui.theme.LocalWindowSizeClass
import at.planqton.directcan.ui.theme.WindowWidthSizeClass
import at.planqton.directcan.ui.theme.Dimensions
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

data class SerialLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val direction: Direction,
    val message: String
) {
    enum class Direction { TX, RX }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingSerialMonitor(
    isVisible: Boolean,
    logs: List<SerialLogEntry>,
    onSendCommand: (String) -> Unit,
    onClearLogs: () -> Unit,
    onExport: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    // Responsive sizing
    val windowSizeClass = LocalWindowSizeClass.current
    val isCompact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact

    // Screen dimensions
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    // Adaptive window width
    val windowWidth = if (isCompact) {
        min(configuration.screenWidthDp - 32, 320).dp
    } else {
        380.dp
    }
    val windowWidthPx = with(density) { windowWidth.toPx() }

    // Position state - start at bottom right, adjusted for screen size
    var offsetX by remember { mutableFloatStateOf(screenWidthPx - windowWidthPx - 20f) }
    var offsetY by remember { mutableFloatStateOf(screenHeightPx - 450f) }

    // Minimized state
    var isMinimized by remember { mutableStateOf(false) }

    // Command input
    var commandInput by remember { mutableStateOf("") }

    // Auto-scroll
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut()
    ) {
        Box(
            modifier = modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceIn(0f, screenWidthPx - windowWidthPx)
                        offsetY = (offsetY + dragAmount.y).coerceIn(0f, screenHeightPx - 100f)
                    }
                }
        ) {
            Surface(
                modifier = Modifier
                    .width(if (isMinimized) 140.dp else windowWidth)
                    .then(
                        if (isMinimized) Modifier.height(48.dp)
                        else Modifier.heightIn(min = 200.dp, max = 400.dp)
                    )
                    .shadow(8.dp, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 8.dp
            ) {
                Column {
                    // Title bar - draggable
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Terminal,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Serial",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )

                            // Log count badge
                            if (!isMinimized && logs.isNotEmpty()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        "${logs.size}",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                            }

                            // Export button
                            if (!isMinimized && logs.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        val exportText = logs.joinToString("\n") { entry ->
                                            val prefix = if (entry.direction == SerialLogEntry.Direction.TX) ">" else "<"
                                            "$prefix ${entry.message}"
                                        }
                                        onExport(exportText)
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = "Export",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            // Clear button
                            if (!isMinimized) {
                                IconButton(
                                    onClick = onClearLogs,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteSweep,
                                        contentDescription = "Clear",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            // Minimize button
                            IconButton(
                                onClick = { isMinimized = !isMinimized },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    if (isMinimized) Icons.Default.OpenInFull else Icons.Default.Minimize,
                                    contentDescription = if (isMinimized) "Expand" else "Minimize",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }

                            // Close button
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    // Content - only show when not minimized
                    if (!isMinimized) {
                        // Log entries
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color(0xFF1E1E1E))
                                .padding(4.dp)
                        ) {
                            if (logs.isEmpty()) {
                                item {
                                    Text(
                                        "Warte auf Daten...",
                                        modifier = Modifier.padding(8.dp),
                                        color = Color.Gray,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            } else {
                                items(logs) { entry ->
                                    SerialLogLine(entry)
                                }
                            }
                        }

                        // Command input
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = commandInput,
                                onValueChange = { commandInput = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                placeholder = {
                                    Text(
                                        "Befehl...",
                                        fontSize = 12.sp
                                    )
                                },
                                textStyle = LocalTextStyle.current.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                ),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        if (commandInput.isNotBlank()) {
                                            onSendCommand(commandInput)
                                            commandInput = ""
                                        }
                                    }
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                )
                            )
                            Spacer(Modifier.width(4.dp))
                            FilledIconButton(
                                onClick = {
                                    if (commandInput.isNotBlank()) {
                                        onSendCommand(commandInput)
                                        commandInput = ""
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Send,
                                    contentDescription = "Send",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SerialLogLine(entry: SerialLogEntry) {
    val (prefix, color) = when (entry.direction) {
        SerialLogEntry.Direction.TX -> ">" to Color(0xFF4FC3F7)  // Light blue for TX
        SerialLogEntry.Direction.RX -> "<" to Color(0xFF81C784)  // Light green for RX
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            prefix,
            color = color,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(4.dp))
        Text(
            entry.message,
            color = Color(0xFFE0E0E0),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}
