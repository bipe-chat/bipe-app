// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.encryption

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import chat.bipe.app.settings.OwnerCheck
import chat.bipe.app.settings.rememberAccountPinCheck
import chat.bipe.shared.R

/**
 * The owner check before an approval (security.md rules 5 and 14 tier 3, bipe-chat/bipe#785): strong biometrics, or
 * the Bipe PIN checked by the server on a phone without them ([OwnerCheck.approval]). Never the screen lock. A phone
 * with no screen lock gets [OwnerCheck.Approval.NO_SCREEN_LOCK].
 */
@Composable
fun rememberApprovalCheck(reason: String, checkPin: suspend (String) -> Unit): suspend () -> OwnerCheck.Approval {
    val context = LocalContext.current
    val cancel = stringResource(R.string.bipe_app_cancel)
    val askPin = rememberAccountPinCheck(reason, checkPin)
    val currentReason by rememberUpdatedState(reason)
    return remember(context, cancel, askPin) {
        val check: suspend () -> OwnerCheck.Approval = { OwnerCheck.approval(context, currentReason, cancel, askPin) }
        check
    }
}
