// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import PhotosUI
import SwiftUI
import UIKit

/// Items grouped by category in the business's order; items without one come last, under
/// bipe_catalog_uncategorised.
struct CatalogGroup: Identifiable {
    var title: String
    var items: [BipeCatalogItem]
    var id: String { title }

    static func grouped(_ items: [BipeCatalogItem]) -> [CatalogGroup] {
        var groups: [CatalogGroup] = []
        var other: [BipeCatalogItem] = []
        for item in items {
            guard let category = item.category?.trimmingCharacters(in: .whitespacesAndNewlines), !category.isEmpty else {
                other.append(item)
                continue
            }
            if let index = groups.firstIndex(where: { $0.title == category }) {
                groups[index].items.append(item)
            } else {
                groups.append(CatalogGroup(title: category, items: [item]))
            }
        }
        if !other.isEmpty { groups.append(CatalogGroup(title: BipeStrings.catalogUncategorised, items: other)) }
        return groups
    }
}

// MARK: - catalog-view

/// Spec catalog-view: a business's catálogo for the customer (ADR 0104). Not a marketplace: one business only.
struct CatalogView: View {
    let api: BipeBusinessAPI
    let business: String
    /// The business takes bookings: a service shows Agendar.
    let canBook: Bool
    let book: (String) -> Void
    /// order-customer add: Adicionar ao pedido on an active fixed-price product. nil when the business takes no orders.
    var addToOrder: ((String) -> Void)? = nil
    let close: () -> Void

    @State private var items: [BipeCatalogItem] = []
    @State private var loading = true
    @State private var failed = false
    @State private var selected: BipeCatalogItem?

    var body: some View {
        NavigationStack {
            content
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .navigationTitle(BipeStrings.catalogTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(BipeStrings.appClose, action: close)
                    }
                }
                .sheet(item: $selected) { item in
                    CatalogItemDetail(item: item, canBook: canBook && item.kind == .service, book: {
                        selected = nil
                        book(item.id)
                    }, addToOrder: addToOrder != nil && item.isOrderable && !item.soldOutToday ? {
                        selected = nil
                        addToOrder?(item.id)
                    } : nil)
                    .presentationDetents([.medium, .large])
                }
        }
        .accessibilityIdentifier(BipeScreen.catalogView.rawValue)
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
        } else if items.isEmpty {
            Text(BipeStrings.catalogEmpty)
                .foregroundStyle(BipeColor.inkSecondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List {
                ForEach(CatalogGroup.grouped(items)) { group in
                    Section {
                        ForEach(group.items) { item in
                            Button { selected = item } label: { CatalogItemRow(item: item) }
                                .accessibilityLabel(BipeStrings.catalogItemOpenA11y(item.name))
                                .listRowBackground(BipeColor.surfaceRaised)
                        }
                    } header: {
                        Text(group.title)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
        }
    }

    /// Action load.
    private func load() async {
        loading = true
        defer { loading = false }
        do {
            items = try await api.catalog(business: business)
            failed = false
        } catch {
            failed = true
        }
    }
}

/// One item in a list: photo, name, price, and the sold-out or hidden badge.
struct CatalogItemRow: View {
    let item: BipeCatalogItem
    var showsHidden = false

    var body: some View {
        HStack(spacing: 12) {
            CatalogPhoto(url: item.photo)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).foregroundStyle(BipeColor.inkPrimary)
                Text(item.price.displayText)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
                HStack(spacing: 6) {
                    if item.soldOutToday { CatalogBadge(text: BipeStrings.catalogSoldOutToday, tint: BipeColor.trustCautionInk) }
                    if showsHidden && !item.active { CatalogBadge(text: BipeStrings.catalogHidden, tint: BipeColor.inkSecondary) }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
    }
}

private struct CatalogBadge: View {
    let text: String
    let tint: Color

    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .foregroundStyle(tint)
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(Capsule().stroke(tint))
    }
}

/// The item detail. The description is plain text: Text with a String never detects links (security rule 10).
private struct CatalogItemDetail: View {
    let item: BipeCatalogItem
    let canBook: Bool
    let book: () -> Void
    /// Adicionar ao pedido (order-customer add); nil hides it.
    var addToOrder: (() -> Void)? = nil

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let photo = item.photo {
                    AsyncImage(url: photo) { image in
                        image.resizable().scaledToFit()
                    } placeholder: {
                        BipeColor.surfaceSunken.frame(height: 200)
                    }
                    .frame(maxWidth: .infinity)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .accessibilityLabel(BipeStrings.catalogPhotoA11y(item.name))
                }
                Text(item.name).font(.title2.bold()).foregroundStyle(BipeColor.inkPrimary)
                Text(item.kind == .service ? BipeStrings.catalogKindService : BipeStrings.catalogKindProduct)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
                Text(item.price.displayText).font(.headline).foregroundStyle(BipeColor.inkPrimary)
                if let minutes = item.durationMinutes, item.kind == .service {
                    Text(BipeStrings.agendaDuration(String(minutes))).foregroundStyle(BipeColor.inkSecondary)
                }
                if item.soldOutToday {
                    Text(BipeStrings.catalogSoldOutToday).font(.subheadline.bold()).foregroundStyle(BipeColor.trustCautionInk)
                }
                if let description = item.description, !description.isEmpty {
                    Text(verbatim: description).foregroundStyle(BipeColor.inkPrimary)
                }
                if canBook {
                    Button(action: book) {
                        Text(BipeStrings.agendaBook).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .padding(.top, 8)
                }
                if let addToOrder {
                    Button(action: addToOrder) {
                        Text(BipeStrings.orderAdd).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .padding(.top, 8)
                }
            }
            .padding(20)
        }
        .background(BipeColor.surfaceBase.ignoresSafeArea())
    }
}

// MARK: - catalog-owner

/// Spec catalog-owner: the company lists, adds, reorders and edits its items, and marks one sold out today
/// in one tap (ADR 0104). Opens from business-tools on Você.
struct CatalogOwnerView: View {
    let api: BipeBusinessAPI

    @State private var items: [BipeCatalogItem] = []
    @State private var loading = true
    @State private var failed = false
    @State private var reordering = false
    @State private var editing: CatalogEditTarget?
    @State private var removing: BipeCatalogItem?
    @State private var notice: String?
    /// The Agenda, when on: a service then edits its duration, buffers and people.
    @State private var agenda: BipeAgendaState?

    var body: some View {
        content
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.catalogTitle)
            .environment(\.editMode, .constant(reordering ? .active : .inactive))
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { editing = CatalogEditTarget(item: nil) } label: {
                        Label {
                            Text(BipeStrings.catalogAddItem)
                        } icon: {
                            PhosphorIcon.plus.image.resizable().frame(width: 22, height: 22)
                        }
                    }
                    .labelStyle(.iconOnly)
                }
                if items.count > 1 {
                    ToolbarItem(placement: .secondaryAction) {
                        Button(reordering ? BipeStrings.appSave : BipeStrings.catalogReorder) {
                            if reordering { Task { await saveOrder() } } else { reordering = true }
                        }
                    }
                }
            }
            .sheet(item: $editing) { target in
                CatalogItemEditView(api: api, item: target.item, nextSort: (items.map(\.sort).max() ?? -1) + 1, agenda: agenda,
                                    close: { message in
                                        editing = nil
                                        if let message { notice = message }
                                        Task { await load() }
                                    })
            }
            .alert(BipeStrings.catalogRemove, isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
                Button(BipeStrings.catalogRemove, role: .destructive) {
                    if let item = removing { Task { await remove(item) } }
                }
                Button(BipeStrings.appCancel, role: .cancel) { removing = nil }
            } message: {
                Text(BipeStrings.catalogRemoveBody)
            }
            .toast($notice)
            .accessibilityIdentifier(BipeScreen.catalogOwner.rawValue)
            .task { await load() }
    }

    @ViewBuilder
    private var content: some View {
        if loading && items.isEmpty {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if failed && items.isEmpty {
            VStack(spacing: 16) {
                Text(BipeStrings.catalogLoadFailed)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
                Button(BipeStrings.appRetry) { Task { await load() } }
                    .buttonStyle(.borderedProminent)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if items.isEmpty {
            VStack(spacing: 16) {
                Text(BipeStrings.catalogOwnerEmpty)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
                Button(BipeStrings.catalogAddItem) { editing = CatalogEditTarget(item: nil) }
                    .buttonStyle(.borderedProminent)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List {
                ForEach(items) { item in
                    VStack(alignment: .leading, spacing: 6) {
                        Button { editing = CatalogEditTarget(item: item) } label: {
                            CatalogItemRow(item: item, showsHidden: true)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(BipeStrings.catalogItemOpenA11y(item.name))
                        if !reordering {
                            Button(item.soldOutToday ? BipeStrings.catalogMarkAvailable : BipeStrings.catalogMarkSoldOut) {
                                Task { await toggleSoldOut(item) }
                            }
                            .buttonStyle(.borderless)
                            .font(.footnote)
                        }
                    }
                    .listRowBackground(BipeColor.surfaceRaised)
                }
                .onMove { from, to in items.move(fromOffsets: from, toOffset: to) }
                .onDelete { offsets in removing = offsets.first.map { items[$0] } }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .refreshable { await load() }
        }
    }

    /// Action load: GET /v1/me/business/catalog, hidden items included, and the Agenda when it is on.
    private func load() async {
        loading = true
        defer { loading = false }
        do {
            items = try await api.ownerCatalog()
            failed = false
        } catch {
            failed = true
        }
        agenda = try? await api.agendaState()
    }

    /// Action toggleSoldOut: PUT the item with sold_out_today flipped.
    private func toggleSoldOut(_ item: BipeCatalogItem) async {
        var input = item.input
        input.soldOutToday.toggle()
        do {
            let saved = try await api.replaceCatalogItem(id: item.id, input)
            if let index = items.firstIndex(where: { $0.id == item.id }) { items[index] = saved }
        } catch {
            notice = BipeStrings.catalogSaveFailed
        }
    }

    /// Action move, done: PUT /v1/me/business/catalog/order with every ID in the new order.
    private func saveOrder() async {
        do {
            items = try await api.reorderCatalog(itemIds: items.map(\.id))
            reordering = false
        } catch {
            notice = BipeStrings.catalogSaveFailed
        }
    }

    /// Action remove, after bipe_catalog_remove: the item is archived.
    private func remove(_ item: BipeCatalogItem) async {
        removing = nil
        do {
            try await api.deleteCatalogItem(id: item.id)
            items.removeAll { $0.id == item.id }
            notice = BipeStrings.catalogRemoved
        } catch {
            notice = BipeStrings.catalogSaveFailed
        }
    }
}

/// The item that catalog-item-edit opens; nil item adds a new one.
struct CatalogEditTarget: Identifiable {
    let item: BipeCatalogItem?
    let id = UUID()
}

// MARK: - catalog-item-edit

/// Spec catalog-item-edit: one form for a product or a service. A service also has its duration, buffers and the
/// people who do it when the Agenda is on.
struct CatalogItemEditView: View {
    let api: BipeBusinessAPI
    let item: BipeCatalogItem?
    let nextSort: Int
    let agenda: BipeAgendaState?
    /// Closes the form; the text is the result to show, or nil.
    let close: (String?) -> Void

    @State private var kind: BipeCatalogKind = .service
    @State private var name = ""
    @State private var priceMode: BipeCatalogPrice.Mode = .fixed
    @State private var amount = ""
    @State private var description = ""
    @State private var category = ""
    @State private var active = true
    @State private var duration = ""
    @State private var bufferBefore = ""
    @State private var bufferAfter = ""
    @State private var agendaIds: Set<String> = []
    @State private var pickerItem: PhotosPickerItem?
    @State private var newPhoto: Data?
    @State private var removesPhoto = false
    @State private var saving = false
    @State private var error: String?
    @State private var confirmsRemove = false
    @State private var loaded = false

    static let nameLimit = 120
    static let descriptionLimit = 500
    static let categoryLimit = 80

    private var schedules: Bool { kind == .service && agenda != nil }

    private var canSave: Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !saving else { return false }
        if priceMode != .onRequest && BipeMoney.cents(from: amount) == nil { return false }
        if schedules && ((Int(duration) ?? 0) < 1 || agendaIds.isEmpty) { return false }
        return true
    }

    var body: some View {
        NavigationStack {
            Form {
                photoSection
                Section {
                    TextField(BipeStrings.catalogItemName, text: $name)
                        .onChange(of: name) { _, value in if value.count > Self.nameLimit { name = String(value.prefix(Self.nameLimit)) } }
                    Picker(BipeStrings.catalogItemKind, selection: $kind) {
                        Text(BipeStrings.catalogKindService).tag(BipeCatalogKind.service)
                        Text(BipeStrings.catalogKindProduct).tag(BipeCatalogKind.product)
                    }
                }
                .listRowBackground(BipeColor.surfaceRaised)
                Section {
                    Picker(BipeStrings.catalogPrice, selection: $priceMode) {
                        Text(BipeStrings.catalogPriceFixed).tag(BipeCatalogPrice.Mode.fixed)
                        Text(BipeStrings.catalogPriceFromOption).tag(BipeCatalogPrice.Mode.from)
                        Text(BipeStrings.catalogOnRequestOption).tag(BipeCatalogPrice.Mode.onRequest)
                    }
                    if priceMode != .onRequest {
                        TextField(BipeStrings.catalogAmount, text: $amount)
                            .keyboardType(.decimalPad)
                        if let cents = BipeMoney.cents(from: amount) {
                            Text(BipeCatalogPrice.amount(priceMode, cents: cents).displayText)
                                .foregroundStyle(BipeColor.inkSecondary)
                        }
                    }
                } header: {
                    Text(BipeStrings.catalogPrice)
                }
                .listRowBackground(BipeColor.surfaceRaised)
                Section {
                    TextField(BipeStrings.catalogDescription, text: $description, axis: .vertical)
                        .lineLimit(3...8)
                        .onChange(of: description) { _, value in
                            if value.count > Self.descriptionLimit { description = String(value.prefix(Self.descriptionLimit)) }
                        }
                    TextField(BipeStrings.catalogCategory, text: $category)
                        .onChange(of: category) { _, value in
                            if value.count > Self.categoryLimit { category = String(value.prefix(Self.categoryLimit)) }
                        }
                    Toggle(BipeStrings.catalogActive, isOn: $active)
                }
                .listRowBackground(BipeColor.surfaceRaised)
                if schedules, let agenda {
                    Section {
                        LabeledField(title: BipeStrings.agendaServiceDuration, text: $duration)
                        LabeledField(title: BipeStrings.agendaBufferBefore, text: $bufferBefore)
                        LabeledField(title: BipeStrings.agendaBufferAfter, text: $bufferAfter)
                    }
                    .listRowBackground(BipeColor.surfaceRaised)
                    Section {
                        ForEach(agenda.agendas) { person in
                            Toggle(person.name, isOn: Binding(get: { agendaIds.contains(person.id) },
                                                              set: { on in if on { agendaIds.insert(person.id) } else { agendaIds.remove(person.id) } }))
                        }
                    } header: {
                        Text(BipeStrings.agendaSetupPeople)
                    }
                    .listRowBackground(BipeColor.surfaceRaised)
                }
                if let error {
                    Section {
                        Text(error).foregroundStyle(BipeColor.trustDangerInk)
                    }
                }
                if item != nil {
                    Section {
                        Button(BipeStrings.catalogRemove, role: .destructive) { confirmsRemove = true }
                    }
                    .listRowBackground(BipeColor.surfaceRaised)
                }
            }
            .scrollContentBackground(.hidden)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(item == nil ? BipeStrings.catalogAddItem : BipeStrings.catalogEditItem)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { close(nil) }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button(BipeStrings.catalogSave) { Task { await save() } }
                            .disabled(!canSave)
                    }
                }
            }
            .alert(BipeStrings.catalogRemove, isPresented: $confirmsRemove) {
                Button(BipeStrings.catalogRemove, role: .destructive) { Task { await remove() } }
                Button(BipeStrings.appCancel, role: .cancel) {}
            } message: {
                Text(BipeStrings.catalogRemoveBody)
            }
            .onChange(of: pickerItem) { _, picked in Task { await loadPhoto(picked) } }
            .onAppear(perform: fill)
        }
        .accessibilityIdentifier(BipeScreen.catalogItemEdit.rawValue)
    }

    private var photoSection: some View {
        Section {
            HStack(spacing: 16) {
                Group {
                    if let newPhoto, let image = UIImage(data: newPhoto) {
                        Image(uiImage: image).resizable().scaledToFill()
                            .frame(width: 72, height: 72)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    } else {
                        CatalogPhoto(url: removesPhoto ? nil : item?.photo, side: 72)
                    }
                }
                .accessibilityLabel(BipeStrings.catalogPhoto)
                VStack(alignment: .leading, spacing: 8) {
                    PhotosPicker(selection: $pickerItem, matching: .images) {
                        Text(BipeStrings.catalogAddPhoto)
                    }
                    if newPhoto != nil || (item?.photo != nil && !removesPhoto) {
                        Button(BipeStrings.catalogRemovePhoto, role: .destructive) {
                            newPhoto = nil
                            removesPhoto = item?.photo != nil
                        }
                    }
                }
                .buttonStyle(.borderless)
            }
        } header: {
            Text(BipeStrings.catalogPhoto)
        }
        .listRowBackground(BipeColor.surfaceRaised)
    }

    /// Fills the form from the item once.
    private func fill() {
        guard !loaded else { return }
        loaded = true
        guard let item else {
            agendaIds = Set(agenda?.agendas.first.map { [$0.id] } ?? [])
            return
        }
        kind = item.kind
        name = item.name
        priceMode = item.price.mode
        amount = item.price.amountCents.map { String(format: "%ld,%02ld", $0 / 100, $0 % 100) } ?? ""
        description = item.description ?? ""
        category = item.category ?? ""
        active = item.active
        duration = item.durationMinutes.map(String.init) ?? ""
        bufferBefore = item.bufferBeforeMinutes.map(String.init) ?? ""
        bufferAfter = item.bufferAfterMinutes.map(String.init) ?? ""
        agendaIds = Set(item.agendaIds ?? [])
    }

    /// Action pickPhoto: scaled down to a JPEG under the profile photo limit.
    private func loadPhoto(_ picked: PhotosPickerItem?) async {
        guard let picked, let data = try? await picked.loadTransferable(type: Data.self) else { return }
        guard let jpeg = BipeProfilePhoto.jpegData(from: data) else {
            error = BipeStrings.catalogPhotoFailed
            return
        }
        error = nil
        newPhoto = jpeg
        removesPhoto = false
    }

    private var input: BipeCatalogItemInput {
        let price = priceMode == .onRequest
            ? BipeCatalogPrice(mode: .onRequest)
            : BipeCatalogPrice.amount(priceMode, cents: BipeMoney.cents(from: amount) ?? 0)
        // Products never send scheduling fields; a service keeps its fields when the Agenda is off.
        let scheduling = kind == .service
        return BipeCatalogItemInput(kind: kind, name: name.trimmingCharacters(in: .whitespacesAndNewlines), price: price,
                                    description: description, category: category.trimmingCharacters(in: .whitespacesAndNewlines),
                                    active: active, sort: item?.sort ?? nextSort, soldOutToday: item?.soldOutToday ?? false,
                                    durationMinutes: scheduling ? (schedules ? Int(duration) : item?.durationMinutes) : nil,
                                    bufferBeforeMinutes: scheduling ? (schedules ? (Int(bufferBefore) ?? 0) : item?.bufferBeforeMinutes) : nil,
                                    bufferAfterMinutes: scheduling ? (schedules ? (Int(bufferAfter) ?? 0) : item?.bufferAfterMinutes) : nil,
                                    agendaIds: scheduling ? (schedules ? Array(agendaIds) : item?.agendaIds) : nil)
    }

    /// Action save: POST or PUT, then the photo once the item exists.
    private func save() async {
        saving = true
        defer { saving = false }
        let saved: BipeCatalogItem
        do {
            if let item {
                saved = try await api.replaceCatalogItem(id: item.id, input)
            } else {
                saved = try await api.createCatalogItem(input)
            }
        } catch {
            self.error = BipeStrings.catalogSaveFailed
            return
        }
        do {
            if let newPhoto {
                _ = try await api.setCatalogItemPhoto(id: saved.id, jpeg: newPhoto)
            } else if removesPhoto {
                try await api.deleteCatalogItemPhoto(id: saved.id)
            }
        } catch {
            close(BipeStrings.catalogPhotoFailed)
            return
        }
        close(BipeStrings.catalogSaved)
    }

    /// Action remove: as catalog-owner remove.
    private func remove() async {
        guard let item else { return }
        do {
            try await api.deleteCatalogItem(id: item.id)
            close(BipeStrings.catalogRemoved)
        } catch {
            self.error = BipeStrings.catalogSaveFailed
        }
    }
}

/// A number field with its title above, for minutes.
struct LabeledField: View {
    let title: String
    @Binding var text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.footnote).foregroundStyle(BipeColor.inkSecondary)
            TextField(title, text: $text).keyboardType(.numberPad)
        }
    }
}
