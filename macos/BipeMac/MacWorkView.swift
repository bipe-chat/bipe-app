// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Combine
import SwiftUI

/// The first Work slice reads the same company-token routes as the phone. Each section fails independently.
@MainActor
final class MacWorkModel: ObservableObject {
    @Published private(set) var tools: BipeBusinessTools?
    @Published private(set) var today: BipeBusinessToday?
    @Published private(set) var catalog: [BipeCatalogItem]?
    @Published private(set) var people: BipeSeatList?
    @Published private(set) var failed: Set<String> = []
    @Published private(set) var loading = false
    private let business: BipeBusinessAPI

    init(business: BipeBusinessAPI) { self.business = business }

    func refresh() async {
        guard !loading else { return }
        loading = true
        failed = []
        async let tools: Void = readTools()
        async let today: Void = readToday()
        async let catalog: Void = readCatalog()
        async let people: Void = readPeople()
        _ = await (tools, today, catalog, people)
        loading = false
    }

    private func readTools() async {
        do { tools = try await business.businessTools() } catch { if !Task.isCancelled { failed.insert("tools") } }
    }
    private func readToday() async {
        do { today = try await business.businessToday() } catch { if !Task.isCancelled { failed.insert("today") } }
    }
    private func readCatalog() async {
        do { catalog = try await business.ownerCatalog() } catch { if !Task.isCancelled { failed.insert("catalog") } }
    }
    private func readPeople() async {
        do { people = try await business.seats() } catch { if !Task.isCancelled { failed.insert("people") } }
    }

    static func toolName(_ id: String) -> String? {
        switch id {
        case "catalog": BipeStrings.catalogTitle
        case "agenda": BipeStrings.tabAgenda
        case "orders": BipeStrings.ordersTitle
        case "quotes": BipeStrings.quotesTitle
        case "queue": BipeStrings.queueTitle
        default: nil
        }
    }
}

struct MacWorkView: View {
    @StateObject private var model: MacWorkModel
    init(account: MacAccount) { _model = StateObject(wrappedValue: MacWorkModel(business: account.business)) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: MacDesign.sectionGap) {
                HStack {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(BipeStrings.tabWork).macFont(.largeTitle).fontWeight(.bold)
                        Text(BipeStrings.macWorkOverview).foregroundStyle(BipeColor.inkSecondary)
                    }
                    Spacer()
                    if model.loading { ProgressView() }
                    Button(BipeStrings.macRefresh) { Task { await model.refresh() } }
                        .buttonStyle(.bipeBordered).disabled(model.loading)
                }
                if !model.failed.isEmpty { MacNotice(text: BipeStrings.errorGeneric, danger: true) }
                if let today = model.today {
                    Text(BipeStrings.workToday).macFont(.title2).fontWeight(.semibold)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 200), spacing: MacDesign.gap)], spacing: MacDesign.gap) {
                        metric(BipeStrings.workTodayBookings, today.bookings.count.formatted())
                        metric(BipeStrings.workTodayOrders, today.orders.count.formatted())
                        metric(BipeStrings.workTodayQuotes, today.quoteRequests.count.formatted())
                        metric(BipeStrings.workTodayQueue, BipeStrings.workTodayQueueWaiting(today.queueWaiting.formatted()))
                    }
                }
                if let tools = model.tools {
                    MacCard {
                        VStack(alignment: .leading, spacing: MacDesign.gap) {
                            Text(BipeStrings.businessToolsTitle).macFont(.headline)
                            ForEach(tools.tools) { tool in
                                if let name = MacWorkModel.toolName(tool.id) {
                                    HStack {
                                        Text(name)
                                        Spacer()
                                        Text(stateName(tool.state)).macFont(.callout).foregroundStyle(BipeColor.inkSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
                if let catalog = model.catalog {
                    MacCard {
                        VStack(alignment: .leading, spacing: MacDesign.gap) {
                            Text(BipeStrings.catalogTitle).macFont(.headline)
                            if catalog.isEmpty { Text(BipeStrings.catalogOwnerEmpty).foregroundStyle(BipeColor.inkSecondary) }
                            ForEach(catalog) { item in
                                HStack(alignment: .top, spacing: MacDesign.gap) {
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(item.name).fontWeight(.semibold)
                                        if let description = item.description { Text(description).macFont(.callout).foregroundStyle(BipeColor.inkSecondary) }
                                        if !item.active { Text(BipeStrings.accessibilityOff).macFont(.caption) }
                                        if item.soldOutToday { Text(BipeStrings.catalogSoldOutToday).macFont(.caption) }
                                    }
                                    Spacer()
                                    Text(item.price.displayText).foregroundStyle(BipeColor.inkSecondary)
                                }
                                if item.id != catalog.last?.id { Divider() }
                            }
                        }
                    }
                }
                if let people = model.people {
                    MacCard {
                        VStack(alignment: .leading, spacing: MacDesign.gap) {
                            Text(BipeStrings.teamTitle).macFont(.headline)
                            ForEach(people.activeSeats) { seat in
                                HStack {
                                    MacAvatar(name: seat.name)
                                    Text(seat.name)
                                    Spacer()
                                    Text(seat.role == "owner" ? BipeStrings.teamRoleOwner : BipeStrings.teamRoleAttendant)
                                        .macFont(.callout).foregroundStyle(BipeColor.inkSecondary)
                                }
                            }
                        }
                    }
                }
            }.padding(MacDesign.pageInset).frame(maxWidth: 1100, alignment: .leading)
                .frame(maxWidth: .infinity, alignment: .leading)
        }.background(BipeColor.surfaceBase).task { await model.refresh() }
    }

    private func metric(_ title: String, _ value: String) -> some View {
        MacCard {
            VStack(alignment: .leading, spacing: 12) {
                Text(title).macFont(.callout).foregroundStyle(BipeColor.inkSecondary)
                Text(value).macFont(.title2).fontWeight(.semibold)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func stateName(_ state: BipeBusinessToolState) -> String {
        switch state {
        case .off: BipeStrings.accessibilityOff
        case .setup: BipeStrings.workToolStateSetup
        case .live: BipeStrings.workToolStateLive
        }
    }
}
