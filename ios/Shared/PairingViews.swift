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
