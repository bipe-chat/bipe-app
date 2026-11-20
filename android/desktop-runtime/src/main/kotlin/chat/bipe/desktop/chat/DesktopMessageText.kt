// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.shared.BipeMessageText
import org.matrix.rustcomponents.sdk.MessageFormat
import org.matrix.rustcomponents.sdk.MessageType

private val bareRoomLink = Regex("""(?<![\w@/])matrix:(?:r|roomid|roomalias|room)/[^\s<>"']+""")
private val enclosingBrackets = listOf('(' to ')', '[' to ']', '{' to '}')

internal fun desktopMessageText(body: String, type: MessageType? = null): BipeMessageText {
    val formatted = when (type) {
        is MessageType.Text -> type.content.formatted
        is MessageType.Image -> type.content.formattedCaption
        is MessageType.Video -> type.content.formattedCaption
        is MessageType.File -> type.content.formattedCaption
        is MessageType.Audio -> type.content.formattedCaption
        else -> null
    }
    val html = formatted?.takeIf { it.format == MessageFormat.Html }?.body
    val parsed = BipeMessageText.parse(body, html)
    // The shared scanner handles web and contact links. The Windows link handler also accepts a bare matrix: URI.
    return BipeMessageText(parsed.runs.flatMap { run ->
        if (run.link != null || run.code || "matrix:" !in run.text) return@flatMap listOf(run)
        buildList {
            var cursor = 0
            for (match in bareRoomLink.findAll(run.text)) {
                var value = match.value.trimEnd('.', ',', ';', '!', '?')
                for ((open, close) in enclosingBrackets) {
                    while (value.endsWith(close) && value.count { it == close } > value.count { it == open }) value = value.dropLast(1)
                }
                val link = BipeMessageText.safeLink(value) ?: continue
                if (match.range.first > cursor) add(run.copy(text = run.text.substring(cursor, match.range.first)))
                add(run.copy(text = value, link = link))
                cursor = match.range.first + value.length
            }
            if (cursor < run.text.length) add(run.copy(text = run.text.substring(cursor)))
        }
    })
}

internal fun messageSafetyText(body: String, richText: BipeMessageText?): String {
    val additional = richText?.safetyText ?: return body
    return if (additional == body) body else "$body\n$additional"
}
