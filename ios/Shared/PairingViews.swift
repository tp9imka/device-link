import CoreImage.CIFilterBuiltins
import SwiftUI
import VisionKit

/// Renders a pairing link as a crisp QR code.
struct QRCodeView: View {
    let content: String

    var body: some View {
        if let image = Self.render(content) {
            Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
                .padding(12).background(Color.white).clipShape(RoundedRectangle(cornerRadius: 16))
                .accessibilityLabel("Pairing QR code")
        }
    }

    static func render(_ content: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(content.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 12, y: 12)),
              let image = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: image)
    }
}

/// "Show my code": one-time QR with countdown; dismisses itself once the other device has linked.
struct PairingCodeSheet: View {
    @ObservedObject var model: LinkModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                if let uri = model.pairingURI {
                    QRCodeView(content: uri).frame(maxWidth: 320)
                    Text("Scan with the camera or the DeviceLink app on the other device.")
                        .multilineTextAlignment(.center).foregroundStyle(.secondary)
                    if let expires = model.pairingExpiresAt {
                        Text(timerInterval: Date()...max(Date(), expires), countsDown: true)
                            .font(.footnote.monospacedDigit()).foregroundStyle(.secondary)
                    }
                    ShareLink(item: uri) { Label("Share link instead", systemImage: "square.and.arrow.up") }
                } else {
                    ProgressView("Creating a one-time code…")
                }
            }
            .padding(24)
            .navigationTitle("Link another device")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { model.cancelCode(); dismiss() } } }
            .onAppear { model.showCode() }
            .onChange(of: model.peers.count) { _, _ in if model.pairingURI == nil { dismiss() } }
            .onChange(of: model.message) { _, message in if message != nil && model.pairingURI == nil { dismiss() } }
        }
    }
}

/// Camera scanner for pairing codes (VisionKit). Falls back to pasting the link.
struct ScannerSheet: View {
    let onCode: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var pasted = ""

    var body: some View {
        NavigationStack {
            Group {
                if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                    QRScanner { code in dismiss(); onCode(code) }.ignoresSafeArea()
                } else {
                    Form {
                        Section(footer: Text("The camera scanner is not available here. Paste the link shared from the other device.")) {
                            TextField("https://…/pair#v2…", text: $pasted, axis: .vertical)
                            Button("Link") { dismiss(); onCode(pasted) }.disabled(pasted.isEmpty)
                        }
                    }
                }
            }
            .navigationTitle("Scan a code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
}

struct QRScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])], qualityLevel: .balanced,
                                                   isHighlightingEnabled: true)
        controller.delegate = context.coordinator
        try? controller.startScanning()
        return controller
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onCode: (String) -> Void
        private var done = false
        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            for item in addedItems {
                if case .barcode(let barcode) = item, let value = barcode.payloadStringValue, !done,
                   value.contains("/pair#") || value.hasPrefix("devicelink://") || value.contains("/setup#") {
                    done = true
                    dataScanner.stopScanning()
                    onCode(value)
                }
            }
        }
    }
}


/// One-time confirmation after linking: both screens show the same six digits. Never asked again.
struct LinkedConfirmation: ViewModifier {
    @ObservedObject var model: LinkModel

    func body(content: Content) -> some View {
        content.alert(model.linkedConfirmation.map { "Linked with \($0.name)" } ?? "",
                      isPresented: Binding(get: { model.linkedConfirmation != nil }, set: { if !$0 { model.linkedConfirmation = nil } }),
                      presenting: model.linkedConfirmation) { peer in
            Button("OK", role: .cancel) {}
            Button("Codes differ: unlink", role: .destructive) { model.unlink(peer) }
        } message: { peer in
            if let code = peer.pairingCode {
                Text("Confirmation code \(code.prefix(3)) \(code.suffix(3)) should match the other screen. This is only shown once: from now on clips just go through.")
            }
        }
    }
}

/// Relay, registration, last poll/receive/send and last error; test clip; reset device.
struct DiagnosticsSheet: View {
    @ObservedObject var model: LinkModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmReset = false

    var body: some View {
        NavigationStack {
            List {
                Section { Text(model.diagnosticsReport).font(.footnote.monospaced()).textSelection(.enabled) }
                Section {
                    Button("Copy report") { UIPasteboard.general.string = model.diagnosticsReport }
                    Button("Send test clip") { model.sendTestClip() }.disabled(model.peers.isEmpty)
                    Button("Reset device", role: .destructive) { confirmReset = true }
                } footer: { Text("The report contains no clipboard content or device names.") }
            }
            .navigationTitle("Diagnostics")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .task { await model.refresh() }
            .confirmationDialog("Reset this device?", isPresented: $confirmReset, titleVisibility: .visible) {
                Button("Reset device", role: .destructive) { model.resetDevice(); dismiss() }
            } message: {
                Text("Unlinks every device, deletes this device's keys and clip history, and creates a new identity.")
            }
        }
    }
}
