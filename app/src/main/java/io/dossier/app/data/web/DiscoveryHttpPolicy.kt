package io.dossier.app.data.web

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/** Shared retry, block-page detection, and circuit-breaker policy for public discovery. */
internal object DiscoveryHttpPolicy {
    private const val DEFAULT_BASE_DELAY_MS = 600L
    private const val MAX_DELAY_MS = 6_000L

    /**
     * DNS policy used by clients that download provider-controlled URLs. A
     * hostname is rejected when any answer points at a local/private address;
     * this also protects every host reached through an HTTP redirect.
     */
    internal val PUBLIC_DNS: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            validatePublicDnsResult(hostname, Dns.SYSTEM.lookup(hostname))
    }

    /**
     * Validates URL syntax and literal addresses before an HTTP request is
     * created. DNS names are resolved by [PUBLIC_DNS] at connection time.
     */
    fun isSafePublicHttpUrl(raw: String): Boolean {
        val url = raw.trim().toHttpUrlOrNull() ?: return false
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false

        val host = url.host
        val normalizedHost = host.trimEnd('.').lowercase()
        if (normalizedHost == "localhost" || normalizedHost.endsWith(".localhost") ||
            normalizedHost == "local" || normalizedHost.endsWith(".local") ||
            (!normalizedHost.contains('.') && !normalizedHost.contains(':'))
        ) {
            return false
        }
        if (host.contains(':')) {
            val literal = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            return isPublicAddress(literal)
        }
        if (isNumericIpv4Host(host)) {
            val literal = parseCanonicalIpv4(host) ?: return false
            return isPublicAddress(literal)
        }
        return true
    }

    /** Intercepts each network request, including redirect follow-ups, before bytes are sent. */
    internal val PUBLIC_URL_INTERCEPTOR: Interceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        if (!isSafePublicHttpUrl(url)) {
            throw IOException("Refusing non-public image URL")
        }
        // Resolve the origin even when OkHttp is configured with a proxy. In
        // that mode OkHttp's Dns hook resolves only the proxy host, so this
        // explicit check prevents a proxy from turning a public URL into an
        // internal-network request.
        validatePublicDnsResult(request.url.host, Dns.SYSTEM.lookup(request.url.host))
        chain.proceed(request)
    }

    /** Testable DNS result gate shared by [PUBLIC_DNS] and callers' tests. */
    internal fun validatePublicDnsResult(
        hostname: String,
        addresses: List<InetAddress>
    ): List<InetAddress> {
        if (hostname.isBlank() || addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
            throw UnknownHostException("Refusing non-public host: $hostname")
        }
        return addresses
    }

    /** Returns false for loopback, private, link-local, multicast and reserved IPs. */
    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }

        val bytes = address.address
        if (bytes.size == IPV4_BYTE_COUNT) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            val third = bytes[2].toInt() and 0xff
            return when {
                first == 0 || first == 10 || first == 127 -> false
                first == 100 && second in 64..127 -> false // RFC 6598 shared space
                first == 169 && second == 254 -> false
                first == 172 && second in 16..31 -> false
                first == 192 && second == 0 && third == 0 -> false
                first == 192 && second == 0 && third == 2 -> false
                first == 192 && second == 168 -> false
                first == 198 && second in 18..19 -> false
                first == 198 && second == 51 && third == 100 -> false
                first == 203 && second == 0 && third == 113 -> false
                first >= 224 -> false
                else -> true
            }
        }

        if (bytes.size == IPV6_BYTE_COUNT) {
            // IPv4-mapped and IPv4-compatible IPv6 addresses can otherwise
            // bypass the IPv4 private-range checks above.
            val mapped = (bytes.take(10).all { it == 0.toByte() } &&
                bytes[10].toInt() and 0xff == 0xff &&
                bytes[11].toInt() and 0xff == 0xff) ||
                bytes.take(12).all { it == 0.toByte() }
            if (mapped) {
                val ipv4 = InetAddress.getByAddress(bytes.copyOfRange(12, 16))
                return isPublicAddress(ipv4)
            }

            // NAT64 (64:ff9b::/96) and 6to4 (2002::/16) carry an IPv4
            // address in the IPv6 payload. Apply the IPv4 policy to it too.
            if (isNat64(bytes)) {
                val ipv4 = InetAddress.getByAddress(bytes.copyOfRange(12, 16))
                return isPublicAddress(ipv4)
            }
            if (bytes[0].toInt() and 0xff == 0x20 &&
                bytes[1].toInt() and 0xff == 0x02
            ) {
                val ipv4 = InetAddress.getByAddress(bytes.copyOfRange(2, 6))
                return isPublicAddress(ipv4)
            }

            // Unique-local IPv6 (fc00::/7) is private even though the JDK's
            // isSiteLocalAddress does not classify it as such.
            val first = bytes[0].toInt() and 0xff
            if (first and 0xfe == 0xfc) return false
            if (first and 0xe0 != 0x20) return false
            return !isReservedIpv6(bytes)
        }

        return false
    }

    /** Reject ambiguous IPv4 spellings instead of letting platform DNS parse them differently. */
    private fun isNumericIpv4Host(host: String): Boolean {
        if (host.all { it.isDigit() || it == '.' }) return true
        val parts = host.split('.')
        return parts.any { it.startsWith("0x", ignoreCase = true) } &&
            parts.all { it.startsWith("0x", ignoreCase = true) || it.all(Char::isDigit) }
    }

    private fun parseCanonicalIpv4(host: String): InetAddress? {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.length > 1 && it.startsWith('0') }) return null
        val octets = parts.map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } ?: return null }
        return runCatching { InetAddress.getByAddress(octets.map(Int::toByte).toByteArray()) }.getOrNull()
    }

    private fun isNat64(bytes: ByteArray): Boolean =
        bytes[0].toInt() and 0xff == 0 &&
            bytes[1].toInt() and 0xff == 0x64 &&
            bytes[2].toInt() and 0xff == 0xff &&
            bytes[3].toInt() and 0xff == 0x9b &&
            bytes.slice(4..11).all { it == 0.toByte() }

    private fun isReservedIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        val fourth = bytes[3].toInt() and 0xff
        return (first == 0x20 && second == 0x01 && third == 0x00 && fourth == 0x00) ||
            (first == 0x20 && second == 0x01 && third == 0x00 && fourth == 0x02 &&
                bytes[4] == 0.toByte() && bytes[5] == 0.toByte()) ||
            (first == 0x20 && second == 0x01 && third == 0x00 && fourth and 0xf0 == 0x10) ||
            (first == 0x20 && second == 0x01 && third == 0x00 && fourth and 0xf0 == 0x20) ||
            (first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8) ||
            (first == 0x3f && second and 0xf0 == 0xf0) ||
            (first == 0 && second == 1 && bytes.slice(2..7).all { it == 0.toByte() })
    }

    fun isTransientHttpStatus(code: Int): Boolean =
        code == 408 || code == 425 || code == 429 || code in 500..599

    fun retryDelayMillis(
        attempt: Int,
        retryAfterHeader: String?,
        baseDelayMs: Long = DEFAULT_BASE_DELAY_MS
    ): Long {
        val retryAfterMs = retryAfterHeader
            ?.trim()
            ?.toLongOrNull()
            ?.coerceIn(0L, 60L)
            ?.times(1_000L)
        if (retryAfterMs != null) return retryAfterMs

        val exponent = attempt.coerceIn(0, 4)
        val exponential = baseDelayMs * (1L shl exponent)
        // Deterministic jitter keeps tests stable while preventing synchronized retries.
        val jitter = ((attempt + 1) * 137L) % 311L
        return min(MAX_DELAY_MS, exponential + jitter)
    }

    fun looksBlocked(html: String): Boolean {
        if (html.isBlank()) return false
        val lower = html.lowercase()
        val markers = listOf(
            "verify you are human",
            "unusual traffic",
            "automated queries",
            "checking your browser",
            "just a moment",
            "cf-challenge",
            "captcha",
            "access denied",
            "enable javascript and cookies",
            "our systems have detected unusual traffic"
        )
        return markers.any(lower::contains)
    }

    private const val IPV4_BYTE_COUNT = 4
    private const val IPV6_BYTE_COUNT = 16
}

/**
 * Small in-memory circuit breaker. One broken provider cannot consume every query budget.
 * State is process-local by design; a fresh app process gets a clean retry opportunity.
 */
internal class ProviderCircuitBreaker(
    private val failureThreshold: Int = 3,
    private val cooldownMillis: Long = 120_000L,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private data class State(var failures: Int = 0, var openUntil: Long = 0L)
    private val states = ConcurrentHashMap<String, State>()

    fun canAttempt(provider: String): Boolean {
        val state = states[provider] ?: return true
        val now = nowMillis()
        if (state.openUntil == 0L || now >= state.openUntil) {
            if (state.openUntil != 0L) states.remove(provider, state)
            return true
        }
        return false
    }

    fun recordSuccess(provider: String) {
        states.remove(provider)
    }

    fun recordFailure(provider: String) {
        states.compute(provider) { _, old ->
            val state = old ?: State()
            state.failures += 1
            if (state.failures >= failureThreshold) {
                state.openUntil = nowMillis() + cooldownMillis
            }
            state
        }
    }
}
