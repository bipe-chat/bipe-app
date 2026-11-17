// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import chat.bipe.desktop.chat.ChatRow
import chat.bipe.desktop.chat.DesktopRoomList
import chat.bipe.desktop.chat.MessageAlerts
import chat.bipe.desktop.chat.NotifyMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Windows notifications through the tray icon (spec notification-service, computer rule). A click on a notification
 * or on the tray icon brings the window forward and opens the newest alerted chat of the account that alerted.
 */
class DesktopAlerts(private val tray: TrayState) {
    /** Room to open after a click, with the account it belongs to. */
    data class Route(val userId: String, val roomId: String)

    private var latest: Route? = null
    var requested by mutableStateOf<Route?>(null)
        private set
    var raise by mutableStateOf(0)
        private set

    fun show(title: String, body: String, route: Route) {
        latest = route
        tray.sendNotification(Notification(title, body, Notification.Type.None))
    }

    /** The tray action: AWT reports a click on the icon and on its notification the same way. */
    fun clicked() {
        requested = latest
        raise++
    }

    fun consumed(route: Route) { if (requested == route) requested = null }

    /** Sign-out or account switch: a later click must never open this account's chat in another account. */
    fun forget(userId: String) {
        if (latest?.userId == userId) latest = null
        if (requested?.userId == userId) requested = null
    }
}

val LocalDesktopAlerts = staticCompositionLocalOf<DesktopAlerts?> { null }

/** Watches one account's chat list and posts its alerts; [openRoom] is the chat shown in the timeline. */
@Composable internal fun MessageAlertEffect(userId: String, list: DesktopRoomList, rows: List<ChatRow>, openRoom: String?, s: DesktopCatalog) {
    val sink = LocalDesktopAlerts.current ?: return
    val focused = LocalWindowInfo.current.isWindowFocused
    val alerts = remember(userId, list) { MessageAlerts(System.currentTimeMillis()) }
    // Posting outlives a row update that arrives while a default mode loads; the account's lifetime still ends it.
    val posting = rememberCoroutineScope()
    DisposableEffect(userId, sink) { onDispose { sink.forget(userId) } }
    LaunchedEffect(alerts, rows, openRoom, focused) {
        val update = alerts.update(rows) { focused && it == openRoom }
        if (update.alerts.isEmpty()) return@LaunchedEffect
        val labels = s.chatLabels()
        posting.launch {
            for (alert in update.alerts) {
                // As on the Mac, a default that cannot be read alerts rather than losing a message; a room rule is on the row.
                val mode = alert.row.notifyMode ?: try { list.defaultNotifyMode(alert.row.isDm, alert.row.encrypted) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { NotifyMode.ALL }
                if (!MessageAlerts.alerts(mode, alert.newMention)) { alerts.dropped(alert.row.id); continue }
                val (title, body) = MessageAlerts.content(alert.row, labels, s.text("bipe_app_name"), s.text("bipe_app_notification_new_message"))
                sink.show(title, body, DesktopAlerts.Route(userId, alert.row.id))
            }
        }
    }
}
