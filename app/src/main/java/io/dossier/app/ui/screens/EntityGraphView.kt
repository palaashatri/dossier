package io.dossier.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dossier.app.domain.model.DossierEdge
import kotlin.math.cos
import kotlin.math.sin
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.evidence.ConfidenceEngine
import io.dossier.app.domain.evidence.RelationshipConfidence
import io.dossier.app.ui.theme.NeuralTheme

/**
 * Interactive identity-graph view (ROADMAP Milestone 8).
 *
 * Renders the [EntityGraph] as a node-link diagram on a Canvas: nodes are
 * colored by [EntityType], sized by confidence, and edges drawn at reduced
 * opacity (per UI guidance: relationship edges ~60% opacity). Tapping a node
 * highlights it and its neighbours and surfaces the evidence behind them.
 *
 * Accessibility: network graphs are poor for screen readers, so this view also
 * offers an **adjacency-list alternative** (toggle) that renders the full graph
 * as a readable, selectable text list — required for non-visual access.
 *
 * Design follows the app's calm/flat dark theme (no neon glow) while adopting
 * the skill's categorical node colors, monospace evidence text, and the
 * 60%-opacity edge convention.
 *
 * Colors are resolved once in the composable scope because Canvas drawing runs
 * inside a non-composable [androidx.compose.ui.graphics.drawscope.DrawScope].
 */
@Composable
fun EntityGraphView(
    graph: EntityGraph,
    modifier: Modifier = Modifier,
    confidenceByEdge: Map<String, RelationshipConfidence> = emptyMap()
) {
    if (graph.entities.isEmpty()) {
        Text(
            text = "No entity links compiled for this scan yet.",
            color = NeuralTheme.TextSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = modifier.padding(4.dp)
        )
        return
    }

    var viewMode by remember { mutableStateOf(GraphViewMode.Graph) }
    var selectedId by remember(graph) { mutableStateOf<String?>(null) }
    val density = LocalDensity.current

    val cobalt = NeuralTheme.Cobalt
    val emerald = NeuralTheme.Emerald
    val amber = NeuralTheme.Amber
    val crimson = NeuralTheme.Crimson
    val border = NeuralTheme.BorderColor
    val textPrimary = NeuralTheme.TextPrimary
    val textSecondary = NeuralTheme.TextSecondary

    val typeColors = mapOf(
        EntityType.Person to cobalt,
        EntityType.Username to emerald,
        EntityType.Email to amber,
        EntityType.Phone to crimson,
        EntityType.Profile to cobalt,
        EntityType.Organization to emerald,
        EntityType.Location to amber,
        EntityType.Image to crimson,
        EntityType.Breach to crimson,
        EntityType.Website to border
    )

    val nodePositions = remember(graph) { layoutGraph(graph) }
    val adjacency = remember(graph) { buildAdjacency(graph) }

    val canvasHeight = (nodePositions.values.maxOfOrNull { it.y }?.plus(70f) ?: 320f).dp
    val canvasWidth = (nodePositions.values.maxOfOrNull { it.x }?.plus(70f) ?: 360f).dp

    Column(modifier = modifier) {
        // View switcher: Graph (visual) <-> List (adjacency accessibility fallback)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GraphViewTab(
                label = "Graph",
                selected = viewMode == GraphViewMode.Graph,
                onClick = { viewMode = GraphViewMode.Graph },
                cobalt = cobalt,
                border = border,
                textSecondary = textSecondary
            )
            GraphViewTab(
                label = "List",
                selected = viewMode == GraphViewMode.List,
                onClick = { viewMode = GraphViewMode.List },
                cobalt = cobalt,
                border = border,
                textSecondary = textSecondary
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        when (viewMode) {
            GraphViewMode.Graph -> GraphCanvas(
                graph = graph,
                nodePositions = nodePositions,
                adjacency = adjacency,
                selectedId = selectedId,
                onSelect = { selectedId = if (selectedId == it) null else it },
                density = density,
                canvasHeight = canvasHeight,
                canvasWidth = canvasWidth,
                typeColors = typeColors,
                cobalt = cobalt,
                border = border,
                textPrimary = textPrimary,
                textSecondary = textSecondary
            )
            GraphViewMode.List -> AdjacencyList(
                graph = graph,
                confidenceByEdge = confidenceByEdge,
                selectedId = selectedId,
                onSelect = { selectedId = if (selectedId == it) null else it },
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                border = border
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        EntityGraphLegend(typeColors = typeColors, textSecondary = textSecondary)

        // Shared detail panel (works in both views)
        selectedId?.let { id ->
            val entity = graph.entities.firstOrNull { it.id == id } ?: return@let
            val connectedEdges = graph.edges.filter { it.fromId == id || it.toId == id }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = border, thickness = 0.7.dp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "${entity.type.name}  ·  ${entity.label}",
                color = textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Text(
                text = "Confidence: ${(entity.confidence * 100).toInt()}%",
                color = textSecondary,
                fontSize = 12.sp
            )
            if (entity.sourceUrls.isNotEmpty()) {
                Text(
                    text = "Sources: ${entity.sourceUrls.joinToString(", ")}",
                    color = textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (connectedEdges.isNotEmpty()) {
                Text(
                    text = "Connections:",
                    color = textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                connectedEdges.forEach { edge ->
                    val otherId = if (edge.fromId == id) edge.toId else edge.fromId
                    val other = graph.entities.firstOrNull { it.id == otherId }
                    val scored = confidenceByEdge[ConfidenceEngine.edgeKey(edge.fromId, edge.toId, edge.relation)]
                    val scoreText = scored?.let { "  → conf ${(it.score * 100).toInt()}%" } ?: ""
                    Text(
                        text = "• ${edge.relation} → ${other?.label ?: otherId}" +
                            (edge.evidence?.takeIf { it.isNotBlank() }?.let { "  (${it.take(60)})" } ?: "") +
                            scoreText,
                        color = textPrimary,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    scored?.let {
                        if (it.reasons.isNotEmpty()) {
                            Text(
                                text = "    why: ${it.reasons.joinToString("; ")}",
                                color = textSecondary,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
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
    cobalt: Color,
    border: Color,
    textSecondary: Color
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) cobalt.copy(alpha = 0.14f) else Color.Transparent)
            .border(0.6.dp, if (selected) cobalt else border, RoundedCornerShape(8.dp))
    ) {
        Text(
            text = label,
            color = if (selected) cobalt else textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun GraphCanvas(
    graph: EntityGraph,
    nodePositions: Map<String, Offset>,
    adjacency: Map<String, Set<String>>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    density: androidx.compose.ui.unit.Density,
    canvasHeight: androidx.compose.ui.unit.Dp,
    canvasWidth: androidx.compose.ui.unit.Dp,
    typeColors: Map<EntityType, Color>,
    cobalt: Color,
    border: Color,
    textPrimary: Color,
    textSecondary: Color
) {
    val semanticsText = remember(graph) {
        "Identity graph with ${graph.entities.size} nodes and ${graph.edges.size} connections. " +
            "Switch to List view for a text alternative."
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(canvasHeight)
            .verticalScroll(rememberScrollState())
            .semantics { contentDescription = semanticsText }
    ) {
        Canvas(
            modifier = Modifier
                .width(canvasWidth)
                .height(canvasHeight)
                .pointerInput(graph) {
                    detectTapGestures { offset ->
                        val hit = nodePositions.entries.minByOrNull { (_, p) ->
                            (p.x - offset.x) * (p.x - offset.x) + (p.y - offset.y) * (p.y - offset.y)
                        }
                        hit?.let { (id, p) ->
                            val within = NODE_RADIUS + 12f
                            if ((p.x - offset.x) * (p.x - offset.x) + (p.y - offset.y) * (p.y - offset.y) <= within * within) {
                                onSelect(id)
                            }
                        }
                    }
                }
        ) {
            val scale = density.density
            val active = selectedId?.let { adjacency[it].orEmpty() + it } ?: null

            graph.edges.forEach { edge ->
                val from = nodePositions[edge.fromId] ?: return@forEach
                val to = nodePositions[edge.toId] ?: return@forEach
                val isActive = active == null || (edge.fromId in active && edge.toId in active)
                // Skill guidance: relationship edges at reduced opacity (~60%).
                val baseAlpha = if (isActive) 0.85f else 0.6f
                drawLine(
                    color = (if (isActive) cobalt else border).copy(alpha = baseAlpha),
                    start = from,
                    end = to,
                    strokeWidth = if (edge.fromId == selectedId || edge.toId == selectedId) 2.2f else 1f
                )
            }

            graph.entities.forEach { entity ->
                val pos = nodePositions[entity.id] ?: return@forEach
                val color = typeColors[entity.type] ?: border
                val isDimmed = active != null && entity.id !in active
                val radius = (NODE_RADIUS * (0.7f + entity.confidence.coerceIn(0f, 1f) * 0.6f))
                drawCircle(
                    color = color.copy(alpha = if (isDimmed) 0.25f else 1f),
                    radius = radius,
                    center = pos
                )
                if (entity.id == selectedId) {
                    drawCircle(
                        color = textPrimary,
                        radius = radius + 3f,
                        center = pos,
                        style = Stroke(width = 1.5f)
                    )
                }
                val label = entity.label.take(18)
                val textSize = 11f * scale
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    pos.x,
                    pos.y - radius - 4f * scale,
                    android.graphics.Paint().apply {
                        this.textSize = textSize
                        this.color = android.graphics.Color.argb(
                            if (isDimmed) 120 else 255,
                            (textPrimary.red * 255).toInt(),
                            (textPrimary.green * 255).toInt(),
                            (textPrimary.blue * 255).toInt()
                        )
                        textAlign = android.graphics.Paint.Align.CENTER
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
    onSelect: (String) -> Unit,
    textPrimary: Color,
    textSecondary: Color,
    border: Color
) {
    val byId = graph.entities.associateBy { it.id }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        graph.entities.forEach { entity ->
            val isSelected = entity.id == selectedId
            val outgoing = graph.edges.filter { it.fromId == entity.id }
            val confidenceCount = outgoing.count {
                confidenceByEdge[ConfidenceEngine.edgeKey(it.fromId, it.toId, it.relation)] != null
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) NeuralTheme.Cobalt.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onSelect(entity.id) }
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "${entity.type.name}: ${entity.label}",
                    color = textPrimary,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 12.sp
                )
                if (outgoing.isNotEmpty()) {
                    outgoing.forEach { edge ->
                        val other = byId[edge.toId]
                        val scored = confidenceByEdge[ConfidenceEngine.edgeKey(edge.fromId, edge.toId, edge.relation)]
                        val scoreText = scored?.let { "  (conf ${(it.score * 100).toInt()}%)" } ?: ""
                        Text(
                            text = "   ↳ ${edge.relation} → ${other?.label ?: edge.toId}$scoreText",
                            color = textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    Text(
                        text = "   (no outgoing connections)",
                        color = textSecondary,
                        fontSize = 10.5.sp
                    )
                }
            }
            HorizontalDivider(color = border, thickness = 0.5.dp)
        }
    }
}

@Composable
private fun EntityGraphLegend(
    typeColors: Map<EntityType, Color>,
    textSecondary: Color
) {
    val items = EntityType.values().take(10)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items.forEach { type ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(typeColors[type] ?: Color.Gray)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = type.name,
                    color = textSecondary,
                    fontSize = 9.5.sp
                )
            }
        }
    }
}

private const val NODE_RADIUS = 16f

/**
 * Deterministic radial layout: subject (Person) at center, all other nodes
 * placed on concentric rings so same-type nodes cluster. Not a force layout —
 * stable across recompositions and cheap to compute.
 */
private fun layoutGraph(graph: EntityGraph): Map<String, Offset> {
    val subject = graph.entities.firstOrNull { it.type == EntityType.Person }
        ?: graph.entities.firstOrNull()
        ?: return emptyMap()

    val others = graph.entities.filter { it.id != subject.id }
    val positions = mutableMapOf<String, Offset>()
    positions[subject.id] = Offset(190f, 170f)

    if (others.isEmpty()) return positions

    val ringRadiusStep = 75f
    val perRing = 8
    others.forEachIndexed { index, _ ->
        val ring = (index / perRing) + 1
        val slot = index % perRing
        val angle = (Math.PI * 2 * slot / perRing) + (ring * 0.4)
        val radius = ringRadiusStep * ring
        val cx = 190f + (radius * cos(angle)).toFloat()
        val cy = 170f + (radius * sin(angle)).toFloat()
        val entity = graph.entities.filter { it.id != subject.id }[index]
        positions[entity.id] = Offset(cx, cy)
    }
    return positions
}

private fun buildAdjacency(graph: EntityGraph): Map<String, Set<String>> {
    val adj = mutableMapOf<String, MutableSet<String>>()
    fun add(a: String, b: String) {
        adj.getOrPut(a) { mutableSetOf() }.add(b)
        adj.getOrPut(b) { mutableSetOf() }.add(a)
    }
    graph.edges.forEach { add(it.fromId, it.toId) }
    return adj
}
