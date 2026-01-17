package at.planqton.directcan.data.device

import android.util.Log
import at.planqton.directcan.data.can.CanSimulator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val TAG = "SimulatorDevice"

/**
 * Simulator device implementation.
 * Wraps CanSimulator to provide simulated CAN data for testing/demo purposes.
 */
class SimulatorDevice(
    private val config: SimulatorConfig
) : CanDevice {

    override val id: String = config.id
    override val type: DeviceType = DeviceType.SIMULATOR
    override val displayName: String = config.name

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The actual simulator instance
    val simulator = CanSimulator()

    private var collectorJob: Job? = null

    // State
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Data streams
    private val _receivedLines = MutableSharedFlow<String>(extraBufferCapacity = 1000)
    override val receivedLines: SharedFlow<String> = _receivedLines.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 100)
    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    override suspend fun connect(): Result<Unit> {
        return try {
            Log.i(TAG, "Starting simulator")
            _connectionState.value = ConnectionState.CONNECTING

            // Start the simulator
            simulator.start()

            // Collect simulator output and forward to receivedLines
            collectorJob = scope.launch {
                simulator.simulatedLines.collect { line ->
                    _receivedLines.emit(line)
                }
            }

            _connectionState.value = ConnectionState.CONNECTED
            Log.i(TAG, "Simulator connected")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Simulator start failed", e)
            _connectionState.value = ConnectionState.ERROR
            _errors.emit("Simulator failed: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun disconnect() {
        Log.i(TAG, "Stopping simulator")
        collectorJob?.cancel()
        collectorJob = null
        simulator.stop()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun send(data: String): Boolean {
        // Simulator doesn't process incoming commands
        Log.v(TAG, "Simulator ignoring send: ${data.trim()}")
        return true
    }

    override fun getStatusInfo(): Map<String, String> {
        return mapOf(
            "Typ" to "Simulator",
            "Status" to if (simulator.isRunning) "Aktiv" else "Gestoppt",
            "RPM" to "%.0f".format(simulator.rpm.value),
            "Speed" to "%.0f km/h".format(simulator.speed.value)
        )
    }

    override suspend fun setCanBitrate(bitrate: Int): Boolean {
        // Simulator doesn't have a real CAN bus, so bitrate is irrelevant
        Log.d(TAG, "Simulator ignoring bitrate: $bitrate (not applicable)")
        return true
    }

    override fun dispose() {
        scope.launch { disconnect() }
        scope.cancel()
    }

    // === Simulator Control Methods ===
    // These allow direct control of the simulator from the UI

    fun setRpm(value: Double) = simulator.setRpm(value)
    fun setSpeed(value: Double) = simulator.setSpeed(value)
    fun setEngineTemp(value: Double) = simulator.setEngineTemp(value)
    fun setFuelLevel(value: Double) = simulator.setFuelLevel(value)
    fun setBatteryVoltage(value: Double) = simulator.setBatteryVoltage(value)
    fun setThrottlePosition(value: Double) = simulator.setThrottlePosition(value)
    fun setBrakePressed(pressed: Boolean) = simulator.setBrakePressed(pressed)
    fun setGear(gear: Int) = simulator.setGear(gear)
    fun setEngineRunning(running: Boolean) = simulator.setEngineRunning(running)

    // Door controls
    fun setDriverDoorOpen(open: Boolean) = simulator.setDriverDoorOpen(open)
    fun setPassengerDoorOpen(open: Boolean) = simulator.setPassengerDoorOpen(open)
    fun setTrunkOpen(open: Boolean) = simulator.setTrunkOpen(open)
    fun setHoodOpen(open: Boolean) = simulator.setHoodOpen(open)

    // Light controls
    fun setHeadlights(on: Boolean) = simulator.setHeadlights(on)
    fun setHighBeam(on: Boolean) = simulator.setHighBeam(on)
    fun setLeftBlinker(on: Boolean) = simulator.setLeftBlinker(on)
    fun setRightBlinker(on: Boolean) = simulator.setRightBlinker(on)
    fun setHazardLights(on: Boolean) = simulator.setHazardLights(on)

    // State flows for UI observation
    val engineRunning: StateFlow<Boolean> get() = simulator.engineRunning
    val rpm: StateFlow<Double> get() = simulator.rpm
    val speed: StateFlow<Double> get() = simulator.speed
    val engineTemp: StateFlow<Double> get() = simulator.engineTemp
    val fuelLevel: StateFlow<Double> get() = simulator.fuelLevel
    val gear: StateFlow<Int> get() = simulator.gear
    val brakePressed: StateFlow<Boolean> get() = simulator.brakePressed
}
