package at.planqton.directcan.data.txscript

import android.util.Log
import at.planqton.directcan.data.can.CanDataRepository
import at.planqton.directcan.data.device.DeviceManager
import at.planqton.directcan.data.txscript.parser.parseScript
import at.planqton.directcan.data.usb.UsbSerialManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

private const val TAG = "TxScriptExecutor"

class TxScriptExecutor(
    private val usbManager: UsbSerialManager,
    private val canDataRepository: CanDataRepository,
    private val deviceManager: DeviceManager? = null
) {
    // Target ports for multi-port support
    private var targetPorts: Set<Int> = setOf(1, 2)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State
    private val _state = MutableStateFlow(ScriptExecutionState.EMPTY)
    val state: StateFlow<ScriptExecutionState> = _state.asStateFlow()

    private var executionJob: Job? = null
    private val isPaused = AtomicBoolean(false)
    private val shouldStop = AtomicBoolean(false)

    // Environment
    private val variables = mutableMapOf<String, Any>()
    private var functions = mapOf<String, ScriptCommand.FunctionDef>()
    private val triggerJobs = mutableListOf<Job>()

    // Response tracking
    private var lastResponse: ReceivedCanFrame? = null
    private val responseChannel = Channel<ReceivedCanFrame>(Channel.CONFLATED)
    private var frameCollectorJob: Job? = null

    // Debug log buffer
    private val debugLog = mutableListOf<ScriptLogEntry>()
    private val maxLogEntries = 500

    // =============== PUBLIC API ===============

    /**
     * Start executing a script.
     */
    fun start(script: TxScript, ports: Set<Int> = setOf(1, 2)): Result<Unit> {
        Log.i(TAG, "Starting script: ${script.name} on ports: $ports")
        targetPorts = ports

        // Check connection
        if (usbManager.connectionState.value != UsbSerialManager.ConnectionState.CONNECTED) {
            val error = ScriptError(
                line = 0,
                message = "Nicht mit CAN-Bus verbunden",
                type = ErrorType.CONNECTION_ERROR
            )
            updateState { copy(state = ScriptState.ERROR, errors = listOf(error)) }
            return Result.failure(Exception(error.message))
        }

        // Parse script
        val parseResult = parseScript(script.content)
        if (!parseResult.isValid) {
            Log.w(TAG, "Script has parse errors: ${parseResult.errors.size}")
            updateState {
                copy(
                    state = ScriptState.ERROR,
                    errors = parseResult.errors
                )
            }
            return Result.failure(Exception("Parse errors: ${parseResult.errors.firstOrNull()?.message}"))
        }

        // Stop any running script
        stop()

        // Reset state
        variables.clear()
        functions = parseResult.functions
        debugLog.clear()
        isPaused.set(false)
        shouldStop.set(false)

        updateState {
            ScriptExecutionState(
                scriptId = script.id,
                scriptName = script.name,
                state = ScriptState.RUNNING,
                totalLines = parseResult.totalLines,
                startTime = System.currentTimeMillis()
            )
        }

        // Start frame collection
        startFrameCollection()

        // Setup triggers
        setupTriggers(parseResult.triggers)

        // Start execution
        executionJob = scope.launch {
            try {
                execute(parseResult.commands)
                if (!shouldStop.get()) {
                    log(LogEntryType.STATE, "Script completed")
                    updateState { copy(state = ScriptState.COMPLETED) }
                }
            } catch (e: BreakException) {
                log(LogEntryType.ERROR, "Break outside of loop", e.line)
            } catch (e: ContinueException) {
                log(LogEntryType.ERROR, "Continue outside of loop", e.line)
            } catch (e: ReturnException) {
                // Normal return from top-level
                log(LogEntryType.STATE, "Script returned")
                updateState { copy(state = ScriptState.COMPLETED) }
            } catch (e: CancellationException) {
                Log.d(TAG, "Script cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Script execution error", e)
                error("Runtime error: ${e.message}", 0, ErrorType.RUNTIME_ERROR)
                updateState { copy(state = ScriptState.ERROR) }
            } finally {
                cleanup()
            }
        }

        return Result.success(Unit)
    }

    /**
     * Pause script execution.
     */
    fun pause() {
        if (_state.value.state == ScriptState.RUNNING) {
            Log.i(TAG, "Pausing script")
            isPaused.set(true)
            updateState { copy(state = ScriptState.PAUSED) }
            log(LogEntryType.STATE, "Paused")
        }
    }

    /**
     * Resume paused script.
     */
    fun resume() {
        if (_state.value.state == ScriptState.PAUSED) {
            Log.i(TAG, "Resuming script")
            isPaused.set(false)
            updateState { copy(state = ScriptState.RUNNING) }
            log(LogEntryType.STATE, "Resumed")
        }
    }

    /**
     * Stop and reset script execution.
     */
    fun stop() {
        Log.i(TAG, "Stopping script")
        shouldStop.set(true)
        isPaused.set(false)

        executionJob?.cancel()
        executionJob = null

        cleanup()

        updateState {
            copy(
                state = ScriptState.STOPPED,
                elapsedTime = if (startTime > 0) System.currentTimeMillis() - startTime else 0
            )
        }
    }

    /**
     * Reset to initial state.
     */
    fun reset() {
        stop()
        debugLog.clear()
        updateState { ScriptExecutionState.EMPTY }
    }

    /**
     * Validate a script without executing it.
     */
    fun validate(content: String): ParseResult {
        return parseScript(content)
    }

    // =============== EXECUTION ===============

    private suspend fun execute(commands: List<ScriptCommand>) {
        for (command in commands) {
            checkPauseAndStop()
            executeCommand(command)
        }
    }

    private suspend fun executeCommand(command: ScriptCommand): Any? {
        updateState { copy(currentLine = command.line) }

        return when (command) {
            is ScriptCommand.VarDeclaration -> {
                val value = evaluate(command.value)
                variables[command.name] = value
                log(LogEntryType.DEBUG, "var ${command.name} = $value", command.line)
                null
            }
            is ScriptCommand.Assignment -> {
                val value = evaluate(command.value)
                variables[command.name] = value
                null
            }
            is ScriptCommand.Send -> executeSend(command)
            is ScriptCommand.Delay -> executeDelay(command)
            is ScriptCommand.Repeat -> executeRepeat(command)
            is ScriptCommand.Loop -> executeLoop(command)
            is ScriptCommand.If -> executeIf(command)
            is ScriptCommand.WaitFor -> executeWaitFor(command)
            is ScriptCommand.FunctionCallStmt -> executeFunctionCall(command.name, command.args, command.line)
            is ScriptCommand.Return -> throw ReturnException(command.value?.let { evaluate(it) }, command.line)
            is ScriptCommand.Break -> throw BreakException(command.line)
            is ScriptCommand.Continue -> throw ContinueException(command.line)
            is ScriptCommand.Print -> executePrint(command)
            is ScriptCommand.ExpressionStmt -> evaluate(command.expression)
            is ScriptCommand.FunctionDef -> null // Already extracted
            is ScriptCommand.OnReceive -> null // Handled as trigger
            is ScriptCommand.OnInterval -> null // Handled as trigger
        }
    }

    private suspend fun executeSend(command: ScriptCommand.Send) {
        val canId = evaluateAsLong(evaluate(command.canId))
        val data = evaluateAsByteArray(evaluate(command.data))

        log(LogEntryType.SEND,
            "send(0x${canId.toString(16).uppercase()}, [${data.joinToString(" ") { "%02X".format(it) }}]${if (command.extended) ", ext" else ""})",
            command.line
        )

        try {
            // Use multi-port sending if deviceManager is available and has multiple connections
            if (deviceManager != null && deviceManager.connectedDeviceCount.value > 1) {
                // Build frame string for sending
                val frameStr = buildString {
                    append(canId.toString(16).uppercase())
                    if (command.extended) append("X")
                    if (data.isNotEmpty()) {
                        append("#")
                        append(data.joinToString("") { "%02X".format(it) })
                    }
                }
                val portsToSend = targetPorts.filter { deviceManager.activeDevices.value.containsKey(it) }.toSet()
                if (portsToSend.isNotEmpty()) {
                    deviceManager.sendToPorts(portsToSend, frameStr)
                }
            } else {
                // Single device - use legacy method
                usbManager.sendCanFrame(canId, data, command.extended)
            }
            updateState { copy(framesSent = framesSent + 1) }
        } catch (e: Exception) {
            error("Send failed: ${e.message}", command.line, ErrorType.SEND_ERROR)
        }
    }

    private suspend fun executeDelay(command: ScriptCommand.Delay) {
        val ms = evaluateAsLong(evaluate(command.duration))
        log(LogEntryType.DEBUG, "delay(${ms}ms)", command.line)

        // Split delay into chunks to allow pause/stop
        var remaining = ms
        while (remaining > 0) {
            checkPauseAndStop()
            val chunk = minOf(remaining, 100)
            delay(chunk)
            remaining -= chunk
        }
    }

    private suspend fun executeRepeat(command: ScriptCommand.Repeat) {
        val count = evaluateAsLong(evaluate(command.count)).toInt()
        log(LogEntryType.DEBUG, "repeat($count)", command.line)

        for (i in 0 until count) {
            checkPauseAndStop()
            variables["iteration"] = i.toLong()
            updateState { copy(iteration = i) }

            try {
                execute(command.body)
            } catch (e: BreakException) {
                break
            } catch (e: ContinueException) {
                continue
            }
        }
    }

    private suspend fun executeLoop(command: ScriptCommand.Loop) {
        log(LogEntryType.DEBUG, "loop {}", command.line)
        var iteration = 0

        while (true) {
            checkPauseAndStop()
            variables["iteration"] = iteration.toLong()
            updateState { copy(iteration = iteration) }

            try {
                execute(command.body)
            } catch (e: BreakException) {
                break
            } catch (e: ContinueException) {
                continue
            }

            iteration++
        }
    }

    private suspend fun executeIf(command: ScriptCommand.If) {
        val condition = evaluateAsBoolean(evaluate(command.condition))

        if (condition) {
            execute(command.thenBranch)
        } else if (command.elseBranch != null) {
            execute(command.elseBranch)
        }
    }

    private suspend fun executeWaitFor(command: ScriptCommand.WaitFor): Boolean {
        val targetId = evaluateAsLong(evaluate(command.canId))
        val timeoutMs = evaluateAsLong(evaluate(command.timeout))
        val dataPattern = command.dataPattern?.let { evaluate(it) }

        log(LogEntryType.DEBUG, "wait_for(0x${targetId.toString(16).uppercase()}, timeout: ${timeoutMs}ms)", command.line)

        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            checkPauseAndStop()

            // Check for matching frame
            val frame = responseChannel.tryReceive().getOrNull()
            if (frame != null && frame.id == targetId) {
                if (dataPattern == null || matchesPattern(frame.data, dataPattern)) {
                    lastResponse = frame
                    updateResponseVariables(frame)
                    log(LogEntryType.RECEIVE, "Received: 0x${frame.id.toString(16).uppercase()}", command.line)
                    updateState { copy(framesReceived = framesReceived + 1) }
                    return true
                }
            }

            delay(10)
        }

        error("Timeout waiting for 0x${targetId.toString(16).uppercase()}", command.line, ErrorType.TIMEOUT_ERROR)
        return false
    }

    private suspend fun executeFunctionCall(name: String, args: List<Expression>, line: Int): Any? {
        val func = functions[name]
            ?: throw RuntimeException("Undefined function: $name").also {
                error("Undefined function: $name", line, ErrorType.UNDEFINED_FUNCTION)
            }

        // Create new scope with parameters
        val savedVariables = variables.toMap()

        for ((i, param) in func.params.withIndex()) {
            variables[param] = if (i < args.size) evaluate(args[i]) else 0L
        }

        return try {
            execute(func.body)
            null
        } catch (e: ReturnException) {
            e.value
        } finally {
            // Restore outer scope (but keep global changes)
            func.params.forEach { variables.remove(it) }
        }
    }

    private fun executePrint(command: ScriptCommand.Print) {
        val message = command.values.joinToString("") { expr ->
            val value = evaluate(expr)
            when (value) {
                is ByteArray -> "[${value.joinToString(" ") { "%02X".format(it) }}]"
                is Long -> value.toString()
                is Double -> value.toString()
                is Boolean -> value.toString()
                else -> value.toString()
            }
        }
        log(LogEntryType.DEBUG, message, command.line)
    }

    // =============== EXPRESSION EVALUATION ===============

    private fun evaluate(expr: Expression): Any {
        return when (expr) {
            is Expression.IntLiteral -> expr.value
            is Expression.HexLiteral -> expr.value
            is Expression.FloatLiteral -> expr.value
            is Expression.StringLiteral -> expr.value
            is Expression.BoolLiteral -> expr.value
            is Expression.ByteArrayLiteral -> {
                expr.bytes.map { evaluateAsLong(evaluate(it)).toByte() }.toByteArray()
            }
            is Expression.Variable -> {
                variables[expr.name]
                    ?: throw RuntimeException("Undefined variable: ${expr.name}")
            }
            is Expression.ArrayAccess -> {
                val array = evaluate(expr.array)
                val index = evaluateAsLong(evaluate(expr.index)).toInt()
                when (array) {
                    is ByteArray -> array.getOrElse(index) { 0 }.toLong() and 0xFF
                    is List<*> -> (array.getOrNull(index) as? Number)?.toLong() ?: 0L
                    else -> throw RuntimeException("Cannot index non-array")
                }
            }
            is Expression.PropertyAccess -> evaluatePropertyAccess(expr)
            is Expression.BinaryOp -> evaluateBinaryOp(expr)
            is Expression.UnaryOp -> evaluateUnaryOp(expr)
            is Expression.FunctionCall -> {
                // Built-in functions first
                evaluateBuiltinFunction(expr.name, expr.args)
                    ?: runBlocking { executeFunctionCall(expr.name, expr.args, 0) }
                    ?: 0L
            }
            is Expression.Random -> {
                val min = evaluateAsLong(evaluate(expr.min))
                val max = evaluateAsLong(evaluate(expr.max))
                Random.nextLong(min, max + 1)
            }
            is Expression.RandomBytes -> {
                val count = evaluateAsLong(evaluate(expr.count)).toInt()
                Random.nextBytes(count)
            }
            is Expression.Wildcard -> WildcardValue
            is Expression.Grouped -> evaluate(expr.expression)
            is Expression.Conditional -> {
                if (evaluateAsBoolean(evaluate(expr.condition))) {
                    evaluate(expr.thenExpr)
                } else {
                    evaluate(expr.elseExpr)
                }
            }
        }
    }

    private fun evaluatePropertyAccess(expr: Expression.PropertyAccess): Any {
        // Handle 'response' object
        if (expr.obj is Expression.Variable && (expr.obj as Expression.Variable).name == "response") {
            val frame = lastResponse ?: return 0L
            return when (expr.property) {
                "id" -> frame.id
                "data" -> frame.data
                "timestamp" -> frame.timestamp
                "extended" -> frame.isExtended
                else -> throw RuntimeException("Unknown response property: ${expr.property}")
            }
        }

        // Handle 'now' built-in
        if (expr.obj is Expression.Variable && (expr.obj as Expression.Variable).name == "now") {
            return System.currentTimeMillis()
        }

        val obj = evaluate(expr.obj)
        return when {
            obj is ReceivedCanFrame -> {
                when (expr.property) {
                    "id" -> obj.id
                    "data" -> obj.data
                    "timestamp" -> obj.timestamp
                    else -> throw RuntimeException("Unknown frame property: ${expr.property}")
                }
            }
            else -> throw RuntimeException("Cannot access property on: $obj")
        }
    }

    private fun evaluateBinaryOp(expr: Expression.BinaryOp): Any {
        val left = evaluate(expr.left)
        val right = evaluate(expr.right)

        return when (expr.operator) {
            Operator.ADD -> evaluateAsLong(left) + evaluateAsLong(right)
            Operator.SUB -> evaluateAsLong(left) - evaluateAsLong(right)
            Operator.MUL -> evaluateAsLong(left) * evaluateAsLong(right)
            Operator.DIV -> {
                val r = evaluateAsLong(right)
                if (r == 0L) 0L else evaluateAsLong(left) / r
            }
            Operator.MOD -> {
                val r = evaluateAsLong(right)
                if (r == 0L) 0L else evaluateAsLong(left) % r
            }
            Operator.BIT_AND -> evaluateAsLong(left) and evaluateAsLong(right)
            Operator.BIT_OR -> evaluateAsLong(left) or evaluateAsLong(right)
            Operator.BIT_XOR -> evaluateAsLong(left) xor evaluateAsLong(right)
            Operator.SHL -> evaluateAsLong(left) shl evaluateAsLong(right).toInt()
            Operator.SHR -> evaluateAsLong(left) shr evaluateAsLong(right).toInt()
            Operator.EQ -> left == right || evaluateAsLong(left) == evaluateAsLong(right)
            Operator.NE -> left != right && evaluateAsLong(left) != evaluateAsLong(right)
            Operator.LT -> evaluateAsLong(left) < evaluateAsLong(right)
            Operator.LE -> evaluateAsLong(left) <= evaluateAsLong(right)
            Operator.GT -> evaluateAsLong(left) > evaluateAsLong(right)
            Operator.GE -> evaluateAsLong(left) >= evaluateAsLong(right)
            Operator.AND -> evaluateAsBoolean(left) && evaluateAsBoolean(right)
            Operator.OR -> evaluateAsBoolean(left) || evaluateAsBoolean(right)
            else -> throw RuntimeException("Unsupported binary operator: ${expr.operator}")
        }
    }

    private fun evaluateUnaryOp(expr: Expression.UnaryOp): Any {
        val operand = evaluate(expr.operand)

        return when (expr.operator) {
            Operator.NEGATE -> -evaluateAsLong(operand)
            Operator.NOT -> !evaluateAsBoolean(operand)
            Operator.BIT_NOT -> evaluateAsLong(operand).inv()
            else -> throw RuntimeException("Unsupported unary operator: ${expr.operator}")
        }
    }

    private fun evaluateBuiltinFunction(name: String, args: List<Expression>): Any? {
        return when (name) {
            "abs" -> kotlin.math.abs(evaluateAsLong(evaluate(args[0])))
            "min" -> minOf(evaluateAsLong(evaluate(args[0])), evaluateAsLong(evaluate(args[1])))
            "max" -> maxOf(evaluateAsLong(evaluate(args[0])), evaluateAsLong(evaluate(args[1])))
            "len" -> {
                val value = evaluate(args[0])
                when (value) {
                    is ByteArray -> value.size.toLong()
                    is String -> value.length.toLong()
                    else -> 0L
                }
            }
            else -> null // Not a builtin
        }
    }

    // =============== TYPE CONVERSION ===============

    private fun evaluateAsLong(value: Any): Long {
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            is Float -> value.toLong()
            is Boolean -> if (value) 1L else 0L
            is Byte -> value.toLong() and 0xFF
            is String -> value.toLongOrNull() ?: value.toLongOrNull(16) ?: 0L
            else -> 0L
        }
    }

    private fun evaluateAsBoolean(value: Any): Boolean {
        return when (value) {
            is Boolean -> value
            is Long -> value != 0L
            is Int -> value != 0
            is Double -> value != 0.0
            is String -> value.isNotEmpty()
            is ByteArray -> value.isNotEmpty()
            else -> false
        }
    }

    private fun evaluateAsByteArray(value: Any): ByteArray {
        return when (value) {
            is ByteArray -> value
            is List<*> -> value.mapNotNull { (it as? Number)?.toByte() }.toByteArray()
            is Long -> byteArrayOf(value.toByte())
            is String -> value.split(" ", ",")
                .filter { it.isNotBlank() }
                .map { it.trim().removePrefix("0x").removePrefix("0X").toInt(16).toByte() }
                .toByteArray()
            else -> byteArrayOf()
        }
    }

    // =============== TRIGGERS ===============

    private fun setupTriggers(triggers: List<ScriptCommand>) {
        for (trigger in triggers) {
            when (trigger) {
                is ScriptCommand.OnReceive -> setupOnReceive(trigger)
                is ScriptCommand.OnInterval -> setupOnInterval(trigger)
                else -> {}
            }
        }
    }

    private fun setupOnReceive(trigger: ScriptCommand.OnReceive) {
        val targetId = evaluateAsLong(evaluate(trigger.canId))
        log(LogEntryType.INFO, "Trigger: on_receive(0x${targetId.toString(16).uppercase()})")

        val job = scope.launch {
            canDataRepository.monitorFrames.collect { frames ->
                if (shouldStop.get()) return@collect

                val matching = frames.lastOrNull { it.id == targetId }
                if (matching != null) {
                    lastResponse = ReceivedCanFrame(matching.id, matching.data, matching.timestamp)
                    updateResponseVariables(lastResponse!!)
                    try {
                        execute(trigger.body)
                    } catch (e: Exception) {
                        Log.e(TAG, "Trigger error", e)
                    }
                }
            }
        }
        triggerJobs.add(job)
    }

    private fun setupOnInterval(trigger: ScriptCommand.OnInterval) {
        val intervalMs = evaluateAsLong(evaluate(trigger.intervalMs))
        log(LogEntryType.INFO, "Trigger: on_interval(${intervalMs}ms)")

        val job = scope.launch {
            while (!shouldStop.get()) {
                checkPauseAndStop()
                delay(intervalMs)
                if (shouldStop.get()) break

                try {
                    execute(trigger.body)
                } catch (e: Exception) {
                    Log.e(TAG, "Interval trigger error", e)
                }
            }
        }
        triggerJobs.add(job)
    }

    // =============== FRAME COLLECTION ===============

    private fun startFrameCollection() {
        frameCollectorJob = scope.launch {
            canDataRepository.monitorFrames.collect { frames ->
                val latest = frames.lastOrNull() ?: return@collect
                val frame = ReceivedCanFrame(latest.id, latest.data, latest.timestamp)
                responseChannel.trySend(frame)
            }
        }
    }

    private fun stopFrameCollection() {
        frameCollectorJob?.cancel()
        frameCollectorJob = null
    }

    private fun updateResponseVariables(frame: ReceivedCanFrame) {
        variables["response"] = frame
    }

    private fun matchesPattern(data: ByteArray, pattern: Any): Boolean {
        if (pattern is ByteArray) {
            if (pattern.size != data.size) return false
            return pattern.zip(data.toList()).all { (p, d) ->
                p == d || p == WildcardValue.toByte()
            }
        }
        if (pattern is List<*>) {
            return pattern.withIndex().all { (i, p) ->
                if (p == WildcardValue) true
                else if (i < data.size) evaluateAsLong(p ?: 0).toByte() == data[i]
                else false
            }
        }
        return true
    }

    // =============== HELPERS ===============

    private suspend fun checkPauseAndStop() {
        if (shouldStop.get()) {
            throw CancellationException("Script stopped")
        }

        while (isPaused.get() && !shouldStop.get()) {
            delay(100)
        }

        if (shouldStop.get()) {
            throw CancellationException("Script stopped")
        }
    }

    private fun updateState(block: ScriptExecutionState.() -> ScriptExecutionState) {
        _state.value = _state.value.block().copy(
            elapsedTime = if (_state.value.startTime > 0)
                System.currentTimeMillis() - _state.value.startTime
            else 0,
            debugLog = debugLog.takeLast(maxLogEntries)
        )
    }

    private fun log(type: LogEntryType, message: String, line: Int = 0) {
        val entry = ScriptLogEntry(
            timestamp = System.currentTimeMillis(),
            line = line,
            type = type,
            message = message
        )
        debugLog.add(entry)
        Log.d(TAG, "[${type.name}] $message")
        updateState { this }
    }

    private fun error(message: String, line: Int, type: ErrorType) {
        val error = ScriptError(
            line = line,
            message = message,
            type = type
        )
        updateState { copy(errors = errors + error) }
        log(LogEntryType.ERROR, message, line)
    }

    private fun cleanup() {
        triggerJobs.forEach { it.cancel() }
        triggerJobs.clear()
        stopFrameCollection()
    }

    // =============== EXCEPTIONS ===============

    private class BreakException(val line: Int) : Exception()
    private class ContinueException(val line: Int) : Exception()
    private class ReturnException(val value: Any?, val line: Int) : Exception()

    companion object {
        private object WildcardValue {
            fun toByte(): Byte = 0xFF.toByte()
        }
    }
}
