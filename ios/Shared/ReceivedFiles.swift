import Foundation

/// Received files on disk, one directory per item id. History keeps 24 hours; so do the files.
/// UIKit-free so the app logic tests can run it on the Mac.
enum ReceivedFiles {
    static let maxAge: TimeInterval = 24 * 3600

    /// Deletes item directories (and stray files) in `directory` last modified more than `maxAge` ago.
    /// Returns how many were removed. Never logs: the entries are named after item ids and file names.
    @discardableResult
    static func prune(in directory: URL, maxAge: TimeInterval = maxAge, now: Date = Date()) -> Int {
        let manager = FileManager.default
        guard let entries = try? manager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey]) else {
            return 0
        }
        let cutoff = now.addingTimeInterval(-maxAge)
        var removed = 0
        for entry in entries {
            // An item directory is written once, when the file lands in it, so its date is the receipt time.
            guard let modified = try? entry.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate,
                  modified < cutoff else { continue }
            if (try? manager.removeItem(at: entry)) != nil { removed += 1 }
        }
        return removed
    }

    /// Streams a received file to `url` part by part, so a large file is never whole in memory. It is written in a
    /// temporary directory on the same volume and moved into place only when complete; a failed transfer leaves
    /// nothing at `url`. `replacing` overwrites an existing file, otherwise one makes this throw.
    static func write(to url: URL, replacing: Bool, attributes: [FileAttributeKey: Any]? = nil,
                      _ content: (_ sink: (Data) throws -> Void) throws -> Void) throws {
        let manager = FileManager.default
        let staging = try manager.url(for: .itemReplacementDirectory, in: .userDomainMask, appropriateFor: url, create: true)
        defer { try? manager.removeItem(at: staging) }
        let partial = staging.appendingPathComponent(url.lastPathComponent)
        guard manager.createFile(atPath: partial.path, contents: nil, attributes: attributes) else { throw CocoaError(.fileWriteUnknown) }
        let handle = try FileHandle(forWritingTo: partial)
        do {
            try content { try handle.write(contentsOf: $0) }
            try handle.close()
        } catch {
            try? handle.close()
            throw error
        }
        if replacing, manager.fileExists(atPath: url.path) { try manager.removeItem(at: url) }
        try manager.moveItem(at: partial, to: url)
    }
}
