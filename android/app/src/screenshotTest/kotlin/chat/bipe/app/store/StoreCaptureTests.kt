// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.store

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.chatlist.ChatRequestSummary
import chat.bipe.app.chatlist.ChatRequestsList
import chat.bipe.app.chatlist.ChatRow
import chat.bipe.app.chatlist.ChatSummary
import chat.bipe.app.common.Avatar
import chat.bipe.app.people.CompanySealFacts
import chat.bipe.app.people.PeopleScaffold
import chat.bipe.app.people.ProfileLinkContent
import chat.bipe.app.pixcheck.PixCheckReceiveModel
import chat.bipe.app.pixcheck.PixCheckScreen
import chat.bipe.app.pixcheck.PixCheckedItem
import chat.bipe.app.store.generated.StoreCaptures
import chat.bipe.app.store.generated.StoreMessage
import chat.bipe.app.store.generated.StorePerson
import chat.bipe.app.store.photos.StorePhotos
import chat.bipe.app.timeline.Bubble
import chat.bipe.app.timeline.MessageRichText
import chat.bipe.app.timeline.MessageTimestamp
import chat.bipe.app.timeline.ScamWarningCard
import chat.bipe.app.timeline.TimelineMessage
import chat.bipe.shared.BadgeCnpjMethod
import chat.bipe.shared.BadgeLevel
import chat.bipe.shared.BadgePayload
import chat.bipe.shared.BipeProfileLink
import chat.bipe.shared.PixCheck
import chat.bipe.shared.R
import chat.bipe.shared.ScamWarningKind
import chat.bipe.shared.company.BipeCompanySealState
import chat.bipe.shared.generated.BipeScreen
import com.android.tools.screenshot.PreviewTest
import java.util.Base64
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

/** A cast photo of StorePhotos (tools/store-capture-photos.mjs). */
private fun photo(id: String): ImageBitmap {
    val bytes = Base64.getDecoder().decode(StorePhotos.jpeg.getValue(id))
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
}

/** The top of a chat: its avatar and name. */
@Composable
private fun StoreChatHeader(chat: StorePerson) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(chat.name, null, null, size = 40.dp, isCompany = chat.isCompany)
        Text(chat.name, style = MaterialTheme.typography.titleMedium, color = BipeTheme.colors.inkPrimary)
    }
}

/** Sample messages as timeline bubbles. In a group, a message from another person carries the sender's short name. */
@Composable
private fun StoreMessages(messages: List<StoreMessage>, isGroup: Boolean) {
    val colors = BipeTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        messages.forEachIndexed { index, sample ->
            val message = timelineMessage(index, sample)
            Bubble(isOwn = message.isOwn, isPlain = sample.sticker != null) {
                if (isGroup && sample.from != null) {
                    Text(sample.from.short, style = MaterialTheme.typography.labelLarge, color = colors.accentInk)
                }
                val media = sample.image ?: sample.sticker
                if (media != null) {
                    Image(
                        photo(media),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(if (sample.sticker != null) 200.dp else 260.dp).clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    MessageRichText(message, canOpen = { true })
                }
                sample.warning?.let { id -> ScamWarningCard(ScamWarningKind.entries.first { it.id == id }, pauseSecondsLeft = 0) }
                MessageTimestamp(message.sentAt, Modifier.align(Alignment.End))
            }
        }
    }
}

/** aviso-golpe: a DM from a new account that copies a mother's name, with the scam warnings of spec scam-warning. */
@Composable
private fun AvisoGolpe() {
    StoreFrame {
        Column(Modifier.fillMaxSize()) {
            StoreChatHeader(StoreCaptures.scamChat)
            StoreMessages(StoreCaptures.scamMessages, isGroup = false)
        }
    }
}

/** selo-cnpj: a company's chat with the facts of spec company-seal under it. */
@Composable
private fun SeloCnpj() {
    val colors = BipeTheme.colors
    val company = StoreCaptures.sealCompany
    val now = System.currentTimeMillis() / 1000
    val payload = BadgePayload(
        sub = "@${company.handle}:${BipeProfileLink.HOST}",
        lvl = BadgeLevel.CNPJ,
        name = company.name,
        cnpj = StoreCaptures.sealCnpj,
        msk = "store-capture",
        legalName = StoreCaptures.sealLegalName,
        tradeName = company.name,
        method = BadgeCnpjMethod.ECNPJ,
        checkedMonth = StoreCaptures.sealCheckedMonth,
        iat = now,
        exp = now + 365L * 24 * 3600,
    )
    StoreFrame {
        Column(Modifier.fillMaxSize()) {
            StoreChatHeader(company)
            StoreMessages(StoreCaptures.sealMessages, isGroup = false)
            Column(Modifier.weight(1f)) {}
            Surface(color = colors.surfaceRaised, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.bipe_app_seal_title), style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
                    CompanySealFacts(BipeCompanySealState.Verified(payload))
                }
            }
        }
    }
}

/** grupos: spec chat-list in the Grupos scope; only the group with a mention has a count that rings. */
@Composable
private fun Grupos() {
    val colors = BipeTheme.colors
    StoreFrame {
        Column(Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.bipe_tab_groups),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.inkPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            StoreCaptures.groups.forEachIndexed { index, group ->
                val chat = ChatSummary(
                    roomId = "!store-group-$index:${BipeProfileLink.HOST}",
                    name = group.group.name,
                    avatarUrl = null,
                    lastMessage = "${group.from.short}: ${group.text}",
                    lastActivity = today(group.time),
                    unreadCount = group.unread,
                    isDirect = false,
                    memberCount = 12,
                    mentionCount = group.mentions,
                    isMuted = group.muted,
                )
                ChatRow(chat, media = null, onClick = {})
            }
        }
    }
}

/** stickers: a group chat with a photo and the sticker made from a picture (spec timeline-media). */
@Composable
private fun Stickers() {
    StoreFrame {
        Column(Modifier.fillMaxSize()) {
            StoreChatHeader(StoreCaptures.stickerChat)
            StoreMessages(StoreCaptures.stickerMessages, isGroup = true)
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

@PreviewTest
@Preview(name = "pt-BR", device = PHONE, locale = "pt-rBR")
@Composable
fun StoreSeloCnpjPtBR() = SeloCnpj()

@PreviewTest
@Preview(name = "en-US", device = PHONE, locale = "en")
@Composable
fun StoreSeloCnpjEnUS() = SeloCnpj()

@PreviewTest
@Preview(name = "pt-BR", device = PHONE, locale = "pt-rBR")
@Composable
fun StoreGruposPtBR() = Grupos()

@PreviewTest
@Preview(name = "en-US", device = PHONE, locale = "en")
@Composable
fun StoreGruposEnUS() = Grupos()

@PreviewTest
@Preview(name = "pt-BR", device = PHONE, locale = "pt-rBR")
@Composable
fun StoreStickersPtBR() = Stickers()

@PreviewTest
@Preview(name = "en-US", device = PHONE, locale = "en")
@Composable
fun StoreStickersEnUS() = Stickers()
