// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.bipeTapTarget
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeOnboarding
import chat.bipe.shared.generated.BipeOnboardingCard
import chat.bipe.shared.generated.BipeOnboardingTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spec chat-list-cards (bipe-chat/bipe#198): which cards of registries/onboarding.json firstList still show on this
 * account. A used or dismissed card never comes back.
 */
class FirstListCardsModel(context: Context, userId: String) {
    private val prefs = context.getSharedPreferences("bipe_first_list", Context.MODE_PRIVATE)
    private val key = "done_$userId"
    private val _done = MutableStateFlow(
        prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull { name -> BipeOnboardingCard.entries.firstOrNull { it.name == name } }.toSet(),
    )
    val done: StateFlow<Set<BipeOnboardingCard>> = _done.asStateFlow()

    /** State cards: registry order, only while Conversas lists at most FIRST_LIST_MAX_CHATS chats. */
    fun visible(done: Set<BipeOnboardingCard>, chatCount: Int, handle: String?): List<BipeOnboardingCard> {
        if (chatCount > BipeOnboarding.FIRST_LIST_MAX_CHATS) return emptyList()
        return BipeOnboardingCard.entries.filter { it !in done && (!it.needsHandle || handle != null) }
    }

    /** Actions dismiss, and every other action once it ran: the card goes for good. */
    fun finish(card: BipeOnboardingCard) {
        _done.value = _done.value + card
        prefs.edit().putStringSet(key, _done.value.map { it.name }.toSet()).apply()
    }
}

@Composable
fun FirstListCard(card: BipeOnboardingCard, handle: String?, onAction: (BipeOnboardingTarget) -> Unit) {
    val colors = BipeTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .background(colors.surfaceRaised, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(card.title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
        val body = if (card == BipeOnboardingCard.PROFILE && handle != null) stringResource(R.string.bipe_first_card_profile_body, handle)
        else stringResource(card.body)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(card.primary.title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkOnAccent,
                modifier = Modifier.bipeTapTarget().background(colors.accentFill, RoundedCornerShape(50))
                    .clickable(role = Role.Button) { onAction(card.primary.target) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            card.text.forEach { button ->
                Text(
                    stringResource(button.title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.accentInk,
                    modifier = Modifier.bipeTapTarget().clickable(role = Role.Button) { onAction(button.target) },
                )
            }
        }
    }
}
