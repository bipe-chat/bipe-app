// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeMessageFormatting
import java.net.URI
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Parses strings only. It never loads images, styles, scripts or other remote resources. */
data class BipeMessageText(val runs: List<Run>) {
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val link: String? = null,
    )
    val text: String get() = runs.joinToString("") { it.text }
    /** Includes destinations hidden behind a friendly label. */
    val safetyText: String get() = (listOf(text) + runs.mapNotNull { it.link }).joinToString("\n")

    companion object {
        fun parse(body: String, html: String? = null): BipeMessageText {
            val parsed = html?.takeIf { it.length <= BipeMessageFormatting.maxHtmlLength }
                ?.let { runCatching { Builder().parse(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }
            return BipeMessageText(linkify(parsed ?: listOf(Run(body))))
        }

        fun safeLink(raw: String): String? {
            if (raw.isEmpty() || raw.any { it.isWhitespace() || it.code < 32 || it.code == 127 }) return null
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme !in BipeMessageFormatting.safeSchemes) return null
            if (scheme == "https" || scheme == "http") {
                if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
            }
            return raw
        }

        private fun linkify(runs: List<Run>): List<Run> = runs.flatMap { run ->
            if (run.link != null || run.code) return@flatMap listOf(run)
            val candidates = listOf(BipeMessageFormatting.linkPattern, BipeMessageFormatting.phonePattern)
                .flatMapIndexed { index, pattern -> Regex(pattern, RegexOption.IGNORE_CASE).findAll(run.text).map { it to index }.toList() }
                .sortedWith(compareBy({ it.first.range.first }, { it.second }))
            val result = mutableListOf<Run>()
            var cursor = 0
            for ((match, kind) in candidates) {
                if (match.range.first < cursor) continue
                var value = match.value.trimEnd('.', ',', ';', '!', '?')
                for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}')) {
                    while (value.endsWith(close) && value.count { it == close } > value.count { it == open }) value = value.dropLast(1)
                }
                val destination = when {
                    kind == 1 -> "tel:" + value.filter { it.isDigit() || it == '+' }
                    value.startsWith("www.", ignoreCase = true) -> "https://$value"
                    '@' in value && "://" !in value -> "mailto:$value"
                    else -> value
                }
                val link = safeLink(destination) ?: continue
                if (value.isEmpty()) continue
                if (match.range.first > cursor) result += run.copy(text = run.text.substring(cursor, match.range.first))
                result += run.copy(text = value, link = link)
                cursor = match.range.first + value.length
            }
            if (cursor < run.text.length) result += run.copy(text = run.text.substring(cursor))
            result
        }
    }

    private class Builder {
        val runs = mutableListOf<Run>()
        val lists = mutableListOf<Pair<Boolean, Int>>()
        var prefixPending = false
        fun parse(html: String): List<Run> {
            visit(Jsoup.parseBodyFragment(html).body(), Run(""), 0)
            while (runs.lastOrNull()?.text?.lastOrNull()?.isWhitespace() == true) {
                runs[runs.lastIndex] = runs.last().copy(text = runs.last().text.dropLast(1))
                if (runs.last().text.isEmpty()) runs.removeAt(runs.lastIndex)
            }
            return runs
        }
        fun append(raw: String, style: Run, preserve: Boolean = false) {
            var value = if (preserve) raw else raw.replace(Regex("[\\t\\r\\n ]+"), " ")
            if (!preserve && (runs.isEmpty() || runs.last().text.lastOrNull()?.isWhitespace() == true)) value = value.trimStart(' ')
            if (value.isNotEmpty()) {
                runs += style.copy(text = value)
                prefixPending = false
            }
        }
        fun line() {
            if (runs.isNotEmpty() && !runs.last().text.endsWith("\n")) runs += Run("\n")
            prefixPending = false
        }
        fun visit(node: Node, style: Run, depth: Int) {
            require(depth <= BipeMessageFormatting.maxDepth)
            if (node is TextNode) { append(node.wholeText, style, style.code); return }
            if (node !is Element) return
            val tag = node.normalName()
            if (tag in BipeMessageFormatting.dropContentTags) return
            if (tag == "br") { runs += Run("\n"); prefixPending = false; return }
            val next = style.copy(
                bold = style.bold || tag in BipeMessageFormatting.boldTags,
                italic = style.italic || tag in BipeMessageFormatting.italicTags,
                strike = style.strike || tag in BipeMessageFormatting.strikeTags,
                code = style.code || tag in BipeMessageFormatting.codeTags,
                link = if (tag == "a") safeLink(node.attr("href")) else style.link,
            )
            val block = tag in BipeMessageFormatting.blockTags
            if (block && !prefixPending) line()
            if (tag == "blockquote") { append("│ ", Run("")); prefixPending = true }
            if (tag == "ul" || tag == "ol") {
                line()
                lists += (tag == "ol") to (node.attr("start").toIntOrNull() ?: 1).coerceIn(1, 1000)
            }
            if (tag == "li") {
                line()
                val item = lists.lastOrNull()
                val prefix = "  ".repeat((lists.size - 1).coerceAtLeast(0)) + if (item?.first == true) "${item.second}. " else "• "
                append(prefix, Run(""), preserve = true)
                prefixPending = true
                if (item != null) lists[lists.lastIndex] = item.first to item.second + 1
            }
            node.childNodes().forEach { visit(it, next, depth + 1) }
            if (tag == "ul" || tag == "ol") { lists.removeAt(lists.lastIndex); line() }
            if (block || tag == "li") line()
        }
    }
}
