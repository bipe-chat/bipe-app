// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
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
                .foregroundStyle(BipeColor.inkPrimary)
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
                            HStack(spacing: 12) {
                                MacAvatar(name: entry.room.displayName() ?? entry.id)
                                Text(entry.room.displayName() ?? entry.id).font(.body).lineLimit(2)
                            }.padding(.vertical, 6).tag(entry.id)
                        }
                    }
                    .navigationTitle(BipeStrings.appChatListTitle)
                    .navigationSplitViewColumnWidth(min: 240, ideal: 280, max: 360)
                    .safeAreaInset(edge: .bottom) {
                        HStack(spacing: 10) {
                            MacAvatar(name: account.userId)
                            Text(account.userId).font(.caption).textSelection(.enabled).lineLimit(2)
                            Spacer(minLength: 0)
                        }.padding().background(BipeColor.surfaceRaised)
                    }
                } detail: {
                    if let room = account.rooms.first(where: { $0.id() == selected }) {
                        MacChatView(room: room).id(room.id())
                    } else {
                        VStack(spacing: 16) {
                            MacBrand()
                            Text(BipeStrings.appChatListEmpty).font(.body).foregroundStyle(BipeColor.inkSecondary)
                        }.frame(maxWidth: .infinity, maxHeight: .infinity).background(BipeColor.surfaceBase)
                    }
                }
            } else {
                MacLinkView(account: account)
            }
        }
        .safeAreaInset(edge: .top) {
            if account.storageFailed {
                MacNotice(text: BipeStrings.errorGeneric, danger: true).padding()
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
        VStack(spacing: MacDesign.sectionGap) {
            MacBrand()
            Text(title).font(.title2).fontWeight(.semibold).multilineTextAlignment(.center)
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
                    .font(.largeTitle.monospacedDigit()).multilineTextAlignment(.center).frame(width: 150)
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
            Text(number.formatted()).font(.subheadline).fontWeight(.semibold)
                .foregroundStyle(BipeColor.inkOnAccent).frame(width: 28, height: 28)
                .background(BipeColor.accentFill, in: Circle()).accessibilityHidden(true)
            Text(text).font(.body).fixedSize(horizontal: false, vertical: true).padding(.top, 3)
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
    @StateObject private var model = MacTimeline()

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { scroll in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: MacDesign.gap) {
                        Button(BipeStrings.appTimelineLoadingHistory) { Task { await model.loadHistory() } }
                            .disabled(model.loadingHistory)
                        ForEach(model.messages) { message in MacMessageView(message: message).id(message.id) }
                    }.padding()
                }
                .onChange(of: model.messages.last?.id) { _, id in
                    if let id { scroll.scrollTo(id, anchor: .bottom) }
                }
            }
            if model.failed { MacNotice(text: BipeStrings.errorGeneric, danger: true).padding() }
            Divider()
            HStack(alignment: .bottom) {
                TextField(BipeStrings.appTimelineComposerPlaceholder, text: $model.draft, axis: .vertical)
                    .lineLimit(1...6).onSubmit { Task { await model.send() } }
                    .textFieldStyle(.plain).padding(12)
                    .background(BipeColor.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(BipeColor.lineSubtle))
                Button(BipeStrings.appTimelineSend) { Task { await model.send() } }
                    .buttonStyle(.bipeProminent)
                    .keyboardShortcut(.return, modifiers: .command)
                    .disabled(model.sending || model.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }.padding()
        }
        .navigationTitle(room.displayName() ?? room.id())
        .background(BipeColor.surfaceBase)
        .task { await model.run(room: room) }
    }
}
