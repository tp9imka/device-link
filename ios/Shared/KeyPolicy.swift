import Foundation

// Platform-free: compiled into the apps, the extensions and DeviceLinkAppTests (macOS).

/// One keychain read: the bytes, no such item, or a failure to read (locked, access denied).
enum StoredKey: Equatable {
    case found(Data)
    case missing
    case unreadable
}

/// This device's keys are missing or unreadable. Linked peers pinned the old ones, so nothing new is
/// made: the user resets and links again. The message is user-facing; nothing in it is secret.
struct KeysLostError: Error, Equatable {
    enum Key: String { case identity = "device identity", encryption = "encryption key" }
    enum Reason: String { case missing, unreadable }
    let key: Key
    let reason: Reason

    static let message = "This device's keys could not be read. Reset and link again."
    /// For the diagnostics report.
    var summary: String { "\(key.rawValue) \(reason.rawValue)" }
}

/// When a stored key may be replaced. Peers pin this device's identity and encryption key at linking,
/// so a fresh key while links exist would leave both sides showing "Linked" while every item fails to
/// decrypt or verify. A fresh key is only made on a device without links; an item that exists but could
/// not be read is never overwritten (a locked keychain recovers by itself).
enum KeyPolicy {
    /// What the stored key turned out to be.
    enum Read: Equatable { case readable, missing, corrupt, unreadable }
    enum Outcome: Equatable { case use, create, lost(KeysLostError.Reason) }

    static func decide(_ read: Read, hasLinks: Bool) -> Outcome {
        switch read {
        case .readable: return .use
        case .missing: return hasLinks ? .lost(.missing) : .create
        case .corrupt: return hasLinks ? .lost(.unreadable) : .create
        case .unreadable: return .lost(.unreadable)
        }
    }

    /// Decodes `stored`, or creates a new key and hands its bytes to `store` when `decide` allows it.
    /// `store` is not called otherwise, so a lost key is never overwritten.
    static func load<Key>(_ stored: StoredKey, as key: KeysLostError.Key, hasLinks: Bool,
                          decode: (Data) throws -> Key, create: () throws -> (Key, Data),
                          store: (Data) throws -> Void) throws -> Key {
        let read: Read
        var decoded: Key?
        switch stored {
        case .found(let data):
            decoded = try? decode(data)
            read = decoded == nil ? .corrupt : .readable
        case .missing: read = .missing
        case .unreadable: read = .unreadable
        }
        switch decide(read, hasLinks: hasLinks) {
        case .use: return decoded!
        case .create:
            let (made, data) = try create()
            try store(data)
            return made
        case .lost(let reason): throw KeysLostError(key: key, reason: reason)
        }
    }
}
