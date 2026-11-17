package com.warpgate.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.warpgate.app.core.GeneratedConfig
import com.warpgate.app.core.GenFormat
import com.warpgate.app.core.WarpEndpoints
import com.warpgate.app.core.WarpEngine
import com.warpgate.app.core.WarpGenEngine
import com.warpgate.app.data.AwgConfig
import com.warpgate.app.data.EndpointStat
import com.warpgate.app.data.MasqueConfig
import com.warpgate.app.data.Profile
import com.warpgate.app.data.ProfileType
import com.warpgate.app.data.ProxyConfig
import com.warpgate.app.data.Store
import com.warpgate.app.data.Subscription
import com.warpgate.app.data.WarpConfig
import com.warpgate.app.sub.LinkParser
import com.warpgate.app.sub.SubscriptionFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Global app state + use-cases. Simple observable singleton for Compose.
 */
object AppState {
    val profiles = mutableStateListOf<Profile>()
    val subscriptions = mutableStateListOf<Subscription>()
    var toast by mutableStateOf<String?>(null)
        private set
    var optimizing by mutableStateOf(false)
        private set
    var registerBusy by mutableStateOf(false)
        private set
    var masqueBusy by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        Store.init(ctx)
        reload()
    }

    fun reload() {
        val st = Store.load()
        profiles.clear()
        profiles.addAll(st.profiles)
        subscriptions.clear()
        subscriptions.addAll(st.subscriptions)
    }

    private fun persist() {
        Store.save(
            com.warpgate.app.data.PersistState(
                profiles = profiles.toList(),
                subscriptions = subscriptions.toList(),
                activeProfileId = Store.load().activeProfileId
            )
        )
    }

    fun showToast(msg: String) { toast = msg }
    fun clearToast() { toast = null }

    // ---- WARP ----

    /** Creates a fresh WARP profile (registers a device with Cloudflare). */
    suspend fun createWarpProfile(ctx: Context): Result<Profile> = withContext(Dispatchers.IO) {
        try {
            registerBusy = true
            runCatching {
                val cfg = WarpEngine.registerWarp()
                val profile = Profile(
                    id = Store.newId(),
                    name = "WARP ${nextWarpNumber()}",
                    type = ProfileType.WARP,
                    warp = cfg
                )
                profiles.add(profile)
                persist()
                profile
            }
        } finally {
            registerBusy = false
        }
    }

    private fun nextWarpNumber(): Int {
        val count = profiles.count { it.type == ProfileType.WARP }
        return count + 1
    }

    /** Adds an AmneziaWG profile from pasted wg-quick/AWG config text. */
    suspend fun importAwg(text: String, name: String?): Result<Profile> = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = AwgParser.parse(text)
                ?: error("Не удалось разобрать конфиг AmneziaWG / WireGuard")
            val existing = profiles.count { it.type == ProfileType.AWG }
            val profile = Profile(
                id = Store.newId(),
                name = name?.takeIf { it.isNotBlank() } ?: "AWG ${existing + 1}",
                type = ProfileType.AWG,
                awg = cfg
            )
            profiles.add(profile)
            persist()
            profile
        }
    }

    /** Manually creates an AWG profile from typed fields. */
    suspend fun createAwgManual(cfg: AwgConfig, name: String?): Result<Profile> = withContext(Dispatchers.IO) {
        runCatching {
            require(cfg.privateKey.isNotBlank() && cfg.peerPub.isNotBlank() && cfg.endpoint.isNotBlank()) {
                "Заполните ключи и эндпоинт"
            }
            val existing = profiles.count { it.type == ProfileType.AWG }
            val profile = Profile(
                id = Store.newId(),
                name = name?.takeIf { it.isNotBlank() } ?: "AWG ${existing + 1}",
                type = ProfileType.AWG,
                awg = cfg
            )
            profiles.add(profile)
            persist()
            profile
        }
    }

    /** Creates a MASQUE (WARP over HTTP/3) profile via the Go core. */
    suspend fun createMasqueProfile(): Result<Profile> = withContext(Dispatchers.IO) {
        try {
            masqueBusy = true
            runCatching {
                val json = WarpEngine.masqueRegister()
                val parsed = Json.parseToJsonElement(json).jsonObject
                val cfgJson = parsed["config"]!!.jsonObject
                val cfg = MasqueConfig(
                    privateKey = cfgJson["private_key"]!!.jsonPrimitive.content,
                    endpointV4 = cfgJson["endpoint_v4"]!!.jsonPrimitive.content,
                    endpointV6 = cfgJson["endpoint_v6"]!!.jsonPrimitive.content,
                    pubKey = cfgJson["pub_key"]!!.jsonPrimitive.content,
                    id = cfgJson["id"]!!.jsonPrimitive.content,
                    token = cfgJson["token"]!!.jsonPrimitive.content,
                    ipv4 = cfgJson["ipv4"]!!.jsonPrimitive.content,
                    ipv6 = cfgJson["ipv6"]!!.jsonPrimitive.content,
                    license = cfgJson["license"]?.jsonPrimitive?.content
                )
                val profile = Profile(
                    id = Store.newId(),
                    name = "MASQUE ${profiles.count { it.type == ProfileType.MASQUE } + 1}",
                    type = ProfileType.MASQUE,
                    masque = cfg
                )
                profiles.add(profile)
                persist()
                profile
            }
        } finally {
            masqueBusy = false
        }
    }

    // ---- Генератор экспортируемых конфигов ----

    var genFormat by mutableStateOf(GenFormat.AMNEZIAWG)
    var genHost by mutableStateOf<String?>(null)
    var genPort by mutableStateOf<Int?>(null)
    var genIpv6 by mutableStateOf(false)
    var genBusy by mutableStateOf(false)
        private set
    var genResult by mutableStateOf<GeneratedConfig?>(null)
        private set

    /**
     * Регистрирует новое устройство в Cloudflare WARP и собирает конфиг
     * выбранного формата. Ключи генерируются на устройстве.
     */
    suspend fun generateConfig(): Result<GeneratedConfig> = withContext(Dispatchers.IO) {
        try {
            genBusy = true
            runCatching {
                WarpGenEngine.generate(genFormat, genHost, genPort, genIpv6)
                    .also { genResult = it }
            }
        } finally {
            genBusy = false
        }
    }

    // ---- Optimizer ----

    /** Scans endpoints for the profile and picks the best one. */
    suspend fun optimize(profileId: String): Result<Profile> = withContext(Dispatchers.IO) {
        runCatching {
            val idx = profiles.indexOfFirst { it.id == profileId }
            val profile = profiles[idx]
            optimizing = true
            try {
                val (priv, peer, reserved) = when (profile.type) {
                    ProfileType.WARP -> Triple(
                        profile.warp!!.privateKey, profile.warp!!.peerPub, profile.warp!!.clientReserved
                    )
                    ProfileType.AWG -> Triple(
                        profile.awg!!.privateKey, profile.awg!!.peerPub, ""
                    )
                    else -> error("Оптимизация доступна для WARP и AmneziaWG профилей")
                }
                val endpointsToScan = if (profile.type == ProfileType.WARP) {
                    WarpEndpoints.candidateList()
                } else {
                    listOf(profile.awg!!.endpoint)
                }
                val results = WarpEngine.scanEndpoints(priv, peer, reserved, endpointsToScan, 2500)
                val best = results.firstOrNull { it.ok }
                    ?: error("Ни один эндпоинт не ответил (проверьте интернет)")
                profiles[idx] = profile.copy().apply {
                    endpoints = results
                    when (type) {
                        ProfileType.WARP -> warp!!.endpoint = best.endpoint
                        ProfileType.AWG -> awg!!.endpoint = best.endpoint
                        else -> {}
                    }
                }
                persist()
                profiles[idx]
            } finally {
                optimizing = false
            }
        }
    }

    fun setEndpointManually(profileId: String, endpoint: String) {
        val idx = profiles.indexOfFirst { it.id == profileId }
        if (idx == -1) return
        val p = profiles[idx]
        val normalized = endpoint.trim().let {
            if (it.contains(':')) it else "$it:2408"
        }
        profiles[idx] = p.copy().apply {
            when (type) {
                ProfileType.WARP -> warp!!.endpoint = normalized
                ProfileType.AWG -> awg!!.endpoint = normalized
                else -> {}
            }
        }
        persist()
    }

    fun deleteProfile(id: String) {
        profiles.removeAll { it.id == id }
        persist()
    }

    fun renameProfile(id: String, name: String) {
        val idx = profiles.indexOfFirst { it.id == id }
        if (idx == -1) return
        profiles[idx] = profiles[idx].copy().apply { this.name = name }
        persist()
    }

    fun bestEndpoint(profile: Profile): EndpointStat? =
        profile.endpoints.firstOrNull { it.ok }

    // ---- Subscriptions & links (Happ-style) ----

    /** Imports links/subscription content: URL (http(s)) or raw link blob. */
    suspend fun importContent(input: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = input.trim()
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                // subscription URL
                val links = SubscriptionFetcher.fetch(trimmed)
                    .getOrElse { throw it }
                require(links.isNotEmpty()) { "Подписка не содержит распознанных ссылок" }
                val sub = Subscription(
                    id = Store.newId(),
                    name = runCatching { java.net.URI(trimmed).host }.getOrDefault("подписка"),
                    url = trimmed,
                    updatedAt = System.currentTimeMillis(),
                    linkCount = links.size
                )
                subscriptions.add(sub)
                addProxyProfiles(links)
                persist()
                links.size
            } else {
                val links = LinkParser.parseAll(trimmed)
                require(links.isNotEmpty()) { "Не найдено ни одной ссылки (vless/vmess/ss/trojan/hy2)" }
                addProxyProfiles(links)
                persist()
                links.size
            }
        }
    }

    private fun addProxyProfiles(links: List<ProxyConfig>) {
        for (link in links) {
            profiles.add(
                Profile(
                    id = Store.newId(),
                    name = link.name.ifBlank { "${link.protocol} ${link.host}" },
                    type = ProfileType.PROXY,
                    proxy = link
                )
            )
        }
    }

    fun refreshSubscription(subId: String, onDone: (Int) -> Unit = {}) {
        val sub = subscriptions.firstOrNull { it.id == subId } ?: return
        subscriptions.removeAll { it.id == subId }
        profiles.removeAll { it.type == ProfileType.PROXY && it.name.startsWith(sub.name) }
        persist()
        // re-fetch happens via importContent on the UI thread coroutine
    }

    fun deleteSubscription(id: String) {
        subscriptions.removeAll { it.id == id }
        persist()
    }
}

/**
 * Parser for wg-quick / AmneziaWG .conf text ([Interface]/[Peer] sections).
 */
object AwgParser {
    fun parse(text: String): AwgConfig? {
        var privateKey: String? = null
        var peerPub: String? = null
        var psk: String? = null
        var endpoint: String? = null
        var address = ""
        var dns: String? = null
        var keepAlive = 25
        var jc = 0; var jmin = 0; var jmax = 0; var s1 = 0; var s2 = 0
        var h1 = 1L; var h2 = 2L; var h3 = 3L; var h4 = 4L

        for (rawLine in text.lines()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith("[")) continue
            val key = line.substringBefore('=').trim().lowercase()
            val value = line.substringAfter('=', "").trim()
            when (key) {
                "privatekey" -> privateKey = value
                "publickey" -> peerPub = value
                "presharedkey" -> psk = value
                "endpoint" -> endpoint = value
                "address" -> address = listOf(address, value).filter { it.isNotBlank() }.joinToString(",")
                "dns" -> dns = value
                "persistentkeepalive" -> keepAlive = value.toIntOrNull() ?: 25
                "jc" -> jc = value.toIntOrNull() ?: 0
                "jmin" -> jmin = value.toIntOrNull() ?: 0
                "jmax" -> jmax = value.toIntOrNull() ?: 0
                "s1" -> s1 = value.toIntOrNull() ?: 0
                "s2" -> s2 = value.toIntOrNull() ?: 0
                "h1" -> h1 = value.toLongOrNull() ?: 1
                "h2" -> h2 = value.toLongOrNull() ?: 2
                "h3" -> h3 = value.toLongOrNull() ?: 3
                "h4" -> h4 = value.toLongOrNull() ?: 4
            }
        }
        val priv = privateKey ?: return null
        val pub = peerPub ?: return null
        val ep = endpoint ?: return null
        return AwgConfig(
            privateKey = priv, peerPub = pub, endpoint = ep,
            address = address.ifBlank { "10.8.1.2/32" }, dns = dns,
            presharedKey = psk, keepAlive = keepAlive,
            jc = jc, jmin = jmin, jmax = jmax, s1 = s1, s2 = s2,
            h1 = h1, h2 = h2, h3 = h3, h4 = h4
        )
    }
}
