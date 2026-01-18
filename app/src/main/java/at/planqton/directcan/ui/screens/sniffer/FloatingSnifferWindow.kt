package at.planqton.directcan.ui.screens.sniffer

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.data.can.CanDataRepository
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingSnifferWindow(
    canId: Long,
    snifferData: CanDataRepository.SnifferFrameData?,
    initialOffsetX: Float,
    initialOffsetY: Float,
    highlightDurationMs: Long = 500L,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    var offsetX by remember { mutableFloatStateOf(initialOffsetX) }
    var offsetY by remember { mutableFloatStateOf(initialOffsetY) }
    var isMinimized by remember { mutableStateOf(false) }
    var viewBits by remember { mutableStateOf(false) }
    var showAscii by remember { mutableStateOf(true) }

    // Refresh trigger for color updates
    var refreshTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(100)
            refreshTrigger++
        }
    }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
    ) {
        Surface(
            modifier = Modifier
                .width(if (isMinimized) 140.dp else if (viewBits) 500.dp else 380.dp)
                .then(if (isMinimized) Modifier.height(40.dp) else Modifier.wrapContentHeight())
                .shadow(8.dp, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column {
                // Title bar (draggable)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                offsetX = (offsetX + dragAmount.x).coerceIn(0f, screenWidthPx - 140f)
                                offsetY = (offsetY + dragAmount.y).coerceIn(0f, screenHeightPx - 40f)
                            }
                        },
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "0x${canId.toString(16).uppercase()}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            if (!isMinimized && snifferData != null) {
                                Text(
                                    " (${snifferData.updateCount})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Row {
                            IconButton(
                                onClick = { isMinimized = !isMinimized },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    if (isMinimized) Icons.Default.OpenInFull else Icons.Default.Minimize,
                                    contentDescription = if (isMinimized) "Maximieren" else "Minimieren",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
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
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }

                // Content (when not minimized)
                AnimatedVisibility(visible = !isMinimized) {
                    key(refreshTrigger) {
                        if (snifferData == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Keine Daten",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Column(modifier = Modifier.padding(8.dp)) {
                                // Options row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilterChip(
                                        selected = viewBits,
                                        onClick = { viewBits = !viewBits },
                                        label = { Text("Bits", fontSize = 10.sp) },
                                        modifier = Modifier.height(24.dp)
                                    )
                                    FilterChip(
                                        selected = showAscii,
                                        onClick = { showAscii = !showAscii },
                                        label = { Text("ASCII", fontSize = 10.sp) },
                                        modifier = Modifier.height(24.dp)
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "Last: ${(System.currentTimeMillis() - snifferData.lastUpdate)}ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                // Data display
                                SnifferDataDisplay(
                                    data = snifferData,
                                    viewBits = viewBits,
                                    showAscii = showAscii,
                                    highlightDurationMs = highlightDurationMs
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
private fun SnifferDataDisplay(
    data: CanDataRepository.SnifferFrameData,
    viewBits: Boolean,
    showAscii: Boolean,
    highlightDurationMs: Long
) {
    val now = System.currentTimeMillis()
    val maxBytes = minOf(data.currentData.size, 8)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Hex/Byte row header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Byte:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(40.dp)
            )
            for (i in 0 until maxBytes) {
                Text(
                    "$i",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(if (viewBits) 52.dp else 24.dp)
                )
            }
        }

        // Data row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (viewBits) "Bits:" else "Hex:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(40.dp)
            )

            if (viewBits) {
                // Bit view
                for (byteIdx in 0 until maxBytes) {
                    val byteVal = data.currentData.getOrNull(byteIdx)?.toInt()?.and(0xFF) ?: 0
                    val prevByteVal = data.previousData.getOrNull(byteIdx)?.toInt()?.and(0xFF) ?: 0
                    val changeTime = if (byteIdx in data.byteChangeTime.indices) data.byteChangeTime[byteIdx] else 0L
                    val timeSinceChange = now - changeTime

                    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        for (bitIdx in 7 downTo 0) {
                            val bitValue = (byteVal shr bitIdx) and 1
                            val prevBitValue = (prevByteVal shr bitIdx) and 1
                            val notchIdx = byteIdx * 8 + (7 - bitIdx)
                            val isNotched = notchIdx in data.notchedBits.indices && data.notchedBits[notchIdx]

                            val bitColor = when {
                                timeSinceChange < highlightDurationMs && bitValue > prevBitValue -> ColorIncrease
                                timeSinceChange < highlightDurationMs && bitValue < prevBitValue -> ColorDecrease
                                isNotched -> ColorNotched
                                bitValue == 1 -> Color.White
                                else -> Color.DarkGray
                            }

                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(bitColor, RoundedCornerShape(1.dp))
                                    .border(0.5.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(1.dp))
                            )
                        }
                    }
                    Spacer(Modifier.width(2.dp))
                }
            } else {
                // Byte view
                for (i in 0 until maxBytes) {
                    val byteVal = data.currentData.getOrNull(i)?.toInt()?.and(0xFF) ?: 0
                    val changeTime = if (i in data.byteChangeTime.indices) data.byteChangeTime[i] else 0L
                    val changeDir = if (i in data.byteChangeDir.indices) data.byteChangeDir[i] else 0
                    val timeSinceChange = now - changeTime

                    val bgColor = when {
                        timeSinceChange < highlightDurationMs && changeDir > 0 -> ColorIncrease.copy(alpha = 0.8f)
                        timeSinceChange < highlightDurationMs && changeDir < 0 -> ColorDecrease.copy(alpha = 0.8f)
                        else -> Color.Transparent
                    }

                    Box(
                        modifier = Modifier
                            .background(bgColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            byteVal.toString(16).uppercase().padStart(2, '0'),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // ASCII row (optional)
        if (showAscii) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "ASCII:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp)
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        data.currentData.take(8).map { b ->
                            val c = b.toInt() and 0xFF
                            if (c in 32..126) c.toChar() else '.'
                        }.joinToString(""),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    )
                }
            }
        }

        // Change info
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(ColorIncrease, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(4.dp))
                Text("Increase", style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(ColorDecrease, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(4.dp))
                Text("Decrease", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "Updates: ${data.updateCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Floating Sniffer Overlay - shows all sniffer data in a single draggable window
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingSnifferOverlay(
    snifferData: List<CanDataRepository.SnifferFrameData>,
    highlightDurationMs: Long = 500L,
    portColors: Map<Int, Color> = emptyMap(),
    showPortColumn: Boolean = false,
    initialOffsetX: Float,
    initialOffsetY: Float,
    onClose: () -> Unit,
    onOpenAnalyse: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    var offsetX by remember { mutableFloatStateOf(initialOffsetX) }
    var offsetY by remember { mutableFloatStateOf(initialOffsetY) }
    var isMinimized by remember { mutableStateOf(false) }
    var viewBits by remember { mutableStateOf(false) }

    // Resizable window dimensions (in dp)
    var windowWidth by remember { mutableFloatStateOf(550f) }
    var windowHeight by remember { mutableFloatStateOf(350f) }

    // Refresh trigger for color updates
    var refreshTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(100)
            refreshTrigger++
        }
    }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
    ) {
        Surface(
            modifier = Modifier
                .width(if (isMinimized) 120.dp else windowWidth.dp)
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
                                offsetX = (offsetX + dragAmount.x).coerceIn(0f, screenWidthPx - 120f)
                                offsetY = (offsetY + dragAmount.y).coerceIn(0f, screenHeightPx - 40f)
                            }
                        },
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.RemoveRedEye,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Sniffer",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            if (!isMinimized) {
                                Text(
                                    " (${snifferData.size} IDs)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Row {
                            IconButton(
                                onClick = { isMinimized = !isMinimized },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    if (isMinimized) Icons.Default.OpenInFull else Icons.Default.Minimize,
                                    contentDescription = if (isMinimized) "Maximieren" else "Minimieren",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
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
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }

                // Content (when not minimized)
                AnimatedVisibility(visible = !isMinimized) {
                    key(refreshTrigger) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            // Options row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = viewBits,
                                    onClick = { viewBits = !viewBits },
                                    label = { Text("Bits", fontSize = 10.sp) },
                                    modifier = Modifier.height(24.dp)
                                )
                            }

                            Spacer(Modifier.height(4.dp))

                            // Header row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (showPortColumn) {
                                    Text("IO", Modifier.width(24.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                Text("ID", Modifier.width(60.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text("Cnt", Modifier.width(50.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text(if (viewBits) "Data (Bits)" else "Data (Hex)", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text("ASCII", Modifier.width(70.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Text("", Modifier.width(28.dp)) // Analyse button
                            }

                            HorizontalDivider()

                            // Data list
                            if (snifferData.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(100.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Keine Frames",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    items(
                                        items = snifferData,
                                        key = { frame -> "${frame.id}_${frame.port}" }
                                    ) { data ->
                                        SnifferRowCompact(
                                            data = data,
                                            viewBits = viewBits,
                                            highlightDurationMs = highlightDurationMs,
                                            showPortColumn = showPortColumn,
                                            portColor = portColors[data.port] ?: Color.Gray,
                                            onAnalyse = { onOpenAnalyse(data.id) }
                                        )
                                    }
                                }
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
                                    .coerceIn(300f, configuration.screenWidthDp.toFloat() - 50f)
                                windowHeight = (windowHeight + dragAmount.y / density.density)
                                    .coerceIn(150f, configuration.screenHeightDp.toFloat() - 100f)
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
}

@Composable
private fun SnifferRowCompact(
    data: CanDataRepository.SnifferFrameData,
    viewBits: Boolean,
    highlightDurationMs: Long,
    showPortColumn: Boolean,
    portColor: Color,
    onAnalyse: () -> Unit
) {
    val now = System.currentTimeMillis()
    val maxBytes = minOf(data.currentData.size, 8)
    val isStale = now - data.lastUpdate > 1000

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Port
        if (showPortColumn) {
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .background(portColor.copy(alpha = 0.2f), RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${data.port}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = portColor
                )
            }
        }

        // ID
        Text(
            "0x${data.id.toString(16).uppercase().padStart(3, '0')}",
            modifier = Modifier.width(60.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (isStale) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary
        )

        // Count
        Text(
            "${data.updateCount}",
            modifier = Modifier.width(50.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )

        // Data
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (viewBits) {
                // Bit view - compact
                for (byteIdx in 0 until maxBytes) {
                    val byteVal = data.currentData.getOrNull(byteIdx)?.toInt()?.and(0xFF) ?: 0
                    val prevByteVal = data.previousData.getOrNull(byteIdx)?.toInt()?.and(0xFF) ?: 0
                    val changeTime = if (byteIdx in data.byteChangeTime.indices) data.byteChangeTime[byteIdx] else 0L
                    val timeSinceChange = now - changeTime

                    Row(horizontalArrangement = Arrangement.spacedBy(0.5.dp)) {
                        for (bitIdx in 7 downTo 0) {
                            val bitValue = (byteVal shr bitIdx) and 1
                            val prevBitValue = (prevByteVal shr bitIdx) and 1
                            val notchIdx = byteIdx * 8 + (7 - bitIdx)
                            val isNotched = notchIdx in data.notchedBits.indices && data.notchedBits[notchIdx]

                            val bitColor = when {
                                timeSinceChange < highlightDurationMs && bitValue > prevBitValue -> ColorIncrease
                                timeSinceChange < highlightDurationMs && bitValue < prevBitValue -> ColorDecrease
                                isNotched -> ColorNotched
                                bitValue == 1 -> Color.White
                                else -> Color.DarkGray
                            }

                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .background(bitColor, RoundedCornerShape(1.dp))
                            )
                        }
                    }
                    Spacer(Modifier.width(1.dp))
                }
            } else {
                // Byte view
                for (i in 0 until maxBytes) {
                    val byteVal = data.currentData.getOrNull(i)?.toInt()?.and(0xFF) ?: 0
                    val changeTime = if (i in data.byteChangeTime.indices) data.byteChangeTime[i] else 0L
                    val changeDir = if (i in data.byteChangeDir.indices) data.byteChangeDir[i] else 0
                    val timeSinceChange = now - changeTime

                    val bgColor = when {
                        timeSinceChange < highlightDurationMs && changeDir > 0 -> ColorIncrease.copy(alpha = 0.8f)
                        timeSinceChange < highlightDurationMs && changeDir < 0 -> ColorDecrease.copy(alpha = 0.8f)
                        else -> Color.Transparent
                    }

                    Box(
                        modifier = Modifier
                            .background(bgColor, RoundedCornerShape(2.dp))
                            .padding(horizontal = 2.dp)
                    ) {
                        Text(
                            byteVal.toString(16).uppercase().padStart(2, '0'),
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (isStale) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // ASCII
        Text(
            data.currentData.take(8).map { b ->
                val c = b.toInt() and 0xFF
                if (c in 32..126) c.toChar() else '.'
            }.joinToString(""),
            modifier = Modifier.width(70.dp),
            style = MaterialTheme.typography.bodySmall,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )

        // Analyse button
        IconButton(
            onClick = onAnalyse,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                Icons.Default.Analytics,
                contentDescription = "Analysieren",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
