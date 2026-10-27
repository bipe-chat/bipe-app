// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import Phosphor
import SwiftUI

/// Theft marks of chat partners (spec chat-timeline theftMark, ADR 0044, bipe-chat/bipe#600). The app asks
/// POST /v1/theft-marks for a person at most every checkMinutes per account and verifies the statement with the
/// badge keys (docs/theft-mark.md). A failed call or an invalid statement is no mark.
@MainActor
enum TheftMarkChecks {
    private struct Entry {
        let checkedAt: Date
        let statement: BipeTheftStatement?
    }

    private static var entries: [String: Entry] = [:]
    private static let api = BipeAPIClient(baseURL: AppConfig.apiBaseURL)
    private static let verifier = try? BipeTheftMarkVerifier.bipeTrusted()

    static var interval: TimeInterval { TimeInterval(BipeRegistries.theftCheckMinutes * 60) }

    /// The valid statement for `userId`, else `nil`.
    static func statement(for userId: String, session: AccountSession, now: Date = .now) async -> BipeTheftStatement? {
        let key = "\(session.userId)|\(userId)"
        if let entry = entries[key], now.timeIntervalSince(entry.checkedAt) < interval {
            return entry.statement.flatMap { $0.expiryDate > now ? $0 : nil }
        }
        guard let verifier else { return nil }
        let api = api
        do {
            let envelopes = try await BipeAPIClient.withOpenIDRetry(session.openID) { (token: BipeOpenIDToken) async throws(BipeAPIError) -> [BadgeEnvelope] in
                try await api.theftMarks(userIDs: [userId], openID: token)
            }
            let statement = verifier.statement(for: userId, in: envelopes, now: now)
            entries[key] = Entry(checkedAt: now, statement: statement)
            return statement
        } catch {
            return nil
        }
    }
}

/// Spec chat-timeline theftMark: the red bar above the composer of a 1:1 chat with a marked account.
struct TheftMarkBanner: View {
    let name: String

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            PhosphorIcon.warningCircle.image
                .resizable()
                .frame(width: 20, height: 20)
                .foregroundStyle(BipeColor.trustDangerInk)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(BipeStrings.theftBannerTitle)
                    .font(.subheadline.bold())
                    .foregroundStyle(BipeColor.trustDangerInk)
                Text(BipeStrings.theftBannerBody(name))
                    .font(.footnote)
                    .foregroundStyle(BipeColor.inkPrimary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.trustDangerWash))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(BipeColor.trustDangerLine, lineWidth: 2))
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
    }
}

/// Spec chat-timeline theftMark: the line under a message the marked account sent near or after the theft.
struct TheftMessageMarker: View {
    var body: some View {
        HStack(spacing: 4) {
            PhosphorIcon.warningCircle.image
                .resizable()
                .frame(width: 12, height: 12)
                .foregroundStyle(BipeColor.trustDangerInk)
                .accessibilityHidden(true)
            Text(BipeStrings.theftMessageMarker)
                .font(.caption)
                .foregroundStyle(BipeColor.trustDangerInk)
        }
    }
}
