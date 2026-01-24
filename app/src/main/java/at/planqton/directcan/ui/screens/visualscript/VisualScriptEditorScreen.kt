package at.planqton.directcan.ui.screens.visualscript

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.planqton.directcan.data.visualscript.*
import at.planqton.directcan.ui.theme.LocalWindowSizeClass
import at.planqton.directcan.ui.theme.WindowWidthSizeClass
import kotlinx.coroutines.launch

/**
 * Main editor screen for visual scripts
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualScriptEditorScreen(
    script: VisualScript,
    onScriptChange: (VisualScript) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onCompile: () -> Unit,
    onRun: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val windowSizeClass = LocalWindowSizeClass.current
    val isCompact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact

    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var showNodePalette by remember { mutableStateOf(!isCompact) }
    var connectionInProgress by remember { mutableStateOf<ConnectionInProgress?>(null) }
    var nodeToEdit by remember { mutableStateOf<VisualNode?>(null) }
    var showCompileDialog by remember { mutableStateOf(false) }
    var compileResult by remember { mutableStateOf<CompileResult?>(null) }

    val compiler = remember { VisualScriptCompiler() }

    // Node configuration dialog
    nodeToEdit?.let { node ->
        NodeConfigDialog(
            node = node,
            onDismiss = { nodeToEdit = null },
            onSave = { newConfig ->
                val updatedNodes = script.nodes.map {
                    if (it.id == node.id) it.copy(config = newConfig) else it
                }
                onScriptChange(script.copy(nodes = updatedNodes))
                nodeToEdit = null
            },
            onDelete = {
                // Remove node and its connections
                val updatedNodes = script.nodes.filter { it.id != node.id }
                val updatedConnections = script.connections.filter {
                    it.fromNodeId != node.id && it.toNodeId != node.id
                }
                onScriptChange(script.copy(nodes = updatedNodes, connections = updatedConnections))
                nodeToEdit = null
                selectedNodeId = null
            }
        )
    }

    // Compile result dialog
    if (showCompileDialog && compileResult != null) {
        CompileResultDialog(
            result = compileResult!!,
            onDismiss = {
                showCompileDialog = false
                compileResult = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(script.name)
                        Text(
                            "${script.nodes.size} Nodes, ${script.connections.size} Verbindungen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    // Toggle palette (compact only)
                    if (isCompact) {
                        IconButton(onClick = { showNodePalette = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Node hinzufügen")
                        }
                    }

                    // Compile
                    IconButton(
                        onClick = {
                            compileResult = compiler.compile(script)
                            showCompileDialog = true
                        }
                    ) {
                        Icon(Icons.Default.Code, contentDescription = "Kompilieren")
                    }

                    // Save
                    IconButton(onClick = onSave) {
                        Icon(Icons.Default.Save, contentDescription = "Speichern")
                    }

                    // Run
                    IconButton(onClick = onRun) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Ausführen")
                    }
                }
            )
        },
        floatingActionButton = {
            if (isCompact && !showNodePalette) {
                FloatingActionButton(
                    onClick = { showNodePalette = true }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Node hinzufügen")
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Node palette (sidebar or bottom sheet)
            if (!isCompact) {
                AnimatedVisibility(
                    visible = showNodePalette,
                    enter = slideInHorizontally() + fadeIn(),
                    exit = slideOutHorizontally() + fadeOut()
                ) {
                    NodePalette(
                        onNodeSelected = { nodeType ->
                            // Add node at center of visible canvas area
                            val newNode = NodeHelpers.createNode(
                                type = nodeType,
                                position = Offset(
                                    -script.canvasOffset.x / script.canvasZoom + 200,
                                    -script.canvasOffset.y / script.canvasZoom + 200
                                )
                            )
                            onScriptChange(script.copy(nodes = script.nodes + newNode))
                            selectedNodeId = newNode.id
                        },
                        modifier = Modifier.width(250.dp)
                    )
                }
            }

            // Canvas
            VisualScriptCanvas(
                script = script,
                selectedNodeId = selectedNodeId,
                connectionInProgress = connectionInProgress,
                onCanvasClick = { pos ->
                    // Deselect node if clicking on empty space
                    selectedNodeId = null

                    // Cancel connection in progress
                    connectionInProgress = null
                },
                onNodeSelect = { nodeId ->
                    selectedNodeId = nodeId

                    // Complete connection if one is in progress
                    connectionInProgress?.let { conn ->
                        if (conn.fromNodeId != nodeId) {
                            val fromNode = script.nodes.find { it.id == conn.fromNodeId }
                            val toNode = script.nodes.find { it.id == nodeId }

                            if (fromNode != null && toNode != null && NodeHelpers.isValidConnection(fromNode, toNode)) {
                                val newConnection = NodeConnection(
                                    fromNodeId = conn.fromNodeId,
                                    fromPort = conn.fromPort,
                                    toNodeId = nodeId
                                )
                                onScriptChange(script.copy(connections = script.connections + newConnection))
                            }
                        }
                        connectionInProgress = null
                    }
                },
                onNodeDrag = { nodeId, delta ->
                    val updatedNodes = script.nodes.map { node ->
                        if (node.id == nodeId) {
                            val newPos = node.position.toOffset() + delta
                            node.copy(position = SerializableOffset.fromOffset(newPos))
                        } else {
                            node
                        }
                    }
                    onScriptChange(script.copy(nodes = updatedNodes))
                },
                onNodeDragEnd = { nodeId ->
                    // Save position (already updated)
                },
                onNodeDoubleClick = { nodeId ->
                    nodeToEdit = script.nodes.find { it.id == nodeId }
                },
                onOutputPortClick = { nodeId, port ->
                    // Start new connection
                    connectionInProgress = ConnectionInProgress(
                        fromNodeId = nodeId,
                        fromPort = port,
                        currentPosition = Offset.Zero // Will be updated by pointer position
                    )
                },
                onInputPortClick = { nodeId ->
                    // Complete connection if one is in progress
                    connectionInProgress?.let { conn ->
                        if (conn.fromNodeId != nodeId) {
                            val fromNode = script.nodes.find { it.id == conn.fromNodeId }
                            val toNode = script.nodes.find { it.id == nodeId }

                            if (fromNode != null && toNode != null && NodeHelpers.isValidConnection(fromNode, toNode)) {
                                val newConnection = NodeConnection(
                                    fromNodeId = conn.fromNodeId,
                                    fromPort = conn.fromPort,
                                    toNodeId = nodeId
                                )
                                onScriptChange(script.copy(connections = script.connections + newConnection))
                            } else {
                                Toast.makeText(context, "Ungültige Verbindung", Toast.LENGTH_SHORT).show()
                            }
                        }
                        connectionInProgress = null
                    }
                },
                onCanvasTransform = { offset, zoom ->
                    onScriptChange(
                        script.copy(
                            canvasOffset = SerializableOffset.fromOffset(offset),
                            canvasZoom = zoom
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }

        // Bottom sheet palette for compact screens
        if (isCompact && showNodePalette) {
            CompactNodePalette(
                onNodeSelected = { nodeType ->
                    val newNode = NodeHelpers.createNode(
                        type = nodeType,
                        position = Offset(
                            -script.canvasOffset.x / script.canvasZoom + 100,
                            -script.canvasOffset.y / script.canvasZoom + 100
                        )
                    )
                    onScriptChange(script.copy(nodes = script.nodes + newNode))
                    selectedNodeId = newNode.id
                },
                onDismiss = { showNodePalette = false }
            )
        }
    }
}

/**
 * Dialog showing compile results
 */
@Composable
private fun CompileResultDialog(
    result: CompileResult,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (result.success)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
                Text(if (result.success) "Kompilierung erfolgreich" else "Kompilierung fehlgeschlagen")
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Errors
                if (result.errors.isNotEmpty()) {
                    Text(
                        "Fehler:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    result.errors.forEach { error ->
                        Text(
                            "• ${error.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Warnings
                if (result.warnings.isNotEmpty()) {
                    Text(
                        "Warnungen:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    result.warnings.forEach { warning ->
                        Text(
                            "• ${warning.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                // Generated code preview
                if (result.success && result.code.isNotBlank()) {
                    Text(
                        "Generierter TxScript Code:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = result.code.take(500) + if (result.code.length > 500) "\n..." else "",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
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
