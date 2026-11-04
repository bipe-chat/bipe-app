// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.reduceMotion
import chat.bipe.app.highContrast
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.generated.BipeSettingsUrl

/**
 * Spec accessibility-settings (bipe-chat/bipe#917): the state of the system accessibility settings that Bipe
 * follows. The rows show state only; the button opens the system accessibility settings.
 */
@Composable
fun AccessibilitySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = BipeTheme.colors
    // The state is read again each time the screen comes back to the front, after a change in the system.
    var revision by remember { mutableIntStateOf(0) }
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision += 1 }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    val fontScale = LocalDensity.current.fontScale
    val states = remember(revision, fontScale) {
        listOf(
            R.string.bipe_accessibility_screen_reader to onOff(screenReader(context)),
            R.string.bipe_accessibility_text_size to when {
                fontScale > 1f -> R.string.bipe_accessibility_text_larger
                fontScale < 1f -> R.string.bipe_accessibility_text_smaller
                else -> R.string.bipe_accessibility_text_default
            },
            R.string.bipe_accessibility_reduce_motion to onOff(reduceMotion(context)),
            R.string.bipe_accessibility_contrast to onOff(highContrast(context)),
        )
    }

    SettingsPage(stringResource(R.string.bipe_chats_accessibility), BipeScreen.ACCESSIBILITY_SETTINGS.id, onBack) {
        item(key = "intro") {
            Text(stringResource(R.string.bipe_accessibility_intro), style = MaterialTheme.typography.bodyMedium,
                color = colors.inkPrimary, modifier = Modifier.padding(16.dp))
        }
        item(key = "states") {
            SectionHeader(null)
            for ((title, value) in states) {
                // One element for TalkBack: the name, then the state.
                Box(Modifier.semantics(mergeDescendants = true) {}) {
                    SettingsRowItem(title = stringResource(title), value = stringResource(value), onClick = null)
                }
            }
        }
        item(key = "system") {
            SectionHeader(null)
            SettingsRowItem(title = stringResource(R.string.bipe_accessibility_open_system), showsChevron = true) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            SettingsRowItem(title = stringResource(R.string.bipe_accessibility_page)) {
                openPage(context, BipeSettingsUrl.ACCESSIBILITY.url(appLanguage(context)))
            }
        }
    }
}

private fun onOff(isOn: Boolean): Int = if (isOn) R.string.bipe_accessibility_on else R.string.bipe_accessibility_off

/** TalkBack and other screen readers turn on touch exploration. */
private fun screenReader(context: Context): Boolean =
    context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
