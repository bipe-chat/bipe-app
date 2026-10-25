// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec agenda-settings (bipe-shared product/screens/agenda-settings.json): the booking policy and the publish state
/// (ADR 0103). Reminder times wait for push reminders (bipe-chat/bipe#320).
struct AgendaSettingsView: View {
    let api: BipeBusinessAPI
    /// Opens agenda-setup at the hours step.
    let editSetup: () -> Void
    /// The Agenda changed: the tab reloads.
    let changed: () -> Void

    @State private var published: Bool
    @State private var timeZone: String
    @State private var policy: BipeAgendaPolicy
    @State private var minNotice: String
    @State private var horizon: String
    @State private var slotStep: String
    @State private var cancelWindow: String
    @State private var maxOpen: String
    @State private var requestMode: Bool
    @State private var saving = false
    @State private var error: String?

    init(api: BipeBusinessAPI, state: BipeAgendaState, editSetup: @escaping () -> Void, changed: @escaping () -> Void) {
        self.api = api
        self.editSetup = editSetup
        self.changed = changed
        _published = State(initialValue: state.published)
        _timeZone = State(initialValue: state.timeZone)
        _policy = State(initialValue: state.policy)
        _minNotice = State(initialValue: String(state.policy.minNoticeMinutes))
        _horizon = State(initialValue: String(state.policy.horizonDays))
        _slotStep = State(initialValue: String(state.policy.slotStepMinutes))
        _cancelWindow = State(initialValue: String(state.policy.cancelWindowMinutes))
        _maxOpen = State(initialValue: String(state.policy.maxOpenBookingsPerCustomer))
        _requestMode = State(initialValue: state.policy.confirmMode == .request)
    }

    /// Every field, within the contract's limits; reminder_minutes stays as read.
    private var edited: BipeAgendaPolicy? {
        guard let minNotice = Int(minNotice), (0...43_200).contains(minNotice),
              let horizon = Int(horizon), (1...365).contains(horizon),
              let slotStep = Int(slotStep), (5...240).contains(slotStep),
              let cancelWindow = Int(cancelWindow), (0...43_200).contains(cancelWindow),
              let maxOpen = Int(maxOpen), (1...20).contains(maxOpen) else { return nil }
        return BipeAgendaPolicy(minNoticeMinutes: minNotice, horizonDays: horizon, slotStepMinutes: slotStep,
                                cancelWindowMinutes: cancelWindow, confirmMode: requestMode ? .request : .auto,
                                maxOpenBookingsPerCustomer: maxOpen, reminderMinutes: policy.reminderMinutes)
    }

    var body: some View {
        Form {
            Section {
                Toggle(BipeStrings.agendaPublish,
                       isOn: Binding(get: { published }, set: { value in Task { await setPublished(value) } }))
                    .disabled(saving)
                LabeledContent(BipeStrings.agendaTimeZone, value: timeZone)
            } footer: {
                Text(published ? BipeStrings.agendaPublished : BipeStrings.agendaNotPublished)
            }
            Section {
                LabeledField(title: BipeStrings.agendaMinNotice, text: $minNotice)
                LabeledField(title: BipeStrings.agendaHorizon, text: $horizon)
                LabeledField(title: BipeStrings.agendaSlotStep, text: $slotStep)
                LabeledField(title: BipeStrings.agendaCancelWindow, text: $cancelWindow)
                LabeledField(title: BipeStrings.agendaMaxOpen, text: $maxOpen)
            }
            Section {
                Toggle(BipeStrings.agendaRequestMode, isOn: $requestMode)
            } footer: {
                Text(BipeStrings.agendaRequestModeFooter)
            }
            Section {
                Button(BipeStrings.agendaSetupHours, action: editSetup)
                Button(BipeStrings.agendaSetupPeople, action: editSetup)
            }
            if let error {
                Section {
                    Text(error).foregroundStyle(BipeColor.trustDangerInk)
                }
            }
        }
        .navigationTitle(BipeStrings.agendaSettingsTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if saving { ProgressView() } else {
                    Button(BipeStrings.appSave) { Task { await save() } }
                        .disabled(edited == nil || edited == policy)
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.agendaSettings.rawValue)
    }

    /// Action setPublished: PUT /v1/me/business/agenda. Publishing can fail with bipe_agenda_publish_incomplete.
    private func setPublished(_ value: Bool) async {
        saving = true
        error = nil
        defer { saving = false }
        do {
            let state = try await api.saveAgendaSettings(published: value, timeZone: timeZone)
            published = state.published
            changed()
        } catch {
            self.error = (error as? BipeAPIError)?.isPublishIncomplete == true ? BipeStrings.agendaPublishIncomplete : BipeStrings.agendaErrorSave
        }
    }

    /// Action save: PUT /v1/me/business/agenda/policy with every field.
    private func save() async {
        guard let edited else { return }
        saving = true
        error = nil
        defer { saving = false }
        do {
            policy = try await api.setAgendaPolicy(edited)
            changed()
        } catch {
            self.error = BipeStrings.agendaErrorSave
        }
    }
}
