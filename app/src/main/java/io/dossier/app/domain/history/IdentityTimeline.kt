package io.dossier.app.domain.history

import io.dossier.app.domain.breach.EmailExposureResult
import io.dossier.app.domain.evidence.Evidence
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

@Serializable
enum class TimelineEventType {
    CurrentEvidence,
    HistoricalEvidence,
    BreachIncident
}

@Serializable
data class IdentityTimelineEvent(
    val id: String,
    val timestampEpochMillis: Long,
    val type: TimelineEventType,
    val title: String,
    val description: String,
    val evidenceIds: List<String> = emptyList(),
    val sourceUrl: String? = null,
    val provider: String? = null,
    val historical: Boolean = false
)

/**
 * Creates a chronological identity/exposure view using only timestamps that
 * actually exist in evidence/provider records. Untimestamped observations are
 * deliberately omitted instead of being assigned an invented scan date.
 */
object IdentityTimelineBuilder {
    fun build(
        evidence: List<Evidence>,
        breachResults: List<EmailExposureResult> = emptyList()
    ): List<IdentityTimelineEvent> {
        val events = mutableListOf<IdentityTimelineEvent>()

        evidence.forEach evidenceLoop@{ item ->
            val timestamp = item.observedAtEpochMillis ?: item.retrievedAtEpochMillis ?: return@evidenceLoop
            events += IdentityTimelineEvent(
                id = "timeline:evidence:${item.id}",
                timestampEpochMillis = timestamp,
                type = if (item.historical) TimelineEventType.HistoricalEvidence else TimelineEventType.CurrentEvidence,
                title = if (item.historical) "Historical ${item.kind.name}" else item.kind.name,
                description = item.value,
                evidenceIds = listOf(item.id),
                sourceUrl = item.sourceUrl,
                provider = item.providerId,
                historical = item.historical
            )
        }

        breachResults.forEach breachResultLoop@{ result ->
            result.breaches.forEach breachLoop@{ breach ->
                val timestamp = parseDateToEpochMillis(breach.breachDate) ?: return@breachLoop
                events += IdentityTimelineEvent(
                    id = "timeline:breach:${result.email}:${breach.name}",
                    timestampEpochMillis = timestamp,
                    type = TimelineEventType.BreachIncident,
                    title = breach.title.ifBlank { breach.name },
                    description = buildString {
                        append("Authoritative breach metadata associated with the supplied identifier")
                        if (breach.dataClasses.isNotEmpty()) {
                            append(": ")
                            append(breach.dataClasses.take(8).joinToString(", "))
                        }
                    },
                    provider = breach.sourceProvider,
                    historical = true
                )
            }
        }

        return events
            .distinctBy(IdentityTimelineEvent::id)
            .sortedWith(
                compareBy<IdentityTimelineEvent> { it.timestampEpochMillis }
                    .thenBy { it.id }
            )
    }

    internal fun parseDateToEpochMillis(value: String?): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrElse {
            runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrElse {
                try {
                    LocalDate.parse(raw).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }
}
