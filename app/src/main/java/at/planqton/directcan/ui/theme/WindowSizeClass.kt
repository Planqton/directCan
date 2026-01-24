package at.planqton.directcan.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window width size classes based on Material Design guidelines.
 * Used for responsive layouts.
 */
enum class WindowWidthSizeClass {
    /** Compact: < 600dp - Small phones */
    Compact,
    /** Medium: 600-840dp - Normal phones, small tablets */
    Medium,
    /** Expanded: > 840dp - Tablets, desktop */
    Expanded
}

/**
 * Window height size classes for vertical space considerations.
 */
enum class WindowHeightSizeClass {
    /** Compact: < 480dp - Landscape phones */
    Compact,
    /** Medium: 480-900dp - Portrait phones */
    Medium,
    /** Expanded: > 900dp - Tablets */
    Expanded
}

/**
 * Combined window size class holder.
 */
data class WindowSizeClass(
    val widthSizeClass: WindowWidthSizeClass,
    val heightSizeClass: WindowHeightSizeClass
) {
    val isCompact: Boolean
        get() = widthSizeClass == WindowWidthSizeClass.Compact

    val isCompactHeight: Boolean
        get() = heightSizeClass == WindowHeightSizeClass.Compact

    val isExpanded: Boolean
        get() = widthSizeClass == WindowWidthSizeClass.Expanded
}

/**
 * CompositionLocal for accessing WindowSizeClass throughout the app.
 */
val LocalWindowSizeClass = compositionLocalOf {
    WindowSizeClass(
        widthSizeClass = WindowWidthSizeClass.Medium,
        heightSizeClass = WindowHeightSizeClass.Medium
    )
}

/**
 * Calculate WindowSizeClass from screen dimensions.
 */
@Composable
fun calculateWindowSizeClass(): WindowSizeClass {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val screenHeightDp = configuration.screenHeightDp.dp

    return WindowSizeClass(
        widthSizeClass = getWidthSizeClass(screenWidthDp),
        heightSizeClass = getHeightSizeClass(screenHeightDp)
    )
}

private fun getWidthSizeClass(width: Dp): WindowWidthSizeClass = when {
    width < 600.dp -> WindowWidthSizeClass.Compact
    width < 840.dp -> WindowWidthSizeClass.Medium
    else -> WindowWidthSizeClass.Expanded
}

private fun getHeightSizeClass(height: Dp): WindowHeightSizeClass = when {
    height < 480.dp -> WindowHeightSizeClass.Compact
    height < 900.dp -> WindowHeightSizeClass.Medium
    else -> WindowHeightSizeClass.Expanded
}

/**
 * Helper extensions for common responsive patterns.
 */

/**
 * Returns true if the current layout should show a sidebar (expanded only).
 */
@Composable
fun shouldShowSidebar(): Boolean {
    val windowSizeClass = LocalWindowSizeClass.current
    return windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
}

/**
 * Returns true if the current layout should use bottom sheet instead of sidebar.
 */
@Composable
fun shouldUseBottomSheet(): Boolean {
    val windowSizeClass = LocalWindowSizeClass.current
    return windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
}

/**
 * Returns true if floating windows should be shown as fullscreen dialogs.
 */
@Composable
fun shouldFloatingWindowsBeFullscreen(): Boolean {
    val windowSizeClass = LocalWindowSizeClass.current
    return windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact &&
            windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
}
