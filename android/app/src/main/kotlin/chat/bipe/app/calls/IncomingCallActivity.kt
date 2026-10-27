// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.calls

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen

/** Spec incoming-call: the ringing screen, also over the lock screen. */
class IncomingCallActivity : ComponentActivity() {
    companion object {
        const val ACTION_ANSWER = "chat.bipe.app.call.ANSWER"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        // The answer action of the call notification and the system's answer (headset, car).
        if (intent?.action == ACTION_ANSWER) {
            IncomingCalls.answer(this)
            finish()
            return
        }
        setContent {
            BipeTheme {
                val ringing by IncomingCalls.ringing.collectAsState()
                val call = ringing
                if (call == null) {
                    finish()
                } else {
                    IncomingCallScreen(
                        call = call,
                        media = CallCenter.media ?: IncomingCalls.media(call),
                        onAnswer = {
                            IncomingCalls.answer(this)
                            finish()
                        },
                        onDecline = {
                            IncomingCalls.decline()
                            finish()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun IncomingCallScreen(call: IncomingCall, media: MediaLoader?, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val colors = BipeTheme.colors
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.INCOMING_CALL.id), color = colors.surfaceSunken) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.weight(1f))
            if (media != null) Avatar(call.callerName, call.callerAvatarUrl, media, size = 120.dp)
            Text(
                stringResource(if (call.video) R.string.bipe_call_incoming_video else R.string.bipe_call_incoming_voice, call.callerName),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.inkPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(64.dp), modifier = Modifier.padding(bottom = 48.dp)) {
                RoundAction(PhosphorIcon.PHONE_X, stringResource(R.string.bipe_call_decline), colors.trustDangerInk, onDecline)
                RoundAction(if (call.video) PhosphorIcon.VIDEO_CAMERA else PhosphorIcon.PHONE, stringResource(R.string.bipe_call_answer),
                    colors.statusSuccess, onAnswer)
            }
        }
    }
}

@Composable
private fun RoundAction(icon: PhosphorIcon, label: String, fill: Color, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = fill, contentColor = colors.inkOnAccent),
        ) {
            Icon(painterResource(icon.drawable), contentDescription = label, modifier = Modifier.size(30.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary)
    }
}
