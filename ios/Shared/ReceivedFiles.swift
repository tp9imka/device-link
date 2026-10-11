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
}
