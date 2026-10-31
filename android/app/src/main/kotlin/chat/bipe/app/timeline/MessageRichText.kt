// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import chat.bipe.app.BipeTheme
import chat.bipe.app.MainActivity
import chat.bipe.app.people.PeopleLinks
import chat.bipe.shared.BipeInviteLink
import chat.bipe.shared.BipeMessageText
import chat.bipe.shared.BipeProfileLink

/** Compose link annotations expose individual links to TalkBack and the keyboard. */
@Composable
fun MessageRichText(message: TimelineMessage, canOpen: () -> Boolean) {
    val context = LocalContext.current
    val colors = BipeTheme.colors
    val parsed = remember(message.richText, message.body) { message.richText ?: BipeMessageText.parse(message.body.orEmpty()) }
    val value = buildAnnotatedString {
        if (message.isEmote) append("* ${message.senderName} ")
        for (run in parsed.runs) {
            val mention = run.link?.let(BipeProfileLink::matrixUserId) != null
            withStyle(SpanStyle(
                fontWeight = if (run.bold) FontWeight.Bold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                textDecoration = if (run.strike) TextDecoration.LineThrough else null,
                fontFamily = if (run.code) FontFamily.Monospace else null,
                background = if (mention) colors.surfaceSunken else Color.Unspecified,
            )) {
                val url = run.link
                if (url == null) append(run.text)
                else withLink(LinkAnnotation.Url(
                    url,
                    styles = TextLinkStyles(style = SpanStyle(color = colors.accentInk, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = {
                        if (canOpen()) {
                            val uri = Uri.parse(url)
                            // In a chat the account is signed in: a group link opens group-link-open (ADR 0184).
                            if (!PeopleLinks.offer(uri) && !PeopleLinks.offerGroupLink(uri)) {
                                val intent = if (BipeInviteLink.code(uri) != null) Intent(context, MainActivity::class.java) else Intent(Intent.ACTION_VIEW)
                                runCatching { context.startActivity(intent.setAction(Intent.ACTION_VIEW).setData(uri).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                            }
                        }
                    },
                )) { append(run.text) }
            }
        }
    }
    Text(value, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
}
