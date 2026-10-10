#!/usr/bin/env bash
# Battery cost of a DeviceLink scenario on one Android phone (procedure: docs/validation-kit.md#battery).
#
#   scripts/android_battery.sh start   <serial>                     # reset stats, record level
#   (unplug USB / disable charging, run the scenario for >= 60 minutes, reconnect)
#   scripts/android_battery.sh collect <serial> <package> <label>   # save stats + summary
#
# Output goes to artifacts/device-tests/battery/<label>-<timestamp>/ (git-ignored). Nothing here
# reads clipboard content; batterystats holds power, wakelock, network and job counters only.
set -euo pipefail

command=${1:?start|collect}
serial=${2:?adb serial}
adb_() { adb -s "$serial" "$@"; }
root=$(cd "$(dirname "$0")/.." && pwd)
state="$root/artifacts/device-tests/battery/.start-$serial"

case "$command" in
  start)
    mkdir -p "$(dirname "$state")"
    adb_ shell dumpsys batterystats --reset >/dev/null
    adb_ shell dumpsys batterystats --enable full-wake-history >/dev/null || true
    level=$(adb_ shell dumpsys battery | awk -F': ' '/ level:/ {print $2}' | tr -d '\r')
    printf '%s %s\n' "$(date +%s)" "$level" > "$state"
    echo "Reset at level $level%. Disconnect USB now (or 'adb shell dumpsys battery unplug' for estimates only)."
    ;;
  collect)
    package=${3:?package, e.g. dev.devicelink.receiver}
    label=${4:?label, e.g. receiver-on-idle}
    [ -f "$state" ] || { echo "Run '$0 start $serial' first." >&2; exit 1; }
    read -r started start_level < "$state"
    out="$root/artifacts/device-tests/battery/$label-$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$out"
    adb_ shell dumpsys battery reset >/dev/null || true
    adb_ shell dumpsys battery > "$out/battery.txt"
    adb_ shell dumpsys batterystats > "$out/batterystats.txt"
    adb_ shell dumpsys batterystats "$package" > "$out/batterystats-$package.txt"
    adb_ shell dumpsys deviceidle > "$out/deviceidle.txt" || true
    adb_ shell getprop ro.build.fingerprint > "$out/build.txt"
    uid=$(adb_ shell cmd package list packages -U "$package" | sed -n "s/.*package:$package uid:\([0-9]*\).*/\1/p" | tr -d '\r' | head -1)
    end_level=$(awk -F': ' '/ level:/ {print $2}' "$out/battery.txt" | tr -d '\r')
    minutes=$(( ($(date +%s) - started) / 60 ))
    {
      echo "label: $label"
      echo "package: $package (uid ${uid:-unknown})"
      echo "duration_minutes: $minutes"
      echo "battery_level: $start_level% -> $end_level%"
      echo "--- estimated power for the app uid (mAh) ---"
      if [ -n "${uid:-}" ]; then
        app_uid="u0a$(( uid % 100000 - 10000 ))"
        grep -E "^\s+(UID )?$app_uid[: ]" "$out/batterystats.txt" | head -5 || true
      fi
      echo "--- wakelocks / wakeups / network for the package ---"
      grep -Ei "wake lock|wakeup alarm|Mobile network|Wi-Fi network|Foreground service|Job " "$out/batterystats-$package.txt" | head -40 || true
    } > "$out/summary.txt"
    rm -f "$state"
    cat "$out/summary.txt"
    echo "Saved to ${out#$root/}"
    ;;
  *) echo "usage: $0 start <serial> | collect <serial> <package> <label>" >&2; exit 2 ;;
esac
