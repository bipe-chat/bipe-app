// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.people

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.business.QUEUE_LINK_QUERY
import chat.bipe.app.business.QueueLinkScreen
import chat.bipe.app.media.stickers.StickerPackScreen
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeProfileLink
import chat.bipe.shared.R
import chat.bipe.stickers.StickerPackRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Profile links that arrive from the system (app links, a scanned QR code) wait here until the home screen shows. */
object PeopleLinks {
    private val _pending = MutableStateFlow<PeopleRoute?>(null)
    val pending: StateFlow<PeopleRoute?> = _pending.asStateFlow()

    /**
     * Takes [uri] when it is a profile link (bipe.chat/@handle or a matrix.to user link). A business's counter QR link,
     * bipe.chat/@handle?fila, opens queue-customer. A pack link bipe.chat/s/<room ID> opens sticker-pack.
     */
    fun offer(uri: Uri): Boolean {
        val queue = runCatching { QUEUE_LINK_QUERY in uri.queryParameterNames }.getOrDefault(false)
        val route = StickerPackRef.fromLink(uri.toString())?.let { PeopleRoute.StickerPackLink(it) }
            ?: BipeProfileLink.handle(uri.toString())?.let { PeopleRoute.ProfileLinkOpen(it, queue) }
            ?: BipeProfileLink.matrixUserId(uri.toString())?.let { PeopleRoute.PersonProfile(it, fromDm = false) }
            ?: return false
        _pending.value = route
        return true
    }

    fun take(): PeopleRoute? = _pending.value.also { _pending.value = null }
}

/** What the people screens open outside themselves. */
class PeopleNavigation(
    val push: (PeopleRoute) -> Unit,
    val pop: () -> Unit,
    /** Closes the people screens and opens chat-timeline. */
    val openChat: (String) -> Unit,
    /** Opens handle-pick. */
    val pickHandle: () -> Unit,
    /** person-profile newGroup: group-create with the person picked. `null` hides the row. */
    val newGroup: ((String) -> Unit)? = null,
    /** person-profile notifications (fromDm): chat-notifications for the DM. */
    val notifications: ((String) -> Unit)? = null,
    /** person-profile deleteChat (fromDm): dm-delete for the DM. */
    val deleteChat: ((String) -> Unit)? = null,
    /** contacts invite: opens invites. */
    val invite: (() -> Unit)? = null,
)

/** The screen of [route]. */
@Composable
fun PeopleScreen(route: PeopleRoute, people: PeopleService, navigation: PeopleNavigation) {
    when (route) {
        is PeopleRoute.PersonProfile -> PersonProfileScreen(
            people,
            route.userId,
            route.fromDm,
            PersonProfileRoutes(
                openChat = navigation.openChat,
                openReport = { roomId -> navigation.push(PeopleRoute.ReportChat(roomId, isDm = true, otherUserId = route.userId)) },
                newGroup = navigation.newGroup,
                notifications = navigation.notifications,
                deleteChat = navigation.deleteChat,
            ),
            onBack = navigation.pop,
        )
        PeopleRoute.ProfileEdit -> ProfileEditScreen(people, onBack = navigation.pop)
        PeopleRoute.PublicProfile -> PublicProfileEditScreen(
            people,
            pickHandle = navigation.pickHandle,
            openLink = { navigation.push(PeopleRoute.ProfileLink) },
            onBack = navigation.pop,
        )
        PeopleRoute.ProfileQr -> ProfileLinkScreen(people, pickHandle = navigation.pickHandle, onBack = navigation.pop, showQr = true)
        PeopleRoute.ProfileLink -> ProfileLinkScreen(people, pickHandle = navigation.pickHandle, onBack = navigation.pop)
        is PeopleRoute.ProfileLinkOpen -> if (route.queue) {
            QueueLinkScreen(people.session, route.handle, onClose = navigation.pop)
        } else ProfileLinkOpenScreen(
            people,
            route.handle,
            openPerson = { userId ->
                navigation.pop()
                navigation.push(PeopleRoute.PersonProfile(userId, fromDm = false))
            },
            openOwnLink = {
                navigation.pop()
                navigation.push(PeopleRoute.ProfileLink)
            },
            onClose = navigation.pop,
        )
        PeopleRoute.Contacts -> ContactsScreen(people, openChat = navigation.openChat, invite = navigation.invite, onBack = navigation.pop)
        PeopleRoute.WhoCanMessage -> WhoCanMessageScreen(people, onBack = navigation.pop)
        PeopleRoute.BlockedList -> BlockedListScreen(
            people,
            openPerson = { navigation.push(PeopleRoute.PersonProfile(it, fromDm = false)) },
            onBack = navigation.pop,
        )
        is PeopleRoute.StickerPackLink -> StickerPackScreen(people.session.client, route.ref, people.media, people.handles, onClose = navigation.pop)
        is PeopleRoute.ReportChat -> {
            val context = LocalContext.current
            ReportChatScreen(
                people,
                route.roomId,
                route.isDm,
                route.otherUserId,
                onSent = { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    navigation.pop()
                },
                onBack = navigation.pop,
            )
        }
    }
}

/**
 * The people and profile screens (bipe-chat/bipe#568). Until the Você tab and Privacidade exist (lanes D and E), this
 * menu at the top of chat-list is their way in.
 */
@Composable
fun PeopleMenu(open: (PeopleRoute) -> Unit) {
    val colors = BipeTheme.colors
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(
            painterResource(PhosphorIcon.USER_CIRCLE.drawable),
            contentDescription = stringResource(R.string.bipe_settings_public_profile),
            tint = colors.accentInk,
            modifier = Modifier.size(26.dp),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        listOf(
            Triple(PhosphorIcon.USER_CIRCLE, R.string.bipe_settings_profile_name_photo, PeopleRoute.ProfileEdit),
            Triple(PhosphorIcon.USER_CIRCLE, R.string.bipe_settings_public_profile, PeopleRoute.PublicProfile),
            Triple(PhosphorIcon.QR_CODE, R.string.bipe_app_profile_link, PeopleRoute.ProfileLink),
            Triple(PhosphorIcon.ADDRESS_BOOK, R.string.bipe_contacts_title, PeopleRoute.Contacts),
            Triple(PhosphorIcon.CHAT_TEXT, R.string.bipe_messaging_title, PeopleRoute.WhoCanMessage),
            Triple(PhosphorIcon.PROHIBIT, R.string.bipe_privacy_blocked, PeopleRoute.BlockedList),
        ).forEach { (icon, label, route) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                leadingIcon = { Icon(painterResource(icon.drawable), contentDescription = null, modifier = Modifier.size(20.dp)) },
                onClick = {
                    expanded = false
                    open(route)
                },
            )
        }
    }
}
