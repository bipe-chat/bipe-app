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
    @StateObject private var workspace = MacWorkspace()
    @AppStorage(BipeSettingsRow.chatsAppearance.rawValue) private var appearance = "system"

    init() {
        MacLocalCache.clean()
        AppTheme.load()
    }

    var body: some Scene {
        WindowGroup {
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
    }
}

struct MacLinkView: View {
    @ObservedObject var account: MacAccount

    var body: some View {
        VStack(spacing: MacDesign.sectionGap) {
            MacBrand()
            Text(title).macFont(.title2).fontWeight(.semibold).multilineTextAlignment(.center)
            if account.phase == .qr, let bytes = account.qrBytes, let image = Self.qrImage(bytes) {
                HStack(alignment: .center, spacing: 40) {
                    VStack(alignment: .leading, spacing: 20) {
                        instruction(1, BipeStrings.webSigninStepOpen)
                        instruction(2, BipeStrings.webSigninStepPath(BipeStrings.tabYou, BipeStrings.settingsAccount,
                                                          BipeStrings.linkedDevicesTitle, BipeStrings.linkedDevicesLinkComputer))
                        instruction(3, BipeStrings.webSigninStepScan)
                    }.frame(maxWidth: 320)
                    MacCard {
                        Image(nsImage: image).interpolation(.none).resizable().scaledToFit()
                            .frame(width: 260, height: 260)
                            .accessibilityLabel(BipeStrings.webSigninStepScan)
                    }
                }
            } else if account.phase == .checkCode {
                TextField(BipeStrings.webSigninCheckCodeTitle, text: $account.checkCode)
                    .macFont(.largeTitle).monospacedDigit().multilineTextAlignment(.center).frame(width: 150)
                    .onChange(of: account.checkCode) { _, value in
                        account.checkCode = String(value.filter { $0.isASCII && $0.isNumber }.prefix(2))
                    }
                    .onSubmit { account.submitCheckCode() }
                Button(BipeStrings.webSigninCheckCodeSend) { account.submitCheckCode() }
                    .keyboardShortcut(.defaultAction).buttonStyle(.bipeProminent)
            } else if account.phase == .failed || account.phase == .revoked {
                Button(BipeStrings.appRetry) { account.retry() }.buttonStyle(.bipeProminent)
            } else {
                ProgressView().accessibilityLabel(title)
            }
        }
        .padding(MacDesign.pageInset)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(LinearGradient(colors: [BipeColor.accentWash, BipeColor.surfaceBase], startPoint: .topLeading, endPoint: .bottomTrailing))
    }

    private func instruction(_ number: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(number.formatted()).macFont(.subheadline).fontWeight(.semibold)
                .foregroundStyle(BipeColor.inkOnAccent).frame(width: 28, height: 28)
                .background(BipeColor.accentFill, in: Circle()).accessibilityHidden(true)
            Text(text).macFont(.body).fixedSize(horizontal: false, vertical: true).padding(.top, 3)
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
    @State private var pickingImage: Bool
    @State private var reportReason = ""
    @State private var reportFailed = false
    let forwardTargets: [MacRoomSummary]

    init(room: Room, name: String, drafts: MacDrafts, media: MacMedia, face: MacAvatarDescriptor? = nil, forwardTargets: [MacRoomSummary] = [], opensPhotoPicker: Bool = false) {
        self.forwardTargets = forwardTargets
        _pickingImage = State(initialValue: opensPhotoPicker)
        self.room = room
        self.name = name
        self.face = face
        self.media = media
        _model = StateObject(wrappedValue: MacTimeline(drafts: drafts))
    }

    var body: some View {
        VStack(spacing: 0) {
            if !model.isEncrypted { MacNotice(text: BipeStrings.appTimelineNotEncrypted).padding() }
            ScrollViewReader { scroll in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: MacDesign.gap) {
                        Button(model.loadingHistory ? BipeStrings.appTimelineLoadingHistory : BipeStrings.actionShowMore) { Task { await model.loadHistory() } }
                            .disabled(model.loadingHistory)
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
                                               reactionPending: { model.reactionPending(rowID: message.id, key: $0) })
                                    .id(message.id).onAppear { model.startMoneyPause(message) }
                            case .day(let id, let date):
                                SystemLine(text: daySeparatorText(date)).id(id)
                                    .accessibilityAddTraits(.isHeader)
                            case .line(let id, let text, let kind):
                                SystemLine(text: text, kind: kind).id(id)
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
            }
            if model.failed { MacNotice(text: BipeStrings.errorGeneric, danger: true).padding() }
            if let error = model.uploadError, model.attachment == nil { MacNotice(text: error, danger: true).padding() }
            Divider()
            if model.editingID != nil {
                HStack {
                    Text(BipeStrings.appMessageActionEdit).macFont(.callout).fontWeight(.semibold)
                    Spacer()
                    Button(BipeStrings.appCancel) { model.cancelEdit() }
                }.padding().background(BipeColor.accentWash)
            }
            if model.replyID != nil {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(model.replyTarget.map { BipeStrings.appMessageReplyingTo($0.sender) } ?? BipeStrings.appMessageActionReply)
                            .macFont(.callout).fontWeight(.semibold)
                        Text(model.replyTarget?.body ?? BipeStrings.appMessageReplyUnavailable)
                            .macFont(.caption).lineLimit(2).foregroundStyle(BipeColor.inkSecondary)
                    }
                    Spacer()
                    Button(BipeStrings.appCancel) { model.cancelReply() }
                }.padding().background(BipeColor.accentWash)
            }
            HStack(alignment: .bottom) {
                Button { pickingImage = true } label: {
                    Image(decorative: "image").resizable().scaledToFit().frame(width: 24, height: 24)
                        .bipeTapTarget()
                }.buttonStyle(.plain).accessibilityLabel(BipeStrings.appAttachPhotos)
                    .help(BipeStrings.appAttachPhotos).disabled(!model.ready || model.preparing || model.sending)
                if model.preparing { ProgressView().accessibilityLabel(BipeStrings.appUploadPreparing) }
                TextField(BipeStrings.appTimelineComposerPlaceholder, text: $model.draft, axis: .vertical)
                    .focused($composerFocused)
                    .lineLimit(1...6).onSubmit { Task { await model.send() } }
                    .textFieldStyle(.plain).padding(.horizontal, 16).padding(.vertical, 11)
                    .background(BipeColor.surfaceRaised, in: RoundedRectangle(cornerRadius: 22))
                    .overlay(RoundedRectangle(cornerRadius: 22).strokeBorder(BipeColor.lineSubtle))
                Button(BipeStrings.appTimelineSend) { Task { await model.send() } }
                    .buttonStyle(.bipeProminent)
                    .keyboardShortcut(.return, modifiers: .command)
                    .disabled(!model.ready || model.sending || model.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }.padding()
        }
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
            Task { await model.prepareImage(url) }
            return true
        }
        .onPasteCommand(of: [.image]) { providers in
            if model.ready, let provider = providers.first { model.pasteImage(provider) }
        }
        .fileImporter(isPresented: $pickingImage, allowedContentTypes: [.image]) { result in
            if case .success(let url) = result { Task { await model.prepareImage(url) } }
        }
        .sheet(isPresented: Binding(get: { model.attachment != nil }, set: { if !$0 { model.cancelAttachment() } })) {
            if let image = model.attachment {
                MacImageComposer(image: image, model: model, maxUploadSize: { await media.maxUploadSize() })
                    .interactiveDismissDisabled(model.sending)
            }
        }
        .onChange(of: scenePhase) { _, phase in if phase == .active { Task { await model.markRead() } } }
    }
}
