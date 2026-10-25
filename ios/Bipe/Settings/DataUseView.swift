// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec data-use (bipe-chat/bipe#242): the budget of a normal day, the media counted today and this month by
/// network, and the data saver switch.
struct DataUseView: View {
    @AppStorage(DataUseMeter.saverKey) private var dataSaver = BipeRegistries.dataSaverDefault
    @State private var snapshot = DataUseMeter.shared.snapshot()

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
        }
        .scrollContentBackground(.hidden)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .navigationTitle(BipeStrings.chatsDataUse)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.dataUse.rawValue)
        .onAppear { snapshot = DataUseMeter.shared.snapshot() }
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
