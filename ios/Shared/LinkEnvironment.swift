import CryptoKit
import DeviceLinkKit
import Foundation
import Security
import UIKit

/// Keychain items shared by the app and its extensions (same access group). Never synced to iCloud.
enum Keychain {
    private static let service = "dev.devicelink.keys"

    static var accessGroup: String? {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "DLKeychainGroup") as? String,
              !group.isEmpty, !group.hasPrefix("$(") else { return nil }
        return group
    }

    private static func query(_ account: String) -> [String: Any] {
        var query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecAttrAccount as String: account, kSecAttrSynchronizable as String: false]
        if let accessGroup { query[kSecAttrAccessGroup as String] = accessGroup }
        return query
    }

    /// Tells "no such item" apart from "could not read it" (for example before the first unlock).
    static func read(_ account: String) -> StoredKey {
        var query = query(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return .missing }
        guard status == errSecSuccess, let data = result as? Data else { return .unreadable }
        return .found(data)
    }

    static func delete(_ account: String) { SecItemDelete(query(account) as CFDictionary) }

    static func write(_ account: String, _ data: Data) throws {
        var item = query(account)
        item[kSecValueData as String] = data
        // Available to background work (receiver, Shortcuts) after the first unlock, never backed up.
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemDelete(query(account) as CFDictionary)
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw DeviceLinkError.invalid("keychain \(status)") }
    }
}

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

    /// Reads `account`, decodes it, or creates and stores a new key when `decide` allows it.
    static func load<Key>(_ account: String, as key: KeysLostError.Key, hasLinks: Bool,
                          decode: (Data) throws -> Key, create: () throws -> (Key, Data)) throws -> Key {
        let read: Read
        var decoded: Key?
        switch Keychain.read(account) {
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
            try Keychain.write(account, data)
            return made
        case .lost(let reason): throw KeysLostError(key: key, reason: reason)
        }
    }
}

/// P-256 identity held by the Secure Enclave; the opaque key blob is stored in the shared keychain.
struct SecureEnclaveIdentity: DeviceIdentity, @unchecked Sendable {
    let key: SecureEnclave.P256.Signing.PrivateKey
    var publicKeyDER: Data { key.publicKey.derRepresentation }
    func sign(_ data: Data) throws -> Data { try key.signature(for: data).derRepresentation }
}

/// Builds the DeviceLink client from Info.plist settings (filled from Private.xcconfig).
enum LinkEnvironment {
    static func info(_ key: String) -> String {
        guard let value = Bundle.main.object(forInfoDictionaryKey: key) as? String, !value.hasPrefix("$(") else { return "" }
        return value.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Built-in relay: the first device creates its identity and can show a code with no setup.
    static var defaultRelayURL: String {
        let host = info("DLRelayHost")
        return host.isEmpty ? "" : "\(info("DLRelayScheme").isEmpty ? "https" : info("DLRelayScheme"))://\(host)"
    }

    static var container: URL {
        let group = info("DLAppGroup")
        if !group.isEmpty, let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) { return url }
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }

    static var model: String {
        var system = utsname()
        uname(&system)
        return withUnsafeBytes(of: &system.machine) { raw in
            String(decoding: raw.prefix(while: { $0 != 0 }), as: UTF8.self)
        }
    }

    static var linksURL: URL { container.appendingPathComponent("DeviceLink/links.json") }

    /// True when devices are linked, or when the link file exists but cannot be read (data protection
    /// before the first unlock): then nobody can tell, so no key may be replaced.
    static func hasLinks() -> Bool {
        let url = linksURL
        guard FileManager.default.fileExists(atPath: url.path) else { return false }
        guard (try? Data(contentsOf: url)) != nil else { return true }
        return !FileLinkStore(url: url).load().peers.isEmpty
    }

    static func identity(hasLinks: Bool) throws -> DeviceIdentity {
        if SecureEnclave.isAvailable {
            return try KeyPolicy.load("identity.se", as: .identity, hasLinks: hasLinks,
                                      decode: { SecureEnclaveIdentity(key: try SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: $0)) },
                                      create: { let key = try SecureEnclave.P256.Signing.PrivateKey(); return (SecureEnclaveIdentity(key: key), key.dataRepresentation) })
        }
        // Simulator: software key in the keychain.
        return try KeyPolicy.load("identity.sw", as: .identity, hasLinks: hasLinks,
                                  decode: { try SoftwareIdentity(rawRepresentation: $0) },
                                  create: { let identity = SoftwareIdentity(); return (identity, identity.privateKey.rawRepresentation) })
    }

    static func encryptionKey(hasLinks: Bool) throws -> EncryptionKeyPair {
        try KeyPolicy.load("encryption.x25519", as: .encryption, hasLinks: hasLinks,
                           decode: { try EncryptionKeyPair(rawRepresentation: $0) },
                           create: { let pair = EncryptionKeyPair(); return (pair, pair.privateKey.rawRepresentation) })
    }

    @MainActor static var deviceName: String {
        let name = UIDevice.current.name
        return name.isEmpty ? UIDevice.current.model : name
    }

    @MainActor private static var current: DeviceLinkClient?

    /// The process's one client. The app model, intents, push actions and extensions all share it, so
    /// link state, sequence numbers, the seen-set and the single long poll are owned by one actor.
    @MainActor static func client() throws -> DeviceLinkClient {
        if let current { return current }
        let made = try makeClient()
        current = made
        return made
    }

    /// After "Reset device" wiped keys and links: builds the client for the new identity.
    @MainActor static func replaceClient() throws -> DeviceLinkClient {
        current = nil
        return try client()
    }

    @MainActor private static func makeClient() throws -> DeviceLinkClient {
        #if DEBUG
        let insecure = true
        #else
        let insecure = info("DLRelayScheme") == "http"
        #endif
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        let linked = hasLinks()
        return try DeviceLinkClient(
            identity: identity(hasLinks: linked), encryption: encryptionKey(hasLinks: linked),
            store: FileLinkStore(url: linksURL),
            config: LinkConfig(deviceName: deviceName, platform: "ios", model: model, appVersion: version,
                               allowInsecureRelay: insecure, defaultRelayURL: defaultRelayURL,
                               defaultEnrollmentToken: info("DLEnrollmentToken")))
    }
}
