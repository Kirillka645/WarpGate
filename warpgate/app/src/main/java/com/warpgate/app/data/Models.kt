package com.warpgate.app.data

import kotlinx.serialization.Serializable

enum class ProfileType { WARP, AWG, MASQUE, PROXY }

@Serializable
data class EndpointStat(
    val endpoint: String,
    val ms: Long,
    val ok: Boolean
)

@Serializable
data class WarpConfig(
    val deviceId: String,
    val token: String,
    val privateKey: String,      // base64 x25519
    val clientReserved: String,  // base64 client_id (reserved bytes)
    val peerPub: String,         // WARP server key
    val addressV4: String,
    val addressV6: String,
    var endpoint: String,
    var license: String? = null
)

@Serializable
data class AwgConfig(
    val privateKey: String,      // base64
    val peerPub: String,         // base64
    var endpoint: String,
    val address: String,         // "10.8.1.2/32, fd42:.../128"
    val dns: String? = null,
    val presharedKey: String? = null,
    val keepAlive: Int = 25,
    // AmneziaWG obfuscation parameters
    val jc: Int = 0,
    val jmin: Int = 0,
    val jmax: Int = 0,
    val s1: Int = 0,
    val s2: Int = 0,
    val h1: Long = 1,
    val h2: Long = 2,
    val h3: Long = 3,
    val h4: Long = 4
)

@Serializable
data class ProxyConfig(
    val protocol: String,        // vless | vmess | trojan | ss | hysteria2
    val name: String,
    val host: String,
    val port: Int,
    val uuid: String? = null,
    val method: String? = null,  // ss method / vmess security
    val network: String? = null, // tcp/ws/grpc
    val security: String? = null,// tls/reality/none
    val sni: String? = null,
    val path: String? = null,
    val flow: String? = null,
    val rawLink: String
)

@Serializable
data class Subscription(
    val id: String,
    var name: String,
    var url: String,
    var updatedAt: Long = 0,
    var linkCount: Int = 0
)

@Serializable
data class MasqueConfig(
    val privateKey: String,   // base64 ECDSA DER
    val endpointV4: String,   // 162.159.198.1
    val endpointV6: String,
    val pubKey: String,       // server PEM
    val id: String,
    val token: String,
    val ipv4: String,
    val ipv6: String,
    val license: String? = null
)

@Serializable
data class Profile(
    val id: String,
    var name: String,
    val type: ProfileType,
    var warp: WarpConfig? = null,
    var awg: AwgConfig? = null,
    var masque: MasqueConfig? = null,
    var proxy: ProxyConfig? = null,
    var endpoints: List<EndpointStat> = emptyList(),
    var createdAt: Long = System.currentTimeMillis()
) {
    val endpointLabel: String
        get() = when (type) {
            ProfileType.WARP -> warp?.endpoint ?: "—"
            ProfileType.AWG -> awg?.endpoint ?: "—"
            ProfileType.MASQUE -> (masque?.endpointV4 ?: "162.159.198.1") + ":443"
            ProfileType.PROXY -> proxy?.let { "${it.host}:${it.port}" } ?: "—"
        }

    val isReady: Boolean
        get() = when (type) {
            ProfileType.WARP -> warp != null
            ProfileType.AWG -> awg != null
            ProfileType.MASQUE -> masque != null
            ProfileType.PROXY -> proxy != null
        }
}
