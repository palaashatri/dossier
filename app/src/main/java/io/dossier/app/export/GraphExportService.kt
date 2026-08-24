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
class GraphExportService(private val context: Context? = null) {

    data class Bundle(
        val graphMl: File,
        val nodesCsv: File,
        val edgesCsv: File,
        val jsonFile: File,
        val isRedacted: Boolean = false
    ) {
        fun files(): List<File> = listOf(graphMl, nodesCsv, edgesCsv, jsonFile)
    }

    fun createBundle(
        graph: EntityGraph,
        label: String = "investigation",
        redactionMode: ExportRedactionMode = ExportRedactionMode.None
    ): Bundle {
        val baseDir = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir"), "dossier-graph-exports")
        val directory = File(baseDir, "graph-exports").also { it.mkdirs() }
        return createBundle(directory = directory, graph = graph, label = label, redactionMode = redactionMode)
    }

    fun share(
        graph: EntityGraph,
        label: String = "investigation",
        redactionMode: ExportRedactionMode = ExportRedactionMode.ShareSafe
    ) {
        val ctx = checkNotNull(context) { "Android Context is required to share graph export" }
        val bundle = createBundle(graph = graph, label = label, redactionMode = redactionMode)
        val isRedacted = redactionMode == ExportRedactionMode.ShareSafe
        val uris = ArrayList<Uri>()
        bundle.files().forEach { file ->
            uris += FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        }
        val subject = if (isRedacted) {
            "Dossier share-safe graph export (redacted)"
        } else {
            "Dossier graph export — $label"
        }
        val shareMessage = if (isRedacted) {
            "Dossier share-safe graph bundle: GraphML + node/edge CSV + JSON.\n" +
            "All direct subject identifiers, account labels, source URLs, and evidence IDs have been redacted.\n" +
            "Graph topology and relationship metadata are preserved for structural analysis.\n" +
            "Notice: Graph connections represent recorded evidence paths and do not prove identity or account ownership."
        } else {
            "Dossier graph bundle: GraphML + node/edge CSV + JSON. Graph relationships represent the evidence state recorded in the exported investigation."
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, shareMessage)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("Dossier graph", uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
        }
        val chooserTitle = if (isRedacted) "Share redacted investigation graph" else "Export investigation graph"
        ctx.startActivity(
            Intent.createChooser(intent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun toGraphMl(graph: EntityGraph, redactionMode: ExportRedactionMode = ExportRedactionMode.None): String =
        Companion.toGraphMl(graph, redactionMode)

    fun nodesCsv(graph: EntityGraph, redactionMode: ExportRedactionMode = ExportRedactionMode.None): String =
        Companion.nodesCsv(graph, redactionMode)

    fun edgesCsv(graph: EntityGraph, redactionMode: ExportRedactionMode = ExportRedactionMode.None): String =
        Companion.edgesCsv(graph, redactionMode)

    companion object {
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun createBundle(
            directory: File,
            graph: EntityGraph,
            label: String = "investigation",
            redactionMode: ExportRedactionMode = ExportRedactionMode.None
        ): Bundle {
            directory.mkdirs()
            val isRedacted = redactionMode == ExportRedactionMode.ShareSafe
            val targetGraph = if (isRedacted) GraphRedactor.redact(graph) else graph
            val safe = (if (isRedacted) "redacted-$label" else label)
                .replace(Regex("[^A-Za-z0-9._-]+"), "-")
                .trim('-').ifBlank { "investigation" }.take(48)
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val redactionSuffix = if (isRedacted) "-share-safe" else ""
            val base = "dossier-$safe-$timestamp$redactionSuffix"

            val graphMl = File(directory, "$base.graphml").apply { writeText(toGraphMl(targetGraph)) }
            val nodesCsv = File(directory, "$base.nodes.csv").apply { writeText(nodesCsv(targetGraph)) }
            val edgesCsv = File(directory, "$base.edges.csv").apply { writeText(edgesCsv(targetGraph)) }
            val jsonFile = File(directory, "$base.graph.json").apply { writeText(json.encodeToString(targetGraph)) }
            return Bundle(graphMl, nodesCsv, edgesCsv, jsonFile, isRedacted = isRedacted)
        }

        fun createBundle(
            directory: File,
            graph: EntityGraph,
            label: String = "investigation",
            policy: GraphRedactionPolicy
        ): Bundle {
            return createBundle(directory, graph, label, policy.mode)
        }

        fun toGraphMl(
            graph: EntityGraph,
            redactionMode: ExportRedactionMode = ExportRedactionMode.None
        ): String {
            val target = if (redactionMode == ExportRedactionMode.ShareSafe) GraphRedactor.redact(graph) else graph
            return buildString {
                appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                appendLine("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">")
                appendLine("  <key id=\"label\" for=\"node\" attr.name=\"label\" attr.type=\"string\"/>")
                appendLine("  <key id=\"kind\" for=\"node\" attr.name=\"kind\" attr.type=\"string\"/>")
                appendLine("  <key id=\"state\" for=\"node\" attr.name=\"state\" attr.type=\"string\"/>")
                appendLine("  <key id=\"confidence\" for=\"all\" attr.name=\"confidence\" attr.type=\"double\"/>")
                appendLine("  <key id=\"relation\" for=\"edge\" attr.name=\"relation\" attr.type=\"string\"/>")
                appendLine("  <key id=\"historical\" for=\"all\" attr.name=\"historical\" attr.type=\"boolean\"/>")
                appendLine("  <graph id=\"dossier\" edgedefault=\"directed\">")
                target.entities.forEach { entity -> appendGraphMlNode(entity) }
                target.edges.forEachIndexed { index, edge -> appendGraphMlEdge(index, edge) }
                appendLine("  </graph>")
                appendLine("</graphml>")
            }
        }

        fun toGraphMl(graph: EntityGraph, policy: GraphRedactionPolicy): String =
            toGraphMl(GraphRedactor.redact(graph, policy), ExportRedactionMode.None)

        fun nodesCsv(
            graph: EntityGraph,
            redactionMode: ExportRedactionMode = ExportRedactionMode.None
        ): String {
            val target = if (redactionMode == ExportRedactionMode.ShareSafe) GraphRedactor.redact(graph) else graph
            return buildString {
                appendLine("id,label,type,kind,state,confidence,historical,source_urls,evidence_ids")
                target.entities.forEach { entity ->
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
        }

        fun nodesCsv(graph: EntityGraph, policy: GraphRedactionPolicy): String =
            nodesCsv(GraphRedactor.redact(graph, policy), ExportRedactionMode.None)

        fun edgesCsv(
            graph: EntityGraph,
            redactionMode: ExportRedactionMode = ExportRedactionMode.None
        ): String {
            val target = if (redactionMode == ExportRedactionMode.ShareSafe) GraphRedactor.redact(graph) else graph
            return buildString {
                appendLine("source,target,relation,relation_type,confidence,historical,evidence,evidence_ids,contradicting_evidence_ids")
                target.edges.forEach { edge ->
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
        }

        fun edgesCsv(graph: EntityGraph, policy: GraphRedactionPolicy): String =
            edgesCsv(GraphRedactor.redact(graph, policy), ExportRedactionMode.None)

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
}
