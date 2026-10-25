// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import android.util.Patterns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BadgePayload
import chat.bipe.shared.BipeNoticeLinks
import chat.bipe.shared.R
import java.text.DateFormat

/**
 * An official notice from a verified institution (spec chat-timeline, bipe#245): a card, not a chat bubble.
 * The institution comes from its signed badge only. The safety line is fixed; the sender cannot change it.
 */
@Composable
fun NoticeCard(message: TimelineMessage, institution: BadgePayload, menuLabel: String, onLongPress: () -> Unit) {
    val colors = BipeTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val time = remember(message.sentAt, locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(message.sentAt) }
    val linkColor = colors.accentInk
    val body = remember(message.body, institution.domains, linkColor) { noticeText(message.body.orEmpty(), institution.domains, SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) }
    val a11y = stringResource(R.string.bipe_notice_a11y, institution.name)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.trustVerifiedWash, RoundedCornerShape(12.dp))
            .border(2.dp, colors.trustVerifiedLine, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = {}, onLongClick = onLongPress, onLongClickLabel = menuLabel)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.clearAndSetSemantics { contentDescription = a11y },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(PhosphorIcon.SEAL_CHECK.drawable), contentDescription = null, tint = colors.trustVerifiedInk, modifier = Modifier.size(20.dp))
            Column {
                Text(institution.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = colors.inkPrimary)
                Text(stringResource(R.string.bipe_notice_level), style = MaterialTheme.typography.bodySmall, color = colors.trustVerifiedInk)
            }
        }
        Text(stringResource(R.string.bipe_notice_label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.inkSecondary)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
        HorizontalDivider(color = colors.trustVerifiedLine)
        Text(stringResource(R.string.bipe_notice_safety), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        Text(time, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
    }
}

/** The notice body with a link only where BipeNoticeLinks allows it (ADR 0036). Every other link stays plain text. */
internal fun noticeText(body: String, domains: List<String>, linkStyle: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(body)
    if (domains.isEmpty()) return@buildAnnotatedString
    val matcher = Patterns.WEB_URL.matcher(body)
    while (matcher.find()) {
        val raw = matcher.group()
        if (BipeNoticeLinks.isClickable(raw, domains)) {
            addLink(LinkAnnotation.Url(raw, TextLinkStyles(style = linkStyle)), matcher.start(), matcher.end())
        }
    }
}
