package io.dossier.app.export

import io.dossier.app.domain.model.EntityGraph
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Dependency-free graph interoperability for Gephi, Cytoscape, NetworkX and
 * hosted graph-analysis tools that accept standard graph formats.
 *
 * These serializers do not add or infer relationships: they export only the
 * current Dossier graph and its evidence linkage. Callers remain responsible for
 * applying the same share-safe redaction policy used by normal report exports
 * before sharing a graph externally.
 */
object GraphInteropExporter {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun toGraphMl(graph: EntityGraph): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">")
        appendLine("  <key id=\"label\" for=\"node\" attr.name=\"label\" attr.type=\"string\"/>")
        appendLine("  <key id=\"kind\" for=\"node\" attr.name=\"kind\" attr.type=\"string\"/>")
        appendLine("  <key id=\"state\" for=\"node\" attr.name=\"state\" attr.type=\"string\"/>")
        appendLine("  <key id=\"confidence\" for=\"all\" attr.name=\"confidence\" attr.type=\"double\"/>")
        appendLine("  <key id=\"relation\" for=\"edge\" attr.name=\"relation\" attr.type=\"string\"/>")
        appendLine("  <key id=\"historical\" for=\"all\" attr.name=\"historical\" attr.type=\"boolean\"/>")
        appendLine("  <graph id=\"dossier\" edgedefault=\"directed\">")
        graph.entities.forEach { node ->
            appendLine("    <node id=\"${xml(node.id)}\">")
            appendLine("      <data key=\"label\">${xml(node.label)}</data>")
            appendLine("      <data key=\"kind\">${xml(node.kind.name)}</data>")
            appendLine("      <data key=\"state\">${xml(node.state.name)}</data>")
            appendLine("      <data key=\"confidence\">${node.confidence.coerceIn(0f, 1f)}</data>")
            appendLine("      <data key=\"historical\">${node.historical}</data>")
            appendLine("    </node>")
        }
        graph.edges.forEachIndexed { index, edge ->
            appendLine("    <edge id=\"e$index\" source=\"${xml(edge.fromId)}\" target=\"${xml(edge.toId)}\">")
            appendLine("      <data key=\"relation\">${xml(edge.relationType.name)}</data>")
            edge.confidence?.let { appendLine("      <data key=\"confidence\">${it.coerceIn(0f, 1f)}</data>") }
            appendLine("      <data key=\"historical\">${edge.historical}</data>")
            appendLine("    </edge>")
        }
        appendLine("  </graph>")
        appendLine("</graphml>")
    }

    fun toGexf(graph: EntityGraph): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<gexf xmlns=\"http://gexf.net/1.3\" version=\"1.3\">")
        appendLine("  <graph mode=\"static\" defaultedgetype=\"directed\">")
        appendLine("    <attributes class=\"node\">")
        appendLine("      <attribute id=\"kind\" title=\"kind\" type=\"string\"/>")
        appendLine("      <attribute id=\"state\" title=\"state\" type=\"string\"/>")
        appendLine("      <attribute id=\"confidence\" title=\"confidence\" type=\"double\"/>")
        appendLine("      <attribute id=\"historical\" title=\"historical\" type=\"boolean\"/>")
        appendLine("    </attributes>")
        appendLine("    <attributes class=\"edge\">")
        appendLine("      <attribute id=\"relation\" title=\"relation\" type=\"string\"/>")
        appendLine("      <attribute id=\"historical\" title=\"historical\" type=\"boolean\"/>")
        appendLine("    </attributes>")
        appendLine("    <nodes>")
        graph.entities.forEach { node ->
            appendLine("      <node id=\"${xml(node.id)}\" label=\"${xml(node.label)}\">")
            appendLine("        <attvalues>")
            appendLine("          <attvalue for=\"kind\" value=\"${xml(node.kind.name)}\"/>")
            appendLine("          <attvalue for=\"state\" value=\"${xml(node.state.name)}\"/>")
            appendLine("          <attvalue for=\"confidence\" value=\"${node.confidence.coerceIn(0f, 1f)}\"/>")
            appendLine("          <attvalue for=\"historical\" value=\"${node.historical}\"/>")
            appendLine("        </attvalues>")
            appendLine("      </node>")
        }
        appendLine("    </nodes>")
        appendLine("    <edges>")
        graph.edges.forEachIndexed { index, edge ->
            appendLine(
                "      <edge id=\"$index\" source=\"${xml(edge.fromId)}\" target=\"${xml(edge.toId)}\" " +
                    "label=\"${xml(edge.relationType.name)}\" weight=\"${edge.confidence?.coerceIn(0f, 1f) ?: 0.5f}\">"
            )
            appendLine("        <attvalues>")
            appendLine("          <attvalue for=\"relation\" value=\"${xml(edge.relationType.name)}\"/>")
            appendLine("          <attvalue for=\"historical\" value=\"${edge.historical}\"/>")
            appendLine("        </attvalues>")
            appendLine("      </edge>")
        }
        appendLine("    </edges>")
        appendLine("  </graph>")
        appendLine("</gexf>")
    }

    /** NetworkX node-link style JSON; also convenient for Cytoscape/Graphistry adapters. */
    fun toNodeLinkJson(graph: EntityGraph): String {
        val root = buildJsonObject {
            put("directed", true)
            put("multigraph", true)
            put("schemaVersion", graph.schemaVersion)
            putJsonArray("nodes") {
                graph.entities.forEach { node ->
                    add(buildJsonObject {
                        put("id", node.id)
                        put("label", node.label)
                        put("type", node.type.name)
                        put("kind", node.kind.name)
                        put("state", node.state.name)
                        put("confidence", node.confidence.coerceIn(0f, 1f))
                        put("historical", node.historical)
                        putJsonArray("evidenceIds") { node.evidenceIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                        putJsonArray("sourceUrls") { node.sourceUrls.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                    })
                }
            }
            putJsonArray("links") {
                graph.edges.forEachIndexed { index, edge ->
                    add(buildJsonObject {
                        put("key", index)
                        put("source", edge.fromId)
                        put("target", edge.toId)
                        put("relation", edge.relation)
                        put("relationType", edge.relationType.name)
                        edge.confidence?.let { put("confidence", it.coerceIn(0f, 1f)) }
                        put("historical", edge.historical)
                        putJsonArray("evidenceIds") { edge.evidenceIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                        putJsonArray("contradictingEvidenceIds") { edge.contradictingEvidenceIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                    })
                }
            }
        }
        return json.encodeToString(root)
    }

    private fun xml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
