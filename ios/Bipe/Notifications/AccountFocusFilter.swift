// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppIntents
import BipeKit
import Foundation

// bipe-chat/bipe#512 (ADR 0138 rule 4, spec notification-service notes): a Focus filter that picks which accounts on
// this phone notify in a Focus. The extension sets filterCriteria to the account's Matrix ID on every notification;
// the system hides the ones the predicate does not match. The titles are keys of the Bipe table, which the app bundle
// carries next to BipeKit's (project.yml), so the system reads them from bipe-shared strings.

/// An account on this phone, for the Focus filter's account list.
struct FocusAccountEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation {
        TypeDisplayRepresentation(name: LocalizedStringResource("bipe_focus_filter_accounts", table: "Bipe"))
    }

    static var defaultQuery: FocusAccountQuery { FocusAccountQuery() }

    let id: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(BipeAccountLabels.label(for: id))")
    }
}

struct FocusAccountQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [FocusAccountEntity] {
        identifiers.map(FocusAccountEntity.init(id:))
    }

    /// The accounts on this phone, oldest first.
    func suggestedEntities() async throws -> [FocusAccountEntity] {
        AccountList.order.map(FocusAccountEntity.init(id:))
    }
}

struct AccountFocusFilter: SetFocusFilterIntent {
    static var title: LocalizedStringResource { LocalizedStringResource("bipe_focus_filter_title", table: "Bipe") }

    static var description: IntentDescription? {
        IntentDescription(LocalizedStringResource("bipe_focus_filter_description", table: "Bipe"))
    }

    /// None chosen: every account notifies.
    @Parameter(title: LocalizedStringResource("bipe_focus_filter_accounts", table: "Bipe"))
    var accounts: [FocusAccountEntity]?

    var displayRepresentation: DisplayRepresentation {
        let names = (accounts ?? []).map { BipeAccountLabels.label(for: $0.id) }
        let subtitle = names.isEmpty ? BipeStrings.focusFilterAll : names.formatted(.list(type: .and))
        return DisplayRepresentation(title: Self.title, subtitle: "\(subtitle)")
    }

    var appContext: FocusFilterAppContext {
        let ids = (accounts ?? []).map(\.id)
        return FocusFilterAppContext(notificationFilterPredicate: ids.isEmpty ? nil : NSPredicate(format: "SELF IN %@", ids))
    }

    func perform() async throws -> some IntentResult {
        .result()
    }
}
