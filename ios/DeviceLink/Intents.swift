import AppIntents
import DeviceLinkKit
import UIKit

/// Collects the newest text across the items fetched during one intent run.
private final class LatestBox: @unchecked Sendable {
    var text: String?
    var count = 0
}

/// "Get DeviceLink clip": fetches waiting items without opening the app, puts the newest on the
/// clipboard and returns its text. Bind it to Back Tap / the Action button for one-gesture paste.
struct GetLatestClipIntent: AppIntent {
    static var title: LocalizedStringResource = "Get DeviceLink clip"
    static var description = IntentDescription("Fetches what your linked device sent and puts it on the clipboard.")
    static var openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
        let client = try LinkEnvironment.makeClient()
        let store = ClipStore.shared
        let box = LatestBox()
        for _ in 0..<10 {
            let received = try await client.receiveOnce(wait: 0) { item in
                await MainActor.run {
                    box.count += 1
                    if let text = item.text, !item.stale { box.text = text }
                    return store.apply(item)
                }
            }
            if received == 0 { break }
        }
        if box.count == 0, let latest = store.records.first(where: { $0.direction == .received }) {
            Pasteboard.write(latest)
            return .result(value: latest.text ?? "", dialog: "Nothing new. Copied the last clip again.")
        }
        if box.count == 0 { return .result(value: "", dialog: "Nothing waiting.") }
        let items = box.count == 1 ? "1 item" : "\(box.count) items"
        return .result(value: box.text ?? "", dialog: "Copied \(items). Ready to paste.")
    }
}

/// "Send to DeviceLink": in Shortcuts, chain Get Clipboard → Send to DeviceLink (iOS does not let
/// apps read the clipboard in the background, Shortcuts can).
struct SendTextIntent: AppIntent {
    static var title: LocalizedStringResource = "Send to DeviceLink"
    static var description = IntentDescription("Sends text to your linked devices; it lands on their clipboard.")
    static var openAppWhenRun = false

    @Parameter(title: "Text") var text: String

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let client = try LinkEnvironment.makeClient()
        let outcomes = try await client.send(.text(text))
        let peers = await client.peers
        if outcomes.isEmpty { return .result(dialog: "No linked device yet.") }
        let names = peers.filter { peer in outcomes.contains { $0.peerId == peer.id && $0.accepted } }.map(\.name)
        if names.isEmpty { return .result(dialog: "Could not send.") }
        return .result(dialog: "Sent to \(names.joined(separator: ", ")).")
    }
}

struct DeviceLinkShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: GetLatestClipIntent(), phrases: ["Get clip from \(.applicationName)", "Paste from \(.applicationName)"],
                    shortTitle: "Get clip", systemImageName: "doc.on.clipboard")
        AppShortcut(intent: SendTextIntent(), phrases: ["Send with \(.applicationName)"],
                    shortTitle: "Send text", systemImageName: "paperplane")
    }
}
