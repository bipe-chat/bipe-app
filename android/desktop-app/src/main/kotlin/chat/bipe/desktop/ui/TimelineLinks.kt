// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

private val roomLinkCandidate = Regex(
    """(?<![\w@/])(?:https://matrix\.to/#/(?:[!#]|%21|%23)|matrix:(?:r|roomid|roomalias|room)/)[^\s<>"']+""",
)
private val enclosingBrackets = listOf('(' to ')', '[' to ']', '{' to '}')

/** Annotate visible Matrix URLs while preserving the exact text for selection and copying. */
@Composable internal fun TimelineLinks(text: String, colors: DesktopColors, openLink: (String) -> Unit) {
    val linkStyle = TextLinkStyles(style = SpanStyle(color = colors["accent.ink"], textDecoration = TextDecoration.Underline))
    val candidates = remember(text) { roomLinkCandidate.findAll(text).toList() }
    val annotated = buildAnnotatedString {
        var cursor = 0
        for (match in candidates) {
            var url = match.value.trimEnd('.', ',', ';', '!', '?')
            for ((open, close) in enclosingBrackets) {
                while (url.endsWith(close) && url.count { it == close } > url.count { it == open }) url = url.dropLast(1)
            }
            append(text.substring(cursor, match.range.first))
            withLink(LinkAnnotation.Url(url, linkStyle) { openLink(url) }) { append(url) }
            cursor = match.range.first + url.length
        }
        append(text.substring(cursor))
    }
    SelectionContainer { Text(annotated) }
}
