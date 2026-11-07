// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.people

import chat.bipe.app.common.BipeToasts
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import chat.bipe.app.common.BipeButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import chat.bipe.app.business.BusinessProfileButtons
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.OfficialBadge
import chat.bipe.app.common.PersonSealButton
import chat.bipe.app.common.bipeTapTarget
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeOfficialAccounts
import chat.bipe.shared.BipeProfileLink
import chat.bipe.shared.BipeSocialHandles
import chat.bipe.shared.NameNormalizer
import chat.bipe.shared.BipeTrustSources
import chat.bipe.shared.R
import chat.bipe.shared.api.publicProfile
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.SocialPlatform
import chat.bipe.shared.company.BipeCompanySealState
import chat.bipe.shared.company.BipePersonProof
import chat.bipe.shared.company.BipePersonSealState
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

/** Type SocialLink of spec person-profile (ADR 0228). [url] is socialUrl, built on the device; the server sends no URL. */
data class SocialLink(val platform: SocialPlatform, val handle: String, val url: String) {
    companion object {
        /** The links of PublicProfile.socials in registry order. Unknown ids and empty handles are skipped. */
        fun links(socials: Map<String, String>): List<SocialLink> = BipeRegistries.profileSocials.mapNotNull { platform ->
            val handle = socials[platform.id]?.let(BipeSocialHandles::clean)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SocialLink(platform, handle, BipeSocialHandles.url(platform, handle))
        }
    }
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
        /** The person seal (bipe#596). */
        val personSeal: BipePersonSealState = BipePersonSealState.None,
        /** proofs of the spec, strongest first (ADR 0275). */
        val proofs: List<BipePersonProof> = emptyList(),
        /** bipe_banner_unverified_title: no proof and no company mark, once the store answered. */
        val showsUnverifiedLine: Boolean = false,
        /** The SDK's identity of the person (spec facts). null while not read, or when the SDK could not read it. */
        val trustIdentity: BipeTrustSources.Identity? = null,
        /** The identity read has run at least once: a null trustIdentity then means lifecycle unknown. */
        val identityRead: Boolean = false,
        /** facts of the spec: identity proof, personal confirmation and changes, apart (ADR 0074, BipeTrustSources). */
        val facts: BipeTrustFacts = BipeTrustSources.profileFacts("", null, BipeTrustSources.Seal.None),
        val reservedNameWarning: Boolean = false,
        val bio: String? = null,
        /** The public profile photo, for the avatar when the server hides the Matrix profile. */
        val publicPhotoUrl: String? = null,
        /** Spec socials: empty shows no section. */
        val socials: List<SocialLink> = emptyList(),
        val dmRoomId: String? = null,
        /** Spec savedNumbers (ADR 0191): the numbers the user saved for this person in the own address book. Empty: no row. */
        val savedNumbers: List<String> = emptyList(),
        val openingChat: Boolean = false,
    )

    private val _state = MutableStateFlow(State(person = Person(userId, Person.name(userId, null)), isOfficial = BipeOfficialAccounts.isOfficial(userId)))
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
            // Spec savedNumbers: may run one address-book lookup first, so it fills in on its own.
            scope.launch { _state.value = _state.value.copy(savedNumbers = people.savedNumbers(userId)) }
            readIdentityNow()
            scope.launch {
                loadedProofs = people.seals.proofs(userId)
                applySeal(people.seals.seal(userId))
            }
            // The seal store answers later and emits; it fails closed.
            scope.launch { people.seals.states.collect { applySeal(people.seals.seal(userId)) } }
            scope.launch { people.seals.personSeals.collect { applySeal(people.seals.seal(userId)) } }
            val profile = person.handle?.let { handle -> runCatching { people.api.publicProfile(handle) }.getOrNull() }
            val bio = profile?.bio?.trim()
            _state.value = _state.value.copy(bio = bio?.takeIf { it.isNotEmpty() }, publicPhotoUrl = profile?.photoUrl, socials = SocialLink.links(profile?.socials.orEmpty()))
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

    /** facts, marks and reservedNameWarning of the spec. */
    private var loadedProofs: List<BipePersonProof> = emptyList()

    private fun applySeal(seal: BipeCompanySealState) {
        val current = _state.value
        val personSeal = people.seals.personSeal(userId)
        val sealSource = when {
            personSeal is BipePersonSealState.Verified -> BipeTrustSources.Seal.Verified(personSeal.payload.name)
            seal is BipeCompanySealState.Verified -> BipeTrustSources.Seal.Verified(seal.payload.name)
            personSeal == BipePersonSealState.Suspended || seal == BipeCompanySealState.Suspended -> BipeTrustSources.Seal.Suspended
            else -> BipeTrustSources.Seal.None
        }
        val facts = BipeTrustSources.profileFacts(userId, current.trustIdentity, sealSource)
        // A failed proofs call falls back on the compact seal. A suspended or a missing seal gives none (ADR 0275 points 2 and 14).
        val strongest = people.seals.strongestProof(userId)
        val proofs = when {
            facts.identity != "verified" || strongest == null -> emptyList()
            loadedProofs.isEmpty() -> listOf(strongest)
            else -> loadedProofs
        }
        val marks = mutableListOf<TrustMark>()
        when {
            personSeal is BipePersonSealState.Verified ->
                // Only when the badge name differs from the display name: else the header already shows it (spec notes).
                facts.verifiedName
                    ?.takeIf { NameNormalizer.normalize(it) != NameNormalizer.normalize(current.person.displayName) }
                    ?.let { name ->
                        proofs.firstOrNull()?.let { marks += TrustMark(TrustMark.Kind.VERIFIED_NAME, context.getString(R.string.bipe_badge_verified_name_via, it.schemeName(context), name)) }
                    }
            personSeal == BipePersonSealState.Suspended -> marks += TrustMark(TrustMark.Kind.NAME_SUSPENDED, context.getString(R.string.bipe_badge_suspended))
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
        // An official account has its own badge, and the own profile shows no facts.
        val unverified = people.seals.hasAnswer(userId) && userId != people.session.userId && !current.isOfficial &&
            proofs.isEmpty() && marks.none { it.kind != TrustMark.Kind.CONFIRMED_IN_PERSON }
        _state.value = current.copy(
            seal = seal, personSeal = personSeal, proofs = proofs, showsUnverifiedLine = unverified,
            facts = facts, marks = marks, reservedNameWarning = reserved,
        )
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

    fun showNotice(text: String) {
        _state.value = _state.value.copy(openingChat = false)
        BipeToasts.show(text)
    }
}

/** What person-profile opens outside the people screens. A route the app does not have yet hides its row. */
data class PersonProfileRoutes(
    val openChat: (String) -> Unit,
    val openReport: (roomId: String) -> Unit,
    val newGroup: ((String) -> Unit)? = null,
    val notifications: ((String) -> Unit)? = null,
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
    var openedProof by remember { mutableStateOf<BipePersonProof?>(null) }
    var showsGallery by remember { mutableStateOf(false) }
    var targets by remember { mutableStateOf<List<InviteTarget>?>(null) }

    PeopleScaffold(
        title = if (fromDm) stringResource(R.string.bipe_dm_details_title) else state.person.displayName,
        testTag = BipeScreen.PERSON_PROFILE.id,
        onBack = onBack,
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
                BipeButton(onClick = model::appear) { Text(stringResource(R.string.bipe_app_retry)) }
            }
            PersonProfileModel.Phase.LOADED -> {
                Header(model, state, isBlocked, onOpenSeal = { showsSeal = true }, onOpenProof = { openedProof = it })
                // Spec business-chat-bar: a company's tools under its name (ADR 0103: Agendar).
                if (state.isCompany) BusinessProfileButtons(people.session, people.media, userId)
                SocialLinks(state.socials)
                SavedNumbers(state.savedNumbers)
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
                // Spec chat-media-gallery (bipe-chat/bipe#672).
                if (fromDm && dmRoomId != null) {
                    RowGroup {
                        IconRow(PhosphorIcon.IMAGE, stringResource(R.string.bipe_gallery_title)) { showsGallery = true }
                    }
                    if (showsGallery) {
                        chat.bipe.app.media.ChatMediaGalleryScreen(people.session, people.media, dmRoomId, onClose = { showsGallery = false })
                    }
                }
                // Spec person-profile notes: a DM is archived from the chat menu, never deleted here.
                val notifications = routes.notifications
                if (fromDm && dmRoomId != null && notifications != null) {
                    RowGroup {
                        IconRow(PhosphorIcon.BELL, stringResource(R.string.bipe_settings_notifications)) { notifications(dmRoomId) }
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
    // Fail closed: the sheet closes when the badge stops verifying.
    openedProof?.takeIf { state.proofs.isNotEmpty() }?.let { PersonSealDialog(it, onClose = { openedProof = null }) }
    targets?.let { list ->
        InviteTargetDialog(list, onPick = { targets = null; model.invite(it) }, onClose = { targets = null })
    }
}

@Composable
private fun Header(model: PersonProfileModel, state: PersonProfileModel.State, isBlocked: Boolean, onOpenSeal: () -> Unit, onOpenProof: (BipePersonProof) -> Unit) {
    val colors = BipeTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Avatar(state.person.displayName, state.person.avatarUrl, model.people.media, size = 96.dp, userId = state.person.userId, isCompany = state.isCompany, publicPhotoUrl = state.publicPhotoUrl)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                state.person.displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colors.inkPrimary,
                textAlign = TextAlign.Center,
            )
            if (state.isOfficial) OfficialBadge(style = MaterialTheme.typography.labelMedium)
            if (state.facts.identity == "verified" && state.seal !is BipeCompanySealState.Verified) {
                // Spec person-seal: a tap opens the strongest proof (ADR 0275 point 4).
                PersonSealButton(state.person.userId, seals = model.people.seals, size = 22.dp)
            } else if (state.facts.identity == "verified") {
                Icon(
                    painterResource(PhosphorIcon.SEAL_CHECK.drawable),
                    contentDescription = stringResource(R.string.bipe_badge_company),
                    tint = colors.trustVerifiedInk,
                    modifier = Modifier.bipeTapTarget().size(22.dp).clickable(role = Role.Button, onClick = onOpenSeal),
                )
            }
        }
        // Spec brand-mark: next to the reseller's own name, never instead of it.
        BrandMarkLine(state.person.userId, model.people.brandMarks, model.people.seals)
        state.person.handle?.let { Text("@$it", style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary) }
        if (model.userId == BipeRegistries.groupsAccountId) {
            Text(stringResource(R.string.bipe_groups_service_about), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary, textAlign = TextAlign.Center)
        }
        if (state.reservedNameWarning) {
            MarkLine(PhosphorIcon.WARNING, stringResource(R.string.bipe_warning_reserved_name), colors.trustCautionInk)
        }
        // Spec person-profile, list of facts (ADR 0275 points 12 to 16): identity, then the facts, then the confirmation.
        val context = LocalContext.current
        val orderedMarks = state.marks.partition { it.kind != TrustMark.Kind.CONFIRMED_IN_PERSON }
        @Composable
        fun line(mark: TrustMark) {
            when (mark.kind) {
                TrustMark.Kind.COMPANY -> MarkLine(PhosphorIcon.SEAL_CHECK, mark.text, colors.trustVerifiedInk, onClick = onOpenSeal)
                TrustMark.Kind.VERIFIED_NAME -> MarkLine(PhosphorIcon.SEAL_CHECK, mark.text, colors.trustVerifiedInk)
                TrustMark.Kind.CONFIRMED_IN_PERSON -> MarkLine(PhosphorIcon.HANDSHAKE, mark.text, colors.trustVerifiedInk)
                else -> MarkLine(PhosphorIcon.SEAL_WARNING, mark.text, colors.trustCautionInk)
            }
        }
        orderedMarks.first.forEach { line(it) }
        state.proofs.forEach { proof ->
            MarkLine(
                if (proof.isFill) PhosphorIcon.SEAL_CHECK_FILL else PhosphorIcon.SEAL_CHECK,
                proof.line(context),
                colors.trustVerifiedInk,
                onClick = { onOpenProof(proof) },
            )
        }
        if (state.showsUnverifiedLine) {
            MarkLine(PhosphorIcon.USER_CIRCLE, stringResource(R.string.bipe_banner_unverified_title), colors.trustNeutralInk)
        }
        // Not on the own profile.
        if (model.userId != model.people.session.userId) ProfileFactsLines(model.people, model.userId, alwaysCreated = true)
        orderedMarks.second.forEach { line(it) }
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

/**
 * Spec socials (ADR 0228): under the bio, one row per network. A tap opens the link in the system browser, which hands
 * off to the network's app; never an in-app web view, never a link preview. Empty: no section.
 */
@Composable
private fun SocialLinks(links: List<SocialLink>) {
    if (links.isEmpty()) return
    val colors = BipeTheme.colors
    val context = LocalContext.current
    RowGroup {
        Text(
            stringResource(R.string.bipe_public_profile_socials),
            style = MaterialTheme.typography.titleSmall,
            color = colors.inkSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        links.forEach { link ->
            ListItem(
                headlineContent = { Text("${link.platform.name} · @${link.handle}", style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary) },
                trailingContent = { Icon(painterResource(PhosphorIcon.LINK.drawable), contentDescription = null, tint = colors.inkSecondary, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    // Action openSocial.
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                },
            )
        }
        Text(
            stringResource(R.string.bipe_public_profile_socials_not_verified),
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Spec savedNumbers (ADR 0191, bipe-chat/bipe#788): only from the user's own address book. Empty: no row. */
@Composable
private fun SavedNumbers(numbers: List<String>) {
    if (numbers.isEmpty()) return
    val colors = BipeTheme.colors
    RowGroup {
        SelectionContainer {
            Column {
                numbers.forEach { number ->
                    ListItem(
                        overlineContent = { Text(stringResource(R.string.bipe_person_number), color = colors.inkSecondary) },
                        headlineContent = { Text(number, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary) },
                    )
                }
            }
        }
        Text(
            stringResource(R.string.bipe_person_number_footer),
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
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
                    targets.forEach { target -> IconRow(PhosphorIcon.USERS_THREE, target.name, containerColor = colors.surfaceRaised) { onPick(target) } }
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
    if (state.personSeal == BipePersonSealState.Suspended) FactCaption(stringResource(R.string.bipe_badge_suspended_body))
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
