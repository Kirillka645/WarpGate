# WarpGate

Android-клиент VPN с генерацией WARP-профилей, оптимизацией эндпоинтов, поддержкой **AmneziaWG**, **MASQUE** и импортом подписок в стиле Happ.

## Возможности

- **WARP-профили** — регистрация устройства в Cloudflare прямо из приложения (кнопка «+ → WARP-профиль»). Ключи x25519 генерируются на устройстве и не покидают его.
- **Генератор конфигов** — вкладка «Генератор» регистрирует новое устройство в WARP и собирает готовый файл: `AmneziaWG .conf` или `Clash / Mihomo .yaml`. Формат совпадает с генератором [warp-config-ru](https://github.com/AppsGanin/warp-config-ru): те же I1-маски AmneziaWG, тот же DNS-обход геоблока, тот же пул эндпоинтов. Готовый конфиг копируется в буфер, отправляется в мессенджер или сохраняется в «Загрузки».
- **Оптимизатор эндпоинтов** — перед подключением приложение замеряет RTT реального WireGuard-хендшейка до 27 эндпоинтов Cloudflare (162.159.192/193/195.x, 188.114.96/97.x на портах 2408/500/1701/4500) и автоматически выбирает самый быстрый. Показывает лучший и альтернативный варианты.
- **AmneziaWG** — импорт конфигов `.conf` с обфускацией (параметры `Jc`, `Jmin`, `Jmax`, `S1`, `S2`, `H1–H4`, `I1–I5` в ядре) и подключение через ядро amneziawg-go.
- **MASQUE** — WARP поверх HTTP/3 (`cf-connect-ip`, QUIC-датаграммы) — экспериментальный режим. Регистрация ECDSA-ключа (P-256), TLS client cert на 24 ч, пиннинг ключа сервера.
- **Подписки как в Happ** — вставьте URL подписки (`https://…`, base64-тело) или список ссылок `vless://`, `vmess://`, `ss://`, `trojan://`, `hysteria2://` (hy2://). Узлы импортируются как профили.
- Тёмная тема в стиле Happ, русский интерфейс, Material 3 (Jetpack Compose), адаптивная иконка приложения.

## Архитектура

| Слой | Технология |
|---|---|
| UI | Kotlin + Jetpack Compose, Material 3, тёмная тема |
| VPN | Android `VpnService` + TUN fd → Go-ядро через gomobile |
| Туннель WG/AWG | [amneziawg-go](https://github.com/amnezia-vpn/amneziawg-go) (форк в `warpcore/awg-fork`) |
| MASQUE | [usque](https://github.com/Diniboy1123/usque) (форк в `warpcore/usque-fork`, MIT) |
| Регистрация WARP | Go-ядро + API `api.cloudflareclient.com/v0a2158` |
| Генератор конфигов | Go-ядро + API `api.cloudflareclient.com/v0i1909051800`, формат warp-config-ru |

Go-ядро (`warpcore/`) собирается командой `gomobile bind` в `warpcore.aar` и предоставляет: `RegisterWarp`, `ScanEndpoints`, `TunnelStart/Stop/Stats` (WG/AWG), `MasqueRegister`, `MasqueStart/Stop`, а также генератор `WarpGenRegister` / `WarpGenAmneziaWG` / `WarpGenClash` / `WarpGenRandomEndpoint`.

### Генератор конфигов

Генератор собран по мотивам [warp-config-ru](https://github.com/AppsGanin/warp-config-ru) (MIT) и повторяет его поведение:

1. Генерируется пара ключей X25519, в API уходит только публичная половина.
2. `POST /reg` + `PATCH /reg/{id}` на `api.cloudflareclient.com/v0i1909051800` — регистрация и включение WARP.
3. Собирается конфиг выбранного формата с I1-маской AmneziaWG (мимикрия под QUIC/SIP-хендшейк), DNS-резолверами для обхода геоблока и MTU 1280.

Формат вывода зафиксирован golden-тестами `warpcore/warpgen_test.go`: они сверяют SHA-256 готовых конфигов с эталонной реализацией, поэтому случайное изменение формата не пройдёт незаметно.

Маски I1 и шаблон Clash вендорены в `warpcore/warpgen_assets.go` из проекта warp-config-ru. Пересобрать их при обновлении референса:

```bash
git clone --depth 1 https://github.com/AppsGanin/warp-config-ru /tmp/ref
python warpcore/tools/extract_warpgen_assets.py /tmp/ref warpcore/warpgen_assets.go
cd warpcore && go test ./...   # golden-хэши подтвердят, что формат не изменился
```

Тот же генератор доступен из командной строки:

```bash
cd warpcore
go run ./cmd/warpgen -format amneziawg      # .conf для AmneziaVPN
go run ./cmd/warpgen -format clash -ipv6   # .yaml для Clash / Mihomo
go run ./cmd/warpgen -host 162.159.193.1 -port 2408
```

### Патчи в форках

- **awg-fork**: у amneziawg-go убрано поле `Reserved` в пакетах хендшейка (AWG использует свои magic-заголовки). Для WARP стандартный WireGuard требует client_id в reserved-байтах каждого пакета — добавлен `Device.SetWarpReserved([3]byte)`: исходящие типы пакетов получают reserved из client_id, входящие проверки маскируют его. Также добавлен экспорт `conn.AppendControlFn` для защиты UDP-сокетов (`VpnService.protect`) и обход WSAEFAULT на Windows/Go 1.26.
- **usque-fork**: добавлен хук `api.DialControl` для защиты QUIC-сокета через `VpnService.protect`.

### Сеть и API WARP

Регистрация устройства в Cloudflare идёт через `warpcore/httpclient.go`, где собрано всё, что нужно мобильным сетям:

- **Приоритет IPv4.** На сетях с неисправным IPv6 (типично для сотового интернета) соединение по IPv6 устанавливается, но TLS-байты теряются — клиент получает `net/http: TLS handshake timeout`, хотя IPv4 работает. Ядро сначала пробует IPv4-адреса, затем IPv6. Штатный Happy Eyeballs Go здесь не спасает: он переключается на IPv4 только если TCP-соединение не установилось, а здесь оно устанавливается и «залипает».
- **Два поколения API.** `v0a2158` и `v0i1909051800` возвращают одинаковые данные (`client_id`, адреса, ключ пира), но лежат по разным путям. Если один недоступен — используется другой.
- **Повторы.** Транзиентные обрывы (captive portal, смена вышки, DPI-сброс) переотправляются автоматически, до трёх попыток на поколение.

Ошибки транспорта классифицируются и переводятся в понятные сообщения на стороне приложения (`WarpErrors`).

## Сборка

Требуется: JDK 17–21, Android SDK (API 35), NDK, Go 1.24+, gomobile.

```bash
# 1. Ядро
cd warpcore
gomobile bind -target=android -androidapi 26 -o warpcore.aar .
cp warpcore.aar ../warpgate/app/libs/

# 2. Приложение
cd ../warpgate
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Тесты

```bash
# Go-ядро: формат конфигов, пулы эндпоинтов, golden-хэши против warp-config-ru
cd warpcore && go test ./...

# Android: парсеры AmneziaWG и share-ссылок
cd warpgate && ./gradlew :app:testDebugUnitTest
```

## Иконка приложения

Иконка собрана из одного SVG-макета: адаптивный foreground (щит с вырезанной молнией), monochrome-слой для тем Android 13+ и растровые mipmap-версии для старых лаунчеров.

- макет: `warpgate/design/ic_launcher.svg`
- рендер PNG: `node warpgate/design/render-icons.mjs` (нужен `sharp`)
- ресурсы: `warpgate/app/src/main/res/{drawable,mipmap-*}`

## Статус (проверено на эмуляторе API 34)

- ✅ WARP: генерация профиля (регистрация в Cloudflare), туннель поднимается (`tun0` UP, адрес 172.16.0.2), статус «Защищено», VPN-иконка в системе
- ✅ VpnService: запрос системного разрешения, foreground-уведомление, stop/revoke
- ✅ Оптимизатор: скан 27 эндпоинтов × 4 порта реальными хендшейками (в песочнице UDP-ответы блокируются средой — на реальной сети работает; хендшейк-зонды отправляются, poll IpcGet ждёт `last_handshake_time_sec`)
- ✅ AmneziaWG: импорт конфигов (включая Jc/Jmin/Jmax/S1/S2/H1–H4), туннель
- ✅ MASQUE: регистрация ECDSA-устройства + туннель (экспериментально)
- ✅ Генератор: регистрация в Cloudflare WARP, конфиг `warp-awg-*.conf` / `warp-clash-*.yaml`, копирование в буфер, «Поделиться», сохранение в «Загрузки»; вывод совпадает с warp-config-ru байт-в-байт
- ✅ Сеть: приоритет IPv4, резервное поколение API, повторы при обрывах; ошибки переводятся в понятные сообщения
- ⚠️ Прокси-профили (vless/vmess/ss/trojan/hy2): импорт подписок и хранение готовы; туннелирование этих протоколов требует прокси-ядра (Xray/sing-box) — планируется.

## Лицензии

- amneziawg-go — MIT
- usque — MIT
- warp-config-ru (маски I1 и шаблон Clash) — MIT, © 2026 Dmitry Ganin
- Весь код приложения распространяется под GPL-3.0 (совместимо с зависимостями).
