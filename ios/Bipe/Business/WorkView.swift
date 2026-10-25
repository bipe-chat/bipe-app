// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec app-tabs and business-tools: the Trabalho tab of a company account, in the place of Grupos.
/// Each business tool opens its owner screen inside this tab.
struct WorkView: View {
    let chatList: ChatListModel
    private let api: BipeAPIClient
    /// Opens a chat in Conversas.
    private let openChat: (String) -> Void
    @StateObject private var people: PeopleService
    @State private var path = NavigationPath()
    private enum Destination: Hashable { case agenda, catalogOwner, ordersOwner, quotesOwner, queueOwner, brandMarks }

    init(chatList: ChatListModel, api: BipeAPIClient, openChat: @escaping (String) -> Void) {
        self.chatList = chatList
        self.api = api
        self.openChat = openChat
        _people = StateObject(wrappedValue: PeopleService(session: chatList.session, api: api, media: chatList.media))
    }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                BusinessToolsSection(api: chatList.session.business(api),
                                     openAgenda: { path.append(Destination.agenda) },
                                     openCatalog: { path.append(Destination.catalogOwner) },
                                     openOrders: { path.append(Destination.ordersOwner) },
                                     openQuotes: { path.append(Destination.quotesOwner) },
                                     openQueue: { path.append(Destination.queueOwner) },
                                     openBrandMarks: { path.append(Destination.brandMarks) })
            }
            .listStyle(.insetGrouped)
            .navigationTitle(BipeStrings.tabWork)
            .navigationDestination(for: Destination.self) { destination in
                switch destination {
                case .agenda:
                    AgendaOwnerView(chatList: chatList, api: api, openChat: openChat)
                case .catalogOwner:
                    CatalogOwnerView(api: chatList.session.business(api))
                case .ordersOwner:
                    OrderOwnerView(context: businessOwnerContext)
                case .quotesOwner:
                    QuoteOwnerView(context: businessOwnerContext)
                case .queueOwner:
                    QueueOwnerView(context: businessOwnerContext)
                case .brandMarks:
                    BrandMarksView(api: chatList.session.business(api), people: people)
                }
            }
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
