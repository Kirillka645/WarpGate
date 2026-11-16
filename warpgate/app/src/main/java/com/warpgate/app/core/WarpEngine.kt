package com.warpgate.app.core

import com.warpgate.app.data.AwgConfig
import com.warpgate.app.data.WarpConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import warpcore.Warpcore

/**
 * WireGuard / AmneziaWG IpcSet configuration builders.
 */

fun b64ToHex(b64: String): String {
    val raw = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return "00".repeat(32)
    return raw.joinToString("") { "%02x".format(it) }
}

/** Device-level + peer IpcSet string for a WARP (standard WireGuard) tunnel. */
fun warpIpcSettings(cfg: WarpConfig): String = buildString {
    append("private_key=").append(b64ToHex(cfg.privateKey)).append('\n')
    append("public_key=").append(b64ToHex(cfg.peerPub)).append('\n')
    append("endpoint=").append(cfg.endpoint).append('\n')
    append("allowed_ip=0.0.0.0/0\n")
    append("allowed_ip=::/0\n")
    append("persistent_keepalive_interval=25\n")
}

/** IpcSet string for an AmneziaWG tunnel with obfuscation parameters. */
fun awgIpcSettings(cfg: AwgConfig): String = buildString {
    append("private_key=").append(b64ToHex(cfg.privateKey)).append('\n')
    if (cfg.jc != 0) append("jc=").append(cfg.jc).append('\n')
    if (cfg.jmin != 0) append("jmin=").append(cfg.jmin).append('\n')
    if (cfg.jmax != 0) append("jmax=").append(cfg.jmax).append('\n')
    if (cfg.s1 != 0) append("s1=").append(cfg.s1).append('\n')
    if (cfg.s2 != 0) append("s2=").append(cfg.s2).append('\n')
    if (cfg.h1 != 1L) append("h1=").append(cfg.h1).append('\n')
    if (cfg.h2 != 2L) append("h2=").append(cfg.h2).append('\n')
    if (cfg.h3 != 3L) append("h3=").append(cfg.h3).append('\n')
    if (cfg.h4 != 4L) append("h4=").append(cfg.h4).append('\n')
    append("public_key=").append(b64ToHex(cfg.peerPub)).append('\n')
    if (!cfg.presharedKey.isNullOrBlank()) {
        append("preshared_key=").append(b64ToHex(cfg.presharedKey)).append('\n')
    }
    append("endpoint=").append(cfg.endpoint).append('\n')
    append("allowed_ip=0.0.0.0/0\n")
    append("allowed_ip=::/0\n")
    append("persistent_keepalive_interval=").append(cfg.keepAlive).append('\n')
}

object WarpEngine {

    private val json = Json { ignoreUnknownKeys = true }

    /** Registers a WARP device via the Go core and builds a WarpConfig. */
    fun registerWarp(): WarpConfig {
        val reg = Warpcore.registerWarp("Android")
        return WarpConfig(
            deviceId = reg.deviceID,
            token = reg.token,
            privateKey = reg.privateKey,
            clientReserved = reg.clientID,
            peerPub = reg.peerPub,
            addressV4 = reg.addressV4,
            addressV6 = reg.addressV6,
            endpoint = reg.endpoint.replace("engage.cloudflareclient.com", "162.159.192.1")
        )
    }

    /**
     * Scans the candidate endpoints with a real WireGuard handshake and
     * returns results sorted best-first.
     */
    fun scanEndpoints(
        privateKeyB64: String,
        peerPubB64: String,
        reservedB64: String,
        endpoints: List<String>,
        timeoutMs: Int = 3000
    ): List<com.warpgate.app.data.EndpointStat> {
        val arr = JsonArray(endpoints.map { JsonPrimitive(it) }).toString()
        val out = Warpcore.scanEndpoints(arr, privateKeyB64, peerPubB64, reservedB64, timeoutMs)
        return runCatching { json.decodeFromString<List<com.warpgate.app.data.EndpointStat>>(out) }
            .getOrDefault(emptyList())
    }

    fun tunnelStart(fd: Int, mtu: Int, settings: String, reservedB64: String, protector: warpcore.Protector): Int =
        Warpcore.tunnelStart(fd, mtu, settings, reservedB64, protector)

    /** Registers a MASQUE-capable WARP device and returns the JSON config. */
    fun masqueRegister(): String = Warpcore.masqueRegister("Android")

    fun masqueStart(fd: Int, mtu: Int, configJson: String, protector: warpcore.Protector): Int =
        Warpcore.masqueStart(fd, mtu, configJson, protector)

    fun masqueStop(handle: Int) {
        runCatching { Warpcore.masqueStop(handle) }
    }

    fun tunnelStop(handle: Int) {
        runCatching { Warpcore.tunnelStop(handle) }
    }

    fun tunnelStopAll() {
        runCatching { Warpcore.tunnelStopAll() }
    }

    fun tunnelStats(handle: Int): String? =
        runCatching { Warpcore.tunnelGetStats(handle) }.getOrNull()
}

/**
 * Candidate WARP endpoint list used by the optimizer (Cloudflare WARP ranges).
 */
object WarpEndpoints {
    val DEFAULT_HOSTS = listOf(
        "162.159.192.1", "162.159.192.2", "162.159.192.3", "162.159.192.4", "162.159.192.5",
        "162.159.192.6", "162.159.192.7", "162.159.192.8", "162.159.192.9", "162.159.192.10",
        "162.159.193.1", "162.159.193.2", "162.159.193.3", "162.159.193.4", "162.159.193.5",
        "162.159.193.10",
        "162.159.195.1", "162.159.195.2", "162.159.195.3",
        "188.114.96.1", "188.114.96.2", "188.114.96.3", "188.114.96.4",
        "188.114.97.1", "188.114.97.2", "188.114.97.3", "188.114.97.4",
    )
    val PORTS = listOf(2408, 500, 1701, 4500)

    fun candidateList(ports: List<Int> = PORTS): List<String> =
        DEFAULT_HOSTS.flatMap { host -> ports.map { "$host:$it" } }
}
