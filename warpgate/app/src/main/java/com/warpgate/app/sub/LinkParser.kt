package com.warpgate.app.sub

import com.warpgate.app.data.ProxyConfig
import java.net.URLDecoder
import java.net.URI
import java.util.Base64

/**
 * Parses proxy share links: vless://, vmess://, trojan://, ss://,
 * hysteria2:// (hy2://). Returns null when the line is not recognized.
 */
object LinkParser {

    fun parseLine(line: String): ProxyConfig? {
        val l = line.trim()
        if (l.isEmpty()) return null
        return when {
            l.startsWith("vless://") -> parseVless(l)
            l.startsWith("vmess://") -> parseVmess(l)
            l.startsWith("trojan://") -> parseTrojan(l)
            l.startsWith("ss://") -> parseSs(l)
            l.startsWith("hysteria2://") || l.startsWith("hy2://") -> parseHy2(l)
            else -> null
        }
    }

    /** Parses a multi-line text blob of links. */
    fun parseAll(text: String): List<ProxyConfig> =
        text.lines().mapNotNull { parseLine(it) }

    private fun parseVless(link: String): ProxyConfig? = runCatching {
        val uri = URI(link)
        val userInfo = uri.userInfo ?: return null
        val params = queryParams(uri.rawQuery ?: "")
        ProxyConfig(
            protocol = "vless",
            name = fragment(uri.fragment),
            host = uri.host ?: "",
            port = if (uri.port > 0) uri.port else 443,
            uuid = userInfo,
            network = params["type"] ?: "tcp",
            security = params["security"] ?: "none",
            sni = params["sni"] ?: params["host"],
            path = params["path"],
            flow = params["flow"],
            rawLink = link
        )
    }.getOrNull()

    private fun parseVmess(link: String): ProxyConfig? = runCatching {
        val body = link.removePrefix("vmess://")
        val decoded = String(b64decode(body))
        val map = rawJsonMap(decoded)
        ProxyConfig(
            protocol = "vmess",
            name = map["ps"] ?: "vmess",
            host = map["add"] ?: "",
            port = map["port"]?.toString()?.toIntOrNull() ?: 443,
            uuid = map["id"],
            method = map["scy"] ?: "auto",
            network = map["net"] ?: "tcp",
            security = map["tls"]?.takeIf { it == "tls" } ?: "none",
            sni = map["sni"] ?: map["host"],
            path = map["path"],
            rawLink = link
        )
    }.getOrNull()

    private fun parseTrojan(link: String): ProxyConfig? = runCatching {
        val uri = URI(link)
        val params = queryParams(uri.rawQuery ?: "")
        ProxyConfig(
            protocol = "trojan",
            name = fragment(uri.fragment),
            host = uri.host ?: "",
            port = if (uri.port > 0) uri.port else 443,
            uuid = uri.userInfo,
            security = "tls",
            sni = params["sni"] ?: params["peer"],
            network = params["type"] ?: "tcp",
            path = params["path"],
            rawLink = link
        )
    }.getOrNull()

    private fun parseSs(link: String): ProxyConfig? = runCatching {
        val body = link.removePrefix("ss://")
        val fragmentName = body.substringAfter('#', "")
        val main = body.substringBefore('#')
        // Two shapes in the wild:
        //   base64(method:pass@host:port) — the whole payload is encoded
        //   base64(method:pass)@host:port — only the credentials are encoded
        val decodedWhole = decodeMaybe(main)
        val (userinfo, hostPart) = when {
            decodedWhole != null && decodedWhole.contains('@') ->
                decodedWhole.substringBefore('@') to decodedWhole.substringAfter('@')
            main.contains('@') -> {
                val at = main.lastIndexOf('@')
                val head = main.substring(0, at)
                (decodeMaybe(head) ?: head) to main.substring(at + 1)
            }
            else -> main to ""
        }

        val hostPort = hostPart.substringBefore('?')
        val host = hostPort.substringBeforeLast(':')
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: 8388
        val method = userinfo.substringBefore(':')
        val pass = userinfo.substringAfter(':', "")

        ProxyConfig(
            protocol = "ss",
            name = fragment(fragmentName),
            host = host,
            port = port,
            method = "$method:$pass",
            rawLink = link
        )
    }.getOrNull()

    private fun parseHy2(link: String): ProxyConfig? = runCatching {
        val scheme = if (link.startsWith("hysteria2://")) "hysteria2://" else "hy2://"
        val uri = URI(link)
        val params = queryParams(uri.rawQuery ?: "")
        ProxyConfig(
            protocol = "hysteria2",
            name = fragment(uri.fragment),
            host = uri.host ?: link.removePrefix(scheme).substringBefore(':'),
            port = if (uri.port > 0) uri.port else 443,
            uuid = uri.userInfo,
            security = "tls",
            sni = params["sni"],
            rawLink = link
        )
    }.getOrNull()

    // --- helpers ---

    private fun b64decode(s: String): ByteArray {
        val clean = s.trim().replace('-', '+').replace('_', '=')
            .replace(Regex("=+\\Z"), "")
        return try {
            Base64.getDecoder().decode(clean)
        } catch (e: IllegalArgumentException) {
            Base64.getUrlDecoder().decode(s.trim())
        }
    }

    /**
     * Base64 decodes a string and returns null when it is not valid base64.
     *
     * Share links mix encoded and plain parts, so a failed decode is a normal
     * outcome and not an error.
     */
    private fun decodeMaybe(s: String): String? = runCatching {
        String(b64decode(s))
    }.getOrNull()

    private fun queryParams(query: String): Map<String, String> =
        query.split('&')
            .filter { it.contains('=') }
            .associate {
                val k = URLDecoder.decode(it.substringBefore('='), "UTF-8")
                val v = URLDecoder.decode(it.substringAfter('='), "UTF-8")
                k to v
            }

    private fun fragment(f: String?): String =
        f?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() } ?: "узел"

    private fun rawJsonMap(json: String): Map<String, String> {
        // minimal JSON object parser without extra deps on types we don't have
        val result = mutableMapOf<String, String>()
        val trimmed = json.trim().removePrefix("{").removeSuffix("}")
        var i = 0
        while (i < trimmed.length) {
            val keyEnd = findStringEnd(trimmed, i)
            if (keyEnd == -1) break
            val key = unquote(trimmed.substring(i + 1, keyEnd - 1))
            i = keyEnd
            while (i < trimmed.length && trimmed[i] != ':') i++
            i++
            while (i < trimmed.length && trimmed[i] == ' ') i++
            val value: String = if (i < trimmed.length && trimmed[i] == '"') {
                val end = findStringEnd(trimmed, i)
                if (end == -1) break
                val v = unquote(trimmed.substring(i + 1, end - 1))
                i = end
                v
            } else {
                val end = trimmed.indexOf(',', i).let { if (it == -1) trimmed.length else it }
                val raw = trimmed.substring(i, end).trim()
                i = end
                raw
            }
            result[key] = value
            while (i < trimmed.length && trimmed[i] == ',') i++
            while (i < trimmed.length && trimmed[i] == ' ') i++
        }
        return result
    }

    private fun findStringEnd(s: String, startQuote: Int): Int {
        var i = startQuote + 1
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) { i += 2; continue }
            if (s[i] == '"') return i + 1
            i++
        }
        return -1
    }

    private fun unquote(s: String): String =
        s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/")
}
