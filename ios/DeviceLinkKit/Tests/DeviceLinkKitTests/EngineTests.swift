import Foundation
import XCTest
@testable import DeviceLinkKit

/// Thread-safe mutable value for @Sendable closures in tests.
final class Locked<Value>: @unchecked Sendable {
    private var value: Value
    private let lock = NSLock()
    init(_ value: Value) { self.value = value }
    var get: Value { lock.lock(); defer { lock.unlock() }; return value }
    func with<T>(_ change: (inout Value) -> T) -> T { lock.lock(); defer { lock.unlock() }; return change(&value) }
}

/// In-memory relay that verifies request signatures; Swift twin of the Kotlin test `FakeRelay`.
final class FakeRelay: HTTPTransport, @unchecked Sendable {
    struct State {
        var devices = Set<String>()
        var registrations: [DeviceMetadata] = []
        var registrationFault: (() throws -> HTTPResponse)?
        var allow = Set<String>() // "recipient>sender"
        var mailbox: [Envelope] = []
        var pairings: [String: [String: String]] = [:]
        var failAcks = 0
        var pollErrors: [(Int, String)] = []
        var failConfirm: (Int, String)?
        /// The confirm is stored, then the response is lost (answered with `failConfirm`).
        var confirmCommitsBeforeFailing = false
        /// The next confirm is stored, then the connection drops before the response arrives.
        var confirmResponseLost = false
        /// The next N `GET /v1/pairings/{id}?wait=0` (the inviter's re-read after a failed confirm) fail with a network error.
        var failPairingReReads = 0
        /// Every confirm body the relay received, in order.
        var confirms: [String] = []
        /// The next `PUT /v1/peers/…` by this device ID fails with a network error.
        var failNextAllowBy: String?
        /// Every `PUT /v1/peers/…` by this device ID is refused with this status and error code.
        var refuseAllowBy: (String, Int, String)?
        /// `PUT /v1/peers/…` attempts per calling device ID.
        var allowAttempts: [String: Int] = [:]
        var polls = 0
        /// `POST /v1/messages` attempts so far; `uploadFaults` answers (or throws for) the attempt with that 1-based number.
        var uploads = 0
        var uploadFaults: [Int: () throws -> HTTPResponse] = [:]
        /// Recipient of every envelope the relay stored, in order (survives later deletes).
        var storedFor: [String] = []
        /// The relay cannot be reached: every request fails with a connection error.
        var down = false
    }
    let state = Locked(State())
    let clock: @Sendable () -> Int64
    init(clock: @escaping @Sendable () -> Int64) { self.clock = clock }

    var mailbox: [Envelope] { state.get.mailbox }

    /// Empty mailbox polls wait out `wait`, like the real long poll (keeps receive loops from spinning).
    let longPoll = Locked(false)

    func send(method: String, url: URL, headers: [String: String], body: Data, timeout: TimeInterval) async throws -> HTTPResponse {
        if state.get.down { throw URLError(.cannotConnectToHost) }
        if method == "POST", url.path == "/v1/register" {
            let metadata = try Wire.decoder.decode(DeviceMetadata.self, from: body)
            let fault = state.with { state in
                state.registrations.append(metadata)
                return state.registrationFault
            }
            if let fault { return try fault() }
        }
        if method == "PUT", url.path.hasPrefix("/v1/peers/"), let key = try? Encoding.unb64(headers["X-Device-Key"] ?? "", maxBytes: 128) {
            let caller = Identity.deviceId(key)
            let (refusal, drop) = state.with { state -> ((Int, String)?, Bool) in
                state.allowAttempts[caller, default: 0] += 1
                if let (refused, status, code) = state.refuseAllowBy, refused == caller { return ((status, code), false) }
                guard state.failNextAllowBy == caller else { return (nil, false) }
                state.failNextAllowBy = nil
                return (nil, true)
            }
            if let (status, code) = refusal { return HTTPResponse(status: status, body: Data("{\"error\":\"\(code)\"}".utf8)) }
            if drop { throw URLError(.networkConnectionLost) }
        }
        if method == "POST", url.path == "/v1/messages",
           let fault = state.with({ state -> (() throws -> HTTPResponse)? in
               state.uploads += 1
               return state.uploadFaults.removeValue(forKey: state.uploads)
           }) {
            return try fault()
        }
        let isPairing = url.path.hasPrefix("/v1/pairings/")
        if method == "GET", isPairing, url.query == "wait=0", state.with({ state -> Bool in
            guard state.failPairingReReads > 0 else { return false }
            state.failPairingReReads -= 1
            return true
        }) {
            throw URLError(.networkConnectionLost)
        }
        let response = state.with { handle(method: method, url: url, headers: headers, body: body, state: &$0) }
        if method == "POST", isPairing, url.path.hasSuffix("/confirm"), response.status == 204 || response.status == 200,
           state.with({ state -> Bool in defer { state.confirmResponseLost = false }; return state.confirmResponseLost }) {
            throw URLError(.networkConnectionLost)
        }
        if longPoll.get, method == "GET", url.path == "/v1/messages", response.body == Data("[]".utf8),
           let wait = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "wait" })?.value.flatMap(UInt64.init) {
            try await Task.sleep(nanoseconds: wait * 1_000_000_000)
        }
        return response
    }

    private func handle(method: String, url: URL, headers: [String: String], body: Data, state: inout State) -> HTTPResponse {
        func ok(_ text: String = "{}", _ status: Int = 200) -> HTTPResponse { HTTPResponse(status: status, body: Data(text.utf8)) }
        func error(_ status: Int, _ code: String) -> HTTPResponse { ok("{\"error\":\"\(code)\"}", status) }
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        let target = components.percentEncodedPath + (components.percentEncodedQuery.map { "?\($0)" } ?? "")
        guard let key = try? Encoding.unb64(headers["X-Device-Key"] ?? "", maxBytes: 128),
              let signature = try? Encoding.unb64(headers["X-Device-Signature"] ?? "", maxBytes: 80) else { return error(401, "unsigned") }
        let canonical = "DeviceLink relay request v1\n\(method)\n\(target)\n\(headers["X-Device-Time"]!)\n\(headers["X-Device-Nonce"]!)\n\(Encoding.hex(Encoding.sha256(body)))"
        guard Identity.verify(key, data: Data(canonical.utf8), signature: signature) else { return error(401, "bad_signature") }
        let caller = Identity.deviceId(key)
        let path = components.percentEncodedPath.split(separator: "/").map(String.init)
        let query = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        let json = (try? JSONSerialization.jsonObject(with: body) as? [String: Any]) ?? [:]
        if path == ["v1", "register"] { state.devices.insert(caller); return ok() }
        if method == "DELETE", path == ["v1", "devices", "self"] {
            // Same effect as server/relay store.remove_device; idempotent for an unknown device.
            state.devices.remove(caller)
            state.allow = state.allow.filter { !$0.split(separator: ">").map(String.init).contains(caller) }
            // What it sent (its unlink notices) stays until delivered or expired.
            state.mailbox.removeAll { $0.recipientId == caller }
            state.pairings = state.pairings.filter { $0.value["creator"] != caller && $0.value["joiner"] != caller }
            return ok("", 204)
        }
        guard state.devices.contains(caller) else { return error(403, "registration_required") }
        switch (method, path.count, path.count > 1 ? path[1] : "") {
        case ("POST", 2, "pairings"):
            state.pairings[json["id"] as! String] = ["creator": caller, "state": "open"]
            return ok()
        case ("POST", 4, "pairings") where path[3] == "join":
            guard var pairing = state.pairings[path[2]] else { return error(404, "pairing_not_found") }
            guard pairing["state"] == "open" else { return error(409, "pairing_used") }
            pairing["state"] = "joined"; pairing["joiner"] = caller; pairing["joinSealed"] = json["sealed"] as? String
            state.pairings[path[2]] = pairing
            return ok()
        case ("POST", 4, "pairings") where path[3] == "confirm":
            if let (status, code) = state.failConfirm, !state.confirmCommitsBeforeFailing { return error(status, code) }
            let sealed = json["sealed"] as? String
            state.confirms.append(sealed ?? "")
            guard var pairing = state.pairings[path[2]], pairing["creator"] == caller else { return error(404, "pairing_not_found") }
            // Same as server/relay store.confirm_pairing: a resend of the same confirm is idempotent.
            if pairing["state"] == "confirmed", pairing["confirmSealed"] == sealed { return ok() }
            guard pairing["state"] == "joined" else { return error(409, "pairing_not_joined") }
            pairing["state"] = "confirmed"; pairing["confirmSealed"] = sealed
            state.pairings[path[2]] = pairing
            state.allow.insert("\(caller)>\(pairing["joiner"]!)")
            if let (status, code) = state.failConfirm { return error(status, code) }
            return ok()
        case ("GET", 3, "pairings"):
            guard let p = state.pairings[path[2]] else { return error(404, "pairing_not_found") }
            var result: [String: Any] = ["state": p["state"]!]
            if let joiner = p["joiner"] { result["joinerId"] = joiner }
            if let sealed = caller == p["creator"] ? p["joinSealed"] : p["confirmSealed"] { result["sealed"] = sealed }
            return ok(String(data: try! JSONSerialization.data(withJSONObject: result), encoding: .utf8)!)
        case ("DELETE", 3, "pairings"):
            state.pairings[path[2]] = nil
            return ok()
        case (_, 3, "peers"):
            if method == "PUT" { state.allow.insert("\(caller)>\(path[2])") } else { state.allow.remove("\(caller)>\(path[2])") }
            return ok("", 204)
        case ("POST", 2, "messages"):
            guard let envelope = try? Envelope.decode(body) else { return error(400, "invalid_envelope") }
            guard envelope.senderId == caller else { return error(403, "sender_mismatch") }
            guard state.allow.contains("\(envelope.recipientId)>\(caller)") else { return error(403, "recipient_not_authorized") }
            state.mailbox.append(envelope)
            state.storedFor.append(envelope.recipientId)
            return ok("{}", 201)
        case ("GET", 2, "messages"):
            state.polls += 1
            if !state.pollErrors.isEmpty { let (status, code) = state.pollErrors.removeFirst(); return error(status, code) }
            let now = clock()
            state.mailbox.removeAll { $0.expiresAt <= now }
            let excluded = Set((query["exclude"] ?? "").split(separator: ",").map(String.init))
            precondition(excluded.count <= 20, "relay caps exclude at 20")
            // Same order as server/relay/store.py: sender, sequence DESC, id.
            let mine = state.mailbox.filter { $0.recipientId == caller && !excluded.contains($0.id) }
                .sorted { ($0.senderId, -$0.sequence, $0.id) < ($1.senderId, -$1.sequence, $1.id) }
                .prefix(Int(query["limit"] ?? "20") ?? 20)
            return HTTPResponse(status: 200, body: try! Wire.encoder().encode(Array(mine)))
        case ("GET", 3, "messages"):
            guard let envelope = state.mailbox.first(where: { $0.id == path[2] && $0.recipientId == caller }) else { return error(404, "message_not_found") }
            return HTTPResponse(status: 200, body: try! envelope.encode())
        case ("DELETE", 3, "messages"):
            if state.failAcks > 0 { state.failAcks -= 1; return error(503, "unavailable") }
            state.mailbox.removeAll { $0.id == path[2] && $0.recipientId == caller }
            return ok("", 204)
        default:
            return error(404, "not_found")
        }
    }
}

/// Link file whose saves can be made to fail, standing in for a process that dies right after applying.
final class FlakyStore: LinkStore, ChunkSpoolLocation, @unchecked Sendable {
    let inner: FileLinkStore
    let failing = Locked(false)
    init(_ inner: FileLinkStore) { self.inner = inner }
    var chunkDirectory: URL { inner.chunkDirectory }
    func load() -> LinkState { inner.load() }
    func save(_ state: LinkState) throws {
        if failing.get { throw DeviceLinkError.invalid("process died") }
        try inner.save(state)
    }
}

final class EngineTests: XCTestCase {
    let now = Locked<Int64>(1_800_000_000_000)
    lazy var relay = FakeRelay { [now] in now.get }

    func client(_ name: String, store: LinkStore = MemoryLinkStore(), identity: DeviceIdentity = SoftwareIdentity(),
                keys: EncryptionKeyPair = EncryptionKeyPair()) throws -> DeviceLinkClient {
        try DeviceLinkClient(identity: identity, encryption: keys, store: store, config: LinkConfig(deviceName: name),
                             transport: relay, clock: { [now] in now.get })
    }

    /// A device whose process can be "restarted": same keys, same link file, fresh engine.
    final class Restartable {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let identity = SoftwareIdentity()
        let keys = EncryptionKeyPair()
        var file: URL { directory.appendingPathComponent("links.json") }
        var spooled: [URL] {
            (try? FileManager.default.contentsOfDirectory(at: directory.appendingPathComponent("links.json.chunks"), includingPropertiesForKeys: nil)) ?? []
        }
        deinit { try? FileManager.default.removeItem(at: directory) }
    }

    func start(_ device: Restartable, store: LinkStore? = nil) throws -> DeviceLinkClient {
        try client("Desk", store: store ?? FileLinkStore(url: device.file), identity: device.identity, keys: device.keys)
    }

    func metadataClient(_ device: Restartable, _ metadata: DeviceMetadata) throws -> DeviceLinkClient {
        try DeviceLinkClient(identity: device.identity, encryption: device.keys, store: FileLinkStore(url: device.file),
                             config: LinkConfig(deviceName: "Phone", platform: metadata.platform, model: metadata.model,
                                                appVersion: metadata.appVersion),
                             transport: relay, clock: { [now] in now.get })
    }

    func testMetadataRefreshesOncePerChangeAndSurvivesEngineRestart() async throws {
        let device = Restartable()
        let original = DeviceMetadata(platform: "ios", model: "Phone", appVersion: "1.0")
        let first = try metadataClient(device, original)
        try await first.configure(relayURL: "https://relay.example")
        try await first.ensureRegistered()
        let store = FileLinkStore(url: device.file)
        for metadata in [original, DeviceMetadata(platform: "ios", model: "Phone", appVersion: "2.0.0"),
                         DeviceMetadata(platform: "ios", model: "Tablet", appVersion: "2.0.0"),
                         DeviceMetadata(platform: "desktop", model: "Tablet", appVersion: "2.0.0")] {
            let before = relay.state.get.registrations.count
            let changed = store.load().registeredMetadata != metadata
            let engine = try metadataClient(device, metadata)
            let registered = await engine.currentDiagnostics().registered
            XCTAssertTrue(registered)
            for _ in 0..<2 {
                let count = try await engine.receiveOnce(wait: 0) { _ in XCTFail("empty mailbox"); return .failed }
                XCTAssertEqual(count, 0)
            }
            let restarted = try metadataClient(device, metadata)
            try await restarted.receiveOnce(wait: 0) { _ in XCTFail("empty mailbox"); return .failed }
            XCTAssertEqual(relay.state.get.registrations.count, before + (changed ? 1 : 0))
            XCTAssertEqual(relay.state.get.registrations.last, metadata)
            XCTAssertEqual(store.load().registeredMetadata, metadata)
        }
    }

    func testLegacyRegistrationWithoutMetadataRefreshesOnce() async throws {
        let device = Restartable()
        let metadata = DeviceMetadata(platform: "ios", model: "Phone", appVersion: "2.0.0")
        let engine = try metadataClient(device, metadata)
        try await engine.configure(relayURL: "https://relay.example")
        try await engine.ensureRegistered()
        let store = FileLinkStore(url: device.file)
        try store.update { $0.registeredMetadata = nil }
        for _ in 0..<2 { try await engine.receiveOnce(wait: 0) { _ in XCTFail("empty mailbox"); return .failed } }
        XCTAssertEqual(relay.state.get.registrations.count, 2)
        XCTAssertEqual(store.load().registeredMetadata, metadata)
    }

    func testRelayRejectionOfMetadataRefreshPreservesCallsAndIsNotRetried() async throws {
        let device = Restartable()
        let old = DeviceMetadata(platform: "ios", model: "Phone", appVersion: "1.0")
        let first = try metadataClient(device, old)
        try await first.configure(relayURL: "https://relay.example")
        try await first.ensureRegistered()
        let metadata = DeviceMetadata(platform: "ios", model: "Phone", appVersion: "2.0.0")
        relay.state.with { $0.registrationFault = { HTTPResponse(status: 403, body: Data(#"{"error":"enrollment_required"}"#.utf8)) } }
        let updated = try metadataClient(device, metadata)
        for _ in 0..<2 {
            let count = try await updated.receiveOnce(wait: 0) { _ in XCTFail("empty mailbox"); return .failed }
            XCTAssertEqual(count, 0)
        }
        let restarted = try metadataClient(device, metadata)
        try await restarted.receiveOnce(wait: 0) { _ in XCTFail("empty mailbox"); return .failed }
        XCTAssertEqual(relay.state.get.registrations.count, 2)
        XCTAssertEqual(relay.state.get.polls, 3)
        XCTAssertEqual(FileLinkStore(url: device.file).load().registeredMetadata, metadata)
        let registered = await updated.currentDiagnostics().registered
        XCTAssertTrue(registered)
    }

    func testTransportFailureLeavesMetadataRefreshPending() async throws {
        let device = Restartable()
        let old = DeviceMetadata(platform: "ios", model: "Phone", appVersion: "1.0")
        let first = try metadataClient(device, old)
        try await first.configure(relayURL: "https://relay.example")
        try await first.ensureRegistered()
        let metadata = DeviceMetadata(platform: "ios", model: "Phone", appVersion: "2.0.0")
        let updated = try metadataClient(device, metadata)
        relay.state.with { $0.registrationFault = { throw URLError(.networkConnectionLost) } }
        do {
            try await updated.receiveOnce(wait: 0) { _ in XCTFail("empty mailbox"); return .failed }
            XCTFail("expected transport failure")
        } catch is URLError { }
        XCTAssertEqual(FileLinkStore(url: device.file).load().registeredMetadata, old)
        relay.state.with { $0.registrationFault = nil }
        for _ in 0..<2 {
            let count = try await updated.receiveOnce(wait: 0) { _ in XCTFail("empty mailbox"); return .failed }
            XCTAssertEqual(count, 0)
        }
        XCTAssertEqual(relay.state.get.registrations.count, 3)
        XCTAssertEqual(FileLinkStore(url: device.file).load().registeredMetadata, metadata)
    }

    func testEnrollmentFailuresStillPropagateForFirstForcedPairingAndForgottenRegistrations() async throws {
        for mode in ["first", "forced", "pairing", "forgotten"] {
            relay.state.with { $0.registrationFault = nil }
            let device = Restartable()
            let metadata = DeviceMetadata(platform: "ios", model: "Phone", appVersion: "2.0.0")
            let engine = try metadataClient(device, metadata)
            try await engine.configure(relayURL: "https://relay.example")
            if mode != "first" { try await engine.ensureRegistered() }
            if mode == "forgotten" { relay.state.with { $0.devices.removeAll() } }
            let before = FileLinkStore(url: device.file).load()
            relay.state.with { $0.registrationFault = { HTTPResponse(status: 403, body: Data(#"{"error":"enrollment_required"}"#.utf8)) } }
            do {
                switch mode {
                case "forced": try await engine.ensureRegistered(force: true)
                case "pairing": try await engine.ensureRegistered(pairingId: String(repeating: "a", count: 32))
                default: try await engine.receiveOnce(wait: 0) { _ in XCTFail("empty mailbox"); return .failed }
                }
                XCTFail("expected enrollment failure")
            } catch DeviceLinkError.relay(403, "enrollment_required") { }
            XCTAssertEqual(FileLinkStore(url: device.file).load(), before)
        }
    }

    @discardableResult
    func link(_ inviter: DeviceLinkClient, _ joiner: DeviceLinkClient) async throws -> LinkedPeer {
        try await inviter.configure(relayURL: "https://relay.example")
        let session = try await inviter.invite()
        async let waiting = inviter.awaitPeer(session)
        _ = try await joiner.join(session.uri)
        return try await waiting
    }

    func bigFile() -> Data { Data((0..<(Limits.maxContentBytes + 3 * 1024 * 1024)).map { UInt8(truncatingIfNeeded: $0 &* 31) }) }

    func receipts(_ client: DeviceLinkClient) async throws -> [String] {
        let stream = await client.events()
        let collected = Locked<[String]>([])
        let task = Task { for await event in stream { if case .receipt(let id, _, _) = event { collected.with { $0.append(id) } } } }
        try await client.receiveOnce(wait: 0) { _ in XCTFail("no content expected"); return .failed }
        try await Task.sleep(nanoseconds: 50_000_000)
        task.cancel()
        return collected.get
    }

    func testRestartMidTransferResumesChunkedFileAndReceiptsIt() async throws {
        let android = try client("Pixel"), desk = Restartable()
        let before = try start(desk)
        try await link(android, before)
        let file = bigFile()
        let sent = try await android.send(.file(name: "big.bin", mime: "application/octet-stream", data: file))[0]
        for _ in 0..<2 { _ = try await before.receiveOnce(wait: 0) { _ in XCTFail("incomplete file applied"); return .failed } }

        let after = try start(desk)
        let items = Locked<[IncomingItem]>([])
        // A chunked file is read from the spool, so only inside the handler.
        let contents = Locked<[Data?]>([])
        for _ in 0..<2 { try await after.receiveOnce(wait: 0) { item in items.with { $0.append(item) }; contents.with { $0.append(item.bytes()) }; return .delivered } }
        XCTAssertEqual(items.get.map(\.id), [sent.itemId!])
        XCTAssertEqual(contents.get.first ?? nil, file)
        let ids = try await receipts(android)
        XCTAssertEqual(ids, [sent.itemId!])
        XCTAssertTrue(desk.spooled.isEmpty, "spool is cleaned after apply")
    }

    func testFileWhoseApplyWasNotRecordedIsAppliedOnRestart() async throws {
        let android = try client("Pixel"), desk = Restartable()
        let flaky = FlakyStore(FileLinkStore(url: desk.file))
        let before = try start(desk, store: flaky)
        try await link(android, before)
        let file = bigFile()
        _ = try await android.send(.file(name: "big.bin", mime: "application/octet-stream", data: file))
        for _ in 0..<3 { _ = try await before.receiveOnce(wait: 0) { _ in .failed } }
        _ = try? await before.receiveOnce(wait: 0) { [flaky] _ in flaky.failing.with { $0 = true }; return .delivered }
        XCTAssertTrue(relay.mailbox.filter { $0.recipientId == before.deviceId }.isEmpty)

        let items = Locked<[IncomingItem]>([])
        // A chunked file is read from the spool, so only inside the handler.
        let contents = Locked<[Data?]>([])
        try await start(desk).receiveOnce(wait: 0) { item in items.with { $0.append(item) }; contents.with { $0.append(item.bytes()) }; return .delivered }
        XCTAssertEqual(contents.get.first ?? nil, file)
        let again = try await start(desk).receiveOnce(wait: 0) { _ in XCTFail("applied twice"); return .failed }
        XCTAssertEqual(again, 0)
    }

    func testItemWhoseApplyWasNotRecordedIsDeliveredAgain() async throws {
        let android = try client("Pixel"), desk = Restartable()
        let flaky = FlakyStore(FileLinkStore(url: desk.file))
        try await link(android, try start(desk, store: flaky))
        _ = try await android.send(.text("survives"))
        _ = try? await start(desk, store: flaky).receiveOnce(wait: 0) { [flaky] _ in flaky.failing.with { $0 = true }; return .copied }
        flaky.failing.with { $0 = false }
        let texts = Locked<[String?]>([])
        try await start(desk).receiveOnce(wait: 0) { item in texts.with { $0.append(item.text) }; return .copied }
        XCTAssertEqual(texts.get, ["survives"])
    }

    func testFailedAcknowledgementStillSendsReceiptAndNeverAppliesTwice() async throws {
        let android = try client("Pixel"), iphone = try client("iPhone")
        try await link(android, iphone)
        let sent = try await android.send(.text("ack me"))[0]
        relay.state.with { $0.failAcks = 1 }
        let calls = Locked(0)
        let first = try await iphone.receiveOnce(wait: 0) { _ in calls.with { $0 += 1 }; return .copied }
        XCTAssertEqual(first, 1)
        let ids = try await receipts(android)
        XCTAssertEqual(ids, [sent.itemId!])
        let second = try await iphone.receiveOnce(wait: 0) { _ in calls.with { $0 += 1 }; return .copied }
        XCTAssertEqual(second, 0)
        XCTAssertEqual(calls.get, 1)
        XCTAssertTrue(relay.mailbox.filter { $0.recipientId == iphone.deviceId }.isEmpty)
    }

    func testPollCollisionAfterReconnectRetriesWithinSeconds() async throws {
        let android = try client("Pixel"), iphone = try client("iPhone")
        try await link(android, iphone)
        relay.longPoll.with { $0 = true }
        _ = try await android.send(.text("after collision"))
        relay.state.with { $0.pollErrors = [(429, "poll_already_active")] }
        let started = Date()
        let received = expectation(description: "item after the collision")
        let loop = Task { await iphone.runReceiver { item in
            if item.text == "after collision" { received.fulfill() }
            return .copied
        } }
        await fulfillment(of: [received], timeout: 10)
        loop.cancel()
        XCTAssertLessThan(Date().timeIntervalSince(started), 10, "a collision must not take the 60 s rate-limit backoff")
    }

    func testDrainAppliesEveryItemEvenWhenAReceiptIsServedFirst() async throws {
        let android = try client("Pixel"), iphone = try client("iPhone")
        try await link(android, iphone)
        _ = try await iphone.send(.text("two"))
        _ = try await iphone.send(.text("three"))
        _ = try await android.send(.text("x"))
        try await iphone.receiveOnce(wait: 0) { _ in .copied } // its receipt is iPhone's newest envelope for Android
        let texts = Locked<[String?]>([])
        let result = try await android.drain { item in texts.with { $0.append(item.text) }; return .delivered }
        XCTAssertEqual(result, DrainResult(applied: 2, complete: true))
        XCTAssertEqual(texts.get, ["three", "two"])
        XCTAssertTrue(relay.mailbox.filter { $0.recipientId == android.deviceId }.isEmpty)
    }

    func testDrainStopsOnAFailedAcknowledgementInsteadOfLooping() async throws {
        let android = try client("Pixel"), iphone = try client("iPhone")
        try await link(android, iphone)
        _ = try await android.send(.text("once"))
        relay.state.with { $0.failAcks = 5 }
        let calls = Locked(0)
        let result = try await iphone.drain { _ in calls.with { $0 += 1 }; return .copied }
        XCTAssertEqual(result, DrainResult(applied: 1, complete: true))
        XCTAssertEqual(calls.get, 1)
    }

    func testDrainReportsAPollCollisionInsteadOfFailing() async throws {
        let android = try client("Pixel"), iphone = try client("iPhone")
        try await link(android, iphone)
        _ = try await android.send(.text("waiting"))
        relay.state.with { $0.pollErrors = [(429, "poll_already_active")] }
        let first = try await iphone.drain { _ in .copied }
        XCTAssertEqual(first, DrainResult(applied: 0, complete: false, collided: true))
        let second = try await iphone.drain { _ in .copied }
        XCTAssertEqual(second.applied, 1)
    }

    func testFailedConfirmLeavesNeitherSideLinked() async throws {
        let android = try client("Pixel"), iphone = try client("iPhone")
        try await android.configure(relayURL: "https://relay.example")
        let session = try await android.invite()
        relay.state.with { $0.failConfirm = (503, "unavailable") }
        await android.setRetryDelays([1, 1, 1]) // the confirm is resent before the inviter gives up
        let events = await android.events()
        let linkedEvents = Locked(0)
        let collector = Task { for await event in events { if case .peerLinked = event { linkedEvents.with { $0 += 1 } } } }
        let joiner = Task { _ = try? await iphone.join(session.uri) }
        do {
            _ = try await android.awaitPeer(session)
            XCTFail("confirm failed, awaitPeer must throw")
        } catch DeviceLinkError.pairing {}
        joiner.cancel(); collector.cancel()
        let inviterPeers = await android.peers, joinerPeers = await iphone.peers
        XCTAssertTrue(inviterPeers.isEmpty)
        XCTAssertTrue(joinerPeers.isEmpty)
        XCTAssertFalse(relay.state.get.allow.contains("\(android.deviceId)>\(iphone.deviceId)"))
        XCTAssertEqual(linkedEvents.get, 0)
    }

    func testConfirmWhoseResponseWasLostStillLinksBothSides() async throws {
        let android = try client("Pixel"), iphone = try client("iPhone")
        relay.state.with { $0.failConfirm = (503, "unavailable"); $0.confirmCommitsBeforeFailing = true }
        try await link(android, iphone)
        let inviterPeers = await android.peers, joinerPeers = await iphone.peers
        XCTAssertEqual(inviterPeers.map(\.id), [iphone.deviceId])
        XCTAssertEqual(joinerPeers.map(\.id), [android.deviceId])
    }

    func testLostConfirmResponseEndsLinkedOnBothSidesEvenWhenTheReReadFailsToo() async throws {
        let android = try client("Pixel"), iphone = try client("iPhone")
        relay.state.with { $0.confirmResponseLost = true; $0.failPairingReReads = 1 }
        await android.setRetryDelays([1, 1, 1])
        try await link(android, iphone)
        let inviterPeers = await android.peers, joinerPeers = await iphone.peers
        XCTAssertEqual(inviterPeers.map(\.id), [iphone.deviceId])
        XCTAssertEqual(joinerPeers.map(\.id), [android.deviceId])
        let allow = relay.state.get.allow
        XCTAssertTrue(allow.contains("\(android.deviceId)>\(iphone.deviceId)") && allow.contains("\(iphone.deviceId)>\(android.deviceId)"))
        // The resend carried the very same sealed confirm, which the relay accepts as idempotent.
        let confirms = relay.state.get.confirms
        XCTAssertEqual(confirms.count, 2)
        XCTAssertEqual(Set(confirms).count, 1)
    }

    func testNetworkErrorOnJoinersAllowAfterConfirmIsRetried() async throws {
        let android = try client("Pixel"), iphone = try client("iPhone")
        relay.state.with { $0.failNextAllowBy = iphone.deviceId }
        try await link(android, iphone)
        let joinerPeers = await iphone.peers
        XCTAssertEqual(joinerPeers.map(\.id), [android.deviceId])
        XCTAssertTrue(relay.state.get.allow.contains("\(iphone.deviceId)>\(android.deviceId)"))
    }

    func testNetworkErrorOnInvitersAllowIsRetriedAndBothSidesEndLinked() async throws {
        let android = try client("Pixel"), iphone = try client("iPhone")
        relay.state.with { $0.failNextAllowBy = android.deviceId }
        await android.setRetryDelays([1, 1, 1])
        try await android.configure(relayURL: "https://relay.example")
        let session = try await android.invite()
        // The joiner runs aside: if the inviter gave up, it would wait for a confirm forever on the frozen clock.
        let joiner = Task { try await iphone.join(session.uri) }
        let linked: LinkedPeer
        do { linked = try await android.awaitPeer(session) } catch { joiner.cancel(); throw error }
        XCTAssertEqual(linked.id, iphone.deviceId)
        let joined = try await joiner.value
        XCTAssertEqual(joined.id, android.deviceId)
        let inviterPeers = await android.peers, joinerPeers = await iphone.peers
        XCTAssertEqual(inviterPeers.map(\.id), [iphone.deviceId])
        XCTAssertEqual(joinerPeers.map(\.id), [android.deviceId])
        let allow = relay.state.get.allow
        XCTAssertTrue(allow.contains("\(android.deviceId)>\(iphone.deviceId)") && allow.contains("\(iphone.deviceId)>\(android.deviceId)"))
        XCTAssertEqual(relay.state.get.allowAttempts[android.deviceId], 2)
    }

    func testRelayRefusalOfInvitersAllowFailsAtOnce() async throws {
        let android = try client("Pixel"), iphone = try client("iPhone")
        try await android.configure(relayURL: "https://relay.example")
        let session = try await android.invite()
        relay.state.with { $0.refuseAllowBy = (android.deviceId, 429, "peer_limit") }
        await android.setRetryDelays([1, 1, 1])
        let joiner = Task { _ = try? await iphone.join(session.uri) }
        do {
            _ = try await android.awaitPeer(session)
            XCTFail("the relay refused the allow, awaitPeer must throw")
        } catch DeviceLinkError.relay(_, let code) {
            XCTAssertEqual(code, "peer_limit")
        }
        joiner.cancel()
        XCTAssertEqual(relay.state.get.allowAttempts[android.deviceId], 1)
        let inviterPeers = await android.peers, joinerPeers = await iphone.peers
        XCTAssertTrue(inviterPeers.isEmpty)
        XCTAssertTrue(joinerPeers.isEmpty)
    }

    static func relayError(_ status: Int, _ code: String) -> () throws -> HTTPResponse {
        { HTTPResponse(status: status, body: Data("{\"error\":\"\(code)\"}".utf8)) }
    }

    /// Links two devices and sends a 4-chunk file from the first with `faults` keyed by upload attempt.
    func sendBigFile(faults: [Int: () throws -> HTTPResponse]) async throws -> SendOutcome {
        let android = try client("Pixel"), iphone = try client("iPhone")
        try await link(android, iphone)
        await android.setRetryDelays([1, 1, 1])
        relay.state.with { $0.uploadFaults = faults }
        return try await android.send(.file(name: "big.bin", mime: "application/octet-stream", data: bigFile()))[0]
    }

    func testTransientRelayErrorOnOneChunkResendsOnlyThatChunk() async throws {
        let outcome = try await sendBigFile(faults: [3: Self.relayError(503, "unavailable")])
        XCTAssertTrue(outcome.accepted)
        XCTAssertEqual(relay.mailbox.count, 4)
        XCTAssertEqual(relay.state.get.uploads, 5, "4 chunks + 1 resend of chunk 3")
    }

    func testDroppedConnectionOnAChunkIsRetriedAndTheItemCompletes() async throws {
        let outcome = try await sendBigFile(faults: [4: { throw URLError(.networkConnectionLost) }, 5: Self.relayError(408, "timeout")])
        XCTAssertTrue(outcome.accepted)
        XCTAssertEqual(relay.mailbox.count, 4)
    }

    func testRefusedChunkIsNotRetriedAndReportsTheRelayCode() async throws {
        let outcome = try await sendBigFile(faults: [2: Self.relayError(413, "payload_too_large")])
        XCTAssertFalse(outcome.accepted)
        XCTAssertEqual(outcome.error, "payload_too_large")
        XCTAssertEqual(relay.mailbox.count, 1)
        XCTAssertEqual(relay.state.get.uploads, 2)
    }

    func testPersistentTransientFailureGivesUpAfterFourAttempts() async throws {
        var faults: [Int: () throws -> HTTPResponse] = [:]
        for attempt in 1...10 { faults[attempt] = Self.relayError(502, "bad_gateway") }
        let outcome = try await sendBigFile(faults: faults)
        XCTAssertEqual(outcome.error, "bad_gateway")
        XCTAssertEqual(relay.state.get.uploads, 4)
        XCTAssertTrue(relay.mailbox.isEmpty)
    }

    func testTransportFailuresReportStableNetworkAndTimeoutCodes() async throws {
        var faults: [Int: () throws -> HTTPResponse] = [:]
        for attempt in 1...4 { faults[attempt] = { throw URLError(.cannotConnectToHost) } }
        for attempt in 5...8 { faults[attempt] = { throw URLError(.timedOut) } }
        let android = try client("Pixel"), iphone = try client("iPhone")
        try await link(android, iphone)
        await android.setRetryDelays([1, 1, 1])
        relay.state.with { $0.uploadFaults = faults }
        let network = try await android.send(.text("x"))[0]
        XCTAssertEqual(network.error, SendOutcome.network)
        let timeout = try await android.send(.text("y"))[0]
        XCTAssertEqual(timeout.error, SendOutcome.timeout)
        XCTAssertFalse(timeout.accepted)
    }

    func testChunkedFileThatCannotBeAssembledGetsAFailedReceipt() async throws {
        let identity = SoftwareIdentity(), keys = EncryptionKeyPair()
        let sender = try client("Pixel", identity: identity, keys: keys), iphone = try client("iPhone")
        try await link(sender, iphone)
        // Every part claims one byte more than the parts hold: consistent, but the total never matches.
        let bytes = Data((0..<(Limits.chunkBytes + 10)).map { UInt8(truncatingIfNeeded: $0) })
        let parts = try Payload.chunks(kind: .file, name: "big.bin", mime: "application/octet-stream", bytes: bytes, now: now.get)
            .map { part -> Payload in var part = part; part.size = Int64(bytes.count) + 1; return part }
        let crypto = EnvelopeCrypto(identity: identity, encryption: keys)
        for (index, part) in parts.enumerated() {
            let envelope = try crypto.seal(part, to: iphone.localBundle, now: now.get, sequence: 100 + Int64(index))
            relay.state.with { $0.mailbox.append(envelope) }
        }
        for _ in 0..<2 {
            let applied = try await iphone.receiveOnce(wait: 0) { _ in XCTFail("a broken file must not be applied"); return .failed }
            XCTAssertEqual(applied, 0)
        }
        let stream = await sender.events()
        let statuses = Locked<[ReceiptStatus]>([])
        let ids = Locked<[String]>([])
        let task = Task { for await event in stream { if case .receipt(let id, _, let status) = event { ids.with { $0.append(id) }; statuses.with { $0.append(status) } } } }
        try await sender.receiveOnce(wait: 0) { _ in XCTFail("no content expected"); return .failed }
        try await Task.sleep(nanoseconds: 50_000_000)
        task.cancel()
        XCTAssertEqual(ids.get, [parts[0].group!])
        XCTAssertEqual(statuses.get, [.failed])
    }

    /// Passes through to the fake relay; mailbox polls fail like a URLSession task cancelled mid-flight.
    final class CancelledPolls: HTTPTransport, @unchecked Sendable {
        let inner: HTTPTransport
        init(_ inner: HTTPTransport) { self.inner = inner }
        func send(method: String, url: URL, headers: [String: String], body: Data, timeout: TimeInterval) async throws -> HTTPResponse {
            if method == "GET", url.path == "/v1/messages" { throw URLError(.cancelled) }
            return try await inner.send(method: method, url: url, headers: headers, body: body, timeout: timeout)
        }
    }

    func testCancelledLongPollIsNotRecordedAsAnError() async throws {
        let android = try client("Pixel")
        let iphone = try DeviceLinkClient(identity: SoftwareIdentity(), encryption: EncryptionKeyPair(), store: MemoryLinkStore(),
                                          config: LinkConfig(deviceName: "iPhone"), transport: CancelledPolls(relay), clock: { [now] in now.get })
        try await link(android, iphone)
        do {
            _ = try await iphone.receiveOnce(wait: 0) { _ in .copied }
            XCTFail("the cancelled poll must still end receiveOnce")
        } catch {}
        let diagnostics = await iphone.currentDiagnostics()
        XCTAssertNil(diagnostics.lastError)
        XCTAssertNil(diagnostics.lastErrorAt)
    }

    func testUnregisterTellsPeersDeletesTheDeviceOnTheRelayAndForgetsEveryLink() async throws {
        let android = try client("Pixel"), iphone = try client("iPhone")
        try await link(android, iphone)
        let stream = await android.events()
        let unlinked = Locked<[String]>([])
        let task = Task { for await event in stream { if case .peerUnlinked(let id, false) = event { unlinked.with { $0.append(id) } } } }
        let confirmed = await android.unregister()
        try await Task.sleep(nanoseconds: 50_000_000)
        task.cancel()
        XCTAssertTrue(confirmed)
        XCTAssertEqual(relay.state.get.storedFor, [iphone.deviceId])
        XCTAssertFalse(relay.state.get.devices.contains(android.deviceId))
        XCTAssertTrue(relay.state.get.allow.allSatisfy { !$0.contains(android.deviceId) })
        let peers = await android.peers, relayURL = await android.relayURL
        XCTAssertTrue(peers.isEmpty)
        XCTAssertEqual(unlinked.get, [iphone.deviceId])
        // The peer was not polling; the unlink notice outlives the unregister and it drops the link later.
        try await iphone.receiveOnce(wait: 0) { _ in XCTFail("no content expected"); return .failed }
        let joinerPeers = await iphone.peers
        XCTAssertTrue(joinerPeers.isEmpty)
        let registered = await android.currentDiagnostics().registered
        XCTAssertFalse(registered)
        XCTAssertEqual(relayURL, "https://relay.example")
    }

    func testUnregisterWithTheRelayUnreachableStillClearsLocalLinks() async throws {
        let android = try client("Pixel"), iphone = try client("iPhone")
        try await link(android, iphone)
        relay.state.with { $0.down = true }
        let confirmed = await android.unregister()
        XCTAssertFalse(confirmed)
        let peers = await android.peers
        XCTAssertTrue(peers.isEmpty)
        XCTAssertTrue(relay.state.get.devices.contains(android.deviceId))
    }

    func testUndecryptableEnvelopesAreDroppedAndCounted() async throws {
        let android = try client("Pixel"), iphone = try client("iPhone")
        try await link(android, iphone)
        _ = try await android.send(.text("tampered"))
        relay.state.with { $0.mailbox[0].ciphertext = Encoding.b64(Data(count: 64)) }
        let count = try await iphone.receiveOnce(wait: 0) { _ in XCTFail("must not apply"); return .failed }
        XCTAssertEqual(count, 0)
        let undecryptable = await iphone.currentDiagnostics().undecryptable
        XCTAssertEqual(undecryptable, 1)
        XCTAssertTrue(relay.mailbox.isEmpty)
    }

    /// Stores uploads, then answers as if after `answer` seconds: a request whose timeout is shorter fails.
    final class LateAnswers: HTTPTransport, @unchecked Sendable {
        let inner: HTTPTransport, answer: TimeInterval
        let timeouts = Locked<[TimeInterval]>([])
        init(_ inner: HTTPTransport, answer: TimeInterval) { self.inner = inner; self.answer = answer }
        func send(method: String, url: URL, headers: [String: String], body: Data, timeout: TimeInterval) async throws -> HTTPResponse {
            let response = try await inner.send(method: method, url: url, headers: headers, body: body, timeout: timeout)
            guard method == "POST", url.path == "/v1/messages" else { return response }
            timeouts.with { $0.append(timeout) }
            if timeout < answer { throw URLError(.timedOut) }
            return response
        }
    }

    func testUploadAnsweredAfter30sButWithinTheRelayAllowanceSucceeds() async throws {
        // The idle timer runs with no progress once the body is handed off; a 30 s timer reported such uploads
        // as failed although the relay stored them.
        let late = LateAnswers(relay, answer: 90)
        let sender = try DeviceLinkClient(identity: SoftwareIdentity(), encryption: EncryptionKeyPair(), store: MemoryLinkStore(),
                                          config: LinkConfig(deviceName: "Pixel"), transport: late, clock: { [now] in now.get })
        try await link(sender, try client("iPhone"))
        await sender.setRetryDelays([1, 1, 1])
        let outcome = try await sender.send(.file(name: "big.bin", mime: "application/octet-stream", data: bigFile()))[0]
        XCTAssertTrue(outcome.accepted)
        XCTAssertEqual(relay.state.get.uploads, 4, "one attempt per chunk")
        XCTAssertTrue(late.timeouts.get.allSatisfy { $0 > 90 })
    }

    /// Generated content read on demand; `position` is how far it was read.
    final class CountingStream: InputStream, @unchecked Sendable {
        let size: Int64
        let position: Locked<Int64>
        init(size: Int64, position: Locked<Int64>) { self.size = size; self.position = position; super.init(data: Data()) }
        static func byte(_ index: Int64) -> UInt8 { UInt8(index * 31 % 251) }
        override func open() {}
        override func close() {}
        override var streamStatus: Stream.Status { .open }
        override var hasBytesAvailable: Bool { position.get < size }
        override func read(_ buffer: UnsafeMutablePointer<UInt8>, maxLength length: Int) -> Int {
            position.with { position in
                let count = Int(min(Int64(length), size - position))
                for index in 0..<count { buffer[index] = Self.byte(position + Int64(index)) }
                position += Int64(count)
                return count
            }
        }
    }

    final class ReadAtUpload: HTTPTransport, @unchecked Sendable {
        let inner: HTTPTransport, position: Locked<Int64>
        let reads = Locked<[Int64]>([])
        init(_ inner: HTTPTransport, position: Locked<Int64>) { self.inner = inner; self.position = position }
        func send(method: String, url: URL, headers: [String: String], body: Data, timeout: TimeInterval) async throws -> HTTPResponse {
            if method == "POST", url.path == "/v1/messages" { reads.with { $0.append(position.get) } }
            return try await inner.send(method: method, url: url, headers: headers, body: body, timeout: timeout)
        }
    }

    func testA30MBStreamIsReadOneChunkAtATimeAndArrivesIntact() async throws {
        let size: Int64 = 30 * 1024 * 1024
        let position = Locked<Int64>(0), opened = Locked(0)
        let counting = ReadAtUpload(relay, position: position)
        let sendingIdentity = SoftwareIdentity(), sendingKeys = EncryptionKeyPair()
        let receivingIdentity = SoftwareIdentity(), receivingKeys = EncryptionKeyPair()
        let sender = try DeviceLinkClient(identity: sendingIdentity, encryption: sendingKeys, store: MemoryLinkStore(),
                                          config: LinkConfig(deviceName: "Pixel"), transport: counting, clock: { [now] in now.get })
        let receiver = try client("Desk", identity: receivingIdentity, keys: receivingKeys)
        try await link(sender, receiver)
        await sender.setRetryDelays([1, 1, 1])
        relay.state.with { $0.uploadFaults[$0.uploads + 3] = Self.relayError(503, "unavailable") }
        let source = OutgoingSource(kind: .file, name: "big.zip", mime: "application/zip", size: size) {
            opened.with { $0 += 1 }
            return CountingStream(size: size, position: position)
        }
        let outcome = try await sender.send(.stream(source))[0]
        XCTAssertTrue(outcome.accepted)
        XCTAssertEqual(opened.get, 1)
        let parts = Payload.partsFor(size: size)
        // Upload k (and the resend of chunk 2) happens with exactly chunks 0..k read: nothing is read ahead.
        let chunkEnd = { (k: Int) in min(size, Int64(k + 1) * Int64(Limits.chunkBytes)) }
        XCTAssertEqual(counting.reads.get, ([0, 1, 2, 2] + Array(3..<parts)).map(chunkEnd))
        let crypto = EnvelopeCrypto(identity: receivingIdentity, encryption: receivingKeys)
        let payloads = try relay.mailbox.map { try crypto.open($0, from: sender.localBundle, now: now.get) }
        XCTAssertEqual(payloads.map(\.part), (0..<parts).map { $0 })
        XCTAssertEqual(Set(payloads.map(\.group)), [outcome.itemId])
        XCTAssertTrue(payloads.allSatisfy { $0.parts == parts && $0.size == size && $0.kind == .file && $0.name == "big.zip" })

        let written = Locked<Int64>(0), intact = Locked(true)
        var applied = 0
        for _ in 0..<parts {
            applied += try await receiver.receiveOnce(wait: 0) { item in
                try? item.write { data in
                    let offset = written.get
                    let expected = Data((0..<Int64(data.count)).map { CountingStream.byte(offset + $0) })
                    if data != expected { intact.with { $0 = false } }
                    written.with { $0 += Int64(data.count) }
                }
                return .delivered
            }
        }
        XCTAssertEqual(applied, 1)
        XCTAssertEqual(written.get, size)
        XCTAssertTrue(intact.get)
    }

    func testStreamShorterThanItsSizeFailsTheSendWithoutANetworkError() async throws {
        let android = try client("Pixel"), iphone = try client("iPhone")
        try await link(android, iphone)
        let short = OutgoingSource(kind: .file, name: "cut.bin", mime: "application/octet-stream", size: Int64(Limits.maxContentBytes) + 1) {
            InputStream(data: Data(count: Limits.chunkBytes + 5))
        }
        let outcome = try await android.send(.stream(short))[0]
        XCTAssertEqual(outcome.error, SendOutcome.sendFailed)
        XCTAssertEqual(relay.mailbox.count, 1, "only the complete first chunk went out")
    }
}
