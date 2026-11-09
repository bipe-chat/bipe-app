// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import MatrixRustSDK
import SwiftUI
import UniformTypeIdentifiers

@main
struct BipeMacApp: App {
    @NSApplicationDelegateAdaptor(MacAppDelegate.self) private var appDelegate
    @StateObject private var workspace = MacWorkspace()
    @AppStorage(BipeSettingsRow.chatsAppearance.rawValue) private var appearance = "system"

    init() {
        // Before any SDK call (bipe-chat/bipe#1301): SDK warnings, errors and panics, to the console in Debug builds
        // only. A Release build writes no SDK log.
        #if DEBUG
        let sdkConsole = true
        #else
        let sdkConsole = false
        #endif
        try? initPlatform(config: TracingConfiguration(logLevel: .warn, traceLogPacks: [], extraTargets: [],
                                                       writeToStdoutOrSystem: sdkConsole, writeToFiles: nil, sentryConfig: nil),
                          useLightweightTokioRuntime: false)
        MacLocalCache.clean()
        AppTheme.load()
    }

    var body: some Scene {
        WindowGroup(id: MacAppDelegate.mainWindowID) {
            MacRootView(workspace: workspace)
                .frame(minWidth: 820, minHeight: 600)
                .preferredColorScheme(appearance == "light" ? .light : appearance == "dark" ? .dark : nil)
                .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
                    SyncedSettings.refresh()
                }
                .macFont(.body)
                .tint(BipeColor.accentFill)
                .foregroundStyle(BipeColor.inkPrimary)
                .task {
                    #if DEBUG
                    // Hosted unit tests use synthetic clients; they must not start a real phone-link attempt.
                    if NSClassFromString("XCTestCase") != nil { return }
                    #endif
                    workspace.start()
                }
        }
        .defaultSize(width: 1040, height: 740)
        .windowToolbarStyle(.unified(showsTitle: false))
    }
}

struct MacRootView: View {
    @ObservedObject var workspace: MacWorkspace
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Group {
            if workspace.failed {
                VStack(spacing: MacDesign.gap) {
                    MacNotice(text: BipeStrings.errorGeneric, danger: true)
                    Button(BipeStrings.appRetry) { workspace.restore() }.buttonStyle(.bipeProminent)
                }.padding(MacDesign.pageInset)
            } else if let account = workspace.current {
                MacAccountView(workspace: workspace, account: account).id(ObjectIdentifier(account))
            } else if let linking = workspace.linking {
                MacLinkView(account: linking)
            } else {
                BipeLaunchView(label: BipeStrings.appStartLoading)
                    .toolbarBackground(.hidden, for: .windowToolbar)
            }
        }
        .sheet(isPresented: Binding(get: { workspace.current != nil && workspace.linking != nil }, set: { if !$0 { workspace.cancelLink() } })) {
            if let linking = workspace.linking {
                VStack(spacing: 0) {
                    HStack {
                        Spacer()
                        Button(BipeStrings.appCancel) { workspace.cancelLink() }
                            .disabled(linking.phase == .keys || linking.phase == .approving)
                            .keyboardShortcut(.cancelAction)
                    }.padding()
                    MacLinkView(account: linking)
                }.frame(width: 820, height: 580).interactiveDismissDisabled()
            }
        }
        .onAppear { workspace.reopenWindow = { openWindow(id: MacAppDelegate.mainWindowID) } }
    }
}

struct MacLinkView: View {
    @ObservedObject var account: MacAccount

    var body: some View {
        // The cover takes the leading side of a wide window and the top of a narrow one.
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 0) {
                MacLinkCover().frame(width: 360)
                panel.frame(minWidth: 620)
            }
            VStack(spacing: 0) {
                MacLinkCover().frame(height: 220)
                panel
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(BipeColor.surfaceBase)
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: MacDesign.sectionGap) {
            Text(title).macFont(.largeTitle).fontWeight(.bold).foregroundStyle(BipeColor.inkPrimary)
                .fixedSize(horizontal: false, vertical: true)
            if account.phase == .qr, let bytes = account.qrBytes, let image = Self.qrImage(bytes) {
                HStack(alignment: .center, spacing: 40) {
                    VStack(alignment: .leading, spacing: 20) {
                        instruction(1, BipeStrings.webSigninStepOpen)
                        instruction(2, BipeStrings.webSigninStepPath(BipeStrings.tabYou, BipeStrings.settingsAccount,
                                                          BipeStrings.linkedDevicesTitle, BipeStrings.linkedDevicesLinkComputer))
                        instruction(3, BipeStrings.webSigninStepScan)
                    }.frame(maxWidth: 300, alignment: .leading)
                    // A white field with a quiet zone in light and dark mode, as on the phone (LinkPhoneShowView).
                    Image(nsImage: image).interpolation(.none).resizable().scaledToFit()
                        .frame(width: 240, height: 240)
                        .padding(16)
                        .background(.white, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .padding(10)
                        .background(BipeColor.accentWash, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
                        .shadow(color: BipeColor.accentFill.opacity(0.18), radius: 20, y: 8)
                        .accessibilityLabel(BipeStrings.webSigninStepScan)
                }
            } else if account.phase == .checkCode {
                TextField(BipeStrings.webSigninCheckCodeTitle, text: $account.checkCode)
                    .textFieldStyle(.plain)
                    .macFont(.largeTitle).monospacedDigit().multilineTextAlignment(.center)
                    .frame(width: 150, height: 72)
                    .background(BipeColor.surfaceRaised, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(BipeColor.accentFill, lineWidth: 2))
                    .onChange(of: account.checkCode) { _, value in
                        account.checkCode = String(value.filter { $0.isASCII && $0.isNumber }.prefix(2))
                    }
                    .onSubmit { account.submitCheckCode() }
                Button(BipeStrings.webSigninCheckCodeSend) { account.submitCheckCode() }
                    .keyboardShortcut(.defaultAction).buttonStyle(MacFilledButtonStyle())
            } else if account.phase == .failed || account.phase == .revoked {
                Button(BipeStrings.appRetry) { account.retry() }.buttonStyle(MacFilledButtonStyle())
            } else {
                ProgressView().controlSize(.large).tint(BipeColor.accentFill).accessibilityLabel(title)
            }
        }
        .padding(48)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background {
            LinearGradient(colors: [BipeColor.accentWash, BipeColor.surfaceBase], startPoint: .top, endPoint: .center)
        }
    }

    private func instruction(_ number: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(number.formatted()).macFont(.headline).fontWeight(.bold)
                .foregroundStyle(BipeColor.inkOnAccent).frame(width: 32, height: 32)
                .background(LinearGradient(colors: [BipeColor.accentFill, BipeColor.accentFillPressed],
                                           startPoint: .topLeading, endPoint: .bottomTrailing),
                            in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .accessibilityHidden(true)
            Text(text).macFont(.body).foregroundStyle(BipeColor.inkPrimary).fixedSize(horizontal: false, vertical: true).padding(.top, 5)
        }
    }

    private var title: String {
        switch account.phase {
        case .qr: BipeStrings.webSigninTitle
        case .checkCode: BipeStrings.webSigninCheckCodeTitle
        case .approving: BipeStrings.webSigninApprove
        case .keys: BipeStrings.webSigninKeys
        case .failed: BipeStrings.errorGeneric
        case .revoked: BipeStrings.softLogoutTitle
        default: BipeStrings.webLoading
        }
    }

    static func qrImage(_ bytes: Data) -> NSImage? { MacQRCode.image(bytes) }

}

struct MacChatView: View {
    let room: Room
    let name: String
    let face: MacAvatarDescriptor?
    @ObservedObject var media: MacMedia
    @StateObject private var model: MacTimeline
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var composerFocused: Bool
    private enum Picker { case media, file }
    @State private var picker: Picker?
    @State private var reportReason = ""
    @State private var reportFailed = false
    let forwardTargets: [MacRoomSummary]

    init(room: Room, name: String, drafts: MacDrafts, media: MacMedia, face: MacAvatarDescriptor? = nil, forwardTargets: [MacRoomSummary] = [], opensPhotoPicker: Bool = false) {
        self.forwardTargets = forwardTargets
        _picker = State(initialValue: opensPhotoPicker ? .media : nil)
        self.room = room
        self.name = name
        self.face = face
        self.media = media
        _model = StateObject(wrappedValue: MacTimeline(drafts: drafts))
    }

    private var composer: some View {
        VStack(spacing: 0) {
            if model.failed { MacNotice(text: BipeStrings.errorGeneric, danger: true).padding() }
            if let error = model.uploadError, model.preparedAttachment == nil {
                MacNotice(text: error, danger: true).padding()
            }
            if model.editingID != nil {
                BipeComposerPanel {
                    BipeComposerContextBar(glyph: .edit, title: BipeStrings.appMessageEditing,
                                           preview: model.editingPreview, onClose: model.cancelEdit)
                }
            } else if model.replyID != nil {
                BipeComposerPanel {
                    BipeComposerContextBar(glyph: .reply,
                        title: model.replyTarget.map { BipeStrings.appMessageReplyingTo($0.sender) } ?? BipeStrings.appMessageActionReply,
                        preview: model.replyTarget?.composerPreview ?? BipeStrings.appMessageReplyUnavailable,
                        onClose: model.cancelReply)
                }
            }
            MacComposer(isEditing: model.editingID != nil,
                        canSend: model.ready && !model.sending && model.canSubmitDraft,
                        canAttach: model.ready && !model.preparing && !model.sending,
                        preparing: model.preparing,
                        choose: { choice in
                            picker = choice == .file ? .file : .media
                        }, send: { Task { await model.send() } }) {
                TextField(BipeStrings.appTimelineComposerPlaceholder, text: $model.draft, axis: .vertical)
                    .focused($composerFocused)
                    .lineLimit(1...5)
                    .onKeyPress(.return, phases: .down) { press in
                        guard !press.modifiers.contains(.shift) else { return .ignored }
                        if model.ready && !model.sending && model.canSubmitDraft { Task { await model.send() } }
                        return .handled
                    }
                    .textFieldStyle(.plain).padding(.vertical, 11)
                    .foregroundStyle(BipeColor.inkPrimary)
            }
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            if !model.isEncrypted { MacNotice(text: BipeStrings.appTimelineNotEncrypted).padding() }
            ScrollViewReader { scroll in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: MacDesign.gap) {
                        if !model.reachedStart {
                            Button(model.loadingHistory ? BipeStrings.appTimelineLoadingHistory : BipeStrings.actionShowMore) { Task { await model.loadHistory() } }
                                .disabled(model.loadingHistory)
                        }
                        ForEach(model.rows) { row in
                            switch row {
                            case .message(let message):
                                MacMessageView(message: message, room: room, media: media, isPrivateRoom: model.isPrivateRoom,
                                               fromRequest: media.isFromRequest(room.id()),
                                               pauseSecondsLeft: { model.pauseSecondsLeft(message.id, now: $0) },
                                               menuItems: model.menuItems(for: message),
                                               choose: { item in
                                                   model.choose(item, message)
                                                   if item == .reply || item == .edit { composerFocused = true }
                                               },
                                               react: { key in Task { await model.react(rowID: message.id, key: key) } },
                                               reactionPending: { model.reactionPending(rowID: message.id, key: $0) },
                                               isHighlighted: model.highlightedRowID == message.id,
                                               openOriginal: { eventID in Task { await model.openOriginal(eventID: eventID) } },
                                               loadReply: { await model.loadReply(rowID: message.id) })
                                    .id(message.id).onAppear { model.startMoneyPause(message) }
                                    .onChange(of: message.warning) { _, _ in model.startMoneyPause(message) }
                            case .day(let id, let date):
                                SystemLine(text: daySeparatorText(date)).id(id)
                                    .accessibilityAddTraits(.isHeader)
                            case .line(let id, let text, let kind):
                                SystemLine(text: text, kind: kind).id(id)
                            case .readMarker(let id):
                                TimelineReadMarker().id(id)
                            }
                        }
                    }.padding()
                }
                .onScrollGeometryChange(for: Bool.self) { geometry in
                    geometry.contentOffset.y + geometry.containerSize.height >= geometry.contentSize.height - 80
                } action: { _, atBottom in
                    model.readingLatest = atBottom
                    if atBottom { Task { await model.markRead() } }
                }
                .onChange(of: model.rows.last?.id) { _, id in
                    // Reading older messages stays where it is. An own message always shows.
                    if let id, model.readingLatest || model.lastRowIsOwnMessage { scroll.scrollTo(id, anchor: .bottom) }
                }
                .onChange(of: model.scrollTarget) { _, target in
                    if let target {
                        scroll.scrollTo(target.rowID, anchor: .center)
                        model.scrolledToTarget(target)
                    }
                }
            }

        }
        .safeAreaInset(edge: .bottom, spacing: 0) { composer }
        .navigationTitle(name)
        .toolbar {
            ToolbarItem(placement: .navigation) {
                HStack(spacing: 8) {
                    if let face { MacAvatar(name: name, media: media, url: face.url, userID: face.userID, members: face.members, size: 28) }
                    Text(name).macFont(.headline).lineLimit(1)
                }
            }
        }
        .alert(BipeStrings.appReactionFailed, isPresented: $model.reactionFailed) {
            Button(BipeStrings.appClose, role: .cancel) {}
        }
        .alert(BipeStrings.appMessageEditFailed, isPresented: $model.editFailed) {
            Button(BipeStrings.appClose, role: .cancel) {}
        }
        .alert(BipeStrings.appMessageDeleteFailed, isPresented: $model.deleteFailed) {
            Button(BipeStrings.appClose, role: .cancel) {}
        }
        .background(ChatBackgroundView())
        .alert(BipeStrings.appMessageDeleteTitle, isPresented: Binding(get: { model.deleting != nil }, set: { if !$0 { model.deleting = nil } }), presenting: model.deleting) { message in
            Button(BipeStrings.appMessageActionDelete, role: .destructive) { Task { await model.confirmDelete(message) } }
            Button(BipeStrings.appCancel, role: .cancel) { model.deleting = nil }
        } message: { _ in Text(BipeStrings.appMessageDeleteBody) }
        .sheet(item: $model.reporting) { message in
            Form {
                TextField(BipeStrings.reportMessageReason, text: $reportReason, axis: .vertical).lineLimit(3...6)
                Text(BipeStrings.reportMessageBody).macFont(.callout).foregroundStyle(BipeColor.inkSecondary)
                if reportFailed { MacNotice(text: BipeStrings.appReportError, danger: true) }
                HStack {
                    Spacer()
                    Button(BipeStrings.appCancel) { model.reporting = nil; reportReason = ""; reportFailed = false }.keyboardShortcut(.cancelAction)
                    Button(BipeStrings.appReportSend) {
                        Task {
                            if await model.sendReport(message, reason: reportReason) {
                                model.reporting = nil; reportReason = ""; reportFailed = false
                            } else { reportFailed = true }
                        }
                    }.keyboardShortcut(.defaultAction)
                }
            }.formStyle(.grouped).frame(width: 460).navigationTitle(BipeStrings.appMessageActionReport)
        }
        .sheet(item: $model.forwarding) { message in
            MacForwardSheet(message: message, targets: forwardTargets) { model.forwarding = nil }
        }
        .task { await model.run(room: room) }
        .task(id: room.id()) { await media.checkRequestRooms(for: room.id()) }
        .dropDestination(for: URL.self) { urls, _ in
            guard let url = urls.first, url.isFileURL, model.ready, !model.preparing, !model.sending else { return false }
            Task { await model.preparePickedURL(url, maxUploadSize: await media.maxUploadSize()) }
            return true
        }
        .onPasteCommand(of: [.fileURL, .movie, .video, .image]) { providers in
            if model.ready, let provider = providers.first {
                Task { model.pasteMedia(provider, maxUploadSize: await media.maxUploadSize()) }
            }
        }
        .fileImporter(isPresented: Binding(get: { picker != nil }, set: { if !$0 { picker = nil } }),
                      allowedContentTypes: picker == .file ? [.data] : [.image, .movie, .video]) { result in
            if case .success(let url) = result {
                Task {
                    await model.preparePickedURL(url, maxUploadSize: await media.maxUploadSize())
                }
            }
        }
        .sheet(isPresented: Binding(get: { model.preparedAttachment != nil }, set: { if !$0 { model.cancelAttachment() } })) {
            if let image = model.attachment {
                MacImageComposer(image: image, model: model, maxUploadSize: { await media.maxUploadSize() })
                    .interactiveDismissDisabled(model.sending)
            } else if let video = model.videoAttachment {
                MacVideoComposer(video: video, model: model, maxUploadSize: { await media.maxUploadSize() })
                    .interactiveDismissDisabled(model.sending)
            } else if let file = model.fileAttachment {
                MacFileComposer(file: file, model: model, maxUploadSize: { await media.maxUploadSize() })
                    .interactiveDismissDisabled(model.sending)
            }
        }
        .onDisappear { if !model.sending { model.cancelAttachment() } }
        .onChange(of: scenePhase) { _, phase in if phase == .active { Task { await model.markRead() } } }
    }
}
