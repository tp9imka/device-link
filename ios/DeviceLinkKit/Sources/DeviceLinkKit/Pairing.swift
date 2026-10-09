import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// One-time QR invitation. The secret lives only in the URL fragment, never sent to the relay.
public struct PairingInvite: Sendable {
    public let relayURL: String
    let secret: Data
    public let inviterId: String

    public var pairingId: String { Encoding.hex(Encoding.hkdf(secret, info: "DeviceLink/pairing-id/v2", length: 16)) }
    var joinKey: SymmetricKey { SymmetricKey(data: Encoding.hkdf(secret, info: "DeviceLink/pairing-join/v2", length: 32)) }
    var confirmKey: SymmetricKey { SymmetricKey(data: Encoding.hkdf(secret, info: "DeviceLink/pairing-confirm/v2", length: 32)) }

    var fragment: String { "v2.\(Encoding.b64url(secret)).\(Encoding.b64url((try? Encoding.unhex(inviterId)) ?? Data()))" }

    /// QR content: an https link handled by the app or forwarded by the relay landing page.
    public var uri: String { "\(relayURL)/pair#\(fragment)" }
    public var appURI: String {
        "devicelink://pair?relay=\(relayURL.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "")#\(fragment)"
    }

    public static func create(relayURL: String, inviterId: String) throws -> PairingInvite {
        guard Envelope.isHex64(inviterId) else { throw DeviceLinkError.invalid("inviter id") }
        return PairingInvite(relayURL: try RelayURL.normalize(relayURL, allowInsecure: true), secret: Encoding.randomBytes(32), inviterId: inviterId)
    }

    /// Accepts the https QR form and the devicelink:// form; nil for anything else.
    public static func parse(_ text: String, allowInsecure: Bool = false) -> PairingInvite? {
        guard let components = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              let fragment = components.percentEncodedFragment else { return nil }
        let relay: String
        switch components.scheme {
        case "devicelink":
            guard components.host == "pair", let value = components.queryItems?.first(where: { $0.name == "relay" })?.value else { return nil }
            relay = value
        case "https", "http":
            guard components.percentEncodedPath == "/pair", components.percentEncodedQuery == nil, let host = components.host else { return nil }
            relay = "\(components.scheme!)://\(host)" + (components.port.map { ":\($0)" } ?? "")
        default:
            return nil
        }
        let parts = fragment.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 3, parts[0] == "v2", let secret = try? Encoding.unb64url(parts[1], expectedBytes: 32),
              let inviter = try? Encoding.unb64url(parts[2], expectedBytes: 32),
              let normalized = try? RelayURL.normalize(relay, allowInsecure: allowInsecure) else { return nil }
        return PairingInvite(relayURL: normalized, secret: secret, inviterId: Encoding.hex(inviter))
    }

    func aad(_ label: String) -> Data { Data("DeviceLink/pairing/v2|\(pairingId)|\(label)".utf8) }

    func seal(_ key: SymmetricKey, _ label: String, _ bundle: KeyBundle) throws -> String {
        let box = try AES.GCM.seal(try bundle.encode(), using: key, nonce: AES.GCM.Nonce(), authenticating: aad(label))
        guard let combined = box.combined else { throw DeviceLinkError.invalid("seal") }
        return Encoding.b64(combined)
    }

    func open(_ key: SymmetricKey, _ label: String, _ sealed: String) throws -> KeyBundle {
        let data = try Encoding.unb64(sealed, maxBytes: 32 * 1024)
        guard data.count > 28 else { throw DeviceLinkError.invalid("sealed pairing data") }
        let plaintext = try AES.GCM.open(try AES.GCM.SealedBox(combined: data), using: key, authenticating: aad(label))
        return try KeyBundle.decode(plaintext)
    }

    /// Six-digit code both screens show after linking (see the protocol document).
    public func confirmationCode(joinerId: String) -> String {
        let bytes = Encoding.hkdf(secret, info: "DeviceLink/pairing-code/v2|\(inviterId)|\(joinerId)", length: 4)
        let value = bytes.reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        let code = String(value % 1_000_000)
        return String(repeating: "0", count: 6 - code.count) + code
    }

    public func sealJoin(_ bundle: KeyBundle) throws -> String { try seal(joinKey, "join", bundle) }
    public func openJoin(_ sealed: String) throws -> KeyBundle { try open(joinKey, "join", sealed) }
    public func sealConfirm(_ bundle: KeyBundle) throws -> String { try seal(confirmKey, "confirm", bundle) }
    public func openConfirm(_ sealed: String) throws -> KeyBundle { try open(confirmKey, "confirm", sealed) }
}

/// Admin-issued setup link: relay URL plus optional enrollment token, for the first device.
public struct SetupLink: Equatable, Sendable {
    public let relayURL: String
    public let enrollmentToken: String

    public init(relayURL: String, enrollmentToken: String) {
        self.relayURL = relayURL
        self.enrollmentToken = enrollmentToken
    }

    public var uri: String { "\(relayURL)/setup#v2.\(Encoding.b64url(Data(enrollmentToken.utf8)))" }

    public static func parse(_ text: String, allowInsecure: Bool = false) -> SetupLink? {
        guard let components = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              let fragment = components.percentEncodedFragment, fragment.hasPrefix("v2.") else { return nil }
        let relay: String
        switch components.scheme {
        case "devicelink":
            guard components.host == "setup", let value = components.queryItems?.first(where: { $0.name == "relay" })?.value else { return nil }
            relay = value
        case "https", "http":
            guard components.percentEncodedPath == "/setup", components.percentEncodedQuery == nil, let host = components.host else { return nil }
            relay = "\(components.scheme!)://\(host)" + (components.port.map { ":\($0)" } ?? "")
        default:
            return nil
        }
        let encoded = String(fragment.dropFirst(3))
        guard encoded.count <= 700, let tokenData = encoded.isEmpty ? Data() : try? Encoding.unb64url(encoded),
              let token = String(data: tokenData, encoding: .utf8), !token.unicodeScalars.contains(where: { $0.properties.generalCategory == .control }),
              let normalized = try? RelayURL.normalize(relay, allowInsecure: allowInsecure) else { return nil }
        return SetupLink(relayURL: normalized, enrollmentToken: token)
    }
}

public enum RelayURL {
    /// `https://host[:port]` only; plain http for loopback/private LAN hosts when allowed (debug builds).
    public static func normalize(_ raw: String, allowInsecure: Bool) throws -> String {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        while text.hasSuffix("/") { text.removeLast() }
        guard let components = URLComponents(string: text), let scheme = components.scheme?.lowercased(),
              let host = components.host?.lowercased(), !host.isEmpty,
              components.user == nil, components.password == nil, components.percentEncodedQuery == nil,
              components.percentEncodedFragment == nil, components.percentEncodedPath.isEmpty else {
            throw DeviceLinkError.invalid("relay URL")
        }
        guard scheme == "https" || (allowInsecure && scheme == "http" && isPrivate(host)) else { throw DeviceLinkError.invalid("relay URL must use HTTPS") }
        return "\(scheme)://\(host)" + (components.port.map { ":\($0)" } ?? "")
    }

    static func isPrivate(_ host: String) -> Bool {
        if host == "localhost" || host == "10.0.2.2" { return true }
        let parts = host.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4 else { return false }
        return parts[0] == 127 || parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && (16...31).contains(parts[1]))
    }
}
