// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.account.BipePin
import chat.bipe.shared.bipeHaptic
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.generated.BipeRegistries

/** A PIN pad: title, dots for the digits typed, an optional message, the keys. The PIN never shows as text. */
@Composable
fun PinPad(
    title: String,
    pin: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    error: String? = null,
    isBusy: Boolean = false,
    onBiometrics: (() -> Unit)? = null,
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = BipeTheme.colors
    Surface(modifier = modifier.fillMaxSize(), color = colors.surfaceBase) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Icon(painterResource(PhosphorIcon.LOCK_SIMPLE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(40.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary, textAlign = TextAlign.Center)
            if (message != null) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary, textAlign = TextAlign.Center)
            }
            val progress = stringResource(R.string.bipe_a11y_pin_progress, pin.length.toString(), BipeRegistries.PIN_LENGTH.toString())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.clearAndSetSemantics { contentDescription = progress }) {
                repeat(BipeRegistries.PIN_LENGTH) { index ->
                    Box(
                        Modifier.size(12.dp).clip(CircleShape)
                            .background(if (index < pin.length) colors.accentFill else colors.lineStrong),
                    )
                }
            }
            Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
                when {
                    isBusy -> CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(24.dp))
                    error != null -> Text(error, style = MaterialTheme.typography.bodyMedium, color = colors.trustDangerInk, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.weight(1f))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    row.forEach { digit -> DigitKey(digit, enabled = !isBusy) { onDigit(digit) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (onBiometrics != null) {
                    IconKey(PhosphorIcon.FINGERPRINT, stringResource(R.string.bipe_a11y_biometrics), enabled = !isBusy, onClick = onBiometrics)
                } else {
                    Spacer(Modifier.size(72.dp))
                }
                DigitKey("0", enabled = !isBusy) { onDigit("0") }
                IconKey(PhosphorIcon.BACKSPACE, stringResource(R.string.bipe_a11y_pin_delete), enabled = !isBusy, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun DigitKey(digit: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    Box(
        modifier = Modifier.size(72.dp).clip(CircleShape).background(colors.surfaceRaised).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(digit, style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary)
    }
}

@Composable
private fun IconKey(icon: PhosphorIcon, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(72.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon.drawable), contentDescription = label, tint = BipeTheme.colors.glyphPrimary, modifier = Modifier.size(28.dp))
    }
}

/** The create and confirm steps of signup-pin, shared by app-lock (turnOn) and pin-change. */
class PinSetupState {
    enum class Step { CREATE, CONFIRM }

    var step by mutableStateOf(Step.CREATE)
        private set
    var pin by mutableStateOf("")
        private set
    var problem by mutableStateOf<String?>(null)
    private var first = ""

    /** Calls [done] with the PIN when the confirm step matches. */
    fun type(context: Context, view: View, digit: String, done: (String) -> Unit) {
        if (pin.length >= BipeRegistries.PIN_LENGTH) return
        if (pin.isEmpty()) problem = null
        pin += digit
        if (pin.length < BipeRegistries.PIN_LENGTH) return
        when (step) {
            Step.CREATE -> {
                val weak = BipePin.problem(pin)
                if (weak != null) {
                    problem = weak.localizedMessage(context)
                    view.bipeHaptic(BipeHaptic.Error)
                    pin = ""
                } else {
                    first = pin
                    pin = ""
                    step = Step.CONFIRM
                }
            }
            Step.CONFIRM -> if (pin == first) {
                val confirmed = pin
                pin = ""
                done(confirmed)
            } else {
                view.bipeHaptic(BipeHaptic.Error)
                reset(context.getString(R.string.bipe_pin_mismatch))
            }
        }
    }

    fun delete() {
        pin = pin.dropLast(1)
    }

    /** Back to create, for example after the server said pin_weak. */
    fun reset(problem: String? = null) {
        if (problem != null) this.problem = problem
        first = ""
        pin = ""
        step = Step.CREATE
    }

    fun title(context: Context): String =
        context.getString(if (step == Step.CREATE) R.string.bipe_pin_create_title else R.string.bipe_pin_confirm_title)

    fun message(context: Context): String = if (step == Step.CREATE) {
        context.getString(R.string.bipe_pin_create_body, BipeRegistries.PIN_LENGTH.toString())
    } else {
        context.getString(R.string.bipe_pin_never_asked)
    }
}
