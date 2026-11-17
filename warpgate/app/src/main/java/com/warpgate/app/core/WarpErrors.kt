package com.warpgate.app.core

/**
 * Перевод технических ошибок ядра в понятные сообщения.
 *
 * Go-ядро возвращает системные тексты вроде `net/http: TLS handshake timeout`.
 * Пользователю они ничего не объясняют, поэтому здесь каждый класс ошибок
 * превращается в причину и подсказку, что делать дальше.
 */
object WarpErrors {

    /** Сообщение по умолчанию, если ошибку распознать не удалось. */
    const val GENERIC: String = "Не удалось связаться с Cloudflare WARP"

    /**
     * Превращает сообщение об ошибке в текст для тоста.
     *
     * @param raw текст ошибки из Go-ядра
     * @param fallback что показать, если ошибка неизвестна
     */
    fun humanize(raw: String?, fallback: String = GENERIC): String {
        val message = raw.orEmpty()
        if (message.isBlank()) return fallback

        val lower = message.lowercase()
        return when {
            lower.contains("tls handshake timeout") ->
                "Cloudflare WARP не отвечает — соединение блокируется или зависает. " +
                    "Попробуйте другую сеть или включите VPN."

            lower.contains("no such host") || lower.contains("dns") ->
                "Не удалось найти адрес Cloudflare WARP — проблема с DNS. " +
                    "Попробуйте сменить DNS или перезапустить сеть."

            lower.contains("connection refused") ->
                "Соединение с Cloudflare WARP отклонено — адрес заблокирован в вашей сети."

            lower.contains("connection reset") || lower.contains("broken pipe") ->
                "Соединение с Cloudflare WARP разорвано — адрес заблокирован в вашей сети."

            lower.contains("unexpected eof") || lower.endsWith("eof") ->
                "Соединение с Cloudflare WARP прервано на стороне сервера."

            lower.contains("network is unreachable") || lower.contains("no route to host") ->
                "Нет маршрута до Cloudflare WARP — проверьте подключение к интернету."

            lower.contains("certificate") || lower.contains("x509") ->
                "Не удалось проверить сертификат Cloudflare WARP — соединение перехватывается."

            lower.contains("i/o timeout") || lower.contains("timeout") ->
                "Превышено время ожидания ответа Cloudflare WARP. Попробуйте позже."

            lower.contains("context deadline exceeded") ->
                "Превышено время ожидания ответа Cloudflare WARP. Попробуйте позже."

            else -> fallback
        }
    }

    /**
     * Сообщение для неудачной регистрации WARP-профиля.
     *
     * Подсказывает вкладку «Генератор» — там используется другой путь к API
     * и другой формат конфига, поэтому он может сработать там, где не
     * проходит туннель.
     */
    fun forProfile(raw: String?): String =
        humanize(raw) + " Как вариант — соберите конфиг во вкладке «Генератор»."
}
