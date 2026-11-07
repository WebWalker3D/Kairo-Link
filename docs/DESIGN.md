# Kairo-Link design

## Why not a hotspot

The goal is the least power that still carries Kairo's traffic: voice clips and a few low-res
snapshots up, replies down, no video. A Wi-Fi hotspot makes the S22+ beacon about ten times a
second and never fully sleep (estimated 2–3 %/h), and every background request from the A16 wakes
the S22+'s cellular modem. A BLE connection that carries only Kairo's traffic idles at a few
hundred microwatts, so the cost lands only when Kairo actually talks.

## Transport

- **L2CAP connection-oriented channel**, not GATT. It's a credit-flow-controlled byte
  stream with large SDUs (65,535 B on these phones). GATT write/notify would cap throughput and
  need its own framing and acks.
- **No GATT server on the relay.** Samsung's stack (S22+, Android 16) kept a closed GATT
  server's service registered and routed reads to it, which stranded the body. The relay now puts
  its PSM in the advertisement's service data (`4b4c0001-…`): PSM u16 LE + protocol version u8.
  The body still opens a GATT *client* connection, only because Android ties connection
  interval, PHY and RSSI to a `BluetoothGatt` handle.
- **Insecure (unbonded) channel + app-layer authentication**, so there's no system pairing
  dialog and no bond state to go stale. Authentication: 16-char Crockford base32 code →
  SHA-256 secret → mutual HMAC-SHA256 challenge (`Handshake`). Confidentiality comes from
  Kairo's own TLS.

## Multiplexing (`core/Mux.kt`)

Frame: `type u8 | stream u16 | length u16 | payload ≤ 4096`. Types: OPEN, OPENED, DATA, CREDIT,
EOF (half close), RESET. Each stream has a 64 KB credit window; credit is returned in batches
(after 16 KB, or when the receive queue drains) so idle streams generate no frames. One stalled
consumer can't block the shared channel; there's a test for exactly that. Each stream gets two
pump threads; the volume is a handful of streams, so threads are fine.

## Power management (`BodyEngine`)

- Interval: low-power (120 ms, latency 2) when idle; HIGH (15 ms) on any frame; back after 2.5 s
  of quiet. Measured against fixed 45 ms ("balanced"), dynamic matched it on real HTTPS (0.85 s
  vs 0.89 s) and bulk transfers; the cost is ~0.3 s extra on the first byte after idle.
- **Request the PHY first, the interval second.** Issuing both at link-up collides (LL status
  0x23) and the controller silently keeps the old interval. Interval requests are retried on
  collision via the hidden `onConnectionUpdated` callback.
- PHY tiers by RSSI with hysteresis: 2M (≥ −78), Coded S2 (−78…−85), Coded S8 (< −85).
- Scanning: balanced for 60 s after a loss, then low-power; filtered in the controller.
- Advertising (relay): 250 ms for 2 minutes after a loss, then 1 s; off while connected.
- A 3 s wake lock after link traffic, throttled to one binder call per second, so a frame
  that just arrived is forwarded before the CPU sleeps.

## Routing (`ConnectProxy`)

`127.0.0.1:8119` routes by mode: **Automatic** (the A16's own validated Wi-Fi, else the relay),
**Link only**, or **Direct only**. Cellular on the A16 is deliberately never used.
`127.0.0.1:8120` always tunnels and also reaches the relay's diagnostic targets
(`echo`, `source-N`, `sink-N` under `.kairo-link.diag:1`).

## Known limits / next steps

- Relay → body tops out near 30 KB/s (S22+ is the BLE peripheral); body → relay reaches ~110 KB/s.
  That suits a body that mostly uploads. Swapping roles would flip it.
- Range with the car on the floor is unmeasured. The Coded PHY tiers are implemented but haven't
  been exercised at distance.
- Power has been reasoned from radio behaviour, not measured with a meter.
- The relay replaces a body session when a new authenticated one arrives (one body at a time).
