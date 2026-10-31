// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.handle

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.chatlist.PromptAction
import chat.bipe.app.chatlist.PromptCard
import chat.bipe.app.common.bipeTapTarget
import chat.bipe.shared.BipeHandle
import chat.bipe.shared.BipeHandleAvailabilityChecker
import chat.bipe.shared.BipeHandleProblem
import chat.bipe.shared.BipeHandleStatus
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeMyHandle
import chat.bipe.shared.generated.BipeOnboardingPrompt
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.onboarding.BipePromptSlot
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Spec handle-pick (bipe-shared product/screens/handle-pick.json). */
class HandlePickModel(
    private val context: Context,
    private val api: BipeApiClient,
    private val session: AccountSession,
    private val scope: CoroutineScope,
) {
    enum class Status { EMPTY, TOO_SHORT, TOO_LONG, INVALID_CHARACTERS, CHECKING, AVAILABLE, RESERVED, TAKEN, UNCHANGED, UNKNOWN }
    enum class Phase { EDITING, SAVING, SAVED, FAILED }

    data class State(
        val currentHandle: String? = null,
        val nextChangeAt: Instant? = null,
        val input: String = "",
        val status: Status = Status.EMPTY,
        val phase: Phase = Phase.EDITING,
        val error: String? = null,
        val doneMessage: String? = null,
        /** Read once: the prompt card shows only when the account has no @ yet. */
        val loaded: Boolean = false,
        val promptDismissed: Boolean = false,
        /** Escolha seu @ (bipe-chat/bipe#200): free handles from the display name, while the account has no @. */
        val suggestions: List<String> = emptyList(),
        /** The prompt slot allows the handle prompt (registries/onboarding.json promptSlot). */
        val slotAllows: Boolean = true,
    ) {
        val inCooldown: Boolean get() = nextChangeAt?.isAfter(Instant.now()) == true

        /** Prompt card above chat-list: trigger chatListWithoutHandle (registries/onboarding.json prompts.handle). */
        val showsPrompt: Boolean get() = loaded && currentHandle == null && !promptDismissed && slotAllows
    }

    private val preferences = context.getSharedPreferences("bipe.handle", Context.MODE_PRIVATE)
    private val promptKey = "prompt.dismissed.${session.userId}"
    private val slot = BipePromptSlot(context, session.userId)
    private val _state = MutableStateFlow(State(promptDismissed = preferences.getBoolean(promptKey, false),
        slotAllows = slot.allows(BipeOnboardingPrompt.HANDLE)))
    val state: StateFlow<State> = _state.asStateFlow()
    private val checker = BipeHandleAvailabilityChecker(api, scope)

    fun load() {
        scope.launch {
            val mine = runCatching { BipeApiClient.withOpenIdRetry(session.openId) { api.myHandle(it) } }.getOrNull() ?: return@launch
            _state.value = _state.value.copy(
                currentHandle = mine.handle,
                nextChangeAt = mine.changeableAt,
                input = mine.handle.orEmpty(),
                status = if (mine.handle == null) Status.EMPTY else Status.UNCHANGED,
                loaded = true,
            )
        }
    }

    /** The prompt card showed: it takes the prompt slot. */
    fun promptShown() = slot.didShow(BipeOnboardingPrompt.HANDLE)

    /** State suggestions of handle-pick: candidates in order, checked one by one, the first free ones kept. */
    fun loadSuggestions() {
        if (_state.value.currentHandle != null || _state.value.suggestions.isNotEmpty()) return
        scope.launch {
            val name = runCatching { session.client.displayName() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@launch
            val free = mutableListOf<String>()
            for (candidate in BipeHandle.suggestions(name)) {
                if (free.size >= BipeRegistries.HANDLE_SUGGESTIONS_SHOWN) break
                if (runCatching { api.handleAvailability(candidate).available }.getOrDefault(false)) free += candidate
            }
            _state.value = _state.value.copy(suggestions = free)
        }
    }

    /** Action editHandle. */
    fun editHandle(text: String) {
        val input = BipeHandle.sanitize(text).lowercase()
        _state.value = _state.value.copy(input = input, error = null, phase = Phase.EDITING)
        if (input.isNotEmpty() && input == _state.value.currentHandle) {
            checker.cancel()
            _state.value = _state.value.copy(status = Status.UNCHANGED)
            return
        }
        checker.check(input) { result -> _state.value = _state.value.copy(status = statusOf(result)) }
    }

    /** Action save: only when the @ is free. */
    fun save(onSaved: () -> Unit) {
        if (_state.value.status != Status.AVAILABLE) return
        val handle = _state.value.input
        _state.value = _state.value.copy(phase = Phase.SAVING)
        scope.launch {
            try {
                val mine = BipeApiClient.withTokenRetry({ session.accessToken() }) { api.setMyHandle(handle, it) }
                saved(mine, context.getString(R.string.bipe_handle_saved), Status.UNCHANGED)
                onSaved()
            } catch (error: BipeApiException) {
                fail(error)
            }
        }
    }

    /** Action remove, after the confirm dialog. */
    fun remove() {
        if (_state.value.currentHandle == null) return
        _state.value = _state.value.copy(phase = Phase.SAVING)
        scope.launch {
            try {
                val mine = BipeApiClient.withTokenRetry({ session.accessToken() }) { api.removeMyHandle(it) }
                saved(mine, context.getString(R.string.bipe_handle_remove_done), Status.EMPTY)
                _state.value = _state.value.copy(input = "")
            } catch (error: BipeApiException) {
                fail(error)
            }
        }
    }

    /** Action dismissPrompt: hides the card on this phone. */
    fun dismissPrompt() {
        preferences.edit().putBoolean(promptKey, true).apply()
        _state.value = _state.value.copy(promptDismissed = true)
    }

    private fun saved(mine: BipeMyHandle, message: String, status: Status) {
        _state.value = _state.value.copy(
            currentHandle = mine.handle,
            nextChangeAt = mine.changeableAt,
            status = status,
            phase = Phase.SAVED,
            doneMessage = message,
        )
    }

    private fun fail(error: BipeApiException) {
        val code = (error as? BipeApiException.Server)?.code
        val message = when {
            code == BipeApiErrorCode.HANDLE_COOLDOWN -> context.getString(R.string.bipe_error_handle_cooldown)
            code == BipeApiErrorCode.HANDLE_TAKEN -> context.getString(R.string.bipe_handle_taken)
            error is BipeApiException.Network -> context.getString(R.string.bipe_error_network)
            else -> context.getString(R.string.bipe_error_generic)
        }
        _state.value = _state.value.copy(phase = Phase.FAILED, error = message)
    }

    private fun statusOf(status: BipeHandleStatus): Status = when (status) {
        BipeHandleStatus.Empty -> Status.EMPTY
        BipeHandleStatus.Checking -> Status.CHECKING
        BipeHandleStatus.Available -> Status.AVAILABLE
        BipeHandleStatus.Unknown -> Status.UNKNOWN
        is BipeHandleStatus.Problem -> when (status.problem) {
            BipeHandleProblem.TOO_SHORT -> Status.TOO_SHORT
            BipeHandleProblem.TOO_LONG -> Status.TOO_LONG
            BipeHandleProblem.INVALID_CHARACTERS -> Status.INVALID_CHARACTERS
            BipeHandleProblem.RESERVED -> Status.RESERVED
            BipeHandleProblem.TAKEN -> Status.TAKEN
        }
    }

    /** The footer under the field. */
    fun footer(state: State): String {
        val until = state.nextChangeAt
        if (state.inCooldown && until != null) {
            val date = DateUtils.formatDateTime(context, until.toEpochMilli(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
            return context.getString(R.string.bipe_handle_cooldown_until, date)
        }
        return when (state.status) {
            Status.EMPTY, Status.UNCHANGED, Status.UNKNOWN -> context.getString(R.string.bipe_handle_screen_footer)
            Status.CHECKING -> context.getString(R.string.bipe_handle_checking)
            Status.AVAILABLE -> context.getString(R.string.bipe_handle_available)
            Status.TOO_SHORT -> BipeHandleProblem.TOO_SHORT.localizedMessage(context)
            Status.TOO_LONG -> BipeHandleProblem.TOO_LONG.localizedMessage(context)
            Status.INVALID_CHARACTERS -> BipeHandleProblem.INVALID_CHARACTERS.localizedMessage(context)
            Status.RESERVED -> BipeHandleProblem.RESERVED.localizedMessage(context)
            Status.TAKEN -> BipeHandleProblem.TAKEN.localizedMessage(context)
        }
    }

    fun isProblem(status: Status): Boolean =
        status in setOf(Status.TOO_SHORT, Status.TOO_LONG, Status.INVALID_CHARACTERS, Status.RESERVED, Status.TAKEN)
}

/** The card above chat-list for an account without an @ (registries/onboarding.json prompts.handle). */
@Composable
fun HandlePromptCard(model: HandlePickModel, open: () -> Unit) {
    val prompt = BipeOnboardingPrompt.HANDLE
    LaunchedEffect(Unit) { model.promptShown() }
    PromptCard(
        title = stringResource(prompt.title),
        body = prompt.body?.let { stringResource(it) },
        primary = PromptAction(stringResource(prompt.primary.title), open),
        texts = prompt.text.map { PromptAction(stringResource(it.title), model::dismissPrompt) },
    )
}

@Composable
fun HandlePickScreen(model: HandlePickModel, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val state by model.state.collectAsState()
    var confirmsRemove by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.HANDLE_PICK.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.bipe_signup_handle), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = colors.inkPrimary, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.bipe_app_close), color = colors.accentInk, modifier = Modifier.bipeTapTarget().clickable(role = Role.Button, onClick = onClose).padding(8.dp))
            }
            val placeholder = stringResource(R.string.bipe_handle_placeholder)
            BasicTextField(
                value = state.input,
                onValueChange = model::editHandle,
                enabled = !state.inCooldown,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.inkPrimary),
                cursorBrush = SolidColor(colors.accentFill),
                modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken, RoundedCornerShape(12.dp)).padding(14.dp)
                    .semantics { contentDescription = placeholder },
                decorationBox = { inner ->
                    Row {
                        Text("@", color = colors.inkSecondary)
                        Box {
                            if (state.input.isEmpty()) Text(placeholder, color = colors.inkSecondary)
                            inner()
                        }
                    }
                },
            )
            Text(model.footer(state), style = MaterialTheme.typography.bodySmall, color = if (model.isProblem(state.status)) colors.trustDangerInk else colors.inkSecondary)
            LaunchedEffect(Unit) { model.loadSuggestions() }
            if (state.input.isEmpty() && state.suggestions.isNotEmpty()) {
                // State suggestions (bipe-chat/bipe#200): a tap fills the field.
                Text(stringResource(R.string.bipe_handle_suggestions), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.suggestions.forEach { suggestion ->
                        Text(
                            "@$suggestion",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.accentInk,
                            maxLines = 1,
                            modifier = Modifier.background(colors.accentWash, RoundedCornerShape(50))
                                .clickable(role = Role.Button) { model.editHandle(suggestion) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.trustDangerInk) }
            if (state.phase == HandlePickModel.Phase.SAVED) {
                state.doneMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.statusSuccess) }
            }
            val enabled = state.status == HandlePickModel.Status.AVAILABLE && state.phase != HandlePickModel.Phase.SAVING
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(if (enabled) colors.accentFill else colors.surfaceSunken, RoundedCornerShape(14.dp))
                    .clickable(enabled = enabled, role = Role.Button) { model.save(onClose) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.bipe_action_continue), style = MaterialTheme.typography.titleMedium, color = if (enabled) colors.inkOnAccent else colors.inkDisabled)
            }
            val current = state.currentHandle
            if (current != null) {
                Text(
                    stringResource(R.string.bipe_handle_remove),
                    color = colors.trustDangerInk,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { confirmsRemove = true }.padding(vertical = 10.dp),
                )
                if (confirmsRemove) {
                    AlertDialog(
                        onDismissRequest = { confirmsRemove = false },
                        text = { Text(stringResource(R.string.bipe_handle_remove_body, current)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmsRemove = false
                                model.remove()
                            }) { Text(stringResource(R.string.bipe_handle_remove), color = colors.trustDangerInk) }
                        },
                        dismissButton = { TextButton(onClick = { confirmsRemove = false }) { Text(stringResource(R.string.bipe_app_cancel)) } },
                    )
                }
            }
        }
    }
}
