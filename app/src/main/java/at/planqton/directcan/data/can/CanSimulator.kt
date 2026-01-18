package at.planqton.directcan.data.can

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * CAN Bus Simulator - generates car CAN traffic for demo/testing
 * Values are sent immediately when changed via setters
 */
class CanSimulator {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var simulationJob: Job? = null

    private val _simulatedLines = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val simulatedLines: SharedFlow<String> = _simulatedLines.asSharedFlow()

    // Vehicle state - exposed as StateFlows for UI
    private val _engineRunning = MutableStateFlow(true)
    val engineRunning: StateFlow<Boolean> = _engineRunning.asStateFlow()

    private val _rpm = MutableStateFlow(800.0)
    val rpm: StateFlow<Double> = _rpm.asStateFlow()

    private val _speed = MutableStateFlow(0.0)
    val speed: StateFlow<Double> = _speed.asStateFlow()

    private val _engineTemp = MutableStateFlow(20.0)
    val engineTemp: StateFlow<Double> = _engineTemp.asStateFlow()

    private val _outsideTemp = MutableStateFlow(18.0)
    val outsideTemp: StateFlow<Double> = _outsideTemp.asStateFlow()

    private val _fuelLevel = MutableStateFlow(75.0)
    val fuelLevel: StateFlow<Double> = _fuelLevel.asStateFlow()

    private val _throttlePosition = MutableStateFlow(0.0)
    val throttlePosition: StateFlow<Double> = _throttlePosition.asStateFlow()

    private val _brakePressed = MutableStateFlow(false)
    val brakePressed: StateFlow<Boolean> = _brakePressed.asStateFlow()

    private val _steeringAngle = MutableStateFlow(0.0)
    val steeringAngle: StateFlow<Double> = _steeringAngle.asStateFlow()

    private val _batteryVoltage = MutableStateFlow(14.2)
    val batteryVoltage: StateFlow<Double> = _batteryVoltage.asStateFlow()

    private val _odometer = MutableStateFlow(Random.nextDouble(50000.0, 150000.0))
    val odometer: StateFlow<Double> = _odometer.asStateFlow()

    private val _gear = MutableStateFlow(0)
    val gear: StateFlow<Int> = _gear.asStateFlow()

    // Lights
    private val _leftBlinker = MutableStateFlow(false)
    val leftBlinker: StateFlow<Boolean> = _leftBlinker.asStateFlow()

    private val _rightBlinker = MutableStateFlow(false)
    val rightBlinker: StateFlow<Boolean> = _rightBlinker.asStateFlow()

    private val _hazardLights = MutableStateFlow(false)
    val hazardLights: StateFlow<Boolean> = _hazardLights.asStateFlow()

    private val _headlightsOn = MutableStateFlow(false)
    val headlightsOn: StateFlow<Boolean> = _headlightsOn.asStateFlow()

    private val _highBeamOn = MutableStateFlow(false)
    val highBeamOn: StateFlow<Boolean> = _highBeamOn.asStateFlow()

    private val _fogLightsOn = MutableStateFlow(false)
    val fogLightsOn: StateFlow<Boolean> = _fogLightsOn.asStateFlow()

    // Doors
    private val _driverDoorOpen = MutableStateFlow(false)
    val driverDoorOpen: StateFlow<Boolean> = _driverDoorOpen.asStateFlow()

    private val _passengerDoorOpen = MutableStateFlow(false)
    val passengerDoorOpen: StateFlow<Boolean> = _passengerDoorOpen.asStateFlow()

    private val _trunkOpen = MutableStateFlow(false)
    val trunkOpen: StateFlow<Boolean> = _trunkOpen.asStateFlow()

    private val _hoodOpen = MutableStateFlow(false)
    val hoodOpen: StateFlow<Boolean> = _hoodOpen.asStateFlow()

    // Steering wheel buttons
    private val _hornPressed = MutableStateFlow(false)
    val hornPressed: StateFlow<Boolean> = _hornPressed.asStateFlow()

    private val _cruiseControlOn = MutableStateFlow(false)
    val cruiseControlOn: StateFlow<Boolean> = _cruiseControlOn.asStateFlow()

    private val _volumeUp = MutableStateFlow(false)
    val volumeUp: StateFlow<Boolean> = _volumeUp.asStateFlow()

    private val _volumeDown = MutableStateFlow(false)
    val volumeDown: StateFlow<Boolean> = _volumeDown.asStateFlow()

    private val _nextTrack = MutableStateFlow(false)
    val nextTrack: StateFlow<Boolean> = _nextTrack.asStateFlow()

    private val _prevTrack = MutableStateFlow(false)
    val prevTrack: StateFlow<Boolean> = _prevTrack.asStateFlow()

    private val _voiceCommand = MutableStateFlow(false)
    val voiceCommand: StateFlow<Boolean> = _voiceCommand.asStateFlow()

    // VIN
    private val _vin = MutableStateFlow("WVWZZZ3CZWE123456")
    val vin: StateFlow<String> = _vin.asStateFlow()

    // Internal timing
    private var blinkerPhase = false

    // Standard CAN IDs
    object CanIds {
        const val ENGINE_RPM = 0x0C9L
        const val VEHICLE_SPEED = 0x0B4L
        const val THROTTLE = 0x140L
        const val ENGINE_TEMP = 0x1D0L
        const val OUTSIDE_TEMP = 0x3C3L
        const val FUEL_LEVEL = 0x349L
        const val LIGHTS_STATUS = 0x470L
        const val BRAKE_STATUS = 0x1A0L
        const val DOORS_STATUS = 0x2C0L
        const val GEAR_STATUS = 0x1F5L
        const val STEERING_ANGLE = 0x0C6L
        const val BATTERY_VOLTAGE = 0x520L
        const val ODOMETER = 0x5B0L
        const val STEERING_BUTTONS = 0x5C0L
        const val VIN_PART1 = 0x7E8L
        const val VIN_PART2 = 0x7E9L
        const val VIN_PART3 = 0x7EAL
        const val HORN = 0x5D0L
        const val CRUISE_CONTROL = 0x5E0L
    }

    // Setters - emit CAN frames immediately when values change
    fun setEngineRunning(running: Boolean) {
        _engineRunning.value = running
    }

    fun setRpm(value: Double) {
        _rpm.value = value.coerceIn(0.0, 8000.0)
        emitFrameNow(CanIds.ENGINE_RPM, encodeRpm(_rpm.value))
    }

    fun setSpeed(value: Double) {
        _speed.value = value.coerceIn(0.0, 300.0)
        emitFrameNow(CanIds.VEHICLE_SPEED, encodeSpeed(_speed.value))
    }

    fun setEngineTemp(value: Double) {
        _engineTemp.value = value.coerceIn(-40.0, 150.0)
        emitFrameNow(CanIds.ENGINE_TEMP, encodeEngineTemp(_engineTemp.value))
    }

    fun setOutsideTemp(value: Double) {
        _outsideTemp.value = value.coerceIn(-40.0, 60.0)
        emitFrameNow(CanIds.OUTSIDE_TEMP, encodeOutsideTemp(_outsideTemp.value))
    }

    fun setFuelLevel(value: Double) {
        _fuelLevel.value = value.coerceIn(0.0, 100.0)
        emitFrameNow(CanIds.FUEL_LEVEL, encodeFuelLevel(_fuelLevel.value))
    }

    fun setThrottlePosition(value: Double) {
        _throttlePosition.value = value.coerceIn(0.0, 100.0)
        emitFrameNow(CanIds.THROTTLE, encodeThrottle(_throttlePosition.value))
    }

    fun setBrakePressed(pressed: Boolean) {
        _brakePressed.value = pressed
        emitFrameNow(CanIds.BRAKE_STATUS, encodeBrake(_brakePressed.value))
    }

    fun setSteeringAngle(value: Double) {
        _steeringAngle.value = value.coerceIn(-720.0, 720.0)
        emitFrameNow(CanIds.STEERING_ANGLE, encodeSteeringAngle(_steeringAngle.value))
    }

    fun setBatteryVoltage(value: Double) {
        _batteryVoltage.value = value.coerceIn(0.0, 20.0)
        emitFrameNow(CanIds.BATTERY_VOLTAGE, encodeBatteryVoltage(_batteryVoltage.value))
    }

    fun setOdometer(value: Double) {
        _odometer.value = value.coerceAtLeast(0.0)
        emitFrameNow(CanIds.ODOMETER, encodeOdometer(_odometer.value))
    }

    fun setGear(value: Int) {
        _gear.value = value.coerceIn(0, 8)
        emitFrameNow(CanIds.GEAR_STATUS, encodeGear(_gear.value))
    }

    fun setLeftBlinker(on: Boolean) {
        _leftBlinker.value = on
        emitFrameNow(CanIds.LIGHTS_STATUS, encodeLights())
    }

    fun setRightBlinker(on: Boolean) {
        _rightBlinker.value = on
        emitFrameNow(CanIds.LIGHTS_STATUS, encodeLights())
    }

    fun setHazardLights(on: Boolean) {
        _hazardLights.value = on
        emitFrameNow(CanIds.LIGHTS_STATUS, encodeLights())
    }

    fun setHeadlights(on: Boolean) {
        _headlightsOn.value = on
        emitFrameNow(CanIds.LIGHTS_STATUS, encodeLights())
    }

    fun setHighBeam(on: Boolean) {
        _highBeamOn.value = on
        emitFrameNow(CanIds.LIGHTS_STATUS, encodeLights())
    }

    fun setFogLights(on: Boolean) {
        _fogLightsOn.value = on
        emitFrameNow(CanIds.LIGHTS_STATUS, encodeLights())
    }

    fun setDriverDoorOpen(open: Boolean) {
        _driverDoorOpen.value = open
        emitFrameNow(CanIds.DOORS_STATUS, encodeDoors())
    }

    fun setPassengerDoorOpen(open: Boolean) {
        _passengerDoorOpen.value = open
        emitFrameNow(CanIds.DOORS_STATUS, encodeDoors())
    }

    fun setTrunkOpen(open: Boolean) {
        _trunkOpen.value = open
        emitFrameNow(CanIds.DOORS_STATUS, encodeDoors())
    }

    fun setHoodOpen(open: Boolean) {
        _hoodOpen.value = open
        emitFrameNow(CanIds.DOORS_STATUS, encodeDoors())
    }

    fun setHornPressed(pressed: Boolean) {
        _hornPressed.value = pressed
        if (pressed) emitFrameNow(CanIds.HORN, byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, 0))
    }

    fun setCruiseControl(on: Boolean) {
        _cruiseControlOn.value = on
        emitFrameNow(CanIds.CRUISE_CONTROL, byteArrayOf(if (on) 0x01 else 0x00, (_speed.value.toInt() and 0xFF).toByte(), 0, 0, 0, 0, 0, 0))
    }

    fun setVolumeUp(pressed: Boolean) {
        _volumeUp.value = pressed
        emitFrameNow(CanIds.STEERING_BUTTONS, encodeSteeringButtons())
    }

    fun setVolumeDown(pressed: Boolean) {
        _volumeDown.value = pressed
        emitFrameNow(CanIds.STEERING_BUTTONS, encodeSteeringButtons())
    }

    fun setNextTrack(pressed: Boolean) {
        _nextTrack.value = pressed
        emitFrameNow(CanIds.STEERING_BUTTONS, encodeSteeringButtons())
    }

    fun setPrevTrack(pressed: Boolean) {
        _prevTrack.value = pressed
        emitFrameNow(CanIds.STEERING_BUTTONS, encodeSteeringButtons())
    }

    fun setVoiceCommand(pressed: Boolean) {
        _voiceCommand.value = pressed
        emitFrameNow(CanIds.STEERING_BUTTONS, encodeSteeringButtons())
    }

    fun setVin(newVin: String) {
        _vin.value = newVin.take(17).uppercase()
        // VIN will be sent in next VIN loop cycle
    }

    // Emit frame immediately (non-suspend, uses tryEmit)
    // SLCAN format: t<id:3><len:1><data>
    private fun emitFrameNow(id: Long, data: ByteArray) {
        val idHex = id.toString(16).uppercase().padStart(3, '0')
        val dataBytes = data.take(8).joinToString("") {
            (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
        }
        val line = "t$idHex${data.size}$dataBytes"
        _simulatedLines.tryEmit(line)
    }

    fun start() {
        if (simulationJob?.isActive == true) return

        simulationJob = scope.launch {
            // Initialize vehicle state with defaults
            _engineRunning.value = true
            _rpm.value = 800.0
            _speed.value = 0.0
            _engineTemp.value = 90.0
            _outsideTemp.value = 18.0
            _fuelLevel.value = 75.0

            // Start periodic broadcast loops (broadcast current state, don't auto-change values)
            launch { periodicBroadcastLoop() }
            launch { lightsLoop() }
            launch { vinLoop() }
        }
    }

    fun stop() {
        simulationJob?.cancel()
        simulationJob = null
    }

    val isRunning: Boolean
        get() = simulationJob?.isActive == true

    // Periodic broadcast of all values (for continuous state updates)
    private suspend fun periodicBroadcastLoop() {
        while (isActive) {
            // Engine & Speed - fast updates
            emitFrame(CanIds.ENGINE_RPM, encodeRpm(_rpm.value))
            emitFrame(CanIds.VEHICLE_SPEED, encodeSpeed(_speed.value))
            emitFrame(CanIds.THROTTLE, encodeThrottle(_throttlePosition.value))
            delay(50)

            // Temperatures & other values - slower updates
            emitFrame(CanIds.ENGINE_TEMP, encodeEngineTemp(_engineTemp.value))
            emitFrame(CanIds.OUTSIDE_TEMP, encodeOutsideTemp(_outsideTemp.value))
            emitFrame(CanIds.FUEL_LEVEL, encodeFuelLevel(_fuelLevel.value))
            emitFrame(CanIds.BATTERY_VOLTAGE, encodeBatteryVoltage(_batteryVoltage.value))
            emitFrame(CanIds.BRAKE_STATUS, encodeBrake(_brakePressed.value))
            emitFrame(CanIds.GEAR_STATUS, encodeGear(_gear.value))
            emitFrame(CanIds.STEERING_ANGLE, encodeSteeringAngle(_steeringAngle.value))
            emitFrame(CanIds.ODOMETER, encodeOdometer(_odometer.value))
            emitFrame(CanIds.DOORS_STATUS, encodeDoors())
            emitFrame(CanIds.STEERING_BUTTONS, encodeSteeringButtons())

            if (_hornPressed.value) {
                emitFrame(CanIds.HORN, byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, 0))
            }
            if (_cruiseControlOn.value) {
                emitFrame(CanIds.CRUISE_CONTROL, byteArrayOf(0x01, (_speed.value.toInt() and 0xFF).toByte(), 0, 0, 0, 0, 0, 0))
            }

            delay(200)
        }
    }

    private suspend fun lightsLoop() {
        var blinkerCounter = 0
        while (isActive) {
            blinkerCounter++
            if (blinkerCounter >= 5) {
                blinkerCounter = 0
                blinkerPhase = !blinkerPhase
            }

            emitFrame(CanIds.LIGHTS_STATUS, encodeLights())
            delay(100)
        }
    }

    private suspend fun vinLoop() {
        while (isActive) {
            // VIN is typically sent in response to OBD request, but we'll broadcast it periodically
            val vinBytes = _vin.value.toByteArray(Charsets.US_ASCII)
            if (vinBytes.size >= 17) {
                emitFrame(CanIds.VIN_PART1, vinBytes.sliceArray(0..5) + byteArrayOf(0, 0))
                delay(50)
                emitFrame(CanIds.VIN_PART2, vinBytes.sliceArray(6..11) + byteArrayOf(0, 0))
                delay(50)
                emitFrame(CanIds.VIN_PART3, vinBytes.sliceArray(12..16) + byteArrayOf(0, 0, 0))
            }
            delay(5000)
        }
    }

    // Encoding functions - ALL use Little Endian (LSB first) to match DBC @1+ format
    private fun encodeRpm(rpm: Double): ByteArray {
        // DBC: SG_ RPM : 0|16@1+ (0.25,0) - factor 0.25 means we multiply by 4
        val rpmInt = (rpm * 4).toInt()
        return byteArrayOf(
            (rpmInt and 0xFF).toByte(),        // LSB at byte 0
            ((rpmInt shr 8) and 0xFF).toByte(), // MSB at byte 1
            0, 0, 0, 0, 0, 0
        )
    }

    private fun encodeSpeed(speed: Double): ByteArray {
        // DBC: SG_ Speed : 8|16@1+ (0.01,0) - starts at bit 8, factor 0.01 means we multiply by 100
        val speedInt = (speed * 100).toInt()
        return byteArrayOf(
            0,                                  // Byte 0 (SpeedValid at bit 0)
            (speedInt and 0xFF).toByte(),       // LSB at byte 1 (bit 8)
            ((speedInt shr 8) and 0xFF).toByte(), // MSB at byte 2
            0, 0, 0, 0, 0
        )
    }

    private fun encodeThrottle(throttle: Double): ByteArray {
        // DBC: SG_ Throttle : 0|8@1+ (0.392157,0) - factor ~0.39 means we multiply by ~2.55
        val throttleInt = (throttle * 2.55).toInt()
        return byteArrayOf(throttleInt.toByte(), 0, 0, 0, 0, 0, 0, 0)
    }

    private fun encodeEngineTemp(temp: Double): ByteArray {
        // DBC: SG_ CoolantTemp : 0|8@1+ (1,-40) - offset -40 means we add 40
        val tempInt = (temp + 40).toInt()
        return byteArrayOf(tempInt.toByte(), 0, 0, 0, 0, 0, 0, 0)
    }

    private fun encodeOutsideTemp(temp: Double): ByteArray {
        // DBC: SG_ AmbientTemp : 0|8@1+ (0.5,-40) - factor 0.5, offset -40
        val tempInt = ((temp + 40) * 2).toInt()
        return byteArrayOf(tempInt.toByte(), 0, 0, 0, 0, 0, 0, 0)
    }

    private fun encodeFuelLevel(level: Double): ByteArray {
        // DBC: SG_ FuelPercent : 0|8@1+ (0.392157,0)
        val levelInt = (level * 2.55).toInt()
        return byteArrayOf(levelInt.toByte(), 0, 0, 0, 0, 0, 0, 0)
    }

    private fun encodeLights(): ByteArray {
        // DBC: Individual bits for each light
        var byte0 = 0
        if (_leftBlinker.value && blinkerPhase) byte0 = byte0 or 0x01   // bit 0
        if (_rightBlinker.value && blinkerPhase) byte0 = byte0 or 0x02  // bit 1
        if (_headlightsOn.value) byte0 = byte0 or 0x04                  // bit 2
        if (_highBeamOn.value) byte0 = byte0 or 0x08                    // bit 3
        if (_hazardLights.value && blinkerPhase) byte0 = byte0 or 0x10  // bit 4
        if (_fogLightsOn.value) byte0 = byte0 or 0x20                   // bit 5

        return byteArrayOf(byte0.toByte(), 0, 0, 0, 0, 0, 0, 0)
    }

    private fun encodeBrake(pressed: Boolean): ByteArray {
        // DBC: SG_ BrakePedal : 0|1@1+
        return byteArrayOf(if (pressed) 0x01 else 0x00, 0, 0, 0, 0, 0, 0, 0)
    }

    private fun encodeBatteryVoltage(voltage: Double): ByteArray {
        // DBC: SG_ Voltage : 0|16@1+ (0.1,0) - Little Endian, factor 0.1 means multiply by 10
        val voltageInt = (voltage * 10).toInt()
        return byteArrayOf(
            (voltageInt and 0xFF).toByte(),        // LSB at byte 0
            ((voltageInt shr 8) and 0xFF).toByte(), // MSB at byte 1
            0, 0, 0, 0, 0, 0
        )
    }

    private fun encodeGear(gear: Int): ByteArray {
        // DBC: SG_ CurrentGear : 0|4@1+
        return byteArrayOf(gear.toByte(), 0, 0, 0, 0, 0, 0, 0)
    }

    private fun encodeSteeringAngle(angle: Double): ByteArray {
        // DBC: SG_ SteeringAngle : 0|16@1+ (0.1,-720) - Little Endian, factor 0.1, offset -720
        val angleInt = ((angle + 720) * 10).toInt()
        return byteArrayOf(
            (angleInt and 0xFF).toByte(),        // LSB at byte 0
            ((angleInt shr 8) and 0xFF).toByte(), // MSB at byte 1
            0, 0, 0, 0, 0, 0
        )
    }

    private fun encodeOdometer(km: Double): ByteArray {
        // DBC: SG_ TotalKm : 0|24@1+ (0.1,0) - Little Endian 24-bit, factor 0.1 means multiply by 10
        val odometerInt = (km * 10).toLong()
        return byteArrayOf(
            (odometerInt and 0xFF).toByte(),          // LSB at byte 0
            ((odometerInt shr 8) and 0xFF).toByte(),  // byte 1
            ((odometerInt shr 16) and 0xFF).toByte(), // MSB at byte 2
            0, 0, 0, 0, 0
        )
    }

    private fun encodeDoors(): ByteArray {
        var doorStatus = 0
        if (_driverDoorOpen.value) doorStatus = doorStatus or 0x01
        if (_passengerDoorOpen.value) doorStatus = doorStatus or 0x02
        if (_trunkOpen.value) doorStatus = doorStatus or 0x10
        if (_hoodOpen.value) doorStatus = doorStatus or 0x20
        return byteArrayOf(doorStatus.toByte(), 0, 0, 0, 0, 0, 0, 0)
    }

    private fun encodeSteeringButtons(): ByteArray {
        var buttons = 0
        if (_volumeUp.value) buttons = buttons or 0x01
        if (_volumeDown.value) buttons = buttons or 0x02
        if (_nextTrack.value) buttons = buttons or 0x04
        if (_prevTrack.value) buttons = buttons or 0x08
        if (_voiceCommand.value) buttons = buttons or 0x10
        return byteArrayOf(buttons.toByte(), 0, 0, 0, 0, 0, 0, 0)
    }

    // SLCAN format: t<id:3><len:1><data>
    private suspend fun emitFrame(id: Long, data: ByteArray) {
        val idHex = id.toString(16).uppercase().padStart(3, '0')
        val dataBytes = data.take(8).joinToString("") {
            (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
        }
        val line = "t$idHex${data.size}$dataBytes"
        _simulatedLines.emit(line)
    }

    private val isActive: Boolean
        get() = simulationJob?.isActive == true
}
