# Rover preview recovery — 2026-11-18

## Observed failure

The A16 on 0.1.0 authenticated to the rover and displayed its Wi-Fi address but
kept a black camera panel. A device screenshot reproduced that state. The rover
camera HTTP listener answered 401 without a session token once reachable; the
phone also established a TCP connection to the camera port. These observations
do not prove image delivery or decoding.

The old Activity made one MJPEG request, silently swallowed failure/EOF, retained
the URL and never retried while that URL stayed unchanged. Interrupting its worker
did not close a blocked HTTP connection. URL equality also admitted queued frames
from an earlier visit to the same URL.

## Repair and qualification

The screen owns a reconnecting stream with an epoch for each visit. Pause stops
the worker and permanently cancels its HTTP call; a late BLE URL while paused
cannot restart it. Closed or superseded GATT callbacks are ignored. The UI shows
connecting, retrying or live camera state; transport exceptions and credential-
bearing URLs are never logged. Driving commands, pairing proof and the existing
single-controller protocol are unchanged.

OkHttp cancellation replaces HttpURLConnection because a real loopback test
proved that disconnect-before-input could otherwise open a new socket later.
The regression failed on that transport and passed with a permanently cancelled
Call. Other regressions exercise connection failure, EOF, interrupt-resistant
blocked reads, same-URL stale callbacks and late URLs after pause.

Qualification: 48 core tests passed, debug APK assembled, Android lint passed.
Independent review found the late-URL and pre-start cancellation races; both
received fixes and regression coverage. In-device preview/recovery verification
is pending at this source commit. The first test run failed on the absent worker;
the final app compile also required an explicit self-referential property type.

## Scope and rollback

This release repairs stream lifecycle; it does not establish or repair the cause
of intermittent LAN/ADB forwarding across the bridged access points. No rover
firmware, Wi-Fi credentials, motor commissioning or server state is changed.
App update preserves the enrolled tether and rover pairing preferences. Reverting
this commit and installing an APK built from the reverted main restores the old
client; preserve preferences and never uninstall as a rollback shortcut.
