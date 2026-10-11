import CryptoKit
import Foundation

/// Decides whether a pasteboard change notification is a new copy worth sending.
///
/// iOS can post `UIPasteboard.changedNotification` more than once for one copy (one per batch of
/// added or removed types, same `changeCount`), and some copy paths write in two steps. Sending on
/// every notification sent the same clip twice. A change is sent once per `changeCount`, and the
/// same content is not sent again within `window` seconds.
struct CopyFilter {
    var window: TimeInterval = 2
    private var lastChangeCount: Int?
    private var lastDigest: SHA256.Digest?
    private var lastSentAt: Date?

    /// `ownChangeCount`: the change DeviceLink made itself (a received clip), which must not echo back.
    mutating func shouldSend(changeCount: Int, ownChangeCount: Int, content: Data, now: Date = Date()) -> Bool {
        guard changeCount != ownChangeCount, changeCount != lastChangeCount else { return false }
        lastChangeCount = changeCount
        let digest = SHA256.hash(data: content)
        if digest == lastDigest, let at = lastSentAt, now.timeIntervalSince(at) < window { return false }
        lastDigest = digest
        lastSentAt = now
        return true
    }
}
