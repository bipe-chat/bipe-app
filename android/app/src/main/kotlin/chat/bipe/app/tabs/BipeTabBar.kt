// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.tabs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import java.text.NumberFormat

/** The bottom bar of app-tabs: Conversas and Grupos with their badges, and the Você item. */
@Composable
internal fun BipeTabBar(
    state: AppTabsModel.State,
    onSelect: (AppTabsModel.Tab) -> Unit,
    onDoubleTapVoce: () -> Unit,
    onLongPressVoce: () -> Unit,
) {
    val colors = BipeTheme.colors
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = colors.accentInk,
        selectedTextColor = colors.accentInk,
        indicatorColor = colors.accentWash,
        unselectedIconColor = colors.inkSecondary,
        unselectedTextColor = colors.inkSecondary,
    )
    NavigationBar(containerColor = colors.surfaceRaised) {
        TabItem(state, AppTabsModel.Tab.CONVERSAS, PhosphorIcon.CHAT_CIRCLE, R.string.bipe_tab_chats, state.conversasUnread, onSelect, itemColors)
        TabItem(state, AppTabsModel.Tab.GRUPOS, PhosphorIcon.USERS_THREE, R.string.bipe_tab_groups, state.gruposAttention, onSelect, itemColors)
        VoceItem(
            selected = state.selectedTab == AppTabsModel.Tab.VOCE,
            showsDot = state.otherAccountUnread,
            onClick = { onSelect(AppTabsModel.Tab.VOCE) },
            onDoubleClick = onDoubleTapVoce,
            onLongClick = onLongPressVoce,
        )
    }
}

@Composable
private fun RowScope.TabItem(
    state: AppTabsModel.State,
    tab: AppTabsModel.Tab,
    icon: PhosphorIcon,
    label: Int,
    badge: Int,
    onSelect: (AppTabsModel.Tab) -> Unit,
    colors: androidx.compose.material3.NavigationBarItemColors,
) {
    NavigationBarItem(
        selected = state.selectedTab == tab,
        onClick = { onSelect(tab) },
        icon = {
            BadgedBox(badge = {
                if (badge > 0) {
                    Badge(containerColor = BipeTheme.colors.accentFill, contentColor = BipeTheme.colors.inkOnAccent) {
                        Text(NumberFormat.getIntegerInstance().format(badge))
                    }
                }
            }) {
                Icon(painterResource(icon.drawable), contentDescription = null, modifier = Modifier.size(24.dp))
            }
        },
        label = { Text(stringResource(label)) },
        colors = colors,
    )
}

/**
 * The Você item. NavigationBarItem has only onClick, so this item is built with combinedClickable (ADR 0135): a tap
 * opens Você, a double tap switches accounts, a long press opens account-switcher.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.VoceItem(
    selected: Boolean,
    showsDot: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = BipeTheme.colors
    val tint = if (selected) colors.accentInk else colors.inkSecondary
    val label = stringResource(R.string.bipe_tab_you)
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .combinedClickable(
                role = Role.Tab,
                onClick = onClick,
                onDoubleClick = onDoubleClick,
                onLongClick = onLongClick,
            )
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(32.dp)
                .background(if (selected) colors.accentWash else colors.surfaceRaised, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            BadgedBox(badge = {
                if (showsDot) Badge(containerColor = colors.accentFill)
            }) {
                Icon(painterResource(PhosphorIcon.USER_CIRCLE.drawable), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.padding(top = 4.dp))
    }
}
