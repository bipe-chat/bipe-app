// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AVFoundation
import CoreImage
import SwiftUI

/// Camera preview that reads QR codes and hands over their raw bytes. The MSC4108 code is binary, so the bytes
/// come from the error-corrected payload, not from the text value.
struct QRScannerView: UIViewRepresentable {
    let onCode: (Data) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onCode: onCode)
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = context.coordinator.session
        view.previewLayer.videoGravity = .resizeAspectFill
        context.coordinator.start()
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        context.coordinator.onCode = onCode
    }

    static func dismantleUIView(_ uiView: PreviewView, coordinator: Coordinator) {
        coordinator.stop()
    }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        let session = AVCaptureSession()
        var onCode: (Data) -> Void
        private let queue = DispatchQueue(label: "chat.bipe.qr-scanner")
        private var lastCode: Data?

        init(onCode: @escaping (Data) -> Void) {
            self.onCode = onCode
            super.init()
            guard let camera = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: camera),
                  session.canAddInput(input) else { return }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else { return }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
        }

        func start() {
            let session = session
            queue.async { session.startRunning() }
        }

        func stop() {
            let session = session
            queue.async { session.stopRunning() }
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput,
                            didOutput metadataObjects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            for object in metadataObjects {
                guard let code = object as? AVMetadataMachineReadableCodeObject,
                      let descriptor = code.descriptor as? CIQRCodeDescriptor,
                      let bytes = QRPayload.bytes(of: descriptor) else { continue }
                // The same code stays in front of the camera: hand it over once.
                guard bytes != lastCode else { return }
                lastCode = bytes
                onCode(bytes)
                return
            }
        }
    }
}

/// Reads the data of a QR code from its error-corrected codewords (ISO/IEC 18004): byte-mode segments only.
enum QRPayload {
    static func bytes(of descriptor: CIQRCodeDescriptor) -> Data? {
        var reader = BitReader(data: descriptor.errorCorrectedPayload)
        let longCount = descriptor.symbolVersion >= 10
        var result = Data()
        while let mode = reader.read(4), mode != 0 {
            switch mode {
            case 0b0100: // byte mode
                guard let count = reader.read(longCount ? 16 : 8) else { return nil }
                for _ in 0..<count {
                    guard let byte = reader.read(8) else { return nil }
                    result.append(UInt8(byte))
                }
            case 0b0111: // ECI designator: one to three bytes, the content stays bytes
                guard let first = reader.read(8) else { return nil }
                if first & 0x80 != 0 {
                    _ = reader.read(first & 0x40 != 0 ? 16 : 8)
                }
            default:
                // Numeric, alphanumeric or kanji: not an MSC4108 code.
                return nil
            }
        }
        return result.isEmpty ? nil : result
    }

    private struct BitReader {
        let data: Data
        var position = 0

        mutating func read(_ count: Int) -> Int? {
            guard position + count <= data.count * 8 else { return nil }
            var value = 0
            for _ in 0..<count {
                let byte = data[data.startIndex + position / 8]
                let bit = (byte >> (7 - UInt8(position % 8))) & 1
                value = (value << 1) | Int(bit)
                position += 1
            }
            return value
        }
    }
}
