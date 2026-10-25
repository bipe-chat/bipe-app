// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.people

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.OfficialBadge
import chat.bipe.app.common.bipeTapTarget
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeOfficialAccounts
import chat.bipe.shared.BipeProfileLink
import chat.bipe.shared.NameNormalizer
import chat.bipe.shared.BipeTrustSources
import chat.bipe.shared.R
import chat.bipe.shared.api.publicProfile
import chat.bipe.shared.company.BipeCompanySealState
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.generated.BipeTrustFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ErrorKind
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MembershipState

/** Type TrustMark of spec person-profile. */
data class TrustMark(val kind: Kind, val text: String) {
    enum class Kind { VERIFIED_NAME, COMPANY, COMPANY_REVOKED, COMPANY_SUSPENDED, CONFIRMED_IN_PERSON, NAME_SUSPENDED }
}

/** A group where the user may invite (action addToGroup). */
data class InviteTarget(val roomId: String, val name: String)

/** Spec person-profile (bipe-shared product/screens/person-profile.json). */
class PersonProfileModel(
    private val context: Context,
    val people: PeopleService,
    val userId: String,
    val fromDm: Boolean,
    private val scope: CoroutineScope,
) {
    enum class Phase { LOADING, LOADED, FAILED }

    data class State(
        val phase: Phase = Phase.LOADING,
        val person: Person,
        val isCompany: Boolean = false,
        /** isOfficial of the spec: the exact user ID is an official Bipe account (ADR 0117). */
        val isOfficial: Boolean = false,
        val marks: List<TrustMark> = emptyList(),
        val seal: BipeCompanySealState = BipeCompanySealState.None,
        /** The SDK's identity of the person (spec facts). null while not read, or when the SDK could not read it. */
        val trustIdentity: BipeTrustSources.Identity? = null,
        /** The identity read has run at least once: a null trustIdentity then means lifecycle unknown. */
        val identityRead: Boolean = false,
        /** facts of the spec: identity proof, personal confirmation and changes, apart (ADR 0074, BipeTrustSources). */
        val facts: BipeTrustFacts = BipeTrustSources.profileFacts("", null, BipeTrustSources.Seal.None),
        val reservedNameWarning: Boolean = false,
        val bio: String? = null,
        val dmRoomId: String? = null,
        val notice: String? = null,
        val openingChat: Boolean = false,
    )

    private val _state = MutableStateFlow(State(person = Person(userId, Person.localpart(userId)), isOfficial = BipeOfficialAccounts.isOfficial(userId)))
    val state: StateFlow<State> = _state.asStateFlow()

    /** Action appear: shows what it has and fills in the rest. */
    fun appear() {
        scope.launch {
            _state.value = _state.value.copy(phase = Phase.LOADING, dmRoomId = people.dmRoomId(userId))
            val person = runCatching { people.person(userId) }.getOrNull()
            if (person == null) {
                _state.value = _state.value.copy(phase = Phase.FAILED)
                return@launch
            }
            _state.value = _state.value.copy(phase = Phase.LOADED, person = person)
            val isCompany = people.isCompany(userId)
            _state.value = _state.value.copy(isCompany = isCompany)
            readIdentityNow()
            // The seal store answers later and emits; it fails closed.
            scope.launch { people.seals.states.collect { applySeal(people.seals.seal(userId)) } }
            val bio = person.handle?.let { handle -> runCatching { people.api.publicProfile(handle) }.getOrNull()?.bio?.trim() }
            _state.value = _state.value.copy(bio = bio?.takeIf { it.isNotEmpty() })
        }
    }

    /** Action retryFacts: reads the person's identity again. */
    fun readIdentity() {
        scope.launch { readIdentityNow() }
    }

    private suspend fun readIdentityNow() {
        val identity = people.trustIdentity(userId)
        _state.value = _state.value.copy(trustIdentity = identity, identityRead = true)
        applySeal(people.seals.seal(userId))
    }

    /** facts, marks and reservedNameWarning of the spec. verifiedName and nameSuspended have no signer yet. */
    private fun applySeal(seal: BipeCompanySealState) {
        val current = _state.value
        val sealSource = when (seal) {
            is BipeCompanySealState.Verified -> BipeTrustSources.Seal.Verified(seal.payload.name)
            BipeCompanySealState.Suspended -> BipeTrustSources.Seal.Suspended
            else -> BipeTrustSources.Seal.None
        }
        val facts = BipeTrustSources.profileFacts(userId, current.trustIdentity, sealSource)
        val marks = mutableListOf<TrustMark>()
        when {
            seal is BipeCompanySealState.Revoked -> marks += TrustMark(TrustMark.Kind.COMPANY_REVOKED, seal.notice(context).orEmpty())
            facts.identity == "verified" -> marks += TrustMark(TrustMark.Kind.COMPANY, context.getString(R.string.bipe_badge_company))
            facts.identity == "suspended" || seal == BipeCompanySealState.Suspended ->
                marks += TrustMark(TrustMark.Kind.COMPANY_SUSPENDED, BipeCompanySealState.Suspended.notice(context).orEmpty())
        }
        if (facts.contact == "confirmed") {
            marks += TrustMark(TrustMark.Kind.CONFIRMED_IN_PERSON, context.getString(R.string.bipe_badge_verified_contact))
        }
        // An official account, or a company whose verified seal names it, does not get the warning.
        val reserved = NameNormalizer.reservedCategory(current.person.displayName) != null &&
            !current.isOfficial && seal !is BipeCompanySealState.Verified
        _state.value = current.copy(seal = seal, facts = facts, marks = marks, reservedNameWarning = reserved)
    }

    /** Action message: the DM, created when missing. */
    fun message(open: (String) -> Unit) {
        if (_state.value.openingChat) return
        _state.value = _state.value.copy(openingChat = true)
        scope.launch {
            try {
                val roomId = people.openOrCreateDm(userId)
                _state.value = _state.value.copy(dmRoomId = roomId, openingChat = false)
                open(roomId)
            } catch (error: PeopleException) {
                showNotice(context.getString(R.string.bipe_app_group_invite_refused, _state.value.person.displayName))
            } catch (error: Exception) {
                showNotice(context.getString(R.string.bipe_error_generic))
            }
        }
    }

    /** Action share: only when the person has an @. */
    fun shareIntent(): Intent? = _state.value.person.handle?.let { handle ->
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, BipeProfileLink.url(handle) ?: return null),
            null,
        )
    }

    /** Action addToGroup, step 1: the user's groups where RoomPowerLevels.canOwnUserInvite. */
    suspend fun inviteTargets(): List<InviteTarget> = people.session.client.rooms().mapNotNull { room ->
        if (room.membership() != Membership.JOINED) return@mapNotNull null
        val info = runCatching { room.roomInfo() }.getOrNull() ?: return@mapNotNull null
        if (info.isDm || info.isDirect || info.isSpace) return@mapNotNull null
        val canInvite = runCatching { room.getPowerLevels().canOwnUserInvite() }.getOrDefault(false)
        if (canInvite) InviteTarget(info.id, info.displayName.orEmpty()) else null
    }.sortedBy { it.name.lowercase() }

    /** Action addToGroup, step 2: invites the person to the chosen group. */
    fun invite(target: InviteTarget) {
        scope.launch {
            val room = runCatching { people.session.client.getRoom(target.roomId) }.getOrNull()
            if (room == null) {
                showNotice(context.getString(R.string.bipe_error_generic))
                return@launch
            }
            val membership = runCatching { room.member(userId).membership }.getOrNull()
            if (membership == MembershipState.Join || membership == MembershipState.Invite) {
                showNotice(context.getString(R.string.bipe_dm_invite_to_group_already_member))
                return@launch
            }
            try {
                room.inviteUserById(userId)
                showNotice(context.getString(R.string.bipe_dm_invite_to_group_sent))
            } catch (error: ClientException.MatrixApi) {
                showNotice(
                    if (error.kind is ErrorKind.Forbidden) {
                        context.getString(R.string.bipe_app_group_invite_refused, _state.value.person.displayName)
                    } else {
                        context.getString(R.string.bipe_error_generic)
                    },
                )
            } catch (error: Exception) {
                showNotice(context.getString(R.string.bipe_error_generic))
            }
        }
    }

    fun showNotice(text: String?) {
        _state.value = _state.value.copy(notice = text, openingChat = false)
    }
}

/** What person-profile opens outside the people screens. A route the app does not have yet hides its row. */
data class PersonProfileRoutes(
    val openChat: (String) -> Unit,
    val openReport: (roomId: String) -> Unit,
    val newGroup: ((String) -> Unit)? = null,
    val notifications: ((String) -> Unit)? = null,
    val deleteChat: ((String) -> Unit)? = null,
)

/** Spec person-profile. From a DM header it is also the DM's info screen (ADR 0141). */
@Composable
fun PersonProfileScreen(people: PeopleService, userId: String, fromDm: Boolean, routes: PersonProfileRoutes, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(userId, fromDm) { PersonProfileModel(context, people, userId, fromDm, scope) }
    LaunchedEffect(model) { model.appear() }
    val state by model.state.collectAsState()
    val ignored by people.ignoredUsers.collectAsState()
    val isBlocked = ignored?.contains(userId) == true
    val colors = BipeTheme.colors
    var showsBlock by remember { mutableStateOf(false) }
    var showsSeal by remember { mutableStateOf(false) }
    var targets by remember { mutableStateOf<List<InviteTarget>?>(null) }

    PeopleScaffold(
        title = if (fromDm) stringResource(R.string.bipe_dm_details_title) else state.person.displayName,
        testTag = BipeScreen.PERSON_PROFILE.id,
        onBack = onBack,
        notice = state.notice,
        onNoticeShown = { model.showNotice(null) },
    ) {
        when (state.phase) {
            PersonProfileModel.Phase.LOADING -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accentFill)
            }
            PersonProfileModel.Phase.FAILED -> Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.bipe_app_person_not_found), color = colors.inkSecondary, textAlign = TextAlign.Center)
                Button(onClick = model::appear) { Text(stringResource(R.string.bipe_app_retry)) }
            }
            PersonProfileModel.Phase.LOADED -> {
                Header(model, state, isBlocked, onOpenSeal = { showsSeal = true })
                RowGroup {
                    IconRow(PhosphorIcon.CHAT_TEXT, stringResource(R.string.bipe_app_person_message), enabled = !state.openingChat) {
                        model.message(routes.openChat)
                    }
                    model.shareIntent()?.let { intent ->
                        IconRow(PhosphorIcon.SHARE_NETWORK, stringResource(R.string.bipe_profile_share)) { context.startActivity(intent) }
                    }
                    IconRow(PhosphorIcon.USER_PLUS, stringResource(R.string.bipe_dm_invite_to_group)) {
                        scope.launch { targets = model.inviteTargets() }
                    }
                    routes.newGroup?.let { newGroup ->
                        IconRow(PhosphorIcon.USERS_THREE, stringResource(R.string.bipe_app_person_new_group)) { newGroup(userId) }
                    }
                }
                val dmRoomId = state.dmRoomId
                if (fromDm && dmRoomId != null && (routes.notifications != null || routes.deleteChat != null)) {
                    RowGroup {
                        routes.notifications?.let { open ->
                            IconRow(PhosphorIcon.BELL, stringResource(R.string.bipe_settings_notifications)) { open(dmRoomId) }
                        }
                        routes.deleteChat?.let { open ->
                            IconRow(PhosphorIcon.TRASH, stringResource(R.string.bipe_dm_delete_action), tint = colors.trustDangerInk) { open(dmRoomId) }
                        }
                    }
                }
                RowGroup {
                    if (isBlocked) {
                        IconRow(PhosphorIcon.PROHIBIT, stringResource(R.string.bipe_action_unblock_person)) { showsBlock = true }
                    } else {
                        IconRow(PhosphorIcon.PROHIBIT, stringResource(R.string.bipe_action_block_person), tint = colors.trustDangerInk) { showsBlock = true }
                    }
                    // Without a DM there is nothing to report: the row is hidden.
                    if (dmRoomId != null) {
                        IconRow(PhosphorIcon.FLAG, stringResource(R.string.bipe_dm_report), tint = colors.trustDangerInk) { routes.openReport(dmRoomId) }
                    }
                }
            }
        }
    }

    if (showsBlock) {
        BlockPersonDialog(people, userId, state.person.displayName, onClose = { showsBlock = false })
    }
    if (showsSeal) {
        CompanySealDialog(state.seal, onClose = { showsSeal = false })
    }
    targets?.let { list ->
        InviteTargetDialog(list, onPick = { targets = null; model.invite(it) }, onClose = { targets = null })
    }
}

@Composable
private fun Header(model: PersonProfileModel, state: PersonProfileModel.State, isBlocked: Boolean, onOpenSeal: () -> Unit) {
    val colors = BipeTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Avatar(state.person.displayName, state.person.avatarUrl, model.people.media, size = 96.dp, userId = state.person.userId, isCompany = state.isCompany)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                state.person.displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colors.inkPrimary,
                textAlign = TextAlign.Center,
            )
            if (state.isOfficial) OfficialBadge(style = MaterialTheme.typography.labelMedium)
            if (state.facts.identity == "verified") {
                Icon(
                    painterResource(PhosphorIcon.SEAL_CHECK.drawable),
                    contentDescription = stringResource(R.string.bipe_badge_company),
                    tint = colors.trustVerifiedInk,
                    modifier = Modifier.bipeTapTarget().size(22.dp).clickable(role = Role.Button, onClick = onOpenSeal),
                )
            }
        }
        state.person.handle?.let { Text("@$it", style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary) }
        if (state.reservedNameWarning) {
            MarkLine(PhosphorIcon.WARNING, stringResource(R.string.bipe_warning_reserved_name), colors.trustCautionInk)
        }
        state.marks.forEach { mark ->
            when (mark.kind) {
                TrustMark.Kind.COMPANY -> MarkLine(PhosphorIcon.SEAL_CHECK, mark.text, colors.trustVerifiedInk, onClick = onOpenSeal)
                TrustMark.Kind.VERIFIED_NAME -> MarkLine(PhosphorIcon.SEAL_CHECK, mark.text, colors.trustVerifiedInk)
                TrustMark.Kind.CONFIRMED_IN_PERSON -> MarkLine(PhosphorIcon.HANDSHAKE, mark.text, colors.trustVerifiedInk)
                else -> MarkLine(PhosphorIcon.SEAL_WARNING, mark.text, colors.trustCautionInk)
            }
        }
        FactLines(state, onRetry = model::readIdentity)
        if (isBlocked) {
            MarkLine(PhosphorIcon.PROHIBIT, stringResource(R.string.bipe_app_person_blocked), colors.trustDangerInk)
        }
        state.bio?.let { bio ->
            Text(bio, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary, textAlign = TextAlign.Center)
            Text(stringResource(R.string.bipe_public_profile_not_verified), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        }
    }
}

/** Action addToGroup: the user's groups where they may invite. */
@Composable
private fun InviteTargetDialog(targets: List<InviteTarget>, onPick: (InviteTarget) -> Unit, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.bipe_dm_invite_to_group)) },
        text = {
            if (targets.isEmpty()) {
                Text(stringResource(R.string.bipe_dm_invite_to_group_empty), color = colors.inkSecondary)
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    targets.forEach { target -> IconRow(PhosphorIcon.USERS_THREE, target.name) { onPick(target) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.bipe_app_cancel)) } },
        containerColor = colors.surfaceRaised,
    )
}

/** Spec block-person: a confirm sheet that blocks a person, or unblocks a blocked one. Blocking does not tell them. */
@Composable
fun BlockPersonDialog(people: PeopleService, userId: String, name: String, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    // Fixed when the sheet opens, so the text does not flip while the ignore list updates.
    val blocks = remember { !people.isBlocked(userId) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!working) onClose() },
        icon = { Icon(painterResource(PhosphorIcon.PROHIBIT.drawable), contentDescription = null, tint = if (blocks) colors.trustDangerInk else colors.accentInk) },
        title = {
            Text(
                if (blocks) stringResource(R.string.bipe_app_block_title, name) else stringResource(R.string.bipe_app_unblock_title, name),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (blocks) R.string.bipe_dm_decline_block_description else R.string.bipe_app_unblock_body), color = colors.inkSecondary)
                if (failed) {
                    Text(stringResource(if (blocks) R.string.bipe_app_block_error else R.string.bipe_app_unblock_error), color = colors.trustDangerInk)
                }
            }
        },
        confirmButton = {
            // Block uses the destructive style; unblock the normal one.
            TextButton(
                enabled = !working,
                onClick = {
                    working = true
                    failed = false
                    scope.launch {
                        val result = runCatching { if (blocks) people.block(userId) else people.unblock(userId) }
                        working = false
                        if (result.isSuccess) onClose() else failed = true
                    }
                },
            ) {
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        stringResource(if (blocks) R.string.bipe_app_block_confirm else R.string.bipe_app_unblock_confirm),
                        color = if (blocks) colors.trustDangerInk else colors.accentInk,
                    )
                }
            }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onClose) { Text(stringResource(R.string.bipe_app_cancel)) } },
        containerColor = colors.surfaceRaised,
    )
}

/** Spec facts: what each fact means, the contact not confirmed yet, a change and an unknown state, each apart. */
@Composable
private fun FactLines(state: PersonProfileModel.State, onRetry: () -> Unit) {
    val colors = BipeTheme.colors
    val facts = state.facts
    if (facts.identity == "verified") FactCaption(stringResource(R.string.bipe_trust_identity_body))
    if (facts.contact == "confirmed") {
        FactCaption(stringResource(R.string.bipe_trust_contact_body))
    } else if (facts.change != "unknown") {
        MarkLine(PhosphorIcon.USER_CIRCLE, stringResource(R.string.bipe_trust_contact_unconfirmed), colors.trustNeutralInk)
    }
    if (facts.change in setOf("identity", "device", "device_and_identity")) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.trustCautionWash, RoundedCornerShape(10.dp))
                .border(1.dp, colors.trustCautionLine, RoundedCornerShape(10.dp))
                .padding(10.dp)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MarkLine(PhosphorIcon.WARNING, stringResource(R.string.bipe_banner_security_change), colors.trustCautionInk)
            Text(stringResource(R.string.bipe_trust_change_confirm_action), style = MaterialTheme.typography.bodySmall, color = colors.inkPrimary)
        }
    }
    if (state.identityRead && facts.change == "unknown") {
        MarkLine(PhosphorIcon.INFO, stringResource(R.string.bipe_trust_lifecycle_unknown), colors.trustNeutralInk)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.bipe_trust_lifecycle_retry_action)) }
    }
    if (facts.identity == "verified" || facts.contact == "confirmed") FactCaption(stringResource(R.string.bipe_trust_warning_absence_body))
}

/** The meaning or limit of a fact, under its mark. */
@Composable
private fun FactCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = BipeTheme.colors.inkSecondary, textAlign = TextAlign.Center)
}
