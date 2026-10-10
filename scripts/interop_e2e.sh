#!/usr/bin/env bash
# Cross-platform live test: Python relay + Kotlin SDK + Swift SDK (CLI) pairing by QR link and exchanging
# text, an image and receipts in both directions. Synthetic data only.
#
#   scripts/interop_e2e.sh
#
# Overrides: PYTHON (relay interpreter), SWIFT_CLI (command running the `devicelink` executable),
# KOTLIN_TEST (command running sdk/core's InteropPeerTest), RELAY_PORT.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
port="${RELAY_PORT:-8790}"
token="interop-$(date +%s)"
PYTHON="${PYTHON:-python3}"
SWIFT_CLI="${SWIFT_CLI:-swift run --package-path $root/ios/DeviceLinkKit devicelink}"
KOTLIN_TEST="${KOTLIN_TEST:-$root/gradlew -p $root :sdk:core:test --tests dev.devicelink.sdk.core.InteropPeerTest --no-daemon -q}"
fail() { echo "FAILED: $*"; for f in received.jsonl kotlin.log relay.log; do [ -f "$work/$f" ] && { echo "--- $f"; tail -40 "$work/$f"; }; done; exit 1; }
cleanup() { kill "${relay_pid:-}" "${kotlin_pid:-}" 2>/dev/null || true; rm -rf "$work"; }
trap cleanup EXIT

(cd "$root/server" && RELAY_DATABASE="$work/relay.sqlite3" RELAY_PORT="$port" RELAY_ENROLLMENT_TOKEN="$token" \
  exec "$PYTHON" -m relay) > "$work/relay.log" 2>&1 &
relay_pid=$!
for _ in $(seq 50); do curl -fs "http://127.0.0.1:$port/health" >/dev/null && break; sleep 0.2; done

export DEVICELINK_INTEROP_DIR="$work" DEVICELINK_RELAY_URL="http://127.0.0.1:$port" DEVICELINK_TOKEN="$token"
$KOTLIN_TEST > "$work/kotlin.log" 2>&1 &
kotlin_pid=$!
for _ in $(seq 600); do [ -s "$work/invite.txt" ] && break; sleep 0.5; done
[ -s "$work/invite.txt" ] || { cat "$work/kotlin.log"; echo "Kotlin never showed a code"; exit 1; }

cli() { $SWIFT_CLI --state "$work/swift" --insecure --name "Swift CLI" "$@"; }
echo "Swift joins the Kotlin code (no setup, no token)"
cli join "$(cat "$work/invite.txt")" --timeout 90
cli send "hello from swift"
cli receive --count 2 --timeout 90 > "$work/received.jsonl" || fail "Swift did not receive two items"
grep -q '"text":"hello from kotlin ✓"' "$work/received.jsonl" || fail "text mismatch"
grep -q "\"sha256\":\"$(cat "$work/image.sha256")\"" "$work/received.jsonl" || fail "image mismatch"
for _ in $(seq 120); do [ -f "$work/phase1.done" ] && break; sleep 0.5; done

echo "Kotlin joins the Swift code"
cli invite > "$work/swift-invite.txt" &
invite_pid=$!
wait "$invite_pid"
cli receive --count 1 --timeout 60 > "$work/received.jsonl" || fail "Swift did not receive from the second device"
grep -q '"text":"joined your code"' "$work/received.jsonl" || fail "second device text mismatch"
wait "$kotlin_pid" || fail "Kotlin side failed"
echo "Interop OK: Kotlin <-> Swift through the relay, both directions"
