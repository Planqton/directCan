package at.planqton.directcan.ui.screens.signals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.can.CanDataRepository
import at.planqton.directcan.data.usb.UsbSerialManager
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries

// Chart colors for different signals
private val chartColors = listOf(
    Color(0xFF2196F3), // Blue
    Color(0xFF4CAF50), // Green
    Color(0xFFF44336), // Red
    Color(0xFFFF9800), // Orange
    Color(0xFF9C27B0), // Purple
    Color(0xFF00BCD4), // Cyan
    Color(0xFFFFEB3B), // Yellow
    Color(0xFF795548), // Brown
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalGraphScreen() {
    val canDataRepository = DirectCanApplication.instance.canDataRepository
    val dbcRepository = DirectCanApplication.instance.dbcRepository
    val usbManager = DirectCanApplication.instance.usbSerialManager

    val connectionState by usbManager.connectionState.collectAsState()
    val isLogging by canDataRepository.isLogging.collectAsState()
    val activeDbc by dbcRepository.activeDbcFile.collectAsState()
    val signalValues by canDataRepository.signalValues.collectAsState()

    // Selected signals to graph
    var selectedSignals by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var autoUpdate by remember { mutableStateOf(true) }

    // Chart model producer
    val modelProducer = remember { CartesianChartModelProducer() }

    // Refresh trigger for chart updates
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(200)
            if (autoUpdate) {
                refreshTrigger++
            }
        }
    }

    // Update chart data when selected signals or data changes
    LaunchedEffect(selectedSignals, refreshTrigger) {
        if (selectedSignals.isEmpty()) return@LaunchedEffect

        val seriesList = selectedSignals.mapNotNull { signalKey ->
            val samples = canDataRepository.getSignalHistory(signalKey)
            if (samples.isEmpty()) null
            else samples.map { it.value.toFloat() }
        }

        if (seriesList.isNotEmpty() && seriesList.all { it.isNotEmpty() }) {
            modelProducer.runTransaction {
                lineSeries {
                    seriesList.forEach { values ->
                        series(values)
                    }
                }
            }
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Main content - Chart area
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            // Header with selected signals info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ShowChart, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Signal Graph",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${selectedSignals.size} Signale ausgewählt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            when {
                connectionState != UsbSerialManager.ConnectionState.CONNECTED -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.UsbOff, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Text("Kein Gerät verbunden")
                        }
                    }
                }
                activeDbc == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Storage, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Text("Keine DBC-Datei geladen")
                        }
                    }
                }
                selectedSignals.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.TouchApp, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Text("Wähle Signale zum Graphen")
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Klicke auf Signale in der Seitenleiste",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Legend
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            selectedSignals.forEachIndexed { index, signalKey ->
                                val color = chartColors[index % chartColors.size]
                                val signalValue = signalValues[signalKey]
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .background(color, MaterialTheme.shapes.small)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        signalValue?.signalName ?: signalKey.substringAfter("_"),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    signalValue?.let {
                                        Text(
                                            " (${it.formattedValue})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // Chart
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        ) {
                            CartesianChartHost(
                                chart = rememberCartesianChart(
                                    rememberLineCartesianLayer(),
                                    startAxis = VerticalAxis.rememberStart(),
                                    bottomAxis = HorizontalAxis.rememberBottom()
                                ),
                                modelProducer = modelProducer,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }

        // Sidebar - Signal selection
        Surface(
            modifier = Modifier.width(220.dp).fillMaxHeight(),
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                // Control buttons
                Button(
                    onClick = {
                        if (isLogging) {
                            usbManager.stopLogging()
                            canDataRepository.setLoggingActive(false)
                        } else {
                            usbManager.startLogging()
                            canDataRepository.setLoggingActive(true)
                        }
                    },
                    enabled = connectionState == UsbSerialManager.ConnectionState.CONNECTED,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLogging) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isLogging) "Stop" else "Start")
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            canDataRepository.clearSignalHistory()
                            selectedSignals = emptySet()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Text("Clear", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { autoUpdate = !autoUpdate },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp),
                        colors = if (autoUpdate) ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) else ButtonDefaults.outlinedButtonColors()
                    ) {
                        Text(if (autoUpdate) "Auto" else "Pause", fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // Signal selection header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Signale:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    if (selectedSignals.isNotEmpty()) {
                        TextButton(
                            onClick = { selectedSignals = emptySet() },
                            contentPadding = PaddingValues(4.dp)
                        ) {
                            Text("Alle abwählen", fontSize = 10.sp)
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Signal list
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
                    shape = MaterialTheme.shapes.small
                ) {
                    if (signalValues.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "Keine Signale",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.padding(4.dp)) {
                            // Group by message
                            val groupedSignals = signalValues.values.groupBy { it.messageId }

                            groupedSignals.forEach { (messageId, signals) ->
                                val firstSignal = signals.first()

                                // Message header
                                item(key = "header_$messageId") {
                                    Text(
                                        "${firstSignal.messageIdHex} ${firstSignal.messageName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                }

                                // Signals
                                items(signals, key = { it.signalKey }) { signal ->
                                    val isSelected = signal.signalKey in selectedSignals
                                    val colorIndex = selectedSignals.toList().indexOf(signal.signalKey)

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedSignals = if (isSelected) {
                                                    selectedSignals - signal.signalKey
                                                } else if (selectedSignals.size < chartColors.size) {
                                                    selectedSignals + signal.signalKey
                                                } else {
                                                    selectedSignals // Max reached
                                                }
                                            }
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                                else Color.Transparent
                                            )
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isSelected && colorIndex >= 0) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(chartColors[colorIndex], MaterialTheme.shapes.extraSmall)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = null,
                                            modifier = Modifier.size(18.dp),
                                            enabled = isSelected || selectedSignals.size < chartColors.size
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            signal.signalName,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 11.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Info
                Text(
                    "Max. ${chartColors.size} Signale gleichzeitig",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
