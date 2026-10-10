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

    /// Thrown before a file is read when it is over the SDK's size limit.
    private struct TooLarge: Error {}

    @MainActor
    private func send() async {
        do {
            let client = try LinkEnvironment.client()
            if await client.peers.isEmpty { return finish(SendMessages.notLinked) }
            guard let content = try await loadContent() else { return finish("Nothing to send") }
            let outcomes = try await client.send(content)
            let peers = await client.peers
            if outcomes.isEmpty { return finish(SendMessages.notLinked) }
            let names = peers.filter { peer in outcomes.contains { $0.peerId == peer.id && $0.accepted } }.map(\.name)
            if names.isEmpty { return finish(SendMessages.describe(outcomeError: outcomes.first?.error)) }
            finish("Sent to \(names.joined(separator: ", ")) ✓")
        } catch is TooLarge {
            finish(SendMessages.tooLarge)
        } catch {
            finish(SendMessages.describe(error))
        }
    }

    /// Reads a shared file only after its size is known to fit; a large video would otherwise be
    /// loaded whole and get the extension killed for memory.
    private func readFile(_ url: URL) throws -> Data? {
        if let size = try? url.resourceValues(forKeys: [.fileSizeKey, .totalFileSizeKey]),
           let bytes = size.totalFileSize ?? size.fileSize, !SendMessages.fits(bytes: bytes) {
            throw TooLarge()
        }
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return nil }
        guard SendMessages.fits(bytes: data.count) else { throw TooLarge() }
        return data
    }

    private func loadContent() async throws -> OutgoingContent? {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem])?.flatMap { $0.attachments ?? [] } ?? []
        for provider in providers {
            if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                let item = try await provider.loadItem(forTypeIdentifier: UTType.image.identifier)
                if let url = item as? URL, let data = try readFile(url) {
                    let type = UTType(filenameExtension: url.pathExtension) ?? .jpeg
                    return .image(name: url.lastPathComponent, mime: type.preferredMIMEType ?? "image/jpeg", data: data)
                }
                if let image = item as? UIImage, let data = image.pngData() { return .image(name: "image.png", mime: "image/png", data: data) }
                if let data = item as? Data {
                    guard SendMessages.fits(bytes: data.count) else { throw TooLarge() }
                    return .image(name: "image.jpg", mime: "image/jpeg", data: data) }
            }
            if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier),
               let url = try await provider.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL {
                if url.isFileURL, let data = try readFile(url) {
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
               let url = try await provider.loadItem(forTypeIdentifier: UTType.data.identifier) as? URL, let data = try readFile(url) {
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
