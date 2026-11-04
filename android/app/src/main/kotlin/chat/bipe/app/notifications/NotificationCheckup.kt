// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import chat.bipe.app.BipeApplication
import chat.bipe.app.BipeTheme
import chat.bipe.app.chatlist.PromptAction
import chat.bipe.app.chatlist.PromptCard
import chat.bipe.app.common.BipeOutlinedButton
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen

/** Spec notification-checkup (bipe-chat/bipe#823): the phone's push checks, Android only. */
object NotificationCheckup {
    /** Type Check.id of the spec, in its order. */
    enum class Check(val label: Int) {
        PERMISSION(R.string.bipe_app_notification_checkup_permission),
        CHANNEL(R.string.bipe_app_notification_checkup_channel),
        BATTERY(R.string.bipe_app_notification_checkup_battery),
        PUSHER(R.string.bipe_app_notification_checkup_pusher),
    }

    /** State maker of the spec. */
    enum class Maker(val help: Int?) {
        XIAOMI(R.string.bipe_app_notification_checkup_help_xiaomi),
        SAMSUNG(R.string.bipe_app_notification_checkup_help_samsung),
        MOTOROLA(R.string.bipe_app_notification_checkup_help_motorola),
        OTHER(null),
    }

    private const val PREFS = "notification_checkup"
    private const val KEY_LAST_PUSH = "lastPushAt"
    private const val KEY_BANNER_CLOSED = "bannerClosedAt"
    private const val BANNER_PAUSE_MS = 7L * 24 * 60 * 60 * 1000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** State lastPush: written by PhoneNotifications.pushReceived for every push, any account. */
    fun pushArrived(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_PUSH, System.currentTimeMillis()).apply()
    }

    fun lastPush(context: Context): Long? = prefs(context).getLong(KEY_LAST_PUSH, 0L).takeIf { it > 0L }

    val maker: Maker
        get() = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> Maker.XIAOMI
            "samsung" -> Maker.SAMSUNG
            "motorola" -> Maker.MOTOROLA
            else -> Maker.OTHER
        }

    fun ignoresBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    /** State checks: true is passed. */
    fun run(context: Context, userId: String): Map<Check, Boolean> {
        val app = context.applicationContext as BipeApplication
        return mapOf(
            Check.PERMISSION to (app.push.permission.value == PushRegistration.Permission.ALLOWED),
            Check.CHANNEL to !app.notifications.messagesChannelBlocked(userId),
            Check.BATTERY to ignoresBatteryOptimizations(context),
            Check.PUSHER to (app.push.registration.value?.userId == userId),
        )
    }

    /** Spec banner: the permission or battery check fails, and the person did not close it in the last 7 days. */
    fun showsBanner(context: Context): Boolean {
        val app = context.applicationContext as BipeApplication
        val failing = app.push.permission.value != PushRegistration.Permission.ALLOWED || !ignoresBatteryOptimizations(context)
        val closedAt = prefs(context).getLong(KEY_BANNER_CLOSED, 0L)
        return failing && System.currentTimeMillis() - closedAt > BANNER_PAUSE_MS
    }

    fun closeBanner(context: Context) {
        prefs(context).edit().putLong(KEY_BANNER_CLOSED, System.currentTimeMillis()).apply()
    }

    /** Action fix battery: the system dialog, else the list of apps with battery optimisation. */
    @SuppressLint("BatteryLife")
    fun openBatteryFix(context: Context) {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))
        runCatching { context.startActivity(request) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    /** Action openAppSettings. */
    fun openAppSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
    }
}

/** Reads the checks again each time the screen resumes, so a fix in the system settings shows at once. */
@Composable
private fun rememberResumeCount(): Int {
    var count by remember { mutableIntStateOf(0) }
    val lifecycle = (LocalContext.current as? LifecycleOwner)?.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) count++ }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    return count
}

/** Spec notification-checkup. */
@Composable
fun NotificationCheckupScreen(userId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BipeApplication
    val colors = BipeTheme.colors
    val askPermission = rememberNotificationPermission(app.push)
    val permission by app.push.permission.collectAsState()
    val registration by app.push.registration.collectAsState()
    val resumed = rememberResumeCount()
    val checks = remember(resumed, permission, registration, userId) { NotificationCheckup.run(context, userId) }
    val lastPush = remember(resumed) { NotificationCheckup.lastPush(context) }

    fun fix(check: NotificationCheckup.Check) {
        when (check) {
            NotificationCheckup.Check.PERMISSION ->
                if (permission == PushRegistration.Permission.NOT_ASKED) {
                    askPermission()
                } else {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                }
            NotificationCheckup.Check.CHANNEL -> runCatching { context.startActivity(app.notifications.messagesChannelSettings(userId)) }
            NotificationCheckup.Check.BATTERY -> NotificationCheckup.openBatteryFix(context)
            NotificationCheckup.Check.PUSHER -> app.push.appBecameActive()
        }
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.NOTIFICATION_CHECKUP.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            Header(stringResource(R.string.bipe_app_notification_checkup_title), onBack)
            Text(
                stringResource(R.string.bipe_app_notification_checkup_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            NotificationCheckup.Check.entries.forEach { check ->
                val passed = checks[check] == true
                val stateText = stringResource(if (passed) R.string.bipe_app_notification_checkup_ok else R.string.bipe_app_notification_checkup_fix)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { stateDescription = stateText },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // The icon and the words both carry the state, never the colour alone.
                    Icon(
                        painterResource(if (passed) PhosphorIcon.CHECK_CIRCLE.drawable else PhosphorIcon.WARNING_CIRCLE.drawable),
                        contentDescription = null,
                        tint = if (passed) colors.statusSuccess else colors.trustCautionInk,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(check.label), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                        if (passed) Text(stateText, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                    }
                    if (!passed) {
                        BipeOutlinedButton(onClick = { fix(check) }) { Text(stateText) }
                    }
                }
            }
            Text(
                if (lastPush != null) {
                    val time = DateUtils.getRelativeTimeSpanString(lastPush, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                    stringResource(R.string.bipe_app_notification_checkup_last_push, time)
                } else {
                    stringResource(R.string.bipe_app_notification_checkup_last_push_none)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            NotificationCheckup.maker.help?.let { help ->
                Text(
                    stringResource(help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                TextButton(onClick = { NotificationCheckup.openAppSettings(context) }, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text(stringResource(R.string.bipe_app_notification_checkup_open_app_settings))
                }
            }
        }
    }
}

/** The notificationCheckup entry of chat-list-prompt (bipe-chat/bipe#894) holds. [closed] changes after a close. */
@Composable
fun notificationCheckupShows(closed: Int): Boolean {
    val context = LocalContext.current
    val app = context.applicationContext as BipeApplication
    val permission by app.push.permission.collectAsState()
    val resumed = rememberResumeCount()
    return remember(resumed, permission, closed) { NotificationCheckup.showsBanner(context) }
}

/** The notificationCheckup card of chat-list-prompt. [onOpen] opens the checkup; [onClose] after Agora não. */
@Composable
fun NotificationCheckupCard(onOpen: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    PromptCard(
        title = stringResource(R.string.bipe_app_notification_checkup_title),
        body = stringResource(R.string.bipe_app_notification_checkup_banner),
        primary = PromptAction(stringResource(R.string.bipe_app_notification_checkup_fix), onOpen),
        texts = listOf(
            PromptAction(stringResource(R.string.bipe_fallback_skip)) {
                NotificationCheckup.closeBanner(context)
                onClose()
            },
        ),
    )
}
