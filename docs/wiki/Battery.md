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

These rows describe nearby transport. Internet Link is independent:

| Internet state | Resources |
| --- | --- |
| Off | No relay requests, polling or push registration |
| Connecting/active | Separate foreground notification and 15-minute deadline |
| Waiting | One signed HTTPS long poll, held up to 25 seconds by the server |
| Processing | One envelope fetched at a time; encryption/decryption and bounded file/image IO |
| Retry | Backoff grows from 1 second to at most 30 seconds |
| Finishing | No new work; active operations get up to 60 seconds to finish |
| Stopped | Active HTTP connections closed, session job cancelled, pending clipboard writes invalidated |

The default session limit is 15 minutes; Settings offers 5 and 30 minutes. Initial
discovery ends after one minute without a connection, so an unsuccessful search
does not scan for the entire session limit. Start another session to retry.
Connection and pairing have a separate two-minute deadline. Session expiry does
not cut off a transfer already in progress, but blocks new work. Explicit stop
cancels immediately. Linked clipboard delivery is event-driven and adds no
clipboard polling. A receiver callback has a 10-second timeout; the sender waits
up to 15 seconds for its clipboard-result acknowledgement. Expiry prevents a
stale clipboard write even while another transfer is finishing.
The app does not keep the display awake and does not request
a blanket battery-optimization exemption.

Internet Link does not use FCM or vendor push. A successful empty poll is followed
by another long poll, roughly one HTTP request per 25 seconds while idle; active
delivery/receipts add requests. Encrypted file offers awaiting explicit acceptance
are excluded from polls so they do not cause repeated immediate downloads. The
server waits on a notification condition rather than repeatedly reading SQLite.

Its foreground session stops accepting new work after 15 minutes. Active requests,
file operations and their clipboard/receipt callbacks get up to 60 seconds to
finish; the screen and notification show that the session is finishing. Once
those operations finish, or the grace limit is reached, connections close.
Explicit Stop cancels immediately. Accepted envelopes can stay on the server until
acknowledged or expired without keeping the sending phone awake. There is no
automatic session restart or wake-up while off, and no separate relay wake lock.
Foreground service lifetime is not a guarantee of instant delivery under Doze.

Image imports and validation incur disk and decoder work; bounded sampled decode
avoids allocating a full 40-megapixel bitmap merely for validation. Image copy is
explicit and event-driven. No background clipboard reads or change listener are
added by either transport.

## Measuring, not guessing

Measure the same physical device and OS build with matched conditions:
1. Baseline with DeviceLink off.
2. Active session while searching with no peer.
3. Paired idle session with screens off.
4. Fixed-size transfer in both directions.
5. Explicit stop and process death, verifying no retained service/socket/wake lock.
6. Internet Link idle and active with the same relay/network, automatic clipboard
   on/off, reachable/unreachable server, and foreground versus sustained Doze.
7. Repeat image-copy workloads with fixed byte/dimension fixtures, including
   cancelled imports and session expiry.

Record duration, transferred bytes, CPU time, network activity, wake-lock time,
thermal state and battery charge counter where available. USB charging makes
battery percentage an unsuitable drain measurement. Use a controlled unplugged
run or power instrumentation for credible mAh/hour numbers.

See the [validation report](https://git.oryxlabs.internal/ivan-antsimonau/device-link/blob/main/docs/validation.md) in the code repository for actual evidence. No standby
drain percentage or all-day battery claim is made by this initial version.
