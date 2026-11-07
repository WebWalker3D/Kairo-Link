# Kairo-Link

A low-power Bluetooth LE tether that lets Kairo's body (a Galaxy A16) reach Kairo through a
phone that has internet (a Galaxy S22+) — without running a Wi-Fi hotspot.

```
 A16 (body)                                   S22+ (relay)
 Kairo body app ──CONNECT──▶ 127.0.0.1:8119 ── BLE L2CAP ──▶ relay ──TCP──▶ connect.kairo.icu:443
                 (OkHttp proxy)               (authenticated,     (allowlist:
                                               multiplexed)        *.kairo.icu:443)
```

One app (`icu.kairo.link`) is installed on both phones; each is told its role.

- **Relay (S22+)** listens on a BLE L2CAP channel and advertises its channel number. It
  advertises only while no body is connected (fast for 2 minutes after a drop, then slow), plus
  a long-range (LE Coded) advertisement so the body can find it at the edge of range.
- **Body (A16)** scans with a controller-side filter, connects, authenticates and exposes a
  loopback HTTP `CONNECT` proxy. In **Automatic** mode it uses the A16's own Wi-Fi when that
  reaches the internet, otherwise the relay. The A16's cellular is never used.
- **Power:** an idle link sends nothing — no keepalives, no polling. The connection idles at the
  low-power interval (120 ms, peripheral latency 2), jumps to 15 ms while traffic flows and drops
  back after 2.5 s of quiet. Signal strength is checked every 15 s to step between the 2M PHY
  and long-range Coded S2/S8.
- **Security:** the phones share a 16-character pairing code (80 bits) shown on the relay; a
  mutual HMAC challenge proves it before any stream opens. The relay dials only
  `kairo.icu` / `*.kairo.icu` on port 443, so it can't be used as an open proxy. Kairo's TLS is
  end to end — the relay only ever sees ciphertext.

## Measured (2026-11-07, phones a few metres apart, about −40 dBm)

On-device self-test through the link (A16's own Wi-Fi bypassed):

| | Result |
|---|---|
| First reply after the link idled | ~360 ms |
| Back-to-back round trip | 30–90 ms |
| Body → relay (snapshots, voice) | 50–110 KB/s — a 30 KB snapshot in ~0.6 s once the link is awake |
| Relay → body (replies) | ~30 KB/s |
| Real HTTPS to `connect.kairo.icu` | 200 OK, TLS + headers in ~0.9 s |
| Relay restarted | body back on the link ~2 s after the relay returns |

Weak signal costs a lot: at −67 to −85 dBm, early runs saw 2–10 KB/s. Range testing with the
car is still to do.

## Hooking up the Kairo body app

The A16 body app (`novexai/android/a16-body`) uses OkHttp, so it only needs a proxy selector.
If Kairo-Link isn't running, the connection to 127.0.0.1:8119 is refused and OkHttp falls
back to a direct connection on its own.

```kotlin
// net/KairoLinkProxy.kt
object KairoLinkProxy : java.net.ProxySelector() {
    private val link = java.net.Proxy(java.net.Proxy.Type.HTTP,
        java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 8119))
    override fun select(uri: java.net.URI?): List<java.net.Proxy> =
        if (uri?.host?.let { it == "kairo.icu" || it.endsWith(".kairo.icu") } == true)
            listOf(link, java.net.Proxy.NO_PROXY) else listOf(java.net.Proxy.NO_PROXY)
    override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, e: java.io.IOException?) {}
}
```

Then add `.proxySelector(KairoLinkProxy)` to the three `OkHttpClient.Builder()`s in
`BodyLink.kt`, `SensoryLink.kt` and `voice/AgentConnection.kt`.

## Build, install, set up

```bash
JAVA_HOME=/usr/lib/jvm/temurin-21-jdk ANDROID_HOME=$HOME/Android/Sdk ./gradlew :core:test :app:assembleDebug :app:lintDebug
adb -s RELAY install -r app/build/outputs/apk/debug/app-debug.apk
adb -s BODY  install -r app/build/outputs/apk/debug/app-debug.apk
```

On the S22+: open Kairo-Link → *Shares its internet (relay)* → **Start**, then note the code.
On the A16: *Is Kairo's body* → type the code → **Start**. On both, tap
*Let Kairo-Link run in the background*. The link restarts itself after reboots and updates.

Debug builds can also be set up over adb:

```bash
adb -s RELAY shell am start -n icu.kairo.link/.MainActivity --es role relay --es code XXXX-XXXX-XXXX-XXXX --ez start true
adb -s BODY  shell am start -n icu.kairo.link/.MainActivity --es role body  --es code XXXX-XXXX-XXXX-XXXX --ez start true
adb -s BODY  shell am start -n icu.kairo.link/.MainActivity --ez selftest true   # report: logcat -s KairoLink
```

(Debug builds accept those extras from any app on the phone; ship a release build for real use.)

## Layout

- `core/` — plain-JVM protocol: framing, handshake, per-stream flow-controlled mux, relay
  session, CONNECT proxy, diagnostics and self-test. `./gradlew :core:test` runs the whole stack
  over a loopback socket standing in for Bluetooth.
- `app/` — Android: `RelayEngine` (advertise + L2CAP accept), `BodyEngine` (scan, connect,
  interval/PHY management, proxies), `LinkService` (foreground service), `MainActivity`.
- `scripts/linktest.py` — the same measurements from a workstation through `adb forward`
  (numbers include adb's own Wi-Fi hop, which is slow — prefer the on-device self-test).

See [docs/DESIGN.md](docs/DESIGN.md) for the protocol and the reasoning behind it.
