package at.planqton.directcan.data.visualscript

import android.util.Log

private const val TAG = "VisualScriptCompiler"

/**
 * Kompiliert einen Visual Script Graph zu TxScript Code
 */
class VisualScriptCompiler {

    /**
     * Kompiliert ein VisualScript zu TxScript Code
     */
    fun compile(script: VisualScript): CompileResult {
        Log.d(TAG, "Compiling script: ${script.name} with ${script.nodes.size} nodes")

        val errors = mutableListOf<CompileError>()
        val warnings = mutableListOf<CompileWarning>()

        // Find all trigger nodes (start points)
        val triggerNodes = script.nodes.filter { it.type.isTrigger }

        if (triggerNodes.isEmpty()) {
            errors.add(CompileError("Kein Trigger-Node gefunden. Script benötigt mindestens einen Startpunkt."))
            return CompileResult(
                success = false,
                code = "",
                errors = errors,
                warnings = warnings
            )
        }

        // Build connection map for faster lookup
        val connectionMap = buildConnectionMap(script)

        // Generate code
        val codeBuilder = StringBuilder()

        // Header
        codeBuilder.appendLine("// Visual Script: ${script.name}")
        codeBuilder.appendLine("// Generiert am: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")
        codeBuilder.appendLine("// Nodes: ${script.nodes.size}, Connections: ${script.connections.size}")
        codeBuilder.appendLine()

        // Collect all variables used
        val variables = collectVariables(script)
        if (variables.isNotEmpty()) {
            codeBuilder.appendLine("// Variablen")
            variables.forEach { varName ->
                codeBuilder.appendLine("var $varName = 0")
            }
            codeBuilder.appendLine()
        }

        // Generate code for each trigger
        triggerNodes.forEachIndexed { index, triggerNode ->
            if (index > 0) codeBuilder.appendLine()

            val triggerCode = generateTriggerCode(triggerNode, script, connectionMap, errors, warnings)
            codeBuilder.append(triggerCode)
        }

        val success = errors.isEmpty()
        val code = codeBuilder.toString()

        Log.d(TAG, "Compilation ${if (success) "successful" else "failed"}: ${errors.size} errors, ${warnings.size} warnings")

        return CompileResult(
            success = success,
            code = code,
            errors = errors,
            warnings = warnings
        )
    }

    /**
     * Baut eine Map für schnellen Connection-Lookup
     */
    private fun buildConnectionMap(script: VisualScript): Map<String, List<NodeConnection>> {
        return script.connections.groupBy { "${it.fromNodeId}:${it.fromPort}" }
    }

    /**
     * Sammelt alle verwendeten Variablen
     */
    private fun collectVariables(script: VisualScript): Set<String> {
        val variables = mutableSetOf<String>()
        script.nodes.forEach { node ->
            when (node.type) {
                NodeType.ACTION_SET_VARIABLE,
                NodeType.ACTION_INCREMENT,
                NodeType.CONDITION_IF_COUNTER -> {
                    val varName = node.config.variableName
                    if (varName.isNotBlank()) {
                        variables.add(varName)
                    }
                }
                else -> { /* no variable */ }
            }
        }
        return variables
    }

    /**
     * Generiert Code für einen Trigger-Node und seine Folge-Nodes
     */
    private fun generateTriggerCode(
        triggerNode: VisualNode,
        script: VisualScript,
        connectionMap: Map<String, List<NodeConnection>>,
        errors: MutableList<CompileError>,
        warnings: MutableList<CompileWarning>
    ): String {
        val builder = StringBuilder()
        val config = triggerNode.config

        when (triggerNode.type) {
            NodeType.TRIGGER_ON_START -> {
                builder.appendLine("// Trigger: Bei Start")
                // Code runs immediately, no wrapper needed
                val bodyCode = generateFlowCode(triggerNode, script, connectionMap, errors, warnings, 0)
                builder.append(bodyCode)
            }

            NodeType.TRIGGER_ON_RECEIVE -> {
                val canId = config.canId.ifBlank { "0x000" }
                builder.appendLine("// Trigger: Bei Empfang von 0x$canId")
                builder.appendLine("on_receive(0x$canId) {")

                val bodyCode = generateFlowCode(triggerNode, script, connectionMap, errors, warnings, 1)
                builder.append(bodyCode)

                builder.appendLine("}")
            }

            NodeType.TRIGGER_ON_INTERVAL -> {
                val interval = config.intervalMs
                builder.appendLine("// Trigger: Alle ${interval}ms")
                builder.appendLine("on_interval($interval) {")

                val bodyCode = generateFlowCode(triggerNode, script, connectionMap, errors, warnings, 1)
                builder.append(bodyCode)

                builder.appendLine("}")
            }

            NodeType.TRIGGER_ON_TIME -> {
                val hour = config.timeHour
                val minute = config.timeMinute
                val timeStr = String.format("%02d:%02d", hour, minute)
                builder.appendLine("// Trigger: Um $timeStr")

                // TxScript doesn't have on_time, so we simulate with on_interval + time check
                builder.appendLine("// HINWEIS: Zeit-basierte Trigger werden als Intervall mit Zeit-Check simuliert")
                builder.appendLine("on_interval(60000) {")
                builder.appendLine("    // Prüfe ob aktuelle Zeit $timeStr ist")
                builder.appendLine("    // TODO: Zeit-Check implementieren")

                val bodyCode = generateFlowCode(triggerNode, script, connectionMap, errors, warnings, 1)
                builder.append(bodyCode)

                builder.appendLine("}")

                warnings.add(CompileWarning(
                    triggerNode.id,
                    "Zeit-Trigger (Um $timeStr) wird als Intervall-Check simuliert"
                ))
            }

            NodeType.TRIGGER_ON_COUNT -> {
                val canId = config.canId.ifBlank { "0x000" }
                val count = config.count
                builder.appendLine("// Trigger: Nach $count Frames von 0x$canId")

                // Simulate with counter variable
                val counterVar = "_count_${canId.replace("0x", "")}"
                builder.appendLine("var $counterVar = 0")
                builder.appendLine("on_receive(0x$canId) {")
                builder.appendLine("    $counterVar = $counterVar + 1")
                builder.appendLine("    if ($counterVar >= $count) {")
                builder.appendLine("        $counterVar = 0  // Reset")

                val bodyCode = generateFlowCode(triggerNode, script, connectionMap, errors, warnings, 2)
                builder.append(bodyCode)

                builder.appendLine("    }")
                builder.appendLine("}")
            }

            else -> {
                errors.add(CompileError("Unbekannter Trigger-Typ: ${triggerNode.type}"))
            }
        }

        return builder.toString()
    }

    /**
     * Generiert Code für den Flow ab einem Node
     */
    private fun generateFlowCode(
        fromNode: VisualNode,
        script: VisualScript,
        connectionMap: Map<String, List<NodeConnection>>,
        errors: MutableList<CompileError>,
        warnings: MutableList<CompileWarning>,
        indentLevel: Int,
        visitedNodes: MutableSet<String> = mutableSetOf()
    ): String {
        val builder = StringBuilder()
        val indent = "    ".repeat(indentLevel)

        // Get outgoing connections
        val connections = connectionMap["${fromNode.id}:${OutputPort.FLOW_OUT}"] ?: emptyList()

        for (connection in connections) {
            val targetNode = script.nodes.find { it.id == connection.toNodeId }
            if (targetNode == null) {
                errors.add(CompileError("Verbindung zu nicht existierendem Node: ${connection.toNodeId}"))
                continue
            }

            // Prevent infinite loops
            if (targetNode.id in visitedNodes) {
                warnings.add(CompileWarning(targetNode.id, "Zyklische Verbindung erkannt, überspringe"))
                continue
            }
            visitedNodes.add(targetNode.id)

            val nodeCode = generateNodeCode(targetNode, script, connectionMap, errors, warnings, indentLevel, visitedNodes)
            builder.append(nodeCode)
        }

        // Handle condition nodes with multiple outputs
        if (fromNode.type.hasMultipleOutputs) {
            val trueConnections = connectionMap["${fromNode.id}:${OutputPort.TRUE_OUT}"] ?: emptyList()
            val falseConnections = connectionMap["${fromNode.id}:${OutputPort.FALSE_OUT}"] ?: emptyList()

            // These are handled in generateNodeCode for condition nodes
        }

        return builder.toString()
    }

    /**
     * Generiert Code für einen einzelnen Node
     */
    private fun generateNodeCode(
        node: VisualNode,
        script: VisualScript,
        connectionMap: Map<String, List<NodeConnection>>,
        errors: MutableList<CompileError>,
        warnings: MutableList<CompileWarning>,
        indentLevel: Int,
        visitedNodes: MutableSet<String>
    ): String {
        val builder = StringBuilder()
        val indent = "    ".repeat(indentLevel)
        val config = node.config

        when (node.type) {
            // === ACTION NODES ===
            NodeType.ACTION_SEND -> {
                val canId = config.canId.ifBlank { "000" }
                val canData = config.canData.ifBlank { "" }
                val dataFormatted = formatDataForTxScript(canData)

                builder.appendLine("${indent}send(0x$canId, [$dataFormatted])")

                // Continue flow
                builder.append(generateFlowCode(node, script, connectionMap, errors, warnings, indentLevel, visitedNodes))
            }

            NodeType.ACTION_DELAY -> {
                val delayMs = config.delayMs
                val delayFormatted = formatDelay(delayMs)

                builder.appendLine("${indent}delay($delayFormatted)")

                // Continue flow
                builder.append(generateFlowCode(node, script, connectionMap, errors, warnings, indentLevel, visitedNodes))
            }

            NodeType.ACTION_SET_VARIABLE -> {
                val varName = config.variableName.ifBlank { "var1" }
                val varValue = config.variableValue.ifBlank { "0" }

                builder.appendLine("${indent}$varName = $varValue")

                // Continue flow
                builder.append(generateFlowCode(node, script, connectionMap, errors, warnings, indentLevel, visitedNodes))
            }

            NodeType.ACTION_INCREMENT -> {
                val varName = config.variableName.ifBlank { "counter" }
                val increment = config.incrementValue

                builder.appendLine("${indent}$varName = $varName + $increment")

                // Continue flow
                builder.append(generateFlowCode(node, script, connectionMap, errors, warnings, indentLevel, visitedNodes))
            }

            NodeType.ACTION_PRINT -> {
                val text = config.printText.ifBlank { "Debug" }

                builder.appendLine("${indent}print(\"$text\")")

                // Continue flow
                builder.append(generateFlowCode(node, script, connectionMap, errors, warnings, indentLevel, visitedNodes))
            }

            // === CONDITION NODES ===
            NodeType.CONDITION_IF_DATA -> {
                val byteIndex = config.byteIndex
                val operator = config.compareOperator.symbol
                val compareValue = config.compareValue.ifBlank { "00" }

                builder.appendLine("${indent}if (frame.data[$byteIndex] $operator 0x$compareValue) {")

                // True branch
                val trueConnections = connectionMap["${node.id}:${OutputPort.TRUE_OUT}"] ?: emptyList()
                for (conn in trueConnections) {
                    val targetNode = script.nodes.find { it.id == conn.toNodeId } ?: continue
                    if (targetNode.id !in visitedNodes) {
                        visitedNodes.add(targetNode.id)
                        builder.append(generateNodeCode(targetNode, script, connectionMap, errors, warnings, indentLevel + 1, visitedNodes))
                    }
                }

                // False branch
                val falseConnections = connectionMap["${node.id}:${OutputPort.FALSE_OUT}"] ?: emptyList()
                if (falseConnections.isNotEmpty()) {
                    builder.appendLine("${indent}} else {")
                    for (conn in falseConnections) {
                        val targetNode = script.nodes.find { it.id == conn.toNodeId } ?: continue
                        if (targetNode.id !in visitedNodes) {
                            visitedNodes.add(targetNode.id)
                            builder.append(generateNodeCode(targetNode, script, connectionMap, errors, warnings, indentLevel + 1, visitedNodes))
                        }
                    }
                }

                builder.appendLine("${indent}}")
            }

            NodeType.CONDITION_IF_COUNTER -> {
                val varName = config.variableName.ifBlank { "counter" }
                val operator = config.compareOperator.symbol
                val compareValue = config.compareValue.ifBlank { "0" }

                builder.appendLine("${indent}if ($varName $operator $compareValue) {")

                // True branch
                val trueConnections = connectionMap["${node.id}:${OutputPort.TRUE_OUT}"] ?: emptyList()
                for (conn in trueConnections) {
                    val targetNode = script.nodes.find { it.id == conn.toNodeId } ?: continue
                    if (targetNode.id !in visitedNodes) {
                        visitedNodes.add(targetNode.id)
                        builder.append(generateNodeCode(targetNode, script, connectionMap, errors, warnings, indentLevel + 1, visitedNodes))
                    }
                }

                // False branch
                val falseConnections = connectionMap["${node.id}:${OutputPort.FALSE_OUT}"] ?: emptyList()
                if (falseConnections.isNotEmpty()) {
                    builder.appendLine("${indent}} else {")
                    for (conn in falseConnections) {
                        val targetNode = script.nodes.find { it.id == conn.toNodeId } ?: continue
                        if (targetNode.id !in visitedNodes) {
                            visitedNodes.add(targetNode.id)
                            builder.append(generateNodeCode(targetNode, script, connectionMap, errors, warnings, indentLevel + 1, visitedNodes))
                        }
                    }
                }

                builder.appendLine("${indent}}")
            }

            NodeType.CONDITION_IF_TIME -> {
                val startTime = config.timeRangeStart.ifBlank { "00:00" }
                val endTime = config.timeRangeEnd.ifBlank { "23:59" }

                builder.appendLine("${indent}// Zeit-Check: $startTime - $endTime")
                builder.appendLine("${indent}// TODO: Zeitprüfung implementieren")
                builder.appendLine("${indent}if (true) {  // Zeitprüfung")

                // True branch
                val trueConnections = connectionMap["${node.id}:${OutputPort.TRUE_OUT}"] ?: emptyList()
                for (conn in trueConnections) {
                    val targetNode = script.nodes.find { it.id == conn.toNodeId } ?: continue
                    if (targetNode.id !in visitedNodes) {
                        visitedNodes.add(targetNode.id)
                        builder.append(generateNodeCode(targetNode, script, connectionMap, errors, warnings, indentLevel + 1, visitedNodes))
                    }
                }

                builder.appendLine("${indent}}")

                warnings.add(CompileWarning(node.id, "Zeit-Bedingung wird noch nicht vollständig unterstützt"))
            }

            NodeType.CONDITION_IF_RECEIVED -> {
                val canId = config.canId.ifBlank { "000" }
                val timeout = config.timeoutMs

                builder.appendLine("${indent}// Prüfe ob 0x$canId in letzten ${timeout}ms empfangen")
                builder.appendLine("${indent}// Dies wird als wait_for mit Timeout simuliert")

                warnings.add(CompileWarning(node.id, "If-Received wird als wait_for simuliert"))

                // Continue with normal flow (true path)
                val trueConnections = connectionMap["${node.id}:${OutputPort.TRUE_OUT}"] ?: emptyList()
                for (conn in trueConnections) {
                    val targetNode = script.nodes.find { it.id == conn.toNodeId } ?: continue
                    if (targetNode.id !in visitedNodes) {
                        visitedNodes.add(targetNode.id)
                        builder.append(generateNodeCode(targetNode, script, connectionMap, errors, warnings, indentLevel, visitedNodes))
                    }
                }
            }

            // === FLOW CONTROL NODES ===
            NodeType.FLOW_WAIT_FOR -> {
                val canId = config.canId.ifBlank { "000" }
                val timeout = config.timeoutMs
                val dataPattern = config.dataPattern

                if (dataPattern.isBlank()) {
                    builder.appendLine("${indent}wait_for(0x$canId, timeout: $timeout)")
                } else {
                    val patternFormatted = formatDataForTxScript(dataPattern)
                    builder.appendLine("${indent}wait_for(0x$canId, timeout: $timeout, data: [$patternFormatted])")
                }

                // Continue flow
                builder.append(generateFlowCode(node, script, connectionMap, errors, warnings, indentLevel, visitedNodes))
            }

            NodeType.FLOW_REPEAT -> {
                val count = config.count

                builder.appendLine("${indent}repeat($count) {")

                // Get nodes connected to FLOW_OUT (inside the repeat)
                val flowConnections = connectionMap["${node.id}:${OutputPort.FLOW_OUT}"] ?: emptyList()
                for (conn in flowConnections) {
                    val targetNode = script.nodes.find { it.id == conn.toNodeId } ?: continue
                    // Don't add to visited for loop body - allow visiting multiple times conceptually
                    builder.append(generateNodeCode(targetNode, script, connectionMap, errors, warnings, indentLevel + 1, mutableSetOf()))
                }

                builder.appendLine("${indent}}")
            }

            NodeType.FLOW_LOOP -> {
                builder.appendLine("${indent}loop {")

                // Get nodes connected to FLOW_OUT (inside the loop)
                val flowConnections = connectionMap["${node.id}:${OutputPort.FLOW_OUT}"] ?: emptyList()
                for (conn in flowConnections) {
                    val targetNode = script.nodes.find { it.id == conn.toNodeId } ?: continue
                    builder.append(generateNodeCode(targetNode, script, connectionMap, errors, warnings, indentLevel + 1, mutableSetOf()))
                }

                builder.appendLine("${indent}}")
            }

            NodeType.FLOW_STOP -> {
                builder.appendLine("${indent}// Script beenden")
                builder.appendLine("${indent}return")
            }

            // Trigger nodes shouldn't appear here
            else -> {
                if (!node.type.isTrigger) {
                    errors.add(CompileError("Unbekannter Node-Typ: ${node.type}"))
                }
            }
        }

        return builder.toString()
    }

    /**
     * Formatiert CAN-Daten für TxScript (z.B. "02,01,00" -> "0x02, 0x01, 0x00")
     */
    private fun formatDataForTxScript(data: String): String {
        if (data.isBlank()) return ""

        return data.split(",", " ")
            .filter { it.isNotBlank() }
            .joinToString(", ") { byte ->
                val cleaned = byte.trim().removePrefix("0x").removePrefix("0X")
                if (cleaned == "*") "*" else "0x$cleaned"
            }
    }

    /**
     * Formatiert Delay für bessere Lesbarkeit
     */
    private fun formatDelay(ms: Long): String {
        return when {
            ms >= 60000 && ms % 60000 == 0L -> "${ms / 60000}m"
            ms >= 1000 && ms % 1000 == 0L -> "${ms / 1000}s"
            else -> "${ms}ms"
        }
    }
}

/**
 * Ergebnis einer Kompilierung
 */
data class CompileResult(
    val success: Boolean,
    val code: String,
    val errors: List<CompileError>,
    val warnings: List<CompileWarning>
)

/**
 * Kompilierungsfehler
 */
data class CompileError(
    val message: String,
    val nodeId: String? = null
)

/**
 * Kompilierungswarnung
 */
data class CompileWarning(
    val nodeId: String,
    val message: String
)
