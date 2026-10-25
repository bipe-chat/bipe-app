// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

// Pedidos (ADR 0105): order-customer, the order card, and order-owner. The app offers pay on site only: Pix waits for
// Pix charge cards (ADR 0080), and Bipe moves no money (ADR 0012). The card never says paid.

/// The items and quantities of an order being built. Only active fixed-price products can be in it.
struct OrderCart: Equatable {
    static let maxQuantity = 999

    private(set) var quantities: [String: Int] = [:]

    var isEmpty: Bool { quantities.isEmpty }

    func quantity(_ itemId: String) -> Int { quantities[itemId] ?? 0 }

    /// Action add: one more of the item.
    mutating func add(_ itemId: String) {
        set(itemId, quantity(itemId) + 1)
    }

    /// Action setQuantity: 0 removes the item.
    mutating func set(_ itemId: String, _ quantity: Int) {
        quantities[itemId] = quantity <= 0 ? nil : min(quantity, Self.maxQuantity)
    }

    /// The lines in the catalogue's order.
    func lines(_ products: [BipeCatalogItem]) -> [BipeOrderLineInput] {
        products.compactMap { item in
            let quantity = quantity(item.id)
            return quantity > 0 ? BipeOrderLineInput(itemId: item.id, quantity: quantity) : nil
        }
    }

    /// The total as a preview; the server snapshots the prices and adds the total.
    func total(_ products: [BipeCatalogItem]) -> Int {
        products.reduce(0) { sum, item in sum + quantity(item.id) * (item.price.amountCents ?? 0) }
    }
}

/// The cart and the products to add: shared by order-customer and the owner's new order.
struct OrderCartSections: View {
    let products: [BipeCatalogItem]
    @Binding var cart: OrderCart

    var body: some View {
        Section {
            if cart.isEmpty {
                Text(BipeStrings.orderEmpty)
                    .foregroundStyle(BipeColor.inkSecondary)
            } else {
                ForEach(products.filter { cart.quantity($0.id) > 0 }) { item in
                    Stepper(value: Binding(get: { cart.quantity(item.id) }, set: { cart.set(item.id, $0) }), in: 0...OrderCart.maxQuantity) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name).foregroundStyle(BipeColor.inkPrimary)
                            Text(BipeStrings.orderLine(String(cart.quantity(item.id)), BusinessText.money(item.price.amountCents ?? 0)))
                                .font(.subheadline)
                                .foregroundStyle(BipeColor.inkSecondary)
                        }
                    }
                    .accessibilityHint(BipeStrings.orderQuantity)
                }
            }
        } header: {
            Text(BipeStrings.orderCart)
        }
        let rest = products.filter { cart.quantity($0.id) == 0 }
        if !rest.isEmpty {
            Section {
                ForEach(rest) { item in
                    Button { cart.add(item.id) } label: {
                        HStack(spacing: 8) {
                            CatalogItemRow(item: item)
                            Text(BipeStrings.orderAdd)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(item.soldOutToday ? BipeColor.inkDisabled : BipeColor.accentInk)
                        }
                    }
                    .disabled(item.soldOutToday)
                }
            } header: {
                Text(BipeStrings.catalogTitle)
            }
        }
    }
}

/// Pickup or delivery, and the one payment the app offers.
struct OrderFulfilmentSections: View {
    @Binding var fulfilment: BipeFulfilment
    let total: Int

    var body: some View {
        Section {
            Picker(BipeStrings.orderFulfilment, selection: $fulfilment) {
                ForEach(BipeFulfilment.allCases, id: \.self) { choice in
                    Text(choice.text).tag(choice)
                }
            }
            .pickerStyle(.segmented)
        } header: {
            Text(BipeStrings.orderFulfilment)
        } footer: {
            Text(BipeStrings.orderAddressInChat)
        }
        Section {
            HStack(spacing: 8) {
                PhosphorIcon.checkCircle.image
                    .resizable()
                    .frame(width: 20, height: 20)
                    .foregroundStyle(BipeColor.accentInk)
                Text(BipeStrings.orderPayOnSite).foregroundStyle(BipeColor.inkPrimary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isSelected)
        } header: {
            Text(BipeStrings.orderPayment)
        }
        Section {
            LabeledContent(BipeStrings.orderTotal, value: BusinessText.money(total))
                .font(.headline)
        }
    }
}

// MARK: - order-customer

/// Spec order-customer (bipe-shared product/screens/order-customer.json): the customer builds an order from the
/// catálogo's fixed-price products, picks retirada or entrega, sees pay on site, and sends it. The address and notes
/// go in the chat.
struct OrderCustomerView: View {
    let api: BipeBusinessAPI
    let business: String
    /// One of this item is in the cart at the start (Adicionar ao pedido in catalog-view).
    let initialItemId: String?
    /// Closes the screen; the text is the result to show (bipe_order_sent), or nil.
    let close: (String?) -> Void

    @State private var products: [BipeCatalogItem] = []
    @State private var loading = true
    @State private var failed = false
    @State private var cart = OrderCart()
    @State private var fulfilment: BipeFulfilment = .pickup
    @State private var sending = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            content
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .navigationTitle(BipeStrings.orderMake)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(BipeStrings.appCancel) { close(nil) }
                    }
                }
        }
        .accessibilityIdentifier(BipeScreen.orderCustomer.rawValue)
        .task { await load() }
    }

    @ViewBuilder
    private var content: some View {
        if loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if failed {
            VStack(spacing: 16) {
                Text(BipeStrings.catalogLoadFailed)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
                Button(BipeStrings.appRetry) { Task { await load() } }
                    .buttonStyle(.borderedProminent)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            Form {
                OrderCartSections(products: products, cart: $cart)
                OrderFulfilmentSections(fulfilment: $fulfilment, total: cart.total(products))
                Section {
                    Button {
                        Task { await send() }
                    } label: {
                        HStack {
                            Spacer()
                            if sending { ProgressView() } else { Text(BipeStrings.orderSend).bold() }
                            Spacer()
                        }
                    }
                    .disabled(cart.isEmpty || sending)
                    .listRowBackground(BipeColor.accentWash)
                } footer: {
                    if let error {
                        Text(error).foregroundStyle(BipeColor.trustDangerInk)
                    }
                }
            }
        }
    }

    /// The business's active fixed-price products, from its public catálogo.
    private func load() async {
        loading = true
        defer { loading = false }
        do {
            products = try await api.catalog(business: business).filter { $0.isOrderable }
            failed = false
            if let initialItemId, cart.isEmpty, let item = products.first(where: { $0.id == initialItemId }), !item.soldOutToday {
                cart.add(item.id)
            }
        } catch {
            failed = true
        }
    }

    /// Action send: lines, fulfilment and in_person. Success shows bipe_order_sent.
    private func send() async {
        sending = true
        error = nil
        defer { sending = false }
        do {
            _ = try await api.placeOrder(business: business, lines: cart.lines(products), fulfilment: fulfilment)
            close(BipeStrings.orderSent)
        } catch {
            let apiError = error as? BipeAPIError
            self.error = apiError?.orderMessage ?? BipeStrings.orderError
            // A changed catálogo: read it again.
            if case .server(.itemUnavailable, _, _) = apiError { await load() }
        }
    }
}

// MARK: - The order card

/// The order card pinned in the chat: lines, total, pickup or delivery, pay on site and the status (ADR 0105). The
/// customer can cancel an open order; on the company side a tap opens the order.
struct OrderCardView: View {
    enum Actions {
        case customer(cancel: () -> Void)
        case owner(open: () -> Void)
    }

    let order: BipeOrder
    /// Owner mode: the customer's name.
    let customerName: String?
    let actions: Actions

    var body: some View {
        switch actions {
        case .owner(let open):
            Button(action: open) { card }
                .buttonStyle(.plain)
        case .customer:
            card
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(BipeStrings.orderCardTitle)
                .font(.caption.bold())
                .foregroundStyle(BipeColor.accentInk)
            if let customerName, !customerName.isEmpty {
                Text(customerName)
                    .font(.headline)
                    .foregroundStyle(BipeColor.inkPrimary)
            }
            OrderSummary(order: order)
            if case .customer(let cancel) = actions, order.status.isOpen {
                Button(BipeStrings.orderCancel, role: .destructive, action: cancel)
                    .padding(.top, 2)
            }
        }
        .businessCard(.orderCustomer)
    }
}

/// The lines, total, fulfilment, payment and status of an order.
struct OrderSummary: View {
    let order: BipeOrder

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(BusinessText.when(order.createdAt))
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkSecondary)
            ForEach(Array(order.lines.enumerated()), id: \.offset) { _, line in
                HStack(alignment: .firstTextBaseline) {
                    Text(BusinessText.line(line)).foregroundStyle(BipeColor.inkPrimary)
                    Spacer(minLength: 8)
                    Text(BusinessText.money(line.totalAmountCents))
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
            HStack {
                Text(BipeStrings.orderTotal).bold()
                Spacer(minLength: 8)
                Text(BusinessText.money(order.totalAmountCents)).bold().monospacedDigit()
            }
            .foregroundStyle(BipeColor.inkPrimary)
            Text(order.fulfilment.text + " · " + BipeStrings.orderPayOnSite)
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkSecondary)
            BusinessStatusLine(text: order.status.text, tint: order.status.tint)
            if order.status == .refused, let reason = order.refuseReason {
                Text(BipeStrings.orderRefuseReasonLabel(reason.text))
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
        }
    }
}

// MARK: - order-owner

/// Spec order-owner (bipe-shared product/screens/order-owner.json): the company's orders, newest first, with the
/// order actions, an owner-entered order, and the order days and cut-off. Opens from business-tools.
struct OrderOwnerView: View {
    let context: BusinessOwnerContext

    @State private var orders: [BipeOrder] = []
    @State private var names: [String: String] = [:]
    @State private var loading = true
    @State private var failed = false
    @State private var opened: BipeOrder?
    @State private var showsNew = false
    @State private var showsSettings = false
    @State private var notice: String?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        content
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.ordersTitle)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { showsNew = true } label: {
                        Label {
                            Text(BipeStrings.orderNew)
                        } icon: {
                            PhosphorIcon.plus.image.resizable().frame(width: 22, height: 22)
                        }
                    }
                    .labelStyle(.iconOnly)
                }
                ToolbarItem(placement: .secondaryAction) {
                    Button(BipeStrings.orderSettings) { showsSettings = true }
                }
            }
            .accessibilityIdentifier(BipeScreen.orderOwner.rawValue)
            .task { await load() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await load() } }
            }
            .refreshable { await load() }
            .toast($notice)
            .sheet(item: $opened) { order in
                OrderOwnerDetailView(context: context, order: order, customerName: BusinessText.customer(order.customer, names: names),
                                     close: { message in
                                         opened = nil
                                         if let message { notice = message }
                                         Task { await load() }
                                     })
            }
            .sheet(isPresented: $showsNew) {
                OrderOwnerNewView(context: context) { message in
                    showsNew = false
                    if let message { notice = message }
                    Task { await load() }
                }
            }
            .sheet(isPresented: $showsSettings) {
                OrderSettingsView(api: context.api) { message in
                    showsSettings = false
                    if let message { notice = message }
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        if loading && orders.isEmpty {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if failed && orders.isEmpty {
            VStack(spacing: 16) {
                Text(BipeStrings.orderLoadFailed)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
                Button(BipeStrings.appRetry) { Task { await load() } }
                    .buttonStyle(.borderedProminent)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if orders.isEmpty {
            Text(BipeStrings.orderOwnerEmpty)
                .foregroundStyle(BipeColor.inkSecondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List {
                ForEach(orders) { order in
                    Button { opened = order } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(BusinessText.customer(order.customer, names: names))
                                    .font(.headline)
                                    .foregroundStyle(BipeColor.inkPrimary)
                                Spacer(minLength: 8)
                                Text(BusinessText.money(order.totalAmountCents))
                                    .monospacedDigit()
                                    .foregroundStyle(BipeColor.inkPrimary)
                            }
                            Text(BusinessText.when(order.createdAt) + " · " + order.fulfilment.text)
                                .font(.subheadline)
                                .foregroundStyle(BipeColor.inkSecondary)
                            BusinessStatusLine(text: order.status.text, tint: order.status.tint)
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
        }
    }

    /// GET /v1/me/business/orders, newest first.
    private func load() async {
        loading = true
        defer { loading = false }
        do {
            orders = try await context.api.ownerOrders().sorted { $0.createdAt > $1.createdAt }
            failed = false
            names = await context.names(for: orders.compactMap(\.customer.userId), known: names)
        } catch {
            failed = true
            if !orders.isEmpty { notice = BipeStrings.orderLoadFailed }
        }
    }
}

/// One order on the company side: Aceitar, Recusar with a fixed reason, Pronto, Entregue and Cancelar.
struct OrderOwnerDetailView: View {
    let context: BusinessOwnerContext
    let order: BipeOrder
    let customerName: String
    /// Closes the sheet; the text is the result to show, or nil.
    let close: (String?) -> Void

    @State private var working = false
    @State private var error: String?
    @State private var refusing = false
    @State private var confirmsCancel = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    OrderSummary(order: order)
                } header: {
                    Text(customerName)
                }
                Section {
                    if order.status == .sent {
                        Button(BipeStrings.orderAccept) { Task { await act(.accept) } }
                        Button(BipeStrings.orderRefuse, role: .destructive) { refusing = true }
                    }
                    if order.status == .accepted {
                        Button(BipeStrings.orderMarkReady) { Task { await act(.ready) } }
                    }
                    if order.status == .ready {
                        Button(BipeStrings.orderMarkDone) { Task { await act(.done) } }
                    }
                    if let userId = order.customer.userId, let openChat = context.openChat {
                        Button(BipeStrings.appPersonMessage) { openChat(userId) }
                    }
                    if order.status.isOpen {
                        Button(BipeStrings.orderCancel, role: .destructive) { confirmsCancel = true }
                    }
                } footer: {
                    if let error {
                        Text(error).foregroundStyle(BipeColor.trustDangerInk)
                    }
                }
                .disabled(working)
            }
            .navigationTitle(BipeStrings.orderCardTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appClose) { close(nil) }
                }
                if working {
                    ToolbarItem(placement: .confirmationAction) { ProgressView() }
                }
            }
            .confirmationDialog(BipeStrings.orderRefuseTitle, isPresented: $refusing, titleVisibility: .visible) {
                ForEach(BipeOrderRefuseReason.allCases, id: \.self) { reason in
                    Button(reason.text, role: .destructive) { Task { await refuse(reason) } }
                }
                Button(BipeStrings.appCancel, role: .cancel) {}
            }
            .alert(BipeStrings.orderCancelConfirm, isPresented: $confirmsCancel) {
                Button(BipeStrings.orderCancel, role: .destructive) { Task { await act(.cancel) } }
                Button(BipeStrings.appCancel, role: .cancel) {}
            }
        }
        .accessibilityIdentifier(BipeScreen.orderOwner.rawValue)
    }

    /// Actions accept, ready, done and cancel.
    private func act(_ action: BipeOwnerOrderAction) async {
        working = true
        error = nil
        defer { working = false }
        do {
            _ = try await context.api.ownerOrderAction(orderId: order.id, action)
            close(nil)
        } catch {
            self.error = (error as? BipeAPIError)?.orderMessage ?? BipeStrings.orderError
        }
    }

    /// Action refuse, with the reason from bipe_order_refuse_title.
    private func refuse(_ reason: BipeOrderRefuseReason) async {
        working = true
        error = nil
        defer { working = false }
        do {
            _ = try await context.api.refuseOrder(orderId: order.id, reason: reason)
            close(nil)
        } catch {
            self.error = (error as? BipeAPIError)?.orderMessage ?? BipeStrings.orderError
        }
    }
}

/// Action newOrder: an order from the catálogo for a typed name or a person from the chats, accepted at once.
/// A name-only order gets no card (ADR 0105).
struct OrderOwnerNewView: View {
    private enum CustomerSource: Hashable {
        case chats
        case typed
    }

    let context: BusinessOwnerContext
    let close: (String?) -> Void

    @State private var products: [BipeCatalogItem] = []
    @State private var loading = true
    @State private var failed = false
    @State private var cart = OrderCart()
    @State private var fulfilment: BipeFulfilment = .pickup
    @State private var source: CustomerSource = .chats
    @State private var customerUserId: String?
    @State private var typedName = ""
    @State private var saving = false
    @State private var error: String?

    private var customer: BipeBookingCustomer? {
        switch source {
        case .chats:
            return customerUserId.map { BipeBookingCustomer.user($0) }
        case .typed:
            let name = typedName.trimmingCharacters(in: .whitespacesAndNewlines)
            return name.isEmpty ? nil : BipeBookingCustomer.named(name)
        }
    }

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView()
                } else if failed {
                    VStack(spacing: 16) {
                        Text(BipeStrings.catalogLoadFailed)
                            .foregroundStyle(BipeColor.inkSecondary)
                            .multilineTextAlignment(.center)
                        Button(BipeStrings.appRetry) { Task { await load() } }
                            .buttonStyle(.borderedProminent)
                    }
                    .padding(24)
                } else {
                    form
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.orderNew)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { close(nil) }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button(BipeStrings.appSave) { Task { await save() } }
                            .disabled(cart.isEmpty || customer == nil)
                    }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.orderOwner.rawValue)
        .task { await load() }
    }

    private var form: some View {
        Form {
            Section {
                Picker(BipeStrings.agendaCustomer, selection: $source) {
                    Text(BipeStrings.agendaCustomerFromChats).tag(CustomerSource.chats)
                    Text(BipeStrings.agendaCustomerName).tag(CustomerSource.typed)
                }
                .pickerStyle(.segmented)
                if source == .chats {
                    Picker(BipeStrings.agendaCustomer, selection: $customerUserId) {
                        Text(verbatim: "—").tag(String?.none)
                        ForEach(context.customers) { customer in
                            Text(customer.name).tag(String?.some(customer.userId))
                        }
                    }
                } else {
                    TextField(BipeStrings.agendaCustomerName, text: $typedName)
                }
            } header: {
                Text(BipeStrings.agendaCustomer)
            }
            OrderCartSections(products: products, cart: $cart)
            OrderFulfilmentSections(fulfilment: $fulfilment, total: cart.total(products))
            if let error {
                Section {
                    Text(error).foregroundStyle(BipeColor.trustDangerInk)
                }
            }
        }
    }

    /// The company's active fixed-price products, from its own catálogo.
    private func load() async {
        loading = true
        defer { loading = false }
        do {
            products = try await context.api.ownerCatalog().filter { $0.isOrderable }
            failed = false
            if customerUserId == nil, context.customers.count == 1 { customerUserId = context.customers.first?.userId }
        } catch {
            failed = true
        }
    }

    /// POST /v1/me/business/orders, paid in person.
    private func save() async {
        guard let customer else { return }
        saving = true
        error = nil
        defer { saving = false }
        do {
            _ = try await context.api.ownerCreateOrder(lines: cart.lines(products), fulfilment: fulfilment, customer: customer)
            close(nil)
        } catch {
            self.error = (error as? BipeAPIError)?.orderMessage ?? BipeStrings.orderError
        }
    }
}

/// Action saveSettings: the order days (ISO weekdays) and the local cut-off, PUT /v1/me/business/orders/policy.
struct OrderSettingsView: View {
    let api: BipeBusinessAPI
    let close: (String?) -> Void

    @State private var policy: BipeOrderPolicy?
    @State private var weekdays: Set<Int> = []
    @State private var cutoff = "23:59"
    @State private var failed = false
    @State private var saving = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Group {
                if policy == nil {
                    if failed {
                        VStack(spacing: 16) {
                            Text(BipeStrings.orderLoadFailed)
                                .foregroundStyle(BipeColor.inkSecondary)
                                .multilineTextAlignment(.center)
                            Button(BipeStrings.appRetry) { Task { await load() } }
                                .buttonStyle(.borderedProminent)
                        }
                        .padding(24)
                    } else {
                        ProgressView()
                    }
                } else {
                    form
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.orderSettings)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { close(nil) }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button(BipeStrings.appSave) { Task { await save() } }
                            .disabled(policy == nil)
                    }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.orderOwner.rawValue)
        .task { await load() }
    }

    private var form: some View {
        Form {
            Section {
                ForEach(1...7, id: \.self) { weekday in
                    Toggle(AgendaSetupView.weekdayName(weekday), isOn: Binding(get: { weekdays.contains(weekday) }, set: { on in
                        if on { weekdays.insert(weekday) } else { weekdays.remove(weekday) }
                    }))
                }
            } header: {
                Text(BipeStrings.orderOpeningDays)
            }
            Section {
                BusinessClockPicker(title: BipeStrings.orderCutoff, clock: $cutoff)
            }
            if let error {
                Section {
                    Text(error).foregroundStyle(BipeColor.trustDangerInk)
                }
            }
        }
    }

    private func load() async {
        do {
            let loaded = try await api.orderPolicy()
            weekdays = Set(loaded.weekdays)
            cutoff = loaded.cutoff
            policy = loaded
            failed = false
        } catch {
            failed = true
        }
    }

    private func save() async {
        guard let policy else { return }
        saving = true
        error = nil
        defer { saving = false }
        do {
            self.policy = try await api.setOrderPolicy(BipeOrderPolicy(timeZone: policy.timeZone, weekdays: weekdays.sorted(), cutoff: cutoff))
            close(nil)
        } catch {
            self.error = (error as? BipeAPIError)?.orderMessage ?? BipeStrings.orderError
        }
    }
}
