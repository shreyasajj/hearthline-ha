package dev.pixelhome

import org.junit.Assert.*
import org.junit.Test

class SendspinConfigTest {
    private fun rejects(config: SendspinConfig) { assertThrows(IllegalArgumentException::class.java) { config.validated() } }
    @Test fun discoveryNeedsNoServerOrCredentials() { assertEquals("",SendspinConfig().validated().server) }
    @Test fun trimsOperatorInputAndAcceptsTlsAndIpv6() {
        assertEquals("Bedroom",SendspinConfig(" Bedroom "," wss://ma.example/sendspin ").validated().name)
        assertEquals("ws://[fd00::10]:8927/sendspin",SendspinConfig(server="ws://[fd00::10]:8927/sendspin").validated().server)
    }
    @Test fun endpointsNeverContainTokensOrAlternatePaths() {
        for(url in listOf("https://ma.example/sendspin","ws://user:secret@ma.example:8927/sendspin","ws://ma.example:8927/sendspin?token=secret","ws://ma.example:8927/sendspin#secret","ws://ma.example:8927/admin","ws://ma.example:0/sendspin","ws://ma.example:65536/sendspin")) rejects(SendspinConfig(server=url))
    }
    @Test fun nameBoundIsUtf8BytesAndRejectsControls() {
        rejects(SendspinConfig(name=" ")); rejects(SendspinConfig(name="Bed\nroom")); rejects(SendspinConfig(name="é".repeat(33)))
        assertEquals(64,SendspinConfig(name="é".repeat(32)).validated().name.toByteArray().size)
    }
    @Test fun listenerPortAndUrlSizeAreBounded() {
        rejects(SendspinConfig(port=1023)); rejects(SendspinConfig(port=65536)); rejects(SendspinConfig(server="ws://"+"a".repeat(520)+"/sendspin"))
        assertEquals(65535,SendspinConfig(port=65535).validated().port)
    }
}
