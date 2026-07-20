package io.dossier.app.domain.graph

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.model.BreachDigest
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ProfileScanResult
import java.util.Locale

/**
 * Pure builder that fuses identity input, profile scan results, findings,
 * face matches, and breach digests into a single entity graph for the report.
 */
object EntityGraphBuilder {

    fun build(
        input: IdentityInput,
        profileResults: List<ProfileScanResult> = emptyList(),
        findings: List<Finding> = emptyList(),
        faceMatches: List<FaceConsistencyMatch> = emptyList(),
        breachDigests: List<BreachDigest> = emptyList(),
        evidence: List<Evidence> = emptyList(),
        relationships: List<EvidenceRelationship> = emptyList()
    ): EntityGraph {
        val entities = linkedMapOf<String, DossierEntity>()
        val edges = mutableListOf<DossierEdge>()

        fun putEntity(entity: DossierEntity) {
            val existing = entities[entity.id]
            if (existing == null) {
                entities[entity.id] = entity
            } else {
                entities[entity.id] = existing.copy(
                    confidence = maxOf(existing.confidence, entity.confidence),
                    sourceUrls = (existing.sourceUrls + entity.sourceUrls).distinct(),
                    label = if (entity.label.length > existing.label.length) entity.label else existing.label
                )
            }
        }

        fun link(fromId: String, toId: String, relation: String, evidence: String? = null) {
            if (fromId == toId) return
            if (edges.any { it.fromId == fromId && it.toId == toId && it.relation == relation }) return
            edges.add(DossierEdge(fromId = fromId, toId = toId, relation = relation, evidence = evidence))
        }

        val subjectLabel = input.fullName.trim().ifBlank {
            input.primaryUsername?.trim()?.ifBlank { null }
                ?: input.usernames.firstOrNull { it.isNotBlank() }
                ?: input.emails.firstOrNull { it.isNotBlank() }
                ?: "Subject"
        }
        val subjectId = entityId(EntityType.Person, subjectLabel)
        putEntity(
            DossierEntity(
                id = subjectId,
                type = EntityType.Person,
                label = subjectLabel,
                confidence = 1.0f
            )
        )

        // ---- Identity seeds -------------------------------------------------
        input.emails.filter { it.isNotBlank() }.forEach { email ->
            val id = entityId(EntityType.Email, email)
            putEntity(DossierEntity(id = id, type = EntityType.Email, label = email.trim(), confidence = 1.0f))
            link(subjectId, id, "has_email")
        }
        input.phones.filter { it.isNotBlank() }.forEach { phone ->
            val id = entityId(EntityType.Phone, phone)
            putEntity(DossierEntity(id = id, type = EntityType.Phone, label = phone.trim(), confidence = 1.0f))
            link(subjectId, id, "has_phone")
        }
        val usernameSeeds = buildList {
            input.primaryUsername?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(input.usernames.filter { it.isNotBlank() })
        }.distinctBy { it.lowercase(Locale.US) }
        usernameSeeds.forEach { username ->
            val id = entityId(EntityType.Username, username)
            putEntity(DossierEntity(id = id, type = EntityType.Username, label = username.trim(), confidence = 1.0f))
            link(subjectId, id, "uses_username")
        }
        input.organizations.filter { it.isNotBlank() }.forEach { org ->
            val id = entityId(EntityType.Organization, org)
            putEntity(DossierEntity(id = id, type = EntityType.Organization, label = org.trim(), confidence = 0.9f))
            link(subjectId, id, "affiliated_with")
        }
        input.locations.filter { it.isNotBlank() }.forEach { loc ->
            val id = entityId(EntityType.Location, loc)
            putEntity(DossierEntity(id = id, type = EntityType.Location, label = loc.trim(), confidence = 0.9f))
            link(subjectId, id, "associated_with_location")
        }
        input.profileUrls.filter { it.isNotBlank() }.forEach { url ->
            val id = entityId(EntityType.Profile, url)
            putEntity(
                DossierEntity(
                    id = id,
                    type = EntityType.Profile,
                    label = url.trim(),
                    confidence = 1.0f,
                    sourceUrls = listOf(url.trim())
                )
            )
            link(subjectId, id, "owns_profile", evidence = "user-supplied")
        }

        // ---- Confirmed / candidate profiles ---------------------------------
        profileResults.forEach { result ->
            val url = result.candidate.url
            val profileId = entityId(EntityType.Profile, url)
            val conf = result.candidate.confidence.coerceIn(0f, 1f)
            putEntity(
                DossierEntity(
                    id = profileId,
                    type = EntityType.Profile,
                    label = result.displayName?.takeIf { it.isNotBlank() }
                        ?: "${result.candidate.platform.name}: ${result.candidate.username}",
                    confidence = conf,
                    sourceUrls = listOf(url)
                )
            )
            val relation = when {
                result.verified && result.exists -> "has_profile"
                result.exists -> "possible_profile"
                else -> "candidate_profile"
            }
            link(
                subjectId,
                profileId,
                relation,
                evidence = result.verificationStatus ?: result.provenance
            )

            val username = result.candidate.username
            if (username.isNotBlank() && username != "unknown" && username != "web") {
                val usernameId = entityId(EntityType.Username, username)
                putEntity(
                    DossierEntity(
                        id = usernameId,
                        type = EntityType.Username,
                        label = username,
                        confidence = conf,
                        sourceUrls = listOf(url)
                    )
                )
                if (result.exists) {
                    link(subjectId, usernameId, "uses_username", evidence = url)
                }
                link(profileId, usernameId, "username_on_profile")
            }

            // PII findings attributed to this profile
            result.findings.forEach { finding ->
                attachFinding(finding, subjectId, profileId, ::putEntity, ::link)
            }
        }

        // ---- Standalone findings (face, breach, public search, etc.) --------
        findings.forEach { finding ->
            val profileId = finding.sourceUrl?.takeIf { it.isNotBlank() }?.let { entityId(EntityType.Profile, it) }
            if (profileId != null && profileId !in entities) {
                putEntity(
                    DossierEntity(
                        id = profileId,
                        type = EntityType.Profile,
                        label = finding.sourceUrl!!,
                        confidence = finding.confidence,
                        sourceUrls = listOf(finding.sourceUrl)
                    )
                )
                link(subjectId, profileId, "related_profile", evidence = finding.type.name)
            }
            attachFinding(finding, subjectId, profileId, ::putEntity, ::link)
        }

        // ---- Evidence layer (M6: first-class Evidence correlation) -----------
        // Evidence is the universal language; the graph now fuses it directly
        // rather than only through the Finding adapter. Scanner-asserted
        // relationships seed edges before the correlation engine generalizes.
        relationships.forEach { rel ->
            val fromId = entityIdForValue(rel.fromValue)
            val toId = entityIdForValue(rel.toValue)
            putEntity(DossierEntity(id = fromId, type = EntityType.Website, label = rel.fromValue, confidence = 0.7f))
            putEntity(DossierEntity(id = toId, type = EntityType.Website, label = rel.toValue, confidence = 0.7f))
            link(fromId, toId, rel.relation, rel.evidence)
        }
        evidence.forEach { ev ->
            attachEvidence(ev, subjectId, ::putEntity, ::link)
        }

        // ---- Face consistency -----------------------------------------------
        faceMatches.forEach { match ->
            val profileId = entityId(EntityType.Profile, match.profileUrl)
            if (profileId !in entities) {
                putEntity(
                    DossierEntity(
                        id = profileId,
                        type = EntityType.Profile,
                        label = match.profileUrl,
                        confidence = match.similarityScore.coerceIn(0f, 1f),
                        sourceUrls = listOf(match.profileUrl)
                    )
                )
                link(subjectId, profileId, "possible_profile", evidence = match.warning)
            }
            val imageId = entityId(EntityType.Image, "face:${match.profileUrl}")
            putEntity(
                DossierEntity(
                    id = imageId,
                    type = EntityType.Image,
                    label = "Face match ${(match.similarityScore * 100).toInt()}%",
                    confidence = match.similarityScore.coerceIn(0f, 1f),
                    sourceUrls = listOf(match.profileUrl)
                )
            )
            link(subjectId, imageId, "face_similar_to", evidence = match.warning)
            link(imageId, profileId, "image_of_profile", evidence = match.warning)
        }

        // ---- Breaches -------------------------------------------------------
        breachDigests.forEach { digest ->
            val emailId = entityId(EntityType.Email, digest.email)
            if (emailId !in entities) {
                putEntity(
                    DossierEntity(
                        id = emailId,
                        type = EntityType.Email,
                        label = digest.email,
                        confidence = 0.9f
                    )
                )
                link(subjectId, emailId, "has_email")
            }
            if (digest.breachCount > 0 || digest.sources.isNotEmpty()) {
                val breachId = entityId(EntityType.Breach, digest.email)
                val label = if (digest.breachCount > 0) {
                    "${digest.breachCount} breach(es) for ${digest.email}"
                } else {
                    "Exposure signals for ${digest.email}"
                }
                putEntity(
                    DossierEntity(
                        id = breachId,
                        type = EntityType.Breach,
                        label = label,
                        confidence = if (digest.breachCount > 0) 0.95f else 0.6f,
                        sourceUrls = digest.sources
                    )
                )
                link(emailId, breachId, "exposed_in", evidence = digest.note)
                link(subjectId, breachId, "has_breach_exposure", evidence = digest.note)
            }
        }

        return EntityGraph(
            entities = entities.values.toList(),
            edges = edges.toList()
        )
    }

    private fun attachFinding(
        finding: Finding,
        subjectId: String,
        profileId: String?,
        putEntity: (DossierEntity) -> Unit,
        link: (String, String, String, String?) -> Unit
    ) {
        val value = finding.value.trim()
        if (value.isBlank()) return
        val type = findingTypeToEntityType(finding.type) ?: return
        val id = entityId(type, value)
        putEntity(
            DossierEntity(
                id = id,
                type = type,
                label = value,
                confidence = finding.confidence.coerceIn(0f, 1f),
                sourceUrls = listOfNotNull(finding.sourceUrl)
            )
        )
        val relation = when (type) {
            EntityType.Email -> "has_email"
            EntityType.Phone -> "has_phone"
            EntityType.Username -> "uses_username"
            EntityType.Organization -> "affiliated_with"
            EntityType.Location -> "associated_with_location"
            EntityType.Profile -> "has_profile"
            EntityType.Website -> "linked_website"
            EntityType.Image -> "related_image"
            EntityType.Breach -> "has_breach_exposure"
            EntityType.Person -> "related_person"
        }
        link(subjectId, id, relation, finding.evidenceSnippet)
        if (profileId != null) {
            link(profileId, id, "mentions", finding.evidenceSnippet)
        }
    }

    private fun findingTypeToEntityType(type: FindingType): EntityType? = when (type) {
        FindingType.Email -> EntityType.Email
        FindingType.Phone -> EntityType.Phone
        FindingType.Address, FindingType.Location -> EntityType.Location
        FindingType.Username, FindingType.UsernameReuse -> EntityType.Username
        FindingType.Profile, FindingType.PlausibleProfileMatch -> EntityType.Profile
        FindingType.Organization -> EntityType.Organization
        FindingType.PublicSearchEvidence, FindingType.PublicImageEvidence -> EntityType.Website
        FindingType.ImageConsistency -> EntityType.Image
        FindingType.SensitiveSnippet -> null
    }

    private fun evidenceKindToEntityType(kind: EvidenceKind): EntityType? = when (kind) {
        EvidenceKind.Email -> EntityType.Email
        EvidenceKind.Phone -> EntityType.Phone
        EvidenceKind.Address -> EntityType.Location
        EvidenceKind.Location -> EntityType.Location
        EvidenceKind.Username, EvidenceKind.UsernameReuse -> EntityType.Username
        EvidenceKind.Profile, EvidenceKind.PlausibleProfileMatch -> EntityType.Profile
        EvidenceKind.Organization -> EntityType.Organization
        EvidenceKind.PublicSearchEvidence, EvidenceKind.PublicImageEvidence -> EntityType.Website
        EvidenceKind.ImageConsistency -> EntityType.Image
        EvidenceKind.SensitiveSnippet -> null
    }

    /**
     * Fuses a single [Evidence] observation into the graph. Mirrors
     * [attachFinding] but consumes Evidence natively (so scanners that emit
     * Evidence directly — the canonical path per ROADMAP — feed the graph
     * without round-tripping through the Finding adapter).
     */
    private fun attachEvidence(
        ev: Evidence,
        subjectId: String,
        putEntity: (DossierEntity) -> Unit,
        link: (String, String, String, String?) -> Unit
    ) {
        val value = ev.value.trim()
        if (value.isBlank()) return
        val type = evidenceKindToEntityType(ev.kind) ?: return
        val id = entityId(type, value)
        putEntity(
            DossierEntity(
                id = id,
                type = type,
                label = value,
                confidence = ev.confidence.coerceIn(0f, 1f),
                sourceUrls = listOfNotNull(ev.sourceUrl)
            )
        )
        val relation = when (type) {
            EntityType.Email -> "has_email"
            EntityType.Phone -> "has_phone"
            EntityType.Username -> "uses_username"
            EntityType.Organization -> "affiliated_with"
            EntityType.Location -> "associated_with_location"
            EntityType.Profile -> "has_profile"
            EntityType.Website -> "linked_website"
            EntityType.Image -> "related_image"
            EntityType.Breach -> "has_breach_exposure"
            EntityType.Person -> "related_person"
        }
        link(subjectId, id, relation, ev.snippet)
        if (ev.sourceUrl != null) {
            val profileId = entityId(EntityType.Profile, ev.sourceUrl)
            link(profileId, id, "mentions", ev.snippet)
        }
    }

    /** Stable entity id for an arbitrary observed value (relationship endpoints). */
    private fun entityIdForValue(raw: String): String {
        val key = raw.trim().lowercase(Locale.US)
        return "value:$key"
    }

    private fun entityId(type: EntityType, raw: String): String {
        val key = raw.trim().lowercase(Locale.US)
        return "${type.name.lowercase(Locale.US)}:$key"
    }
}
