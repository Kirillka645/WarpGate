package com.warpgate.app.sub

import com.warpgate.app.data.ProxyConfig
import java.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches a Happ-style subscription URL: a base64-encoded (or plain) body of
 * proxy share links, one per line.
 */
object SubscriptionFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun fetch(url: String): Result<List<ProxyConfig>> = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "WarpGate/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            require(resp.isSuccessful) { "HTTP ${resp.code}" }
            val body = resp.body?.string().orEmpty()
            decodeSubscriptionBody(body)
        }
    }

    /** Decodes base64 or plain multi-line subscription content. */
    fun decodeSubscriptionBody(body: String): List<ProxyConfig> {
        val text = tryBase64(body.trim()) ?: body
        val parsed = LinkParser.parseAll(text)
        return parsed
    }

    private fun tryBase64(s: String): String? {
        if (s.contains("://")) return null
        return runCatching {
            val cleaned = s.replace("\\s".toRegex(), "")
                .replace('-', '+').replace('_', '/')
            val pad = (4 - cleaned.length % 4) % 4
            val decoded = Base64.getDecoder().decode(cleaned + "=".repeat(pad))
            val str = String(decoded)
            if (str.contains("://")) str else null
        }.getOrNull()
    }
}
