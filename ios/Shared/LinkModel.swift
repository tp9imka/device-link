import DeviceLinkKit
import SwiftUI
import UIKit

/// App-side state for DeviceLink: links, receiving while active, sending, pairing.
@MainActor
final class LinkModel: ObservableObject {
    @Published private(set) var peers: [LinkedPeer] = []
    @Published private(set) var status: ReceiverStatus = .idle
    @Published var pairingURI: String?
    @Published var pairingExpiresAt: Date?
    @Published var message: String?
    @Published var busy = false
    let clips = ClipStore.shared
    private(set) var client: DeviceLinkClient?
    private var receiving: Task<Void, Never>?
    private var pairing: Task<Void, Never>?
    private var events: Task<Void, Never>?

    @Published private(set) var relayConfigured = false
    @Published private(set) var diagnostics: Diagnostics?
    /// Shown once right after linking; both screens show the same six digits.
    @Published var linkedConfirmation: LinkedPeer?
    /// Devices sends go to; empty = all linked devices. Persisted.
    @Published var sendTargets: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "sendTargets") ?? []) {
        didSet { UserDefaults.standard.set(Array(sendTargets), forKey: "sendTargets") }
    }

    init() {
        do {
            let client = try LinkEnvironment.makeClient()
            self.client = client
            events = Task { [weak self] in
                for await event in await client.events() { await self?.handle(event) }
            }
            Task { await refresh() }
        } catch {
            message = "DeviceLink could not create its device keys: \(error)"
        }
    }

    func refresh() async {
        guard let client else { return }
        peers = await client.peers
        relayConfigured = await client.isConfigured
        diagnostics = await client.currentDiagnostics()
        sendTargets = sendTargets.filter { id in peers.contains { $0.id == id } }
        clips.reload()
    }

    private func handle(_ event: LinkEvent) async {
        switch event {
        case .status(let value): status = value
        case .receipt(let id, _, let status):
            clips.update(id, status == .copied ? .copied : status == .delivered ? .delivered : .failed)
        case .peerLinked, .peerUnlinked: await refresh()
        }
    }

    // MARK: Receiving (foreground; push or the Shortcuts action covers the rest)

    func setActive(_ active: Bool) {
        if active {
            guard receiving == nil, let client else { return }
            receiving = Task { [clips] in
                await client.runReceiver { item in await MainActor.run { clips.apply(item) } }
            }
            Task { await refresh() }
        } else {
            receiving?.cancel()
            receiving = nil
        }
    }

    // MARK: Pairing

    /// Shows a one-time code. Works on a fresh install when a relay is built in.
    func showCode() {
        guard let client else { return }
        pairing?.cancel()
        pairing = Task {
            do {
                let session = try await client.invite()
                pairingURI = session.uri
                pairingExpiresAt = Date(timeIntervalSince1970: TimeInterval(session.expiresAt) / 1000)
                let peer = try await client.awaitPeer(session)
                pairingURI = nil
                linkedConfirmation = peer
                await refresh()
                setActive(true)
            } catch is CancellationError {
            } catch {
                pairingURI = nil
                message = describe(error)
            }
        }
    }

    func cancelCode() { pairing?.cancel(); pairingURI = nil }

    /// Joins from a scanned code, an opened link or pasted text; also accepts admin setup links.
    func open(_ link: String) {
        guard let client else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                if try await client.applySetupLink(link) { message = "Relay configured"; return }
                let peer = try await client.join(link)
                linkedConfirmation = peer
                await refresh()
                setActive(true)
            } catch {
                message = describe(error)
            }
        }
    }

    func unlink(_ peer: LinkedPeer) {
        Task { try? await client?.unlink(peer.id); await refresh() }
    }

    // MARK: Sending

    func send(_ content: OutgoingContent, preview: String?, fileName: String? = nil, mime: String? = nil) {
        guard let client else { return }
        Task {
            do {
                let outcomes = try await client.send(content, to: sendTargets.isEmpty ? nil : sendTargets)
                let names = Dictionary(uniqueKeysWithValues: peers.map { ($0.id, $0.name) })
                for outcome in outcomes {
                    clips.add(ClipRecord(id: outcome.itemId ?? UUID().uuidString.lowercased(), direction: .sent,
                                         peerName: names[outcome.peerId] ?? "", kind: fileName == nil ? "text" : "file",
                                         text: preview, fileName: fileName, mime: mime, at: Date(), state: outcome.accepted ? .pending : .failed))
                }
                if outcomes.isEmpty { message = "Link a device first" }
                else if outcomes.contains(where: { !$0.accepted }) { message = "Could not send to every device" }
            } catch {
                message = describe(error)
            }
        }
    }

    func sendText(_ text: String) {
        guard !text.isEmpty else { return }
        send(.text(text), preview: text)
    }

    func sendData(_ data: Data, name: String, mime: String) {
        let image = ["image/png", "image/jpeg", "image/heic", "image/gif", "image/webp"].contains(mime)
        send(image ? .image(name: name, mime: mime, data: data) : .file(name: name, mime: mime, data: data), preview: nil, fileName: name, mime: mime)
    }

    func registerPush(token: Data) {
        guard let client, !peers.isEmpty else { return }
        let hex = token.map { String(format: "%02x", $0) }.joined()
        #if DEBUG
        let environment = "sandbox"
        #else
        let environment = "production"
        #endif
        let topic = Bundle.main.bundleIdentifier ?? ""
        Task {
            guard let info = try? await client.relayInfo(), info.push?.contains("apns") == true else { return }
            try? await client.registerPush(token: hex, environment: environment, topic: topic)
        }
    }

    /// Plain-text report for bug reports. Contains no clipboard content or device names.
    var diagnosticsReport: String {
        guard let d = diagnostics else { return "No diagnostics yet" }
        func at(_ time: Int64?) -> String {
            time.map { DateFormatter.localizedString(from: Date(timeIntervalSince1970: TimeInterval($0) / 1000), dateStyle: .short, timeStyle: .medium) } ?? "never"
        }
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        return """
        DeviceLink \(version) on iOS \(UIDevice.current.systemVersion), \(LinkEnvironment.model)
        Device ID: \(d.deviceId.prefix(16))…
        Relay: \(d.relayURL.isEmpty ? "not configured" : d.relayURL) · registered: \(d.registered)
        Linked devices: \(d.peers) · status: \(status.rawValue)
        Last poll: \(at(d.lastPollAt)) · last receive: \(at(d.lastReceiveAt)) · last send: \(at(d.lastSendAt))
        Received: \(d.received) · sent: \(d.sent) (since app start)
        Last error: \(d.lastError ?? "none")\(d.lastErrorAt.map { " at " + at($0) } ?? "")
        """
    }

    func sendTestClip() {
        sendText("DeviceLink test clip · \(DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium))")
    }

    /// Unlinks everything (telling each device), deletes keys and history, and starts with a new identity.
    func resetDevice() {
        guard let client else { return }
        Task {
            try? await client.unlinkAll()
            setActive(false)
            clips.clear()
            Keychain.delete("identity.se"); Keychain.delete("identity.sw"); Keychain.delete("encryption.x25519")
            try? FileManager.default.removeItem(at: LinkEnvironment.container.appendingPathComponent("DeviceLink/links.json"))
            sendTargets = []
            do {
                let fresh = try LinkEnvironment.makeClient()
                self.client = fresh
                events?.cancel()
                events = Task { [weak self] in for await event in await fresh.events() { await self?.handle(event) } }
                await refresh()
                message = "Device reset. Show or scan a code to link again."
            } catch {
                message = "Reset failed: \(error)"
            }
        }
    }

    private func describe(_ error: Error) -> String {
        if let error = error as? DeviceLinkError {
            switch error {
            case .notConfigured: return "No relay configured. Scan a code from a linked device or the relay's Setup page."
            case .pairing(let reason): return reason
            case .relay(let status, _) where status == 403: return "The relay refused this device."
            default: return error.description
            }
        }
        if error is URLError { return "The relay could not be reached. Check the internet connection." }
        return error.localizedDescription
    }
}
