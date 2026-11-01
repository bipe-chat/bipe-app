// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

#if DEBUG
import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI

// Store captures (bipe-chat/bipe#457): one scene per slug of the bipe-shared appstore.md screenshot table, built
// from the app's own views and the sample data in bipe-shared product/store-captures.json. Debug builds only.
// The UI test BipeStoreCapturesTests launches the app with BIPE_STORE_CAPTURE, the path of the JSON file that
// tools/store-captures-ios.mjs writes for one slug, takes the screenshot and writes the element tree. The scenes
// have no account and no network: an offline client that never syncs, and the cast photos from files.

/// The input of one capture, as tools/store-captures-ios.mjs writes it.
struct StoreCapture: Decodable {
    struct Person: Decodable {
        let id: String
        let name: String
        let short: String
        let handle: String?
        let isCompany: Bool
        /// The mxc URL of the photo in `files`, or nil.
        let avatar: String?

        var userId: String { "@\(handle ?? id):\(BipeProfileLink.host)" }
    }

    struct Message: Decodable {
        /// nil: the viewer sent it.
        let from: Person?
        let time: String
        let text: String?
        /// The mxc URL in `files` of the photo that the message shows.
        let image: String?
        let sticker: String?
        let width: Int?
        let height: Int?
    }

    struct Request: Decodable {
        let from: Person
        let trust: String
    }

    struct Group: Decodable {
        let group: Person
        let from: Person
        let time: String
        let text: String
        let unread: Int
        let mentions: Int
        let muted: Bool
    }

    let slug: String
    let viewer: Person
    /// mxc URL to a file path on the Mac that runs the simulator.
    let files: [String: String]
    let chat: Person?
    let messages: [Message]?
    let requests: [Request]?
    let pixCode: String?
    let owner: Person?
    let company: Person?
    let legalName: String?
    let cnpj: String?
    let checkedMonth: String?
    let groups: [Group]?

    /// The capture the UI test asked for, or nil for a normal start.
    static let current: StoreCapture? = {
        guard let path = ProcessInfo.processInfo.environment["BIPE_STORE_CAPTURE"],
              let data = FileManager.default.contents(atPath: path) else { return nil }
        do {
            return try JSONDecoder().decode(StoreCapture.self, from: data)
        } catch {
            fatalError("BIPE_STORE_CAPTURE: \(error)")
        }
    }()

    /// A sample time of today, from "HH:MM".
    static func today(_ time: String) -> Date {
        let parts = time.split(separator: ":").compactMap { Int($0) }
        return Calendar.current.date(bySettingHour: parts.first ?? 0, minute: parts.last ?? 0, second: 0, of: .now) ?? .now
    }
}

/// The root of a capture run: builds the offline session, then shows the scene of the slug.
struct StoreCaptureRoot: View {
    let capture: StoreCapture
    @State private var session: AccountSession?
    @State private var failure: String?

    var body: some View {
        Group {
            if let session {
                StoreCaptureScene(capture: capture, session: session, media: StoreCaptureRoot.media(capture))
            } else if let failure {
                Text(verbatim: failure)
            }
        }
        .statusBarHidden()
        .task {
            do { session = try await Self.offlineSession(userId: capture.viewer.userId) } catch { failure = "\(error)" }
        }
    }

    private static func media(_ capture: StoreCapture) -> MediaLoader {
        MediaLoader(local: capture.files.compactMapValues { FileManager.default.contents(atPath: $0) })
    }

    /// A client with a made-up session on an unreachable server. It never syncs: the timeline and composer views
    /// need a session object, not a server.
    private static func offlineSession(userId: String) async throws -> AccountSession {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("store-capture-\(UUID().uuidString)")
        let data = root.appendingPathComponent("data")
        let cache = root.appendingPathComponent("cache")
        try FileManager.default.createDirectory(at: data, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        let homeserver = "https://store-capture.invalid"
        let client = try await ClientBuilder()
            .sessionPaths(dataPath: data.path, cachePath: cache.path)
            .slidingSyncVersionBuilder(versionBuilder: .native)
            .homeserverUrl(url: homeserver)
            .build()
        try await client.restoreSession(session: Session(accessToken: "store-capture", refreshToken: nil, userId: userId,
                                                         deviceId: "STORECAPTURE", homeserverUrl: homeserver,
                                                         oauthData: nil, slidingSyncVersion: .native))
        let sync = try await client.syncService().withOfflineMode().finish()
        return AccountSession(client: client, syncService: sync, userId: userId)
    }
}

/// The screen of one slug. The test waits for the element `store-capture-ready`.
private struct StoreCaptureScene: View {
    let capture: StoreCapture
    let session: AccountSession
    let media: MediaLoader

    var body: some View {
        scene
            .overlay(alignment: .topLeading) {
                Color.clear.frame(width: 1, height: 1)
                    .accessibilityElement()
                    .accessibilityIdentifier("store-capture-ready")
            }
    }

    @ViewBuilder
    private var scene: some View {
        switch capture.slug {
        case "aviso-golpe":
            StoreChatScene(capture: capture, chat: capture.chat!, isDm: true, session: session, media: media)
        case "pedidos":
            StoreTabs(selected: .conversas) {
                StorePushed(rootTitle: BipeStrings.appChatListTitle) {
                    ChatRequestsList(requests: requests, trust: \.trust, hidePhotos: true, media: media,
                                     onOpenWhoCanMessage: {}) { _ in EmptyView() }
                }
            }
        case "conferir-pix":
            NavigationStack {
                PixCheckResultView(results: [PixCheckedItem(sourceIndex: 0, input: capture.pixCode!, check: PixCheck.check(capture.pixCode!))],
                                   accountAvailable: true, sendToChat: { _ in })
                    .navigationTitle(BipeStrings.pixCheckTitle)
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button(BipeStrings.pixCheckClose) {} }
                    }
                    .background(BipeColor.surfaceBase.ignoresSafeArea())
            }
        case "seu-arroba":
            StoreTabs(selected: .voce) {
                StorePushed(rootTitle: BipeStrings.tabYou) {
                    List {
                        if let handle = capture.owner?.handle, let link = BipeProfileLink.url(handle: handle) {
                            ProfileLinkContent(link: link, showingQr: .constant(true), notice: .constant(nil))
                        }
                    }
                    .navigationTitle(BipeStrings.appProfileLink)
                    .navigationBarTitleDisplayMode(.inline)
                }
            }
        case "selo-cnpj":
            StoreChatScene(capture: capture, chat: capture.company!, isDm: true, session: session, media: media)
                .sheet(isPresented: .constant(true)) {
                    CompanySealSheet(userId: capture.company!.userId, state: seal)
                }
        case "grupos":
            StoreTabs(selected: .grupos) {
                NavigationStack {
                    List(groups) { chat in
                        ChatRow(chat: chat, media: media)
                    }
                    .listStyle(.plain)
                    .background(BipeColor.surfaceBase.ignoresSafeArea())
                    .navigationTitle(BipeStrings.tabGroups)
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) { CreateGroupButton(createGroup: {}, invite: {}) }
                    }
                }
            }
        case "stickers":
            StoreChatScene(capture: capture, chat: capture.chat!, isDm: false, session: session, media: media)
        default:
            Text(verbatim: "no scene for \(capture.slug)")
        }
    }

    private var requests: [ChatRequestSummary] {
        (capture.requests ?? []).enumerated().map { index, request in
            let trust: ChatRequestSummary.Trust = switch request.trust {
            case "verified": .verified(name: request.from.name)
            case "external": .external
            default: .unverified
            }
            return ChatRequestSummary(roomId: "!store-\(index):\(BipeProfileLink.host)", inviterId: request.from.userId,
                                      name: request.from.name, inviterName: request.from.name, avatarUrl: request.from.avatar,
                                      trust: trust, invitedAt: nil, isDm: true)
        }
    }

    private var groups: [ChatSummary] {
        (capture.groups ?? []).enumerated().map { index, group in
            ChatSummary(roomId: "!store-group-\(index):\(BipeProfileLink.host)", name: group.group.name, avatarUrl: group.group.avatar,
                        lastMessage: "\(group.from.short): \(group.text)", lastActivity: StoreCapture.today(group.time),
                        unreadCount: group.unread, isDirect: false, memberCount: 12, mentionCount: group.mentions,
                        isMuted: group.muted)
        }
    }

    /// A verified seal from the sample data. The decoder is the one of signed badges, so the fields are checked.
    private var seal: BipeCompanySealState {
        guard let company = capture.company, let cnpj = capture.cnpj, let legalName = capture.legalName,
              let checkedMonth = capture.checkedMonth else { return .none }
        let now = Int64(Date.now.timeIntervalSince1970)
        let json: [String: Any] = [
            "sub": company.userId, "lvl": "cnpj", "name": company.name, "cnpj": cnpj, "msk": "store-capture",
            "legal_name": legalName, "trade_name": company.name, "method": "ecnpj", "checked_month": checkedMonth,
            "iat": now, "exp": now + 365 * 24 * 3600,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: json),
              let payload = try? JSONDecoder().decode(BadgePayload.self, from: data) else { return .none }
        return .verified(payload)
    }
}

/// A chat of the store captures: the app's timeline rows and composer, with the chat title and call buttons.
private struct StoreChatScene: View {
    let capture: StoreCapture
    let chat: StoreCapture.Person
    let isDm: Bool
    let session: AccountSession
    let media: MediaLoader
    @StateObject private var model: ChatTimelineModel
    @StateObject private var voicePlayer = VoicePlayer()

    init(capture: StoreCapture, chat: StoreCapture.Person, isDm: Bool, session: AccountSession, media: MediaLoader) {
        self.capture = capture
        self.chat = chat
        self.isDm = isDm
        self.session = session
        self.media = media
        _model = StateObject(wrappedValue: ChatTimelineModel(session: session, roomId: "!store-chat:\(BipeProfileLink.host)"))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(rows) { row in
                            TimelineRowView(row: row, isDm: isDm, isHighlighted: false, model: model, media: media,
                                            voicePlayer: voicePlayer)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                }
                .defaultScrollAnchor(.bottom)
                ComposerBar(model: model, media: media, openAttachments: {}, openStickers: {})
            }
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    HStack(spacing: 8) {
                        AvatarView(name: chat.name, mxcUrl: chat.avatar, loader: media, size: 32, isCompany: chat.isCompany)
                        Text(chat.name).font(.headline).foregroundStyle(BipeColor.inkPrimary).lineLimit(1)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(isDm ? .isButton : [])
                }
                ToolbarItem(placement: .primaryAction) {
                    CallHeaderButtons(model: model, session: session)
                }
                ToolbarItem(placement: .primaryAction) {
                    Label {
                        Text(isDm ? BipeStrings.dmDetailsTitle : BipeStrings.appGroupDetailsTitle)
                    } icon: {
                        PhosphorIcon.info.toolbarImage(size: 22)
                    }
                    .labelStyle(.iconOnly)
                }
            }
        }
    }

    private var rows: [TimelineRow] {
        (capture.messages ?? []).enumerated().map { index, sample in
            let id = "store-\(index)"
            let sender = sample.from ?? capture.viewer
            var message = TimelineMessage(id: id, kind: .text, body: sample.text, senderId: sender.userId,
                                          senderName: isDm ? sender.name : sender.short, isOwn: sample.from == nil,
                                          sentAt: StoreCapture.today(sample.time), sendState: .sent, cause: nil)
            if let text = sample.text {
                message.richText = BipeMessageText(body: text, html: nil)
            }
            if let url = sample.image ?? sample.sticker, let source = try? MediaSource.fromUrl(url: url) {
                message.kind = .media
                message.body = nil
                let sticker = sample.sticker != nil
                message.media = MediaMessage(kind: sticker ? .sticker : .image, filename: "\(id).\(sticker ? "png" : "jpg")",
                                             mimeType: sticker ? "image/png" : "image/jpeg", width: sample.width, height: sample.height,
                                             source: MediaRef(source: source))
            }
            let warning = message.isOwn ? nil : sample.text.flatMap { ScamDetector.warningKind(for: $0 + "\n", eventID: id) }
            let details = MessageDetails(eventId: "$\(id)", reply: nil, reactions: [], isEdited: false,
                                         receipt: message.isOwn ? MessageReceipt(tick: .read, readers: [], readersUpToHere: [],
                                                                                 showsLabel: false) : nil,
                                         scamWarning: warning, canReply: true, canEdit: false, canDelete: false)
            return TimelineRow(id: id, kind: .message, message: message, details: details)
        }
    }
}

/// The app's four tabs (AppTabsView), with the capture in the selected one.
private struct StoreTabs<Content: View>: View {
    let selected: AppTabsModel.Tab
    @ViewBuilder let content: () -> Content

    var body: some View {
        TabView(selection: .constant(selected)) {
            Tab(value: AppTabsModel.Tab.conversas) { tab(.conversas) } label: {
                Label(BipeStrings.tabChats, systemImage: "bubble.left.and.bubble.right")
            }
            Tab(value: AppTabsModel.Tab.grupos) { tab(.grupos) } label: {
                Label(BipeStrings.tabGroups, systemImage: "person.3")
            }
            Tab(value: AppTabsModel.Tab.voce) { tab(.voce) } label: {
                Label(BipeStrings.tabYou, systemImage: "person.crop.circle")
            }
            Tab(value: AppTabsModel.Tab.search, role: .search) { tab(.search) } label: {
                Label(BipeStrings.appTabSearch, systemImage: "magnifyingglass")
            }
        }
    }

    @ViewBuilder
    private func tab(_ value: AppTabsModel.Tab) -> some View {
        if value == selected { content() } else { Color.clear }
    }
}

/// A screen pushed one level into a tab's navigation, so it shows the back button as in the app.
private struct StorePushed<Content: View>: View {
    let rootTitle: String
    @ViewBuilder let content: () -> Content
    @State private var path = [0]

    var body: some View {
        NavigationStack(path: $path) {
            Color.clear
                .navigationTitle(rootTitle)
                .navigationDestination(for: Int.self) { _ in content() }
        }
    }
}
#endif
