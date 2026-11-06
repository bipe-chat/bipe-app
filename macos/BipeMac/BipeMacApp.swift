// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import CoreImage.CIFilterBuiltins
import MatrixRustSDK
import SwiftUI

@main
struct BipeMacApp: App {
    @StateObject private var account = MacAccount()

    var body: some Scene {
        WindowGroup {
            MacRootView(account: account)
                .frame(minWidth: 760, minHeight: 520)
                .fontDesign(.rounded)
                .tint(BipeColor.accentFill)
                .task {
                    #if DEBUG
                    // Hosted unit tests use synthetic clients; they must not start a real phone-link attempt.
                    if NSClassFromString("XCTestCase") != nil { return }
                    #endif
                    account.start()
                }
        }
        .defaultSize(width: 1040, height: 720)
    }
}

struct MacRootView: View {
    @ObservedObject var account: MacAccount
    @State private var selected: String?

    var body: some View {
        Group {
            if account.phase == .signedIn {
                NavigationSplitView {
                    List(selection: $selected) {
                        ForEach(account.rooms.map { MacRoom(room: $0) }) { entry in
                            Text(entry.room.displayName() ?? entry.id).tag(entry.id)
                        }
                    }
                    .navigationTitle(BipeStrings.appChatListTitle)
                    .safeAreaInset(edge: .bottom) {
                        Text(account.userId).font(.caption).textSelection(.enabled).padding()
                    }
                } detail: {
                    if let room = account.rooms.first(where: { $0.id() == selected }) {
                        MacChatView(room: room).id(room.id())
                    } else {
                        Text(BipeStrings.appChatListEmpty).foregroundStyle(BipeColor.inkSecondary)
                    }
                }
            } else {
                MacLinkView(account: account)
            }
        }
        .safeAreaInset(edge: .top) {
            if account.storageFailed {
                Text(BipeStrings.errorGeneric).padding().frame(maxWidth: .infinity)
                    .foregroundStyle(BipeColor.trustDangerInk).background(BipeColor.trustDangerWash)
            }
        }
    }
}

private struct MacRoom: Identifiable {
    let room: Room
    var id: String { room.id() }
}

struct MacLinkView: View {
    @ObservedObject var account: MacAccount

    var body: some View {
        VStack(spacing: 24) {
            Text(title).font(.title2).fontWeight(.semibold).multilineTextAlignment(.center)
            if account.phase == .qr, let bytes = account.qrBytes, let image = Self.qrImage(bytes) {
                HStack(spacing: 40) {
                    VStack(alignment: .leading, spacing: 20) {
                        Text(BipeStrings.webSigninStepOpen)
                        Text(BipeStrings.webSigninStepPath(BipeStrings.tabYou, BipeStrings.settingsAccount,
                                                          BipeStrings.linkedDevicesTitle, BipeStrings.linkedDevicesLinkComputer))
                        Text(BipeStrings.webSigninStepScan)
                    }.frame(maxWidth: 320)
                    Image(nsImage: image).interpolation(.none).resizable().scaledToFit()
                        .frame(width: 280, height: 280)
                        .accessibilityLabel(BipeStrings.webSigninStepScan)
                }
            } else if account.phase == .checkCode {
                TextField(BipeStrings.webSigninCheckCodeTitle, text: $account.checkCode)
                    .font(.largeTitle.monospacedDigit()).multilineTextAlignment(.center).frame(width: 150)
                    .onChange(of: account.checkCode) { _, value in
                        account.checkCode = String(value.filter { $0.isASCII && $0.isNumber }.prefix(2))
                    }
                    .onSubmit { account.submitCheckCode() }
                Button(BipeStrings.webSigninCheckCodeSend) { account.submitCheckCode() }
                    .keyboardShortcut(.defaultAction).controlSize(.large)
            } else if account.phase == .failed || account.phase == .revoked {
                Button(BipeStrings.appRetry) { account.retry() }.controlSize(.large)
            } else {
                ProgressView().accessibilityLabel(title)
            }
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(BipeColor.surfaceBase)
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

    /// A white quiet zone is part of the QR bitmap itself, independent of the app's light/dark palette.
    private static func qrImage(_ bytes: Data) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = bytes
        filter.correctionLevel = "L"
        guard let output = filter.outputImage else { return nil }
        let quiet = output.extent.insetBy(dx: -4, dy: -4)
        let white = CIImage(color: CIColor.white).cropped(to: quiet)
        let image = output.composited(over: white).transformed(by: CGAffineTransform(scaleX: 6, y: 6))
        guard let bitmap = CIContext().createCGImage(image, from: image.extent) else { return nil }
        return NSImage(cgImage: bitmap, size: NSSize(width: bitmap.width, height: bitmap.height))
    }
}

struct MacChatView: View {
    let room: Room
    @StateObject private var model = MacTimeline()

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { scroll in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 16) {
                        Button(BipeStrings.appTimelineLoadingHistory) { Task { await model.loadHistory() } }
                            .disabled(model.loadingHistory)
                        ForEach(model.messages) { message in
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Text(message.sender).font(.headline)
                                    Spacer()
                                    Text(message.timestamp, style: .time).font(.caption)
                                }
                                Text(message.body).textSelection(.enabled)
                                if let warning = message.warning {
                                    Text(warning.message).font(.callout).padding(8)
                                        .foregroundStyle(BipeColor.trustCautionInk).background(BipeColor.trustCautionWash)
                                }
                                if message.pending { Text(BipeStrings.appTimelineSending).font(.caption) }
                                if message.failed { Text(BipeStrings.appTimelineFailed).foregroundStyle(BipeColor.trustDangerInk) }
                            }
                            .padding(12)
                            .background(message.isOwn ? BipeColor.bubbleOwn : BipeColor.surfaceRaised,
                                        in: RoundedRectangle(cornerRadius: 12))
                            .id(message.id)
                        }
                    }.padding()
                }
                .onChange(of: model.messages.last?.id) { _, id in
                    if let id { scroll.scrollTo(id, anchor: .bottom) }
                }
            }
            if model.failed { Text(BipeStrings.errorGeneric).foregroundStyle(BipeColor.trustDangerInk).padding() }
            Divider()
            HStack(alignment: .bottom) {
                TextField(BipeStrings.appTimelineComposerPlaceholder, text: $model.draft, axis: .vertical)
                    .lineLimit(1...6).onSubmit { Task { await model.send() } }
                Button(BipeStrings.appTimelineSend) { Task { await model.send() } }
                    .keyboardShortcut(.return, modifiers: .command)
                    .disabled(model.sending || model.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }.padding()
        }
        .navigationTitle(room.displayName() ?? room.id())
        .background(BipeColor.surfaceBase)
        .task { await model.run(room: room) }
    }
}
