#!/usr/bin/env sh
# Checksum-pinned Python launcher; no Gradle wrapper JAR is bundled.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$ROOT/scripts/gradle_bootstrap.py" "$@"
