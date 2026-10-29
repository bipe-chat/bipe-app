// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.voce

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.BuildConfig
import chat.bipe.app.account.AccountSession
import chat.bipe.app.settings.SettingsPage
import chat.bipe.app.tabs.AccountSummary
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BadgeLevel
import chat.bipe.shared.BipeGovbrFlow
import chat.bipe.shared.BipeTrustProfile
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeBadgeLookup
import chat.bipe.shared.api.BipeBusinessClaim
import chat.bipe.shared.api.badge
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Specs trust-card and trust-profile (ADR 0019, ADR 0112): the private Perfil de confiança of the current account.
 * Steps come only from [BipeTrustProfile]. A company account calls `GET /v1/me/business`. A personal account calls
 * `GET /v1/badges/{userId}` only when the build flag `BuildConfig.BIPE_GOVBR` is on (spec trust-card notes).
 */
class TrustCardModel(private val session: AccountSession, private val api: BipeApiClient, private val scope: CoroutineScope) {
    private val _profile = MutableStateFlow<BipeTrustProfile?>(null)
    val profile: StateFlow<BipeTrustProfile?> = _profile.asStateFlow()
    private var account: AccountSummary? = null
    private var business: BipeBusinessClaim? = null
    /** The level of the account's own badge, read without the signature check: only for this owner-only card. */
    private var personBadge: BadgeLevel? = null
    private var load: Job? = null

    /** Rebuilds the ladder for [current]. A failed or offline call keeps the company steps "Em breve"; the card never shows an error. */
    fun update(current: AccountSummary?) {
        val previous = account
        account = current
        if (current == null) {
            _profile.value = null
            return
        }
        if (previous?.userId != current.userId || previous.isCompany != current.isCompany) {
            business = null
            personBadge = null
            load?.cancel()
            if (current.isCompany && current.userId == session.userId) {
                load = scope.launch {
                    val claim = try {
                        BipeApiClient.withTokenRetry({ session.accessToken() }) { api.myBusiness(it) }
                    } catch (_: BipeApiException) {
                        null
                    }
                    if (account?.userId == current.userId) {
                        business = claim
                        recompute()
                    }
                }
            } else if (!current.isCompany && current.userId == session.userId && BuildConfig.BIPE_GOVBR) {
                load = scope.launch {
                    val lookup = try {
                        api.badge(current.userId)
                    } catch (_: BipeApiException) {
                        null
                    }
                    if (account?.userId == current.userId && lookup is BipeBadgeLookup.Envelope) {
                        personBadge = BipeGovbrFlow.unverifiedLevel(lookup.json)
                        recompute()
                    }
                }
            }
        }
        recompute()
    }

    private fun recompute() {
        val current = account ?: return
        _profile.value = BipeTrustProfile.create(
            displayName = current.displayName,
            hasAvatar = current.avatarUrl != null,
            handle = current.handle,
            isCompany = current.isCompany,
            business = business,
            personBadge = personBadge,
            govbrEnabled = BuildConfig.BIPE_GOVBR,
            claimEnabled = true,
        )
    }

    /** Spec govbr-verify, after verified: step 5 shows done at once; the next load reads the badge again. */
    fun govbrVerified(level: BadgeLevel?) {
        if (account?.userId != session.userId) return
        personBadge = level ?: BadgeLevel.GOVBR
        recompute()
    }

    /** After a change on business-claim: reads the claim again. */
    fun reload() {
        val current = account ?: return
        account = null
        update(current)
    }
}

/**
 * What the trust rows can open. [openClaim]: a company account opens business-claim (bipe-chat/bipe#249). [openGovbr]: a
 * personal account with the build flag on opens govbr-verify (bipe-chat/bipe#544), the only entry to that flow.
 */
class TrustActions(
    val addName: () -> Unit,
    val addPhoto: () -> Unit,
    val chooseHandle: () -> Unit,
    val openClaim: (() -> Unit)? = null,
    val openGovbr: (() -> Unit)? = null,
) {
    fun run(item: BipeTrustProfile.ProfileItem) = when (item) {
        BipeTrustProfile.ProfileItem.DISPLAY_NAME -> addName()
        BipeTrustProfile.ProfileItem.HANDLE -> chooseHandle()
        BipeTrustProfile.ProfileItem.AVATAR -> addPhoto()
    }
}

/** Spec trust-card: the card after Anotações. One row when every live step is done. */
fun LazyListScope.TrustCardSection(profile: BipeTrustProfile, actions: TrustActions, openLadder: () -> Unit) {
    item(key = "trust-card") {
        val colors = BipeTheme.colors
        ListItem(
            headlineContent = { Text(stringResource(R.string.bipe_trust_title), color = colors.inkPrimary) },
            supportingContent = {
                Column {
                    if (!profile.liveStepsDone) {
                        LinearProgressIndicator(
                            progress = { profile.progress },
                            color = colors.accentFill,
                            trackColor = colors.lineSubtle,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp),
                        )
                    }
                    Text(progressText(profile), color = colors.inkSecondary)
                }
            },
            leadingContent = { Icon(painterResource(PhosphorIcon.FINGERPRINT.drawable), contentDescription = null, tint = colors.accentInk) },
            trailingContent = { Icon(painterResource(PhosphorIcon.CARET_RIGHT.drawable), contentDescription = null, tint = colors.glyphSecondary, modifier = Modifier.size(16.dp)) },
            colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
            modifier = Modifier.testTag(BipeScreen.TRUST_CARD.id).clickable(onClick = openLadder),
        )
    }
    if (!profile.liveStepsDone) {
        items(profile.missingProfileItems, key = { "trust-card-${it.id}" }) { item ->
            TrustActionRow(item, profile.isCompany, startPadding = 0.dp) { actions.run(item) }
        }
        item(key = "trust-card-footer") {
            Text(stringResource(R.string.bipe_trust_footer), style = MaterialTheme.typography.bodySmall, color = BipeTheme.colors.inkSecondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        }
    }
}

/** Spec trust-profile: every step with what it proves and its status. */
@Composable
fun TrustProfileScreen(profile: BipeTrustProfile, actions: TrustActions, onBack: () -> Unit) {
    val colors = BipeTheme.colors
    SettingsPage(stringResource(R.string.bipe_trust_title), BipeScreen.TRUST_PROFILE.id, onBack = onBack) {
        item(key = "trust-progress") {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                LinearProgressIndicator(
                    progress = { profile.progress },
                    color = colors.accentFill,
                    trackColor = colors.lineSubtle,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(progressText(profile), color = colors.inkSecondary, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
        profile.steps.forEachIndexed { index, step ->
            val claimStep = profile.isCompany && step.id in setOf(BipeTrustProfile.StepId.CNPJ, BipeTrustProfile.StepId.PROOF, BipeTrustProfile.StepId.DOMAIN)
            val openClaim = actions.openClaim
            // Action openGovbr: only a todo step 5 opens govbr-verify.
            val openGovbr = actions.openGovbr?.takeIf {
                !profile.isCompany && step.id == BipeTrustProfile.StepId.GOVBR && step.status == BipeTrustProfile.Status.TODO
            }
            item(key = "trust-step-${step.id.id}") {
                // Action openClaim: every company step opens business-claim, done or not.
                if (claimStep && openClaim != null) Box(Modifier.clickable(role = Role.Button, onClick = openClaim)) { TrustStepRow(index + 1, step, profile.isCompany) }
                else if (openGovbr != null) Box(Modifier.clickable(role = Role.Button, onClick = openGovbr)) { TrustStepRow(index + 1, step, profile.isCompany) }
                else TrustStepRow(index + 1, step, profile.isCompany)
            }
            if (step.id == BipeTrustProfile.StepId.PROFILE && step.status == BipeTrustProfile.Status.TODO) {
                items(profile.missingProfileItems, key = { "trust-step-action-${it.id}" }) { item ->
                    TrustActionRow(item, profile.isCompany, startPadding = 40.dp) { actions.run(item) }
                }
            }
        }
        item(key = "trust-footer") {
            Text(stringResource(R.string.bipe_trust_footer), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary,
                modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun TrustStepRow(number: Int, step: BipeTrustProfile.Step, isCompany: Boolean) {
    val colors = BipeTheme.colors
    val done = step.status == BipeTrustProfile.Status.DONE
    val comingSoon = stringResource(R.string.bipe_trust_coming_soon)
    val doneLabel = stringResource(R.string.bipe_trust_step_done)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                when (step.status) {
                    BipeTrustProfile.Status.COMING_SOON -> stateDescription = comingSoon
                    BipeTrustProfile.Status.DONE -> stateDescription = doneLabel
                    BipeTrustProfile.Status.TODO -> {}
                }
            },
        verticalAlignment = Alignment.Top,
    ) {
        Text(number.toString(), style = MaterialTheme.typography.titleSmall, color = colors.inkSecondary, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(stringResource(stepTitle(step.id)), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
            Text(stringResource(stepBody(step.id, isCompany)), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            if (step.status == BipeTrustProfile.Status.COMING_SOON) {
                Text(comingSoon, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            }
        }
        if (done) {
            // A check, never the Bipe seal (trust-ui.md): the status has an icon, not only a colour.
            Icon(painterResource(PhosphorIcon.CHECK_CIRCLE.drawable), contentDescription = null, tint = colors.statusSuccess,
                modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun TrustActionRow(item: BipeTrustProfile.ProfileItem, isCompany: Boolean, startPadding: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    ListItem(
        headlineContent = { Text(stringResource(actionText(item, isCompany)), color = colors.accentInk) },
        leadingContent = { Icon(painterResource(actionIcon(item).drawable), contentDescription = null, tint = colors.accentInk) },
        colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
        modifier = Modifier.padding(start = startPadding).clickable(onClick = onClick),
    )
}

@Composable
private fun progressText(profile: BipeTrustProfile): String =
    stringResource(R.string.bipe_trust_progress, profile.completedCount.toString(), profile.totalCount.toString())

@StringRes
private fun stepTitle(id: BipeTrustProfile.StepId): Int = when (id) {
    BipeTrustProfile.StepId.ACCOUNT -> R.string.bipe_trust_step_account
    BipeTrustProfile.StepId.PROFILE -> R.string.bipe_trust_step_profile
    BipeTrustProfile.StepId.EMAIL -> R.string.bipe_trust_step_email
    BipeTrustProfile.StepId.PIX -> R.string.bipe_trust_step_pix
    BipeTrustProfile.StepId.GOVBR -> R.string.bipe_trust_step_govbr
    BipeTrustProfile.StepId.CNPJ -> R.string.bipe_trust_step_cnpj
    BipeTrustProfile.StepId.PROOF -> R.string.bipe_trust_step_proof
    BipeTrustProfile.StepId.DOMAIN -> R.string.bipe_trust_step_domain
}

@StringRes
private fun stepBody(id: BipeTrustProfile.StepId, isCompany: Boolean): Int = when (id) {
    BipeTrustProfile.StepId.ACCOUNT -> R.string.bipe_trust_step_account_body
    BipeTrustProfile.StepId.PROFILE -> if (isCompany) R.string.bipe_trust_step_company_profile_body else R.string.bipe_trust_step_profile_body
    BipeTrustProfile.StepId.EMAIL -> R.string.bipe_trust_step_email_body
    BipeTrustProfile.StepId.PIX -> R.string.bipe_trust_step_pix_body
    BipeTrustProfile.StepId.GOVBR -> R.string.bipe_trust_step_govbr_body
    BipeTrustProfile.StepId.CNPJ -> R.string.bipe_trust_step_cnpj_body
    BipeTrustProfile.StepId.PROOF -> R.string.bipe_trust_step_proof_body
    BipeTrustProfile.StepId.DOMAIN -> R.string.bipe_trust_step_domain_body
}

@StringRes
private fun actionText(item: BipeTrustProfile.ProfileItem, isCompany: Boolean): Int = when (item) {
    BipeTrustProfile.ProfileItem.DISPLAY_NAME -> if (isCompany) R.string.bipe_trust_step_profile_add_company_name else R.string.bipe_trust_step_profile_add_name
    BipeTrustProfile.ProfileItem.HANDLE -> R.string.bipe_trust_step_profile_add_handle
    BipeTrustProfile.ProfileItem.AVATAR -> if (isCompany) R.string.bipe_trust_step_profile_add_logo else R.string.bipe_trust_step_profile_add_photo
}

private fun actionIcon(item: BipeTrustProfile.ProfileItem): PhosphorIcon = when (item) {
    BipeTrustProfile.ProfileItem.DISPLAY_NAME -> PhosphorIcon.PENCIL_SIMPLE
    BipeTrustProfile.ProfileItem.HANDLE -> PhosphorIcon.USER_CIRCLE
    BipeTrustProfile.ProfileItem.AVATAR -> PhosphorIcon.CAMERA
}
