// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec business-tools (ADR 0194): what the Trabalho tab reads. A failed read of the tools shows every tool as a row,
/// so that nothing hides (point 5). Suggestions come from records only.
@MainActor
final class WorkHomeModel: ObservableObject {
    @Published private(set) var tools: [BipeBusinessTool]?
    @Published private(set) var sector: String?
    @Published private(set) var setup: BipeWorkSetup?
    @Published private(set) var suggestion: BipeWorkSuggestion?
    @Published private(set) var today: BipeBusinessToday?
    @Published var askSector = false
    /// The owner's web switches (ADR 0195).
    @Published private(set) var web: BipeWebSettings?
    @Published var notice: String?

    let api: BipeBusinessAPI
    private let accountId: String

    init(api: BipeBusinessAPI, accountId: String) {
        self.api = api
        self.accountId = accountId
    }

    private var askedKey: String { "bipe.work.sectorAsked.\(accountId)" }

    func load() async {
        if let read = try? await api.businessTools() {
            tools = read.tools
            sector = read.sector
        }
        setup = try? await api.workSetup()
        web = try? await api.webSettings()
        suggestion = (try? await api.workSuggestions())?.first
        let hasLive = tools?.contains { $0.state == .live } == true
        today = hasLive ? try? await api.businessToday() : nil
        // Spec work-sector: once, on a new work account. Pular and a pick both mark it.
        if let setup, sector == nil, setup.doneCount == 0, !UserDefaults.standard.bool(forKey: askedKey) {
            askSector = true
        }
    }

    func setSector(_ id: String?) async {
        UserDefaults.standard.set(true, forKey: askedKey)
        askSector = false
        if let read = try? await api.setWorkSector(id) {
            tools = read.tools
            sector = read.sector
        }
        setup = try? await api.workSetup()
    }

    func set(_ tool: BipeBusinessTool, to state: BipeBusinessToolState) async {
        do {
            tools = try await api.setBusinessTool(id: tool.id, state: state).tools
        } catch {
            notice = BipeStrings.workToolError
        }
        await load()
    }

    func setWeb(_ next: BipeWebSettings) async {
        do {
            web = try await api.setWebSettings(next)
        } catch {
            notice = BipeStrings.workToolError
        }
    }

    func dismissSuggestion() async {
        guard let suggestion else { return }
        self.suggestion = (try? await api.dismissWorkSuggestion(id: suggestion.id))?.first
    }

    func act(_ call: @escaping () async throws -> Void) async {
        do {
            try await call()
        } catch {
            notice = BipeStrings.errorGeneric
        }
        await load()
    }
}

/// Text of the registry IDs (registries/business-tools.json).
enum WorkText {
    static func tool(_ id: String) -> String {
        switch id {
        case "agenda": BipeStrings.tabAgenda
        case "catalog": BipeStrings.catalogTitle
        case "orders": BipeStrings.ordersTitle
        case "quotes": BipeStrings.quotesTitle
        default: BipeStrings.queueTitle
        }
    }

    static func icon(_ id: String) -> PhosphorIcon {
        switch id {
        case "agenda": .clock
        case "catalog": .image
        case "orders": .notepad
        case "quotes": .file
        default: .usersThree
        }
    }

    static func sector(_ id: String) -> String {
        switch id {
        case "beauty": BipeStrings.sectorBeauty
        case "food": BipeStrings.sectorFood
        case "repair": BipeStrings.sectorRepair
        case "health": BipeStrings.sectorHealth
        case "resale": BipeStrings.sectorResale
        case "home_services": BipeStrings.sectorHomeServices
        default: BipeStrings.sectorOther
        }
    }

    static func suggestion(_ id: String) -> String {
        switch id {
        case "quotes_for_on_request": BipeStrings.workSuggestQuotesForOnRequest
        case "agenda_for_services": BipeStrings.workSuggestAgendaForServices
        default: BipeStrings.workSuggestOrdersForProducts
        }
    }

    static func step(_ id: String, firstTool: String) -> (title: String, body: String) {
        switch id {
        case "profile": (BipeStrings.workStepProfile, BipeStrings.workStepProfileBody)
        case "link": (BipeStrings.workStepLink, BipeStrings.workStepLinkBody)
        case "catalog": (BipeStrings.workStepCatalog, BipeStrings.workStepCatalogBody(String(BipeRegistries.businessCatalogMinItems)))
        case "first_tool": (BipeStrings.workStepFirstTool(tool(firstTool)), BipeStrings.workStepFirstToolBody)
        case "seal": (BipeStrings.workStepSeal, BipeStrings.workStepSealBody)
        default: (BipeStrings.workStepSecondSeat, BipeStrings.workStepSecondSeatBody)
        }
    }
}

/// The sections of the tab: Hoje, Próximo passo, one suggestion, the tools, Todas as ferramentas, the account rows.
struct WorkHomeSections: View {
    @ObservedObject var model: WorkHomeModel
    let openTool: (String) -> Void
    let openSetup: () -> Void
    let openBrandMarks: () -> Void
    let openKit: () -> Void
    let openSector: () -> Void
    @State private var blocked: BipeBusinessTool?

    private var rows: [BipeBusinessTool] { model.tools?.filter { $0.state != .off } ?? [] }
    private var offTools: [BipeBusinessTool] { model.tools?.filter { $0.state == .off } ?? [] }

    var body: some View {
        if let today = model.today { todaySection(today) }
        if let setup = model.setup, let next = setup.steps.first(where: { !$0.done }) {
            Section {
                Button(action: openSetup) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(WorkText.step(next.id, firstTool: setup.firstTool).title).foregroundStyle(BipeColor.inkPrimary)
                        Text(WorkText.step(next.id, firstTool: setup.firstTool).body).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                    }
                }
            } header: {
                Text(BipeStrings.workNextStep)
            } footer: {
                Text(BipeStrings.workProgress(String(setup.doneCount), String(setup.steps.count)))
            }
        }
        if let suggestion = model.suggestion {
            Section {
                Text(WorkText.suggestion(suggestion.id)).foregroundStyle(BipeColor.inkPrimary)
                Button(BipeStrings.workToolTurnOn) { turnOn(id: suggestion.tool) }
                Button(BipeStrings.workSuggestDismiss) { Task { await model.dismissSuggestion() } }
            }
        }
        if !rows.isEmpty {
            Section {
                ForEach(rows) { tool in
                    Button { openTool(tool.id) } label: {
                        WorkToolLabel(id: tool.id, subtitle: tool.state == .live ? BipeStrings.workToolStateLive : BipeStrings.workToolStateSetup)
                    }
                }
            } header: {
                Text(BipeStrings.businessToolsTitle)
            } footer: {
                Text(BipeStrings.businessToolsFooter)
            }
        }
        // A failed read lists every tool: nothing hides (ADR 0194 point 5).
        if model.tools == nil {
            Section {
                ForEach(BipeRegistries.businessToolIds, id: \.self) { id in
                    Button { openTool(id) } label: { WorkToolLabel(id: id, subtitle: nil) }
                }
            }
        } else if !offTools.isEmpty {
            Section(BipeStrings.workAllTools) {
                ForEach(offTools) { tool in
                    Button { turnOn(tool) } label: { WorkToolLabel(id: tool.id, subtitle: BipeStrings.workToolTurnOn) }
                }
            }
        }
        Section {
            Button(action: openSector) {
                LabeledContent(BipeStrings.workSectorRow, value: model.sector.map(WorkText.sector) ?? BipeStrings.workSectorNone)
                    .foregroundStyle(BipeColor.inkPrimary)
            }
            Button(action: openBrandMarks) { Text(BipeStrings.brandMarksTitle).foregroundStyle(BipeColor.inkPrimary) }
            Button(action: openKit) { Text(BipeStrings.businessKitTitle).foregroundStyle(BipeColor.inkPrimary) }
        }
        .alert(blocked?.missing == "catalog_product" ? BipeStrings.workToolNeedsProduct : BipeStrings.workToolNeedsService,
               isPresented: Binding(get: { blocked != nil }, set: { if !$0 { blocked = nil } })) {
            Button(BipeStrings.workToolOpenCatalog) { openTool("catalog") }
            Button(BipeStrings.appCancel, role: .cancel) {}
        }
    }

    private func turnOn(id: String) {
        if let tool = model.tools?.first(where: { $0.id == id }) { turnOn(tool) }
    }

    /// Action turnOn: a missing dependency says what is missing and opens the catálogo.
    private func turnOn(_ tool: BipeBusinessTool) {
        if tool.missing != nil {
            blocked = tool
            return
        }
        Task {
            await model.set(tool, to: .setup)
            openTool(tool.id)
        }
    }

    @ViewBuilder
    private func todaySection(_ today: BipeBusinessToday) -> some View {
        Section(BipeStrings.workToday) {
            ForEach(today.bookings) { booking in
                HStack {
                    Text(booking.web ? "\(BookingText.when(booking)) · \(BipeStrings.webRecordMark)" : BookingText.when(booking)).foregroundStyle(BipeColor.inkPrimary)
                    Spacer()
                    if booking.status == .requested {
                        Button(BipeStrings.workTodayConfirm) {
                            Task { await model.act { _ = try await model.api.ownerAction(bookingId: booking.id, .accept) } }
                        }
                        .buttonStyle(.borderless)
                    }
                }
            }
            ForEach(today.orders) { order in
                HStack {
                    Text(order.web ? "\(BipeMoney.text(cents: order.totalAmountCents)) · \(BipeStrings.webRecordMark)" : BipeMoney.text(cents: order.totalAmountCents)).foregroundStyle(BipeColor.inkPrimary)
                    Spacer()
                    if order.status == .sent {
                        Button(BipeStrings.workTodayAccept) {
                            Task { await model.act { _ = try await model.api.ownerOrderAction(orderId: order.id, .accept) } }
                        }
                        .buttonStyle(.borderless)
                    }
                }
            }
            if today.queueOpen || today.queueWaiting > 0 {
                HStack {
                    Text(BipeStrings.workTodayQueueWaiting(String(today.queueWaiting))).foregroundStyle(BipeColor.inkPrimary)
                    Spacer()
                    if today.queueWaiting > 0 {
                        Button(BipeStrings.workTodayCallNext) {
                            Task { await model.act { _ = try await model.api.callNext() } }
                        }
                        .buttonStyle(.borderless)
                    }
                }
            }
            if !today.quoteRequests.isEmpty {
                HStack {
                    Text(BipeStrings.workTodayQuotes).foregroundStyle(BipeColor.inkPrimary)
                    Spacer()
                    Button(BipeStrings.workTodayAnswer) { openTool("quotes") }.buttonStyle(.borderless)
                }
            }
        }
    }
}

/// One tool row: its icon, name and state.
struct WorkToolLabel: View {
    let id: String
    let subtitle: String?

    var body: some View {
        HStack(spacing: 12) {
            WorkText.icon(id).image
                .resizable()
                .frame(width: 24, height: 24)
                .foregroundStyle(BipeColor.accentInk)
            VStack(alignment: .leading, spacing: 2) {
                Text(WorkText.tool(id)).foregroundStyle(BipeColor.inkPrimary)
                if let subtitle {
                    Text(subtitle).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                }
            }
            Spacer()
        }
    }
}

/// Spec work-setup: the full list of steps. The server computes each one; the owner does not tick a step by hand.
struct WorkSetupView: View {
    @ObservedObject var model: WorkHomeModel
    let people: PeopleService
    let openTool: (String) -> Void
    @State private var shareLink: URL?

    var body: some View {
        List {
            if let setup = model.setup {
                Section {
                    ForEach(setup.steps) { step in
                        Button { open(step, setup) } label: {
                            HStack(spacing: 12) {
                                Image(systemName: step.done ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(step.done ? BipeColor.accentInk : BipeColor.inkSecondary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(WorkText.step(step.id, firstTool: setup.firstTool).title).foregroundStyle(BipeColor.inkPrimary)
                                    Text(WorkText.step(step.id, firstTool: setup.firstTool).body).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                                }
                            }
                        }
                    }
                } footer: {
                    Text(setup.doneCount == setup.steps.count ? BipeStrings.workSetupDone : BipeStrings.workProgress(String(setup.doneCount), String(setup.steps.count)))
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(BipeStrings.workSetupTitle)
        .accessibilityIdentifier(BipeScreen.workSetup.rawValue)
        .sheet(isPresented: Binding(get: { shareLink != nil }, set: { if !$0 { shareLink = nil } })) {
            if let shareLink { ShareSheet(items: [shareLink]) }
        }
    }

    private func open(_ step: BipeWorkSetupStep, _ setup: BipeWorkSetup) {
        switch step.id {
        case "catalog": openTool("catalog")
        case "first_tool": openTool(setup.firstTool)
        case "link":
            Task {
                let mine = try? await people.withOpenID { (token: BipeOpenIDToken) async throws(BipeAPIError) -> BipeMyHandle in
                    try await people.api.myHandle(openID: token)
                }
                shareLink = mine?.handle.flatMap(BipeProfileLink.url(handle:))
                _ = try? await model.api.workLinkOpened()
                await model.load()
            }
        // Perfil and Selo live under Você on the phone: the row says what to do.
        default: break
        }
    }
}

/// Spec work-sector: the sector list with Outro and Pular. It never limits the tools (ADR 0194 point 9).
struct WorkSectorView: View {
    @ObservedObject var model: WorkHomeModel
    let close: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(BipeRegistries.businessSectorIds, id: \.self) { id in
                        Button {
                            Task { await model.setSector(id) }
                            close()
                        } label: {
                            HStack {
                                Text(WorkText.sector(id)).foregroundStyle(BipeColor.inkPrimary)
                                Spacer()
                                if model.sector == id { Image(systemName: "checkmark").foregroundStyle(BipeColor.accentInk) }
                            }
                        }
                    }
                } footer: {
                    Text(BipeStrings.workSectorBody)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(BipeStrings.workSectorTitle)
            .accessibilityIdentifier(BipeScreen.workSector.rawValue)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.workSectorSkip) {
                        Task { await model.setSector(nil) }
                        close()
                    }
                }
            }
        }
    }
}

/// Each owner screen has Publicar (live) and Desligar (off).
struct WorkToolStateBar: View {
    @ObservedObject var model: WorkHomeModel
    let id: String

    private var tool: BipeBusinessTool? { model.tools?.first { $0.id == id } }

    private var webKey: WritableKeyPath<BipeWebSettings, Bool>? {
        switch id {
        case "agenda": \.webBooking
        case "orders": \.webOrders
        case "queue": \.webQueue
        default: nil
        }
    }

    private var webTitle: String {
        switch id {
        case "agenda": BipeStrings.webBookingSwitch
        case "orders": BipeStrings.webOrdersSwitch
        default: BipeStrings.webQueueSwitch
        }
    }

    var body: some View {
        if let web = model.web, tool?.state == .live, let key = webKey {
            Toggle(webTitle,
                   isOn: Binding(get: { web[keyPath: key] },
                                 set: { on in
                                     var next = web
                                     next[keyPath: key] = on
                                     Task { await model.setWeb(next) }
                                 }))
                .font(.footnote)
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .background(.bar)
        }
        if let tool, tool.state != .off {
            HStack {
                Text(tool.state == .live ? BipeStrings.workToolStateLive : BipeStrings.workToolStateSetup)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.inkSecondary)
                Spacer()
                if tool.state == .setup {
                    Button(BipeStrings.workToolPublish) { Task { await model.set(tool, to: .live) } }
                }
                Button(BipeStrings.workToolTurnOff, role: .destructive) { Task { await model.set(tool, to: .off) } }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.bar)
        }
    }
}
