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

    static func read(_ account: String) -> Data? {
        var query = query(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        return SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess ? result as? Data : nil
    }

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

    static func identity() throws -> DeviceIdentity {
        if SecureEnclave.isAvailable {
            if let blob = Keychain.read("identity.se") { return SecureEnclaveIdentity(key: try SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: blob)) }
            let key = try SecureEnclave.P256.Signing.PrivateKey()
            try Keychain.write("identity.se", key.dataRepresentation)
            return SecureEnclaveIdentity(key: key)
        }
        // Simulator: software key in the keychain.
        if let raw = Keychain.read("identity.sw") { return try SoftwareIdentity(rawRepresentation: raw) }
        let identity = SoftwareIdentity()
        try Keychain.write("identity.sw", identity.privateKey.rawRepresentation)
        return identity
    }

    static func encryptionKey() throws -> EncryptionKeyPair {
        if let raw = Keychain.read("encryption.x25519") { return try EncryptionKeyPair(rawRepresentation: raw) }
        let pair = EncryptionKeyPair()
        try Keychain.write("encryption.x25519", pair.privateKey.rawRepresentation)
        return pair
    }

    @MainActor static var deviceName: String {
        let name = UIDevice.current.name
        return name.isEmpty ? UIDevice.current.model : name
    }

    @MainActor static func makeClient() throws -> DeviceLinkClient {
        #if DEBUG
        let insecure = true
        #else
        let insecure = info("DLRelayScheme") == "http"
        #endif
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        return try DeviceLinkClient(
            identity: identity(), encryption: encryptionKey(),
            store: FileLinkStore(url: container.appendingPathComponent("DeviceLink/links.json")),
            config: LinkConfig(deviceName: deviceName, platform: "ios", model: model, appVersion: version,
                               allowInsecureRelay: insecure, defaultRelayURL: defaultRelayURL,
                               defaultEnrollmentToken: info("DLEnrollmentToken")))
    }
}
