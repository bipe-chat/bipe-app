// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Specs pix-check-receive and pix-check-result: the check, then its result. Works signed out and offline.
struct PixCheckView: View {
    @ObservedObject var model: PixCheckReceiveModel
    /// Shows Send to a chat (accountAvailable of pix-check-result).
    let accountAvailable: Bool
    let close: () -> Void
    /// Action sendToChat: the original input of a report, for an editable preview. nil hides the button.
    var sendToChat: ((PixCheckedItem) -> Void)?

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(BipeStrings.pixCheckTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        if model.phase == .reading || model.phase == .checking {
                            Button(BipeStrings.appCancel) {
                                model.cancel()
                                close()
                            }
                        } else {
                            Button(BipeStrings.pixCheckClose) {
                                model.close()
                                close()
                            }
                        }
                    }
                }
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .alert(BipeStrings.errorGeneric, isPresented: $model.handoffFailed) {
                    Button(BipeStrings.appCancel, role: .cancel) {}
                }
        }
        .accessibilityIdentifier(BipeScreen.pixCheckReceive.rawValue)
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .reading, .checking:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .ready, .nothingFound:
            PixCheckResultView(results: model.results,
                               accountAvailable: accountAvailable,
                               sendToChat: sendToChat)
        case .missingInput, .unsupported, .noQr, .cancelled:
            message(BipeStrings.pixCheckNothingFound, retry: false)
        case .failed:
            message(BipeStrings.errorGeneric, retry: model.canRetry)
        }
    }

    private func message(_ text: String, retry: Bool) -> some View {
        VStack(spacing: 16) {
            Text(text)
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            if retry {
                Button(BipeStrings.appRetry, action: model.retry)
                    .foregroundStyle(BipeColor.accentInk)
            }
            Text(BipeStrings.pixCheckPrivate)
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Spec pix-check-result: the fields found, the rule warning, and the bank check at the end.
struct PixCheckResultView: View {
    let results: [PixCheckedItem]
    let accountAvailable: Bool
    var sendToChat: ((PixCheckedItem) -> Void)?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if !results.contains(where: { $0.check.foundPix }) {
                    Text(BipeStrings.pixCheckNothingFound)
                        .foregroundStyle(BipeColor.inkPrimary)
                }
                ForEach(results) { item in
                    report(item)
                }
                Text(BipeStrings.pixCheckPrivate)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.inkSecondary)
                // Always last, also for bad CRC, malformed and nothing found.
                Text(BipeStrings.pixCheckBankName)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(BipeColor.inkPrimary)
            }
            .padding(16)
        }
        .accessibilityIdentifier(BipeScreen.pixCheckResult.rawValue)
    }

    @ViewBuilder
    private func report(_ item: PixCheckedItem) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if let code = item.code {
                CodeReport(code: code)
            }
            if !item.keys.isEmpty {
                KeysReport(keys: item.keys)
            }
            if let warning = item.warningKind {
                WarningCard(kind: warning)
            }
            if accountAvailable, let sendToChat, item.check.foundPix {
                Button {
                    sendToChat(item)
                } label: {
                    Text(BipeStrings.pixCheckSendToChat)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(BipeColor.accentInk)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(BipeColor.surfaceRaised))
    }
}

/// A BR Code: the status line, then the fields in the order of the spec notes.
private struct CodeReport: View {
    let code: PixCheck.Code

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(BipeStrings.pixCheckCodeTitle)
                .font(.headline)
                .foregroundStyle(BipeColor.inkPrimary)
            switch code.status {
            case .badCrc:
                // Before any parsed field, with the caution treatment; the fields stay unverified.
                caution(BipeStrings.pixCheckCodeBadCrc)
                fields
            case .malformed:
                caution(BipeStrings.pixCheckCodeMalformed)
            case .valid:
                if code.dynamic {
                    Text(BipeStrings.pixCheckCodeDynamic)
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
                fields
                if !code.dynamic {
                    Text(BipeStrings.pixCheckNameHint)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
        }
    }

    @ViewBuilder
    private var fields: some View {
        field(BipeStrings.pixCheckFieldName, code.name)
        field(BipeStrings.pixCheckFieldCity, code.city)
        field(BipeStrings.pixCheckFieldAmount, code.amount.map(PixFormat.amount) ?? BipeStrings.pixCheckAmountOpen)
        if code.dynamic {
            // Plain text only: the charge location is never opened or fetched.
            field(BipeStrings.pixCheckFieldChargeHost, code.urlHost)
        } else {
            field(BipeStrings.pixCheckFieldKey, code.key)
        }
        field(BipeStrings.pixCheckFieldInfo, code.info)
        field(BipeStrings.pixCheckFieldTxid, code.txid)
    }

    @ViewBuilder
    private func field(_ title: String, _ value: String?) -> some View {
        if let value, !value.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkSecondary)
                Text(verbatim: value)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .textSelection(.enabled)
            }
        }
    }

    private func caution(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            PhosphorIcon.warningCircle.image
                .resizable()
                .frame(width: 18, height: 18)
                .accessibilityHidden(true)
            Text(text)
                .font(.subheadline.weight(.semibold))
        }
        .foregroundStyle(BipeColor.trustDangerInk)
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 10).fill(BipeColor.trustDangerWash))
    }
}

/// Other Pix keys in the text, each with every matching type joined by "or".
private struct KeysReport: View {
    let keys: [PixCheck.Key]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(BipeStrings.pixCheckKeysTitle)
                .font(.headline)
                .foregroundStyle(BipeColor.inkPrimary)
            ForEach(Array(keys.enumerated()), id: \.offset) { _, key in
                VStack(alignment: .leading, spacing: 2) {
                    Text(PixFormat.types(key.types))
                        .font(.caption)
                        .foregroundStyle(BipeColor.inkSecondary)
                    Text(verbatim: key.value)
                        .foregroundStyle(BipeColor.inkPrimary)
                        .textSelection(.enabled)
                }
            }
        }
    }
}

/// The rule warning: the scam-warning title and the mapped text.
private struct WarningCard: View {
    let kind: ScamWarningKind

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            PhosphorIcon.warningCircle.image
                .resizable()
                .frame(width: 16, height: 16)
                .foregroundStyle(BipeColor.trustCautionInk)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(BipeStrings.scamWarningTitle)
                    .font(.caption.bold())
                    .foregroundStyle(BipeColor.trustCautionInk)
                Text(kind.message)
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkPrimary)
            }
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 10).fill(BipeColor.trustCautionWash))
        .accessibilityElement(children: .combine)
    }
}

enum PixFormat {
    /// The decimal amount of the code in BRL, from its digits: no binary rounding.
    static func amount(_ value: String) -> String {
        guard let decimal = Decimal(string: value, locale: Locale(identifier: "en_US_POSIX")) else { return value }
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.currencyCode = "BRL"
        formatter.locale = Locale(identifier: Bundle.main.preferredLocalizations.first ?? "pt-BR")
        return formatter.string(from: decimal as NSDecimalNumber) ?? value
    }

    /// The localized labels of every matching key type, joined with "or" in reference order.
    static func types(_ types: [PixCheck.KeyType]) -> String {
        let labels = types.map(label)
        guard var joined = labels.first else { return label(.unknown) }
        for next in labels.dropFirst() {
            joined = BipeStrings.pixCheckKeyTypesOr(joined, next)
        }
        return joined
    }

    static func label(_ type: PixCheck.KeyType) -> String {
        switch type {
        case .cpf: BipeStrings.pixCheckKeyTypeCpf
        case .cnpj: BipeStrings.pixCheckKeyTypeCnpj
        case .phone: BipeStrings.pixCheckKeyTypePhone
        case .email: BipeStrings.pixCheckKeyTypeEmail
        case .random: BipeStrings.pixCheckKeyTypeRandom
        case .unknown: BipeStrings.pixCheckKeyTypeUnknown
        }
    }
}
