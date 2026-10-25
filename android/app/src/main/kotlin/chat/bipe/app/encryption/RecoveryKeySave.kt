// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.encryption

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import chat.bipe.app.common.BipeButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import chat.bipe.app.common.BipeOutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.settings.OwnerCheck
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.EnableRecoveryProgress
import org.matrix.rustcomponents.sdk.EnableRecoveryProgressListener
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.RecoveryStateListener
import org.matrix.rustcomponents.sdk.TaskHandle

/**
 * Spec recovery-key-save (bipe-shared product/screens/recovery-key-save.json): makes the recovery key for the message
 * history and shows it once (ADR 0073). Bipe never holds the key and never asks for it.
 */
class RecoveryKeySaveModel(private val session: AccountSession, private val scope: CoroutineScope) {
    enum class Phase { INTRO, CREATING, SHOW_KEY, SAVED, FAILED }

    enum class Progress { STARTING, CREATING_BACKUP, CREATING_RECOVERY_KEY, BACKING_UP, ROOM_KEY_UPLOAD_ERROR, DONE }

    private val encryption = session.client.encryption()
    private val _recoveryState = MutableStateFlow(encryption.recoveryState())
    val recoveryState: StateFlow<RecoveryState> = _recoveryState.asStateFlow()
    private val _phase = MutableStateFlow(Phase.INTRO)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _progress = MutableStateFlow(Progress.STARTING)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** Held in memory while showKey lasts. Never logged, never stored by the app. */
    private val _recoveryKey = MutableStateFlow<String?>(null)
    val recoveryKey: StateFlow<String?> = _recoveryKey.asStateFlow()
    private var handle: TaskHandle? = null

    fun observe() {
        val updates = Channel<RecoveryState>(Channel.UNLIMITED)
        handle = encryption.recoveryStateListener(object : RecoveryStateListener {
            override fun onUpdate(status: RecoveryState) {
                updates.trySend(status)
            }
        })
        scope.launch { for (state in updates) _recoveryState.value = state }
    }

    fun close() {
        handle?.cancel()
        _recoveryKey.value = null
    }

    fun keyExists(state: RecoveryState): Boolean = state == RecoveryState.ENABLED || state == RecoveryState.INCOMPLETE

    /** Action create: a first key with enableRecovery; a new key (the old one stops working) after the owner check. */
    fun create(confirmOwner: suspend () -> Boolean) {
        val replace = keyExists(_recoveryState.value)
        scope.launch {
            if (replace && !confirmOwner()) return@launch
            _phase.value = Phase.CREATING
            _progress.value = Progress.STARTING
            try {
                val key = if (replace) {
                    encryption.resetRecoveryKey()
                } else {
                    encryption.enableRecovery(
                        waitForBackupsToUpload = true,
                        passphrase = null,
                        progressListener = object : EnableRecoveryProgressListener {
                            override fun onUpdate(status: EnableRecoveryProgress) {
                                _progress.value = when (status) {
                                    is EnableRecoveryProgress.Starting -> Progress.STARTING
                                    is EnableRecoveryProgress.CreatingBackup -> Progress.CREATING_BACKUP
                                    is EnableRecoveryProgress.CreatingRecoveryKey -> Progress.CREATING_RECOVERY_KEY
                                    is EnableRecoveryProgress.BackingUp -> Progress.BACKING_UP
                                    is EnableRecoveryProgress.RoomKeyUploadError -> Progress.ROOM_KEY_UPLOAD_ERROR
                                    is EnableRecoveryProgress.Done -> Progress.DONE
                                }
                            }
                        },
                    )
                }
                _recoveryKey.value = key
                _phase.value = Phase.SHOW_KEY
            } catch (error: Exception) {
                _phase.value = Phase.FAILED
            }
        }
    }

    /** Action copy: marked as sensitive so the system does not show or sync it, and cleared after 60 s. */
    fun copy(context: Context) {
        val key = _recoveryKey.value ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("", key)
        clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            } else {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
        scope.launch {
            delay(60_000)
            val current = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (current == key) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }

    /** Action share: the system share sheet, for a password manager or a note. */
    fun share(context: Context) {
        val key = _recoveryKey.value ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, key)
        context.startActivity(Intent.createChooser(send, null))
    }

    /** Action confirmSaved: clears the key from memory. */
    fun confirmSaved() {
        _recoveryKey.value = null
        _phase.value = Phase.SAVED
    }
}

/** The key in groups of 4 characters (spec notes). */
private fun grouped(key: String): String = key.filterNot { it.isWhitespace() }.chunked(4).joinToString(" ")

@Composable
fun RecoveryKeySaveScreen(session: AccountSession, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(session) { RecoveryKeySaveModel(session, scope) }
    DisposableEffect(model) {
        model.observe()
        onDispose { model.close() }
    }
    val colors = BipeTheme.colors
    val recoveryState by model.recoveryState.collectAsState()
    val phase by model.phase.collectAsState()
    val key by model.recoveryKey.collectAsState()
    var copied by remember { mutableStateOf(false) }
    val exists = model.keyExists(recoveryState)
    val ownerTitle = stringResource(R.string.bipe_app_recovery_key_biometric)
    // The key shows only once: leaving before "I saved the key" needs the explicit button.
    BackHandler(enabled = phase != RecoveryKeySaveModel.Phase.SHOW_KEY && phase != RecoveryKeySaveModel.Phase.CREATING) { onClose() }

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.RECOVERY_KEY_SAVE.id), color = colors.surfaceBase) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.bipe_account_recovery_key), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = colors.inkPrimary)
            when (phase) {
                RecoveryKeySaveModel.Phase.INTRO -> {
                    Text(
                        stringResource(if (exists) R.string.bipe_account_recovery_key_saved else R.string.bipe_account_recovery_key_missing),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (exists) colors.trustVerifiedInk else colors.inkSecondary,
                    )
                    Text(
                        stringResource(if (exists) R.string.bipe_app_recovery_key_replace_body else R.string.bipe_fallback_recovery_key_body),
                        color = colors.inkSecondary,
                    )
                    Text(stringResource(R.string.bipe_account_recovery_key_footer), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                    Spacer(Modifier.weight(1f))
                    BipeButton(
                        onClick = { model.create { OwnerCheck.owner(context, ownerTitle) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(if (exists) R.string.bipe_app_recovery_key_replace else R.string.bipe_app_recovery_key_create))
                    }
                }
                RecoveryKeySaveModel.Phase.CREATING -> {
                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.bipe_app_recovery_key_creating), color = colors.inkSecondary)
                    }
                    Spacer(Modifier.weight(1f))
                }
                RecoveryKeySaveModel.Phase.SHOW_KEY -> {
                    Text(stringResource(R.string.bipe_app_recovery_key_show_body), color = colors.inkSecondary)
                    SelectionContainer {
                        Text(
                            grouped(key.orEmpty()),
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            color = colors.inkPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surfaceSunken, RoundedCornerShape(12.dp))
                                .padding(16.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BipeOutlinedButton(onClick = {
                            model.copy(context)
                            copied = true
                        }) {
                            Icon(painterResource(PhosphorIcon.COPY.drawable), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(if (copied) R.string.bipe_app_copied else R.string.bipe_app_copy))
                        }
                        BipeOutlinedButton(onClick = { model.share(context) }) {
                            Text(stringResource(R.string.bipe_app_share))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    BipeButton(onClick = model::confirmSaved, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bipe_app_recovery_key_saved_action))
                    }
                }
                RecoveryKeySaveModel.Phase.SAVED -> {
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(PhosphorIcon.CHECK_CIRCLE.drawable), contentDescription = null, tint = colors.statusSuccess, modifier = Modifier.size(24.dp))
                        Text(stringResource(R.string.bipe_account_recovery_key_saved), color = colors.inkPrimary)
                    }
                    Spacer(Modifier.weight(1f))
                    BipeButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bipe_action_continue))
                    }
                }
                RecoveryKeySaveModel.Phase.FAILED -> {
                    Text(stringResource(R.string.bipe_error_generic), color = colors.trustDangerInk)
                    Spacer(Modifier.weight(1f))
                    BipeButton(onClick = { model.create { OwnerCheck.owner(context, ownerTitle) } }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bipe_app_retry))
                    }
                }
            }
        }
    }
}
