// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.tabs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import chat.bipe.app.chatlist.ChatListModel
import chat.bipe.app.chatlist.ChatListScreen
import chat.bipe.app.chatlist.ChatScope
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.common.bipeHaptic
import chat.bipe.app.handle.HandlePickModel
import chat.bipe.app.voce.VoceScreen
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.generated.BipeScreen

/** Spec app-tabs: Conversas · Grupos · Você in the bottom bar; search is a top-bar action on Android (ADR 0053). */
@Composable
fun AppTabsScreen(
    tabs: AppTabsModel,
    chatList: ChatListModel,
    media: MediaLoader,
    handle: HandlePickModel,
    onOpenHandlePick: () -> Unit,
    onOpenChat: (String) -> Unit,
    onNewAccount: () -> Unit,
    /** Spec encryption-status: the banner above Conversas. */
    banner: @Composable () -> Unit = {},
    onCreateGroup: () -> Unit = {},
    onDeleteDm: (String) -> Unit = {},
    onInvite: () -> Unit = {},
) {
    val state by tabs.state.collectAsState()
    val account = state.accounts.firstOrNull { it.isCurrent }
    val session = chatList.session
    val view = LocalView.current
    // Search goes back to the tab it was opened from.
    var searchFrom by rememberSaveable { mutableStateOf(AppTabsModel.Tab.CONVERSAS) }
    val openSearch = {
        searchFrom = state.selectedTab
        tabs.selectTab(AppTabsModel.Tab.SEARCH)
    }
    Column(modifier = Modifier.fillMaxSize().testTag(BipeScreen.APP_TABS.id)) {
        Box(modifier = Modifier.weight(1f)) {
            when (state.selectedTab) {
                AppTabsModel.Tab.CONVERSAS ->
                    ChatListScreen(chatList, ChatScope.CONVERSAS, media, handle, openSearch, onOpenHandlePick, onOpenChat,
                        account = account, onOpenVoce = { tabs.selectTab(AppTabsModel.Tab.VOCE) }, banner = banner,
                        onCreateGroup = onCreateGroup, onDeleteDm = onDeleteDm, onInvite = onInvite)
                AppTabsModel.Tab.GRUPOS ->
                    ChatListScreen(chatList, ChatScope.GRUPOS, media, handle, openSearch, onOpenHandlePick, onOpenChat,
                        account = account, onOpenVoce = { tabs.selectTab(AppTabsModel.Tab.VOCE) },
                        onCreateGroup = onCreateGroup, onDeleteDm = onDeleteDm, onInvite = onInvite)
                AppTabsModel.Tab.VOCE -> VoceScreen(tabs, session, tabs.start.api, media, onNewAccount, onOpenChat)
                AppTabsModel.Tab.SEARCH -> {
                    BackHandler { tabs.selectTab(searchFrom) }
                    ChatSearchScreen(chatList, media, onOpenChat)
                }
            }
        }
        BipeTabBar(
            state = state,
            onSelect = tabs::selectTab,
            onDoubleTapVoce = { if (tabs.doubleTapVoce()) view.bipeHaptic(BipeHaptic.Tick) },
            onLongPressVoce = {
                view.bipeHaptic(BipeHaptic.Hold)
                tabs.longPressVoce()
            },
        )
    }
    if (state.showsAccountSwitcher) AccountSwitcher(tabs, media, onNewAccount)
}
