// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.tabs

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import chat.bipe.shared.R
import java.text.NumberFormat

/**
 * The bottom bar of app-tabs: the standard Material 3 navigation bar with Material icons; the system sets their size,
 * colour and selected state (ADR 0149).
 */
@Composable
internal fun BipeTabBar(
    state: AppTabsModel.State,
    onSelect: (AppTabsModel.Tab) -> Unit,
    onDoubleTapVoce: () -> Unit,
    onLongPressVoce: () -> Unit,
    /** The account menu, anchored to the Você item (ADR 0135). */
    voceMenu: @Composable () -> Unit = {},
) {
    NavigationBar {
        // A company account has its groups in Conversas (spec app-tabs).
        TabItem(
            state, AppTabsModel.Tab.CONVERSAS, Icons.Outlined.ChatBubbleOutline, Icons.Filled.ChatBubble,
            R.string.bipe_tab_chats, if (state.isCompany) state.conversasUnread + state.gruposAttention else state.conversasUnread, onSelect,
        )
        // A company account has Trabalho in the place of Grupos (spec app-tabs, business-tools).
        if (state.isCompany) {
            TabItem(
                state, AppTabsModel.Tab.TRABALHO, Icons.Outlined.Work, Icons.Filled.Work,
                R.string.bipe_tab_work, 0, onSelect,
            )
        } else {
            TabItem(
                state, AppTabsModel.Tab.GRUPOS, Icons.Outlined.Groups, Icons.Filled.Groups,
                R.string.bipe_tab_groups, state.gruposAttention, onSelect,
            )
        }
        TabItem(
            state, AppTabsModel.Tab.VOCE, Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle,
            R.string.bipe_tab_you, 0, onSelect,
            showsDot = state.otherAccountUnread,
            menu = voceMenu,
            modifier = Modifier.voceGestures(onDoubleTapVoce, onLongPressVoce).voceSwitchAction(
                if (state.accounts.size > 1) stringResource(R.string.bipe_a11y_switch_account) else null,
                onLongPressVoce,
            ),
        )
    }
}

@Composable
private fun RowScope.TabItem(
    state: AppTabsModel.State,
    tab: AppTabsModel.Tab,
    icon: ImageVector,
    selectedIcon: ImageVector,
    label: Int,
    badge: Int,
    onSelect: (AppTabsModel.Tab) -> Unit,
    showsDot: Boolean = false,
    menu: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val selected = state.selectedTab == tab
    NavigationBarItem(
        selected = selected,
        onClick = { onSelect(tab) },
        icon = {
            BadgedBox(badge = {
                when {
                    badge > 0 -> Badge { Text(NumberFormat.getIntegerInstance().format(badge)) }
                    // The other-account dot on Você (ADR 0135).
                    showsDot -> Badge()
                }
            }) {
                Box {
                    Icon(if (selected) selectedIcon else icon, contentDescription = null)
                    menu()
                }
            }
        },
        label = { Text(stringResource(label)) },
        modifier = modifier,
    )
}

/**
 * A double tap and a long press on the Você item (ADR 0135). NavigationBarItem has only onClick, so this watches the
 * touches in the initial pass and never consumes them: a tap still opens Você through the item itself.
 */
@Composable
/**
 * Spec app-tabs: TalkBack cannot hold, and Você has no account rows, so the Você item carries a custom action that
 * opens the account menu like a hold. Only with two or more accounts ([label] null otherwise).
 */
private fun Modifier.voceSwitchAction(label: String?, open: () -> Unit): Modifier =
    if (label == null) this else semantics { customActions = listOf(CustomAccessibilityAction(label) { open(); true }) }

private fun Modifier.voceGestures(onDoubleTap: () -> Unit, onLongPress: () -> Unit): Modifier {
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val longPress by rememberUpdatedState(onLongPress)
    return pointerInput(Unit) {
        var lastUp = 0L
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var timedOut = true
            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation(PointerEventPass.Initial).also { timedOut = false }
            }
            when {
                timedOut -> {
                    lastUp = 0L
                    longPress()
                }
                up == null -> lastUp = 0L
                up.uptimeMillis - lastUp <= viewConfiguration.doubleTapTimeoutMillis -> {
                    lastUp = 0L
                    doubleTap()
                }
                else -> lastUp = up.uptimeMillis
            }
        }
    }
}
