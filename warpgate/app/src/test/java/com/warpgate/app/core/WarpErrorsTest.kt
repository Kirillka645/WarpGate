package com.warpgate.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты перевода технических ошибок ядра в сообщения для пользователя.
 *
 * Сценарий из релизного фидбэка: приложение показывало «Post
 * "https://api.cloudflareclient.com/v0a2158/reg": net/http: TLS handshake
 * timeout» — пользователю это ничего не объясняло.
 */
class WarpErrorsTest {

    @Test
    fun explainsTheTlsHandshakeTimeout() {
        val raw = """Post "https://api.cloudflareclient.com/v0a2158/reg": net/http: TLS handshake timeout"""
        val text = WarpErrors.humanize(raw)
        assertTrue("must mention WARP", text.contains("Cloudflare WARP"))
        assertTrue("must give a hint", text.contains("VPN") || text.contains("сеть"))
        assertTrue("must not leak the raw Go text", !text.contains("net/http"))
    }

    @Test
    fun explainsTheDnsFailure() {
        val text = WarpErrors.humanize("dial tcp: lookup api.cloudflareclient.com: no such host")
        assertTrue(text.contains("DNS"))
    }

    @Test
    fun explainsTheBlockedConnection() {
        val reset = WarpErrors.humanize("read tcp 10.0.0.2:443: connection reset by peer")
        assertTrue(reset.contains("заблокирован"))

        val refused = WarpErrors.humanize("dial tcp 8.6.112.0:443: connect: connection refused")
        assertTrue(refused.contains("заблокирован"))
    }

    @Test
    fun explainsTheMissingRoute() {
        val text = WarpErrors.humanize("dial tcp: connect: network is unreachable")
        assertTrue(text.contains("маршрут"))
    }

    @Test
    fun explainsTheInterceptedCertificate() {
        val text = WarpErrors.humanize("tls: failed to verify certificate: x509: certificate signed by unknown authority")
        assertTrue(text.contains("сертификат"))
    }

    @Test
    fun fallsBackWhenTheErrorIsUnknown() {
        assertEquals(WarpErrors.GENERIC, WarpErrors.humanize("something odd happened"))
        assertEquals(WarpErrors.GENERIC, WarpErrors.humanize(null))
        assertEquals(WarpErrors.GENERIC, WarpErrors.humanize(""))
    }

    @Test
    fun usesTheCustomFallback() {
        assertEquals(
            "Ошибка регистрации MASQUE",
            WarpErrors.humanize("something odd happened", "Ошибка регистрации MASQUE")
        )
    }

    @Test
    fun theProfileHintPointsToTheGenerator() {
        val text = WarpErrors.forProfile("net/http: TLS handshake timeout")
        assertTrue("must point to the generator tab", text.contains("Генератор"))
    }

    @Test
    fun everyKnownNetworkFailureGetsItsOwnMessage() {
        val samples = listOf(
            "net/http: TLS handshake timeout",
            "no such host",
            "connection reset by peer",
            "connection refused",
            "network is unreachable",
            "x509: certificate signed by unknown authority",
            "i/o timeout"
        )
        val messages = samples.map { WarpErrors.humanize(it) }.toSet()
        assertEquals("each failure must get its own message", samples.size, messages.size)
    }
}
