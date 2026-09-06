package io.dossier.app.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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

/** A scrollable visual relationship map with a complete text alternative. */
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

    val accent = NeuralTheme.Cobalt
    val success = NeuralTheme.Emerald
    val warning = NeuralTheme.Amber
    val danger = NeuralTheme.Crimson
    val border = NeuralTheme.BorderColor
    val surface = NeuralTheme.SurfaceDark
    val textPrimary = NeuralTheme.TextPrimary
    val textSecondary = NeuralTheme.TextSecondary
    val typeColors = mapOf(
        EntityType.Person to accent,
        EntityType.Username to success,
        EntityType.Email to warning,
        EntityType.Phone to danger,
        EntityType.Profile to accent,
        EntityType.Organization to success,
        EntityType.Location to warning,
        EntityType.Image to danger,
        EntityType.Breach to danger,
        EntityType.Website to textSecondary
    )
    val layout = remember(graph) { layoutGraph(graph) }
    val adjacency = remember(graph) { buildAdjacency(graph) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GraphViewTab(
                label = "Visual graph",
                selected = viewMode == GraphViewMode.Graph,
                onClick = { viewMode = GraphViewMode.Graph },
                accent = accent,
                border = border,
                textSecondary = textSecondary
            )
            GraphViewTab(
                label = "Accessible list",
                selected = viewMode == GraphViewMode.List,
                onClick = { viewMode = GraphViewMode.List },
                accent = accent,
                border = border,
                textSecondary = textSecondary
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
                typeColors = typeColors,
                accent = accent,
                border = border,
                surface = surface,
                textPrimary = textPrimary,
                textSecondary = textSecondary
            )
            GraphViewMode.List -> AdjacencyList(
                graph = graph,
                confidenceByEdge = confidenceByEdge,
                selectedId = selectedId,
                onSelect = { selectedId = if (selectedId == it) null else it },
                accent = accent,
                border = border,
                surface = surface,
                textPrimary = textPrimary,
                textSecondary = textSecondary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        EntityGraphLegend(typeColors, textSecondary)

        selectedId?.let { id ->
            val entity = graph.entities.firstOrNull { it.id == id } ?: return@let
            val connected = graph.edges.filter { it.fromId == id || it.toId == id }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = border, thickness = 0.7.dp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "${entity.type.name}: ${entity.label}",
                color = textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp
            )
            Text(
                text = "Attribution confidence ${(entity.confidence * 100).toInt()}%",
                color = textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (entity.sourceUrls.isNotEmpty()) {
                Text(
                    text = "Sources: ${entity.sourceUrls.take(4).joinToString(", ")}",
                    color = textSecondary,
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
                    color = textPrimary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 5.dp)
                )
                edge.evidence?.takeIf(String::isNotBlank)?.let { evidence ->
                    Text(
                        text = evidence.take(160),
                        color = textSecondary,
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
private fun GraphViewTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color,
    border: Color,
    textSecondary: Color
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (selected) accent else border, RoundedCornerShape(9.dp))
            .semantics {
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
            }
    ) {
        Text(
            text = label,
            color = if (selected) accent else textSecondary,
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
    typeColors: Map<EntityType, Color>,
    accent: Color,
    border: Color,
    surface: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val density = LocalDensity.current.density
    val semanticSummary = remember(graph) {
        "Relationship graph with ${graph.entities.size} entities and ${graph.edges.size} connections. Use Accessible list for complete text navigation."
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .background(surface, RoundedCornerShape(10.dp))
            .border(1.dp, border, RoundedCornerShape(10.dp))
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
                            squaredDistance(unitPosition.toPixels(density), tapped)
                        }
                        hit?.let { (id, unitPosition) ->
                            val radius = (NODE_RADIUS_DP + 16f) * density
                            if (squaredDistance(unitPosition.toPixels(density), tapped) <= radius * radius) {
                                onSelect(id)
                            }
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
                    color = (if (active) accent else border)
                        .copy(alpha = if (active) 0.75f else 0.35f),
                    start = from,
                    end = to,
                    strokeWidth = if (edge.fromId == selectedId || edge.toId == selectedId) {
                        2.4f * density
                    } else 1.2f * density
                )
            }

            graph.entities.forEach { entity ->
                val position = layout.positions[entity.id]?.toPixels(density) ?: return@forEach
                val nodeColor = typeColors[entity.type] ?: textSecondary
                val dimmed = activeIds != null && entity.id !in activeIds
                val radius = NODE_RADIUS_DP *
                    (0.78f + entity.confidence.coerceIn(0f, 1f) * 0.45f) * density
                drawCircle(
                    color = nodeColor.copy(alpha = if (dimmed) 0.25f else 0.95f),
                    radius = radius,
                    center = position
                )
                if (entity.id == selectedId) {
                    drawCircle(
                        color = textPrimary,
                        radius = radius + 4f * density,
                        center = position,
                        style = Stroke(width = 2f * density)
                    )
                }
                val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 11f * density
                    color = android.graphics.Color.argb(
                        if (dimmed) 110 else 255,
                        (textPrimary.red * 255).toInt(),
                        (textPrimary.green * 255).toInt(),
                        (textPrimary.blue * 255).toInt()
                    )
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val rawLabel = "${entity.type.name.take(3).uppercase()} · ${entity.label}"
                val edgePadding = GRAPH_LABEL_EDGE_PADDING_DP * density
                val maxLabelWidth = (size.width - edgePadding * 2f).coerceAtLeast(0f)
                val label = fitGraphLabelText(rawLabel, labelPaint, maxLabelWidth)
                val placement = clampGraphLabelPlacement(
                    nodeCenterX = position.x,
                    nodeBaselineY = position.y - radius - 7f * density,
                    labelWidth = labelPaint.measureText(label),
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    fontAscent = labelPaint.fontMetrics.ascent,
                    fontDescent = labelPaint.fontMetrics.descent,
                    edgePadding = edgePadding
                )
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    placement.centerX,
                    placement.baselineY,
                    labelPaint
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
    onSelect: (String) -> Unit,
    accent: Color,
    border: Color,
    surface: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    val byId = remember(graph) { graph.entities.associateBy(DossierEntity::id) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surface, RoundedCornerShape(10.dp))
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .padding(6.dp)
    ) {
        graph.entities.forEach { entity ->
            val selected = entity.id == selectedId
            val edges = graph.edges.filter { it.fromId == entity.id || it.toId == entity.id }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onSelect(entity.id) }
                    .semantics {
                        this.selected = selected
                        stateDescription = if (selected) "Selected" else "Not selected"
                    }
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "${entity.type.name}: ${entity.label}",
                    color = textPrimary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 12.5.sp
                )
                if (edges.isEmpty()) {
                    Text("No recorded connections", color = textSecondary, fontSize = 11.sp)
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
                            color = textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                        )
                    }
                }
            }
            HorizontalDivider(color = border, thickness = 0.5.dp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntityGraphLegend(typeColors: Map<EntityType, Color>, textSecondary: Color) {
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
                        .background(typeColors[type] ?: textSecondary)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(type.name, color = textSecondary, fontSize = 10.5.sp)
            }
        }
    }
}

private data class GraphLayout(
    val positions: Map<String, Offset>,
    val width: Dp,
    val height: Dp
)

private fun layoutGraph(graph: EntityGraph): GraphLayout {
    val subject = graph.entities.firstOrNull { it.type == EntityType.Person }
        ?: graph.entities.first()
    val raw = mutableMapOf(subject.id to Offset.Zero)
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
    return GraphLayout(
        positions = raw.mapValues { (_, point) ->
            Offset(point.x - minX + padding, point.y - minY + padding)
        },
        width = maxOf(360f, maxX - minX + padding * 2f).dp,
        height = maxOf(320f, maxY - minY + padding * 2f).dp
    )
}

private fun buildAdjacency(graph: EntityGraph): Map<String, Set<String>> {
    val result = mutableMapOf<String, MutableSet<String>>()
    graph.edges.forEach { edge ->
        result.getOrPut(edge.fromId) { mutableSetOf() }.add(edge.toId)
        result.getOrPut(edge.toId) { mutableSetOf() }.add(edge.fromId)
    }
    return result
}

private fun Offset.toPixels(density: Float): Offset = Offset(x * density, y * density)
private fun squaredDistance(first: Offset, second: Offset): Float {
    val dx = first.x - second.x
    val dy = first.y - second.y
    return dx * dx + dy * dy
}

/** Pixel placement for one canvas label, constrained to the drawable viewport. */
internal data class GraphLabelPlacement(
    val centerX: Float,
    val baselineY: Float
)

/**
 * Keeps a measured label inside the canvas on both axes. The graph can be
 * horizontally scrollable, but labels still must not be clipped by the
 * canvas' own bounds when a node is laid out close to an edge.
 */
internal fun clampGraphLabelPlacement(
    nodeCenterX: Float,
    nodeBaselineY: Float,
    labelWidth: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    fontAscent: Float,
    fontDescent: Float,
    edgePadding: Float
): GraphLabelPlacement {
    val safeCanvasWidth = canvasWidth.coerceAtLeast(0f)
    val safeCanvasHeight = canvasHeight.coerceAtLeast(0f)
    val safeLabelWidth = labelWidth.coerceAtLeast(0f)
    val safeEdgePadding = edgePadding.coerceAtLeast(0f)

    val minCenterX = safeEdgePadding + safeLabelWidth / 2f
    val maxCenterX = safeCanvasWidth - safeEdgePadding - safeLabelWidth / 2f
    val centerX = if (maxCenterX >= minCenterX) {
        nodeCenterX.coerceIn(minCenterX, maxCenterX)
    } else {
        safeCanvasWidth / 2f
    }

    val minBaseline = safeEdgePadding - fontAscent
    val maxBaseline = safeCanvasHeight - safeEdgePadding - fontDescent
    val baselineY = if (maxBaseline >= minBaseline) {
        nodeBaselineY.coerceIn(minBaseline, maxBaseline)
    } else {
        safeCanvasHeight / 2f
    }

    return GraphLabelPlacement(centerX = centerX, baselineY = baselineY)
}

private fun fitGraphLabelText(
    rawText: String,
    paint: android.graphics.Paint,
    maxWidth: Float
): String {
    if (rawText.isBlank() || maxWidth <= 0f || paint.measureText(rawText) <= maxWidth) {
        return if (maxWidth > 0f) rawText else ""
    }

    val ellipsisWidth = paint.measureText(GRAPH_LABEL_ELLIPSIS)
    if (ellipsisWidth > maxWidth) return ""
    val prefixWidth = maxWidth - ellipsisWidth
    val prefixLength = paint.breakText(rawText, true, prefixWidth, null)
    return rawText.take(prefixLength).trimEnd() + GRAPH_LABEL_ELLIPSIS
}

private const val NODE_RADIUS_DP = 17f
private const val GRAPH_LABEL_EDGE_PADDING_DP = 10f
private const val GRAPH_LABEL_ELLIPSIS = "…"
