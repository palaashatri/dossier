package io.dossier.app

import io.dossier.app.data.web.DiscoveryHttpPolicy
import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicUrlPolicyTest {

    @Test
    fun rejectsPrivateAndLocalIpLiteralsBeforeARequestIsBuilt() {
        val blocked = listOf(
            "http://127.0.0.1/image.jpg",
            "http://10.0.0.8/image.jpg",
            "http://172.16.4.2/image.jpg",
            "http://192.168.1.20/image.jpg",
            "http://169.254.169.254/latest/meta-data/",
            "http://[::1]/image.jpg",
            "http://[fd00::1]/image.jpg",
            "http://[::ffff:127.0.0.1]/image.jpg",
            "http://2130706433/image.jpg"
        )

        blocked.forEach { url ->
            assertFalse("private URL must be rejected: $url", DiscoveryHttpPolicy.isSafePublicHttpUrl(url))
        }
    }

    @Test
    fun rejectsCredentialsAndNonHttpUrls() {
        assertFalse(DiscoveryHttpPolicy.isSafePublicHttpUrl("https://user:pass@example.com/image.jpg"))
        assertFalse(DiscoveryHttpPolicy.isSafePublicHttpUrl("file:///private/image.jpg"))
        assertFalse(DiscoveryHttpPolicy.isSafePublicHttpUrl("not a URL"))
        assertFalse(DiscoveryHttpPolicy.isSafePublicHttpUrl("https://999.999.999.999/image.jpg"))
    }

    @Test
    fun acceptsPublicHostSyntaxAndPublicIpLiteral() {
        assertTrue(DiscoveryHttpPolicy.isSafePublicHttpUrl("https://cdn.example.com/image.jpg"))
        assertTrue(DiscoveryHttpPolicy.isSafePublicHttpUrl("https://8.8.8.8/image.jpg"))
    }

    @Test
    fun dnsValidationRejectsAnyPrivateAddressInAResolutionSet() {
        val privateAddress = InetAddress.getByName("10.0.0.8")
        val publicAddress = InetAddress.getByName("8.8.8.8")

        assertEquals(
            listOf(publicAddress),
            DiscoveryHttpPolicy.validatePublicDnsResult("cdn.example.com", listOf(publicAddress))
        )
        assertThrowsUnknownHost {
            DiscoveryHttpPolicy.validatePublicDnsResult(
                "cdn.example.com",
                listOf(publicAddress, privateAddress)
            )
        }
    }

    @Test
    fun rejectsReservedAndMappedAddresses() {
        val blocked = listOf(
            InetAddress.getByName("100.64.0.1"),
            InetAddress.getByName("192.0.0.1"),
            InetAddress.getByName("198.18.0.1"),
            InetAddress.getByName("224.0.0.1"),
            InetAddress.getByName("255.255.255.255"),
            InetAddress.getByName("fc00::1"),
            InetAddress.getByAddress(
                byteArrayOf(
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0xff.toByte(), 0xff.toByte(), 127, 0, 0, 1
                )
            )
        )

        blocked.forEach { address ->
            assertFalse("reserved address must be rejected: $address", DiscoveryHttpPolicy.isPublicAddress(address))
        }
    }

    private fun assertThrowsUnknownHost(block: () -> Unit) {
        try {
            block()
        } catch (_: UnknownHostException) {
            return
        }
        throw AssertionError("expected UnknownHostException")
    }
}
