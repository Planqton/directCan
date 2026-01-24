package at.planqton.directcan.data.visualscript

import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Visual Script - Ein Node-Graph basiertes Automatisierungsskript
 */
@Serializable
data class VisualScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val nodes: List<VisualNode> = emptyList(),
    val connections: List<NodeConnection> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val canvasOffset: SerializableOffset = SerializableOffset(0f, 0f),
    val canvasZoom: Float = 1f
) {
    companion object {
        const val FILE_EXTENSION = ".vscript"
    }
}

/**
 * Serialisierbarer Offset für Canvas-Position
 */
@Serializable
data class SerializableOffset(
    val x: Float,
    val y: Float
) {
    fun toOffset(): Offset = Offset(x, y)

    companion object {
        fun fromOffset(offset: Offset) = SerializableOffset(offset.x, offset.y)
    }
}

/**
 * Ein einzelner Node im Visual Script Graph
 */
@Serializable
data class VisualNode(
    val id: String = UUID.randomUUID().toString(),
    val type: NodeType,
    val position: SerializableOffset,
    val config: NodeConfig = NodeConfig()
)

/**
 * Verbindung zwischen zwei Nodes
 */
@Serializable
data class NodeConnection(
    val id: String = UUID.randomUUID().toString(),
    val fromNodeId: String,
    val fromPort: OutputPort,
    val toNodeId: String,
    val toPort: InputPort = InputPort.FLOW_IN
)

/**
 * Output-Ports eines Nodes
 */
@Serializable
enum class OutputPort {
    FLOW_OUT,       // Normaler Ausgang
    TRUE_OUT,       // Condition: wenn true
    FALSE_OUT       // Condition: wenn false
}

/**
 * Input-Ports eines Nodes
 */
@Serializable
enum class InputPort {
    FLOW_IN         // Eingang für Flow
}

/**
 * Alle verfügbaren Node-Typen
 */
@Serializable
enum class NodeType(
    val displayName: String,
    val category: NodeCategory,
    val description: String,
    val color: Long  // ARGB color
) {
    // === TRIGGER NODES (Startpunkte) ===
    TRIGGER_ON_START(
        "Bei Start",
        NodeCategory.TRIGGER,
        "Wird beim Script-Start ausgeführt",
        0xFF4CAF50  // Green
    ),
    TRIGGER_ON_RECEIVE(
        "Bei Empfang",
        NodeCategory.TRIGGER,
        "Wird bei Empfang eines bestimmten CAN-Frames ausgeführt",
        0xFF4CAF50
    ),
    TRIGGER_ON_INTERVAL(
        "Intervall",
        NodeCategory.TRIGGER,
        "Wird periodisch in einem Intervall ausgeführt",
        0xFF4CAF50
    ),
    TRIGGER_ON_TIME(
        "Zu Uhrzeit",
        NodeCategory.TRIGGER,
        "Wird zu einer bestimmten Uhrzeit ausgeführt",
        0xFF4CAF50
    ),
    TRIGGER_ON_COUNT(
        "Nach Anzahl",
        NodeCategory.TRIGGER,
        "Wird nach X empfangenen Frames ausgeführt",
        0xFF4CAF50
    ),

    // === CONDITION NODES (Verzweigungen) ===
    CONDITION_IF_DATA(
        "Wenn Daten",
        NodeCategory.CONDITION,
        "Prüft Frame-Daten (Byte-Vergleich)",
        0xFFFF9800  // Orange
    ),
    CONDITION_IF_TIME(
        "Wenn Uhrzeit",
        NodeCategory.CONDITION,
        "Prüft ob aktuelle Zeit in Bereich liegt",
        0xFFFF9800
    ),
    CONDITION_IF_COUNTER(
        "Wenn Zähler",
        NodeCategory.CONDITION,
        "Prüft den Wert einer Zählervariable",
        0xFFFF9800
    ),
    CONDITION_IF_RECEIVED(
        "Wenn empfangen",
        NodeCategory.CONDITION,
        "Prüft ob Frame in letzten X ms empfangen wurde",
        0xFFFF9800
    ),

    // === ACTION NODES ===
    ACTION_SEND(
        "Sende Frame",
        NodeCategory.ACTION,
        "Sendet einen CAN-Frame",
        0xFF2196F3  // Blue
    ),
    ACTION_DELAY(
        "Warten",
        NodeCategory.ACTION,
        "Wartet eine bestimmte Zeit",
        0xFF2196F3
    ),
    ACTION_SET_VARIABLE(
        "Variable setzen",
        NodeCategory.ACTION,
        "Setzt den Wert einer Variable",
        0xFF2196F3
    ),
    ACTION_INCREMENT(
        "Zähler erhöhen",
        NodeCategory.ACTION,
        "Erhöht einen Zähler um einen Wert",
        0xFF2196F3
    ),
    ACTION_PRINT(
        "Debug-Ausgabe",
        NodeCategory.ACTION,
        "Gibt Text zur Diagnose aus",
        0xFF2196F3
    ),

    // === FLOW CONTROL NODES ===
    FLOW_WAIT_FOR(
        "Warte auf Frame",
        NodeCategory.FLOW,
        "Wartet auf einen bestimmten CAN-Frame",
        0xFF9C27B0  // Purple
    ),
    FLOW_REPEAT(
        "Wiederholen",
        NodeCategory.FLOW,
        "Wiederholt den folgenden Block X mal",
        0xFF9C27B0
    ),
    FLOW_LOOP(
        "Endlosschleife",
        NodeCategory.FLOW,
        "Wiederholt den folgenden Block endlos",
        0xFF9C27B0
    ),
    FLOW_STOP(
        "Script beenden",
        NodeCategory.FLOW,
        "Beendet das Script",
        0xFFF44336  // Red
    );

    val hasMultipleOutputs: Boolean
        get() = category == NodeCategory.CONDITION

    val isTrigger: Boolean
        get() = category == NodeCategory.TRIGGER
}

/**
 * Kategorien für Node-Typen
 */
@Serializable
enum class NodeCategory(val displayName: String) {
    TRIGGER("Trigger"),
    CONDITION("Bedingung"),
    ACTION("Aktion"),
    FLOW("Ablaufsteuerung")
}

/**
 * Konfiguration eines Nodes - enthält alle möglichen Parameter
 */
@Serializable
data class NodeConfig(
    // CAN Frame Parameter
    val canId: String = "",           // CAN-ID (hex, z.B. "7DF")
    val canData: String = "",         // Daten (hex, z.B. "02,01,00")
    val isExtended: Boolean = false,  // Extended Frame

    // Zeit Parameter
    val delayMs: Long = 1000,         // Verzögerung in ms
    val intervalMs: Long = 1000,      // Intervall in ms
    val timeHour: Int = 0,            // Uhrzeit: Stunde
    val timeMinute: Int = 0,          // Uhrzeit: Minute
    val timeRangeStart: String = "",  // Zeitbereich Start (HH:MM)
    val timeRangeEnd: String = "",    // Zeitbereich Ende (HH:MM)
    val timeRepeat: Boolean = false,  // Täglich wiederholen

    // Condition Parameter
    val byteIndex: Int = 0,           // Welches Byte prüfen
    val compareOperator: CompareOperator = CompareOperator.EQUALS,
    val compareValue: String = "",    // Vergleichswert (hex)
    val timeoutMs: Long = 5000,       // Timeout für Warten

    // Variable Parameter
    val variableName: String = "",    // Name der Variable
    val variableValue: String = "",   // Wert
    val incrementValue: Int = 1,      // Inkrement-Wert

    // Counter Parameter
    val count: Int = 1,               // Anzahl Wiederholungen / Frame-Count

    // Print Parameter
    val printText: String = "",       // Text für Debug-Ausgabe

    // Data Pattern für Filter
    val dataPattern: String = ""      // Pattern für Daten-Filter (z.B. "02,*,00")
)

/**
 * Vergleichsoperatoren für Conditions
 */
@Serializable
enum class CompareOperator(val symbol: String, val displayName: String) {
    EQUALS("==", "gleich"),
    NOT_EQUALS("!=", "ungleich"),
    LESS_THAN("<", "kleiner als"),
    LESS_EQUALS("<=", "kleiner gleich"),
    GREATER_THAN(">", "größer als"),
    GREATER_EQUALS(">=", "größer gleich"),
    BIT_SET("&", "Bit gesetzt"),
    BIT_CLEAR("!&", "Bit nicht gesetzt")
}

/**
 * File-Info für gespeicherte Visual Scripts
 */
@Serializable
data class VisualScriptFileInfo(
    val id: String,
    val name: String,
    val fileName: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val description: String = "",
    val nodeCount: Int = 0
) {
    val displaySize: String
        get() = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / (1024 * 1024)} MB"
        }
}

/**
 * Hilfsfunktionen für Nodes
 */
object NodeHelpers {

    /**
     * Erstellt einen neuen Node mit Standardkonfiguration
     */
    fun createNode(type: NodeType, position: Offset): VisualNode {
        return VisualNode(
            type = type,
            position = SerializableOffset.fromOffset(position),
            config = getDefaultConfig(type)
        )
    }

    /**
     * Gibt die Standardkonfiguration für einen Node-Typ zurück
     */
    fun getDefaultConfig(type: NodeType): NodeConfig {
        return when (type) {
            NodeType.TRIGGER_ON_START -> NodeConfig()
            NodeType.TRIGGER_ON_RECEIVE -> NodeConfig(canId = "7DF")
            NodeType.TRIGGER_ON_INTERVAL -> NodeConfig(intervalMs = 1000)
            NodeType.TRIGGER_ON_TIME -> NodeConfig(timeHour = 12, timeMinute = 0)
            NodeType.TRIGGER_ON_COUNT -> NodeConfig(canId = "7DF", count = 5)

            NodeType.CONDITION_IF_DATA -> NodeConfig(byteIndex = 0, compareValue = "00")
            NodeType.CONDITION_IF_TIME -> NodeConfig(timeRangeStart = "08:00", timeRangeEnd = "18:00")
            NodeType.CONDITION_IF_COUNTER -> NodeConfig(variableName = "counter", compareValue = "10")
            NodeType.CONDITION_IF_RECEIVED -> NodeConfig(canId = "7DF", timeoutMs = 1000)

            NodeType.ACTION_SEND -> NodeConfig(canId = "7DF", canData = "02,01,00")
            NodeType.ACTION_DELAY -> NodeConfig(delayMs = 1000)
            NodeType.ACTION_SET_VARIABLE -> NodeConfig(variableName = "var1", variableValue = "0")
            NodeType.ACTION_INCREMENT -> NodeConfig(variableName = "counter", incrementValue = 1)
            NodeType.ACTION_PRINT -> NodeConfig(printText = "Debug")

            NodeType.FLOW_WAIT_FOR -> NodeConfig(canId = "7E8", timeoutMs = 5000)
            NodeType.FLOW_REPEAT -> NodeConfig(count = 3)
            NodeType.FLOW_LOOP -> NodeConfig()
            NodeType.FLOW_STOP -> NodeConfig()
        }
    }

    /**
     * Prüft ob eine Verbindung gültig ist
     */
    fun isValidConnection(fromNode: VisualNode, toNode: VisualNode): Boolean {
        // Keine Verbindung zu sich selbst
        if (fromNode.id == toNode.id) return false

        // Keine Verbindung zu Triggern (haben keinen Eingang)
        if (toNode.type.isTrigger) return false

        return true
    }

    /**
     * Parst eine CAN-ID aus String
     */
    fun parseCanId(idString: String): Long? {
        val cleaned = idString.trim().removePrefix("0x").removePrefix("0X")
        return try {
            cleaned.toLong(16)
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Parst CAN-Daten aus String (z.B. "02,01,00" oder "02 01 00")
     */
    fun parseCanData(dataString: String): ByteArray? {
        if (dataString.isBlank()) return byteArrayOf()

        val cleaned = dataString.trim()
            .replace(" ", ",")
            .replace("0x", "")
            .replace("0X", "")

        return try {
            cleaned.split(",")
                .filter { it.isNotBlank() }
                .map { it.trim().toInt(16).toByte() }
                .toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Formatiert eine CAN-ID für die Anzeige
     */
    fun formatCanId(id: Long, extended: Boolean = false): String {
        return if (extended) {
            String.format("%08X", id)
        } else {
            String.format("%03X", id)
        }
    }

    /**
     * Formatiert CAN-Daten für die Anzeige
     */
    fun formatCanData(data: ByteArray): String {
        return data.joinToString(",") { String.format("%02X", it) }
    }
}
