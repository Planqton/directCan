package at.planqton.directcan.ui.screens.simulator

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.data.device.DeviceType
import at.planqton.directcan.data.device.SimulatorDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulatorScreen() {
    val deviceManager = DirectCanApplication.instance.deviceManager

    // Find the simulator device from configured devices
    val simulatorDevice = remember {
        deviceManager.devices.value.filterIsInstance<SimulatorDevice>().firstOrNull()
    }

    // Fallback to creating a temporary simulator if none configured
    val simulator = simulatorDevice?.simulator ?: remember {
        at.planqton.directcan.data.can.CanSimulator()
    }

    val engineRunning by simulator.engineRunning.collectAsState()
    val rpm by simulator.rpm.collectAsState()
    val speed by simulator.speed.collectAsState()
    val engineTemp by simulator.engineTemp.collectAsState()
    val outsideTemp by simulator.outsideTemp.collectAsState()
    val fuelLevel by simulator.fuelLevel.collectAsState()
    val throttle by simulator.throttlePosition.collectAsState()
    val brakePressed by simulator.brakePressed.collectAsState()
    val steeringAngle by simulator.steeringAngle.collectAsState()
    val batteryVoltage by simulator.batteryVoltage.collectAsState()
    val odometer by simulator.odometer.collectAsState()
    val gear by simulator.gear.collectAsState()
    val vin by simulator.vin.collectAsState()

    // Lights
    val leftBlinker by simulator.leftBlinker.collectAsState()
    val rightBlinker by simulator.rightBlinker.collectAsState()
    val hazardLights by simulator.hazardLights.collectAsState()
    val headlights by simulator.headlightsOn.collectAsState()
    val highBeam by simulator.highBeamOn.collectAsState()
    val fogLights by simulator.fogLightsOn.collectAsState()

    // Doors
    val driverDoor by simulator.driverDoorOpen.collectAsState()
    val passengerDoor by simulator.passengerDoorOpen.collectAsState()
    val trunk by simulator.trunkOpen.collectAsState()
    val hood by simulator.hoodOpen.collectAsState()

    // Steering buttons
    val horn by simulator.hornPressed.collectAsState()
    val cruiseControl by simulator.cruiseControlOn.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Engine & Speed Section
        SectionCard(title = "Motor & Geschwindigkeit", icon = Icons.Default.Speed) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Motor läuft")
                Switch(
                    checked = engineRunning,
                    onCheckedChange = { simulator.setEngineRunning(it) }
                )
            }

            SliderWithLabel(
                label = "Drehzahl",
                value = rpm.toFloat(),
                onValueChange = { simulator.setRpm(it.toDouble()) },
                valueRange = 0f..8000f,
                unit = "rpm"
            )

            SliderWithLabel(
                label = "Geschwindigkeit",
                value = speed.toFloat(),
                onValueChange = { simulator.setSpeed(it.toDouble()) },
                valueRange = 0f..300f,
                unit = "km/h"
            )

            SliderWithLabel(
                label = "Gas",
                value = throttle.toFloat(),
                onValueChange = { simulator.setThrottlePosition(it.toDouble()) },
                valueRange = 0f..100f,
                unit = "%"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Bremse")
                Switch(
                    checked = brakePressed,
                    onCheckedChange = { simulator.setBrakePressed(it) }
                )
            }

            // Gear selector
            Text("Gang: $gear", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("P" to 0, "R" to 7, "N" to 8, "1" to 1, "2" to 2, "3" to 3, "4" to 4, "5" to 5, "6" to 6).forEach { (label, g) ->
                    FilterChip(
                        selected = gear == g,
                        onClick = { simulator.setGear(g) },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Temperature Section
        SectionCard(title = "Temperaturen", icon = Icons.Default.Thermostat) {
            SliderWithLabel(
                label = "Motortemperatur",
                value = engineTemp.toFloat(),
                onValueChange = { simulator.setEngineTemp(it.toDouble()) },
                valueRange = -40f..150f,
                unit = "°C"
            )

            SliderWithLabel(
                label = "Außentemperatur",
                value = outsideTemp.toFloat(),
                onValueChange = { simulator.setOutsideTemp(it.toDouble()) },
                valueRange = -40f..60f,
                unit = "°C"
            )
        }

        // Fuel & Battery Section
        SectionCard(title = "Tank & Batterie", icon = Icons.Default.LocalGasStation) {
            SliderWithLabel(
                label = "Tankfüllung",
                value = fuelLevel.toFloat(),
                onValueChange = { simulator.setFuelLevel(it.toDouble()) },
                valueRange = 0f..100f,
                unit = "%"
            )

            SliderWithLabel(
                label = "Batteriespannung",
                value = batteryVoltage.toFloat(),
                onValueChange = { simulator.setBatteryVoltage(it.toDouble()) },
                valueRange = 0f..20f,
                unit = "V"
            )
        }

        // Steering Section
        SectionCard(title = "Lenkung", icon = Icons.Default.TripOrigin) {
            SliderWithLabel(
                label = "Lenkwinkel",
                value = steeringAngle.toFloat(),
                onValueChange = { simulator.setSteeringAngle(it.toDouble()) },
                valueRange = -720f..720f,
                unit = "°"
            )
        }

        // Lights Section
        SectionCard(title = "Beleuchtung", icon = Icons.Default.Lightbulb) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToggleChip(
                    label = "Links",
                    checked = leftBlinker,
                    onCheckedChange = { simulator.setLeftBlinker(it) },
                    modifier = Modifier.weight(1f)
                )
                ToggleChip(
                    label = "Rechts",
                    checked = rightBlinker,
                    onCheckedChange = { simulator.setRightBlinker(it) },
                    modifier = Modifier.weight(1f)
                )
                ToggleChip(
                    label = "Warnblinker",
                    checked = hazardLights,
                    onCheckedChange = { simulator.setHazardLights(it) },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToggleChip(
                    label = "Abblend",
                    checked = headlights,
                    onCheckedChange = { simulator.setHeadlights(it) },
                    modifier = Modifier.weight(1f)
                )
                ToggleChip(
                    label = "Fernlicht",
                    checked = highBeam,
                    onCheckedChange = { simulator.setHighBeam(it) },
                    modifier = Modifier.weight(1f)
                )
                ToggleChip(
                    label = "Nebel",
                    checked = fogLights,
                    onCheckedChange = { simulator.setFogLights(it) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Doors Section
        SectionCard(title = "Türen", icon = Icons.Default.DoorFront) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToggleChip(
                    label = "Fahrer",
                    checked = driverDoor,
                    onCheckedChange = { simulator.setDriverDoorOpen(it) },
                    modifier = Modifier.weight(1f)
                )
                ToggleChip(
                    label = "Beifahrer",
                    checked = passengerDoor,
                    onCheckedChange = { simulator.setPassengerDoorOpen(it) },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToggleChip(
                    label = "Kofferraum",
                    checked = trunk,
                    onCheckedChange = { simulator.setTrunkOpen(it) },
                    modifier = Modifier.weight(1f)
                )
                ToggleChip(
                    label = "Motorhaube",
                    checked = hood,
                    onCheckedChange = { simulator.setHoodOpen(it) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Controls Section
        SectionCard(title = "Lenkradtasten", icon = Icons.Default.SportsEsports) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToggleChip(
                    label = "Hupe",
                    checked = horn,
                    onCheckedChange = { simulator.setHornPressed(it) },
                    modifier = Modifier.weight(1f)
                )
                ToggleChip(
                    label = "Tempomat",
                    checked = cruiseControl,
                    onCheckedChange = { simulator.setCruiseControl(it) },
                    modifier = Modifier.weight(1f)
                )
            }

            Text("Mediensteuerung", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { simulator.setPrevTrack(true); simulator.setPrevTrack(false) }
                ) {
                    Icon(Icons.Default.SkipPrevious, "Zurück")
                }
                IconButton(
                    onClick = { simulator.setVolumeDown(true); simulator.setVolumeDown(false) }
                ) {
                    Icon(Icons.Default.VolumeDown, "Leiser")
                }
                IconButton(
                    onClick = { simulator.setVolumeUp(true); simulator.setVolumeUp(false) }
                ) {
                    Icon(Icons.Default.VolumeUp, "Lauter")
                }
                IconButton(
                    onClick = { simulator.setNextTrack(true); simulator.setNextTrack(false) }
                ) {
                    Icon(Icons.Default.SkipNext, "Weiter")
                }
                IconButton(
                    onClick = { simulator.setVoiceCommand(true); simulator.setVoiceCommand(false) }
                ) {
                    Icon(Icons.Default.Mic, "Sprache")
                }
            }
        }

        // VIN & Odometer Section
        SectionCard(title = "Fahrzeugdaten", icon = Icons.Default.DirectionsCar) {
            var vinInput by remember { mutableStateOf(vin) }

            OutlinedTextField(
                value = vinInput,
                onValueChange = {
                    vinInput = it.take(17).uppercase()
                    simulator.setVin(vinInput)
                },
                label = { Text("VIN (Fahrgestellnummer)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Kilometerstand: ${String.format("%.1f", odometer)} km",
                style = MaterialTheme.typography.bodyLarge
            )

            var odometerInput by remember { mutableStateOf("") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = odometerInput,
                    onValueChange = { odometerInput = it },
                    label = { Text("Neuer Wert (km)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = {
                        odometerInput.toDoubleOrNull()?.let { value ->
                            simulator.setOdometer(value)
                        }
                    },
                    enabled = odometerInput.toDoubleOrNull() != null
                ) {
                    Text("Set")
                }
            }
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
fun SliderWithLabel(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    unit: String,
    enabled: Boolean = true
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${String.format("%.1f", value)} $unit",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun ToggleChip(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = checked,
        onClick = { onCheckedChange(!checked) },
        label = { Text(label) },
        modifier = modifier
    )
}
