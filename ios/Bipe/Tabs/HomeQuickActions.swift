// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import UIKit

/// Spec app-tabs, action homeQuickAction: the actions of a long press on the app icon (bipe-chat/bipe#1241).
enum HomeQuickAction: String, CaseIterable {
    case newChat
    case search
    case myQrCode
    case switchAccount

    private static let typePrefix = "chat.bipe.quick."

    init?(item: UIApplicationShortcutItem) {
        guard item.type.hasPrefix(Self.typePrefix) else { return nil }
        self.init(rawValue: String(item.type.dropFirst(Self.typePrefix.count)))
    }

    fileprivate func item(subtitle: String?) -> UIApplicationShortcutItem {
        UIApplicationShortcutItem(type: Self.typePrefix + rawValue, localizedTitle: title, localizedSubtitle: subtitle,
                                  icon: UIApplicationShortcutIcon(systemImageName: systemImage))
    }

    private var title: String {
        switch self {
        case .newChat: BipeStrings.actionStartChat
        case .search: BipeStrings.appTabSearch
        case .myQrCode: BipeStrings.profileQrTitle
        case .switchAccount: BipeStrings.a11ySwitchAccount
        }
    }

    /// iOS draws this menu, so it gets system symbols, as the native context menus do.
    private var systemImage: String {
        switch self {
        case .newChat: "square.and.pencil"
        case .search: "magnifyingglass"
        case .myQrCode: "qrcode"
        case .switchAccount: "person.2.circle"
        }
    }
}

/// Holds the action until the signed-in screens show it, as NotificationActions does for a notification.
/// AppTabsView handles the tab actions. The Conversas chat list handles the actions that open a screen.
@MainActor
final class HomeQuickActions: ObservableObject {
    static let shared = HomeQuickActions()

    @Published var pending: HomeQuickAction?

    /// The content of the last published items, so that a chat list update does not set them again.
    private var published: [String]?

    /// Returns false when the item is not a Bipe action.
    func receive(_ item: UIApplicationShortcutItem) -> Bool {
        guard let action = HomeQuickAction(item: item) else { return false }
        pending = action
        return true
    }

    /// With no account on the phone, no action shows. While the app opens, the items of the last run stay.
    func update(phase: AccountStartModel.Phase, accounts: [AccountSummary]) {
        switch phase {
        case .ready:
            let current = accounts.first(where: \.isCurrent)
            // Switch account only with a second account on this phone, with the current account as subtitle.
            let subtitle = accounts.count > 1 ? current.map { account in
                account.handle.map { "@\($0)" } ?? account.displayName
            } : nil
            let actions = HomeQuickAction.allCases.filter { $0 != .switchAccount || accounts.count > 1 }
            publish(actions.map { $0.item(subtitle: $0 == .switchAccount ? subtitle : nil) })
        case .noAccount:
            pending = nil
            publish([])
        case .opening, .failed:
            break
        }
    }

    private func publish(_ items: [UIApplicationShortcutItem]) {
        let content = items.map { "\($0.type)|\($0.localizedSubtitle ?? "")" }
        guard content != published else { return }
        published = content
        UIApplication.shared.shortcutItems = items
    }
}

/// Receives a quick action on a cold launch and on a running app. A SwiftUI app with a scene gets it only here.
final class BipeSceneDelegate: NSObject, UIWindowSceneDelegate {
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        if let item = connectionOptions.shortcutItem {
            _ = HomeQuickActions.shared.receive(item)
        }
    }

    func windowScene(_ windowScene: UIWindowScene, performActionFor shortcutItem: UIApplicationShortcutItem,
                     completionHandler: @escaping (Bool) -> Void) {
        completionHandler(HomeQuickActions.shared.receive(shortcutItem))
    }
}
