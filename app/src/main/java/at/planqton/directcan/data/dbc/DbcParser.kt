package at.planqton.directcan.data.dbc

import java.io.InputStream

class DbcParser {

    fun parse(input: InputStream): DbcFile {
        val content = input.bufferedReader().readText()
        return parse(content)
    }

    fun parse(content: String): DbcFile {
        val lines = content.lines()
        var version = ""
        val nodes = mutableListOf<DbcNode>()
        val messages = mutableListOf<DbcMessage>()
        val valueTables = mutableMapOf<String, MutableMap<Int, String>>()
        val attributes = mutableMapOf<String, String>()
        val messageComments = mutableMapOf<Long, String>()
        val signalComments = mutableMapOf<Pair<Long, String>, String>()
        val signalValueDescriptions = mutableMapOf<Pair<Long, String>, MutableMap<Int, String>>()

        var currentMessage: MutableDbcMessage? = null
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()

            when {
                line.startsWith("VERSION") -> {
                    version = parseVersion(line)
                }

                line.startsWith("BU_:") -> {
                    val nodeNames = line.substringAfter("BU_:").trim().split("\\s+".toRegex())
                    nodes.addAll(nodeNames.filter { it.isNotEmpty() }.map { DbcNode(it) })
                }

                line.startsWith("BO_ ") -> {
                    currentMessage?.let { messages.add(it.toDbcMessage()) }
                    currentMessage = parseMessage(line)
                }

                line.startsWith(" SG_ ") || line.startsWith("SG_ ") -> {
                    currentMessage?.let { msg ->
                        parseSignal(line)?.let { signal ->
                            msg.signals.add(signal.toMutable())
                        }
                    }
                }

                line.startsWith("CM_ BO_ ") -> {
                    parseMessageComment(line, lines, i)?.let { (id, comment, newIndex) ->
                        messageComments[id] = comment
                        i = newIndex
                    }
                }

                line.startsWith("CM_ SG_ ") -> {
                    parseSignalComment(line, lines, i)?.let { (id, signalName, comment, newIndex) ->
                        signalComments[id to signalName] = comment
                        i = newIndex
                    }
                }

                line.startsWith("VAL_ ") -> {
                    parseValueDescriptions(line)?.let { (msgId, signalName, values) ->
                        signalValueDescriptions.getOrPut(msgId to signalName) { mutableMapOf() }
                            .putAll(values)
                    }
                }

                line.startsWith("VAL_TABLE_ ") -> {
                    parseValueTable(line)?.let { (name, values) ->
                        valueTables[name] = values.toMutableMap()
                    }
                }

                line.startsWith("BA_ ") -> {
                    parseAttribute(line)?.let { (key, value) ->
                        attributes[key] = value
                    }
                }
            }
            i++
        }

        currentMessage?.let { messages.add(it.toDbcMessage()) }

        // Apply comments and value descriptions
        val finalMessages = messages.map { msg ->
            val comment = messageComments[msg.id] ?: msg.description
            val updatedSignals = msg.signals.map { signal ->
                val sigComment = signalComments[msg.id to signal.name] ?: signal.description
                val sigValues = signalValueDescriptions[msg.id to signal.name] ?: signal.valueDescriptions
                signal.copy(description = sigComment, valueDescriptions = sigValues)
            }
            msg.copy(description = comment, signals = updatedSignals)
        }

        return DbcFile(
            version = version,
            nodes = nodes,
            messages = finalMessages,
            valueTables = valueTables,
            attributes = attributes
        )
    }

    private fun parseVersion(line: String): String {
        val match = Regex("VERSION\\s+\"([^\"]*)\"").find(line)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun parseMessage(line: String): MutableDbcMessage? {
        // BO_ <id> <name>: <length> <transmitter>
        val match = Regex("BO_\\s+(\\d+)\\s+(\\w+)\\s*:\\s*(\\d+)\\s*(\\w*)").find(line)
        return match?.let {
            val id = it.groupValues[1].toLong()
            val name = it.groupValues[2]
            val length = it.groupValues[3].toInt()
            val transmitter = it.groupValues[4]
            MutableDbcMessage(id, name, length, transmitter)
        }
    }

    private fun parseSignal(line: String): DbcSignal? {
        // SG_ <name> : <startBit>|<length>@<byteOrder><valueType> (<factor>,<offset>) [<min>|<max>] "<unit>" <receivers>
        val pattern = Regex(
            """SG_\s+(\w+)\s*(?:(\w+)\s*)?:\s*(\d+)\|(\d+)@([01])([+-])\s*\(([^,]+),([^)]+)\)\s*\[([^|]+)\|([^\]]+)\]\s*"([^"]*)"\s*(.*)"""
        )
        val match = pattern.find(line.trim())
        return match?.let {
            val name = it.groupValues[1]
            val startBit = it.groupValues[3].toInt()
            val length = it.groupValues[4].toInt()
            val byteOrder = if (it.groupValues[5] == "0") DbcSignal.ByteOrder.BIG_ENDIAN else DbcSignal.ByteOrder.LITTLE_ENDIAN
            val valueType = if (it.groupValues[6] == "-") DbcSignal.ValueType.SIGNED else DbcSignal.ValueType.UNSIGNED
            val factor = it.groupValues[7].trim().toDoubleOrNull() ?: 1.0
            val offset = it.groupValues[8].trim().toDoubleOrNull() ?: 0.0
            val min = it.groupValues[9].trim().toDoubleOrNull() ?: 0.0
            val max = it.groupValues[10].trim().toDoubleOrNull() ?: 0.0
            val unit = it.groupValues[11]
            val receivers = it.groupValues[12].trim().split(",").map { r -> r.trim() }.filter { r -> r.isNotEmpty() && r != "Vector__XXX" }

            DbcSignal(
                name = name,
                startBit = startBit,
                length = length,
                byteOrder = byteOrder,
                valueType = valueType,
                factor = factor,
                offset = offset,
                min = min,
                max = max,
                unit = unit,
                receivers = receivers
            )
        }
    }

    private fun parseMessageComment(line: String, lines: List<String>, startIndex: Int): Triple<Long, String, Int>? {
        val pattern = Regex("CM_\\s+BO_\\s+(\\d+)\\s+\"(.*)\"?")
        var fullLine = line
        var endIndex = startIndex

        // Multi-line comment handling
        if (!line.endsWith("\";")) {
            while (endIndex < lines.size - 1) {
                endIndex++
                fullLine += "\n" + lines[endIndex]
                if (lines[endIndex].contains("\";")) break
            }
        }

        val match = Regex("CM_\\s+BO_\\s+(\\d+)\\s+\"(.*)\"\\s*;", RegexOption.DOT_MATCHES_ALL).find(fullLine)
        return match?.let {
            Triple(it.groupValues[1].toLong(), it.groupValues[2].trim(), endIndex)
        }
    }

    private fun parseSignalComment(line: String, lines: List<String>, startIndex: Int): Tuple4<Long, String, String, Int>? {
        var fullLine = line
        var endIndex = startIndex

        if (!line.endsWith("\";")) {
            while (endIndex < lines.size - 1) {
                endIndex++
                fullLine += "\n" + lines[endIndex]
                if (lines[endIndex].contains("\";")) break
            }
        }

        val match = Regex("CM_\\s+SG_\\s+(\\d+)\\s+(\\w+)\\s+\"(.*)\"\\s*;", RegexOption.DOT_MATCHES_ALL).find(fullLine)
        return match?.let {
            Tuple4(it.groupValues[1].toLong(), it.groupValues[2], it.groupValues[3].trim(), endIndex)
        }
    }

    private fun parseValueDescriptions(line: String): Triple<Long, String, Map<Int, String>>? {
        // VAL_ <msgId> <signalName> <value> "<desc>" ... ;
        val headerMatch = Regex("VAL_\\s+(\\d+)\\s+(\\w+)\\s+(.*)").find(line) ?: return null

        val msgId = headerMatch.groupValues[1].toLong()
        val signalName = headerMatch.groupValues[2]
        val rest = headerMatch.groupValues[3]

        val values = mutableMapOf<Int, String>()
        val valuePattern = Regex("(\\d+)\\s+\"([^\"]*)\"")
        valuePattern.findAll(rest).forEach { match ->
            val value = match.groupValues[1].toInt()
            val desc = match.groupValues[2]
            values[value] = desc
        }

        return Triple(msgId, signalName, values)
    }

    private fun parseValueTable(line: String): Pair<String, Map<Int, String>>? {
        val headerMatch = Regex("VAL_TABLE_\\s+(\\w+)\\s+(.*)").find(line) ?: return null

        val name = headerMatch.groupValues[1]
        val rest = headerMatch.groupValues[2]

        val values = mutableMapOf<Int, String>()
        val valuePattern = Regex("(\\d+)\\s+\"([^\"]*)\"")
        valuePattern.findAll(rest).forEach { match ->
            val value = match.groupValues[1].toInt()
            val desc = match.groupValues[2]
            values[value] = desc
        }

        return Pair(name, values)
    }

    private fun parseAttribute(line: String): Pair<String, String>? {
        val match = Regex("BA_\\s+\"(\\w+)\"\\s+(.*)\\s*;").find(line)
        return match?.let {
            Pair(it.groupValues[1], it.groupValues[2].trim().removeSurrounding("\""))
        }
    }

    // Helper data classes
    private data class MutableDbcMessage(
        val id: Long,
        val name: String,
        val length: Int,
        val transmitter: String,
        val signals: MutableList<MutableDbcSignal> = mutableListOf()
    ) {
        fun toDbcMessage() = DbcMessage(
            id = id,
            name = name,
            length = length,
            transmitter = transmitter,
            signals = signals.map { it.toDbcSignal() }
        )
    }

    private data class MutableDbcSignal(
        val name: String,
        val startBit: Int,
        val length: Int,
        val byteOrder: DbcSignal.ByteOrder,
        val valueType: DbcSignal.ValueType,
        val factor: Double,
        val offset: Double,
        val min: Double,
        val max: Double,
        val unit: String,
        val receivers: List<String>
    ) {
        fun toDbcSignal() = DbcSignal(
            name = name,
            startBit = startBit,
            length = length,
            byteOrder = byteOrder,
            valueType = valueType,
            factor = factor,
            offset = offset,
            min = min,
            max = max,
            unit = unit,
            receivers = receivers
        )
    }

    private fun DbcSignal.toMutable() = MutableDbcSignal(
        name, startBit, length, byteOrder, valueType, factor, offset, min, max, unit, receivers
    )

    private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    companion object {
        fun generateDbc(dbcFile: DbcFile): String {
            val sb = StringBuilder()

            sb.appendLine("VERSION \"${dbcFile.version}\"")
            sb.appendLine()
            sb.appendLine("NS_ :")
            sb.appendLine()
            sb.appendLine("BS_:")
            sb.appendLine()

            // Nodes
            sb.append("BU_:")
            dbcFile.nodes.forEach { sb.append(" ${it.name}") }
            sb.appendLine()
            sb.appendLine()

            // Messages and signals
            dbcFile.messages.forEach { msg ->
                sb.appendLine("BO_ ${msg.id} ${msg.name}: ${msg.length} ${msg.transmitter}")
                msg.signals.forEach { sig ->
                    val byteOrder = if (sig.byteOrder == DbcSignal.ByteOrder.LITTLE_ENDIAN) "1" else "0"
                    val valueType = if (sig.valueType == DbcSignal.ValueType.SIGNED) "-" else "+"
                    val receivers = sig.receivers.ifEmpty { listOf("Vector__XXX") }.joinToString(",")
                    sb.appendLine(" SG_ ${sig.name} : ${sig.startBit}|${sig.length}@${byteOrder}${valueType} (${sig.factor},${sig.offset}) [${sig.min}|${sig.max}] \"${sig.unit}\" $receivers")
                }
                sb.appendLine()
            }

            // Comments
            dbcFile.messages.forEach { msg ->
                if (msg.description.isNotEmpty()) {
                    sb.appendLine("CM_ BO_ ${msg.id} \"${msg.description}\";")
                }
                msg.signals.forEach { sig ->
                    if (sig.description.isNotEmpty()) {
                        sb.appendLine("CM_ SG_ ${msg.id} ${sig.name} \"${sig.description}\";")
                    }
                }
            }

            // Value descriptions
            dbcFile.messages.forEach { msg ->
                msg.signals.forEach { sig ->
                    if (sig.valueDescriptions.isNotEmpty()) {
                        sb.append("VAL_ ${msg.id} ${sig.name}")
                        sig.valueDescriptions.forEach { (value, desc) ->
                            sb.append(" $value \"$desc\"")
                        }
                        sb.appendLine(" ;")
                    }
                }
            }

            return sb.toString()
        }
    }
}
