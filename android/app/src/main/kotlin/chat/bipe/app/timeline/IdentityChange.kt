// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.IdentityStatusChange
import org.matrix.rustcomponents.sdk.IdentityStatusChangeListener
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.matrix_sdk_crypto.IdentityState

/** Spec identity-change (bipe-chat/bipe#770): a member's crypto identity changed in this chat. */
class IdentityChangeModel(private val session: AccountSession, private val roomId: String, private val scope: CoroutineScope) {
    /** Type IdentityWarning. */
    data class Warning(val userId: String, val name: String, val kind: Kind) {
        enum class Kind { CHANGED, VERIFICATION_LOST }
    }

    data class State(
        val warnings: List<Warning> = emptyList(),
        val busy: Boolean = false,
        val failed: Boolean = false,
    ) {
        /** State blocksSending: a verified person is no longer verified. */
        val blocksSending: Boolean get() = warnings.any { it.kind == Warning.Kind.VERIFICATION_LOST }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var handle: TaskHandle? = null

    /** Listens until [close]. */
    fun start() {
        if (handle != null) return
        scope.launch {
            val room = runCatching { session.client.getRoom(roomId) }.getOrNull() ?: return@launch
            val listener = object : IdentityStatusChangeListener {
                override fun call(identityStatusChange: List<IdentityStatusChange>) {
                    scope.launch { record(identityStatusChange) }
                }

                private suspend fun record(changes: List<IdentityStatusChange>) {
                    for (change in changes) {
                        if (change.userId == session.userId) continue
                        val kind = when (change.changedTo) {
                            IdentityState.PIN_VIOLATION -> Warning.Kind.CHANGED
                            IdentityState.VERIFICATION_VIOLATION -> Warning.Kind.VERIFICATION_LOST
                            IdentityState.VERIFIED, IdentityState.PINNED -> null
                        }
                        val name = if (kind == null) {
                            null
                        } else {
                            runCatching { room.memberDisplayName(change.userId) }.getOrNull()?.takeIf { it.isNotBlank() } ?: change.userId
                        }
                        _state.update { current ->
                            val others = current.warnings.filterNot { it.userId == change.userId }
                            current.copy(warnings = if (kind != null && name != null) others + Warning(change.userId, name, kind) else others)
                        }
                    }
                }
            }
            handle = runCatching { room.subscribeToIdentityStatusChanges(listener) }.getOrNull()
        }
    }

    fun close() {
        handle?.cancel()
        handle = null
    }

    /** Action acknowledge: pins the new identity, or withdraws the old verification. The SDK then removes the warning. */
    fun acknowledge() {
        val first = _state.value.warnings.firstOrNull() ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        scope.launch {
            val done = runCatching {
                val identity = session.client.encryption().userIdentity(first.userId, true) ?: error("no identity")
                when (first.kind) {
                    Warning.Kind.CHANGED -> identity.pin()
                    Warning.Kind.VERIFICATION_LOST -> identity.withdrawVerification()
                }
            }.isSuccess
            _state.update { it.copy(busy = false, failed = !done) }
        }
    }

    fun dismissError() {
        _state.update { it.copy(failed = false) }
    }
}

/** Spec identity-change: the bar above the composer. */
@Composable
fun IdentityChangeBar(model: IdentityChangeModel) {
    val colors = BipeTheme.colors
    val state by model.state.collectAsState()
    var showsHelp by remember { mutableStateOf(false) }
    val first = state.warnings.firstOrNull() ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(colors.trustCautionWash, RoundedCornerShape(12.dp))
            .border(1.dp, colors.trustCautionLine, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = colors.trustCautionInk, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val text = when (first.kind) {
                    IdentityChangeModel.Warning.Kind.CHANGED -> stringResource(R.string.bipe_identity_change_body, first.name)
                    IdentityChangeModel.Warning.Kind.VERIFICATION_LOST -> stringResource(R.string.bipe_identity_change_verified_body, first.name)
                }
                Text(text, style = MaterialTheme.typography.bodySmall, color = colors.inkPrimary)
                if (state.warnings.size > 1) {
                    Text(
                        stringResource(R.string.bipe_identity_change_more, (state.warnings.size - 1).toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                    )
                }
            }
        }
        Row(modifier = Modifier.padding(start = 16.dp)) {
            TextButton(onClick = model::acknowledge, enabled = !state.busy) {
                Text(stringResource(R.string.bipe_identity_change_ok), fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = { showsHelp = true }) { Text(stringResource(R.string.bipe_identity_change_learn_more)) }
        }
    }
    if (showsHelp) {
        AlertDialog(
            onDismissRequest = { showsHelp = false },
            title = { Text(stringResource(R.string.bipe_identity_change_learn_more)) },
            text = { Text(stringResource(R.string.bipe_identity_change_help)) },
            confirmButton = { TextButton(onClick = { showsHelp = false }) { Text(stringResource(R.string.bipe_action_continue)) } },
        )
    }
    if (state.failed) {
        AlertDialog(
            onDismissRequest = model::dismissError,
            text = { Text(stringResource(R.string.bipe_error_generic)) },
            confirmButton = { TextButton(onClick = model::dismissError) { Text(stringResource(R.string.bipe_action_continue)) } },
        )
    }
}

/** Spec identity-change blocksSending: the composer shows but takes no input until the person taps Entendi. */
@Composable
fun IdentityComposerGate(blocked: Boolean, composer: @Composable () -> Unit) {
    if (!blocked) {
        composer()
        return
    }
    Box {
        Box(Modifier.alpha(0.4f).clearAndSetSemantics { disabled() }) { composer() }
        Box(
            Modifier.matchParentSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        )
    }
}
