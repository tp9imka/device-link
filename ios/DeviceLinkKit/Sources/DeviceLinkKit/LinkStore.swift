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
    /// Load -> modify -> save as one step. The engine writes only through this, so concurrent writers
    /// (on iOS the app, share extension and notification extension share one file) never drop each
    /// other's `seen`, `lastClip` or `sequence` updates.
    @discardableResult
    func update(_ transform: (inout LinkState) throws -> Void) throws -> LinkState
}

extension LinkStore {
    /// Not atomic; stores shared between threads or processes override it.
    @discardableResult
    public func update(_ transform: (inout LinkState) throws -> Void) throws -> LinkState {
        var state = load()
        try transform(&state)
        try save(state)
        return state
    }
}

public final class MemoryLinkStore: LinkStore, @unchecked Sendable {
    private var state: LinkState
    private let lock = NSLock()
    public init(_ state: LinkState = LinkState()) { self.state = state }
    public func load() -> LinkState { lock.lock(); defer { lock.unlock() }; return state }
    public func save(_ state: LinkState) { lock.lock(); self.state = state; lock.unlock() }
    @discardableResult
    public func update(_ transform: (inout LinkState) throws -> Void) throws -> LinkState {
        lock.lock(); defer { lock.unlock() }
        var next = state
        try transform(&next)
        state = next
        return next
    }
}

/// Atomic JSON file. On iOS place it in the App Group container so extensions share links.
///
/// Writes are serialized across processes with `flock` on a sibling `<name>.lock` file, and `update`
/// re-reads the file under that lock, so the app and its extensions never lose each other's changes.
/// The lock is held only for one synchronous read-modify-write (never across a suspension point), so a
/// suspended process does not keep a file lock in the shared container.
public final class FileLinkStore: LinkStore, @unchecked Sendable {
    let url: URL
    private let lock = NSLock()
    public init(url: URL) { self.url = url }

    var lockURL: URL { url.deletingLastPathComponent().appendingPathComponent(url.lastPathComponent + ".lock") }

    public func load() -> LinkState {
        lock.lock(); defer { lock.unlock() }
        return read()
    }

    public func save(_ state: LinkState) throws {
        try exclusive { try write(state) }
    }

    @discardableResult
    public func update(_ transform: (inout LinkState) throws -> Void) throws -> LinkState {
        try exclusive {
            var state = read()
            try transform(&state)
            try write(state)
            return state
        }
    }

    private func read() -> LinkState {
        guard let data = try? Data(contentsOf: url), let state = try? Wire.decoder.decode(LinkState.self, from: data) else { return LinkState() }
        return state
    }

    private func write(_ state: LinkState) throws {
        #if os(iOS)
        try Wire.encoder().encode(state).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try Wire.encoder().encode(state).write(to: url, options: [.atomic])
        #endif
    }

    /// In-process lock first (threads of one process each need it; flock is per open file), then the
    /// cross-process advisory lock.
    private func exclusive<T>(_ body: () throws -> T) throws -> T {
        lock.lock(); defer { lock.unlock() }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let descriptor = open(lockURL.path, O_RDWR | O_CREAT | O_CLOEXEC, 0o600)
        guard descriptor >= 0 else { throw DeviceLinkError.invalid("link store lock (errno \(errno))") }
        defer { close(descriptor) }
        while flock(descriptor, LOCK_EX) != 0 {
            guard errno == EINTR else { throw DeviceLinkError.invalid("link store lock (errno \(errno))") }
        }
        defer { flock(descriptor, LOCK_UN) }
        return try body()
    }
}

/// Stores with a durable location keep chunked files in transfer on disk (see `ChunkSpool`).
protocol ChunkSpoolLocation {
    var chunkDirectory: URL { get }
}

extension FileLinkStore: ChunkSpoolLocation {
    /// Encrypted chunks of files in transfer live next to the link state.
    var chunkDirectory: URL { url.deletingLastPathComponent().appendingPathComponent(url.lastPathComponent + ".chunks") }
}
