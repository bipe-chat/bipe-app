// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import chat.bipe.app.BipeTheme
import chat.bipe.app.groups.GroupPeople
import chat.bipe.app.people.Person
import chat.bipe.app.people.PeopleService
import chat.bipe.shared.BipeGuardianState
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.resolveHandles
import chat.bipe.shared.clearTheftMark
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.guardians
import chat.bipe.shared.markStolen
import chat.bipe.shared.runCatchingExceptions
import chat.bipe.shared.setGuardians
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Spec theft-protection (ADR 0044, ADR 0051, bipe-chat/bipe#600): the owner picks 1 to 3 guardians from verified
 * contacts. A guardian sees the accounts they protect and marks one as stolen, or clears the mark.
 */
class TheftProtectionModel(private val context: Context, private val model: SettingsModel, private val scope: CoroutineScope) {
    enum class Phase { LOADING, SHOWN, SAVING, FAILED }

    /** State confirm. */
    enum class Confirm { MARK, CLEAR }

    /** Type GuardianRow. */
    data class GuardianRow(val userId: String, val name: String)

    /** Type GuardedRow. [markedAt] is set while a mark is on. */
    data class GuardedRow(val userId: String, val name: String, val markedAt: Instant?)

    data class State(
        val guardians: List<GuardianRow> = emptyList(),
        val guarding: List<GuardedRow> = emptyList(),
        /** bipe_theft_last_mark, filled in. */
        val lastMark: String? = null,
        /** Set while the picker is open. */
        val picker: List<GuardianRow>? = null,
        val confirm: Pair<Confirm, GuardedRow>? = null,
        val phase: Phase = Phase.LOADING,
        /** A string resource: bipe_theft_error or bipe_error_network. */
        val error: Int? = null,
    ) {
        val isFull: Boolean get() = guardians.size >= BipeRegistries.THEFT_MAX_GUARDIANS
        val isBusy: Boolean get() = phase == Phase.LOADING || phase == Phase.SAVING
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val names = mutableMapOf<String, String>()

    /** Action appear: GET /v1/me/guardians. Runs again when the app comes to the foreground. */
    fun appear() {
        scope.launch { load() }
    }

    /** Action openPicker: verified contacts (UserIdentity.isVerified) that are not guardians. */
    fun openPicker() {
        if (_state.value.isFull) return
        scope.launch {
            val session = model.session
            val taken = _state.value.guardians.map { it.userId }.toSet()
            val encryption = session.client.encryption()
            val rows = GroupPeople.contacts(session.client, session.userId)
                .filter { it.userId !in taken }
                .filter { person ->
                    runCatchingExceptions { encryption.userIdentity(person.userId, false)?.isVerified() }.getOrNull() == true
                }
                .map { person ->
                    names[person.userId] = person.displayName
                    GuardianRow(person.userId, person.displayName)
                }
            _state.update { it.copy(picker = rows) }
        }
    }

    fun closePicker() {
        _state.update { it.copy(picker = null) }
    }

    /** Action addGuardian: PUT /v1/me/guardians with the list plus the contact. Closes the picker. */
    fun addGuardian(userId: String) {
        val current = _state.value.guardians.map { it.userId }
        _state.update { it.copy(picker = null) }
        if (_state.value.isFull || userId in current) return
        save(current + userId)
    }

    /** Action removeGuardian: PUT /v1/me/guardians without the guardian. */
    fun removeGuardian(userId: String) {
        save(_state.value.guardians.map { it.userId }.filter { it != userId })
    }

    /** Action askMark or askClear. */
    fun ask(row: GuardedRow) {
        _state.update { it.copy(confirm = (if (row.markedAt == null) Confirm.MARK else Confirm.CLEAR) to row) }
    }

    fun dismissConfirm() {
        _state.update { it.copy(confirm = null) }
    }

    /**
     * Action mark or clear, after the dialog: the owner check of the phone (biometrics or screen lock), then
     * POST /v1/theft/{userId}/mark or /clear, then appear.
     */
    fun confirm(confirmOwner: suspend () -> Boolean) {
        val (action, row) = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        scope.launch {
            if (!confirmOwner()) {
                // A phone with no screen lock cannot pass the check (fail closed); a cancelled prompt is no error.
                if (!OwnerCheck.canCheckOwner(context)) _state.update { it.copy(error = R.string.bipe_theft_error) }
                return@launch
            }
            _state.update { it.copy(phase = Phase.SAVING, error = null) }
            try {
                model.authorized { token ->
                    if (action == Confirm.MARK) model.api.markStolen(row.userId, token) else model.api.clearTheftMark(row.userId, token)
                }
            } catch (error: BipeApiException) {
                fail(error)
                return@launch
            }
            load()
        }
    }

    private suspend fun load() {
        try {
            show(model.authorized { model.api.guardians(it) })
        } catch (error: BipeApiException) {
            fail(error)
        }
    }

    private fun save(userIds: List<String>) {
        scope.launch {
            _state.update { it.copy(phase = Phase.SAVING, error = null) }
            try {
                show(model.authorized { model.api.setGuardians(userIds, it) })
            } catch (error: BipeApiException) {
                fail(error)
            }
        }
    }

    private fun fail(error: BipeApiException) {
        _state.update {
            it.copy(
                phase = if (it.guardians.isEmpty() && it.guarding.isEmpty()) Phase.FAILED else Phase.SHOWN,
                error = if (error is BipeApiException.Network) R.string.bipe_error_network else R.string.bipe_theft_error,
            )
        }
    }

    private suspend fun show(state: BipeGuardianState) {
        val ids = state.guardians.map { it.userId } + state.guarding.map { it.userId } + listOfNotNull(state.lastMark?.markedBy)
        loadNames(ids)
        val lastMark = state.lastMark?.let { mark ->
            context.getString(R.string.bipe_theft_last_mark, name(mark.markedBy), mark.stolenInstant?.let(::formatDate) ?: mark.stolenAt)
        }
        _state.update {
            it.copy(
                guardians = state.guardians.map { guardian -> GuardianRow(guardian.userId, name(guardian.userId)) },
                guarding = state.guarding.map { account -> GuardedRow(account.userId, name(account.userId), account.mark?.stolenInstant) },
                lastMark = lastMark,
                phase = Phase.SHOWN,
                error = null,
            )
        }
    }

    /** The display name the app knows, else the @ (POST /v1/handles/resolve), else the localpart. Never the Matrix ID. */
    private suspend fun loadNames(userIds: List<String>) {
        val missing = userIds.distinct().filter { it !in names }
        if (missing.isEmpty()) return
        val handles = runCatchingExceptions { BipeApiClient.withOpenIdRetry(model.session.openId) { model.api.resolveHandles(missing, it) } }.getOrDefault(emptyMap())
        for (userId in missing) {
            val profile = runCatchingExceptions { model.session.client.getProfile(userId) }.getOrNull()
            names[userId] = PeopleService.displayName(null, profile?.displayName, handles[userId], userId)
        }
    }

    private fun name(userId: String): String = names[userId] ?: Person.localpart(userId)

    fun formatDate(instant: Instant): String = DateUtils.formatDateTime(
        context,
        instant.toEpochMilli(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )
}

/** Spec theft-protection. */
@Composable
fun TheftProtectionScreen(model: SettingsModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theft = remember(model) { TheftProtectionModel(context.applicationContext, model, scope) }
    val state by theft.state.collectAsState()
    val colors = BipeTheme.colors
    val markReason = stringResource(R.string.bipe_theft_mark_action)
    val clearReason = stringResource(R.string.bipe_theft_clear_action)
    LaunchedEffect(theft) { theft.appear() }
    // Action appear runs again when the app comes to the foreground.
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    DisposableEffect(lifecycle, theft) {
        var first = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (first) first = false else theft.appear()
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    SettingsPage(stringResource(R.string.bipe_theft_title), BipeScreen.THEFT_PROTECTION.id, onBack) {
        item(key = "os-protection") { OsTheftProtectionRow() }
        item(key = "intro") {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.bipe_theft_intro), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                state.lastMark?.let { lastMark ->
                    Text(lastMark, style = MaterialTheme.typography.bodyMedium, color = colors.trustDangerInk, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        item(key = "guardians-header") { TheftSectionTitle(stringResource(R.string.bipe_theft_guardians_section)) }
        items(state.guardians, key = { "guardian-" + it.userId }) { guardian ->
            SettingsRowItem(
                title = guardian.name,
                value = stringResource(R.string.bipe_theft_remove_guardian),
                valueColor = colors.trustDangerInk,
                onClick = if (state.isBusy) null else ({ theft.removeGuardian(guardian.userId) }),
            )
        }
        item(key = "guardians-add") {
            if (state.isFull) {
                Text(
                    stringResource(R.string.bipe_theft_guardians_full),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                SettingsRowItem(
                    title = stringResource(R.string.bipe_theft_add_guardian),
                    titleColor = colors.accentInk,
                    onClick = if (state.isBusy) null else theft::openPicker,
                )
            }
        }
        item(key = "guardians-footer") {
            Text(
                stringResource(R.string.bipe_theft_guardians_footer),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item(key = "guarding-header") { TheftSectionTitle(stringResource(R.string.bipe_theft_guarding_section)) }
        if (state.guarding.isEmpty() && state.phase != TheftProtectionModel.Phase.LOADING) {
            item(key = "guarding-empty") {
                Text(
                    stringResource(R.string.bipe_theft_guarding_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        items(state.guarding, key = { "guarded-" + it.userId }) { row ->
            val marked = row.markedAt
            SettingsRowItem(
                title = row.name,
                value = stringResource(if (marked == null) R.string.bipe_theft_mark_action else R.string.bipe_theft_clear_action),
                footer = marked?.let { stringResource(R.string.bipe_theft_marked_at, theft.formatDate(it)) },
                valueColor = if (marked == null) colors.trustDangerInk else colors.accentInk,
                onClick = if (state.isBusy) null else ({ theft.ask(row) }),
            )
        }
        if (state.isBusy) {
            item(key = "progress") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accentFill)
                }
            }
        }
        state.error?.let { error ->
            item(key = "error") {
                Text(stringResource(error), color = colors.trustDangerInk, modifier = Modifier.padding(16.dp))
            }
        }
    }
    state.picker?.let { rows ->
        AlertDialog(
            onDismissRequest = theft::closePicker,
            title = { Text(stringResource(R.string.bipe_theft_picker_title)) },
            text = {
                if (rows.isEmpty()) {
                    Text(stringResource(R.string.bipe_theft_picker_empty))
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(rows, key = { it.userId }) { row ->
                            TextButton(onClick = { theft.addGuardian(row.userId) }, modifier = Modifier.fillMaxWidth()) {
                                Text(row.name, color = colors.inkPrimary, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = theft::closePicker) { Text(stringResource(R.string.bipe_app_cancel)) }
            },
        )
    }
    state.confirm?.let { (action, row) ->
        val isMark = action == TheftProtectionModel.Confirm.MARK
        AlertDialog(
            onDismissRequest = theft::dismissConfirm,
            title = {
                Text(stringResource(if (isMark) R.string.bipe_theft_mark_confirm_title else R.string.bipe_theft_clear_confirm_title, row.name))
            },
            text = {
                Text(stringResource(if (isMark) R.string.bipe_theft_mark_confirm_body else R.string.bipe_theft_clear_confirm_body, row.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    theft.confirm { OwnerCheck.owner(context, if (isMark) markReason else clearReason) }
                }) {
                    Text(
                        stringResource(if (isMark) R.string.bipe_theft_mark_action else R.string.bipe_theft_clear_action),
                        color = if (isMark) colors.trustDangerInk else colors.accentInk,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = theft::dismissConfirm) { Text(stringResource(R.string.bipe_app_cancel)) }
            },
        )
    }
}

@Composable
private fun TheftSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = BipeTheme.colors.inkSecondary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}
