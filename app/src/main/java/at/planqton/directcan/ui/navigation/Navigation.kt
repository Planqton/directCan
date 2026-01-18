package at.planqton.directcan.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.CarRepair
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Monitor : Screen("monitor", "Monitor", Icons.Default.List)
    object Sniffer : Screen("sniffer", "Sniffer", Icons.Default.RemoveRedEye)
    object Signals : Screen("signals", "Signals", Icons.Default.ShowChart)
    object SignalGraph : Screen("signalgraph", "Graph", Icons.Default.Timeline)
    object DbcManager : Screen("dbc", "DBC", Icons.Default.Storage)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object LogManager : Screen("logmanager", "Logs", Icons.Default.Storage)
    object DbcEditor : Screen("dbc_editor/{dbcPath}", "DBC Editor", Icons.Default.Storage) {
        fun createRoute(dbcPath: String) = "dbc_editor/${java.net.URLEncoder.encode(dbcPath, "UTF-8")}"
    }
    // Snapshot is a special button, not a navigation destination
    object Snapshot : Screen("snapshot", "Snap", Icons.Default.CameraAlt)
    // AI Chat screens
    object AiSettings : Screen("ai_settings", "AI", Icons.Default.Psychology)
    object AiChat : Screen("ai_chat/{chatId}", "AI Chat", Icons.Default.Chat) {
        fun createRoute(chatId: String) = "ai_chat/${java.net.URLEncoder.encode(chatId, "UTF-8")}"
    }
    // Active AI Chat - for bottom nav when chat is open
    object ActiveAiChat : Screen("active_ai_chat", "AI", Icons.Default.Chat)
    // TX Script screens
    object TxScriptManager : Screen("txscript_manager", "TX Scripts", Icons.Default.Code)
    object TxScriptEditor : Screen("txscript_editor/{scriptPath}", "Script Editor", Icons.Default.Edit) {
        fun createRoute(scriptPath: String) = "txscript_editor/${java.net.URLEncoder.encode(scriptPath, "UTF-8")}"
    }
    // Device Manager screen (like SavvyCAN Connection Settings)
    object DeviceManager : Screen("device_manager", "Verbindungen", Icons.Default.Link)
    // DTC/OBD2 Diagnosis screen
    object Dtc : Screen("dtc", "DTC Diagnose", Icons.Default.CarRepair)

    companion object {
        // Snapshot button after Settings - no navigation, just action
        // Sniffer, Signals, SignalGraph are hidden - accessible from Monitor
        val bottomNavItems = listOf(Home, Monitor, DbcManager, Settings, Snapshot)
        // Items including AI Chat
        val bottomNavItemsWithAiChat = listOf(Home, Monitor, DbcManager, Settings, ActiveAiChat, Snapshot)
    }
}
