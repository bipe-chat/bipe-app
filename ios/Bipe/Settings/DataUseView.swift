// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec data-use (bipe-chat/bipe#242): the budget of a normal day, the media counted today and this month by
/// network, the data saver switch, and the space Bipe uses on the phone (bipe-chat/bipe#826).
struct DataUseView: View {
    let session: AccountSession
    @AppStorage(DataUseMeter.saverKey) private var dataSaver = BipeRegistries.dataSaverDefault
    @State private var snapshot = DataUseMeter.shared.snapshot()
    /// mediaCacheBytes, voiceBytes and storeBytes; nil while they load.
    @State private var storage: StorageUse?
    @State private var confirmClear = false
    @State private var clearing = false
    @State private var clearFailed = false

    private var budgetLabel: String {
        ByteCountFormatter.string(fromByteCount: Int64(BipeRegistries.dataUseBudgetMegabytesPerDay) * 1_000_000, countStyle: .file)
    }

    var body: some View {
        List {
            Section {
                Text(BipeStrings.dataUseBudget(budgetLabel))
                    .foregroundStyle(BipeColor.inkPrimary)
                    .listRowBackground(BipeColor.surfaceRaised)
            }
            Section {
                countRows(mobile: snapshot.todayMobile, wifi: snapshot.todayWifi)
            } header: {
                Text(BipeStrings.dataUseToday)
            }
            Section {
                countRows(mobile: snapshot.monthMobile, wifi: snapshot.monthWifi)
            } header: {
                Text(BipeStrings.dataUseMonth)
            } footer: {
                Text(BipeStrings.dataUseFooter)
            }
            Section {
                Toggle(isOn: $dataSaver) {
                    Text(BipeStrings.dataUseSaver)
                        .foregroundStyle(BipeColor.inkPrimary)
                }
                .listRowBackground(BipeColor.surfaceRaised)
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    Text(BipeStrings.dataUseSaverFooter)
                    if DataUseMeter.shared.systemSaverOn {
                        Text(BipeStrings.dataUseSaverSystem)
                    }
                }
            }
            storageSection
        }
        .scrollContentBackground(.hidden)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .navigationTitle(BipeStrings.chatsDataUse)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.dataUse.rawValue)
        .onAppear { snapshot = DataUseMeter.shared.snapshot() }
        // Action measureStorage.
        .task { storage = await StorageMeter.measure(client: session.client) }
        .alert(BipeStrings.storageClearTitle, isPresented: $confirmClear) {
            Button(role: .destructive) {
                clear()
            } label: {
                Text(BipeStrings.storageClearConfirm)
            }
            Button(role: .cancel) {} label: {
                Text(BipeStrings.appCancel)
            }
        } message: {
            Text(BipeStrings.storageClearBody)
        }
    }

    /// Spec data-use, storage: three sizes and the button that clears the media cache.
    private var storageSection: some View {
        Section {
            storageRow(title: BipeStrings.storageMedia, bytes: storage?.mediaCacheBytes)
            storageRow(title: BipeStrings.storageVoice, bytes: storage?.voiceBytes)
            storageRow(title: BipeStrings.storageStore, bytes: storage?.storeBytes)
            // Action askClearMediaCache.
            Button(role: .destructive) {
                confirmClear = true
            } label: {
                HStack {
                    Text(BipeStrings.storageClear)
                    if clearing {
                        Spacer()
                        ProgressView()
                    }
                }
            }
            .disabled(clearing)
            .listRowBackground(BipeColor.surfaceRaised)
        } header: {
            Text(BipeStrings.storageTitle)
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                Text(BipeStrings.storageFooter)
                if clearFailed {
                    Text(BipeStrings.errorGeneric)
                        .foregroundStyle(BipeColor.trustDangerInk)
                }
            }
        }
    }

    private func storageRow(title: String, bytes: Int64?) -> some View {
        HStack {
            Text(title)
                .foregroundStyle(BipeColor.inkPrimary)
            Spacer()
            if let bytes {
                Text(Self.size(bytes))
                    .foregroundStyle(BipeColor.inkSecondary)
            } else {
                ProgressView()
            }
        }
        .accessibilityElement(children: .combine)
        .listRowBackground(BipeColor.surfaceRaised)
    }

    /// Action clearMediaCache. The session is built again, so the tabs build again and the app's toast shows
    /// freedBytes (bipe_storage_cleared).
    private func clear() {
        clearing = true
        clearFailed = false
        Task {
            do {
                try await AccountStartModel.shared.clearCaches(session)
            } catch {
                clearFailed = true
            }
            clearing = false
        }
    }

    @ViewBuilder
    private func countRows(mobile: DataUseMeter.Count, wifi: DataUseMeter.Count) -> some View {
        countRow(title: BipeStrings.dataUseMobile, count: mobile)
        countRow(title: BipeStrings.dataUseWifi, count: wifi)
    }

    private func countRow(title: String, count: DataUseMeter.Count) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .foregroundStyle(BipeColor.inkPrimary)
            Text(BipeStrings.dataUseSent(Self.size(count.sentBytes)))
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkSecondary)
            Text(BipeStrings.dataUseReceived(Self.size(count.receivedBytes)))
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkSecondary)
        }
        .accessibilityElement(children: .combine)
        .listRowBackground(BipeColor.surfaceRaised)
    }

    private static func size(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}
