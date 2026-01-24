package at.planqton.directcan.ui.screens.visualscript

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import at.planqton.directcan.data.visualscript.*
import kotlin.math.abs

/**
 * Canvas for the visual script editor with zoom, pan, and node rendering
 */
@Composable
fun VisualScriptCanvas(
    script: VisualScript,
    selectedNodeId: String?,
    connectionInProgress: ConnectionInProgress?,
    onCanvasClick: (Offset) -> Unit,
    onNodeSelect: (String) -> Unit,
    onNodeDrag: (String, Offset) -> Unit,
    onNodeDragEnd: (String) -> Unit,
    onNodeDoubleClick: (String) -> Unit,
    onOutputPortClick: (String, OutputPort) -> Unit,
    onInputPortClick: (String) -> Unit,
    onCanvasTransform: (offset: Offset, zoom: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    val connectionColor = MaterialTheme.colorScheme.outline
    val tempConnectionColor = MaterialTheme.colorScheme.primary

    var canvasOffset by remember { mutableStateOf(script.canvasOffset.toOffset()) }
    var canvasZoom by remember { mutableStateOf(script.canvasZoom) }

    // Convert dp sizes to px for calculations
    val nodeWidthPx = with(density) { 180.dp.toPx() }
    val nodeHeightPx = with(density) { 80.dp.toPx() } // Approximate

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    // Update zoom (clamped)
                    val newZoom = (canvasZoom * zoom).coerceIn(0.5f, 2f)

                    // Calculate zoom-centered offset adjustment
                    val zoomDelta = newZoom / canvasZoom
                    val offsetAdjustment = (centroid - canvasOffset) * (1 - zoomDelta)

                    canvasZoom = newZoom
                    canvasOffset = canvasOffset + pan + offsetAdjustment
                    onCanvasTransform(canvasOffset, canvasZoom)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    // Convert screen coordinates to canvas coordinates
                    val canvasPos = (offset - canvasOffset) / canvasZoom
                    onCanvasClick(canvasPos)
                }
            }
    ) {
        // Grid background
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawGrid(gridColor, canvasOffset, canvasZoom)
        }

        // Connection lines
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Draw existing connections
            script.connections.forEach { connection ->
                val fromNode = script.nodes.find { it.id == connection.fromNodeId }
                val toNode = script.nodes.find { it.id == connection.toNodeId }

                if (fromNode != null && toNode != null) {
                    val startPos = getOutputPortPosition(
                        fromNode,
                        connection.fromPort,
                        nodeWidthPx,
                        nodeHeightPx,
                        canvasOffset,
                        canvasZoom
                    )
                    val endPos = getInputPortPosition(
                        toNode,
                        nodeWidthPx,
                        nodeHeightPx,
                        canvasOffset,
                        canvasZoom
                    )

                    val portColor = when (connection.fromPort) {
                        OutputPort.TRUE_OUT -> Color(0xFF4CAF50)
                        OutputPort.FALSE_OUT -> Color(0xFFF44336)
                        OutputPort.FLOW_OUT -> connectionColor
                    }

                    drawConnectionLine(startPos, endPos, portColor)
                }
            }

            // Draw connection in progress
            connectionInProgress?.let { conn ->
                val fromNode = script.nodes.find { it.id == conn.fromNodeId }
                if (fromNode != null) {
                    val startPos = getOutputPortPosition(
                        fromNode,
                        conn.fromPort,
                        nodeWidthPx,
                        nodeHeightPx,
                        canvasOffset,
                        canvasZoom
                    )

                    drawConnectionLine(startPos, conn.currentPosition, tempConnectionColor, dashed = true)
                }
            }
        }

        // Nodes
        script.nodes.forEach { node ->
            val screenPos = node.position.toOffset() * canvasZoom + canvasOffset

            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { screenPos.x.toDp() },
                        y = with(density) { screenPos.y.toDp() }
                    )
                    .graphicsLayer {
                        scaleX = canvasZoom
                        scaleY = canvasZoom
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
            ) {
                NodeCard(
                    node = node,
                    isSelected = node.id == selectedNodeId,
                    onSelect = { onNodeSelect(node.id) },
                    onDrag = { delta ->
                        // Convert drag delta from screen to canvas coordinates
                        onNodeDrag(node.id, delta / canvasZoom)
                    },
                    onDragEnd = { onNodeDragEnd(node.id) },
                    onOutputPortClick = { port -> onOutputPortClick(node.id, port) },
                    onInputPortClick = { onInputPortClick(node.id) },
                    onDoubleClick = { onNodeDoubleClick(node.id) }
                )
            }
        }
    }
}

/**
 * Draws a grid pattern on the canvas
 */
private fun DrawScope.drawGrid(
    color: Color,
    offset: Offset,
    zoom: Float
) {
    val gridSize = 40f * zoom
    val startX = offset.x % gridSize
    val startY = offset.y % gridSize

    // Vertical lines
    var x = startX
    while (x < size.width) {
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f
        )
        x += gridSize
    }

    // Horizontal lines
    var y = startY
    while (y < size.height) {
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f
        )
        y += gridSize
    }
}

/**
 * Draws a bezier curve connection between two points
 */
private fun DrawScope.drawConnectionLine(
    start: Offset,
    end: Offset,
    color: Color,
    dashed: Boolean = false
) {
    val controlOffset = abs(end.x - start.x) * 0.5f

    val path = Path().apply {
        moveTo(start.x, start.y)
        cubicTo(
            start.x + controlOffset, start.y,
            end.x - controlOffset, end.y,
            end.x, end.y
        )
    }

    if (dashed) {
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            )
        )
    } else {
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 3f)
        )
    }

    // Draw arrow at end
    val arrowSize = 8f
    val angle = kotlin.math.atan2(end.y - (end.y - controlOffset), end.x - (end.x - controlOffset))

    val arrowPath = Path().apply {
        moveTo(end.x, end.y)
        lineTo(
            end.x - arrowSize * kotlin.math.cos(angle - Math.PI.toFloat() / 6),
            end.y - arrowSize * kotlin.math.sin(angle - Math.PI.toFloat() / 6)
        )
        moveTo(end.x, end.y)
        lineTo(
            end.x - arrowSize * kotlin.math.cos(angle + Math.PI.toFloat() / 6),
            end.y - arrowSize * kotlin.math.sin(angle + Math.PI.toFloat() / 6)
        )
    }

    drawPath(
        path = arrowPath,
        color = color,
        style = Stroke(width = 3f)
    )
}

/**
 * Calculate output port position in screen coordinates
 */
private fun getOutputPortPosition(
    node: VisualNode,
    port: OutputPort,
    nodeWidth: Float,
    nodeHeight: Float,
    canvasOffset: Offset,
    zoom: Float
): Offset {
    val nodePos = node.position.toOffset()
    val baseY = nodePos.y + nodeHeight / 2

    val portY = when (port) {
        OutputPort.FLOW_OUT -> baseY
        OutputPort.TRUE_OUT -> baseY - 12
        OutputPort.FALSE_OUT -> baseY + 12
    }

    val canvasPos = Offset(nodePos.x + nodeWidth, portY)
    return canvasPos * zoom + canvasOffset
}

/**
 * Calculate input port position in screen coordinates
 */
private fun getInputPortPosition(
    node: VisualNode,
    nodeWidth: Float,
    nodeHeight: Float,
    canvasOffset: Offset,
    zoom: Float
): Offset {
    val nodePos = node.position.toOffset()
    val canvasPos = Offset(nodePos.x, nodePos.y + nodeHeight / 2)
    return canvasPos * zoom + canvasOffset
}

/**
 * State for a connection being drawn
 */
data class ConnectionInProgress(
    val fromNodeId: String,
    val fromPort: OutputPort,
    val currentPosition: Offset
)
