package at.planqton.directcan.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Centralized dimensions for consistent sizing across the app.
 * Provides adaptive values based on WindowSizeClass.
 */
object Dimensions {

    // ============== Fixed Sizes ==============

    /** Minimum touch target size (accessibility) */
    val minTouchTarget = 48.dp

    /** Icon sizes */
    val iconSmall = 16.dp
    val iconMedium = 24.dp
    val iconLarge = 32.dp

    /** Card elevation */
    val cardElevation = 2.dp

    // ============== Adaptive Padding ==============

    /** Screen edge padding - adapts to screen size */
    @Composable
    fun screenPadding(): Dp {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Compact -> 8.dp
            WindowWidthSizeClass.Medium -> 12.dp
            WindowWidthSizeClass.Expanded -> 16.dp
        }
    }

    /** Content padding inside cards/containers */
    @Composable
    fun contentPadding(): Dp {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Compact -> 8.dp
            WindowWidthSizeClass.Medium -> 12.dp
            WindowWidthSizeClass.Expanded -> 16.dp
        }
    }

    /** Spacing between items in lists */
    @Composable
    fun itemSpacing(): Dp {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Compact -> 6.dp
            WindowWidthSizeClass.Medium -> 8.dp
            WindowWidthSizeClass.Expanded -> 12.dp
        }
    }

    // ============== Adaptive Component Sizes ==============

    /** Sidebar width - only used on non-compact screens */
    @Composable
    fun sidebarWidth(): Dp {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Compact -> 0.dp // No sidebar on compact
            WindowWidthSizeClass.Medium -> 180.dp
            WindowWidthSizeClass.Expanded -> 220.dp
        }
    }

    /** Bottom sheet peek height for compact screens */
    val bottomSheetPeekHeight = 56.dp

    /** Floating window max width */
    @Composable
    fun floatingWindowMaxWidth(): Dp {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Compact -> 320.dp
            WindowWidthSizeClass.Medium -> 380.dp
            WindowWidthSizeClass.Expanded -> 450.dp
        }
    }

    /** Floating window max height */
    @Composable
    fun floatingWindowMaxHeight(): Dp {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.heightSizeClass) {
            WindowHeightSizeClass.Compact -> 300.dp
            WindowHeightSizeClass.Medium -> 450.dp
            WindowHeightSizeClass.Expanded -> 600.dp
        }
    }

    // ============== Table/Grid Column Widths ==============

    /** CAN ID column width */
    @Composable
    fun columnWidthId(): Dp {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Compact -> 55.dp
            else -> 70.dp
        }
    }

    /** CAN Data column width */
    @Composable
    fun columnWidthData(): Dp {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Compact -> 150.dp
            WindowWidthSizeClass.Medium -> 180.dp
            WindowWidthSizeClass.Expanded -> 200.dp
        }
    }

    /** Decoded signal column width */
    @Composable
    fun columnWidthDecoded(): Dp {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Compact -> 120.dp
            WindowWidthSizeClass.Medium -> 200.dp
            WindowWidthSizeClass.Expanded -> 300.dp
        }
    }

    /** Time column width */
    @Composable
    fun columnWidthTime(): Dp {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Compact -> 50.dp
            else -> 70.dp
        }
    }

    /** Small flag columns (Ext, RTR, Dir, etc.) */
    @Composable
    fun columnWidthFlag(): Dp {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Compact -> 28.dp
            else -> 35.dp
        }
    }

    // ============== Navigation ==============

    /** Number of items to show in bottom navigation */
    @Composable
    fun bottomNavItemCount(): Int {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Compact -> 4 // Home, Monitor, DBC, More
            else -> 5 // All items
        }
    }

    // ============== Dialog Sizes ==============

    /** Dialog max width */
    @Composable
    fun dialogMaxWidth(): Dp {
        val windowSizeClass = LocalWindowSizeClass.current
        return when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Compact -> 300.dp
            WindowWidthSizeClass.Medium -> 400.dp
            WindowWidthSizeClass.Expanded -> 500.dp
        }
    }
}

// ============== Convenience Extensions ==============

/**
 * Get adaptive padding for screen content.
 */
@Composable
fun adaptiveScreenPadding() = Dimensions.screenPadding()

/**
 * Get adaptive spacing for list items.
 */
@Composable
fun adaptiveItemSpacing() = Dimensions.itemSpacing()
