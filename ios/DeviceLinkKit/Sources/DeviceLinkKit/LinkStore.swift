import Foundation

public struct LinkedPeer: Codable, Equatable, Sendable, Identifiable {
    public var bundle: KeyBundle
    public var linkedAt: Int64
    /// Six-digit confirmation code shown to the user after linking.
    public var pairingCode: String?
    public var id: String { bundle.id }
    public var name: String { bundle.name }
    public var platform: String { bundle.platform }
}

/// Everything the engine persists: public keys and counters, never content. Same shape as Kotlin `LinkState`.
public struct LinkState: Codable, Equatable, Sendable {
    public var relayUrl: String = ""
    public var enrollmentToken: String = ""
    public var registeredRelay: String = ""
    public var sequence: Int64 = 0
    public var peers: [LinkedPeer] = []
    public var seen: [String: Int64] = [:]
    public var lastClip: [String: Int64] = [:]
    public var pendingRevocations: Set<String> = []

    public init() {}

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        relayUrl = try c.decodeIfPresent(String.self, forKey: .relayUrl) ?? ""
        enrollmentToken = try c.decodeIfPresent(String.self, forKey: .enrollmentToken) ?? ""
        registeredRelay = try c.decodeIfPresent(String.self, forKey: .registeredRelay) ?? ""
        sequence = try c.decodeIfPresent(Int64.self, forKey: .sequence) ?? 0
        peers = try c.decodeIfPresent([LinkedPeer].self, forKey: .peers) ?? []
        seen = try c.decodeIfPresent([String: Int64].self, forKey: .seen) ?? [:]
        lastClip = try c.decodeIfPresent([String: Int64].self, forKey: .lastClip) ?? [:]
        pendingRevocations = try c.decodeIfPresent(Set<String>.self, forKey: .pendingRevocations) ?? []
    }
}

public protocol LinkStore: Sendable {
    func load() -> LinkState
    func save(_ state: LinkState) throws
}

public final class MemoryLinkStore: LinkStore, @unchecked Sendable {
    private var state: LinkState
    private let lock = NSLock()
    public init(_ state: LinkState = LinkState()) { self.state = state }
    public func load() -> LinkState { lock.lock(); defer { lock.unlock() }; return state }
    public func save(_ state: LinkState) { lock.lock(); self.state = state; lock.unlock() }
}

/// Atomic JSON file. On iOS place it in the App Group container so extensions share links.
public final class FileLinkStore: LinkStore, @unchecked Sendable {
    let url: URL
    private let lock = NSLock()
    public init(url: URL) { self.url = url }

    public func load() -> LinkState {
        lock.lock(); defer { lock.unlock() }
        guard let data = try? Data(contentsOf: url), let state = try? Wire.decoder.decode(LinkState.self, from: data) else { return LinkState() }
        return state
    }

    public func save(_ state: LinkState) throws {
        lock.lock(); defer { lock.unlock() }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        #if os(iOS)
        try Wire.encoder().encode(state).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try Wire.encoder().encode(state).write(to: url, options: [.atomic])
        #endif
    }
}
