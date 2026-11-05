// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AVFoundation
import BipeKit
import CoreTransferable
import Phosphor
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// The choices of spec attachment-picker.
enum AttachmentChoice {
    case photos, camera, file, location, sticker, poll
}

/// Spec attachment-picker: the menu behind the composer's + button. The order of rows is photos and videos,
/// camera, file, location, sticker, poll (spec attachment-picker notes).
struct AttachmentMenu<Label: View>: View {
    /// canPoll of spec attachment-picker: false hides the poll row.
    let canPoll: Bool
    /// Spec attachment-picker notes, Notes: photos and videos, camera and file only.
    var isNotes = false
    let choose: (AttachmentChoice) -> Void
    @ViewBuilder let label: () -> Label

    var body: some View {
        Menu {
            Button { choose(.photos) } label: { IconLabel(icon: .image, text: BipeStrings.appAttachPhotos) }
            Button { choose(.camera) } label: { IconLabel(icon: .camera, text: BipeStrings.appAttachCamera) }
            Button { choose(.file) } label: { IconLabel(icon: .file, text: BipeStrings.appAttachFile) }
            if !isNotes {
                // Phosphor has no pin in tools/phosphor.json.
                Button { choose(.location) } label: { SwiftUI.Label(BipeStrings.appAttachLocation, systemImage: "mappin.and.ellipse") }
                Button { choose(.sticker) } label: { IconLabel(icon: .sticker, text: BipeStrings.actionStickers) }
            }
            if canPoll, !isNotes {
                Button { choose(.poll) } label: { IconLabel(icon: .chartBar, text: BipeStrings.appAttachPoll) }
            }
        } label: {
            label()
        }
        .menuOrder(.fixed)
        .accessibilityIdentifier(BipeScreen.attachmentPicker.rawValue)
    }
}

/// Spec attachment-picker: runs the choice made in AttachmentMenu. Each choice hands over to the system
/// picker; the picked items open upload-preview through onPicked.
struct AttachmentPicker: ViewModifier {
    @Binding var choice: AttachmentChoice?
    /// The picked files, copied into the app cache.
    let onPicked: ([URL]) -> Void
    /// Action sticker: opens sticker-picker.
    let onStickers: () -> Void
    /// Action location: opens location-send.
    let onLocation: () -> Void
    /// Action poll: opens poll-create.
    let onPoll: () -> Void

    /// permissionProblem of spec attachment-picker.
    enum PermissionProblem {
        case none
        case photos
        case camera
    }

    @State private var showsPhotos = false
    @State private var showsCamera = false
    @State private var showsFiles = false
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var permissionProblem = PermissionProblem.none

    func body(content: Content) -> some View {
        content
            .onChange(of: choice) { _, choice in
                guard let choice else { return }
                self.choice = nil
                run(choice)
            }
            .photosPicker(isPresented: $showsPhotos,
                          selection: $photoItems,
                          maxSelectionCount: UploadPreviewModel.maxItems,
                          matching: .any(of: [.images, .videos]),
                          preferredItemEncoding: .current)
            .onChange(of: photoItems) { _, items in
                guard !items.isEmpty else { return }
                photoItems = []
                Task { await load(items) }
            }
            .fullScreenCover(isPresented: $showsCamera) {
                CameraPicker { url in
                    showsCamera = false
                    if let url { onPicked([url]) }
                }
                .ignoresSafeArea()
            }
            .fileImporter(isPresented: $showsFiles, allowedContentTypes: [.item], allowsMultipleSelection: false) { result in
                guard case .success(let urls) = result, let url = urls.first, let copy = PickedFile.copy(url) else { return }
                onPicked([copy])
            }
            .alert(permissionText, isPresented: Binding(get: { permissionProblem != .none }, set: { if !$0 { permissionProblem = .none } })) {
                Button(BipeStrings.contactsOpenSettings) {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                Button(BipeStrings.appCancel, role: .cancel) {}
            }
    }

    private var permissionText: String {
        permissionProblem == .camera ? BipeStrings.cameraPermissionBody : BipeStrings.appAttachPhotosPermission
    }

    private func run(_ choice: AttachmentChoice) {
        switch choice {
        case .photos: showsPhotos = true
        case .camera: openCamera()
        case .file: showsFiles = true
        case .location: onLocation()
        case .sticker: onStickers()
        case .poll: onPoll()
        }
    }

    /// Action camera: the system camera for a photo or a video.
    private func openCamera() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .denied, .restricted:
            permissionProblem = .camera
        case .notDetermined:
            Task {
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                if granted { showsCamera = true } else { permissionProblem = .camera }
            }
        default:
            showsCamera = true
        }
    }

    private func load(_ items: [PhotosPickerItem]) async {
        var urls: [URL] = []
        for item in items {
            if let file = try? await item.loadTransferable(type: PickedFile.self) {
                urls.append(file.url)
            }
        }
        if !urls.isEmpty { onPicked(urls) }
    }
}

/// A picked photo, video or file, copied into the app cache (the system deletes its own copy).
struct PickedFile: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { received in
            PickedFile(url: try copyOrThrow(received.file))
        }
        FileRepresentation(importedContentType: .image) { received in
            PickedFile(url: try copyOrThrow(received.file))
        }
    }

    static func copy(_ url: URL) -> URL? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return try? copyOrThrow(url)
    }

    private static func copyOrThrow(_ url: URL) throws -> URL {
        let target = try MediaPreparation.workFolder().appendingPathComponent(url.lastPathComponent)
        try FileManager.default.copyItem(at: url, to: target)
        return target
    }
}

/// The system camera for a photo or a video.
private struct CameraPicker: UIViewControllerRepresentable {
    let done: (URL?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = [UTType.image.identifier, UTType.movie.identifier]
        picker.videoQuality = .typeHigh
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(done: done)
    }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let done: (URL?) -> Void

        init(done: @escaping (URL?) -> Void) {
            self.done = done
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let movie = info[.mediaURL] as? URL {
                done(PickedFile.copy(movie))
            } else if let image = info[.originalImage] as? UIImage, let data = image.jpegData(compressionQuality: 0.9),
                      let folder = try? MediaPreparation.workFolder() {
                let url = folder.appendingPathComponent("IMG-\(Int(Date().timeIntervalSince1970))").appendingPathExtension("jpg")
                done((try? data.write(to: url)) != nil ? url : nil)
            } else {
                done(nil)
            }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            done(nil)
        }
    }
}

extension View {
    func attachmentPicker(choice: Binding<AttachmentChoice?>, onPicked: @escaping ([URL]) -> Void, onStickers: @escaping () -> Void,
                          onLocation: @escaping () -> Void, onPoll: @escaping () -> Void) -> some View {
        modifier(AttachmentPicker(choice: choice, onPicked: onPicked, onStickers: onStickers,
                                  onLocation: onLocation, onPoll: onPoll))
    }
}
