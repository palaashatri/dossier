package io.dossier.app.domain.image

/**
 * Deterministic whole-image duplicate clustering.
 *
 * This operates on exact content hashes and whole-image perceptual hashes only;
 * it does not use face embeddings or infer that two different photos depict the
 * same person. Perceptual clusters therefore mean near-duplicate/repost content.
 */
object ImageDuplicateClusterer {
    data class Candidate(
        val id: String,
        val sha256: String,
        val perceptualHash: Long,
        val querySimilarity: Float
    )

    enum class ClusterType {
        ExactContent,
        PerceptualNearDuplicate
    }

    data class Cluster(
        val id: String,
        val type: ClusterType,
        val representativeCandidateId: String,
        val memberCandidateIds: List<String>
    )

    fun cluster(
        candidates: List<Candidate>,
        perceptualThreshold: Float = DEFAULT_PERCEPTUAL_THRESHOLD
    ): List<Cluster> {
        if (candidates.size < 2) return emptyList()
        require(perceptualThreshold in 0f..1f)

        val parent = IntArray(candidates.size) { it }

        fun find(index: Int): Int {
            var node = index
            while (parent[node] != node) {
                parent[node] = parent[parent[node]]
                node = parent[node]
            }
            return node
        }

        fun union(left: Int, right: Int) {
            val leftRoot = find(left)
            val rightRoot = find(right)
            if (leftRoot != rightRoot) parent[rightRoot] = leftRoot
        }

        for (left in candidates.indices) {
            for (right in left + 1 until candidates.size) {
                val first = candidates[left]
                val second = candidates[right]
                val exact = first.sha256.isNotBlank() && first.sha256 == second.sha256
                val perceptuallyNear = hashSimilarity(first.perceptualHash, second.perceptualHash) >=
                    perceptualThreshold
                if (exact || perceptuallyNear) union(left, right)
            }
        }

        return candidates.indices
            .groupBy(::find)
            .values
            .map { indexes -> indexes.map(candidates::get) }
            .filter { it.size >= 2 }
            .map { members ->
                val sorted = members.sortedBy(Candidate::id)
                val exact = sorted.map(Candidate::sha256).filter(String::isNotBlank).distinct().size == 1 &&
                    sorted.all { it.sha256.isNotBlank() }
                val representative = sorted.maxWithOrNull(
                    compareBy<Candidate> { it.querySimilarity }.thenByDescending { it.id }
                ) ?: sorted.first()
                Cluster(
                    id = stableClusterId(sorted.map(Candidate::id)),
                    type = if (exact) ClusterType.ExactContent else ClusterType.PerceptualNearDuplicate,
                    representativeCandidateId = representative.id,
                    memberCandidateIds = sorted.map(Candidate::id)
                )
            }
            .sortedBy(Cluster::id)
    }

    internal fun hashSimilarity(first: Long, second: Long): Float =
        1f - java.lang.Long.bitCount(first xor second) / 64f

    private fun stableClusterId(memberIds: List<String>): String {
        val value = memberIds.sorted().joinToString("|")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "imgcluster:${digest.take(20)}"
    }

    private const val DEFAULT_PERCEPTUAL_THRESHOLD = 0.90f
}
