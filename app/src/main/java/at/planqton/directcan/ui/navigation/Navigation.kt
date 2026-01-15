package at.planqton.directcan.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
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

    companion object {
        // Snapshot button after Settings - no navigation, just action
        val bottomNavItems = listOf(Home, Monitor, Sniffer, Signals, SignalGraph, DbcManager, Settings, Snapshot)
    }
}
