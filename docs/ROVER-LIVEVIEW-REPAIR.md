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
received fixes and regression coverage, and follow-up review found no remaining
important blockers. The first test run failed on the absent worker;
the final app compile also required an explicit self-referential property type.

## A16 installation and verification

Source `9261172` is pushed on main. Updated the SM-S166V in place to version0.1.1,
code2; the installed and built APK SHA256 both equal
`08c89e8000d25c02602140b622f1c656e6972663854e1573d13e90952c270142`.
The signing certificate matches the previous APK, which was saved outside the
repository for rollback. Pairing and tether settings were retained.

The old app's screenshot showed Connected, the rover address and a black panel.
After the update, a screenshot shows the actual room image and Live camera.
Leaving Rover closed its camera TCP connection (no remaining matching connection
in the sampled socket list); returning reauthenticated and displayed actual live
frames again. Private screenshots remain outside the repository. No driving input
was sent; the status continued to report motors locked and deadman trips0.
Connection-failure/EOF recovery is covered by the core regressions; no forced
radio outage or access-point roaming recovery is claimed from this device check.

## Scope and rollback

This release repairs stream lifecycle; it does not establish or repair the cause
of intermittent LAN/ADB forwarding across the bridged access points. No rover
firmware, Wi-Fi credentials, motor commissioning or server state is changed.
App update preserves the enrolled tether and rover pairing preferences. Reverting
this commit and installing an APK built from the reverted main restores the old
client; preserve preferences and never uninstall as a rollback shortcut.
