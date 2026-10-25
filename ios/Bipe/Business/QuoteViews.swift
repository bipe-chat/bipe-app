// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

// Orçamento (ADR 0106): quote-card for the customer and the company side of the chat, and quote-builder for the
// owner. A business cannot send a quote without a request. Totals come from the server; the builder shows a preview.

// MARK: - quote-card

/// Spec quote-card (bipe-shared product/screens/quote-card.json): the current version with its lines, total,
/// validity and status, Aceitar (into an order or a booking) and Recusar, and older versions readable.
struct QuoteCardView: View {
    enum Actions {
        case customer(acceptOrder: () -> Void, acceptBooking: () -> Void, refuse: () -> Void)
        /// ownerMode: Enviar nova versão instead of Aceitar and Recusar.
        case owner(revise: () -> Void)
    }

    let quote: BipeQuote
    /// The name of the visit service, when the business's public services list it.
    let visitServiceName: String?
    let actions: Actions
    /// Action showVersion: the older versions, read only.
    let showVersions: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(BipeStrings.quoteCardTitle)
                    .font(.caption.bold())
                    .foregroundStyle(BipeColor.accentInk)
                Spacer(minLength: 8)
                Text(BipeStrings.quoteVersion(String(quote.currentVersion)))
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            if let current = quote.current {
                QuoteVersionSummary(version: current, visitServiceName: visitServiceName)
            }
            BusinessStatusLine(text: quote.status.text, tint: quote.status.tint)
            buttons
        }
        .businessCard(.quoteCard)
    }

    @ViewBuilder
    private var buttons: some View {
        VStack(alignment: .leading, spacing: 6) {
            switch actions {
            case .customer(let acceptOrder, let acceptBooking, let refuse):
                if quote.status == .offered {
                    Button(BipeStrings.quoteAcceptOrder, action: acceptOrder)
                        .buttonStyle(.borderedProminent)
                    if quote.current?.visitServiceId != nil {
                        Button(BipeStrings.quoteAcceptBooking, action: acceptBooking)
                            .buttonStyle(.bordered)
                    }
                    Button(BipeStrings.quoteDecline, role: .destructive, action: refuse)
                }
            case .owner(let revise):
                if quote.status == .offered || quote.status == .expired {
                    Button(BipeStrings.quoteSendRevision, action: revise)
                }
            }
            if !quote.previous.isEmpty {
                Button(BipeStrings.quotePreviousVersions, action: showVersions)
                    .font(.subheadline)
            }
        }
        .padding(.top, 2)
    }
}

/// The lines, total, validity and visit of one version.
struct QuoteVersionSummary: View {
    let version: BipeQuoteVersion
    var visitServiceName: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(version.lines.enumerated()), id: \.offset) { _, line in
                HStack(alignment: .firstTextBaseline) {
                    Text(BusinessText.line(line)).foregroundStyle(BipeColor.inkPrimary)
                    Spacer(minLength: 8)
                    Text(BusinessText.money(line.totalAmountCents))
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
            HStack {
                Text(BipeStrings.quoteTotal).bold()
                Spacer(minLength: 8)
                Text(BusinessText.money(version.totalAmountCents)).bold().monospacedDigit()
            }
            .foregroundStyle(BipeColor.inkPrimary)
            Text(BipeStrings.quoteValidUntil(version.validUntil.formatted(date: .abbreviated, time: .omitted)))
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkSecondary)
            if version.visitServiceId != nil {
                Text([BipeStrings.quoteVisit, visitServiceName].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ": "))
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
        }
    }
}

/// Action showVersion: the versions before the current one, newest first, read only.
struct QuoteVersionsView: View {
    let quote: BipeQuote
    let close: () -> Void

    var body: some View {
        NavigationStack {
            List {
                ForEach(quote.previous) { version in
                    Section {
                        QuoteVersionSummary(version: version)
                    } header: {
                        Text(BipeStrings.quoteVersion(String(version.version)))
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(BipeStrings.quotePreviousVersions)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appClose, action: close)
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.quoteCard.rawValue)
    }
}

// MARK: - quote-builder

/// Spec quote-builder (bipe-shared product/screens/quote-builder.json): the company's quote requests and quotes.
/// Opens from business-tools.
struct QuoteOwnerView: View {
    let context: BusinessOwnerContext

    @State private var requests: [BipeQuoteRequest] = []
    @State private var quotes: [BipeQuote] = []
    @State private var names: [String: String] = [:]
    @State private var loading = true
    @State private var failed = false
    @State private var building: QuoteBuilderTarget?
    @State private var notice: String?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        content
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.quotesTitle)
            .accessibilityIdentifier(BipeScreen.quoteBuilder.rawValue)
            .task { await load() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await load() } }
            }
            .refreshable { await load() }
            .toast($notice)
            .sheet(item: $building) { target in
                QuoteBuilderView(context: context, mode: target.mode, customerName: names[target.customerId] ?? Person.localpart(of: target.customerId),
                                 close: { message in
                                     building = nil
                                     if let message { notice = message }
                                     Task { await load() }
                                 })
            }
    }

    @ViewBuilder
    private var content: some View {
        if loading && requests.isEmpty && quotes.isEmpty {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if failed && requests.isEmpty && quotes.isEmpty {
            VStack(spacing: 16) {
                Text(BipeStrings.quoteLoadFailed)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
                Button(BipeStrings.appRetry) { Task { await load() } }
                    .buttonStyle(.borderedProminent)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if requests.isEmpty && quotes.isEmpty {
            Text(BipeStrings.quoteOwnerEmpty)
                .foregroundStyle(BipeColor.inkSecondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List {
                if !requests.isEmpty {
                    Section {
                        ForEach(requests) { request in
                            VStack(alignment: .leading, spacing: 6) {
                                Text(name(request.customerId))
                                    .font(.headline)
                                    .foregroundStyle(BipeColor.inkPrimary)
                                Text(BipeStrings.quoteRequestedAt(BusinessText.when(request.createdAt)))
                                    .font(.subheadline)
                                    .foregroundStyle(BipeColor.inkSecondary)
                                HStack(spacing: 12) {
                                    Button(BipeStrings.quoteAnswer) {
                                        building = QuoteBuilderTarget(mode: .answer(request), customerId: request.customerId)
                                    }
                                    .buttonStyle(.borderedProminent)
                                    if let openChat = context.openChat {
                                        Button(BipeStrings.appPersonMessage) { openChat(request.customerId) }
                                            .buttonStyle(.bordered)
                                    }
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    } header: {
                        Text(BipeStrings.quoteRequestsTitle)
                    }
                }
                if !quotes.isEmpty {
                    Section {
                        ForEach(quotes) { quote in
                            quoteRow(quote)
                        }
                    } header: {
                        Text(BipeStrings.quotesTitle)
                    }
                }
            }
            .listStyle(.insetGrouped)
        }
    }

    private func quoteRow(_ quote: BipeQuote) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(name(quote.customerId))
                    .font(.headline)
                    .foregroundStyle(BipeColor.inkPrimary)
                Spacer(minLength: 8)
                Text(BusinessText.money(quote.current?.totalAmountCents ?? 0))
                    .monospacedDigit()
                    .foregroundStyle(BipeColor.inkPrimary)
            }
            Text(BipeStrings.quoteVersion(String(quote.currentVersion)))
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkSecondary)
            BusinessStatusLine(text: quote.status.text, tint: quote.status.tint)
            HStack(spacing: 12) {
                if quote.status == .offered || quote.status == .expired {
                    Button(BipeStrings.quoteSendRevision) {
                        building = QuoteBuilderTarget(mode: .revise(quote), customerId: quote.customerId)
                    }
                    .buttonStyle(.bordered)
                }
                if let openChat = context.openChat {
                    Button(BipeStrings.appPersonMessage) { openChat(quote.customerId) }
                        .buttonStyle(.bordered)
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func name(_ userId: String) -> String {
        names[userId] ?? Person.localpart(of: userId)
    }

    /// GET /v1/me/business/quotes/requests and /v1/me/business/quotes.
    private func load() async {
        loading = true
        defer { loading = false }
        do {
            requests = try await context.api.quoteRequests().sorted { $0.createdAt > $1.createdAt }
            quotes = try await context.api.ownerQuotes()
            failed = false
            names = await context.names(for: requests.map(\.customerId) + quotes.map(\.customerId), known: names)
        } catch {
            failed = true
            if !requests.isEmpty || !quotes.isEmpty { notice = BipeStrings.quoteLoadFailed }
        }
    }
}

/// What the builder sheet opens with.
struct QuoteBuilderTarget: Identifiable {
    let mode: QuoteBuilderView.Mode
    let customerId: String

    var id: String {
        switch mode {
        case .answer(let request): "answer-\(request.id)"
        case .revise(let quote): "revise-\(quote.id)"
        }
    }
}

/// The builder: lines from the catálogo or typed, quantities, unit prices in reais, a validity date and an
/// optional visit service. Actions answer and revise.
struct QuoteBuilderView: View {
    enum Mode {
        /// Action answer: the first version for a request.
        case answer(BipeQuoteRequest)
        /// Action revise: a new version of a quote.
        case revise(BipeQuote)
    }

    /// One line being built: a catálogo item or a typed name.
    private struct DraftLine: Identifiable {
        let id = UUID()
        var itemId: String?
        var name: String
        var quantity: String
        var unitPrice: String
    }

    let context: BusinessOwnerContext
    let mode: Mode
    let customerName: String
    /// Closes the sheet; the text is the result to show, or nil.
    let close: (String?) -> Void

    @State private var catalog: [BipeCatalogItem] = []
    @State private var lines: [DraftLine] = []
    @State private var validUntil = Calendar.current.date(byAdding: .day, value: 7, to: Date()) ?? Date()
    @State private var visitServiceId: String?
    @State private var loaded = false
    @State private var saving = false
    @State private var error: String?

    private var isRevision: Bool {
        if case .revise = mode { return true }
        return false
    }

    private var services: [BipeCatalogItem] {
        catalog.filter { $0.kind == .service && $0.active }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach($lines) { $line in
                        lineEditor($line)
                    }
                    .onDelete { lines.remove(atOffsets: $0) }
                    Button(BipeStrings.quoteAddLine) {
                        lines.append(DraftLine(itemId: nil, name: "", quantity: "1", unitPrice: ""))
                    }
                    if !catalog.isEmpty {
                        Menu(BipeStrings.quoteFromCatalog) {
                            ForEach(catalog.filter(\.active)) { item in
                                Button(item.name) { add(item) }
                            }
                        }
                    }
                } header: {
                    Text(customerName)
                }
                Section {
                    DatePicker(BipeStrings.quoteValidity, selection: $validUntil, in: Date()..., displayedComponents: [.date])
                    Picker(BipeStrings.quoteVisit, selection: $visitServiceId) {
                        Text(BipeStrings.quoteVisitNone).tag(String?.none)
                        ForEach(services) { service in
                            Text(service.name).tag(String?.some(service.id))
                        }
                    }
                }
                Section {
                    LabeledContent(BipeStrings.quoteTotal, value: BusinessText.money(previewTotal))
                        .font(.headline)
                }
                Section {
                    Button {
                        Task { await send() }
                    } label: {
                        HStack {
                            Spacer()
                            if saving { ProgressView() } else {
                                Text(isRevision ? BipeStrings.quoteSendRevision : BipeStrings.quoteSend).bold()
                            }
                            Spacer()
                        }
                    }
                    .disabled(draft == nil || saving)
                    .listRowBackground(BipeColor.accentWash)
                } footer: {
                    if let error {
                        Text(error).foregroundStyle(BipeColor.trustDangerInk)
                    }
                }
            }
            .navigationTitle(isRevision ? BipeStrings.quoteNewVersion : BipeStrings.quoteBuilderTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { close(nil) }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.quoteBuilder.rawValue)
        .task { await load() }
    }

    private func lineEditor(_ line: Binding<DraftLine>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if line.wrappedValue.itemId == nil {
                TextField(BipeStrings.quoteLineName, text: line.name)
            } else {
                Text(line.wrappedValue.name).font(.headline).foregroundStyle(BipeColor.inkPrimary)
            }
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(BipeStrings.quoteQuantity).font(.footnote).foregroundStyle(BipeColor.inkSecondary)
                    TextField(BipeStrings.quoteQuantity, text: line.quantity).keyboardType(.decimalPad)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(BipeStrings.quoteUnitPrice).font(.footnote).foregroundStyle(BipeColor.inkSecondary)
                    TextField(BipeStrings.quoteUnitPrice, text: line.unitPrice).keyboardType(.decimalPad)
                }
            }
        }
        .padding(.vertical, 2)
    }

    /// Action addLine from the catálogo: its fixed price as the default unit price.
    private func add(_ item: BipeCatalogItem) {
        let price = item.price.mode == .onRequest ? "" : item.price.amountCents.map(Self.reais) ?? ""
        lines.append(DraftLine(itemId: item.id, name: item.name, quantity: "1", unitPrice: price))
    }

    /// `40,00`: an amount in reais to edit.
    private static func reais(_ cents: Int) -> String {
        BipeMoney.text(cents: cents).replacingOccurrences(of: "R$ ", with: "")
    }

    private static func quantity(_ typed: String) -> Double? {
        let text = typed.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        guard let value = Double(text), value > 0, value <= 100_000 else { return nil }
        return value
    }

    /// The version to send; nil while a line is not complete.
    private var draft: BipeQuoteVersionInput? {
        guard !lines.isEmpty, lines.count <= 50 else { return nil }
        var inputs: [BipeQuoteLineInput] = []
        for line in lines {
            guard let quantity = Self.quantity(line.quantity), let cents = BipeMoney.cents(from: line.unitPrice) else { return nil }
            if let itemId = line.itemId {
                inputs.append(.item(itemId, quantity: quantity, unitAmountCents: cents))
            } else {
                let name = line.name.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !name.isEmpty else { return nil }
                inputs.append(.named(name, quantity: quantity, unitAmountCents: cents))
            }
        }
        let end = BipeQuoteVersionInput.endOfLocalDay(validUntil)
        guard end > Date() else { return nil }
        return BipeQuoteVersionInput(lines: inputs, validUntil: end, visitServiceId: visitServiceId)
    }

    /// The draft total as a preview only: the server adds the total.
    private var previewTotal: Int {
        lines.reduce(0) { sum, line in
            guard let quantity = Self.quantity(line.quantity), let cents = BipeMoney.cents(from: line.unitPrice) else { return sum }
            return sum + Int((quantity * Double(cents)).rounded())
        }
    }

    /// The owner's catálogo; a revision starts from the current version.
    private func load() async {
        catalog = (try? await context.api.ownerCatalog()) ?? []
        guard !loaded else { return }
        loaded = true
        if case .revise(let quote) = mode, let current = quote.current {
            lines = current.lines.map { line in
                DraftLine(itemId: line.itemId, name: line.name, quantity: BusinessText.quantity(line.quantity),
                          unitPrice: Self.reais(line.unitAmountCents))
            }
            visitServiceId = current.visitServiceId
            if current.validUntil > Date() { validUntil = current.validUntil }
        }
    }

    /// Actions answer and revise.
    private func send() async {
        guard let draft else { return }
        saving = true
        error = nil
        defer { saving = false }
        do {
            switch mode {
            case .answer(let request):
                _ = try await context.api.answerQuoteRequest(requestId: request.id, draft)
            case .revise(let quote):
                _ = try await context.api.reviseQuote(quoteId: quote.id, draft)
            }
            close(nil)
        } catch {
            self.error = (error as? BipeAPIError)?.quoteMessage ?? BipeStrings.quoteError
        }
    }
}
