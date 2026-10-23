// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Where a link that the app opens leads (spec profile-link-open).
enum PeopleLink: Equatable, Identifiable {
    /// https://bipe.chat/@handle: look up the exact @.
    case handle(String)
    /// https://matrix.to/#/@user:server: open the person directly.
    case userId(String)

    var id: String {
        switch self {
        case .handle(let handle): "handle:\(handle)"
        case .userId(let userId): "user:\(userId)"
        }
    }

    /// `nil` when the URL is not a profile link: the app ignores it.
    init?(url: URL) {
        if let handle = BipeProfileLink.handle(from: url) {
            self = .handle(handle)
        } else if let userId = BipeProfileLink.matrixUserID(fromPermalink: url) {
            self = .userId(userId)
        } else {
            return nil
        }
    }
}

/// Spec profile-link-open: finds the account with exactly this @ and opens the person.
struct ProfileLinkOpenView: View {
    enum Phase: Equatable {
        case resolving
        case found
        case notFound
        case failed
    }

    let people: PeopleService
    let handle: String
    /// Another account: opens person-profile.
    let openPerson: (String) -> Void
    /// The user's own @: opens profile-link.
    let openOwnLink: () -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var phase: Phase = .resolving

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                switch phase {
                case .resolving, .found:
                    ProgressView()
                    Text(BipeStrings.appProfileLinkOpening)
                        .foregroundStyle(BipeColor.inkSecondary)
                case .notFound:
                    PhosphorIcon.userCircle.image
                        .resizable()
                        .frame(width: 48, height: 48)
                        .foregroundStyle(BipeColor.glyphSecondary)
                    Text(BipeStrings.linkHandleNotFound(handle))
                        .foregroundStyle(BipeColor.inkPrimary)
                        .multilineTextAlignment(.center)
                case .failed:
                    Text(BipeStrings.errorNetwork)
                        .foregroundStyle(BipeColor.inkPrimary)
                        .multilineTextAlignment(.center)
                    Button(BipeStrings.appRetry) { Task { await open() } }
                        .buttonStyle(.borderedProminent)
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appClose) { dismiss() }
                }
            }
        }
        .tint(BipeColor.accentFill)
        .accessibilityIdentifier(BipeScreen.profileLinkOpen.rawValue)
        .presentationDetents([.medium])
        .task { await open() }
    }

    /// Action open: GET /v1/handles/{handle}. Exact lookup only (ADR 0013, ADR 0041).
    private func open() async {
        phase = .resolving
        do throws(BipeAPIError) {
            let record = try await people.withToken { (token: String) async throws(BipeAPIError) -> BipeHandleRecord in
                try await people.api.handle(handle, accessToken: token)
            }
            phase = .found
            dismiss()
            if record.userId == people.session.userId {
                openOwnLink()
            } else {
                openPerson(record.userId)
            }
        } catch {
            if case .server(_, _, 404) = error {
                phase = .notFound
            } else {
                phase = .failed
            }
        }
    }
}
