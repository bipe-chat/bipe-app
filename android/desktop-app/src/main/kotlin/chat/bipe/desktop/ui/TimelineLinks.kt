// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import chat.bipe.shared.BipeMessageText

/** Compose link annotations expose each destination to the keyboard and accessibility services. */
@Composable internal fun TimelineMessageText(text: BipeMessageText, colors: DesktopColors, linksEnabled: Boolean, prefix: String = "", openLink: (String) -> Unit) {
    val linkStyle = TextLinkStyles(style = SpanStyle(color = colors["accent.ink"], textDecoration = TextDecoration.Underline))
    val annotated = buildAnnotatedString {
        append(prefix)
        for (run in text.runs) {
            withStyle(SpanStyle(
                fontWeight = if (run.bold) FontWeight.Bold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                fontFamily = if (run.code) FontFamily.Monospace else null,
                textDecoration = if (run.strike) TextDecoration.LineThrough else null,
            )) {
                val url = run.link
                if (url != null && linksEnabled) withLink(LinkAnnotation.Url(url, linkStyle) { openLink(url) }) { append(run.text) }
                else append(run.text)
            }
        }
    }
    SelectionContainer { Text(annotated) }
}
