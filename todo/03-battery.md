# 03 · Battery measurement

## Goal

Measure what the Android receiver's foreground-service long poll costs on battery, against the
target of **≤ 2 % per 8 h above baseline** while idle, and record the method and numbers.

## Context

- The receiver keeps a `remoteMessaging` foreground service with a 25 s long poll
  (`LinkConfig.pollWaitSeconds`, `sdk/core/.../DeviceLinkClient.kt`) while it is on.
  Off means no polling at all.
- Script: `scripts/android_battery.sh` (start / collect). Procedure: `docs/validation-kit.md#battery`.
- iOS has no background polling (APNs only), so only a 24 h Settings → Battery reading is needed.

## Steps

1. Charge to ~90 %, disconnect USB (USB power invalidates drain numbers; use `adb tcpip` if needed,
   or collect only after reconnecting).
2. For each scenario (`baseline`, `receiver-idle`, `receiver-traffic`), three runs of ≥ 60 min,
   screen off, same Wi-Fi, no other foreground apps:
   `scripts/android_battery.sh start <serial>` → run → reconnect →
   `scripts/android_battery.sh collect <serial> dev.devicelink.receiver <scenario>`.
3. Repeat `receiver-idle` on cellular.
4. Put a table into `docs/validation.md`: device, OS, network, scenario, minutes, level delta,
   app mAh, wakelock time, mobile/Wi-Fi bytes. Extrapolate to 8 h, state the variance.

## If over target (hand to spec 05, don't tune during measurement)

Options in order: raise `pollWaitSeconds` towards 50 (relay `max_poll_wait`; Cloudflare idle limit
is 100 s); longer backoff after network errors; stop polling while the device is in deep Doze and
resume on `ACTION_DEVICE_IDLE_MODE_CHANGED` (latency trade-off, must be visible to the user).
Do not drop the visible foreground service or poll while "off".

## Done when

Numbers for all scenarios × 3 runs are recorded with the method, and a pass/fail against the target.
