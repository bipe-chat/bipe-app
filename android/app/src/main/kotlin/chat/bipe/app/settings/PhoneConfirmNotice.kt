// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import chat.bipe.app.account.AccountSession
import chat.bipe.shared.R
import chat.bipe.shared.account.BipePhoneLinkState
import chat.bipe.shared.account.myPhone
import chat.bipe.shared.api.BipeApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Spec phone-link-status, action checkNotice (bipe-chat/bipe#470): one in-app notice when the yearly confirmation of the
 * linked number is near or has run out. One notice per confirm_by and state, kept on this phone.
 */
class PhoneConfirmNoticeModel(
    context: Context,
    private val api: BipeApiClient,
    private val session: AccountSession,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("phone-confirm", Context.MODE_PRIVATE)
    private val shownKey = "shown.${session.userId}"
    private val _notice = MutableStateFlow<BipePhoneLinkState?>(null)
    val notice: StateFlow<BipePhoneLinkState?> = _notice.asStateFlow()

    /** On launch and on foreground. */
    fun check() {
        if (_notice.value != null) return
        scope.launch {
            val phone = runCatching { BipeApiClient.withTokenRetry({ session.accessToken() }) { api.myPhone(it) } }.getOrNull() ?: return@launch
            val state = BipePhoneLinkState.of(phone)
            val confirmBy = state.confirmBy ?: return@launch
            if (!state.needsAttention) return@launch
            // A new notice for the soon state and again once it has run out, never twice for one of them.
            val key = "${confirmBy.epochSecond}:${if (state is BipePhoneLinkState.Expired) "expired" else "soon"}"
            if (prefs.getString(shownKey, null) == key) return@launch
            prefs.edit().putString(shownKey, key).apply()
            _notice.value = state
        }
    }

    fun dismiss() {
        _notice.value = null
    }
}

/** The notice of [PhoneConfirmNoticeModel]. */
@Composable
fun PhoneConfirmNoticeDialog(model: PhoneConfirmNoticeModel) {
    val context = LocalContext.current
    val notice by model.notice.collectAsState()
    val state = notice ?: return
    AlertDialog(
        onDismissRequest = model::dismiss,
        title = { Text(stringResource(R.string.bipe_phone_confirm_notice_title)) },
        text = { Text(state.summary(context)) },
        confirmButton = { TextButton(onClick = model::dismiss) { Text(stringResource(R.string.bipe_action_continue)) } },
    )
}
