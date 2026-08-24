package io.dossier.app.domain.graph

import io.dossier.app.domain.evidence.Evidence
import io.dossier.app.domain.evidence.EvidenceKind
import io.dossier.app.domain.evidence.EvidenceReliability
import io.dossier.app.domain.evidence.EvidenceRelationship
import io.dossier.app.domain.evidence.EvidenceState
import io.dossier.app.domain.evidence.HistoricalAttributeKind
import io.dossier.app.domain.evidence.EvidenceCollection
import io.dossier.app.domain.evidence.EvidenceIdPolicy
import io.dossier.app.domain.evidence.withResolvedRelationshipEvidence
import io.dossier.app.domain.identity.EntityResolverV2
import io.dossier.app.domain.identity.ResolutionBand
import io.dossier.app.domain.model.BreachDigest
import io.dossier.app.domain.model.DossierEdge
import io.dossier.app.domain.model.DossierEntity
import io.dossier.app.domain.model.EntityGraph
import io.dossier.app.domain.model.EntityType
import io.dossier.app.domain.model.FaceConsistencyMatch
import io.dossier.app.domain.model.Finding
import io.dossier.app.domain.model.FindingType
import io.dossier.app.domain.model.GraphNodeState
import io.dossier.app.domain.model.IdentityInput
import io.dossier.app.domain.model.ProfileScanResult
import java.util.Locale

/** Fuses identity input, verified observations, evidence, images and breach summaries into one graph. */
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
                    label = if (entity.label.length > existing.label.length) entity.label else existing.label,
                    evidenceIds = (existing.evidenceIds + entity.evidenceIds).distinct(),
                    historical = existing.historical || entity.historical,
                    firstObservedAtEpochMillis = minNullable(
                        existing.firstObservedAtEpochMillis,
                        entity.firstObservedAtEpochMillis
                    ),
                    lastObservedAtEpochMillis = maxNullable(
                        existing.lastObservedAtEpochMillis,
                        entity.lastObservedAtEpochMillis
                    ),
                    state = strongerState(existing.state, entity.state)
                )
            }
        }

        fun addLink(
            fromId: String,
            toId: String,
            relation: String,
            evidenceText: String? = null,
            evidenceIds: List<String> = emptyList()
        ) {
            if (fromId == toId) return
            val duplicateIndex = edges.indexOfFirst {
                it.fromId == fromId && it.toId == toId && it.relation == relation
            }
            if (duplicateIndex >= 0) {
                val existing = edges[duplicateIndex]
                edges[duplicateIndex] = existing.copy(
                    evidence = existing.evidence ?: evidenceText,
                    evidenceIds = (existing.evidenceIds + evidenceIds)
                        .map(EvidenceIdPolicy::migrate)
                        .distinct()
                )
                return
            }
            edges.add(
                DossierEdge(
                    fromId = fromId,
                    toId = toId,
                    relation = relation,
                    evidence = evidenceText,
                    evidenceIds = evidenceIds.map(EvidenceIdPolicy::migrate).distinct()
                )
            )
        }

        fun link(fromId: String, toId: String, relation: String, evidenceText: String? = null) {
            addLink(fromId, toId, relation, evidenceText)
        }

        fun linkWithEvidence(
            fromId: String,
            toId: String,
            relation: String,
            evidenceText: String? = null,
            evidenceIds: List<String> = emptyList()
        ) {
            addLink(fromId, toId, relation, evidenceText, evidenceIds)
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
                confidence = 1.0f,
                state = GraphNodeState.Confirmed
            )
        )

        input.emails.filter { it.isNotBlank() }.forEach { email ->
            val id = entityId(EntityType.Email, email)
            putEntity(
                DossierEntity(
                    id = id,
                    type = EntityType.Email,
                    label = email.trim(),
                    confidence = 1.0f,
                    state = GraphNodeState.Confirmed
                )
            )
            link(subjectId, id, "has_email", "user-supplied")
        }
        input.phones.filter { it.isNotBlank() }.forEach { phone ->
            val id = entityId(EntityType.Phone, phone)
            putEntity(
                DossierEntity(
                    id = id,
                    type = EntityType.Phone,
                    label = phone.trim(),
                    confidence = 1.0f,
                    state = GraphNodeState.Confirmed
                )
            )
            link(subjectId, id, "has_phone", "user-supplied")
        }
        val usernameSeeds = buildList {
            input.primaryUsername?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(input.usernames.filter { it.isNotBlank() })
        }.distinctBy { it.lowercase(Locale.US) }
        usernameSeeds.forEach { username ->
            val id = entityId(EntityType.Username, username)
            putEntity(
                DossierEntity(
                    id = id,
                    type = EntityType.Username,
                    label = username.trim(),
                    confidence = 1.0f,
                    state = GraphNodeState.Confirmed
                )
            )
            link(subjectId, id, "uses_username", "user-supplied")
        }
        input.organizations.filter { it.isNotBlank() }.forEach { org ->
            val id = entityId(EntityType.Organization, org)
            putEntity(DossierEntity(id, EntityType.Organization, org.trim(), 0.9f, state = GraphNodeState.Confirmed))
            link(subjectId, id, "affiliated_with", "user-supplied")
        }
        input.locations.filter { it.isNotBlank() }.forEach { loc ->
            val id = entityId(EntityType.Location, loc)
            putEntity(DossierEntity(id, EntityType.Location, loc.trim(), 0.9f, state = GraphNodeState.Confirmed))
            link(subjectId, id, "associated_with_location", "user-supplied")
        }
        input.profileUrls.filter { it.isNotBlank() }.forEach { url ->
            val id = entityId(EntityType.Profile, url)
            putEntity(
                DossierEntity(
                    id = id,
                    type = EntityType.Profile,
                    label = url.trim(),
                    confidence = 1.0f,
                    sourceUrls = listOf(url.trim()),
                    state = GraphNodeState.Confirmed
                )
            )
            link(subjectId, id, "owns_profile", "user-supplied")
        }

        profileResults.forEach { result ->
            val url = result.candidate.url
            val profileId = entityId(EntityType.Profile, url)
            val resolution = EntityResolverV2.resolve(input, result)
            val resolverConfidence = resolution.score.toFloat().coerceIn(0f, 1f)
            val conf = maxOf(result.candidate.confidence.coerceIn(0f, 1f), resolverConfidence)
            putEntity(
                DossierEntity(
                    id = profileId,
                    type = EntityType.Profile,
                    label = result.displayName?.takeIf { it.isNotBlank() }
                        ?: "${result.candidate.platform.name}: ${result.candidate.username}",
                    confidence = conf,
                    sourceUrls = listOf(url),
                    state = resolution.band.toGraphState()
                )
            )
            val relation = when (resolution.band) {
                ResolutionBand.Confirmed -> "owns_profile"
                ResolutionBand.High,
                ResolutionBand.Medium -> "has_profile"
                ResolutionBand.Low -> "possible_profile"
                ResolutionBand.Conflicting -> "candidate_profile"
                ResolutionBand.Unresolved -> if (result.exists) "possible_profile" else "candidate_profile"
            }
            link(
                subjectId,
                profileId,
                relation,
                resolution.explanation.takeIf { it.isNotBlank() }
                    ?: result.verificationStatus
                    ?: result.provenance
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
                        sourceUrls = listOf(url),
                        state = resolution.band.toGraphState()
                    )
                )
                if (result.exists) link(subjectId, usernameId, "uses_username", url)
                link(profileId, usernameId, "username_on_profile")
            }
            result.findings.forEach { finding ->
                attachFinding(finding, subjectId, profileId, ::putEntity, ::link)
            }
        }

        findings.forEach { finding ->
            val profileId = finding.sourceUrl?.takeIf { it.isNotBlank() }?.let { entityId(EntityType.Profile, it) }
            if (profileId != null && profileId !in entities) {
                putEntity(
                    DossierEntity(
                        id = profileId,
                        type = EntityType.Profile,
                        label = finding.sourceUrl!!,
                        confidence = finding.confidence,
                        sourceUrls = listOf(finding.sourceUrl),
                        state = GraphNodeState.Unresolved
                    )
                )
                link(subjectId, profileId, "related_profile", finding.type.name)
            }
            attachFinding(finding, subjectId, profileId, ::putEntity, ::link)
        }

        evidence.forEach { ev ->
            attachEvidence(ev, subjectId, ::putEntity, ::link)
        }

        val resolvedRelationships = EvidenceCollection(
            evidence = evidence,
            relationships = relationships
        ).withResolvedRelationshipEvidence().relationships
        resolvedRelationships.forEach { rel ->
            // Reuse one exact canonical entity when the relationship endpoint
            // already exists. This keeps evidence/plugin relationships in the
            // same graph source of truth as profile/finding/input nodes while
            // refusing ambiguous or fuzzy merges.
            val fromId = resolveExistingEntityId(rel.fromValue, entities)
                ?: entityIdForValue(rel.fromValue)
            val toId = resolveExistingEntityId(rel.toValue, entities)
                ?: entityIdForValue(rel.toValue)
            if (fromId !in entities) {
                putEntity(DossierEntity(fromId, EntityType.Website, rel.fromValue, 0.7f))
            }
            if (toId !in entities) {
                putEntity(DossierEntity(toId, EntityType.Website, rel.toValue, 0.7f))
            }
            linkWithEvidence(fromId, toId, rel.relation, rel.evidence, rel.evidenceIds)
        }

        faceMatches.forEach { match ->
            val profileId = entityId(EntityType.Profile, match.profileUrl)
            if (profileId !in entities) {
                putEntity(
                    DossierEntity(
                        id = profileId,
                        type = EntityType.Profile,
                        label = match.profileUrl,
                        confidence = match.similarityScore.coerceIn(0f, 1f),
                        sourceUrls = listOf(match.profileUrl),
                        state = GraphNodeState.Unresolved
                    )
                )
                link(subjectId, profileId, "possible_profile", match.warning)
            }
            val imageId = entityId(EntityType.Image, "face:${match.profileUrl}")
            putEntity(
                DossierEntity(
                    id = imageId,
                    type = EntityType.Image,
                    label = "Face similarity score ${"%.3f".format(Locale.US, match.similarityScore)}",
                    confidence = match.similarityScore.coerceIn(0f, 1f),
                    sourceUrls = listOf(match.profileUrl),
                    state = GraphNodeState.Unresolved
                )
            )
            link(subjectId, imageId, "face_similar_to", match.warning)
            link(imageId, profileId, "image_of_profile", match.warning)
        }

        breachDigests.forEach { digest ->
            val emailId = entityId(EntityType.Email, digest.email)
            if (emailId !in entities) {
                putEntity(DossierEntity(emailId, EntityType.Email, digest.email, 0.9f))
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
                        sourceUrls = digest.sources,
                        state = if (digest.breachCount > 0) GraphNodeState.High else GraphNodeState.Unresolved
                    )
                )
                link(emailId, breachId, "exposed_in", digest.note)
                link(subjectId, breachId, "has_breach_exposure", digest.note)
            }
        }

        return enrichEdgeProvenance(
            EntityGraph(entities.values.toList(), edges.toList()),
            evidence
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
        val relation = relationFor(type)
        link(subjectId, id, relation, finding.evidenceSnippet)
        if (profileId != null) link(profileId, id, "mentions", finding.evidenceSnippet)
    }

    private fun evidenceKindToEntityType(kind: EvidenceKind): EntityType? = when (kind) {
        EvidenceKind.Email -> EntityType.Email
        EvidenceKind.Phone -> EntityType.Phone
        EvidenceKind.Address, EvidenceKind.Location -> EntityType.Location
        EvidenceKind.Username, EvidenceKind.UsernameReuse -> EntityType.Username
        EvidenceKind.Profile, EvidenceKind.PlausibleProfileMatch -> EntityType.Profile
        EvidenceKind.Organization -> EntityType.Organization
        EvidenceKind.PublicSearchEvidence, EvidenceKind.PublicImageEvidence -> EntityType.Website
        EvidenceKind.ImageConsistency -> EntityType.Image
        EvidenceKind.SensitiveSnippet -> null
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

    private fun attachEvidence(
        ev: Evidence,
        subjectId: String,
        putEntity: (DossierEntity) -> Unit,
        link: (String, String, String, String?) -> Unit
    ) {
        val value = ev.value.trim()
        if (value.isBlank()) return
        val timestamp = ev.observedAtEpochMillis ?: ev.retrievedAtEpochMillis
        val historicalArchive = ev.historical && ev.reliability == EvidenceReliability.ArchiveSnapshot
        val sourceId = if (historicalArchive && ev.attributeKind != null) {
            ev.sourceUrl?.trim()?.takeIf(String::isNotBlank)?.let { sourceUrl ->
                val id = entityId(EntityType.Website, sourceUrl)
                // Attribute records are observations about the archived page, not
                // standalone current profiles. Keep the archive source node
                // historical and attach the attribute evidence to it.
                putEntity(
                    DossierEntity(
                        id = id,
                        type = EntityType.Website,
                        label = sourceUrl,
                        confidence = ev.confidence.coerceIn(0f, 1f),
                        sourceUrls = listOf(sourceUrl),
                        state = ev.state.toGraphState(),
                        evidenceIds = listOf(ev.id),
                        historical = historicalArchive,
                        firstObservedAtEpochMillis = timestamp,
                        lastObservedAtEpochMillis = timestamp
                    )
                )
                id
            }
        } else {
            null
        }

        // Display names and bios are textual claims. Retain them as evidence
        // on the archived source node but never turn the text into a Profile
        // entity or a direct subject-ownership edge.
        if (ev.attributeKind == HistoricalAttributeKind.DisplayName ||
            ev.attributeKind == HistoricalAttributeKind.Bio
        ) {
            sourceId?.let { archiveId ->
                link(subjectId, archiveId, if (historicalArchive) "archived_as" else "mentions", ev.snippet)
            }
            return
        }

        val type = when (ev.attributeKind) {
            HistoricalAttributeKind.ExternalLink -> EntityType.Website
            HistoricalAttributeKind.AvatarUrl -> EntityType.Image
            HistoricalAttributeKind.Username -> EntityType.Username
            HistoricalAttributeKind.Organization -> EntityType.Organization
            HistoricalAttributeKind.Location -> EntityType.Location
            null -> evidenceKindToEntityType(ev.kind)
            else -> evidenceKindToEntityType(ev.kind)
        } ?: return

        val id = entityId(type, value)
        putEntity(
            DossierEntity(
                id = id,
                type = type,
                label = value,
                confidence = ev.confidence.coerceIn(0f, 1f),
                sourceUrls = listOfNotNull(ev.sourceUrl),
                state = ev.state.toGraphState(),
                evidenceIds = listOf(ev.id),
                historical = ev.historical,
                firstObservedAtEpochMillis = timestamp,
                lastObservedAtEpochMillis = timestamp
            )
        )
        if (sourceId != null && historicalArchive) {
            val relation = when (ev.attributeKind) {
                HistoricalAttributeKind.ExternalLink -> "links_to"
                HistoricalAttributeKind.AvatarUrl -> "uses_avatar"
                HistoricalAttributeKind.Username -> "claims_identity"
                HistoricalAttributeKind.Organization,
                HistoricalAttributeKind.Location -> "mentions"
                else -> "mentions"
            }
            link(sourceId, id, relation, ev.snippet)
        } else {
            val subjectRelation = if (historicalArchive && ev.attributeKind == null) {
                "archived_as"
            } else {
                relationFor(type)
            }
            link(subjectId, id, subjectRelation, ev.snippet)
            if (!historicalArchive) {
                ev.sourceUrl?.let { sourceUrl ->
                    val profileId = entityId(EntityType.Profile, sourceUrl)
                    link(profileId, id, "mentions", ev.snippet)
                }
            }
        }
    }

    private fun enrichEdgeProvenance(graph: EntityGraph, evidence: List<Evidence>): EntityGraph {
        if (graph.edges.isEmpty()) return graph
        val byId = graph.entities.associateBy(DossierEntity::id)
        val evidenceById = evidence.associateBy { EvidenceIdPolicy.migrate(it.id) }
        val evidenceByValue = evidence.groupBy { it.value.trim().lowercase(Locale.US) }
        val evidenceBySource = evidence
            .filter { !it.sourceUrl.isNullOrBlank() }
            .groupBy { it.sourceUrl!!.trim().lowercase(Locale.US) }

        val enrichedEdges = graph.edges.map { edge ->
            val from = byId[edge.fromId]
            val to = byId[edge.toId]
            val relevant = buildList {
                addAll(edge.evidenceIds.mapNotNull { evidenceById[EvidenceIdPolicy.migrate(it)] })
                if (to != null) addAll(evidenceByValue[to.label.trim().lowercase(Locale.US)].orEmpty())
                to?.sourceUrls.orEmpty().forEach { url ->
                    addAll(evidenceBySource[url.trim().lowercase(Locale.US)].orEmpty())
                }
                if (from != null && from.type != EntityType.Person) {
                    addAll(evidenceByValue[from.label.trim().lowercase(Locale.US)].orEmpty())
                }
            }.distinctBy(Evidence::id)
            val contradictions = relevant.filter { it.state == EvidenceState.Conflicting }
            edge.copy(
                evidenceIds = (edge.evidenceIds + relevant.map(Evidence::id))
                    .map(EvidenceIdPolicy::migrate)
                    .distinct(),
                contradictingEvidenceIds = (
                    edge.contradictingEvidenceIds + contradictions.map(Evidence::id)
                ).map(EvidenceIdPolicy::migrate).distinct(),
                confidence = edge.confidence ?: to?.confidence,
                historical = edge.historical || relevant.any(Evidence::historical) || (to?.historical == true)
            )
        }
        return graph.copy(edges = enrichedEdges)
    }

    private fun relationFor(type: EntityType): String = when (type) {
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

    private fun ResolutionBand.toGraphState(): GraphNodeState = when (this) {
        ResolutionBand.Confirmed -> GraphNodeState.Confirmed
        ResolutionBand.High -> GraphNodeState.High
        ResolutionBand.Medium -> GraphNodeState.Medium
        ResolutionBand.Low -> GraphNodeState.Low
        ResolutionBand.Unresolved -> GraphNodeState.Unresolved
        ResolutionBand.Conflicting -> GraphNodeState.Conflicting
    }

    private fun EvidenceState.toGraphState(): GraphNodeState = when (this) {
        EvidenceState.Verified -> GraphNodeState.Confirmed
        EvidenceState.Probable -> GraphNodeState.High
        EvidenceState.Candidate -> GraphNodeState.Medium
        EvidenceState.Observed -> GraphNodeState.Unresolved
        EvidenceState.Conflicting -> GraphNodeState.Conflicting
        EvidenceState.Rejected -> GraphNodeState.Low
        EvidenceState.Unavailable -> GraphNodeState.Unresolved
    }

    private fun strongerState(a: GraphNodeState, b: GraphNodeState): GraphNodeState {
        if (a == GraphNodeState.Conflicting || b == GraphNodeState.Conflicting) return GraphNodeState.Conflicting
        val order = listOf(
            GraphNodeState.Unresolved,
            GraphNodeState.Low,
            GraphNodeState.Medium,
            GraphNodeState.High,
            GraphNodeState.Confirmed
        )
        return if (order.indexOf(a) >= order.indexOf(b)) a else b
    }

    private fun minNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    private fun maxNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private fun entityIdForValue(raw: String): String = "value:${raw.trim().lowercase(Locale.US)}"

    /**
     * Resolves only an exact, unique label/source URL match. Multiple entities
     * with the same public value remain unresolved rather than being merged by
     * a relationship payload that lacks a typed endpoint.
     */
    private fun resolveExistingEntityId(
        raw: String,
        entities: Map<String, DossierEntity>
    ): String? {
        val key = raw.trim().lowercase(Locale.US)
        if (key.isBlank()) return null
        val labelMatches = entities.values
            .asSequence()
            .filter { entity -> entity.label.trim().lowercase(Locale.US) == key }
            .map(DossierEntity::id)
            .distinct()
            .toList()
        if (labelMatches.size == 1) return labelMatches.single()
        if (labelMatches.isNotEmpty()) return null

        val sourceMatches = entities.values
            .asSequence()
            .filter { entity -> entity.sourceUrls.any { it.trim().lowercase(Locale.US) == key } }
            .toList()
        val typedUrlMatches = sourceMatches.filter {
            it.type == EntityType.Profile || it.type == EntityType.Website
        }.map(DossierEntity::id).distinct()
        return when {
            typedUrlMatches.size == 1 -> typedUrlMatches.single()
            sourceMatches.map(DossierEntity::id).distinct().size == 1 -> sourceMatches.single().id
            else -> null
        }
    }

    private fun entityId(type: EntityType, raw: String): String =
        "${type.name.lowercase(Locale.US)}:${raw.trim().lowercase(Locale.US)}"
}
