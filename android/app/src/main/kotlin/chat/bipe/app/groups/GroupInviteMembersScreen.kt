// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.invites.InviteSomeoneNewRow
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen

/**
 * Spec group-invite-members. In pick mode onDone gets the picked people; in invite mode it gets the people invited,
 * after every invite went out.
 */
@Composable
fun GroupInviteMembersScreen(
    session: AccountSession,
    media: MediaLoader,
    mode: GroupInviteMembersModel.Mode,
    onBack: () -> Unit,
    onDone: (List<Person>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val model = remember(mode) { GroupInviteMembersModel(session, mode, scope) }
    LaunchedEffect(model) { model.appear() }
    BackHandler(onBack = onBack)
    val colors = BipeTheme.colors
    val query by model.query.collectAsState()
    val contacts by model.contacts.collectAsState()
    val phase by model.phase.collectAsState()
    val loading by model.loading.collectAsState()
    val selectedCount by model.selectedCount.collectAsState()

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.GROUP_INVITE_MEMBERS.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            GroupTopBar(stringResource(R.string.bipe_app_group_add_people), onBack) {
                if (phase == GroupInviteMembersModel.Phase.INVITING) {
                    CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(24.dp).padding(end = 4.dp))
                } else {
                    TextButton(
                        enabled = selectedCount > 0,
                        onClick = {
                            // Action invite.
                            when (mode) {
                                is GroupInviteMembersModel.Mode.Pick -> onDone(model.selectedPeople())
                                is GroupInviteMembersModel.Mode.Invite -> {
                                    val people = model.selectedPeople()
                                    model.invite { onDone(people) }
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.bipe_app_group_invite_action))
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = model::setQuery,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.bipe_app_people_search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item(key = "header") { GroupSectionHeader(stringResource(R.string.bipe_app_people_contacts)) }
                if (loading) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = colors.accentFill)
                        }
                    }
                }
                items(contacts, key = { it.person.userId }) { pick ->
                    PickRow(pick, media) { model.toggle(pick.person.userId) }
                }
                item(key = "footer") { GroupFooter(stringResource(R.string.bipe_start_chat_search_by_handle)) }
                item(key = "invite") { InviteSomeoneNewRow(session, media) }
            }
        }
    }
}
