import Foundation

public struct LinkConfig: Sendable {
    public var deviceName: String
    public var platform: String
    public var model: String
    public var appVersion: String
    public var allowInsecureRelay: Bool
    public var lifetimeMillis: Int64
    public var pollWaitSeconds: Int
    /// Relay built into the app (private build configuration): the first device needs no setup and can
    /// show a pairing QR immediately; its identity is created on first launch.
    public var defaultRelayURL: String
    public var defaultEnrollmentToken: String

    public init(deviceName: String, platform: String = "ios", model: String = "", appVersion: String = "",
                allowInsecureRelay: Bool = false, lifetimeMillis: Int64 = Limits.defaultLifetimeMillis, pollWaitSeconds: Int = 25,
                defaultRelayURL: String = "", defaultEnrollmentToken: String = "") {
        self.deviceName = deviceName; self.platform = platform; self.model = model; self.appVersion = appVersion
        self.allowInsecureRelay = allowInsecureRelay; self.lifetimeMillis = lifetimeMillis; self.pollWaitSeconds = pollWaitSeconds
        self.defaultRelayURL = defaultRelayURL; self.defaultEnrollmentToken = defaultEnrollmentToken
    }
}

public enum OutgoingContent: Sendable {
    case text(String)
    /// Text with an optional rich-text alternative; `sensitive` hides previews and expires the clip where possible.
    case styledText(String, html: String?, sensitive: Bool)
    case image(name: String, mime: String, data: Data)
    case file(name: String, mime: String, data: Data)
}

/// A decrypted item from a linked peer, handed to the app to apply.
public struct IncomingItem: Sendable, Identifiable {
    public let id: String
    public let peer: LinkedPeer
    public let payload: Payload
    public let expiresAt: Int64
    /// A newer clip from the same peer was already applied; list it, do not overwrite the clipboard.
    public let stale: Bool
    /// Reassembled content of a chunked file; nil for single-envelope items.
    let assembled: Data?

    init(id: String, peer: LinkedPeer, payload: Payload, expiresAt: Int64, stale: Bool, assembled: Data? = nil) {
        self.id = id; self.peer = peer; self.payload = payload; self.expiresAt = expiresAt; self.stale = stale; self.assembled = assembled
    }

    public var kind: PayloadKind { payload.kind }
    public var text: String? { payload.text }
    public var html: String? { payload.html }
    public var sensitive: Bool { payload.sensitive == true }
    public var fileName: String? { payload.name }
    public var mime: String { payload.mime ?? "text/plain" }
    public func bytes() -> Data? { assembled ?? (try? payload.dataBytes()) }
}

/// Snapshot for debug screens. Never contains content.
public struct Diagnostics: Sendable, Equatable {
    public var deviceId: String
    public var relayURL: String = ""
    public var registered = false
    public var peers = 0
    public var lastPollAt: Int64?
    public var lastReceiveAt: Int64?
    public var lastSendAt: Int64?
    public var lastError: String?
    public var lastErrorAt: Int64?
    public var received = 0
    public var sent = 0
    /// Envelopes from a linked peer that failed to authenticate or decrypt (for example after a silent re-key).
    public var undecryptable = 0
}

/// Result of sending one item to one peer.
///
/// `error` is nil when the relay accepted every envelope of the item. Otherwise it is one of:
/// - the relay's JSON `error` code when the relay answered (for example `recipient_not_authorized`,
///   `mailbox_full`, `rate_limit`, `payload_too_large`), or `http_<status>` when its answer had no code;
/// - `SendOutcome.network`: no HTTP answer (connection refused, reset or lost, DNS or TLS failure);
/// - `SendOutcome.timeout`: the request timed out;
/// - `SendOutcome.sendFailed`: a local failure before or after the upload (for example the link file could not be saved).
/// Transient failures (408, 429 other than `mailbox_full`, 5xx, network, timeout) were already retried.
public struct SendOutcome: Sendable {
    public static let network = "network"
    public static let timeout = "timeout"
    public static let sendFailed = "send_failed"

    public let peerId: String
    public let itemId: String?
    public let error: String?
    public var accepted: Bool { itemId != nil && error == nil }

    static func code(for error: Error) -> String {
        switch error {
        case DeviceLinkError.relay(_, let code): return code
        case let error as URLError: return error.code == .timedOut ? timeout : network
        default: return sendFailed
        }
    }
}

/// Pauses before the 2nd, 3rd and 4th upload attempt of one envelope (4 attempts, about 7 s in total).
let uploadRetryDelaysMillis: [UInt64] = [1_000, 2_000, 4_000]

/// URL errors a retry cannot fix (cancellation, bad URL, certificate and ATS failures).
private let permanentURLErrors: Set<URLError.Code> = [
    .cancelled, .badURL, .unsupportedURL, .userAuthenticationRequired, .userCancelledAuthentication,
    .serverCertificateUntrusted, .serverCertificateHasBadDate, .serverCertificateHasUnknownRoot,
    .serverCertificateNotYetValid, .clientCertificateRejected, .clientCertificateRequired,
    .appTransportSecurityRequiresSecureConnection,
]

/// Upload failures worth resending the same envelope for: 408, 429 (except `mailbox_full`, which has its own
/// wait), 5xx, and transport errors a retry can fix. Other 4xx (400, 401, 403, 404, 409, 413) cannot succeed.
func isTransientUploadFailure(_ error: Error) -> Bool {
    switch error {
    case DeviceLinkError.relay(let status, let code):
        return code != "mailbox_full" && (status == 408 || status == 429 || (500...599).contains(status))
    case let error as URLError: return !permanentURLErrors.contains(error.code)
    default: return false
    }
}

/// Outcome of `DeviceLinkClient.drain`.
public struct DrainResult: Sendable, Equatable {
    /// Items that reached the handler (receipts, unlink notices and duplicates are not counted).
    public var applied: Int
    /// The relay answered with an empty mailbox: nothing is left waiting.
    public var complete: Bool
    /// Another mailbox poll by this device was active (`poll_already_active`), so draining stopped early.
    public var collided = false
}

public enum ReceiverStatus: String, Sendable { case idle, connecting, online, offline, needsSetup, rejected }

public enum LinkEvent: Sendable, Equatable {
    case peerLinked(LinkedPeer)
    case peerUnlinked(peerId: String, byRemote: Bool)
    case receipt(itemId: String, peerId: String, status: ReceiptStatus)
    case status(ReceiverStatus)
}

/// Platform-neutral DeviceLink engine, the Swift twin of the Kotlin `DeviceLinkClient`.
public actor DeviceLinkClient {
    /// Relay error for a second concurrent mailbox poll by the same device.
    static let pollAlreadyActive = "poll_already_active"
    public nonisolated let deviceId: String
    public nonisolated let localBundle: KeyBundle
    let identity: DeviceIdentity
    let crypto: EnvelopeCrypto
    let store: LinkStore
    let config: LinkConfig
    let transport: HTTPTransport
    let clock: @Sendable () -> Int64
    private var listeners: [UUID: AsyncStream<LinkEvent>.Continuation] = [:]
    private let spool: ChunkSpool
    /// Spooled files left by a previous process are resumed once, on the first receive.
    private var spoolResumed = false
    /// Upload retry pauses; tests shorten them.
    var retryDelaysMillis = uploadRetryDelaysMillis
    func setRetryDelays(_ delays: [UInt64]) { retryDelaysMillis = delays }
    public private(set) var diagnostics: Diagnostics
    public private(set) var status: ReceiverStatus = .idle

    public init(identity: DeviceIdentity, encryption: EncryptionKeyPair, store: LinkStore, config: LinkConfig,
                transport: HTTPTransport = URLSessionTransport(),
                clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) throws {
        self.identity = identity
        self.deviceId = identity.deviceId
        self.localBundle = try KeyBundle.create(identity: identity, encryption: encryption, name: config.deviceName, platform: config.platform)
        self.crypto = EnvelopeCrypto(identity: identity, encryption: encryption)
        self.store = store
        self.spool = (store as? ChunkSpoolLocation).map { FileChunkSpool(directory: $0.chunkDirectory) } ?? MemoryChunkSpool()
        self.config = config
        self.transport = transport
        self.clock = clock
        self.diagnostics = Diagnostics(deviceId: identity.deviceId)
        if store.load().relayUrl.isEmpty, !config.defaultRelayURL.isEmpty {
            let relayURL = try RelayURL.normalize(config.defaultRelayURL, allowInsecure: config.allowInsecureRelay)
            let token = config.defaultEnrollmentToken
            try store.update { state in
                guard state.relayUrl.isEmpty else { return }
                state.relayUrl = relayURL
                state.enrollmentToken = token
            }
        }
    }

    // MARK: State

    public var peers: [LinkedPeer] { store.load().peers }
    public var relayURL: String { store.load().relayUrl }
    public var isConfigured: Bool { !relayURL.isEmpty }

    public func events() -> AsyncStream<LinkEvent> {
        let id = UUID()
        return AsyncStream(bufferingPolicy: .bufferingNewest(64)) { continuation in
            listeners[id] = continuation
            continuation.onTermination = { [weak self] _ in Task { await self?.removeListener(id) } }
        }
    }

    private func removeListener(_ id: UUID) { listeners[id] = nil }
    private func emit(_ event: LinkEvent) { listeners.values.forEach { $0.yield(event) } }
    private func note(_ change: (inout Diagnostics) -> Void) {
        let state = store.load()
        change(&diagnostics)
        diagnostics.relayURL = state.relayUrl
        diagnostics.peers = state.peers.count
        diagnostics.registered = !state.relayUrl.isEmpty && state.registeredRelay == state.relayUrl
    }

    private func noteFailure(_ error: Error) {
        // A cancelled request (Off, app backgrounded, a superseded long poll) is not a failure.
        if error is CancellationError || (error as? URLError)?.code == .cancelled { return }
        let text: String
        switch error {
        case DeviceLinkError.relay(let status, let code): text = "relay \(status) \(code)"
        case is URLError: text = "network: \((error as! URLError).code.rawValue)"
        default: text = String(describing: type(of: error))
        }
        note { $0.lastError = text; $0.lastErrorAt = clock() }
    }

    public func currentDiagnostics() -> Diagnostics { note { _ in }; return diagnostics }

    private func setStatus(_ value: ReceiverStatus) { if status != value { status = value; emit(.status(value)) } }

    @discardableResult
    private func update(_ transform: (inout LinkState) throws -> Void) throws -> LinkState {
        let now = clock()
        return try store.update { state in
            try transform(&state)
            state.seen = state.seen.filter { $0.value > now }
        }
    }

    func relay(_ url: String? = nil) throws -> RelayClient {
        let base = url ?? relayURL
        guard !base.isEmpty else { throw DeviceLinkError.notConfigured }
        return RelayClient(baseURL: base, identity: identity, transport: transport, clock: clock)
    }

    // MARK: Setup

    public func configure(relayURL: String, enrollmentToken: String = "") throws {
        let normalized = try RelayURL.normalize(relayURL, allowInsecure: config.allowInsecureRelay)
        try update { state in
            if state.relayUrl == normalized { state.enrollmentToken = enrollmentToken; return }
            guard state.peers.isEmpty else { throw DeviceLinkError.pairing("Unlink existing devices before switching relay") }
            state.relayUrl = normalized; state.enrollmentToken = enrollmentToken; state.registeredRelay = ""
        }
    }

    /// Applies an admin setup link; returns false when the text is not one.
    public func applySetupLink(_ text: String) throws -> Bool {
        guard let link = SetupLink.parse(text, allowInsecure: config.allowInsecureRelay) else { return false }
        try configure(relayURL: link.relayURL, enrollmentToken: link.enrollmentToken)
        return true
    }

    public func ensureRegistered(pairingId: String? = nil, force: Bool = false) async throws {
        let state = store.load()
        if !force, pairingId == nil, state.registeredRelay == state.relayUrl { return }
        try await relay().register(DeviceMetadata(platform: config.platform, model: config.model, appVersion: config.appVersion),
                                   enrollmentToken: state.enrollmentToken, pairingId: pairingId)
        try update { $0.registeredRelay = state.relayUrl }
    }

    private func registered<T>(_ block: (RelayClient) async throws -> T) async throws -> T {
        try await ensureRegistered()
        do {
            return try await block(try relay())
        } catch DeviceLinkError.relay(_, "registration_required") {
            try await ensureRegistered(force: true)
            return try await block(try relay())
        }
    }

    public func relayInfo() async throws -> RelayInfo { try await relay().info() }

    /// Registers an APNs token so the relay can wake this device (content-free alert) when an item waits.
    public func registerPush(token: String, environment: String, topic: String) async throws {
        try await registered { try await $0.registerPush(token: token, environment: environment, topic: topic) }
    }

    // MARK: Pairing

    public struct PairingSession: Sendable {
        public let invite: PairingInvite
        public let expiresAt: Int64
        public var uri: String { invite.uri }
    }

    /// Creates a one-time QR invitation (default 5 minutes).
    public func invite(lifetimeMillis: Int64 = 5 * 60_000) async throws -> PairingSession {
        guard isConfigured else { throw DeviceLinkError.notConfigured }
        let invite = try PairingInvite.create(relayURL: relayURL, inviterId: deviceId)
        let expiresAt = clock() + lifetimeMillis
        try await registered { try await $0.createPairing(invite.pairingId, expiresAt: expiresAt) }
        return PairingSession(invite: invite, expiresAt: expiresAt)
    }

    /// Waits for a joiner, pins it, allows it on the relay and confirms. The link is saved and announced only
    /// once the relay accepted the confirmation, so a failed confirm never leaves the inviter showing a link
    /// the joiner does not have. A confirm whose response was lost is checked against the pairing state; if
    /// it really failed, the relay allowance is withdrawn and this throws.
    public func awaitPeer(_ session: PairingSession) async throws -> LinkedPeer {
        let relay = try relay(session.invite.relayURL)
        while true {
            try Task.checkCancellation()
            guard clock() < session.expiresAt else { throw DeviceLinkError.pairing("Pairing code expired") }
            let status: PairingStatus
            do { status = try await relay.pollPairing(session.invite.pairingId, wait: 25) } catch let error as URLError {
                _ = error; try await Task.sleep(nanoseconds: 2_000_000_000); continue
            }
            switch status.state {
            case "open": try await Task.sleep(nanoseconds: 200_000_000)
            case "joined", "confirmed":
                let bundle = try session.invite.openJoin(status.sealed ?? "")
                guard bundle.id == status.joinerId, bundle.id != deviceId else { throw DeviceLinkError.pairing("Pairing data does not match joiner") }
                try await relay.allow(bundle.id)
                // "confirmed" here means an earlier confirm by this inviter was accepted (response lost).
                if status.state == "joined" { try await confirm(session, relay: relay, joinerId: bundle.id) }
                let peer = try addPeer(bundle, code: session.invite.confirmationCode(joinerId: bundle.id))
                emit(.peerLinked(peer))
                return peer
            default: throw DeviceLinkError.pairing("Pairing \(status.state)")
            }
        }
    }

    private func confirm(_ session: PairingSession, relay: RelayClient, joinerId: String) async throws {
        do {
            try await relay.confirmPairing(session.invite.pairingId, sealed: try session.invite.sealConfirm(localBundle))
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            if (try? await relay.pollPairing(session.invite.pairingId, wait: 0))?.state == "confirmed" { return }
            // Keep an existing link to the same device (re-pairing) allowed.
            if !peers.contains(where: { $0.id == joinerId }) { try? await relay.revoke(joinerId) }
            noteFailure(error)
            if case DeviceLinkError.relay(_, let code) = error { throw DeviceLinkError.pairing("Relay refused pairing (\(code))") }
            throw DeviceLinkError.pairing("Could not reach the relay to confirm the link")
        }
    }

    public func cancel(_ session: PairingSession) async { try? await relay(session.invite.relayURL).cancelPairing(session.invite.pairingId) }

    /// Joins from a scanned QR / opened link; adopts the invite's relay when this device has no links yet.
    public func join(_ uri: String) async throws -> LinkedPeer {
        guard let invite = PairingInvite.parse(uri, allowInsecure: config.allowInsecureRelay) else {
            throw DeviceLinkError.pairing("Not a DeviceLink pairing code")
        }
        guard invite.inviterId != deviceId else { throw DeviceLinkError.pairing("Scan the code on the other device") }
        if relayURL != invite.relayURL { try configure(relayURL: invite.relayURL) }
        try await ensureRegistered(pairingId: invite.pairingId)
        let relay = try relay()
        do {
            try await relay.joinPairing(invite.pairingId, sealed: try invite.sealJoin(localBundle))
        } catch DeviceLinkError.relay(let status, let code) {
            throw DeviceLinkError.pairing(status == 404 || status == 409 ? "Pairing code expired or already used" : "Relay refused pairing (\(code))")
        }
        let deadline = clock() + 120_000
        while clock() < deadline {
            try Task.checkCancellation()
            let status: PairingStatus
            do { status = try await relay.pollPairing(invite.pairingId, wait: 25) } catch let error as URLError {
                _ = error; try await Task.sleep(nanoseconds: 2_000_000_000); continue
            }
            switch status.state {
            case "joined": try await Task.sleep(nanoseconds: 200_000_000)
            case "confirmed":
                let bundle = try invite.openConfirm(status.sealed ?? "")
                guard bundle.id == invite.inviterId else { throw DeviceLinkError.pairing("Inviter identity does not match the code") }
                // The relay deletes the pairing once the joiner has read "confirmed", so a network error
                // here is retried in place: re-polling would only find the pairing gone.
                var attempt: UInt64 = 0
                while true {
                    do { try await relay.allow(bundle.id); break } catch let error as URLError {
                        attempt += 1
                        if attempt >= 5 || clock() >= deadline { throw error }
                        try await Task.sleep(nanoseconds: 2_000_000_000 * attempt)
                    }
                }
                let peer = try addPeer(bundle, code: invite.confirmationCode(joinerId: deviceId))
                emit(.peerLinked(peer))
                return peer
            default: throw DeviceLinkError.pairing("Pairing \(status.state)")
            }
        }
        throw DeviceLinkError.pairing("The other device did not confirm in time")
    }

    private func addPeer(_ bundle: KeyBundle, code: String) throws -> LinkedPeer {
        let peer = LinkedPeer(bundle: bundle, linkedAt: clock(), pairingCode: code)
        try update { state in
            state.peers.removeAll { $0.id == bundle.id }
            state.peers.append(peer)
            state.pendingRevocations.remove(bundle.id)
        }
        return peer
    }

    /// Removes a link locally at once, tells the peer, revokes it on the relay (retried later when offline).
    public func unlink(_ peerId: String) async throws {
        if let peer = peers.first(where: { $0.id == peerId }) { _ = try? await deliver(.unlink(), to: peer) }
        try update { state in
            state.peers.removeAll { $0.id == peerId }
            state.pendingRevocations.insert(peerId)
            state.lastClip[peerId] = nil
        }
        emit(.peerUnlinked(peerId: peerId, byRemote: false))
        try? await flushRevocations()
    }

    /// "Remove this device everywhere": tells every peer, revokes them on the relay, forgets them locally.
    public func unlinkAll() async throws { for peer in peers { try await unlink(peer.id) } }

    /// First step of "Reset device". Sends every linked peer an unlink notice (one attempt each, so peers that
    /// are online drop the link instead of sending into a void), then asks the relay to delete this device
    /// (`DELETE /v1/devices/self`: its links, waiting messages to and from it, pairings and push token).
    ///
    /// Best effort: never throws. Returns true only when the relay confirmed the delete. Whatever the result,
    /// and even when the task is cancelled (wrap it in a short timeout), the local link state is cleared:
    /// peers, pending revocations, replay ledger, clip sequences, chunks in transfer and the registration
    /// mark. The relay URL and enrollment token are kept, so the device can link again. Callers then wipe
    /// keys and app state as usual, which keeps reset working offline.
    public func unregister() async -> Bool {
        let state = store.load()
        defer {
            _ = try? update { current in
                var cleared = LinkState()
                cleared.relayUrl = current.relayUrl
                cleared.enrollmentToken = current.enrollmentToken
                current = cleared
            }
            for key in spool.keys() { spool.remove(key) }
            note { _ in }
            for peer in state.peers { emit(.peerUnlinked(peerId: peer.id, byRemote: false)) }
        }
        guard !state.relayUrl.isEmpty else { return false }
        for peer in state.peers where !Task.isCancelled { _ = try? await deliver(.unlink(), to: peer, retry: false) }
        do {
            try await relay().unregister()
            return true
        } catch {
            noteFailure(error)
            return false
        }
    }

    private func flushRevocations() async throws {
        for id in store.load().pendingRevocations {
            try await registered { try await $0.revoke(id) }
            try update { $0.pendingRevocations.remove(id) }
        }
    }

    // MARK: Sending

    /// Encrypts separately for each target peer (all linked peers by default).
    public func send(_ content: OutgoingContent, to peerIds: Set<String>? = nil) async throws -> [SendOutcome] {
        let now = clock()
        let payloads: [Payload]
        switch content {
        case .text(let text): payloads = [.text(text, now: now)]
        case .styledText(let text, let html, let sensitive): payloads = [.text(text, now: now, html: html, sensitive: sensitive)]
        case .image(let name, let mime, let data):
            payloads = data.count <= Limits.maxContentBytes ? [.image(name: name, mime: mime, bytes: data, now: now)]
                : try Payload.chunks(kind: .image, name: name, mime: mime, bytes: data, now: now)
        case .file(let name, let mime, let data):
            payloads = data.count <= Limits.maxContentBytes ? [.file(name: name, mime: mime, bytes: data, now: now)]
                : try Payload.chunks(kind: .file, name: name, mime: mime, bytes: data, now: now)
        }
        for payload in payloads { try payload.validate() }
        var outcomes: [SendOutcome] = []
        for peer in peers where peerIds?.contains(peer.id) ?? true {
            do {
                var id = ""
                for payload in payloads { id = try await deliver(payload, to: peer) }
                note { $0.lastSendAt = clock(); $0.sent += 1 }
                outcomes.append(SendOutcome(peerId: peer.id, itemId: payloads[0].group ?? id, error: nil))
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                noteFailure(error)
                outcomes.append(SendOutcome(peerId: peer.id, itemId: nil, error: SendOutcome.code(for: error)))
            }
        }
        return outcomes
    }

    /// Seals `payload` once and uploads it. Each envelope is retried on its own, so a failure on chunk 7 of 8
    /// resends only chunk 7: transient failures (`isTransientUploadFailure`) up to `uploadRetryDelaysMillis`
    /// more times, `429 mailbox_full` every 2 s for up to 90 s while the receiver drains. `retry: false` makes
    /// one attempt (best-effort notices that must not hold up the caller).
    private func deliver(_ payload: Payload, to peer: LinkedPeer, retry: Bool = true) async throws -> String {
        let sequence = try update { $0.sequence += 1 }.sequence
        let envelope = try crypto.seal(payload, to: peer.bundle, now: clock(), sequence: sequence, lifetimeMillis: config.lifetimeMillis)
        var attempt = 0
        let deadline = clock() + 90_000
        while true {
            do {
                // Retries resend the exact same envelope: the relay treats it as idempotent.
                try await registered { try await $0.upload(envelope) }
                return envelope.id
            } catch {
                if error is CancellationError || !retry { throw error }
                if case DeviceLinkError.relay(_, "mailbox_full") = error, clock() < deadline {
                    // The receiver drains its mailbox as it acknowledges; large chunked files rely on this.
                    try await Task.sleep(nanoseconds: 2_000_000_000)
                    continue
                }
                guard isTransientUploadFailure(error), attempt < retryDelaysMillis.count else { throw error }
                try await Task.sleep(nanoseconds: retryDelaysMillis[attempt] * 1_000_000)
                attempt += 1
            }
        }
    }

    // MARK: Receiving

    /// Fetches everything waiting without long-polling, until the relay returns an empty mailbox, applying
    /// each item through `handler`. Use it for one-shot actions (the notification Copy action, the
    /// "Get DeviceLink clip" intent) instead of looping on `receiveOnce(wait: 0)` until it returns 0: that
    /// stops early because receipts, unlink notices and duplicates count 0, and the relay serves a sender's
    /// newest envelope (often a receipt) first.
    ///
    ///     let result = try await client.drain { item in await store.apply(item) }
    ///     if result.collided { /* the app's receive loop is polling; it will deliver the items */ }
    ///
    /// At most `maxEnvelopes` envelopes are processed. Envelopes already handled in this call are excluded
    /// from the next poll, so a failed acknowledgement cannot loop. Returns `collided = true` instead of
    /// throwing on `poll_already_active`; other relay and network errors are thrown.
    public func drain(maxEnvelopes: Int = 100, handler: @Sendable (IncomingItem) async -> ReceiptStatus) async throws -> DrainResult {
        var applied = try await resumeSpooled(handler: handler)
        defer { if applied > 0 { note { $0.lastReceiveAt = clock(); $0.received += applied } } }
        var handled: [String] = []
        for _ in 0..<maxEnvelopes {
            note { $0.lastPollAt = clock() }
            let envelopes: [Envelope]
            do {
                envelopes = try await registered { [handled] in try await $0.poll(wait: 0, limit: 1, exclude: handled) }
            } catch DeviceLinkError.relay(_, Self.pollAlreadyActive) {
                return DrainResult(applied: applied, complete: false, collided: true)
            } catch {
                if !(error is CancellationError) { noteFailure(error) }
                throw error
            }
            if envelopes.isEmpty { return DrainResult(applied: applied, complete: true) }
            for envelope in envelopes {
                if try await process(envelope, handler: handler) { applied += 1 }
                handled.append(envelope.id)
                if handled.count > 20 { handled.removeFirst() }
            }
        }
        return DrainResult(applied: applied, complete: false)
    }

    /// Fetches and processes at most one envelope; [handler] applies it and returns the receipt status.
    /// Returns how many items reached [handler] (receipts, unlink notices and duplicates are not counted),
    /// so 0 does not mean the mailbox is empty: use `drain` to fetch everything waiting.
    @discardableResult
    public func receiveOnce(wait: Int? = nil, handler: @Sendable (IncomingItem) async -> ReceiptStatus) async throws -> Int {
        let waitSeconds = wait ?? config.pollWaitSeconds
        let resumed = isConfigured ? try await resumeSpooled(handler: handler) : 0
        note { $0.lastPollAt = clock() }
        let envelopes: [Envelope]
        do { envelopes = try await registered { try await $0.poll(wait: waitSeconds, limit: 1) } } catch {
            if !(error is CancellationError) { noteFailure(error) }
            if resumed > 0 { note { $0.lastReceiveAt = clock(); $0.received += resumed } }
            throw error
        }
        var delivered = resumed
        for envelope in envelopes where try await process(envelope, handler: handler) { delivered += 1 }
        if delivered > 0 { note { $0.lastReceiveAt = clock(); $0.received += delivered } }
        return delivered
    }

    /// Delivery order for every envelope: apply -> record (seen, lastClip) -> acknowledge -> receipt.
    /// Recording only after the handler ran means a crash never loses an item: the relay delivers it again.
    /// The same clip can then reach the clipboard twice, but an OLDER clip never overwrites a newer one,
    /// because `lastClip` is saved together with `seen` and the relay serves each sender's newest first.
    private func process(_ envelope: Envelope, handler: @Sendable (IncomingItem) async -> ReceiptStatus) async throws -> Bool {
        let relay = try relay()
        let state = store.load()
        guard state.seen[envelope.id] == nil, let peer = state.peers.first(where: { $0.id == envelope.senderId }) else {
            try await relay.acknowledge(envelope.id)
            return false
        }
        guard let payload = try? crypto.open(envelope, from: peer.bundle, now: clock()) else {
            // It can never decrypt; drop it, but make a silent re-key or tampering visible.
            note { $0.undecryptable += 1 }
            try await relay.acknowledge(envelope.id)
            return false
        }
        switch payload.kind {
        case .receipt:
            if let original = payload.receiptFor, let status = payload.status { emit(.receipt(itemId: original, peerId: peer.id, status: status)) }
            try update { $0.seen[envelope.id] = envelope.expiresAt }
            await acknowledgeApplied(relay, envelope.id)
            return false
        case .unlink:
            try update { state in
                state.peers.removeAll { $0.id == peer.id }
                state.pendingRevocations.insert(peer.id)
                state.lastClip[peer.id] = nil
                state.seen[envelope.id] = envelope.expiresAt
            }
            await acknowledgeApplied(relay, envelope.id)
            emit(.peerUnlinked(peerId: peer.id, byRemote: true))
            try? await flushRevocations()
            return false
        case .text, .image, .file:
            if payload.isChunk, let group = payload.group, let part = payload.part, let parts = payload.parts {
                // Stored (still encrypted) before the acknowledgement, so a restart resumes the file.
                let key = ChunkSpoolKey.key(peerId: peer.id, group: group)
                spool.purge(now: clock())
                try spool.put(key, part: part, envelope: envelope)
                await acknowledgeApplied(relay, envelope.id)
                guard spool.count(key) >= parts else { return false }
                return try await assemble(key, peer: peer, handler: handler)
            }
            let stale = payload.kind != .file && envelope.sequence <= (state.lastClip[peer.id] ?? 0)
            let item = IncomingItem(id: envelope.id, peer: peer, payload: payload, expiresAt: envelope.expiresAt, stale: stale)
            let status = try await apply(item, envelopes: [envelope], handler: handler)
            await acknowledgeApplied(relay, envelope.id)
            _ = try? await deliver(.receipt(envelope.id, status), to: peer)
            return true
        }
    }

    /// Runs `handler`, then records the envelopes as seen (and the clip sequence) in one save.
    private func apply(_ item: IncomingItem, envelopes: [Envelope], handler: @Sendable (IncomingItem) async -> ReceiptStatus) async throws -> ReceiptStatus {
        let status = await handler(item)
        let sequence = envelopes.map(\.sequence).max() ?? 0
        try update { state in
            for envelope in envelopes { state.seen[envelope.id] = envelope.expiresAt }
            if status == .copied { state.lastClip[item.peer.id] = max(state.lastClip[item.peer.id] ?? 0, sequence) }
        }
        return status
    }

    /// Acknowledges an envelope whose effect is already recorded. A failure is not fatal: the relay delivers
    /// it again, `seen` turns that into a plain acknowledgement, and the receipt still goes now.
    private func acknowledgeApplied(_ relay: RelayClient, _ id: String) async {
        do { try await relay.acknowledge(id) } catch { if !(error is CancellationError) { noteFailure(error) } }
    }

    /// Applies a spooled chunked file once every part is present. Returns whether the handler ran.
    private func assemble(_ key: String, peer: LinkedPeer, handler: @Sendable (IncomingItem) async -> ReceiptStatus) async throws -> Bool {
        let stored = spool.parts(key)
        let state = store.load()
        // Applied and recorded before a crash, only the cleanup was missed.
        if !stored.isEmpty, stored.allSatisfy({ state.seen[$0.id] != nil }) { spool.remove(key); return false }
        let now = clock()
        var opened: [(Envelope, Payload)] = []
        for envelope in stored {
            guard let payload = try? crypto.open(envelope, from: peer.bundle, now: now), payload.isChunk else { return await rejectFile(key, peer: peer) }
            opened.append((envelope, payload))
        }
        opened.sort { ($0.1.part ?? 0) < ($1.1.part ?? 0) }
        guard let first = opened.first?.1, let parts = first.parts, let size = first.size else { return false }
        let consistent = opened.map { $0.1.part ?? -1 } == Array(0..<parts) && opened.allSatisfy { _, p in
            p.parts == parts && p.size == size && p.group == first.group && p.kind == first.kind
        }
        guard consistent else {
            // Fewer parts than announced: still arriving. As many or more and still inconsistent: never completes.
            return opened.count >= parts ? await rejectFile(key, peer: peer) : false
        }
        var whole = Data(capacity: Int(size))
        for (_, payload) in opened { whole.append(try payload.dataBytes() ?? Data()) }
        guard Int64(whole.count) == size, let group = first.group else { return await rejectFile(key, peer: peer) }
        let envelopes = opened.map(\.0)
        let newest = envelopes.map(\.sequence).max() ?? 0
        let stale = first.kind != .file && newest <= (state.lastClip[peer.id] ?? 0)
        var meta = first
        meta.data = nil; meta.part = nil; meta.parts = nil
        let item = IncomingItem(id: group, peer: peer, payload: meta, expiresAt: envelopes.map(\.expiresAt).min() ?? 0,
                                stale: stale, assembled: whole)
        let status = try await apply(item, envelopes: envelopes, handler: handler)
        spool.remove(key)
        _ = try? await deliver(.receipt(group, status), to: peer)
        return true
    }

    /// Drops a chunked file that can never be assembled (a part does not open, parts disagree, or the total
    /// does not match `size`) and tells the sender with a `failed` receipt for the group, so it does not wait
    /// for a receipt that never comes. Returns false: the handler did not run.
    private func rejectFile(_ key: String, peer: LinkedPeer) async -> Bool {
        spool.remove(key)
        let group = String(key.drop { $0 != "_" }.dropFirst())
        if UUID(uuidString: group)?.uuidString.lowercased() == group {
            _ = try? await deliver(.receipt(group, .failed), to: peer)
        }
        return false
    }

    /// Completes files whose chunks were all stored by a previous process that stopped before applying.
    private func resumeSpooled(handler: @Sendable (IncomingItem) async -> ReceiptStatus) async throws -> Int {
        guard !spoolResumed else { return 0 }
        spoolResumed = true
        spool.purge(now: clock())
        let peers = store.load().peers
        var applied = 0
        for key in spool.keys() {
            guard let peer = peers.first(where: { $0.id == ChunkSpoolKey.peer(of: key) }) else { spool.remove(key); continue }
            if try await assemble(key, peer: peer, handler: handler) { applied += 1 }
        }
        return applied
    }

    /// Decrypts one waiting item for a notification preview without consuming it: the app's receive
    /// loop still applies and acknowledges it. Returns nil for unknown senders, receipts or expired items.
    public func preview(messageId: String) async throws -> IncomingItem? {
        let envelope = try await registered { try await $0.fetch(messageId) }
        guard let peer = peers.first(where: { $0.id == envelope.senderId }),
              let payload = try? crypto.open(envelope, from: peer.bundle, now: clock()),
              [.text, .image, .file].contains(payload.kind) else { return nil }
        return IncomingItem(id: payload.group ?? envelope.id, peer: peer, payload: payload, expiresAt: envelope.expiresAt, stale: false)
    }

    /// Long-polls until the calling task is cancelled (Off = no polling), with jittered backoff.
    public func runReceiver(handler: @escaping @Sendable (IncomingItem) async -> ReceiptStatus) async {
        var backoff: UInt64 = 1_000
        defer { setStatus(.idle) }
        while !Task.isCancelled {
            do {
                if !isConfigured { setStatus(.needsSetup); try await Task.sleep(nanoseconds: 5_000_000_000); continue }
                if peers.isEmpty { setStatus(.idle); try await Task.sleep(nanoseconds: 3_000_000_000); continue }
                if status != .online { setStatus(.connecting) }
                if !store.load().pendingRevocations.isEmpty { try await flushRevocations() }
                setStatus(.online)
                try await receiveOnce(handler: handler)
                backoff = 1_000
            } catch is CancellationError {
                return
            } catch DeviceLinkError.relay(_, Self.pollAlreadyActive) {
                // The relay still holds this device's previous long poll (for example after a reconnect).
                // Transient: retry soon instead of the 60 s rate-limit backoff, status unchanged.
                try? await Task.sleep(nanoseconds: (2_000 + UInt64.random(in: 0...1_000)) * 1_000_000)
            } catch DeviceLinkError.relay(let status, _) {
                setStatus(status == 401 || status == 403 ? .rejected : .offline)
                try? await Task.sleep(nanoseconds: (status == 429 ? 60_000 : max(backoff, 15_000)) * 1_000_000)
                backoff = min(backoff * 2, 60_000)
            } catch {
                setStatus(.offline)
                try? await Task.sleep(nanoseconds: (backoff + UInt64.random(in: 0...backoff / 2)) * 1_000_000)
                backoff = min(backoff * 2, 60_000)
            }
        }
    }
}
