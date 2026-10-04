import SwiftUI
import AVFoundation

/// Sheet wrapper: camera scanner + manual entry. Calls `onCode` once and dismisses.
struct ScannerSheet: View {
    var title = "Scan barcode"
    let onCode: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var typed = ""
    @State private var cameraDenied = false
    @State private var delivered = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ZStack {
                    if cameraDenied {
                        VStack(spacing: 12) {
                            Image(systemName: "camera.fill").font(.largeTitle).foregroundStyle(.secondary)
                            Text("Allow camera access in Settings to scan, or type the code below.").multilineTextAlignment(.center).padding(.horizontal)
                            Button("Open Settings") { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }
                        }
                    } else {
                        BarcodeCameraView(onCode: deliver, onDenied: { cameraDenied = true }).ignoresSafeArea(edges: .horizontal)
                        Reticle().stroke(Color.white, lineWidth: 4).frame(width: 280, height: 160)
                    }
                }
                .frame(maxHeight: .infinity)
                .background(Color.black)
                HStack {
                    TextField("UPC, SKU, order # or tracking #", text: $typed).textFieldStyle(.roundedBorder).autocorrectionDisabled().textInputAutocapitalization(.never).submitLabel(.go).onSubmit { if !typed.isEmpty { deliver(typed) } }
                    Button("Go") { deliver(typed) }.buttonStyle(.borderedProminent).disabled(typed.isEmpty)
                }
                .padding()
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }

    private func deliver(_ code: String) {
        guard !delivered else { return }
        delivered = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        dismiss()
        onCode(code.replacingOccurrences(of: "\u{1D}", with: "").trimmingCharacters(in: .whitespacesAndNewlines))
    }
}

/// Corner-bracket reticle.
struct Reticle: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path(); let l: CGFloat = 28
        p.move(to: CGPoint(x: r.minX, y: r.minY + l)); p.addLine(to: CGPoint(x: r.minX, y: r.minY)); p.addLine(to: CGPoint(x: r.minX + l, y: r.minY))
        p.move(to: CGPoint(x: r.maxX - l, y: r.minY)); p.addLine(to: CGPoint(x: r.maxX, y: r.minY)); p.addLine(to: CGPoint(x: r.maxX, y: r.minY + l))
        p.move(to: CGPoint(x: r.maxX, y: r.maxY - l)); p.addLine(to: CGPoint(x: r.maxX, y: r.maxY)); p.addLine(to: CGPoint(x: r.maxX - l, y: r.maxY))
        p.move(to: CGPoint(x: r.minX + l, y: r.maxY)); p.addLine(to: CGPoint(x: r.minX, y: r.maxY)); p.addLine(to: CGPoint(x: r.minX, y: r.maxY - l))
        return p
    }
}

/// AVFoundation metadata scanner (works on every device, no Vision requirement).
struct BarcodeCameraView: UIViewControllerRepresentable {
    let onCode: (String) -> Void
    let onDenied: () -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let vc = ScannerController()
        vc.onCode = onCode
        vc.onDenied = onDenied
        return vc
    }
    func updateUIViewController(_ uiViewController: ScannerController, context: Context) {}
}

final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    var onDenied: (() -> Void)?
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private let queue = DispatchQueue(label: "easyesuite.scanner")
    private var fired = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: configure()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] ok in
                DispatchQueue.main.async { if ok { self?.configure() } else { self?.onDenied?() } }
            }
        default: onDenied?()
        }
    }

    private func configure() {
        guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device) else { onDenied?(); return }
        session.beginConfiguration()
        if session.canAddInput(input) { session.addInput(input) }
        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: queue)
            let wanted: [AVMetadataObject.ObjectType] = [.ean8, .ean13, .upce, .code128, .code39, .code93, .itf14, .interleaved2of5, .qr, .dataMatrix, .pdf417]
            output.metadataObjectTypes = wanted.filter { output.availableMetadataObjectTypes.contains($0) }
        }
        session.commitConfiguration()
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        preview = layer
        queue.async { [session] in session.startRunning() }
    }

    override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); preview?.frame = view.bounds }
    override func viewWillDisappear(_ animated: Bool) { super.viewWillDisappear(animated); queue.async { [session] in if session.isRunning { session.stopRunning() } } }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !fired, let obj = metadataObjects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first, let value = obj.stringValue, !value.isEmpty else { return }
        fired = true
        var code = value
        // iOS reports UPC-A as EAN-13 with a leading zero; keep the 12-digit form the ERP stores.
        if obj.type == .ean13, code.count == 13, code.hasPrefix("0") { code = String(code.dropFirst()) }
        DispatchQueue.main.async { [weak self] in self?.onCode?(code) }
    }
}
