import DeviceLinkKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// The thin receiver: link devices, see clips (newest first), re-copy, share, and send back.
struct ContentView: View {
    @ObservedObject var model: LinkModel
    @ObservedObject private var clips = ClipStore.shared
    @State private var showingCode = false
    @State private var scanning = false
    @State private var photo: PhotosPickerItem?
    @State private var importing = false
    @State private var shareItem: ShareItem?

    var body: some View {
        NavigationStack {
            List {
                statusSection
                if model.peers.isEmpty { linkSection } else { devicesSection; sendSection }
                clipsSection
                Section {
                    EmptyView()
                } footer: {
                    Text("Tip: in Shortcuts, add “Get DeviceLink clip” to Back Tap (Settings › Accessibility › Touch) to paste without opening the app. Share › Send to device works from any app.")
                }
            }
            .navigationTitle("DeviceLink")
            .toolbar {
                if !model.peers.isEmpty {
                    ToolbarItem(placement: .primaryAction) {
                        Menu {
                            Button { showingCode = true } label: { Label("Show my code", systemImage: "qrcode") }
                            Button { scanning = true } label: { Label("Scan a code", systemImage: "qrcode.viewfinder") }
                        } label: { Image(systemName: "plus") }.accessibilityLabel("Link another device")
                    }
                }
            }
            .sheet(isPresented: $showingCode) { PairingCodeSheet(model: model) }
            .sheet(isPresented: $scanning) { ScannerSheet { model.open($0) } }
            .sheet(item: $shareItem) { ActivityView(items: $0.items) }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.item]) { result in
                guard let url = try? result.get(), url.startAccessingSecurityScopedResource() else { return }
                defer { url.stopAccessingSecurityScopedResource() }
                if let data = try? Data(contentsOf: url) {
                    model.sendData(data, name: url.lastPathComponent, mime: UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream")
                }
            }
            .onChange(of: photo) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        let type = item.supportedContentTypes.first ?? .jpeg
                        model.sendData(data, name: "photo.\(type.preferredFilenameExtension ?? "jpg")", mime: type.preferredMIMEType ?? "image/jpeg")
                    }
                    photo = nil
                }
            }
            .alert(model.message ?? "", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
                Button("OK", role: .cancel) {}
            }
            .overlay { if model.busy { ProgressView("Linking…").padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12)) } }
        }
    }

    private var statusSection: some View {
        Section {
            HStack(spacing: 14) {
                Image(systemName: statusIcon).font(.title2).foregroundStyle(statusColor).frame(width: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text(statusTitle).font(.headline)
                    Text(statusDetail).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)
        }
    }

    private var statusIcon: String {
        if model.peers.isEmpty { return "link.badge.plus" }
        switch model.status { case .online: return "checkmark.circle.fill"; case .offline, .rejected: return "exclamationmark.triangle.fill"; default: return "arrow.triangle.2.circlepath" }
    }
    private var statusColor: Color { model.status == .online ? .green : model.peers.isEmpty ? .accentColor : .orange }
    private var statusTitle: String {
        if model.peers.isEmpty { return "Not linked yet" }
        switch model.status { case .online: return "Ready"; case .offline: return "Offline"; case .rejected: return "Refused by relay"; default: return "Connecting…" }
    }
    private var statusDetail: String {
        model.peers.isEmpty ? (model.relayConfigured ? "Show your code or scan the other device's code." : "Scan the code shown on your other device.")
            : "Clips from \(model.peers.map(\.name).joined(separator: ", ")) go straight to your clipboard while DeviceLink is open."
    }

    private var linkSection: some View {
        Section("Link your first device") {
            Button { showingCode = true } label: { Label("Show my code", systemImage: "qrcode") }.disabled(!model.relayConfigured)
            Button { scanning = true } label: { Label("Scan a code", systemImage: "qrcode.viewfinder") }
            PasteButton(payloadType: String.self) { strings in if let link = strings.first { model.open(link) } }
        }
    }

    private var devicesSection: some View {
        Section("Linked devices") {
            ForEach(model.peers) { peer in
                Label(peer.name, systemImage: peer.platform == "ios" ? "iphone" : peer.platform == "android" ? "candybarphone" : "laptopcomputer")
                    .swipeActions { Button("Unlink", role: .destructive) { model.unlink(peer) } }
            }
        }
    }

    private var sendSection: some View {
        Section("Send") {
            // PasteButton reads the clipboard without the "Allow Paste" prompt.
            PasteButton(payloadType: String.self) { strings in if let text = strings.first { model.sendText(text) } }
            PhotosPicker(selection: $photo, matching: .images) { Label("Send a photo", systemImage: "photo") }
            Button { importing = true } label: { Label("Send a file", systemImage: "doc") }
        }
    }

    private var clipsSection: some View {
        Section {
            if clips.records.isEmpty {
                Text("Nothing yet. Copy something on the linked device: it lands here and on your clipboard.").foregroundStyle(.secondary)
            }
            ForEach(clips.records) { record in
                ClipRow(record: record)
                    .contentShape(Rectangle())
                    .onTapGesture { if !Pasteboard.write(record) { shareItem = ShareItem(record: record) } else { clips.update(record.id, .copied) } }
                    .contextMenu {
                        Button { Pasteboard.write(record) } label: { Label("Copy", systemImage: "doc.on.doc") }
                        Button { shareItem = ShareItem(record: record) } label: { Label("Share…", systemImage: "square.and.arrow.up") }
                        Button(role: .destructive) { clips.remove(record.id) } label: { Label("Remove", systemImage: "trash") }
                    }
            }
        } header: {
            HStack { Text("Clips"); Spacer(); if !clips.records.isEmpty { Button("Clear") { clips.clear() }.font(.caption) } }
        }
    }
}

struct ClipRow: View {
    let record: ClipRecord

    var body: some View {
        HStack(spacing: 12) {
            if record.isImage, let url = record.fileURL, let image = UIImage(contentsOfFile: url.path) {
                Image(uiImage: image).resizable().scaledToFill().frame(width: 48, height: 48).clipShape(RoundedRectangle(cornerRadius: 8))
            } else {
                Image(systemName: record.direction == .received ? "arrow.down.circle" : "arrow.up.circle").font(.title3).foregroundStyle(.secondary)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text("\(record.direction == .received ? "From" : "To") \(record.peerName) · \(record.at, style: .relative) ago")
                    .font(.caption).foregroundStyle(.secondary)
                Text(record.text ?? record.fileName ?? "Image").lineLimit(3)
                Text(stateText).font(.caption).foregroundStyle(record.state == .failed ? .orange : record.state == .copied || record.state == .delivered ? .green : .secondary)
            }
        }
        .padding(.vertical, 2)
    }

    private var stateText: String {
        switch (record.direction, record.state) {
        case (.received, .copied): return "Copied to clipboard"
        case (.received, _): return "Tap to copy or share"
        case (.sent, .pending): return "Sending…"
        case (.sent, .copied): return "Copied on \(record.peerName)"
        case (.sent, .delivered), (.sent, .shareNeeded): return "Delivered to \(record.peerName)"
        case (.sent, .failed): return "Not delivered"
        }
    }
}

struct ShareItem: Identifiable {
    let id = UUID()
    let items: [Any]
    init(record: ClipRecord) { items = record.fileURL.map { [$0] } ?? [record.text ?? ""] }
}

/// Platform share sheet for items the clipboard cannot hold (files) or that the user wants elsewhere.
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
