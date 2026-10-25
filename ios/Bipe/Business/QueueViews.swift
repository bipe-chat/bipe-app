// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit

// Fila virtual (ADR 0107): queue-customer, the queue card, and queue-owner. One queue per business per local day.
// Bipe never asks for the customer's location. The wait is a range and an estimate, never zero.

// MARK: - The queue card

/// The active queue place pinned in the chat: the position and the wait, or Chegou sua vez. The customer can leave;
/// on the company side a tap opens queue-owner.
struct QueueCardView: View {
    enum Actions {
        case customer(leave: () -> Void)
        case owner(open: () -> Void)
    }

    let position: BipeQueuePosition
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
            Text(BipeStrings.queueTitle)
                .font(.caption.bold())
                .foregroundStyle(BipeColor.accentInk)
            if let customerName, !customerName.isEmpty {
                Text(customerName)
                    .font(.headline)
                    .foregroundStyle(BipeColor.inkPrimary)
            }
            QueuePositionSummary(position: position)
            if case .customer(let leave) = actions, position.place.status.isActive {
                Button(BipeStrings.queueLeave, role: .destructive, action: leave)
                    .padding(.top, 2)
            }
        }
        .businessCard(.queueCustomer)
    }
}

/// The position, or Chegou sua vez, with the wait range and the estimate hint.
struct QueuePositionSummary: View {
    let position: BipeQueuePosition

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if position.place.status == .called {
                Text(BipeStrings.queueCalled)
                    .font(.title3.bold())
                    .foregroundStyle(BipeColor.trustVerifiedInk)
            } else if position.place.status == .waiting {
                Text(BipeStrings.queuePositionNumber(String(position.position)))
                    .font(.title3.bold())
                    .foregroundStyle(BipeColor.inkPrimary)
                Text(BusinessText.wait(position.wait))
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkPrimary)
                if position.wait != nil {
                    Text(BipeStrings.queueEstimateHint)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            } else {
                BusinessStatusLine(text: position.place.status.text, tint: position.place.status.tint)
            }
        }
    }
}

// MARK: - queue-customer

/// Spec queue-customer (bipe-shared product/screens/queue-customer.json): join the business's queue of the day,
/// see the position and a wait range, and leave. Opens from the chat, the profile and the counter QR code
/// (https://bipe.chat/@handle?fila).
struct QueueCustomerView: View {
    let api: BipeBusinessAPI
    /// Handle or Matrix ID.
    let business: String
    /// Closes the screen; the text is the result to show, or nil.
    let close: (String?) -> Void

    @State private var queue: BipeQueue?
    @State private var position: BipeQueuePosition?
    @State private var loading = true
    @State private var failed = false
    @State private var working = false
    @State private var error: String?
    @State private var joined = false
    @State private var confirmsLeave = false

    private var active: Bool { position?.place.status.isActive == true }

    var body: some View {
        NavigationStack {
            content
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .navigationTitle(BipeStrings.queueTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(BipeStrings.appClose) { close(nil) }
                    }
                }
                .alert(BipeStrings.queueLeaveConfirm, isPresented: $confirmsLeave) {
                    Button(BipeStrings.queueLeave, role: .destructive) { Task { await leave() } }
                    Button(BipeStrings.appCancel, role: .cancel) {}
                }
        }
        .accessibilityIdentifier(BipeScreen.queueCustomer.rawValue)
        .task { await load() }
        // Action load: every 30 seconds while the screen shows and the place is active.
        .refreshEvery30Seconds(while: active) { await load() }
    }

    @ViewBuilder
    private var content: some View {
        if loading && queue == nil {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if failed && queue == nil {
            VStack(spacing: 16) {
                Text(BipeStrings.queueLoadFailed)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
                Button(BipeStrings.appRetry) { Task { await load() } }
                    .buttonStyle(.bipeProminent)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List {
                if let position, active {
                    Section {
                        QueuePositionSummary(position: position)
                            .padding(.vertical, 4)
                    } header: {
                        Text(BipeStrings.queuePosition)
                    } footer: {
                        if joined { Text(BipeStrings.queueJoined) }
                    }
                    Section {
                        Button(BipeStrings.queueLeave, role: .destructive) { confirmsLeave = true }
                            .disabled(working)
                    }
                } else if queue?.open == true {
                    Section {
                        Text(BipeStrings.queueOpenNow).foregroundStyle(BipeColor.inkPrimary)
                    }
                    Section {
                        Button {
                            Task { await join() }
                        } label: {
                            HStack {
                                Spacer()
                                if working { ProgressView() } else { Text(BipeStrings.queueJoin).bold() }
                                Spacer()
                            }
                        }
                        .disabled(working)
                        .listRowBackground(BipeColor.accentWash)
                    }
                } else {
                    Section {
                        Text(BipeStrings.queueClosed).foregroundStyle(BipeColor.inkSecondary)
                    }
                }
                if let error {
                    Section {
                        Text(error).foregroundStyle(BipeColor.trustDangerInk)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .refreshable { await load() }
        }
    }

    /// Action load: the queue and the caller's place.
    private func load() async {
        loading = true
        defer { loading = false }
        do {
            queue = try await api.queue(business: business)
            position = try await api.myQueuePlace(business: business)
            failed = false
        } catch {
            failed = true
        }
    }

    /// Action join. already_in_queue shows bipe_queue_error_already_joined; queue_closed shows bipe_queue_closed.
    private func join() async {
        working = true
        error = nil
        defer { working = false }
        do {
            position = try await api.joinQueue(business: business)
            joined = true
        } catch {
            self.error = (error as? BipeAPIError)?.queueMessage ?? BipeStrings.queueError
            await load()
        }
    }

    /// Action leave, after bipe_queue_leave_confirm.
    private func leave() async {
        guard let position else { return }
        working = true
        error = nil
        defer { working = false }
        do {
            self.position = try await api.leaveQueue(business: business, placeId: position.place.id)
            joined = false
        } catch {
            self.error = (error as? BipeAPIError)?.queueMessage ?? BipeStrings.queueError
        }
        await load()
    }
}

// MARK: - queue-owner

/// Spec queue-owner (bipe-shared product/screens/queue-owner.json): the company opens and closes today's queue,
/// calls the next person, marks served or did not arrive, calls again, adds a walk-in, sets the arrival window and
/// shows the counter QR code. Opens from business-tools.
struct QueueOwnerView: View {
    let context: BusinessOwnerContext

    @State private var queue: BipeQueue?
    @State private var places: [BipeQueuePlace] = []
    @State private var names: [String: String] = [:]
    @State private var loading = true
    @State private var failed = false
    @State private var working = false
    @State private var notice: String?
    @State private var showsWalkIn = false
    @State private var showsSettings = false
    @State private var showsQr = false
    @Environment(\.scenePhase) private var scenePhase

    /// Waiting and called first, in order of arrival, then the rest of today.
    private var sortedPlaces: [BipeQueuePlace] {
        places.filter { $0.status.isActive } + places.filter { !$0.status.isActive }
    }

    private var waitingCount: Int { places.filter { $0.status == .waiting }.count }

    var body: some View {
        content
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.queueTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { showsWalkIn = true } label: {
                        Label {
                            Text(BipeStrings.queueAddWalkIn)
                        } icon: {
                            PhosphorIcon.userPlus.image.resizable().frame(width: 22, height: 22)
                        }
                    }
                    .labelStyle(.iconOnly)
                }
                ToolbarItem(placement: .secondaryAction) {
                    Button(BipeStrings.queueQrTitle) { showsQr = true }
                }
                ToolbarItem(placement: .secondaryAction) {
                    Button(BipeStrings.queueSettings) { showsSettings = true }
                        .disabled(queue == nil)
                }
            }
            .accessibilityIdentifier(BipeScreen.queueOwner.rawValue)
            .task { await load() }
            // Action refresh: every 30 seconds while the screen shows.
            .refreshEvery30Seconds(while: true) { await load() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await load() } }
            }
            .toast($notice)
            .sheet(isPresented: $showsWalkIn) {
                QueueWalkInView(context: context) { added in
                    showsWalkIn = false
                    if added { Task { await load() } }
                }
            }
            .sheet(isPresented: $showsSettings) {
                QueueSettingsView(api: context.api, minutes: queue?.policy.noShowAfterMinutes ?? BipeQueuePolicy.defaultNoShowAfterMinutes) { saved in
                    showsSettings = false
                    if let saved { queue = saved }
                }
            }
            .sheet(isPresented: $showsQr) {
                QueueQrView(people: context.people) { showsQr = false }
            }
    }

    @ViewBuilder
    private var content: some View {
        if loading && queue == nil {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if failed && queue == nil {
            VStack(spacing: 16) {
                Text(BipeStrings.queueLoadFailed)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
                Button(BipeStrings.appRetry) { Task { await load() } }
                    .buttonStyle(.bipeProminent)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List {
                Section {
                    BusinessStatusLine(text: queue?.open == true ? BipeStrings.queueOpenNow : BipeStrings.queueClosed,
                                       tint: queue?.open == true ? BipeColor.trustVerifiedInk : BipeColor.inkSecondary)
                    Text(BipeStrings.queueWaitingCount(String(waitingCount)))
                        .foregroundStyle(BipeColor.inkPrimary)
                    Button(queue?.open == true ? BipeStrings.queueClose : BipeStrings.queueOpen) {
                        Task { await setOpen(!(queue?.open ?? false)) }
                    }
                    if waitingCount > 0 {
                        Button {
                            Task { await callNext() }
                        } label: {
                            Text(BipeStrings.queueCallNext).bold()
                        }
                    }
                }
                .disabled(working)
                Section {
                    if places.isEmpty {
                        Text(BipeStrings.queueOwnerEmpty)
                            .foregroundStyle(BipeColor.inkSecondary)
                    } else {
                        ForEach(sortedPlaces) { place in
                            row(place)
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .refreshable { await load() }
        }
    }

    private func row(_ place: BipeQueuePlace) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(BusinessText.customer(place.customer, names: names))
                    .font(.headline)
                    .foregroundStyle(BipeColor.inkPrimary)
                Spacer(minLength: 8)
                Text(BookingText.time(place.joinedAt))
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            BusinessStatusLine(text: place.status.text, tint: place.status.tint)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { actions(place) }
                VStack(alignment: .leading, spacing: 6) { actions(place) }
            }
            .buttonStyle(.bipeBordered)
            .disabled(working)
        }
        .padding(.vertical, 4)
    }

    /// served from waiting, called or no-show; no-show from waiting or called; recall from called or no-show.
    @ViewBuilder
    private func actions(_ place: BipeQueuePlace) -> some View {
        if place.status == .waiting || place.status == .called {
            Button(BipeStrings.queueMarkServed) { Task { await act(place, .served) } }
            Button(BipeStrings.queueMarkNoShow) { Task { await act(place, .noShow) } }
        }
        if place.status == .called || place.status == .noShow {
            Button(BipeStrings.queueRecall) { Task { await act(place, .recall) } }
        }
        if let userId = place.customer.userId, let openChat = context.openChat {
            Button(BipeStrings.appPersonMessage) { openChat(userId) }
        }
    }

    /// Action refresh: today's queue and places.
    private func load() async {
        loading = true
        defer { loading = false }
        do {
            queue = try await context.api.ownerQueue()
            places = try await context.api.ownerQueuePlaces()
            failed = false
            names = await context.names(for: places.compactMap(\.customer.userId), known: names)
        } catch {
            failed = true
        }
    }

    /// Actions open and close. Places already in the queue stay when it closes.
    private func setOpen(_ open: Bool) async {
        working = true
        defer { working = false }
        do {
            queue = try await context.api.setQueueOpen(open)
        } catch {
            notice = (error as? BipeAPIError)?.queueMessage ?? BipeStrings.queueError
        }
    }

    private func callNext() async {
        working = true
        defer { working = false }
        do {
            _ = try await context.api.callNext()
        } catch {
            notice = (error as? BipeAPIError)?.queueMessage ?? BipeStrings.queueError
        }
        await load()
    }

    /// Actions served, noShow and recall.
    private func act(_ place: BipeQueuePlace, _ action: BipeQueuePlaceAction) async {
        working = true
        defer { working = false }
        do {
            _ = try await context.api.queuePlaceAction(placeId: place.id, action)
        } catch {
            notice = (error as? BipeAPIError)?.queueMessage ?? BipeStrings.queueError
        }
        await load()
    }
}

/// Action addWalkIn: a typed name or a person from the business's chats, then POST .../queue/places.
private struct QueueWalkInView: View {
    private enum CustomerSource: Hashable {
        case chats
        case typed
    }

    let context: BusinessOwnerContext
    /// true when a place was added.
    let close: (Bool) -> Void

    @State private var source: CustomerSource = .typed
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
            Form {
                Section {
                    Picker(BipeStrings.agendaCustomer, selection: $source) {
                        Text(BipeStrings.agendaCustomerName).tag(CustomerSource.typed)
                        Text(BipeStrings.agendaCustomerFromChats).tag(CustomerSource.chats)
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
                } footer: {
                    if let error {
                        Text(error).foregroundStyle(BipeColor.trustDangerInk)
                    }
                }
            }
            .navigationTitle(BipeStrings.queueAddWalkIn)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { close(false) }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button(BipeStrings.appSave) { Task { await save() } }
                            .disabled(customer == nil)
                    }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.queueOwner.rawValue)
        .presentationDetents([.medium, .large])
        .onAppear {
            if customerUserId == nil, context.customers.count == 1 { customerUserId = context.customers.first?.userId }
        }
    }

    private func save() async {
        guard let customer else { return }
        saving = true
        error = nil
        defer { saving = false }
        do {
            _ = try await context.api.addQueuePlace(customer: customer)
            close(true)
        } catch {
            self.error = (error as? BipeAPIError)?.queueMessage ?? BipeStrings.queueError
        }
    }
}

/// Action setWindow: the minutes to arrive after a call, 1 to 240 (default 10).
private struct QueueSettingsView: View {
    let api: BipeBusinessAPI
    let close: (BipeQueue?) -> Void

    @State private var minutes: String
    @State private var saving = false
    @State private var error: String?

    init(api: BipeBusinessAPI, minutes: Int, close: @escaping (BipeQueue?) -> Void) {
        self.api = api
        self.close = close
        _minutes = State(initialValue: String(minutes))
    }

    private var edited: Int? {
        guard let value = Int(minutes.trimmingCharacters(in: .whitespaces)), (1...240).contains(value) else { return nil }
        return value
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledField(title: BipeStrings.queueNoShowWindow, text: $minutes)
                } footer: {
                    if let error {
                        Text(error).foregroundStyle(BipeColor.trustDangerInk)
                    }
                }
            }
            .navigationTitle(BipeStrings.queueSettings)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appCancel) { close(nil) }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else {
                        Button(BipeStrings.appSave) { Task { await save() } }
                            .disabled(edited == nil)
                    }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.queueOwner.rawValue)
        .presentationDetents([.medium])
    }

    private func save() async {
        guard let edited else { return }
        saving = true
        error = nil
        defer { saving = false }
        do {
            close(try await api.setQueuePolicy(noShowAfterMinutes: edited))
        } catch {
            self.error = (error as? BipeAPIError)?.queueMessage ?? BipeStrings.queueError
        }
    }
}

/// Action showQr: a QR code of https://bipe.chat/@handle?fila with bipe_queue_qr_body, to show on screen or share as
/// an image to print. The code is drawn on the device (CoreImage CIQRCodeGenerator, QRCodeImage).
private struct QueueQrView: View {
    let people: PeopleService
    let close: () -> Void

    @State private var handle: String?
    @State private var loaded = false
    @State private var sharedImage: SharedImage?

    private var link: URL? { handle.flatMap(BipeProfileLink.queueURL(handle:)) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    if let link {
                        QueueQrCard(link: link)
                        Button {
                            if let image = QueueQrCard.image(link: link) { sharedImage = SharedImage(image: image) }
                        } label: {
                            IconLabel(icon: .shareNetwork, text: BipeStrings.appShare)
                        }
                        .buttonStyle(.bipeBordered)
                    } else if loaded {
                        Text(BipeStrings.publicProfileNeedsHandle)
                            .foregroundStyle(BipeColor.inkSecondary)
                            .multilineTextAlignment(.center)
                    } else {
                        ProgressView()
                    }
                }
                .padding(24)
                .frame(maxWidth: .infinity)
            }
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.queueQrTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appClose, action: close)
                }
            }
            .sheet(item: $sharedImage) { shared in
                ShareSheet(items: [shared.image])
            }
        }
        .accessibilityIdentifier(BipeScreen.queueOwner.rawValue)
        .task {
            let mine = try? await people.withToken { (token: String) async throws(BipeAPIError) -> BipeMyHandle in
                try await people.api.myHandle(accessToken: token)
            }
            handle = mine?.handle
            loaded = true
        }
    }
}

/// An image for the share sheet.
private struct SharedImage: Identifiable {
    let id = UUID()
    let image: UIImage
}

/// The counter card: the QR code and bipe_queue_qr_body. Also drawn into an image to print.
private struct QueueQrCard: View {
    let link: URL

    var body: some View {
        VStack(spacing: 12) {
            QRCodeImage(text: link.absoluteString)
                .frame(width: 240, height: 240)
            Text(BipeStrings.queueQrBody)
                .font(.body)
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            Text(link.absoluteString)
                .font(.footnote.monospaced())
                .foregroundStyle(BipeColor.inkSecondary)
        }
        .padding(16)
    }

    /// The card as an image, on a light ground in both themes: printers and scanners need dark on light.
    @MainActor static func image(link: URL) -> UIImage? {
        let renderer = ImageRenderer(content: QueueQrCard(link: link)
            .frame(width: 320)
            .background(Color.white)
            .environment(\.colorScheme, .light))
        renderer.scale = 3
        return renderer.uiImage
    }
}
