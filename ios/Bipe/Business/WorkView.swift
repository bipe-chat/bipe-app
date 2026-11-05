// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec app-tabs and business-tools: the Trabalho tab of a company account, in the place of Grupos, step by step
/// (ADR 0194). Each business tool opens its owner screen inside this tab.
struct WorkView: View {
    let chatList: ChatListModel
    private let api: BipeAPIClient
    /// Opens a chat in Conversas.
    private let openChat: (String) -> Void
    @StateObject private var people: PeopleService
    @StateObject private var home: WorkHomeModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var path = NavigationPath()
    private enum Destination: Hashable { case agenda, catalogOwner, ordersOwner, quotesOwner, queueOwner, brandMarks, kit, setup }

    init(chatList: ChatListModel, api: BipeAPIClient, openChat: @escaping (String) -> Void) {
        self.chatList = chatList
        self.api = api
        self.openChat = openChat
        _people = StateObject(wrappedValue: PeopleService(session: chatList.session, api: api, media: chatList.media))
        _home = StateObject(wrappedValue: WorkHomeModel(api: chatList.session.business(api), accountId: chatList.session.userId))
    }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                WorkHomeSections(model: home,
                                 openTool: open(tool:),
                                 openSetup: { path.append(Destination.setup) },
                                 openBrandMarks: { path.append(Destination.brandMarks) },
                                 openKit: { path.append(Destination.kit) },
                                 openSector: { home.askSector = true })
            }
            .accessibilityIdentifier(BipeScreen.businessTools.rawValue)
            .task { await home.load() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await home.load() } }
            }
            .sheet(isPresented: $home.askSector) {
                WorkSectorView(model: home, close: { home.askSector = false })
            }
            .toast($home.notice)
            .listStyle(.insetGrouped)
            .navigationTitle(BipeStrings.tabWork)
            .navigationDestination(for: Destination.self) { destination in
                switch destination {
                case .agenda:
                    AgendaOwnerView(chatList: chatList, api: api, openChat: openChat)
                        .safeAreaInset(edge: .bottom) { WorkToolStateBar(model: home, id: "agenda") }
                case .catalogOwner:
                    CatalogOwnerView(api: chatList.session.business(api))
                        .safeAreaInset(edge: .bottom) { WorkToolStateBar(model: home, id: "catalog") }
                case .ordersOwner:
                    OrderOwnerView(context: businessOwnerContext)
                        .safeAreaInset(edge: .bottom) { WorkToolStateBar(model: home, id: "orders") }
                case .quotesOwner:
                    QuoteOwnerView(context: businessOwnerContext)
                        .safeAreaInset(edge: .bottom) { WorkToolStateBar(model: home, id: "quotes") }
                case .queueOwner:
                    QueueOwnerView(context: businessOwnerContext)
                        .safeAreaInset(edge: .bottom) { WorkToolStateBar(model: home, id: "queue") }
                case .brandMarks:
                    BrandMarksView(api: chatList.session.business(api), people: people)
                case .kit:
                    BusinessKitView(people: people)
                case .setup:
                    WorkSetupView(model: home, people: people, openTool: open(tool:))
                }
            }
        }
    }

    private func open(tool id: String) {
        switch id {
        case "agenda": path.append(Destination.agenda)
        case "catalog": path.append(Destination.catalogOwner)
        case "orders": path.append(Destination.ordersOwner)
        case "quotes": path.append(Destination.quotesOwner)
        default: path.append(Destination.queueOwner)
        }
    }

    /// The owner screens of Pedidos, Orçamentos and Fila virtual: names, the DM partners, and the DM with a customer.
    private var businessOwnerContext: BusinessOwnerContext {
        BusinessOwnerContext(api: chatList.session.business(api), people: people,
                             customers: BusinessCustomer.fromChats(chatList.chats),
                             openChat: { userId in
                                 Task {
                                     if let roomId = try? await people.openOrCreateDM(with: userId) { openChat(roomId) }
                                 }
                             })
    }
}
