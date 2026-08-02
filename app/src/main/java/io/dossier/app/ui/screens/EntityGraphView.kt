package io.dossier.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.domain.evidence.ConfidenceEngine
import io.dossier.app.domain.evidence.RelationshipConfidence
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.ui.theme.NeuralTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Interactive graph with a first-class text alternative. The visual layout is
 * normalised into positive coordinates, scrollable in both directions, and
 * never assumes that every node fits inside the phone viewport.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntityGraphView(
    graph: EntityGraph,
    modifier: Modifier = Modifier,
    confidenceByEdge: Map<String, RelationshipConfidence> = emptyMap()
) {
    if (graph.entities.isEmpty()) {
        Text(
            text = "No entity relationships were compiled for this scan.",
            color = NeuralTheme.TextSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = modifier.padding(4.dp)
        )
        return
    }

    var viewMode by remember { mutableStateOf(GraphViewMode.Graph) }
    var selectedId by remember(graph) { mutableStateOf<String?>(null) }

    val colors = mapOf(
        EntityType.Person to NeuralTheme.Cobalt,
        EntityType.Username to NeuralTheme.Emerald,
        EntityType.Email to NeuralTheme.Amber,
        EntityType.Phone to NeuralTheme.Crimson,
        EntityType.Profile to NeuralTheme.Cobalt,
        EntityType.Organization to NeuralTheme.Emerald,
        EntityType.Location to NeuralTheme.Amber,
        EntityType.Image to NeuralTheme.Crimson,
        EntityType.Breach to NeuralTheme.Crimson,
        EntityType.Website to NeuralTheme.TextSecondary
    )
    val layout = remember(graph) { layoutGraph(graph) }
    val adjacency = remember(graph) { buildAdjacency(graph) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GraphViewTab(
                label = "Visual graph",
                selected = viewMode == GraphViewMode.Graph,
                onClick = { viewMode = GraphViewMode.Graph }
            )
            GraphViewTab(
                label = "Accessible list",
                selected = viewMode == GraphViewMode.List,
                onClick = { viewMode = GraphViewMode.List }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        when (viewMode) {
            GraphViewMode.Graph -> GraphCanvas(
                graph = graph,
                layout = layout,
                adjacency = adjacency,
                selectedId = selectedId,
                onSelect = { selectedId = if (selectedId == it) null else it },
                typeColors = colors
            )
            GraphViewMode.List -> AdjacencyList(
                graph = graph,
                confidenceByEdge = confidenceByEdge,
                selectedId = selectedId,
                onSelect = { selectedId = if (selectedId == it) null else it }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        EntityGraphLegend(typeColors = colors)

        selectedId?.let { id ->
            val entity = graph.entities.firstOrNull { it.id == id } ?: return@let
            val connected = graph.edges.filter { it.fromId == id || it.toId == id }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 0.7.dp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "${entity.type.name}: ${entity.label}",
                color = NeuralTheme.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp
            )
            Text(
                text = "Attribution confidence ${(entity.confidence * 100).toInt()}%",
                color = NeuralTheme.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (entity.sourceUrls.isNotEmpty()) {
                Text(
                    text = "Sources: ${entity.sourceUrls.take(4).joinToString(", ")}",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            connected.forEach { edge ->
                val otherId = if (edge.fromId == id) edge.toId else edge.fromId
                val other = graph.entities.firstOrNull { it.id == otherId }
                val scored = confidenceByEdge[
                    ConfidenceEngine.edgeKey(edge.fromId, edge.toId, edge.relation)
                ]
                Text(
                    text = buildString {
                        append("• ${edge.relation} → ${other?.label ?: otherId}")
                        scored?.let { append(" · ${(it.score * 100).toInt()}%") }
                    },
                    color = NeuralTheme.TextPrimary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 5.dp)
                )
                edge.evidence?.takeIf(String::isNotBlank)?.let { evidence ->
                    Text(
                        text = evidence.take(160),
                        color = NeuralTheme.TextSecondary,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(start = 12.dp, top = 1.dp)
                    )
                }
            }
        }
    }
}

private enum class GraphViewMode { Graph, List }

@Composable
private fun GraphViewTab(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) NeuralTheme.Cobalt.copy(alpha = 0.12f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) NeuralTheme.Cobalt else NeuralTheme.BorderColor,
                RoundedCornerShape(9.dp)
            )
    ) {
        Text(
            text = label,
            color = if (selected) NeuralTheme.Cobalt else NeuralTheme.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun GraphCanvas(
    graph: EntityGraph,
    layout: GraphLayout,
    adjacency: Map<String, Set<String>>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    typeColors: Map<EntityType, Color>
) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val density = LocalDensity.current.density
    val semanticSummary = remember(graph) {
        "Relationship graph with ${graph.entities.size} entities and ${graph.edges.size} connections. Use Accessible list for full text navigation."
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .background(NeuralTheme.SurfaceDark, RoundedCornerShape(10.dp))
            .border(1.dp, NeuralTheme.BorderColor, RoundedCornerShape(10.dp))
            .horizontalScroll(horizontal)
            .verticalScroll(vertical)
            .semantics { contentDescription = semanticSummary }
    ) {
        Canvas(
            modifier = Modifier
                .width(layout.width)
                .height(layout.height)
                .pointerInput(graph, density) {
                    detectTapGestures { tapped ->
                        val hit = layout.positions.minByOrNull { (_, unitPosition) ->
                            val point = unitPosition.toPixels(density)
                            squaredDistance(point, tapped)
                        }
                        hit?.let { (id, unitPosition) ->
                            val point = unitPosition.toPixels(density)
                            val hitRadius = (NODE_RADIUS_DP + 16f) * density
                            if (squaredDistance(point, tapped) <= hitRadius * hitRadius) onSelect(id)
                        }
                    }
                }
        ) {
            val activeIds = selectedId?.let { adjacency[it].orEmpty() + it }

            graph.edges.forEach { edge ->
                val from = layout.positions[edge.fromId]?.toPixels(density) ?: return@forEach
                val to = layout.positions[edge.toId]?.toPixels(density) ?: return@forEach
                val active = activeIds == null || edge.fromId in activeIds || edge.toId in activeIds
                drawLine(
                    color = (if (active) NeuralTheme.Cobalt else NeuralTheme.BorderColor)
                        .copy(alpha = if (active) 0.75f else 0.35f),
                    start = from,
                    end = to,
                    strokeWidth = if (edge.fromId == selectedId || edge.toId == selectedId) {
                        2.4f * density
                    } else {
                        1.2f * density
                    }
                )
            }

            graph.entities.forEach { entity ->
                val position = layout.positions[entity.id]?.toPixels(density) ?: return@forEach
                val color = typeColors[entity.type] ?: NeuralTheme.TextSecondary
                val dimmed = activeIds != null && entity.id !in activeIds
                val radius = NODE_RADIUS_DP *
                    (0.78f + entity.confidence.coerceIn(0f, 1f) * 0.45f) * density
                drawCircle(
                    color = color.copy(alpha = if (dimmed) 0.25f else 0.95f),
                    radius = radius,
                    center = position
                )
                if (entity.id == selectedId) {
                    drawCircle(
                        color = NeuralTheme.TextPrimary,
                        radius = radius + 4f * density,
                        center = position,
                        style = Stroke(width = 2f * density)
                    )
                }

                drawContext.canvas.nativeCanvas.drawText(
                    "${entity.type.name.take(3).uppercase()} · ${entity.label.take(16)}",
                    position.x,
                    position.y - radius - 7f * density,
                    android.graphics.Paint().apply {
                        textSize = 11f * density
                        this.color = android.graphics.Color.argb(
                            if (dimmed) 110 else 255,
                            (NeuralTheme.TextPrimary.red * 255).toInt(),
                            (NeuralTheme.TextPrimary.green * 255).toInt(),
                            (NeuralTheme.TextPrimary.blue * 255).toInt()
                        )
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    }
                )
            }
        }
    }
}

@Composable
private fun AdjacencyList(
    graph: EntityGraph,
    confidenceByEdge: Map<String, RelationshipConfidence>,
    selectedId: String?,
    onSelect: (String) -> Unit
) {
    val byId = remember(graph) { graph.entities.associateBy(DossierEntity::id) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NeuralTheme.SurfaceDark, RoundedCornerShape(10.dp))
            .border(1.dp, NeuralTheme.BorderColor, RoundedCornerShape(10.dp))
            .padding(6.dp)
    ) {
        graph.entities.forEach { entity ->
            val selected = entity.id == selectedId
            val edges = graph.edges.filter { it.fromId == entity.id || it.toId == entity.id }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) NeuralTheme.Cobalt.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onSelect(entity.id) }
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "${entity.type.name}: ${entity.label}",
                    color = NeuralTheme.TextPrimary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 12.5.sp
                )
                if (edges.isEmpty()) {
                    Text("No recorded connections", color = NeuralTheme.TextSecondary, fontSize = 11.sp)
                } else {
                    edges.forEach { edge ->
                        val otherId = if (edge.fromId == entity.id) edge.toId else edge.fromId
                        val score = confidenceByEdge[
                            ConfidenceEngine.edgeKey(edge.fromId, edge.toId, edge.relation)
                        ]
                        Text(
                            text = buildString {
                                append("↳ ${edge.relation} → ${byId[otherId]?.label ?: otherId}")
                                score?.let { append(" (${(it.score * 100).toInt()}%)") }
                            },
                            color = NeuralTheme.TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                        )
                    }
                }
            }
            HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 0.5.dp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntityGraphLegend(typeColors: Map<EntityType, Color>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        EntityType.entries.forEach { type ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(typeColors[type] ?: NeuralTheme.TextSecondary)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(type.name, color = NeuralTheme.TextSecondary, fontSize = 10.5.sp)
            }
        }
    }
}

private data class GraphLayout(
    val positions: Map<String, Offset>,
    val width: Dp,
    val height: Dp
)

/** Stable concentric layout, shifted so no node or label has negative coordinates. */
private fun layoutGraph(graph: EntityGraph): GraphLayout {
    val subject = graph.entities.firstOrNull { it.type == EntityType.Person }
        ?: graph.entities.first()
    val raw = mutableMapOf<String, Offset>()
    raw[subject.id] = Offset.Zero

    val others = graph.entities.filterNot { it.id == subject.id }
    val perRing = 8
    others.forEachIndexed { index, entity ->
        val ring = index / perRing + 1
        val slot = index % perRing
        val itemsInRing = minOf(perRing, others.size - (ring - 1) * perRing)
        val angle = Math.PI * 2.0 * slot / itemsInRing + ring * 0.35
        val radius = 100f * ring
        raw[entity.id] = Offset(
            (radius * cos(angle)).toFloat(),
            (radius * sin(angle)).toFloat()
        )
    }

    val minX = raw.values.minOf(Offset::x)
    val minY = raw.values.minOf(Offset::y)
    val maxX = raw.values.maxOf(Offset::x)
    val maxY = raw.values.maxOf(Offset::y)
    val padding = 85f
    val shifted = raw.mapValues { (_, point) ->
        Offset(point.x - minX + padding, point.y - minY + padding)
    }
    return GraphLayout(
        positions = shifted,
        width = maxOf(360f, maxX - minX + padding * 2f).dp,
        height = maxOf(320f, maxY - minY + padding * 2f).dp
    )
}

private fun buildAdjacency(graph: EntityGraph): Map<String, Set<String>> {
    val adjacency = mutableMapOf<String, MutableSet<String>>()
    graph.edges.forEach { edge ->
        adjacency.getOrPut(edge.fromId) { mutableSetOf() }.add(edge.toId)
        adjacency.getOrPut(edge.toId) { mutableSetOf() }.add(edge.fromId)
    }
    return adjacency
}

private fun Offset.toPixels(density: Float): Offset = Offset(x * density, y * density)
private fun squaredDistance(first: Offset, second: Offset): Float {
    val dx = first.x - second.x
    val dy = first.y - second.y
    return dx * dx + dy * dy
}

private const val NODE_RADIUS_DP = 17f
