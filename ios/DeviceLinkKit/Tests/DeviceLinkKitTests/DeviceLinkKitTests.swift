import Foundation
import XCTest
@testable import DeviceLinkKit
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

final class ContractTests: XCTestCase {
    struct Device {
        let identity = SoftwareIdentity()
        let keys = EncryptionKeyPair()
        let bundle: KeyBundle
        let crypto: EnvelopeCrypto
        init(_ name: String) throws {
            bundle = try KeyBundle.create(identity: identity, encryption: keys, name: name, platform: "ios")
            crypto = EnvelopeCrypto(identity: identity, encryption: keys)
        }
    }

    let now: Int64 = 1_800_000_000_000

    func testHPKEMatchesRFC9180VectorA21() throws {
        let recipient = try EncryptionKeyPair(rawRepresentation: try Encoding.unhex("8057991eef8f1f1af18f4a9491d16a1ce333f695d4db8e38da75975c4478e0fb"))
        let sealed = try Encoding.unhex("1afa08d3dec047a643885163f1180476fa7ddb54c6a8029ea33f95796bf2ac4a" +
                                        "1c5250d8034ec2b784ba2cfd69dbdb8af406cfe3ff938e131f0def8c8b60b4db21993c62ce81883d2dd1b51a28")
        let plaintext = try HPKESeal.open(recipient: recipient, info: try Encoding.unhex("4f6465206f6e2061204772656369616e2055726e"),
                                          sealed: sealed, aad: try Encoding.unhex("436f756e742d30"))
        XCTAssertEqual(String(data: plaintext, encoding: .utf8), "Beauty is truth, truth beauty")
    }

    func testHKDFMatchesRFC5869Case3() throws {
        XCTAssertEqual(Encoding.hex(Encoding.hkdf(try Encoding.unhex(String(repeating: "0b", count: 22)), info: "", length: 42)),
                       "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8")
    }

    func testBundleSignatureCoversEveryField() throws {
        let alice = try Device("Alice"), bob = try Device("Bob")
        XCTAssertTrue(alice.bundle.isVerified)
        XCTAssertEqual(try KeyBundle.decode(try alice.bundle.encode()), alice.bundle)
        var renamed = alice.bundle; renamed.name = "Mallory"; XCTAssertFalse(renamed.isVerified)
        var moved = alice.bundle; moved.platform = "android"; XCTAssertFalse(moved.isVerified)
        var swapped = alice.bundle; swapped.encryptionKey = bob.bundle.encryptionKey; XCTAssertFalse(swapped.isVerified)
    }

    func testEnvelopeRoundTripAndRejections() throws {
        let alice = try Device("Alice"), bob = try Device("Bob"), carol = try Device("Carol")
        let payloads: [Payload] = [.text("private clip", now: now), .image(name: "a.png", mime: "image/png", bytes: Data([1, 2]), now: now),
                                   .file(name: "secret.pdf", mime: "application/pdf", bytes: Data([3]), now: now),
                                   .receipt("6f0d3c9e-6a45-4b8e-9b51-2f4c1b4e0a11", .copied), .unlink()]
        for (index, payload) in payloads.enumerated() {
            let envelope = try alice.crypto.seal(payload, to: bob.bundle, now: now, sequence: Int64(index + 1))
            let visible = String(data: try envelope.encode(), encoding: .utf8)!
            XCTAssertFalse(visible.contains("private") || visible.contains("secret") || visible.contains("image/png"))
            XCTAssertEqual(try bob.crypto.open(try Envelope.decode(Data(visible.utf8)), from: alice.bundle, now: now), payload)
        }
        let envelope = try alice.crypto.seal(.text("x", now: now), to: bob.bundle, now: now, sequence: 9)
        var changed = envelope; changed.sequence = 10
        XCTAssertThrowsError(try bob.crypto.open(changed, from: alice.bundle, now: now))
        XCTAssertThrowsError(try bob.crypto.open(envelope, from: alice.bundle, now: envelope.expiresAt))
        XCTAssertThrowsError(try carol.crypto.open(envelope, from: alice.bundle, now: now))
        var forged = try carol.crypto.seal(.text("x", now: now), to: bob.bundle, now: now, sequence: 1)
        forged.senderId = alice.bundle.id
        XCTAssertThrowsError(try bob.crypto.open(forged, from: alice.bundle, now: now))
    }

    func testPairingInviteFormsAndSealing() throws {
        let alice = try Device("Alice"), bob = try Device("Bob")
        let invite = try PairingInvite.create(relayURL: "https://relay.example", inviterId: alice.bundle.id)
        XCTAssertTrue(invite.uri.hasPrefix("https://relay.example/pair#v2."))
        for form in [invite.uri, invite.appURI] {
            let parsed = try XCTUnwrap(PairingInvite.parse(form))
            XCTAssertEqual(parsed.pairingId, invite.pairingId)
            XCTAssertEqual(parsed.inviterId, alice.bundle.id)
            XCTAssertEqual(parsed.relayURL, "https://relay.example")
            XCTAssertEqual(try parsed.openJoin(try invite.sealJoin(bob.bundle)), bob.bundle)
        }
        XCTAssertThrowsError(try invite.openConfirm(try invite.sealJoin(bob.bundle)))
        for bad in ["", "https://relay.example/pair", "https://relay.example/x#v2.a.b", "http://relay.example/pair#v2.a.b"] {
            XCTAssertNil(PairingInvite.parse(bad), bad)
        }
        XCTAssertEqual(try RelayURL.normalize("HTTPS://Relay.Example:8443/", allowInsecure: false), "https://relay.example:8443")
        XCTAssertThrowsError(try RelayURL.normalize("http://relay.example", allowInsecure: true))
        XCTAssertEqual(try RelayURL.normalize("http://127.0.0.1:8000", allowInsecure: true), "http://127.0.0.1:8000")
        let setup = SetupLink(relayURL: "https://relay.example", enrollmentToken: "s3cret token")
        XCTAssertEqual(SetupLink.parse(setup.uri), setup)
        XCTAssertEqual(SetupLink.parse("devicelink://setup?relay=https%3A%2F%2Frelay.example#v2.czNjcmV0IHRva2Vu"), setup)
    }
}

/// Cross-language vectors in docs/protocol/vectors: Kotlin writes kotlin.json, Swift writes swift.json,
/// each side must open the other's output.
final class InteropVectorTests: XCTestCase {
    static let directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("docs/protocol/vectors")

    func testOpensKotlinVectors() throws {
        let url = Self.directory.appendingPathComponent("kotlin.json")
        guard let data = try? Data(contentsOf: url) else { throw XCTSkip("kotlin.json not generated yet") }
        try VectorFile.verify(data)
    }

    func testWritesSwiftVectorsWhenRequested() throws {
        guard ProcessInfo.processInfo.environment["DEVICELINK_WRITE_VECTORS"] == "1" else { throw XCTSkip("set DEVICELINK_WRITE_VECTORS=1") }
        let data = try VectorFile.generate(producer: "swift")
        try VectorFile.verify(data)
        try FileManager.default.createDirectory(at: Self.directory, withIntermediateDirectories: true)
        try data.write(to: Self.directory.appendingPathComponent("swift.json"))
    }
}

enum VectorFile {
    struct Party: Codable { var identityPrivate: String; var encryptionPrivate: String; var bundle: KeyBundle }
    struct Item: Codable { var now: Int64; var envelope: Envelope; var payload: Payload }
    struct Pairing: Codable { var uri: String; var pairingId: String; var joinSealed: String; var confirmSealed: String }
    struct Request: Codable { var method: String; var path: String; var body: String; var headers: [String: String] }
    struct File: Codable {
        var producer: String
        var sender: Party
        var recipient: Party
        var items: [Item]
        var pairing: Pairing
        var request: Request
    }

    static func generate(producer: String) throws -> Data {
        let now: Int64 = 1_800_000_000_000
        let senderIdentity = SoftwareIdentity(), senderKeys = EncryptionKeyPair()
        let recipientIdentity = SoftwareIdentity(), recipientKeys = EncryptionKeyPair()
        let sender = try KeyBundle.create(identity: senderIdentity, encryption: senderKeys, name: "Swift sender ✓", platform: "ios")
        let recipient = try KeyBundle.create(identity: recipientIdentity, encryption: recipientKeys, name: "Recipient", platform: "android")
        let crypto = EnvelopeCrypto(identity: senderIdentity, encryption: senderKeys)
        let payloads: [Payload] = [.text("Hello from \(producer) — ünïcødé 📋", now: now),
                                   .image(name: "pixel.png", mime: "image/png", bytes: Data((0..<64).map { UInt8($0) }), now: now),
                                   .receipt("6f0d3c9e-6a45-4b8e-9b51-2f4c1b4e0a11", .copied)]
        let items = try payloads.enumerated().map { index, payload in
            Item(now: now, envelope: try crypto.seal(payload, to: recipient, now: now, sequence: Int64(index + 1)), payload: payload)
        }
        let invite = try PairingInvite.create(relayURL: "https://relay.example", inviterId: sender.id)
        let relay = RelayClient(baseURL: "https://relay.example", identity: senderIdentity, transport: URLSessionTransport(), clock: { now })
        let body = "{}"
        return try Wire.encoder().encode(File(
            producer: producer,
            sender: Party(identityPrivate: Encoding.b64(senderIdentity.privateKey.rawRepresentation),
                          encryptionPrivate: Encoding.b64(senderKeys.privateKey.rawRepresentation), bundle: sender),
            recipient: Party(identityPrivate: Encoding.b64(recipientIdentity.privateKey.rawRepresentation),
                             encryptionPrivate: Encoding.b64(recipientKeys.privateKey.rawRepresentation), bundle: recipient),
            items: items,
            pairing: Pairing(uri: invite.uri, pairingId: invite.pairingId, joinSealed: try invite.sealJoin(recipient),
                             confirmSealed: try invite.sealConfirm(sender)),
            request: Request(method: "PUT", path: "/v1/peers/\(recipient.id)", body: body,
                             headers: try relay.signatureHeaders(method: "PUT", path: "/v1/peers/\(recipient.id)", body: Data(body.utf8)))))
    }

    static func verify(_ data: Data) throws {
        let file = try Wire.decoder.decode(File.self, from: data)
        XCTAssertTrue(file.sender.bundle.isVerified)
        XCTAssertTrue(file.recipient.bundle.isVerified)
        let recipientIdentity = try SoftwareIdentity(rawRepresentation: try Encoding.unb64(file.recipient.identityPrivate, maxBytes: 32))
        XCTAssertEqual(recipientIdentity.deviceId, file.recipient.bundle.id)
        let crypto = EnvelopeCrypto(identity: recipientIdentity,
                                    encryption: try EncryptionKeyPair(rawRepresentation: try Encoding.unb64(file.recipient.encryptionPrivate, maxBytes: 32)))
        for item in file.items {
            XCTAssertEqual(try crypto.open(item.envelope, from: file.sender.bundle, now: item.now), item.payload)
        }
        let invite = try XCTUnwrap(PairingInvite.parse(file.pairing.uri))
        XCTAssertEqual(invite.pairingId, file.pairing.pairingId)
        XCTAssertEqual(try invite.openJoin(file.pairing.joinSealed), file.recipient.bundle)
        XCTAssertEqual(try invite.openConfirm(file.pairing.confirmSealed), file.sender.bundle)
        let headers = file.request.headers
        let canonical = "DeviceLink relay request v1\n\(file.request.method)\n\(file.request.path)\n\(headers["X-Device-Time"]!)\n" +
            "\(headers["X-Device-Nonce"]!)\n\(Encoding.hex(Encoding.sha256(Data(file.request.body.utf8))))"
        XCTAssertTrue(Identity.verify(try Encoding.unb64(headers["X-Device-Key"]!, maxBytes: 128), data: Data(canonical.utf8),
                                      signature: try Encoding.unb64(headers["X-Device-Signature"]!, maxBytes: 80)))
    }
}
