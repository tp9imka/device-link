import Foundation

/// Keeps the still-encrypted chunk envelopes of a file in transfer on the receiver, so a chunk can be
/// acknowledged as soon as it is stored and a restart mid-transfer resumes instead of losing the file.
/// Holding chunks unacknowledged on the relay instead would deadlock files above the relay's per-mailbox
/// byte quota (64 MiB default; ten 4 MiB chunks are ~71 MiB of envelopes). Twin of Kotlin `ChunkSpool`.
///
/// Bounded: at most `maxGroups` files in flight, entries dropped at envelope expiry.
/// Keys are `<sender id>_<group uuid>` (both validated before use, safe as file names).
protocol ChunkSpool: Sendable {
    func put(_ key: String, part: Int, envelope: Envelope) throws
    /// Part numbers stored for `key`, without reading them.
    func partNumbers(_ key: String) -> [Int]
    /// One stored part, read on demand so a whole file is never in memory; nil when missing or unreadable.
    func part(_ key: String, _ part: Int) -> Envelope?
    func keys() -> [String]
    func remove(_ key: String)
    /// Drops every entry whose envelope has expired.
    func purge(now: Int64)
}

extension ChunkSpool {
    /// Stored parts of `key`, without reading them.
    func count(_ key: String) -> Int { partNumbers(key).count }
}

enum ChunkSpoolKey {
    static let maxGroups = 3
    static func key(peerId: String, group: String) -> String { "\(peerId)_\(group)" }
    static func peer(of key: String) -> String { String(key.prefix { $0 != "_" }) }
}

final class MemoryChunkSpool: ChunkSpool, @unchecked Sendable {
    private var entries: [String: [Int: Envelope]] = [:]
    private var order: [String] = []
    private let lock = NSLock()

    func put(_ key: String, part: Int, envelope: Envelope) {
        lock.lock(); defer { lock.unlock() }
        if entries[key] == nil {
            while order.count >= ChunkSpoolKey.maxGroups { entries[order.removeFirst()] = nil }
            order.append(key)
        }
        entries[key, default: [:]][part] = envelope
    }
    func partNumbers(_ key: String) -> [Int] { lock.lock(); defer { lock.unlock() }; return (entries[key]?.keys).map { $0.sorted() } ?? [] }
    func part(_ key: String, _ part: Int) -> Envelope? { lock.lock(); defer { lock.unlock() }; return entries[key]?[part] }
    func keys() -> [String] { lock.lock(); defer { lock.unlock() }; return order }
    func remove(_ key: String) { lock.lock(); defer { lock.unlock() }; entries[key] = nil; order.removeAll { $0 == key } }
    func purge(now: Int64) {
        lock.lock(); defer { lock.unlock() }
        for key in order {
            entries[key] = entries[key]?.filter { $0.value.expiresAt > now }
            if entries[key]?.isEmpty ?? true { entries[key] = nil }
        }
        order.removeAll { entries[$0] == nil }
    }
}

/// One directory per file, one `<part>-<expiresAt>.json` per chunk envelope (ciphertext only).
final class FileChunkSpool: ChunkSpool, @unchecked Sendable {
    let directory: URL
    private let lock = NSLock()
    init(directory: URL) { self.directory = directory }

    private func list(_ url: URL) -> [URL] {
        (try? FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: nil)) ?? []
    }

    /// (file, part, expiresAt) for each stored chunk of `key`.
    private func entries(_ key: String) -> [(URL, Int, Int64)] {
        list(directory.appendingPathComponent(key)).compactMap { url in
            let name = url.lastPathComponent
            guard name.hasSuffix(".json") else { return nil }
            let fields = name.dropLast(5).split(separator: "-")
            guard fields.count == 2, let part = Int(fields[0]), let expiry = Int64(fields[1]) else { return nil }
            return (url, part, expiry)
        }
    }

    private func unlockedKeys() -> [String] {
        list(directory).map(\.lastPathComponent).filter { name in
            name.count == 101 && name.range(of: "^[0-9a-f]{64}_[0-9a-f-]{36}$", options: .regularExpression) != nil
        }
    }

    func put(_ key: String, part: Int, envelope: Envelope) throws {
        lock.lock(); defer { lock.unlock() }
        let group = directory.appendingPathComponent(key)
        if !FileManager.default.fileExists(atPath: group.path) {
            let existing = unlockedKeys().sorted { (entries($0).map(\.2).min() ?? 0) < (entries($1).map(\.2).min() ?? 0) }
            for old in existing.prefix(max(0, existing.count - ChunkSpoolKey.maxGroups + 1)) {
                try? FileManager.default.removeItem(at: directory.appendingPathComponent(old))
            }
            try FileManager.default.createDirectory(at: group, withIntermediateDirectories: true)
            var root = directory
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? root.setResourceValues(values)
        }
        for (url, stored, _) in entries(key) where stored == part { try? FileManager.default.removeItem(at: url) }
        let target = group.appendingPathComponent("\(part)-\(envelope.expiresAt).json")
        #if os(iOS)
        try envelope.encode().write(to: target, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try envelope.encode().write(to: target, options: [.atomic])
        #endif
    }

    func partNumbers(_ key: String) -> [Int] { lock.lock(); defer { lock.unlock() }; return entries(key).map(\.1).sorted() }

    func part(_ key: String, _ part: Int) -> Envelope? {
        lock.lock(); defer { lock.unlock() }
        guard let url = entries(key).first(where: { $0.1 == part })?.0 else { return nil }
        return (try? Data(contentsOf: url)).flatMap { try? Envelope.decode($0) }
    }

    func keys() -> [String] { lock.lock(); defer { lock.unlock() }; return unlockedKeys() }

    func remove(_ key: String) {
        lock.lock(); defer { lock.unlock() }
        try? FileManager.default.removeItem(at: directory.appendingPathComponent(key))
    }

    func purge(now: Int64) {
        lock.lock(); defer { lock.unlock() }
        for key in unlockedKeys() {
            for (url, _, expiry) in entries(key) where expiry <= now { try? FileManager.default.removeItem(at: url) }
            if entries(key).isEmpty { try? FileManager.default.removeItem(at: directory.appendingPathComponent(key)) }
        }
    }
}
