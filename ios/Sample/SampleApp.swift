import DeviceLinkKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// SDK integration sample: every copy made inside this app is sent to the linked devices.
/// iOS posts UIPasteboard.changedNotification only for changes made while the app is in front,
/// which is exactly the "copied here" signal this integration needs, without paste prompts.
@main
struct DeviceLinkSampleApp: App {
    @StateObject private var model = LinkModel()
    @StateObject private var autoShare = AutoShare()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            SampleView(model: model)
                .onAppear { autoShare.attach(model) }
                .onOpenURL { model.open($0.absoluteString) }
        }
        .onChange(of: phase) { _, phase in model.setActive(phase == .active) }
    }
}

@MainActor
final class AutoShare: ObservableObject {
    private var observer: NSObjectProtocol?

    func attach(_ model: LinkModel) {
        guard observer == nil else { return }
        observer = NotificationCenter.default.addObserver(forName: UIPasteboard.changedNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated {
                let pasteboard = UIPasteboard.general
                // Skip what DeviceLink itself just wrote (received clips), so nothing echoes back.
                guard pasteboard.changeCount != Pasteboard.ownChangeCount, !model.peers.isEmpty else { return }
                if let image = pasteboard.image, let data = image.pngData() {
                    model.sendData(data, name: "copied.png", mime: "image/png")
                } else if let text = pasteboard.string, !text.isEmpty {
                    model.sendText(text)
                }
            }
        }
    }
}

struct SampleView: View {
    @ObservedObject var model: LinkModel
    @ObservedObject private var clips = ClipStore.shared
    @State private var showingCode = false
    @State private var scanning = false
    @State private var scratch = ""

    private let samples: [(String, String)] = [
        ("Short text", "Hello from the DeviceLink iOS sample 👋"),
        ("One-time code", "482913"),
        ("Link", "https://example.org/devicelink/sample?ref=clipboard"),
        ("Address", "Sample Street 12\n10115 Example City\nNowhere"),
        ("Unicode", "Ünïcødé ✓ — 日本語 · العربية · 🚀📋"),
        ("Long text (~20 KB)", (1...400).map { "Line \($0): synthetic long clipboard text for DeviceLink." }.joined(separator: "\n")),
    ]

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if model.peers.isEmpty {
                        Text("No device linked yet").font(.headline)
                    } else {
                        Text("Linked with \(model.peers.map(\.name).joined(separator: ", "))").font(.headline)
                    }
                    HStack {
                        Button("Show code") { showingCode = true }.buttonStyle(.borderedProminent)
                        Button("Scan code") { scanning = true }.buttonStyle(.bordered)
                    }
                } footer: { Text("Every copy made in this app is sent automatically: the other device can paste it right away.") }

                Section("Copy something") {
                    TextField("Type here, select, Copy", text: $scratch, axis: .vertical).lineLimit(3...6)
                }

                Section("Sample data") {
                    ForEach(samples, id: \.0) { label, value in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(label).font(.subheadline.bold())
                                Text(value).lineLimit(2).foregroundStyle(.secondary).textSelection(.enabled)
                            }
                            Spacer()
                            Button("Copy") { UIPasteboard.general.string = value }.buttonStyle(.bordered)
                        }
                    }
                    HStack {
                        Image(uiImage: SampleImage.make()).resizable().frame(width: 56, height: 56).clipShape(RoundedRectangle(cornerRadius: 8))
                        Text("Sample image").font(.subheadline.bold())
                        Spacer()
                        Button("Copy image") { UIPasteboard.general.image = SampleImage.make() }.buttonStyle(.bordered)
                    }
                    HStack {
                        VStack(alignment: .leading) {
                            Text("Sample file").font(.subheadline.bold())
                            Text("Files do not go to the clipboard; the receiver offers Share…").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Send") {
                            model.sendData(Data("DeviceLink sample file (synthetic data)\n".utf8), name: "sample-notes.txt", mime: "text/plain")
                        }.buttonStyle(.bordered)
                    }
                }

                Section("Recent deliveries") {
                    let sent = clips.records.filter { $0.direction == .sent }.prefix(6)
                    if sent.isEmpty { Text("Nothing sent yet").foregroundStyle(.secondary) }
                    ForEach(Array(sent)) { record in
                        HStack {
                            Text((record.text ?? record.fileName ?? "Image").replacingOccurrences(of: "\n", with: " ")).lineLimit(1)
                            Spacer()
                            Text(record.state == .copied ? "✓ copied" : record.state == .pending ? "sending…" : record.state == .failed ? "failed" : "✓ delivered")
                                .font(.caption).foregroundStyle(record.state == .failed ? .orange : .secondary)
                        }
                    }
                }

                Section("Test scenarios") {
                    Text("""
                    1. Copy the short text: paste it on the other device.
                    2. Copy unicode and the long text: content arrives intact.
                    3. Copy the image: paste it in Notes or a chat there.
                    4. Send the sample file: use Share… there.
                    5. Put the other device offline, copy, reconnect within 5 minutes: it still arrives.
                    6. Stay offline longer than 5 minutes: the item expires, nothing arrives.
                    7. Copy three samples quickly: only the newest replaces the clipboard there.
                    8. On the other device, Share › Send to device: it lands on this clipboard.
                    """).font(.footnote).textSelection(.enabled)
                }
            }
            .navigationTitle("DeviceLink Sample")
            .sheet(isPresented: $showingCode) { PairingCodeSheet(model: model) }
            .sheet(isPresented: $scanning) { ScannerSheet { model.open($0) } }
            .alert(model.message ?? "", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
                Button("OK", role: .cancel) {}
            }
        }
    }
}

/// Synthetic image drawn at runtime; no bundled assets.
enum SampleImage {
    static func make() -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 240, height: 240)).image { context in
            let colors = [UIColor.systemGreen.cgColor, UIColor.systemBlue.cgColor] as CFArray
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: nil) {
                context.cgContext.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 240, y: 240), options: [])
            }
            UIColor.white.setStroke()
            let ring = UIBezierPath(ovalIn: CGRect(x: 50, y: 50, width: 140, height: 140))
            ring.lineWidth = 16
            ring.stroke()
        }
    }
}
