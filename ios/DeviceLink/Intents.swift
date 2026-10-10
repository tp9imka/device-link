import AppIntents
import DeviceLinkKit
import UIKit

/// Collects the newest text across the items fetched during one intent run.
final class LatestBox: @unchecked Sendable {
    var text: String?
    var count = 0
}

/// "Get DeviceLink clip": fetches everything waiting without opening the app, puts the newest on the
/// clipboard and returns its text. Bind it to Back Tap / the Action button for one-gesture paste.
/// Never re-copies an old clip: when nothing new is waiting it says so.
struct GetLatestClipIntent: AppIntent {
    static var title: LocalizedStringResource = "Get DeviceLink clip"
    static var description = IntentDescription("Fetches what your linked device sent and puts it on the clipboard.")
    static var openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
        let box = LatestBox()
        let result: DrainResult
        do {
            let client = try LinkEnvironment.client()
            if await client.peers.isEmpty { return .result(value: "", dialog: "\(SendMessages.notLinked)") }
            result = try await drainWaiting(client, into: box)
        } catch {
            return .result(value: "", dialog: "\(SendMessages.describe(error))")
        }
        if box.count == 0 {
            // The receive loop holds the mailbox poll: whatever arrives, it copies right away.
            if result.collided { return .result(value: "", dialog: "DeviceLink is already receiving: new clips land on the clipboard by themselves.") }
            return .result(value: "", dialog: "Nothing new from your linked devices.")
        }
        let items = box.count == 1 ? "1 item" : "\(box.count) items"
        return .result(value: box.text ?? "", dialog: "Copied \(items). Ready to paste.")
    }
}

/// Drains the mailbox into the clip store. A collision can also be the relay still holding a poll
/// from an app process that just ended, so it retries briefly before giving up.
@MainActor
func drainWaiting(_ client: DeviceLinkClient, into box: LatestBox? = nil) async throws -> DrainResult {
    let store = ClipStore.shared
    var attempt = 0
    while true {
        let result = try await client.drain { item in
            await MainActor.run {
                if let box {
                    box.count += 1
                    if let text = item.text, !item.stale { box.text = text }
                }
                return store.apply(item)
            }
        }
        // In front, the collision is this app's own receive loop, which delivers on its own.
        attempt += 1
        if !result.collided || attempt == 3 || UIApplication.shared.applicationState == .active { return result }
        try await Task.sleep(nanoseconds: 2_500_000_000)
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
        let client: DeviceLinkClient
        let outcomes: [SendOutcome]
        do {
            client = try LinkEnvironment.client()
            outcomes = try await client.send(.text(text))
        } catch {
            return .result(dialog: "\(SendMessages.describe(error))")
        }
        let peers = await client.peers
        if outcomes.isEmpty { return .result(dialog: "\(SendMessages.notLinked)") }
        let names = peers.filter { peer in outcomes.contains { $0.peerId == peer.id && $0.accepted } }.map(\.name)
        if names.isEmpty { return .result(dialog: "\(SendMessages.describe(outcomeError: outcomes.first?.error))") }
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
