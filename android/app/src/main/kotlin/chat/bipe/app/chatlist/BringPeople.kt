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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.BipeButton
import chat.bipe.app.common.bipeTapTarget
import chat.bipe.app.invites.dateLabel
import chat.bipe.app.invites.shareInvite
import chat.bipe.app.people.PeopleScaffold
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeCreatedInvite
import chat.bipe.shared.api.BipeInvite
import chat.bipe.shared.api.createInvite
import chat.bipe.shared.api.myInvites
import chat.bipe.shared.api.nameInvite
import chat.bipe.shared.generated.BipeOnboarding
import chat.bipe.shared.generated.BipeOnboardingPrompt
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.invites.BipeInviteMemory
import chat.bipe.shared.onboarding.BipeBringPeople
import chat.bipe.shared.onboarding.BipeBringPeopleMemory
import chat.bipe.shared.onboarding.BipePromptSlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Specs bring-people and bring-people-reminder (bipe-chat/bipe#752): up to 4 named personal invites, then at most
 * one reminder a week that names one person. One model per account. The iOS counterpart is `BringPeopleModel`.
 */
class BringPeopleModel(context: Context, val session: AccountSession, private val api: BipeApiClient) {
    enum class Status { EMPTY, SENT, JOINED, EXPIRED }

    data class Slot(val index: Int, val inviteId: String?, val name: String?, val status: Status, val joinedUserId: String?)

    sealed interface Phase {
        data object Loading : Phase
        data object Ready : Phase
        /** One slot's invite is being made. */
        data class Creating(val index: Int) : Phase
        /** bipe_error_network or bipe_error_invite_quota. */
        data class Failed(val quota: Boolean) : Phase
    }

    data class Reminder(val index: Int, val name: String, val kind: BipeBringPeople.Kind)

    data class State(
        val slots: List<Slot> = emptyList(),
        val available: Int? = null,
        val nextAvailableAt: String? = null,
        val phase: Phase = Phase.Loading,
        val reminder: Reminder? = null,
        /** The slot whose name field is open. One slot edits at a time. */
        val editing: Int? = null,
    ) {
        /** The slots the sheet shows: filled ones, then the empty ones the invite quota allows. */
        val visibleSlots: List<Slot>
            get() = slots.filter { it.status != Status.EMPTY } + slots.filter { it.status == Status.EMPTY }.take(maxOf(available ?: 0, 0))
        val anyFilled: Boolean get() = slots.any { it.status != Status.EMPTY }
    }

    val memory = BipeBringPeopleMemory(context, session.userId)
    private val promptSlot = BipePromptSlot(context, session.userId)
    private val links = BipeInviteMemory(context, session.userId)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var invites: Map<String, BipeInvite> = emptyMap()
    private var savedLinks: Map<String, String> = emptyMap()
    /** The result of POST for a slot, so a retry repeats only the name and never makes a second invite. */
    private var created: Pair<Int, BipeCreatedInvite>? = null

    fun edit(index: Int?) { _state.value = _state.value.copy(editing = index) }

    /** Action appear: GET /v1/me/invites, then each slot's status from its invite. */
    suspend fun load() {
        try {
            val quota = withToken { api.myInvites(it) }
            invites = quota.invites.associateBy { it.id }
            memory.keep(quota.invites.filter { it.status != "revoked" }.map { it.id }.toSet())
            savedLinks = runCatching { links.read().links }.getOrDefault(emptyMap())
            val phase = _state.value.phase.takeIf { it is Phase.Creating } ?: Phase.Ready
            _state.value = _state.value.copy(available = quota.available, nextAvailableAt = quota.nextAvailableAt, phase = phase)
            rebuild()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            if (_state.value.slots.isEmpty()) rebuild()
            _state.value = _state.value.copy(phase = Phase.Failed(quota = false))
        }
    }

    private fun rebuild() {
        val ids = memory.inviteIds
        val slots = (0 until BipeOnboarding.BRING_PEOPLE_SLOTS).map { index ->
            val invite = ids.getOrNull(index)?.let(invites::get)
                ?: return@map Slot(index, null, null, Status.EMPTY, null)
            val status = when (invite.status) {
                "pending" -> Status.SENT
                "used" -> Status.JOINED
                else -> Status.EXPIRED
            }
            Slot(index, invite.id, invite.name, status, invite.joinedUserId)
        }
        _state.value = _state.value.copy(slots = slots)
        refreshReminder()
    }

    /** Actions invite and replaceExpired: POST /v1/me/invites, then PUT the name. Returns the link to share. */
    suspend fun invite(index: Int, name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        _state.value = _state.value.copy(phase = Phase.Creating(index))
        return try {
            val made = created?.takeIf { it.first == index }?.second
                ?: withToken { api.createInvite(it) }.also { created = index to it }
            links.saveCreated(made.id, made.url, note = "")
            memory.setInvite(made.id, index)
            withToken { api.nameInvite(made.id, trimmed, it) }
            created = null
            _state.value = _state.value.copy(phase = Phase.Ready, editing = null)
            load()
            made.url
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val quota = error is BipeApiException.Server && error.code == BipeApiErrorCode.INVITE_QUOTA_EXHAUSTED
            _state.value = _state.value.copy(phase = Phase.Failed(quota))
            null
        }
    }

    /** Action resend: the link kept on this phone, or null when the invite was made on another phone. */
    fun link(index: Int): String? = _state.value.slots.getOrNull(index)?.inviteId?.let(savedLinks::get)

    /** Action openJoined: the DM with the person who joined. */
    fun dmRoomId(index: Int): String? {
        val userId = _state.value.slots.getOrNull(index)?.joinedUserId ?: return null
        return runCatching { session.client.getDmRoom(userId) }.getOrNull()?.id()
    }

    /** The sheet showed. The first time it counts as the first ask. */
    fun sheetShown() = memory.asked(sheet = true)

    /** Action notNow of the sheet: one decline. Shown only while no slot is filled. */
    fun notNow() {
        memory.decline()
        refreshReminder()
    }

    /** Trigger bringPeopleDue (BipeBringPeople.reminder) and the prompt slot. */
    fun refreshReminder() {
        val filled = _state.value.slots.filter { it.inviteId != null }
        val ruleSlots = filled.map { slot ->
            val invite = slot.inviteId?.let(invites::get)
            BipeBringPeople.RuleSlot(invite?.status ?: "revoked", invite?.createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: Long.MAX_VALUE)
        }
        val pick = if (promptSlot.allows(BipeOnboardingPrompt.BRING_PEOPLE_REMINDER)) {
            BipeBringPeople.reminder(memory.declines, memory.lastAskAt, ruleSlots, System.currentTimeMillis())
        } else null
        val name = pick?.let { filled[it.slot].name }?.takeIf { it.isNotBlank() }
        _state.value = _state.value.copy(reminder = if (pick != null && name != null) Reminder(filled[pick.slot].index, name, pick.kind) else null)
    }

    fun reminderShown() = promptSlot.didShow(BipeOnboardingPrompt.BRING_PEOPLE_REMINDER)

    /** Action act: pending gives the kept link (null: the invite is on another phone); expired makes a new invite. */
    suspend fun act(): String? {
        val reminder = _state.value.reminder ?: return null
        memory.asked()
        _state.value = _state.value.copy(reminder = null)
        return when (reminder.kind) {
            BipeBringPeople.Kind.PENDING -> link(reminder.index)
            BipeBringPeople.Kind.EXPIRED -> invite(reminder.index, reminder.name)
        }
    }

    /** Action notNow of the reminder: one decline. */
    fun declineReminder() {
        memory.decline()
        _state.value = _state.value.copy(reminder = null)
    }

    private suspend fun <T> withToken(request: suspend (String) -> T): T = BipeApiClient.withTokenRetry({ session.accessToken() }, request)
}

/** Spec bring-people: "Quem da sua família devia estar aqui?" with up to 4 slots. */
@Composable
fun BringPeopleScreen(
    model: BringPeopleModel,
    onClose: () -> Unit,
    openChat: (String) -> Unit,
    openPerson: (String) -> Unit,
    openInvites: () -> Unit,
) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by model.state.collectAsState()
    var name by remember { mutableStateOf("") }
    LaunchedEffect(model) { model.load() }
    fun invite(index: Int, value: String) = scope.launch {
        model.invite(index, value)?.let {
            name = ""
            shareInvite(context, it)
        }
    }
    PeopleScaffold(stringResource(R.string.bipe_bring_people_title), BipeScreen.BRING_PEOPLE.id, onClose) {
        Text(stringResource(R.string.bipe_bring_people_body), color = colors.inkSecondary)
        if (state.phase == BringPeopleModel.Phase.Loading && state.slots.isEmpty()) CircularProgressIndicator(color = colors.accentFill)
        state.visibleSlots.forEach { slot ->
            Column(
                modifier = Modifier.fillMaxWidth().background(colors.surfaceRaised, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    state.phase == BringPeopleModel.Phase.Creating(slot.index) -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(color = colors.accentFill)
                        Text(stringResource(R.string.bipe_app_invite_creating), color = colors.inkSecondary)
                    }
                    slot.status == BringPeopleModel.Status.EMPTY && state.editing == slot.index -> {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.bipe_bring_people_name_placeholder)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { invite(slot.index, name) }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                        BipeButton(onClick = { invite(slot.index, name) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.bipe_first_card_invite_action))
                        }
                    }
                    slot.status == BringPeopleModel.Status.EMPTY -> TextButton(
                        onClick = { name = ""; model.edit(slot.index) },
                        enabled = state.editing == null,
                    ) { Text(stringResource(R.string.bipe_bring_people_slot_empty)) }
                    slot.status == BringPeopleModel.Status.SENT -> {
                        SlotHeader(slot, stringResource(R.string.bipe_bring_people_sent))
                        TextButton(onClick = {
                            val link = model.link(slot.index)
                            if (link != null) shareInvite(context, link) else { onClose(); openInvites() }
                        }) { Text(stringResource(R.string.bipe_bring_people_resend)) }
                    }
                    slot.status == BringPeopleModel.Status.JOINED -> Column(
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                            val roomId = model.dmRoomId(slot.index)
                            val userId = slot.joinedUserId
                            if (roomId != null) { onClose(); openChat(roomId) }
                            else if (userId != null) { onClose(); openPerson(userId) }
                        },
                    ) { SlotHeader(slot, stringResource(R.string.bipe_bring_people_joined)) }
                    else -> {
                        SlotHeader(slot, stringResource(R.string.bipe_bring_people_expired))
                        TextButton(onClick = { slot.name?.let { invite(slot.index, it) } }) {
                            Text(stringResource(R.string.bipe_bring_people_expired_action))
                        }
                    }
                }
            }
        }
        // State available 0: the empty slots stay hidden and the sheet says why.
        if (state.available == 0 && state.slots.any { it.status == BringPeopleModel.Status.EMPTY }) {
            val next = state.nextAvailableAt?.let { stringResource(R.string.bipe_invites_next, dateLabel(it)) }
            Text(listOfNotNull(stringResource(R.string.bipe_invites_none_left), next).joinToString(" "),
                style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        }
        (state.phase as? BringPeopleModel.Phase.Failed)?.let { failed ->
            Text(stringResource(if (failed.quota) R.string.bipe_error_invite_quota else R.string.bipe_error_network), color = colors.trustDangerInk)
            TextButton(onClick = {
                val editing = state.editing
                if (editing != null && name.isNotBlank()) invite(editing, name) else scope.launch { model.load() }
            }) { Text(stringResource(R.string.bipe_app_retry)) }
        }
        BipeButton(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(stringResource(R.string.bipe_bring_people_done))
        }
        if (!state.anyFilled) {
            TextButton(onClick = { model.notNow(); onClose() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bipe_fallback_skip))
            }
        }
    }
}

@Composable
private fun SlotHeader(slot: BringPeopleModel.Slot, label: String) {
    val colors = BipeTheme.colors
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        slot.name?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary) }
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = if (slot.status == BringPeopleModel.Status.JOINED) colors.statusSuccess else colors.inkSecondary)
    }
}

/** Spec bring-people-reminder: the card above Conversas that names one person. Same shape as the PIN reminder card. */
@Composable
fun BringPeopleReminderCard(model: BringPeopleModel, reminder: BringPeopleModel.Reminder, openInvites: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prompt = BipeOnboardingPrompt.BRING_PEOPLE_REMINDER
    val pending = reminder.kind == BipeBringPeople.Kind.PENDING
    LaunchedEffect(reminder) { model.reminderShown() }
    PromptCard(
        title = stringResource(R.string.bipe_bring_people_reminder_title, reminder.name),
        body = stringResource(if (pending) R.string.bipe_bring_people_reminder_pending else R.string.bipe_bring_people_reminder_expired),
        primary = PromptAction(stringResource(if (pending) R.string.bipe_bring_people_resend else R.string.bipe_bring_people_expired_action)) {
            scope.launch {
                val link = model.act()
                if (link != null) shareInvite(context, link) else if (pending) openInvites()
            }
        },
        texts = prompt.text.map { PromptAction(stringResource(it.title)) { model.declineReminder() } },
        modifier = Modifier.testTag(BipeScreen.BRING_PEOPLE_REMINDER.id),
    )
}
