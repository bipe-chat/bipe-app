// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec remote-banner: one banner above the chat list for critical notices only (ADR 0117).
@MainActor
final class RemoteBannerModel: ObservableObject {
    @Published private(set) var banner: BipeRemoteBanner?
    @Published private(set) var state: BipeRemoteBanner.State = .hidden

    private let api: BipeAPIClient
    private var lastFetch: Date?
    private static let dismissedKey = "bipe.remoteBanner.dismissed"

    init(api: BipeAPIClient) {
        self.api = api
    }

    /// Action refresh: when the chat list appears and on foreground, at most once a minute. A failure keeps the
    /// last banner until it expires.
    func refresh() async {
        if let lastFetch, Date.now.timeIntervalSince(lastFetch) < 60 {
            update()
            return
        }
        lastFetch = .now
        do {
            // nil: no banner, or past expires_at.
            banner = try await api.remoteBanner()
        } catch {
            // A failed fetch keeps the last banner until it expires.
        }
        update()
    }

    private func update() {
        var state = banner?.state(appVersion: AppVersion.name) ?? .hidden
        if state == .notice, banner?.id == UserDefaults.standard.string(forKey: Self.dismissedKey) {
            state = .hidden
        }
        self.state = state
    }

    var isUpdate: Bool {
        if case .update = state { return true }
        return false
    }

    /// Action dismiss: notice only. That id does not show again on this phone.
    func dismiss() {
        guard state == .notice, let id = banner?.id else { return }
        UserDefaults.standard.set(id, forKey: Self.dismissedKey)
        update()
    }
}

struct RemoteBannerView: View {
    @ObservedObject var model: RemoteBannerModel
    @State private var page: WebPage?
    @Environment(\.openURL) private var openURL

    var body: some View {
        if model.state != .hidden, let banner = model.banner {
            HStack(alignment: .top, spacing: 12) {
                PhosphorIcon.warningCircle.image
                    .resizable()
                    .frame(width: 22, height: 22)
                    .foregroundStyle(BipeColor.trustCautionInk)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    // Title and text come from the server in pt-BR and show as they are.
                    Text(verbatim: banner.title)
                        .font(.subheadline.bold())
                        .foregroundStyle(BipeColor.inkPrimary)
                    Text(verbatim: banner.text)
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.inkPrimary)
                    if model.isUpdate {
                        Text(BipeStrings.remoteBannerUpdate)
                            .font(.subheadline.bold())
                            .foregroundStyle(BipeColor.accentInk)
                    }
                }
                Spacer(minLength: 0)
                if model.state == .notice {
                    // Action dismiss.
                    Button {
                        model.dismiss()
                    } label: {
                        PhosphorIcon.x.image
                            .resizable()
                            .frame(width: 16, height: 16)
                            .foregroundStyle(BipeColor.glyphSecondary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(BipeStrings.remoteBannerDismiss)
                }
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.trustCautionWash))
            .contentShape(Rectangle())
            // Action open.
            .onTapGesture {
                switch model.state {
                case .update(let store): openURL(store)
                case .notice: page = WebPage(url: banner.link)
                case .hidden: break
                }
            }
            .sheet(item: $page) { page in
                SafariPage(url: page.url).ignoresSafeArea()
            }
            .accessibilityIdentifier(BipeScreen.remoteBanner.rawValue)
        }
    }
}
