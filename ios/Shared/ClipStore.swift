import DeviceLinkKit
import Foundation
import UIKit
import UniformTypeIdentifiers

enum ClipDirection: String, Codable { case received, sent }
enum ClipState: String, Codable { case pending, copied, delivered, shareNeeded, failed }

/// One entry in the clip list. Lives in the App Group container; pruned after 24 hours.
struct ClipRecord: Codable, Identifiable, Equatable {
    var id: String
    var direction: ClipDirection
    var peerName: String
    var kind: String
    var text: String?
    var fileName: String?
    var mime: String?
    var at: Date
    var state: ClipState
    var html: String? = nil
    /// Sender marked it sensitive: masked in lists, expires from the clipboard after 2 minutes.
    var sensitive: Bool = false

    init(id: String, direction: ClipDirection, peerName: String, kind: String, text: String?, fileName: String?, mime: String?,
         at: Date, state: ClipState, html: String? = nil, sensitive: Bool = false) {
        self.id = id; self.direction = direction; self.peerName = peerName; self.kind = kind; self.text = text
        self.fileName = fileName; self.mime = mime; self.at = at; self.state = state; self.html = html; self.sensitive = sensitive
    }

    // Older history files lack the newer fields.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id); direction = try c.decode(ClipDirection.self, forKey: .direction)
        peerName = try c.decode(String.self, forKey: .peerName); kind = try c.decode(String.self, forKey: .kind)
        text = try c.decodeIfPresent(String.self, forKey: .text); fileName = try c.decodeIfPresent(String.self, forKey: .fileName)
        mime = try c.decodeIfPresent(String.self, forKey: .mime); at = try c.decode(Date.self, forKey: .at)
        state = try c.decode(ClipState.self, forKey: .state); html = try c.decodeIfPresent(String.self, forKey: .html)
        sensitive = try c.decodeIfPresent(Bool.self, forKey: .sensitive) ?? false
    }

    var fileURL: URL? { fileName.map { ClipStore.filesDirectory.appendingPathComponent(id).appendingPathComponent($0) } }
    var isImage: Bool { mime?.hasPrefix("image/") == true }
}

@MainActor
final class ClipStore: ObservableObject {
    static let shared = ClipStore()
    nonisolated static var filesDirectory: URL { LinkEnvironment.container.appendingPathComponent("DeviceLink/Received") }
    private nonisolated let url = LinkEnvironment.container.appendingPathComponent("DeviceLink/history.json")
    @Published private(set) var records: [ClipRecord] = []

    init() { reload() }

    func reload() {
        let cutoff = Date().addingTimeInterval(-24 * 3600)
        records = ((try? JSONDecoder().decode([ClipRecord].self, from: Data(contentsOf: url))) ?? []).filter { $0.at > cutoff }
    }

    func add(_ record: ClipRecord) { save([record] + records.filter { $0.id != record.id }) }
    func update(_ id: String, _ state: ClipState) {
        save(records.map { var item = $0; if item.id == id && item.state != .copied { item.state = state }; return item })
    }
    func remove(_ id: String) { save(records.filter { $0.id != id }); try? FileManager.default.removeItem(at: Self.filesDirectory.appendingPathComponent(id)) }
    func clear() { save([]); try? FileManager.default.removeItem(at: Self.filesDirectory) }

    private func save(_ next: [ClipRecord]) {
        let cutoff = Date().addingTimeInterval(-24 * 3600)
        records = Array(next.filter { $0.at > cutoff }.prefix(50))
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? JSONEncoder().encode(records).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    /// Applies a received item: clipboard for text/images, file + share prompt otherwise.
    func apply(_ item: IncomingItem, autoCopy: Bool = true) -> ReceiptStatus {
        var record = ClipRecord(id: item.id, direction: .received, peerName: item.peer.name, kind: item.kind.rawValue,
                                text: item.text, fileName: nil, mime: item.payload.mime, at: Date(), state: .shareNeeded,
                                html: item.html, sensitive: item.sensitive)
        if let data = item.bytes(), let name = item.fileName {
            let directory = Self.filesDirectory.appendingPathComponent(item.id)
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try? data.write(to: directory.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            record.fileName = name
        }
        let copied = autoCopy && !item.stale && Pasteboard.write(record)
        record.state = copied ? .copied : .shareNeeded
        add(record)
        return copied ? .copied : .delivered
    }
}

/// Clipboard writes. iOS lets an app set the pasteboard whenever it runs; reads of other apps' content prompt.
@MainActor
enum Pasteboard {
    /// changeCount right after our own write, so the auto-share observer can ignore it.
    static var ownChangeCount = -1

    @discardableResult
    static func write(_ record: ClipRecord) -> Bool {
        if let text = record.text {
            var item: [String: Any] = [UTType.utf8PlainText.identifier: text]
            if let html = record.html { item[UTType.html.identifier] = html }
            // Sensitive clips stay on this device only and disappear after two minutes.
            let options: [UIPasteboard.OptionsKey: Any] = record.sensitive
                ? [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)] : [:]
            UIPasteboard.general.setItems([item], options: options)
        } else if record.isImage, let url = record.fileURL, let data = try? Data(contentsOf: url) {
            let type = record.mime.flatMap { UTType(mimeType: $0) }?.identifier ?? UTType.png.identifier
            UIPasteboard.general.setData(data, forPasteboardType: type)
        } else {
            return false
        }
        ownChangeCount = UIPasteboard.general.changeCount
        return true
    }
}
