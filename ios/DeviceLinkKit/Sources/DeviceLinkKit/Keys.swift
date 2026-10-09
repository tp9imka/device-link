import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// A device's long-term P-256 signing identity. The iOS app keeps it in the Secure Enclave
/// (`SecureEnclaveIdentity`); tests and the CLI use `SoftwareIdentity`.
public protocol DeviceIdentity: Sendable {
    /// X.509 SubjectPublicKeyInfo DER of the P-256 public key.
    var publicKeyDER: Data { get }
    /// DER ECDSA-SHA256 signature.
    func sign(_ data: Data) throws -> Data
}

public extension DeviceIdentity {
    var deviceId: String { Identity.deviceId(publicKeyDER) }
}

public enum Identity {
    public static func deviceId(_ publicKeyDER: Data) -> String { Encoding.hex(Encoding.sha256(publicKeyDER)) }

    public static func publicKey(_ der: Data) throws -> P256.Signing.PublicKey {
        guard (64...128).contains(der.count), let key = try? P256.Signing.PublicKey(derRepresentation: der),
              key.derRepresentation == der else { throw DeviceLinkError.invalid("identity key") }
        return key
    }

    public static func verify(_ publicKeyDER: Data, data: Data, signature: Data) -> Bool {
        guard (8...80).contains(signature.count), let key = try? publicKey(publicKeyDER),
              let parsed = try? P256.Signing.ECDSASignature(derRepresentation: signature) else { return false }
        return key.isValidSignature(parsed, for: data)
    }
}

public struct SoftwareIdentity: DeviceIdentity {
    public let privateKey: P256.Signing.PrivateKey

    public init(privateKey: P256.Signing.PrivateKey = P256.Signing.PrivateKey()) { self.privateKey = privateKey }
    public init(rawRepresentation: Data) throws { privateKey = try P256.Signing.PrivateKey(rawRepresentation: rawRepresentation) }

    public var publicKeyDER: Data { privateKey.publicKey.derRepresentation }
    public func sign(_ data: Data) throws -> Data { try privateKey.signature(for: data).derRepresentation }
}

/// Raw X25519 key pair used as the HPKE recipient key.
public struct EncryptionKeyPair: Sendable {
    public let privateKey: Curve25519.KeyAgreement.PrivateKey
    public init(privateKey: Curve25519.KeyAgreement.PrivateKey = Curve25519.KeyAgreement.PrivateKey()) { self.privateKey = privateKey }
    public init(rawRepresentation: Data) throws { privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: rawRepresentation) }
    public var publicKey: Data { privateKey.publicKey.rawRepresentation }
}

/// RFC 9180 base mode, DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / ChaCha20-Poly1305, single shot,
/// info = envelope context, empty AAD. Output `enc || ciphertext` (matches Tink NO_PREFIX).
public enum HPKESeal {
    static let suite = HPKE.Ciphersuite.Curve25519_SHA256_ChachaPoly

    public static func seal(recipientPublicKey: Data, info: Data, plaintext: Data) throws -> Data {
        let key = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: recipientPublicKey)
        var sender = try HPKE.Sender(recipientKey: key, ciphersuite: suite, info: info)
        let ciphertext = try sender.seal(plaintext)
        return sender.encapsulatedKey + ciphertext
    }

    public static func open(recipient: EncryptionKeyPair, info: Data, sealed: Data, aad: Data = Data()) throws -> Data {
        guard sealed.count >= 32 + 16 else { throw DeviceLinkError.invalid("HPKE ciphertext") }
        let start = sealed.startIndex
        var receiver = try HPKE.Recipient(privateKey: recipient.privateKey, ciphersuite: suite, info: info,
                                          encapsulatedKey: sealed[start..<start + 32])
        return try receiver.open(sealed[(start + 32)...], authenticating: aad)
    }
}
