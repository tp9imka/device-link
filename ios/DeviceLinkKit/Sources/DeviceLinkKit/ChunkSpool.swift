import Foundation

/// Keeps the still-encrypted chunk envelopes of a file in transfer on the receiver, so a chunk can be
/// acknowledged as soon as it is stored and a restart mid-transfer resumes instead of losing the file.
/// Holding chunks unacknowledged on the relay instead would deadlock files above the relay's per-mailbox
/// byte quota (64 MiB default; ten 4 MiB chunks are ~71 MiB of envelopes). Twin of Kotlin `ChunkSpool`.
///
/// Bounded: at most `maxGroups` files in flight, each of at most `Limits.maxParts` parts, and a file is
/// dropped once no new part of it has arrived for `groupIdleMillis` (group idle expiry). Each part is kept
/// with the time it was received, so a file still arriving is not lost to its first part's envelope expiry.
/// Keys are `<sender id>_<group uuid>` (both validated before use, safe as file names).
protocol ChunkSpool: Sendable {
    /// Stores `part` received at `receivedAt`. A part already stored is kept as it is: a redelivery is not progress.
    func put(_ key: String, part: Int, envelope: Envelope, receivedAt: Int64) throws
    /// Part numbers stored for `key`, without reading them.
    func partNumbers(_ key: String) -> [Int]
    /// One stored part, read on demand so a whole file is never in memory; nil when missing or unreadable.
    func part(_ key: String, _ part: Int) -> Envelope?
    /// When `part` of `key` was stored; nil when it is missing.
    func receivedAt(_ key: String, _ part: Int) -> Int64?
    func keys() -> [String]
    func remove(_ key: String)
    /// Group idle expiry: drops every file with no new part for `groupIdleMillis` and returns their keys.
    func purge(now: Int64) -> [String]
}

extension ChunkSpool {
    /// Stored parts of `key`, without reading them.
    func count(_ key: String) -> Int { partNumbers(key).count }
}

enum ChunkSpoolKey {
    static let maxGroups = 3
    /// One envelope lifetime without a new part ends a file in transfer.
    static let groupIdleMillis = Limits.defaultLifetimeMillis
    static func key(peerId: String, group: String) -> String { "\(peerId)_\(group)" }
    static func peer(of key: String) -> String { String(key.prefix { $0 != "_" }) }
}

final class MemoryChunkSpool: ChunkSpool, @unchecked Sendable {
    private var entries: [String: [Int: (envelope: Envelope, receivedAt: Int64)]] = [:]
    private var order: [String] = []
    private let lock = NSLock()

    func put(_ key: String, part: Int, envelope: Envelope, receivedAt: Int64) {
        lock.lock(); defer { lock.unlock() }
        if entries[key] == nil {
            while order.count >= ChunkSpoolKey.maxGroups { entries[order.removeFirst()] = nil }
            order.append(key)
        }
        if entries[key]?[part] == nil { entries[key, default: [:]][part] = (envelope, receivedAt) }
    }
    func partNumbers(_ key: String) -> [Int] { lock.lock(); defer { lock.unlock() }; return (entries[key]?.keys).map { $0.sorted() } ?? [] }
    func part(_ key: String, _ part: Int) -> Envelope? { lock.lock(); defer { lock.unlock() }; return entries[key]?[part]?.envelope }
    func receivedAt(_ key: String, _ part: Int) -> Int64? { lock.lock(); defer { lock.unlock() }; return entries[key]?[part]?.receivedAt }
    func keys() -> [String] { lock.lock(); defer { lock.unlock() }; return order }
    func remove(_ key: String) { lock.lock(); defer { lock.unlock() }; entries[key] = nil; order.removeAll { $0 == key } }
    func purge(now: Int64) -> [String] {
        lock.lock(); defer { lock.unlock() }
        let idle = order.filter { key in
            guard let last = entries[key]?.values.map(\.receivedAt).max() else { return true }
            return now - last >= ChunkSpoolKey.groupIdleMillis
        }
        for key in idle { entries[key] = nil }
        order.removeAll { idle.contains($0) }
        return idle
    }
}

/// One directory per file, one `<part>-<receivedAt>.chunk` per chunk envelope (ciphertext only). Anything
/// else in a file's directory (a `<part>-<expiresAt>.json` from an older version) is deleted on purge.
final class FileChunkSpool: ChunkSpool, @unchecked Sendable {
    let directory: URL
    private let lock = NSLock()
    init(directory: URL) { self.directory = directory }

    private func list(_ url: URL) -> [URL] {
        (try? FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: nil)) ?? []
    }

    /// Part and receive time encoded in a chunk file name; nil for anything else.
    private func parse(_ name: String) -> (Int, Int64)? {
        guard name.hasSuffix(".chunk") else { return nil }
        let fields = name.dropLast(6).split(separator: "-", omittingEmptySubsequences: false)
        guard fields.count == 2, (1...2).contains(fields[0].count), (1...19).contains(fields[1].count),
              fields.allSatisfy({ $0.allSatisfy(\.isASCII) && $0.allSatisfy(\.isNumber) }),
              let part = Int(fields[0]), let received = Int64(fields[1]) else { return nil }
        return (part, received)
    }

    /// (file, part, receivedAt) for each stored chunk of `key`.
    private func entries(_ key: String) -> [(URL, Int, Int64)] {
        list(directory.appendingPathComponent(key)).compactMap { url in
            parse(url.lastPathComponent).map { (url, $0.0, $0.1) }
        }
    }

    /// When the newest part of `key` arrived; nil when it holds none.
    private func lastProgress(_ key: String) -> Int64? { entries(key).map(\.2).max() }

    private func unlockedKeys() -> [String] {
        list(directory).map(\.lastPathComponent).filter { name in
            name.count == 101 && name.range(of: "^[0-9a-f]{64}_[0-9a-f-]{36}$", options: .regularExpression) != nil
        }
    }

    func put(_ key: String, part: Int, envelope: Envelope, receivedAt: Int64) throws {
        lock.lock(); defer { lock.unlock() }
        let group = directory.appendingPathComponent(key)
        if !FileManager.default.fileExists(atPath: group.path) {
            let existing = unlockedKeys().sorted { (lastProgress($0) ?? 0) < (lastProgress($1) ?? 0) }
            for old in existing.prefix(max(0, existing.count - ChunkSpoolKey.maxGroups + 1)) {
                try? FileManager.default.removeItem(at: directory.appendingPathComponent(old))
            }
            try FileManager.default.createDirectory(at: group, withIntermediateDirectories: true)
            var root = directory
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? root.setResourceValues(values)
        }
        if entries(key).contains(where: { $0.1 == part }) { return }
        let target = group.appendingPathComponent("\(part)-\(receivedAt).chunk")
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

    func receivedAt(_ key: String, _ part: Int) -> Int64? {
        lock.lock(); defer { lock.unlock() }
        return entries(key).first(where: { $0.1 == part })?.2
    }

    func keys() -> [String] { lock.lock(); defer { lock.unlock() }; return unlockedKeys() }

    func remove(_ key: String) {
        lock.lock(); defer { lock.unlock() }
        try? FileManager.default.removeItem(at: directory.appendingPathComponent(key))
    }

    func purge(now: Int64) -> [String] {
        lock.lock(); defer { lock.unlock() }
        return unlockedKeys().filter { key in
            for url in list(directory.appendingPathComponent(key)) where parse(url.lastPathComponent) == nil {
                try? FileManager.default.removeItem(at: url)
            }
            guard let last = lastProgress(key), now - last < ChunkSpoolKey.groupIdleMillis else {
                try? FileManager.default.removeItem(at: directory.appendingPathComponent(key))
                return true
            }
            return false
        }
    }
}
