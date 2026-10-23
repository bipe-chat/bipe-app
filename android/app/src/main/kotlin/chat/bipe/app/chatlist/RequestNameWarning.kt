// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import chat.bipe.app.BipeTheme
import chat.bipe.app.people.PeopleService
import chat.bipe.shared.BipeContactNameKey
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeRelationshipNames

/** An already-known contact match and an authenticated proof name. Opening a request sends no address book. */
@Composable
internal fun RequestNameWarning(people: PeopleService, peerId: String, verifiedName: String?, identityKey: String?) {
    val context = LocalContext.current
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    val names by people.contactNames.collectAsState()
    var foregroundRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) foregroundRevision += 1 }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    val savedName = remember(names, people, peerId, foregroundRevision) { people.contactName(peerId) } ?: return
    val proofName = verifiedName ?: return
    val proofKey = identityKey ?: return
    val key = BipeContactNameKey.make(people.session.userId, peerId, savedName, proofName, proofKey)
    val prefs = remember(context) { context.getSharedPreferences("bipe_contact_name_answers", Context.MODE_PRIVATE) }
    var answer by remember(key) { mutableStateOf(if (prefs.contains(key)) prefs.getBoolean(key, false) else null) }
    val warning = BipeRelationshipNames.warning(savedName, proofName, answer == true) ?: return
    val colors = BipeTheme.colors
    val ink = if (warning == "mismatch") colors.trustDangerInk else colors.trustCautionInk
    Column(
        Modifier.fillMaxWidth().background(
            if (warning == "mismatch") colors.trustDangerWash else colors.trustCautionWash, RoundedCornerShape(12.dp),
        ).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.bipe_warning_name_mismatch, proofName), color = ink, textAlign = TextAlign.Center)
        if (warning == "confirm" && answer == null) {
            Text(stringResource(R.string.bipe_warning_nickname_confirm_title), color = ink, textAlign = TextAlign.Center)
            Text(stringResource(R.string.bipe_warning_nickname_confirm_body, savedName, proofName), color = ink, textAlign = TextAlign.Center)
            TextButton(onClick = { prefs.edit().putBoolean(key, true).apply(); answer = true }) {
                Text(stringResource(R.string.bipe_action_nickname_confirm_yes))
            }
            TextButton(onClick = { prefs.edit().putBoolean(key, false).apply(); answer = false }) {
                Text(stringResource(R.string.bipe_action_nickname_confirm_unsure))
            }
        }
    }
}
