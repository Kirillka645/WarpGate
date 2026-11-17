package com.warpgate.app.sub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Tests for the proxy share-link parser (Happ-style subscriptions).
 */
class LinkParserTest {

    @Test
    fun parsesVlessLink() {
        val cfg = LinkParser.parseLine(
            "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
                "?type=ws&security=tls&sni=cdn.example.com&path=%2Fws&flow=xtls-rprx-vision#My%20Node"
        )
        requireNotNull(cfg)
        assertEquals("vless", cfg.protocol)
        assertEquals("example.com", cfg.host)
        assertEquals(443, cfg.port)
        assertEquals("11111111-2222-3333-4444-555555555555", cfg.uuid)
        assertEquals("ws", cfg.network)
        assertEquals("tls", cfg.security)
        assertEquals("cdn.example.com", cfg.sni)
        assertEquals("/ws", cfg.path)
        assertEquals("xtls-rprx-vision", cfg.flow)
        assertEquals("My Node", cfg.name)
    }

    @Test
    fun parsesVmessLink() {
        val json = """
            {"v":"2","ps":"Berlin","add":"de.example.com","port":"8443","id":"abc-123","scy":"auto","net":"ws","tls":"tls","sni":"sni.example.com","path":"/path"}
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray())

        val cfg = LinkParser.parseLine("vmess://$encoded")
        requireNotNull(cfg)
        assertEquals("vmess", cfg.protocol)
        assertEquals("Berlin", cfg.name)
        assertEquals("de.example.com", cfg.host)
        assertEquals(8443, cfg.port)
        assertEquals("abc-123", cfg.uuid)
        assertEquals("auto", cfg.method)
        assertEquals("ws", cfg.network)
        assertEquals("tls", cfg.security)
        assertEquals("sni.example.com", cfg.sni)
        assertEquals("/path", cfg.path)
    }

    @Test
    fun parsesTrojanLink() {
        val cfg = LinkParser.parseLine(
            "trojan://password@tr.example.com:8443?sni=tr.example.com&type=tcp#Trojan"
        )
        requireNotNull(cfg)
        assertEquals("trojan", cfg.protocol)
        assertEquals("tr.example.com", cfg.host)
        assertEquals(8443, cfg.port)
        assertEquals("password", cfg.uuid)
        assertEquals("tls", cfg.security)
        assertEquals("Trojan", cfg.name)
    }

    @Test
    fun parsesShadowsocksLink() {
        val payload = Base64.getEncoder().encodeToString("aes-256-gcm:secret".toByteArray())
        val cfg = LinkParser.parseLine("ss://$payload@ss.example.com:8388#SS%20Node")
        requireNotNull(cfg)
        assertEquals("ss", cfg.protocol)
        assertEquals("ss.example.com", cfg.host)
        assertEquals(8388, cfg.port)
        assertEquals("aes-256-gcm:secret", cfg.method)
        assertEquals("SS Node", cfg.name)
    }

    @Test
    fun parsesShadowsocksLinkWithTheWholePayloadEncoded() {
        // The second shape in the wild: method, password and host are encoded
        // together, the fragment stays plain.
        val payload = Base64.getEncoder().encodeToString(
            "aes-256-gcm:secret@ss.example.com:8388".toByteArray()
        )
        val cfg = LinkParser.parseLine("ss://$payload#Whole")
        requireNotNull(cfg)
        assertEquals("ss.example.com", cfg.host)
        assertEquals(8388, cfg.port)
        assertEquals("aes-256-gcm:secret", cfg.method)
        assertEquals("Whole", cfg.name)
    }

    @Test
    fun parsesHysteria2Link() {
        val cfg = LinkParser.parseLine("hysteria2://pass@hy2.example.com:443?sni=hy2.example.com#HY2")
        requireNotNull(cfg)
        assertEquals("hysteria2", cfg.protocol)
        assertEquals("hy2.example.com", cfg.host)
        assertEquals(443, cfg.port)
        assertEquals("pass", cfg.uuid)
        assertEquals("HY2", cfg.name)

        val short = LinkParser.parseLine("hy2://pass@hy2.example.com:443#HY2")
        requireNotNull(short)
        assertEquals("hysteria2", short.protocol)
    }

    @Test
    fun ignoresUnknownAndEmptyLines() {
        assertNull(LinkParser.parseLine(""))
        assertNull(LinkParser.parseLine("   "))
        assertNull(LinkParser.parseLine("https://example.com/subscription"))
        assertNull(LinkParser.parseLine("just some text"))
    }

    @Test
    fun parsesAMixedBlobAndKeepsTheOrder() {
        val text = """
            vless://uuid@a.example.com:443#A
            garbage line
            vmess://eyJ2IjoiMiIsInBzIjoiQiIsImFkZCI6ImIuZXhhbXBsZS5jb20iLCJwb3J0IjoiNDQzIn0=
            trojan://p@c.example.com:443#C
        """.trimIndent()

        val all = LinkParser.parseAll(text)
        assertEquals(3, all.size)
        assertEquals(listOf("A", "B", "C"), all.map { it.name })
        assertTrue(all.all { it.host.isNotBlank() })
    }

    @Test
    fun defaultsThePortWhenTheLinkHasNone() {
        val cfg = LinkParser.parseLine("trojan://pass@t.example.com#T")
        requireNotNull(cfg)
        assertEquals(443, cfg.port)
    }
}
