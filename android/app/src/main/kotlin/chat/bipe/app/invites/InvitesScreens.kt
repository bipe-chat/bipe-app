// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.invites

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.bipe.app.BipeApplication
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.groups.GroupRow
import chat.bipe.app.groups.LocalBipeApi
import chat.bipe.app.people.PeopleService
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient

/** Existing group/menu entry points share the account-lifetime invite model and sender. */
@Composable
fun InvitesScreen(session: AccountSession, api: BipeApiClient, media: MediaLoader, onClose: () -> Unit, onOpenChat: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BipeApplication
    val scope = rememberCoroutineScope()
    val people = remember(session) { PeopleService(context, session, api, media, scope) }
    DisposableEffect(people) {
        people.start()
        onDispose { people.close() }
    }
    InvitesScreen(app.appTabs.invites(session), people, onBack = onClose, openChat = {
        onClose()
        onOpenChat(it)
    })
}

/** group-invite-members: invite somebody who has not joined Bipe yet. */
@Composable
fun InviteSomeoneNewRow(session: AccountSession, media: MediaLoader) {
    val api = LocalBipeApi.current ?: return
    var open by remember { mutableStateOf(false) }
    GroupRow(PhosphorIcon.PLUS, stringResource(R.string.bipe_contacts_invite)) { open = true }
    if (open) {
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            InvitesScreen(session, api, media, onClose = { open = false }, onOpenChat = {})
        }
    }
}
