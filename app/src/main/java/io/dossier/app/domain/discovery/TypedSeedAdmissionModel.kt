package io.dossier.app.domain.discovery

import kotlinx.serialization.Serializable
import java.net.URI
import java.util.Locale

@Serializable
enum class TypedSeedKind {
    Email,
    Phone,
    Url,
    Document,
    Archive,
    Photo,
    Image,
    Username,
    Name
}

@Serializable
data class TypedSeed(
    val kind: TypedSeedKind,
    val value: String,
    val isVerified: Boolean = false,
    val depth: Int = 0
)

@Serializable
data class TypedSeedAdmissionConfig(
    val maxDepth: Int = 2,
    val maxTotalSeeds: Int = 30,
    val perKindBudgets: Map<TypedSeedKind, Int> = mapOf(
        TypedSeedKind.Email to 5,
        TypedSeedKind.Phone to 5,
        TypedSeedKind.Url to 10,
        TypedSeedKind.Document to 5,
        TypedSeedKind.Archive to 5,
        TypedSeedKind.Photo to 2,
        TypedSeedKind.Image to 2,
        TypedSeedKind.Username to 5,
        TypedSeedKind.Name to 0
    )
)

class TypedSeedAdmissionModel(
    private val config: TypedSeedAdmissionConfig = TypedSeedAdmissionConfig()
) {
    private val queue = mutableListOf<TypedSeed>()
    private val visited = mutableSetOf<String>()
    private val admittedByKind = mutableMapOf<TypedSeedKind, Int>()

    val pendingCount: Int get() = queue.size
    val admittedCount: Int get() = admittedByKind.values.sum()
    val isExecutionAvailable: Boolean get() = false

    fun offer(kind: TypedSeedKind, rawValue: String, depth: Int, isVerified: Boolean = false): Boolean {
        if (depth > config.maxDepth) return false
        val normalized = normalize(kind, rawValue) ?: return false
        if (!isSafe(kind, normalized, isVerified)) return false
        val key = "${kind.name}:$normalized"
        if (key in visited || queue.any { "${it.kind.name}:${it.value}" == key }) return false
        
        val kindBudget = config.perKindBudgets[kind] ?: 0
        val kindCount = admittedByKind[kind] ?: 0
        if (kindCount >= kindBudget) return false
        if (admittedCount >= config.maxTotalSeeds) return false

        queue.add(TypedSeed(kind, normalized, isVerified, depth))
        visited.add(key)
        admittedByKind[kind] = kindCount + 1
        return true
    }

    private fun normalize(kind: TypedSeedKind, value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return when (kind) {
            TypedSeedKind.Email -> if (trimmed.contains("@")) trimmed.lowercase(Locale.ROOT) else null
            TypedSeedKind.Phone -> {
                val digits = trimmed.filter { it.isDigit() || it == '+' }
                if (digits.length >= 7) digits else null
            }
            TypedSeedKind.Url, TypedSeedKind.Document, TypedSeedKind.Archive -> runCatching {
                val uri = URI(trimmed)
                if (uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && uri.host != null) {
                    trimmed
                } else null
            }.getOrNull()
            TypedSeedKind.Photo, TypedSeedKind.Image -> trimmed
            TypedSeedKind.Username -> trimmed.lowercase(Locale.ROOT)
            TypedSeedKind.Name -> trimmed
        }
    }

    private fun isSafe(kind: TypedSeedKind, value: String, isVerified: Boolean): Boolean {
        // Explicit candidate/import safety
        if (!isVerified && kind in setOf(TypedSeedKind.Photo, TypedSeedKind.Image, TypedSeedKind.Document, TypedSeedKind.Archive)) {
            // Depending on strictness, we might reject unverified media
        }
        return true
    }
    
    fun pop(): TypedSeed? {
        if (queue.isEmpty()) return null
        return queue.removeAt(0)
    }
}
