import Foundation

public enum Limits {
    public static let maxTextBytes = 64 * 1024
    public static let maxContentBytes = 10 * 1024 * 1024
    public static let maxCiphertextBytes = 16 * 1024 * 1024
    public static let maxEnvelopeLifetimeMillis: Int64 = 3_600_000
    public static let defaultLifetimeMillis: Int64 = 5 * 60_000
    public static let maxClockSkewMillis: Int64 = 60_000
    public static let maxNameUTF16 = 48
    public static let maxFileNameUTF16 = 120
    public static let imageMimes: Set<String> = ["image/png", "image/jpeg", "image/webp", "image/gif", "image/heic"]
}

enum Wire {
    static func encoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes, .sortedKeys]
        return encoder
    }
    static let decoder = JSONDecoder()
}

/// Signed public keys a device shares with its peers during pairing.
public struct KeyBundle: Codable, Equatable, Sendable {
    public var v: Int = 2
    public var identityKey: String
    public var encryptionKey: String
    public var name: String
    public var platform: String
    public var signature: String = ""

    public var id: String { (try? Identity.deviceId(identityKeyDER())) ?? "" }
    public func identityKeyDER() throws -> Data { try Encoding.unb64(identityKey, maxBytes: 128) }
    public func encryptionKeyRaw() throws -> Data {
        let raw = try Encoding.unb64(encryptionKey, maxBytes: 32)
        guard raw.count == 32 else { throw DeviceLinkError.invalid("encryption key") }
        return raw
    }

    public func signedBytes() throws -> Data {
        var canonical = Encoding.Canonical()
        canonical.field("DeviceLink/key-bundle/v2")
        canonical.field(try identityKeyDER())
        canonical.field(try encryptionKeyRaw())
        canonical.field(name)
        canonical.field(platform)
        return canonical.data
    }

    public func validate() throws {
        guard v == 2 else { throw DeviceLinkError.invalid("bundle version") }
        _ = try Identity.publicKey(identityKeyDER())
        _ = try encryptionKeyRaw()
        guard Encoding.validDisplayText(name, maxUTF16: Limits.maxNameUTF16) else { throw DeviceLinkError.invalid("device name") }
        guard platform.range(of: "^[a-z]{1,16}$", options: .regularExpression) != nil else { throw DeviceLinkError.invalid("platform") }
    }

    public var isVerified: Bool {
        guard (try? validate()) != nil, let der = try? identityKeyDER(), let bytes = try? signedBytes(),
              let signature = try? Encoding.unb64(signature, maxBytes: 80) else { return false }
        return Identity.verify(der, data: bytes, signature: signature)
    }

    public func encode() throws -> Data { try Wire.encoder().encode(self) }

    public static func create(identity: DeviceIdentity, encryption: EncryptionKeyPair, name: String, platform: String) throws -> KeyBundle {
        var bundle = KeyBundle(identityKey: Encoding.b64(identity.publicKeyDER), encryptionKey: Encoding.b64(encryption.publicKey),
                               name: String(name.trimmingCharacters(in: .whitespacesAndNewlines).utf16.prefix(Limits.maxNameUTF16)) ?? "Device",
                               platform: platform)
        try bundle.validate()
        bundle.signature = Encoding.b64(try identity.sign(try bundle.signedBytes()))
        guard bundle.isVerified else { throw DeviceLinkError.invalid("signer does not match identity") }
        return bundle
    }

    public static func decode(_ data: Data) throws -> KeyBundle {
        guard data.count <= 16 * 1024 else { throw DeviceLinkError.invalid("bundle size") }
        let bundle = try Wire.decoder.decode(KeyBundle.self, from: data)
        guard bundle.isVerified else { throw DeviceLinkError.invalid("key bundle signature") }
        return bundle
    }
}

/// Public routing metadata stored by the relay.
public struct Envelope: Codable, Equatable, Sendable {
    public var version: Int = 2
    public var id: String
    public var senderId: String
    public var recipientId: String
    public var createdAt: Int64
    public var expiresAt: Int64
    public var sequence: Int64
    public var ciphertext: String
    public var signature: String

    public func context() -> Data {
        var canonical = Encoding.Canonical()
        canonical.field("DeviceLink/envelope-context/v2")
        canonical.u32(UInt32(version))
        canonical.field(id); canonical.field(senderId); canonical.field(recipientId)
        canonical.i64(createdAt); canonical.i64(expiresAt); canonical.i64(sequence)
        return canonical.data
    }

    public func signedBytes() throws -> Data {
        var canonical = Encoding.Canonical()
        canonical.field("DeviceLink/envelope/v2")
        canonical.field(context())
        canonical.field(try Encoding.unb64(ciphertext, maxBytes: Limits.maxCiphertextBytes))
        return canonical.data
    }

    static func isHex64(_ value: String) -> Bool { value.count == 64 && value.allSatisfy { "0123456789abcdef".contains($0) } }

    public func validate() throws {
        guard version == 2, UUID(uuidString: id)?.uuidString.lowercased() == id else { throw DeviceLinkError.invalid("envelope id") }
        guard Self.isHex64(senderId), Self.isHex64(recipientId), senderId != recipientId else { throw DeviceLinkError.invalid("routing") }
        guard sequence > 0, createdAt >= 0, expiresAt > createdAt, expiresAt - createdAt <= Limits.maxEnvelopeLifetimeMillis
        else { throw DeviceLinkError.invalid("envelope metadata") }
        guard try Encoding.unb64(ciphertext, maxBytes: Limits.maxCiphertextBytes).count >= 48,
              try Encoding.unb64(signature, maxBytes: 80).count >= 8 else { throw DeviceLinkError.invalid("envelope content") }
    }

    public func encode() throws -> Data { try validate(); return try Wire.encoder().encode(self) }

    public static func decode(_ data: Data) throws -> Envelope {
        let envelope = try Wire.decoder.decode(Envelope.self, from: data)
        try envelope.validate()
        return envelope
    }

    public static func decodeList(_ data: Data) throws -> [Envelope] {
        let items = try Wire.decoder.decode([Envelope].self, from: data)
        try items.forEach { try $0.validate() }
        return items
    }
}

public enum PayloadKind: String, Codable, Sendable { case text, image, file, receipt, unlink }
public enum ReceiptStatus: String, Codable, Sendable { case copied, delivered, failed }

/// Every field is end-to-end encrypted.
public struct Payload: Codable, Equatable, Sendable {
    public var kind: PayloadKind
    public var text: String?
    public var name: String?
    public var mime: String?
    public var data: String?
    public var receiptFor: String?
    public var status: ReceiptStatus?
    public var sentAt: Int64?

    public init(kind: PayloadKind, text: String? = nil, name: String? = nil, mime: String? = nil, data: String? = nil,
                receiptFor: String? = nil, status: ReceiptStatus? = nil, sentAt: Int64? = nil) {
        self.kind = kind; self.text = text; self.name = name; self.mime = mime; self.data = data
        self.receiptFor = receiptFor; self.status = status; self.sentAt = sentAt
    }

    public func dataBytes() throws -> Data? { try data.map { try Encoding.unb64($0, maxBytes: Limits.maxContentBytes) } }

    public static func validFileName(_ name: String) -> Bool {
        Encoding.validDisplayText(name, maxUTF16: Limits.maxFileNameUTF16) && !name.contains("/") && !name.contains("\\") && name != "." && name != ".."
    }

    public func validate() throws {
        func require(_ condition: Bool, _ reason: String) throws { if !condition { throw DeviceLinkError.invalid(reason) } }
        switch kind {
        case .text:
            try require(text.map { (1...Limits.maxTextBytes).contains($0.utf8.count) } ?? false, "text")
            try require(name == nil && mime == nil && data == nil && receiptFor == nil && status == nil, "text fields")
        case .image, .file:
            try require(text == nil && receiptFor == nil && status == nil, "file fields")
            try require(name.map(Self.validFileName) ?? false, "file name")
            try require(mime.map { $0.count <= 127 && $0.range(of: "^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$", options: .regularExpression) != nil } ?? false, "MIME type")
            try require(!(try dataBytes() ?? Data()).isEmpty, "content")
            if kind == .image { try require(Limits.imageMimes.contains(mime ?? ""), "image type") }
        case .receipt:
            try require(receiptFor.map { UUID(uuidString: $0)?.uuidString.lowercased() == $0 } ?? false && status != nil, "receipt")
            try require(text == nil && name == nil && mime == nil && data == nil, "receipt fields")
        case .unlink:
            try require(text == nil && name == nil && mime == nil && data == nil && receiptFor == nil && status == nil, "unlink fields")
        }
    }

    public func encode() throws -> Data { try validate(); return try Wire.encoder().encode(self) }
    public static func decode(_ data: Data) throws -> Payload {
        let payload = try Wire.decoder.decode(Payload.self, from: data)
        try payload.validate()
        return payload
    }

    public static func text(_ text: String, now: Int64) -> Payload { Payload(kind: .text, text: text, sentAt: now) }
    public static func image(name: String, mime: String, bytes: Data, now: Int64) -> Payload {
        Payload(kind: .image, name: name, mime: mime, data: Encoding.b64(bytes), sentAt: now)
    }
    public static func file(name: String, mime: String, bytes: Data, now: Int64) -> Payload {
        Payload(kind: .file, name: name, mime: mime, data: Encoding.b64(bytes), sentAt: now)
    }
    public static func receipt(_ id: String, _ status: ReceiptStatus) -> Payload { Payload(kind: .receipt, receiptFor: id, status: status) }
    public static func unlink() -> Payload { Payload(kind: .unlink) }
}

/// Seals and opens envelopes between pinned key bundles.
public struct EnvelopeCrypto: Sendable {
    let identity: DeviceIdentity
    let encryption: EncryptionKeyPair
    public var deviceId: String { identity.deviceId }

    public init(identity: DeviceIdentity, encryption: EncryptionKeyPair) {
        self.identity = identity
        self.encryption = encryption
    }

    public func seal(_ payload: Payload, to recipient: KeyBundle, now: Int64, sequence: Int64,
                     lifetimeMillis: Int64 = Limits.defaultLifetimeMillis, id: String = UUID().uuidString.lowercased()) throws -> Envelope {
        guard recipient.isVerified else { throw DeviceLinkError.invalid("recipient bundle") }
        guard (1...Limits.maxEnvelopeLifetimeMillis).contains(lifetimeMillis) else { throw DeviceLinkError.invalid("lifetime") }
        let plaintext = try payload.encode()
        var envelope = Envelope(id: id, senderId: deviceId, recipientId: recipient.id, createdAt: now,
                                expiresAt: now + lifetimeMillis, sequence: sequence, ciphertext: "", signature: "")
        envelope.ciphertext = Encoding.b64(try HPKESeal.seal(recipientPublicKey: try recipient.encryptionKeyRaw(),
                                                             info: envelope.context(), plaintext: plaintext))
        envelope.signature = Encoding.b64(try identity.sign(try envelope.signedBytes()))
        try envelope.validate()
        return envelope
    }

    /// Authenticates against the pinned sender bundle before decrypting.
    public func open(_ envelope: Envelope, from sender: KeyBundle, now: Int64) throws -> Payload {
        try envelope.validate()
        guard envelope.senderId == sender.id, envelope.recipientId == deviceId else { throw DeviceLinkError.invalid("routing") }
        guard envelope.expiresAt > now else { throw DeviceLinkError.invalid("expired") }
        guard envelope.createdAt - now <= Limits.maxClockSkewMillis else { throw DeviceLinkError.invalid("from the future") }
        guard Identity.verify(try sender.identityKeyDER(), data: try envelope.signedBytes(),
                              signature: try Encoding.unb64(envelope.signature, maxBytes: 80)) else { throw DeviceLinkError.invalid("signature") }
        let plaintext = try HPKESeal.open(recipient: encryption, info: envelope.context(),
                                          sealed: try Encoding.unb64(envelope.ciphertext, maxBytes: Limits.maxCiphertextBytes))
        return try Payload.decode(plaintext)
    }
}
