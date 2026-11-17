package com.warpgate.app.core

import kotlinx.serialization.json.Json
import warpcore.Warpcore

/**
 * Формат генерируемого конфига.
 *
 * Форматы и параметры совпадают с генератором warp-config-ru: те же ключи
 * WARP, те же I1-маски AmneziaWG и тот же шаблон Clash.
 */
enum class GenFormat(
    val title: String,
    val subtitle: String,
    val extension: String
) {
    AMNEZIAWG(
        title = "AmneziaWG",
        subtitle = "Файл .conf для AmneziaVPN и совместимых клиентов",
        extension = "conf"
    ),
    CLASH(
        title = "Clash / Mihomo",
        subtitle = "Файл .yaml для Clash, Mihomo и форков",
        extension = "yaml"
    )
}

/** Готовый конфиг вместе с именем файла и выбранным эндпоинтом. */
data class GeneratedConfig(
    val text: String,
    val fileName: String,
    val endpoint: String,
    val format: GenFormat
) {
    /** Размер конфига в байтах — показывается рядом с именем файла. */
    val sizeLabel: String
        get() = when {
            text.length < 1024 -> "$text.length Б"
            else -> String.format("%.1f КБ", text.length / 1024.0)
        }
}

/**
 * Обвязка Go-ядра: регистрация устройства в Cloudflare WARP и сборка
 * экспортируемого конфига.
 *
 * Ключи генерируются на устройстве и наружу не отправляются — в API уходит
 * только публичная половина.
 */
object WarpGenEngine {

    private val json = Json { ignoreUnknownKeys = true }

    /** Пул эндпоинтов WARP. */
    val hosts: List<String>
        get() = runCatching {
            json.decodeFromString<List<String>>(Warpcore.warpGenHosts())
        }.getOrDefault(listOf("162.159.192.1", "162.159.193.1", "162.159.195.1"))

    /** Пул UDP-портов, которые принимает WARP. */
    val ports: List<Int>
        get() = runCatching {
            json.decodeFromString<List<Int>>(Warpcore.warpGenPorts())
        }.getOrDefault(listOf(4500, 2408, 500, 1701))

    /** Случайный эндпоинт из пула. */
    fun randomEndpoint(): Pair<String, Int> = runCatching {
        val ep = Warpcore.warpGenRandomEndpoint()
        ep.host to ep.port
    }.getOrElse { (hosts.firstOrNull() ?: "162.159.192.1") to 4500 }

    /**
     * Регистрирует новое устройство в WARP и собирает конфиг.
     *
     * @param format формат конфига
     * @param host конкретный хост или null для случайного из пула
     * @param port конкретный порт или null для случайного из пула
     * @param ipv6 добавить IPv6 в конфиг (dual stack)
     */
    fun generate(
        format: GenFormat,
        host: String?,
        port: Int?,
        ipv6: Boolean
    ): GeneratedConfig {
        val reg = Warpcore.warpGenRegister()

        val pickedHost = host?.takeIf { it.isNotBlank() }
        val pickedPort = port
        val (finalHost, finalPort) = if (pickedHost == null || pickedPort == null) {
            val (rh, rp) = randomEndpoint()
            (pickedHost ?: rh) to (pickedPort ?: rp)
        } else {
            pickedHost to pickedPort
        }

        val text = when (format) {
            GenFormat.AMNEZIAWG -> Warpcore.warpGenAmneziaWG(
                reg.privateKey, reg.peerPub, reg.clientIPv6, finalHost, finalPort, ipv6
            )
            GenFormat.CLASH -> Warpcore.warpGenClash(
                reg.privateKey, reg.clientIPv6, finalHost, finalPort, ipv6
            )
        }

        return GeneratedConfig(
            text = text,
            fileName = fileName(format),
            endpoint = "$finalHost:$finalPort",
            format = format
        )
    }

    /** Имя файла в стиле warp-config-ru: warp-awg-<stamp>.conf. */
    private fun fileName(format: GenFormat): String {
        val stamp = (1_000_000..9_999_999).random()
        val prefix = if (format == GenFormat.AMNEZIAWG) "warp-awg" else "warp-clash"
        return "$prefix-$stamp.${format.extension}"
    }
}
