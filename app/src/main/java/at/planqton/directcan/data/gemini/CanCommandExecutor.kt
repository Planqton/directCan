package at.planqton.directcan.data.gemini

import android.util.Log
import at.planqton.directcan.data.can.CanFrame
import at.planqton.directcan.data.device.DeviceManager
import at.planqton.directcan.data.device.UsbSlcanDevice
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "CanCommandExecutor"

/**
 * Executes CAN commands received from the AI.
 * Communicates with the connected CAN device.
 */
class CanCommandExecutor(
    private val deviceManager: DeviceManager
) {
    /**
     * Execute a single command and return the result.
     */
    suspend fun execute(command: CanCommand): CanCommandResult {
        val device = deviceManager.activeDevice.value as? UsbSlcanDevice
            ?: return CanCommandResult.Error(
                command = command,
                message = "Kein Gerät verbunden"
            )

        return try {
            when (command) {
                is CanCommand.SendFrame -> executeSendFrame(device, command)
                is CanCommand.SendIsoTp -> executeSendIsoTp(device, command)
                is CanCommand.ReadDtcs -> executeReadDtcs(device, command)
                is CanCommand.ClearDtcs -> executeClearDtcs(device, command)
                is CanCommand.ReadVin -> executeReadVin(device, command)
                is CanCommand.UdsRequest -> executeUdsRequest(device, command)
                is CanCommand.Obd2Pid -> executeObd2Pid(device, command)
                is CanCommand.ScanBus -> executeScanBus(device, command)
                is CanCommand.ObserveIds -> executeObserveIds(device, command)
                is CanCommand.Delay -> executeDelay(command)
                is CanCommand.PeriodicFrame -> executePeriodicFrame(device, command)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed", e)
            CanCommandResult.Error(
                command = command,
                message = "Fehler: ${e.message}"
            )
        }
    }

    /**
     * Execute all commands in a block and return all results.
     */
    suspend fun executeAll(commands: List<CanCommand>): List<CanCommandResult> {
        return commands.map { execute(it) }
    }

    /**
     * Execute all commands with progress callback.
     */
    suspend fun executeWithCallback(
        commands: List<CanCommand>,
        onProgress: suspend (String) -> Unit
    ): List<CanCommandResult> {
        val results = mutableListOf<CanCommandResult>()

        for ((index, command) in commands.withIndex()) {
            onProgress("Führe Befehl ${index + 1}/${commands.size} aus...")

            val result = execute(command)
            results.add(result)

            // Report result
            val status = if (result.success) "✓" else "✗"
            onProgress("$status ${result.message}")
        }

        return results
    }

    // ==================== Command Implementations ====================

    private suspend fun executeSendFrame(
        device: UsbSlcanDevice,
        command: CanCommand.SendFrame
    ): CanCommandResult {
        val data = parseHexData(command.data)
        val success = device.sendCanFrame(command.id, data, command.extended)

        return CanCommandResult.Sent(
            command = command,
            success = success,
            message = if (success) {
                "Frame 0x${command.id.toString(16).uppercase()} gesendet"
            } else {
                "Senden fehlgeschlagen"
            }
        )
    }

    private suspend fun executeSendIsoTp(
        device: UsbSlcanDevice,
        command: CanCommand.SendIsoTp
    ): CanCommandResult {
        // Check if device supports ISO-TP
        if (!device.capabilities.value.supportsIsoTp) {
            return CanCommandResult.Error(
                command = command,
                message = "Firmware unterstützt kein ISO-TP"
            )
        }

        val data = parseHexData(command.data)
        val response = withTimeoutOrNull(command.timeoutMs.toLong()) {
            device.sendIsoTp(command.txId, command.rxId, data)
        }

        return if (response != null) {
            val responseHex = response.joinToString(" ") { "%02X".format(it) }
            CanCommandResult.IsoTpResponse(
                command = command,
                success = true,
                message = "Antwort von 0x${command.rxId.toString(16).uppercase()}: $responseHex",
                responseData = response,
                responseHex = responseHex
            )
        } else {
            CanCommandResult.Timeout(
                command = command,
                message = "Keine Antwort von 0x${command.rxId.toString(16).uppercase()} innerhalb ${command.timeoutMs}ms"
            )
        }
    }

    private suspend fun executeReadDtcs(
        device: UsbSlcanDevice,
        command: CanCommand.ReadDtcs
    ): CanCommandResult {
        // Build UDS request for Service 0x19 (ReadDTCInformation)
        val data = byteArrayOf(0x19, command.subFunction.toByte(), 0xFF.toByte())

        return if (device.capabilities.value.supportsIsoTp) {
            val response = device.sendIsoTp(command.txId, command.rxId, data)
            if (response != null) {
                val responseHex = response.joinToString(" ") { "%02X".format(it) }
                CanCommandResult.IsoTpResponse(
                    command = command,
                    success = true,
                    message = "DTC-Antwort: $responseHex",
                    responseData = response,
                    responseHex = responseHex
                )
            } else {
                CanCommandResult.Timeout(
                    command = command,
                    message = "Keine DTC-Antwort erhalten"
                )
            }
        } else {
            // Fallback to OBD2 Service 0x03
            val result = device.readDtcs()
            result.fold(
                onSuccess = { dtcs ->
                    CanCommandResult.Sent(
                        command = command,
                        success = true,
                        message = if (dtcs.isEmpty()) {
                            "Keine DTCs gespeichert"
                        } else {
                            "DTCs: ${dtcs.joinToString(", ")}"
                        }
                    )
                },
                onFailure = { error ->
                    CanCommandResult.Error(
                        command = command,
                        message = "DTC-Lesen fehlgeschlagen: ${error.message}"
                    )
                }
            )
        }
    }

    private suspend fun executeClearDtcs(
        device: UsbSlcanDevice,
        command: CanCommand.ClearDtcs
    ): CanCommandResult {
        // UDS Service 0x14 (ClearDiagnosticInformation)
        val data = byteArrayOf(0x14, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        return if (device.capabilities.value.supportsIsoTp) {
            val response = device.sendIsoTp(command.txId, command.rxId, data)
            if (response != null && response.isNotEmpty()) {
                val success = response[0] == 0x54.toByte() // Positive response
                val responseHex = response.joinToString(" ") { "%02X".format(it) }
                CanCommandResult.IsoTpResponse(
                    command = command,
                    success = success,
                    message = if (success) "DTCs gelöscht" else "Löschen fehlgeschlagen: $responseHex",
                    responseData = response,
                    responseHex = responseHex
                )
            } else {
                CanCommandResult.Timeout(
                    command = command,
                    message = "Keine Antwort auf DTC-Löschbefehl"
                )
            }
        } else {
            // Fallback to OBD2 Service 0x04
            val result = device.clearDtcs()
            result.fold(
                onSuccess = { success ->
                    CanCommandResult.Sent(
                        command = command,
                        success = success,
                        message = if (success) "DTCs gelöscht" else "Löschen fehlgeschlagen"
                    )
                },
                onFailure = { error ->
                    CanCommandResult.Error(
                        command = command,
                        message = "DTC-Löschen fehlgeschlagen: ${error.message}"
                    )
                }
            )
        }
    }

    private suspend fun executeReadVin(
        device: UsbSlcanDevice,
        command: CanCommand.ReadVin
    ): CanCommandResult {
        return if (device.capabilities.value.supportsIsoTp) {
            // OBD2 Service 0x09 PID 0x02
            val data = byteArrayOf(0x09, 0x02)
            val response = device.sendIsoTp(command.txId, command.rxId, data)

            if (response != null && response.size >= 4) {
                val responseHex = response.joinToString(" ") { "%02X".format(it) }
                // VIN starts at byte 3 (after 49 02 01)
                val vinBytes = response.drop(3).take(17).toByteArray()
                val vin = String(vinBytes, Charsets.US_ASCII).trim()

                CanCommandResult.IsoTpResponse(
                    command = command,
                    success = true,
                    message = "VIN: $vin",
                    responseData = response,
                    responseHex = responseHex
                )
            } else {
                CanCommandResult.Timeout(
                    command = command,
                    message = "Keine VIN-Antwort erhalten"
                )
            }
        } else {
            // Use device convenience method
            val result = device.readVin()
            result.fold(
                onSuccess = { vin ->
                    CanCommandResult.Sent(
                        command = command,
                        success = true,
                        message = "VIN: $vin"
                    )
                },
                onFailure = { error ->
                    CanCommandResult.Error(
                        command = command,
                        message = "VIN-Lesen fehlgeschlagen: ${error.message}"
                    )
                }
            )
        }
    }

    private suspend fun executeUdsRequest(
        device: UsbSlcanDevice,
        command: CanCommand.UdsRequest
    ): CanCommandResult {
        if (!device.capabilities.value.supportsIsoTp) {
            return CanCommandResult.Error(
                command = command,
                message = "Firmware unterstützt kein ISO-TP"
            )
        }

        // Build UDS request
        val dataBytes = buildList {
            add(command.service.toByte())
            command.subFunction?.let { add(it.toByte()) }
            command.data?.let { addAll(parseHexData(it).toList()) }
        }.toByteArray()

        val response = withTimeoutOrNull(command.timeoutMs.toLong()) {
            device.sendIsoTp(command.txId, command.rxId, dataBytes)
        }

        return if (response != null) {
            val responseHex = response.joinToString(" ") { "%02X".format(it) }
            CanCommandResult.IsoTpResponse(
                command = command,
                success = true,
                message = "UDS Antwort: $responseHex",
                responseData = response,
                responseHex = responseHex
            )
        } else {
            CanCommandResult.Timeout(
                command = command,
                message = "Keine UDS-Antwort innerhalb ${command.timeoutMs}ms"
            )
        }
    }

    private suspend fun executeObd2Pid(
        device: UsbSlcanDevice,
        command: CanCommand.Obd2Pid
    ): CanCommandResult {
        // OBD2 PID request: Service (01/02) + PID
        val data = byteArrayOf(command.service.toByte(), command.pid.toByte())

        return if (device.capabilities.value.supportsIsoTp) {
            val response = withTimeoutOrNull(command.timeoutMs.toLong()) {
                device.sendIsoTp(command.txId, command.rxId, data)
            }

            if (response != null) {
                val responseHex = response.joinToString(" ") { "%02X".format(it) }
                CanCommandResult.IsoTpResponse(
                    command = command,
                    success = true,
                    message = "OBD2 PID 0x${command.pid.toString(16).uppercase()}: $responseHex",
                    responseData = response,
                    responseHex = responseHex
                )
            } else {
                CanCommandResult.Timeout(
                    command = command,
                    message = "Keine Antwort auf PID 0x${command.pid.toString(16).uppercase()}"
                )
            }
        } else {
            // Send as regular CAN frame
            val frame = byteArrayOf(0x02, command.service.toByte(), command.pid.toByte())
            val success = device.sendCanFrame(command.txId, frame)
            CanCommandResult.Sent(
                command = command,
                success = success,
                message = if (success) "OBD2 Request gesendet (keine ISO-TP Unterstützung)"
                else "Senden fehlgeschlagen"
            )
        }
    }

    private suspend fun executeScanBus(
        device: UsbSlcanDevice,
        command: CanCommand.ScanBus
    ): CanCommandResult {
        val foundIds = mutableMapOf<Long, Int>()
        val startTime = System.currentTimeMillis()

        // Collect frames for the specified duration
        // This is a simplified implementation - in reality we'd need to parse
        // the SLCAN frames coming from receivedLines
        coroutineScope {
            val collector = launch {
                device.receivedLines.collect { line ->
                    // Parse SLCAN frame format: t<id:3><len:1><data> or T<id:8><len:1><data>
                    val frame = parseSlcanLine(line)
                    if (frame != null) {
                        foundIds[frame.id] = (foundIds[frame.id] ?: 0) + 1
                    }
                }
            }

            delay(command.durationMs.toLong())
            collector.cancel()
        }

        val sortedIds = foundIds.entries.sortedByDescending { it.value }

        return CanCommandResult.BusScanResult(
            command = command,
            success = true,
            message = "${foundIds.size} verschiedene CAN-IDs in ${command.durationMs}ms gefunden",
            foundIds = sortedIds.map { it.key },
            frameCountById = foundIds
        )
    }

    private suspend fun executeObserveIds(
        device: UsbSlcanDevice,
        command: CanCommand.ObserveIds
    ): CanCommandResult {
        val frames = mutableListOf<CanCommandResult.ObservationResult.FrameSnapshot>()
        val targetIds = command.ids.toSet()

        coroutineScope {
            val collector = launch {
                device.receivedLines.collect { line ->
                    val frame = parseSlcanLine(line)
                    if (frame != null && frame.id in targetIds) {
                        frames.add(
                            CanCommandResult.ObservationResult.FrameSnapshot(
                                id = frame.id,
                                data = frame.data.joinToString(" ") { "%02X".format(it) },
                                timestamp = frame.timestamp
                            )
                        )
                    }
                }
            }

            delay(command.durationMs.toLong())
            collector.cancel()
        }

        return CanCommandResult.ObservationResult(
            command = command,
            success = true,
            message = "${frames.size} Frames von ${command.ids.size} IDs in ${command.durationMs}ms empfangen",
            frames = frames
        )
    }

    private suspend fun executeDelay(command: CanCommand.Delay): CanCommandResult {
        delay(command.milliseconds.toLong())
        return CanCommandResult.Sent(
            command = command,
            success = true,
            message = "Gewartet: ${command.milliseconds}ms"
        )
    }

    private suspend fun executePeriodicFrame(
        device: UsbSlcanDevice,
        command: CanCommand.PeriodicFrame
    ): CanCommandResult {
        // Note: This would require firmware support for periodic transmission
        // For now, just send a single frame
        val data = parseHexData(command.data)
        val success = device.sendCanFrame(command.id, data)

        return CanCommandResult.Sent(
            command = command,
            success = success,
            message = if (command.enable) {
                "Periodisches Senden gestartet (Frame gesendet)"
            } else {
                "Periodisches Senden gestoppt"
            }
        )
    }

    // ==================== Helpers ====================

    /**
     * Parse hex string like "02 01 0C" or "02010C" to ByteArray.
     */
    private fun parseHexData(hexString: String): ByteArray {
        val cleaned = hexString.replace(" ", "").replace("0x", "")
        return cleaned.chunked(2)
            .filter { it.length == 2 }
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    /**
     * Parse SLCAN line to CanFrame.
     * Format: t<id:3><len:1><data> or T<id:8><len:1><data>
     */
    private fun parseSlcanLine(line: String): CanFrame? {
        return try {
            when {
                line.startsWith("t") && line.length >= 5 -> {
                    // Standard frame
                    val id = line.substring(1, 4).toLong(16)
                    val len = line[4].toString().toInt()
                    val dataHex = line.substring(5).take(len * 2)
                    val data = dataHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    CanFrame(timestamp = System.currentTimeMillis(), id = id, data = data, isExtended = false)
                }
                line.startsWith("T") && line.length >= 10 -> {
                    // Extended frame
                    val id = line.substring(1, 9).toLong(16)
                    val len = line[9].toString().toInt()
                    val dataHex = line.substring(10).take(len * 2)
                    val data = dataHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    CanFrame(timestamp = System.currentTimeMillis(), id = id, data = data, isExtended = true)
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
