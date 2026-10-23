// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.people

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipePhoneNumber
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.handle
import chat.bipe.shared.api.inviteConnections
import chat.bipe.shared.api.lookupContacts
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Type ContactMatch of spec contacts. */
data class ContactMatch(val person: Person, val joinedWithInvite: Boolean)

/** State permission of spec contacts. Android has no limited access. */
private enum class ContactsPermission { NOT_ASKED, GRANTED, DENIED }

/** State phase of spec profile-link-open. */
private enum class LinkPhase { RESOLVING, NOT_FOUND, FAILED }

/** The address book: E.164 to the saved name. Numbers that do not normalise and duplicates are dropped. */
internal suspend fun readAddressBook(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
    val names = mutableMapOf<String, String>()
    val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
    runCatching {
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(0) ?: continue
                val e164 = BipePhoneNumber.normalize(number) ?: continue
                if (e164 !in names) names[e164] = cursor.getString(1).orEmpty()
            }
        }
    }
    names
}

/**
 * Spec contacts: which people in the address book are on Bipe. The numbers leave the phone only for the lookup; the
 * server compares them and keeps none (ADR 0015). The app keeps no copy of what it sent.
 */
@Composable
fun ContactsScreen(people: PeopleService, openChat: (String) -> Unit, invite: (() -> Unit)?, onBack: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(if (granted()) ContactsPermission.GRANTED else ContactsPermission.NOT_ASKED) }
    var checking by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var matches by remember { mutableStateOf<List<ContactMatch>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    // Action check: normalised numbers in chunks of 500 to POST /v1/contacts/lookup. Stops at a 429.
    fun check() {
        if (checking) return
        checking = true
        failure = null
        scope.launch {
            val names = readAddressBook(context)
            val phones = names.keys.toList()
            val found = linkedMapOf<String, String>()
            val result = runCatching {
                // At most 500 numbers per POST /v1/contacts/lookup.
                phones.chunked(500).forEach { chunk ->
                    people.withToken { people.api.lookupContacts(chunk, it) }.forEach { match ->
                        if (match.userId != people.session.userId && match.userId !in found) found[match.userId] = names[match.phone].orEmpty()
                    }
                }
            }
            result.exceptionOrNull()?.let { error ->
                val rateLimited = (error as? BipeApiException.Server)?.code == BipeApiErrorCode.RATE_LIMITED
                failure = context.getString(if (rateLimited) R.string.bipe_error_rate_limited else R.string.bipe_error_network)
                checking = false
                return@launch
            }
            people.rememberContactNames(found)
            val invited = runCatching { people.withToken { people.api.inviteConnections(it) }.toSet() }.getOrDefault(emptySet())
            matches = found.map { (userId, name) ->
                val person = runCatching { people.person(userId, contactName = name) }.getOrElse { Person(userId, name) }
                ContactMatch(person, userId in invited)
            }.sortedBy { it.person.displayName.lowercase() }
            checking = false
        }
    }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        permission = if (isGranted) ContactsPermission.GRANTED else ContactsPermission.DENIED
        if (isGranted) check()
    }
    LaunchedEffect(Unit) { if (granted() && matches == null) check() }

    PeopleScaffold(
        stringResource(R.string.bipe_contacts_title),
        BipeScreen.CONTACTS.id,
        onBack,
        notice = notice,
        onNoticeShown = { notice = null },
    ) {
        when (permission) {
            ContactsPermission.NOT_ASKED -> {
                Text(stringResource(R.string.bipe_contacts_intro), color = colors.inkSecondary)
                Button(onClick = { request.launch(Manifest.permission.READ_CONTACTS) }) { Text(stringResource(R.string.bipe_contacts_allow)) }
            }
            ContactsPermission.DENIED -> {
                Text(stringResource(R.string.bipe_contacts_denied), color = colors.inkSecondary)
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text(stringResource(R.string.bipe_contacts_open_settings_android)) }
            }
            ContactsPermission.GRANTED -> {
                val list = matches
                when {
                    checking || (list == null && failure == null) -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.accentFill)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.bipe_app_contacts_loading), color = colors.inkSecondary)
                    }
                    failure != null -> {
                        Text(failure.orEmpty(), color = colors.trustDangerInk)
                        TextButton(onClick = ::check) { Text(stringResource(R.string.bipe_app_retry)) }
                    }
                    list.isNullOrEmpty() -> Text(stringResource(R.string.bipe_contacts_none), color = colors.inkSecondary)
                    else -> list.orEmpty().forEach { match ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(match.person.displayName, match.person.avatarUrl, people.media, size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(match.person.displayName, color = colors.inkPrimary, maxLines = 1)
                                if (match.joinedWithInvite) {
                                    Text(stringResource(R.string.bipe_contacts_joined_with_invite), style = MaterialTheme.typography.bodySmall, color = colors.accentInk)
                                } else {
                                    match.person.handle?.let { Text("@$it", style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary) }
                                }
                            }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    runCatching { people.openOrCreateDm(match.person.userId) }
                                        .onSuccess(openChat)
                                        .onFailure { error ->
                                            notice = if (error is PeopleException) {
                                                context.getString(R.string.bipe_app_group_invite_refused, match.person.displayName)
                                            } else {
                                                context.getString(R.string.bipe_error_generic)
                                            }
                                        }
                                }
                            }) { Text(stringResource(R.string.bipe_invites_chat)) }
                        }
                    }
                }
                TextButton(onClick = ::check, enabled = !checking) { Text(stringResource(R.string.bipe_app_retry)) }
            }
        }
        invite?.let { IconRow(PhosphorIcon.USER_PLUS, stringResource(R.string.bipe_contacts_invite), onClick = it) }
    }
}

/** Spec profile-link-open: finds the account with exactly this @ and opens the person (ADR 0013, ADR 0041). */
@Composable
fun ProfileLinkOpenScreen(people: PeopleService, handle: String, openPerson: (String) -> Unit, openOwnLink: () -> Unit, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    var phase by remember { mutableStateOf(LinkPhase.RESOLVING) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        phase = LinkPhase.RESOLVING
        runCatching { people.withToken { people.api.handle(handle, it) } }
            .onSuccess { record -> if (record.userId == people.session.userId) openOwnLink() else openPerson(record.userId) }
            .onFailure { error ->
                val status = (error as? BipeApiException.Server)?.statusCode ?: (error as? BipeApiException.UnexpectedResponse)?.statusCode
                phase = if (status == 404) LinkPhase.NOT_FOUND else LinkPhase.FAILED
            }
    }
    PeopleScaffold(stringResource(R.string.bipe_app_profile_link), BipeScreen.PROFILE_LINK_OPEN.id, onClose) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (phase) {
                LinkPhase.NOT_FOUND -> Text(stringResource(R.string.bipe_link_handle_not_found, handle), color = colors.inkPrimary, textAlign = TextAlign.Center)
                LinkPhase.FAILED -> {
                    Text(stringResource(R.string.bipe_error_network), color = colors.inkPrimary, textAlign = TextAlign.Center)
                    Button(onClick = { attempt++ }) { Text(stringResource(R.string.bipe_app_retry)) }
                }
                LinkPhase.RESOLVING -> {
                    CircularProgressIndicator(color = colors.accentFill)
                    Text(stringResource(R.string.bipe_app_profile_link_opening), color = colors.inkSecondary)
                }
            }
            if (phase != LinkPhase.RESOLVING) TextButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_close)) }
        }
    }
}
