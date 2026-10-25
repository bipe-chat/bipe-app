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
import chat.bipe.app.business.WorkScreen
import chat.bipe.app.chatlist.ChatRequestsModel
import chat.bipe.app.chatlist.ChatSearchModel
import chat.bipe.app.chatlist.RemoteBannerModel
import chat.bipe.app.chatlist.ChatListModel
import chat.bipe.app.chatlist.ChatListScreen
import chat.bipe.app.chatlist.ChatScope
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.common.bipeHaptic
import chat.bipe.app.handle.HandlePickModel
import chat.bipe.app.voce.VoceScreen
import chat.bipe.app.people.PeopleRoute
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.generated.BipeScreen

/** Spec app-tabs: Conversas · Grupos · (Agenda for a company) · Você in the bottom bar; search is a top-bar action on Android (ADR 0053). */
@Composable
fun AppTabsScreen(
    tabs: AppTabsModel,
    chatList: ChatListModel,
    media: MediaLoader,
    handle: HandlePickModel,
    onOpenHandlePick: () -> Unit,
    onOpenChat: (String) -> Unit,
    onNewAccount: () -> Unit,
    requests: ChatRequestsModel,
    search: ChatSearchModel,
    remoteBanner: RemoteBannerModel,
    onOpenRequests: () -> Unit,
    onFindHandle: (String) -> Unit,
    /** Spec encryption-status: the banner above Conversas. */
    banner: @Composable () -> Unit = {},
    /** Spec recovery-reminder: the card above Conversas. */
    recoveryCard: @Composable () -> Unit = {},
    onCreateGroup: () -> Unit = {},
    onDeleteDm: (String) -> Unit = {},
    onChatNotifications: (String) -> Unit = {},
    onChatInfo: (chat.bipe.app.chatlist.ChatSummary) -> Unit = {},
    onInvite: () -> Unit = {},
    /** Opens a people screen (bipe-chat/bipe#568). */
    onOpenPeople: (PeopleRoute) -> Unit = {},
    listOpenRequest: Int = 0,
) {
    val state by tabs.state.collectAsState()
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
                    ChatListScreen(chatList, if (state.isCompany) ChatScope.TODAS else ChatScope.CONVERSAS, media, handle, openSearch, onOpenHandlePick, onOpenChat,
                        banner = banner, recoveryCard = recoveryCard,
                        onCreateGroup = onCreateGroup, onDeleteDm = onDeleteDm, onChatNotifications = onChatNotifications, onChatInfo = onChatInfo, onInvite = onInvite, onOpenPeople = onOpenPeople,
                        requests = requests, remoteBanner = remoteBanner, onOpenRequests = onOpenRequests, listOpenRequest = listOpenRequest)
                AppTabsModel.Tab.GRUPOS ->
                    ChatListScreen(chatList, ChatScope.GRUPOS, media, handle, openSearch, onOpenHandlePick, onOpenChat,
                        onCreateGroup = onCreateGroup, onDeleteDm = onDeleteDm, onChatNotifications = onChatNotifications, onChatInfo = onChatInfo, onInvite = onInvite, onOpenPeople = onOpenPeople,
                        requests = requests, remoteBanner = remoteBanner, onOpenRequests = onOpenRequests, listOpenRequest = listOpenRequest)
                // Spec business-tools: the company account's Trabalho tab, in the place of Grupos.
                AppTabsModel.Tab.TRABALHO -> WorkScreen(session, chatList, onOpenChat)
                AppTabsModel.Tab.VOCE -> VoceScreen(tabs, session, tabs.start.api, media, onNewAccount, onOpenChat)
                AppTabsModel.Tab.SEARCH -> {
                    BackHandler { tabs.selectTab(searchFrom) }
                    ChatSearchScreen(search, media, onOpenChat, onFindHandle)
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
            voceMenu = { AccountMenu(tabs, media, onNewAccount) },
        )
    }
}
