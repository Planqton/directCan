package at.planqton.directcan.data.txscript

import kotlinx.serialization.Serializable

/**
 * Represents the current state of a script execution.
 */
enum class ScriptState {
    IDLE,       // Not started
    RUNNING,    // Actively executing
    PAUSED,     // Temporarily paused
    STOPPED,    // Stopped/Reset
    ERROR,      // Error occurred
    COMPLETED   // Successfully finished
}

/**
 * Complete execution state for a running script.
 */
data class ScriptExecutionState(
    val scriptId: String = "",
    val scriptName: String = "",
    val state: ScriptState = ScriptState.IDLE,
    val currentLine: Int = 0,
    val totalLines: Int = 0,
    val iteration: Int = 0,
    val startTime: Long = 0,
    val elapsedTime: Long = 0,
    val framesSent: Int = 0,
    val framesReceived: Int = 0,
    val variables: Map<String, Any> = emptyMap(),
    val errors: List<ScriptError> = emptyList(),
    val debugLog: List<ScriptLogEntry> = emptyList()
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
    val errorCount: Int get() = errors.size

    val progress: Float
        get() = if (totalLines > 0) currentLine.toFloat() / totalLines else 0f

    val isRunning: Boolean get() = state == ScriptState.RUNNING
    val isPaused: Boolean get() = state == ScriptState.PAUSED
    val isActive: Boolean get() = state == ScriptState.RUNNING || state == ScriptState.PAUSED
    val isCompleted: Boolean get() = state == ScriptState.COMPLETED
    val isStopped: Boolean get() = state == ScriptState.STOPPED || state == ScriptState.IDLE

    companion object {
        val EMPTY = ScriptExecutionState()
    }
}

/**
 * Represents an error that occurred during script parsing or execution.
 */
@Serializable
data class ScriptError(
    val line: Int,
    val column: Int = 0,
    val message: String,
    val type: ErrorType,
    val timestamp: Long = System.currentTimeMillis()
) {
    val locationString: String
        get() = if (column > 0) "Line $line:$column" else "Line $line"
}

/**
 * Types of errors that can occur.
 */
@Serializable
enum class ErrorType {
    PARSE_ERROR,        // Syntax error
    RUNTIME_ERROR,      // Runtime error
    TIMEOUT_ERROR,      // wait_for timeout
    SEND_ERROR,         // USB send failed
    TYPE_ERROR,         // Wrong data type
    UNDEFINED_VARIABLE, // Variable not defined
    UNDEFINED_FUNCTION, // Function not defined
    INVALID_ARGUMENT,   // Invalid argument value
    CONNECTION_ERROR    // Not connected to CAN bus
}

/**
 * Log entry for script execution.
 */
@Serializable
data class ScriptLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val line: Int = 0,
    val type: LogEntryType,
    val message: String
)

/**
 * Types of log entries.
 */
@Serializable
enum class LogEntryType {
    INFO,       // General info
    DEBUG,      // Debug message (print statements)
    WARN,       // Warning
    ERROR,      // Error
    SEND,       // Frame sent
    RECEIVE,    // Frame received
    STATE       // State change
}
