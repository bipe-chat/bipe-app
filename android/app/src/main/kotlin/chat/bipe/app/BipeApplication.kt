// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app

import android.app.Application
import chat.bipe.app.account.AccountStartModel
import chat.bipe.app.tabs.AppTabsModel
import chat.bipe.shared.R
import kotlinx.coroutines.MainScope

/** Holds the accounts and the tabs for the life of the process, so the sessions outlive an activity. */
class BipeApplication : Application() {
    val scope = MainScope()
    val accountStart: AccountStartModel by lazy { AccountStartModel(this, scope) }
    val appTabs: AppTabsModel by lazy { AppTabsModel(accountStart, scope, getString(R.string.bipe_app_timeline_undecryptable)) }
}
