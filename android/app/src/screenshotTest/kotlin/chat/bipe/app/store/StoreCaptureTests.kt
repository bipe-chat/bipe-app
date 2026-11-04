// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.chatlist.ChatRequestSummary
import chat.bipe.app.chatlist.ChatRequestsList
import chat.bipe.app.common.Avatar
import chat.bipe.app.people.PeopleScaffold
import chat.bipe.app.people.ProfileLinkContent
import chat.bipe.app.pixcheck.PixCheckReceiveModel
import chat.bipe.app.pixcheck.PixCheckScreen
import chat.bipe.app.pixcheck.PixCheckedItem
import chat.bipe.app.store.generated.StoreCaptures
import chat.bipe.app.store.generated.StoreMessage
import chat.bipe.app.timeline.Bubble
import chat.bipe.app.timeline.MessageRichText
import chat.bipe.app.timeline.MessageTimestamp
import chat.bipe.app.timeline.ScamWarningCard
import chat.bipe.app.timeline.TimelineMessage
import chat.bipe.shared.BipeProfileLink
import chat.bipe.shared.PixCheck
import chat.bipe.shared.R
import chat.bipe.shared.ScamWarningKind
import chat.bipe.shared.generated.BipeScreen
import com.android.tools.screenshot.PreviewTest
import java.util.Calendar

// Store captures of the Play listing (bipe-chat/bipe#457): one preview per slug of the bipe-shared
// appstore.md screenshot table and per locale, built from the app's own Compose screens and the sample
// data in bipe-shared product/store-captures.json (generated/StoreCaptures.kt). The Compose Preview
// Screenshot Testing plugin renders them on the JVM at 1080x1920: `./gradlew :app:updatePlayDebugScreenshotTest`
// in CI (store-captures.yml), then `node tools/store-captures.mjs` copies them into the bipe-web layout.
// A function is named Store<Slug in camel case><PtBR|EnUS>; tools/store-captures.mjs reads the name.

private const val PHONE = "spec:width=1080px,height=1920px,dpi=480"

@Composable
private fun StoreFrame(content: @Composable () -> Unit) {
    BipeTheme {
        Surface(Modifier.fillMaxSize(), color = BipeTheme.colors.surfaceBase) { content() }
    }
}

/** A sample time of today, from "HH:MM". */
private fun today(time: String): Long = Calendar.getInstance().apply {
    val (hour, minute) = time.split(":").map(String::toInt)
    set(Calendar.HOUR_OF_DAY, hour)
    set(Calendar.MINUTE, minute)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun timelineMessage(index: Int, sample: StoreMessage): TimelineMessage {
    val sender = sample.from ?: StoreCaptures.viewer
    return TimelineMessage(
        id = "store-$index",
        kind = TimelineMessage.Kind.TEXT,
        body = sample.text,
        senderId = "@${sender.handle}:${BipeProfileLink.HOST}",
        senderName = sender.name,
        isOwn = sample.from == null,
        sentAt = today(sample.time),
        sendState = TimelineMessage.SendState.SENT,
        cause = null,
    )
}

/** aviso-golpe: a DM from a new account that copies a mother's name, with the scam warnings of spec scam-warning. */
@Composable
private fun AvisoGolpe() {
    val colors = BipeTheme.colors
    val chat = StoreCaptures.scamChat
    StoreFrame {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(chat.name, null, null, size = 40.dp, isCompany = chat.isCompany)
                Text(chat.name, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StoreCaptures.scamMessages.forEachIndexed { index, sample ->
                    val message = timelineMessage(index, sample)
                    Bubble(isOwn = message.isOwn) {
                        MessageRichText(message, canOpen = { true })
                        sample.warning?.let { id -> ScamWarningCard(ScamWarningKind.entries.first { it.id == id }, pauseSecondsLeft = 0) }
                        MessageTimestamp(message.sentAt, Modifier.align(Alignment.End))
                    }
                }
            }
        }
    }
}

/** pedidos: the box of spec chat-requests. */
@Composable
private fun Pedidos() {
    val requests = StoreCaptures.requests.mapIndexed { index, request ->
        ChatRequestSummary(
            roomId = "!store-$index:${BipeProfileLink.HOST}",
            inviterId = "@${request.from.handle}:${BipeProfileLink.HOST}",
            name = request.from.name,
            inviterName = request.from.name,
            avatarUrl = null,
            trust = ChatRequestSummary.Trust.valueOf(request.trust.uppercase()),
            invitedAt = null,
            isDm = true,
        )
    }
    StoreFrame {
        ChatRequestsList(requests, trustOf = { it.trust }, hidePhotos = true, media = null, onBack = {}, onOpen = {}, onOpenWhoCanMessage = {})
    }
}

/** conferir-pix: spec pix-check-result for a BR Code, checked by the app's own PixCheck. */
@Composable
private fun ConferirPix() {
    val code = StoreCaptures.pixCode
    val state = PixCheckReceiveModel.State(phase = PixCheckReceiveModel.Phase.READY, results = listOf(PixCheckedItem(0, code, PixCheck.check(code))))
    StoreFrame {
        PixCheckScreen(state, onCancel = {}, onClose = {}, onRetry = {})
    }
}

/** seu-arroba: spec profile-link with the QR code open. */
@Composable
private fun SeuArroba() {
    val link = StoreCaptures.handleOwner.handle?.let(BipeProfileLink::url) ?: return
    StoreFrame {
        PeopleScaffold(stringResource(R.string.bipe_app_profile_link), BipeScreen.PROFILE_LINK.id, onBack = {}) {
            ProfileLinkContent(link, showingQr = true, onShare = {}, onCopy = {}, onToggleQr = {})
        }
    }
}

@PreviewTest
@Preview(name = "pt-BR", device = PHONE, locale = "pt-rBR")
@Composable
fun StoreAvisoGolpePtBR() = AvisoGolpe()

@PreviewTest
@Preview(name = "en-US", device = PHONE, locale = "en")
@Composable
fun StoreAvisoGolpeEnUS() = AvisoGolpe()

@PreviewTest
@Preview(name = "pt-BR", device = PHONE, locale = "pt-rBR")
@Composable
fun StorePedidosPtBR() = Pedidos()

@PreviewTest
@Preview(name = "en-US", device = PHONE, locale = "en")
@Composable
fun StorePedidosEnUS() = Pedidos()

@PreviewTest
@Preview(name = "pt-BR", device = PHONE, locale = "pt-rBR")
@Composable
fun StoreConferirPixPtBR() = ConferirPix()

@PreviewTest
@Preview(name = "en-US", device = PHONE, locale = "en")
@Composable
fun StoreConferirPixEnUS() = ConferirPix()

@PreviewTest
@Preview(name = "pt-BR", device = PHONE, locale = "pt-rBR")
@Composable
fun StoreSeuArrobaPtBR() = SeuArroba()

@PreviewTest
@Preview(name = "en-US", device = PHONE, locale = "en")
@Composable
fun StoreSeuArrobaEnUS() = SeuArroba()
