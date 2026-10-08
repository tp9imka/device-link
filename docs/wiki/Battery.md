# Battery and sessions

DeviceLink favors deliberate, temporary availability. Pairing information remains
stored while transport is off; remembering a device does not require scanning.

| State | Resources |
| --- | --- |
| Off | No discovery, listening socket, connection, session timer or wake lock |
| Searching | Bounded Wi-Fi Direct discovery; user-visible session notification |
| Connecting | Discovery stops; connection/handshake has a deadline |
| Connected idle | One socket waits for input; no clipboard polling |
| Transferring | File IO and a bounded CPU wake lock; progress updates are throttled |
| Stopped | Timers/jobs cancelled, streams/sockets closed, discovery/group removed |

The default session is 15 minutes; Settings offers 5 and 30 minutes. Expiry does
not cut off a transfer already in progress, but blocks new work. Explicit stop
cancels immediately. The app does not keep the display awake and does not request
a blanket battery-optimization exemption.

## Measuring, not guessing

Measure the same physical device and OS build with matched conditions:
1. Baseline with DeviceLink off.
2. Active session while searching with no peer.
3. Paired idle session with screens off.
4. Fixed-size transfer in both directions.
5. Explicit stop and process death, verifying no retained service/socket/wake lock.

Record duration, transferred bytes, CPU time, network activity, wake-lock time,
thermal state and battery charge counter where available. USB charging makes
battery percentage an unsuitable drain measurement. Use a controlled unplugged
run or power instrumentation for credible mAh/hour numbers.

See the [validation report](https://git.oryxlabs.internal/ivan-antsimonau/device-link/blob/main/docs/validation.md) in the code repository for actual evidence. No standby
drain percentage or all-day battery claim is made by this initial version.
