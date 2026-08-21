package io.dossier.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Portable investigation-graph exports for Gephi/Cytoscape/spreadsheets/scripts. */
class GraphExportService(private val context: Context) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    data class Bundle(
        val graphMl: File,
        val nodesCsv: File,
        val edgesCsv: File,
        val jsonFile: File
    ) {
        fun files(): List<File> = listOf(graphMl, nodesCsv, edgesCsv, jsonFile)
    }

    fun createBundle(graph: EntityGraph, label: String = "investigation"): Bundle {
        val directory = File(context.cacheDir, "graph-exports").also { it.mkdirs() }
        val safe = label.replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-').ifBlank { "investigation" }.take(48)
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val base = "dossier-$safe-$timestamp"

        val graphMl = File(directory, "$base.graphml").apply { writeText(toGraphMl(graph)) }
        val nodesCsv = File(directory, "$base.nodes.csv").apply { writeText(nodesCsv(graph)) }
        val edgesCsv = File(directory, "$base.edges.csv").apply { writeText(edgesCsv(graph)) }
        val jsonFile = File(directory, "$base.graph.json").apply { writeText(json.encodeToString(graph)) }
        return Bundle(graphMl, nodesCsv, edgesCsv, jsonFile)
    }

    fun share(graph: EntityGraph, label: String = "investigation") {
        val bundle = createBundle(graph, label)
        val uris = ArrayList<Uri>()
        bundle.files().forEach { file ->
            uris += FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_SUBJECT, "Dossier graph export — $label")
            putExtra(
                Intent.EXTRA_TEXT,
                "Dossier graph bundle: GraphML + node/edge CSV + JSON. Graph relationships represent the evidence state recorded in the exported investigation."
            )
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("Dossier graph", uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
        }
        context.startActivity(
            Intent.createChooser(intent, "Export investigation graph").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    internal fun toGraphMl(graph: EntityGraph): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">")
        appendLine("  <key id=\"label\" for=\"node\" attr.name=\"label\" attr.type=\"string\"/>")
        appendLine("  <key id=\"kind\" for=\"node\" attr.name=\"kind\" attr.type=\"string\"/>")
        appendLine("  <key id=\"state\" for=\"node\" attr.name=\"state\" attr.type=\"string\"/>")
        appendLine("  <key id=\"confidence\" for=\"all\" attr.name=\"confidence\" attr.type=\"double\"/>")
        appendLine("  <key id=\"relation\" for=\"edge\" attr.name=\"relation\" attr.type=\"string\"/>")
        appendLine("  <key id=\"historical\" for=\"all\" attr.name=\"historical\" attr.type=\"boolean\"/>")
        appendLine("  <graph id=\"dossier\" edgedefault=\"directed\">")
        graph.entities.forEach { entity -> appendGraphMlNode(entity) }
        graph.edges.forEachIndexed { index, edge -> appendGraphMlEdge(index, edge) }
        appendLine("  </graph>")
        appendLine("</graphml>")
    }

    internal fun nodesCsv(graph: EntityGraph): String = buildString {
        appendLine("id,label,type,kind,state,confidence,historical,source_urls,evidence_ids")
        graph.entities.forEach { entity ->
            appendLine(
                listOf(
                    entity.id,
                    entity.label,
                    entity.type.name,
                    entity.kind.name,
                    entity.state.name,
                    entity.confidence.toString(),
                    entity.historical.toString(),
                    entity.sourceUrls.joinToString(" | "),
                    entity.evidenceIds.joinToString(" | ")
                ).joinToString(",", transform = ::csv)
            )
        }
    }

    internal fun edgesCsv(graph: EntityGraph): String = buildString {
        appendLine("source,target,relation,relation_type,confidence,historical,evidence,evidence_ids,contradicting_evidence_ids")
        graph.edges.forEach { edge ->
            appendLine(
                listOf(
                    edge.fromId,
                    edge.toId,
                    edge.relation,
                    edge.relationType.name,
                    edge.confidence?.toString().orEmpty(),
                    edge.historical.toString(),
                    edge.evidence.orEmpty(),
                    edge.evidenceIds.joinToString(" | "),
                    edge.contradictingEvidenceIds.joinToString(" | ")
                ).joinToString(",", transform = ::csv)
            )
        }
    }

    private fun StringBuilder.appendGraphMlNode(entity: DossierEntity) {
        appendLine("    <node id=\"${xml(entity.id)}\">")
        appendLine("      <data key=\"label\">${xml(entity.label)}</data>")
        appendLine("      <data key=\"kind\">${xml(entity.kind.name)}</data>")
        appendLine("      <data key=\"state\">${xml(entity.state.name)}</data>")
        appendLine("      <data key=\"confidence\">${entity.confidence}</data>")
        appendLine("      <data key=\"historical\">${entity.historical}</data>")
        appendLine("    </node>")
    }

    private fun StringBuilder.appendGraphMlEdge(index: Int, edge: DossierEdge) {
        appendLine(
            "    <edge id=\"e$index\" source=\"${xml(edge.fromId)}\" target=\"${xml(edge.toId)}\">"
        )
        appendLine("      <data key=\"relation\">${xml(edge.relation)}</data>")
        edge.confidence?.let { appendLine("      <data key=\"confidence\">$it</data>") }
        appendLine("      <data key=\"historical\">${edge.historical}</data>")
        appendLine("    </edge>")
    }

    private fun csv(value: String): String =
        "\"${value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ")}\""

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
