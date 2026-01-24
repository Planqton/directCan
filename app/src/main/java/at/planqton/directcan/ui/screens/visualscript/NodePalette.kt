package at.planqton.directcan.ui.screens.visualscript

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.planqton.directcan.data.visualscript.NodeCategory
import at.planqton.directcan.data.visualscript.NodeType

/**
 * Palette with all available node types, grouped by category
 */
@Composable
fun NodePalette(
    onNodeSelected: (NodeType) -> Unit,
    modifier: Modifier = Modifier
) {
    val groupedNodes = remember {
        NodeType.entries.groupBy { it.category }
    }

    var expandedCategory by remember { mutableStateOf<NodeCategory?>(NodeCategory.TRIGGER) }

    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text(
                "Node-Palette",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(8.dp)
            )
        }

        NodeCategory.entries.forEach { category ->
            val nodesInCategory = groupedNodes[category] ?: emptyList()

            item {
                CategoryHeader(
                    category = category,
                    nodeCount = nodesInCategory.size,
                    isExpanded = expandedCategory == category,
                    onToggle = {
                        expandedCategory = if (expandedCategory == category) null else category
                    }
                )
            }

            if (expandedCategory == category) {
                items(nodesInCategory) { nodeType ->
                    NodePaletteItem(
                        nodeType = nodeType,
                        onClick = { onNodeSelected(nodeType) }
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
        }

        item {
            HelpCard()
        }
    }
}

@Composable
private fun CategoryHeader(
    category: NodeCategory,
    nodeCount: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val categoryColor = when (category) {
        NodeCategory.TRIGGER -> Color(0xFF4CAF50)
        NodeCategory.CONDITION -> Color(0xFFFF9800)
        NodeCategory.ACTION -> Color(0xFF2196F3)
        NodeCategory.FLOW -> Color(0xFF9C27B0)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle() },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(categoryColor, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = category.displayName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$nodeCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun NodePaletteItem(
    nodeType: NodeType,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Color(nodeType.color), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getNodeIcon(nodeType),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = nodeType.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = nodeType.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 2
                )
            }
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Hinzufügen",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun HelpCard() {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Hilfe",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                "1. Tippe auf einen Node um ihn hinzuzufügen",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "2. Ziehe Nodes auf dem Canvas um sie zu positionieren",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "3. Verbinde Nodes durch Tippen auf die Ports",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "4. Doppeltippe auf einen Node um ihn zu konfigurieren",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Compact version of the palette for smaller screens
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactNodePalette(
    onNodeSelected: (NodeType) -> Unit,
    onDismiss: () -> Unit
) {
    val groupedNodes = remember {
        NodeType.entries.groupBy { it.category }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "Node hinzufügen",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            NodeCategory.entries.forEach { category ->
                val nodesInCategory = groupedNodes[category] ?: emptyList()

                Text(
                    text = category.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    nodesInCategory.forEach { nodeType ->
                        CompactNodeChip(
                            nodeType = nodeType,
                            onClick = {
                                onNodeSelected(nodeType)
                                onDismiss()
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun CompactNodeChip(
    nodeType: NodeType,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        color = Color(nodeType.color).copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = getNodeIcon(nodeType),
                contentDescription = null,
                tint = Color(nodeType.color),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = nodeType.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = Color(nodeType.color)
            )
        }
    }
}

