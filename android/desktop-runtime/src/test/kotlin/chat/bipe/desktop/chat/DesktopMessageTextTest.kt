// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.FormattedBody
import org.matrix.rustcomponents.sdk.MessageFormat
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.TextMessageContent

class DesktopMessageTextTest {
    @Test fun formattedLinkDestinationsAreIncludedInTheScamCheck() {
        val body = "Confirm the payment"
        val destination = "https://example.test/payment"
        val type = MessageType.Text(TextMessageContent(body,
            FormattedBody(MessageFormat.Html, "<strong><a href=\"$destination\">$body</a></strong>")))
        val parsed = desktopMessageText(body, type)
        val run = parsed.runs.single()
        assertEquals(body, run.text)
        assertTrue(run.bold)
        assertEquals(destination, run.link)
        assertTrue(messageSafetyText(body, parsed).contains(destination))
    }

    @Test fun bareMatrixLinksKeepSurroundingPunctuationAndCodeRemainsUnlinked() {
        val uri = "matrix:r/group:example.test"
        val body = "Open ($uri)."
        val parsed = desktopMessageText(body)
        assertEquals(body, parsed.text)
        assertEquals(listOf(uri), parsed.runs.mapNotNull { it.link })

        val type = MessageType.Text(TextMessageContent(uri, FormattedBody(MessageFormat.Html, "<code>$uri</code>")))
        val code = desktopMessageText(uri, type).runs.single()
        assertTrue(code.code)
        assertEquals(null, code.link)
    }

    @Test fun scriptContentAndUnsafeDestinationsStayOutOfRenderedRuns() {
        val body = "Read more"
        val html = "<script>ignored</script><a href=\"javascript:alert(1)\">$body</a>"
        val type = MessageType.Text(TextMessageContent(body, FormattedBody(MessageFormat.Html, html)))
        val parsed = desktopMessageText(body, type)
        assertEquals(body, parsed.text)
        assertFalse(parsed.runs.any { it.link != null })
        assertEquals(body, messageSafetyText(body, parsed))
    }
}
