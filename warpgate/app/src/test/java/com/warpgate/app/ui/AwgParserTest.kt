package com.warpgate.app.ui

import com.warpgate.app.ui.AwgParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the AmneziaWG / WireGuard config parser.
 *
 * The fixture mirrors exactly what the WARP generator produces, so a change in
 * the generated format is caught here as well.
 */
class AwgParserTest {

    private val generatedConfig = """
        [Interface]
        PrivateKey = aGVsbG8gd29ybGQgdGhpcyBpcyAzMiBieXRlcyEhIQ==
        Address = 172.16.0.2
        DNS = 45.155.204.190, 37.230.192.51, 83.220.169.155, 212.109.195.93
        MTU = 1280
        S1 = 0
        S2 = 0
        Jc = 4
        Jmin = 40
        Jmax = 70
        H1 = 1
        H2 = 2
        H3 = 3
        H4 = 4

        [Peer]
        PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
        AllowedIPs = 0.0.0.0/0
        Endpoint = 162.159.195.1:2408
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun parsesTheGeneratedWarpConfig() {
        val cfg = AwgParser.parse(generatedConfig)
        assertNotNull("the generated config must be importable", cfg)
        requireNotNull(cfg)

        assertEquals("aGVsbG8gd29ybGQgdGhpcyBpcyAzMiBieXRlcyEhIQ==", cfg.privateKey)
        assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", cfg.peerPub)
        assertEquals("162.159.195.1:2408", cfg.endpoint)
        assertEquals("172.16.0.2", cfg.address)
        assertEquals("45.155.204.190, 37.230.192.51, 83.220.169.155, 212.109.195.93", cfg.dns)
        assertEquals(25, cfg.keepAlive)

        // AmneziaWG obfuscation parameters.
        assertEquals(4, cfg.jc)
        assertEquals(40, cfg.jmin)
        assertEquals(70, cfg.jmax)
        assertEquals(0, cfg.s1)
        assertEquals(0, cfg.s2)
        assertEquals(1L, cfg.h1)
        assertEquals(2L, cfg.h2)
        assertEquals(3L, cfg.h3)
        assertEquals(4L, cfg.h4)
    }

    @Test
    fun keepsTheNonDefaultObfuscationValues() {
        val text = """
            [Interface]
            PrivateKey = KEY
            Jc = 7
            Jmin = 11
            Jmax = 99
            S1 = 33
            S2 = 44
            H1 = 5
            H2 = 6
            H3 = 7
            H4 = 8

            [Peer]
            PublicKey = PEER
            Endpoint = example.com:51820
        """.trimIndent()

        val cfg = AwgParser.parse(text)
        requireNotNull(cfg)
        assertEquals(7, cfg.jc)
        assertEquals(11, cfg.jmin)
        assertEquals(99, cfg.jmax)
        assertEquals(33, cfg.s1)
        assertEquals(44, cfg.s2)
        assertEquals(5L, cfg.h1)
        assertEquals(8L, cfg.h4)
        assertEquals("example.com:51820", cfg.endpoint)
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val text = """
            # a comment
            [Interface]
            PrivateKey = KEY # trailing comment

            [Peer]
            PublicKey = PEER
            Endpoint = 1.2.3.4:500
        """.trimIndent()

        val cfg = AwgParser.parse(text)
        requireNotNull(cfg)
        assertEquals("KEY", cfg.privateKey)
        assertEquals("PEER", cfg.peerPub)
        assertEquals("1.2.3.4:500", cfg.endpoint)
    }

    @Test
    fun rejectsIncompleteConfigs() {
        assertNull("a config without a private key is not usable", AwgParser.parse("[Peer]\nPublicKey = P\nEndpoint = 1.2.3.4:500"))
        assertNull("a config without a peer key is not usable", AwgParser.parse("[Interface]\nPrivateKey = K"))
        assertNull("a config without an endpoint is not usable", AwgParser.parse("[Interface]\nPrivateKey = K\n[Peer]\nPublicKey = P"))
    }

    @Test
    fun fallsBackToDefaultsForOptionalFields() {
        val cfg = AwgParser.parse(
            "[Interface]\nPrivateKey = K\n[Peer]\nPublicKey = P\nEndpoint = 1.2.3.4:500"
        )
        requireNotNull(cfg)
        assertEquals(25, cfg.keepAlive)
        assertEquals("10.8.1.2/32", cfg.address)
        assertEquals(0, cfg.jc)
        assertTrue("the preshared key stays empty", cfg.presharedKey.isNullOrBlank())
    }
}
