import DeviceLinkKit
import UIKit
import UniformTypeIdentifiers

/// "Send to device" in the iOS share sheet: text, links, photos and files go to every linked device.
final class ShareViewController: UIViewController {
    private let label = UILabel()
    private let spinner = UIActivityIndicatorView(style: .large)

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        label.font = .preferredFont(forTextStyle: .headline)
        label.adjustsFontForContentSizeCategory = true
        label.numberOfLines = 0
        label.textAlignment = .center
        label.text = "Sending…"
        let stack = UIStackView(arrangedSubviews: [spinner, label])
        stack.axis = .vertical
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            stack.leadingAnchor.constraint(greaterThanOrEqualTo: view.layoutMarginsGuide.leadingAnchor),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: view.layoutMarginsGuide.trailingAnchor),
        ])
        spinner.startAnimating()
        Task { await send() }
    }

    @MainActor
    private func send() async {
        do {
            let client = try LinkEnvironment.makeClient()
            guard let content = try await loadContent() else { return finish("Nothing to send") }
            let outcomes = try await client.send(content)
            let peers = await client.peers
            if outcomes.isEmpty { return finish("No linked device yet. Open DeviceLink to link one.") }
            let names = peers.filter { peer in outcomes.contains { $0.peerId == peer.id && $0.accepted } }.map(\.name)
            finish(names.isEmpty ? "Could not send. Check the connection." : "Sent to \(names.joined(separator: ", ")) ✓")
        } catch {
            finish("Could not send: \(error)")
        }
    }

    private func loadContent() async throws -> OutgoingContent? {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem])?.flatMap { $0.attachments ?? [] } ?? []
        for provider in providers {
            if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                let item = try await provider.loadItem(forTypeIdentifier: UTType.image.identifier)
                if let url = item as? URL, let data = try? Data(contentsOf: url) {
                    let type = UTType(filenameExtension: url.pathExtension) ?? .jpeg
                    return .image(name: url.lastPathComponent, mime: type.preferredMIMEType ?? "image/jpeg", data: data)
                }
                if let image = item as? UIImage, let data = image.pngData() { return .image(name: "image.png", mime: "image/png", data: data) }
                if let data = item as? Data { return .image(name: "image.jpg", mime: "image/jpeg", data: data) }
            }
            if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier),
               let url = try await provider.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL {
                if url.isFileURL, let data = try? Data(contentsOf: url) {
                    let type = UTType(filenameExtension: url.pathExtension)
                    return .file(name: url.lastPathComponent, mime: type?.preferredMIMEType ?? "application/octet-stream", data: data)
                }
                return .text(url.absoluteString)
            }
            if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
               let text = try await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier) as? String {
                return .text(text)
            }
            if provider.hasItemConformingToTypeIdentifier(UTType.data.identifier),
               let url = try await provider.loadItem(forTypeIdentifier: UTType.data.identifier) as? URL, let data = try? Data(contentsOf: url) {
                let type = UTType(filenameExtension: url.pathExtension)
                return .file(name: url.lastPathComponent, mime: type?.preferredMIMEType ?? "application/octet-stream", data: data)
            }
        }
        return nil
    }

    @MainActor
    private func finish(_ text: String) {
        spinner.stopAnimating()
        label.text = text
        Task {
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            extensionContext?.completeRequest(returningItems: nil)
        }
    }
}
