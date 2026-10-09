import AppKit
import CoreImage.CIFilterBuiltins
import DeviceLinkKit
import Security
import SwiftUI

/// DeviceLink for the Mac menu bar: show a code or paste a link to pair, then the clipboard syncs
/// both ways automatically (macOS lets apps read the pasteboard, unlike phones).
@main
struct DeviceLinkMacApp: App {
    @StateObject private var model = MacModel()

    var body: some Scene {
        MenuBarExtra {
            MenuContent(model: model)
        } label: {
            Image(systemName: model.syncing ? "link.circle.fill" : "link.circle")
        }
        .menuBarExtraStyle(.window)
    }
}

/// Keys live in the login keychain; links and settings in Application Support.
enum MacKeys {
    private static let service = "dev.devicelink.mac"

    static func load(_ account: String, create: () -> Data) throws -> Data {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecAttrAccount as String: account, kSecReturnData as String: true]
        var result: AnyObject?
        if SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data { return data }
        let data = create()
        let item: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                   kSecAttrAccount as String: account, kSecValueData as String: data,
                                   kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw DeviceLinkError.invalid("keychain \(status)") }
        return data
    }
}

@MainActor
final class MacModel: ObservableObject {
    @Published var peers: [LinkedPeer] = []
    @Published var syncing = false
    @Published var pairingURI: String?
    @Published var message: String?
    @Published var recent: [String] = []
    @Published var relay = ""
    private var client: DeviceLinkClient?
    private var receiver: Task<Void, Never>?
    private var watcher: Timer?
    private var lastChange = NSPasteboard.general.changeCount

    init() {
        do {
            let identity = try SoftwareIdentity(rawRepresentation: try MacKeys.load("identity") { SoftwareIdentity().privateKey.rawRepresentation })
            let encryption = try EncryptionKeyPair(rawRepresentation: try MacKeys.load("encryption") { EncryptionKeyPair().privateKey.rawRepresentation })
            let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("DeviceLink")
            let host = Bundle.main.object(forInfoDictionaryKey: "DLRelayHost") as? String ?? ""
            let scheme = (Bundle.main.object(forInfoDictionaryKey: "DLRelayScheme") as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "https"
            client = try DeviceLinkClient(identity: identity, encryption: encryption,
                                          store: FileLinkStore(url: support.appendingPathComponent("links.json")),
                                          config: LinkConfig(deviceName: Host.current().localizedName ?? "Mac", platform: "desktop", model: "macOS",
                                                             appVersion: "2.0.0", allowInsecureRelay: scheme == "http",
                                                             defaultRelayURL: host.isEmpty || host.hasPrefix("$(") ? "" : "\(scheme)://\(host)",
                                                             defaultEnrollmentToken: Bundle.main.object(forInfoDictionaryKey: "DLEnrollmentToken") as? String ?? ""))
            Task { await refresh(); start() }
        } catch {
            message = "Could not create device keys: \(error)"
        }
    }

    func refresh() async {
        guard let client else { return }
        peers = await client.peers
        relay = await client.relayURL
    }

    /// Receive loop + pasteboard watcher. Both run only while devices are linked.
    func start() {
        guard let client, receiver == nil else { return }
        syncing = true
        receiver = Task { [weak self] in
            await client.runReceiver { item in
                await MainActor.run { self?.apply(item) ?? .delivered }
            }
        }
        watcher = Timer.scheduledTimer(withTimeInterval: 0.7, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.checkPasteboard() }
        }
    }

    func stop() {
        receiver?.cancel(); receiver = nil
        watcher?.invalidate(); watcher = nil
        syncing = false
    }

    private func apply(_ item: IncomingItem) -> ReceiptStatus {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        var copied = false
        if let text = item.text {
            copied = pasteboard.setString(text, forType: .string)
            if let html = item.html { pasteboard.setString(html, forType: .html) }
        } else if item.kind == .image, let data = item.bytes(), let image = NSImage(data: data) {
            copied = pasteboard.writeObjects([image])
        } else if let data = item.bytes(), let name = item.fileName {
            let url = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0].appendingPathComponent(name)
            copied = (try? data.write(to: url, options: .withoutOverwriting)) != nil && pasteboard.writeObjects([url as NSURL])
        }
        lastChange = pasteboard.changeCount
        recent.insert("↓ \(item.peer.name): \(item.sensitive ? "Sensitive content" : item.text?.prefix(60).description ?? item.fileName ?? "image")", at: 0)
        recent = Array(recent.prefix(8))
        return copied ? .copied : .delivered
    }

    private func checkPasteboard() {
        let pasteboard = NSPasteboard.general
        guard pasteboard.changeCount != lastChange, let client, !peers.isEmpty else { return }
        lastChange = pasteboard.changeCount
        // Respect password managers: they mark secrets as concealed/transient.
        let types = pasteboard.types ?? []
        if types.contains(NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")) ||
            types.contains(NSPasteboard.PasteboardType("org.nspasteboard.TransientType")) { return }
        let content: OutgoingContent
        if let text = pasteboard.string(forType: .string), !text.isEmpty, text.utf8.count <= Limits.maxTextBytes {
            content = .styledText(text, html: pasteboard.string(forType: .html), sensitive: false)
            recent.insert("↑ \(text.prefix(60))", at: 0)
        } else if let image = NSImage(pasteboard: pasteboard), let tiff = image.tiffRepresentation,
                  let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) {
            content = .image(name: "clipboard.png", mime: "image/png", data: png)
            recent.insert("↑ image", at: 0)
        } else { return }
        recent = Array(recent.prefix(8))
        Task { _ = try? await client.send(content) }
    }

    func showCode() {
        guard let client else { return }
        Task {
            do {
                let session = try await client.invite()
                pairingURI = session.uri
                let peer = try await client.awaitPeer(session)
                pairingURI = nil
                message = "Linked with \(peer.name) · code \(peer.pairingCode ?? "")"
                await refresh(); start()
            } catch {
                pairingURI = nil
                message = "\(error)"
            }
        }
    }

    func join(_ link: String) {
        guard let client else { return }
        Task {
            do {
                if try await client.applySetupLink(link) { message = "Relay configured"; await refresh(); return }
                let peer = try await client.join(link)
                message = "Linked with \(peer.name) · code \(peer.pairingCode ?? "")"
                await refresh(); start()
            } catch { message = "\(error)" }
        }
    }

    func unlink(_ peer: LinkedPeer) { Task { try? await client?.unlink(peer.id); await refresh() } }

    static func qr(_ text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return nil }
        let rep = NSCIImageRep(ciImage: output)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}

struct MenuContent: View {
    @ObservedObject var model: MacModel
    @State private var link = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("DeviceLink").font(.headline)
                Spacer()
                Toggle("Sync", isOn: Binding(get: { model.syncing }, set: { $0 ? model.start() : model.stop() })).toggleStyle(.switch)
            }
            if let uri = model.pairingURI, let image = MacModel.qr(uri) {
                Image(nsImage: image).interpolation(.none).resizable().frame(width: 220, height: 220)
                Text("Scan with the other device. Valid 5 minutes.").font(.caption).foregroundStyle(.secondary)
            }
            if model.peers.isEmpty {
                Text("Not linked yet").foregroundStyle(.secondary)
            } else {
                ForEach(model.peers) { peer in
                    HStack { Text(peer.name); Spacer(); Button("Unlink") { model.unlink(peer) }.buttonStyle(.link) }
                }
            }
            HStack {
                Button("Show my code") { model.showCode() }
                TextField("Paste a pairing link", text: $link).onSubmit { model.join(link); link = "" }
            }
            if !model.recent.isEmpty {
                Divider()
                ForEach(model.recent, id: \.self) { Text($0).lineLimit(1).font(.caption) }
            }
            if let message = model.message { Text(message).font(.caption).foregroundStyle(.secondary) }
            Divider()
            HStack {
                Text(model.relay.isEmpty ? "No relay" : model.relay).font(.caption2).foregroundStyle(.secondary)
                Spacer()
                Button("Quit") { NSApplication.shared.terminate(nil) }
            }
        }
        .padding(14)
        .frame(width: 320)
    }
}
