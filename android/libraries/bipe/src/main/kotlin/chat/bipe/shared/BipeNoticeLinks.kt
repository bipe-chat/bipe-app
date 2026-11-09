// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.core.noticeDomainValid
import chat.bipe.core.noticeLinkClickable

/**
 * Links in an official notice (ADR 0036, bipe-chat/bipe#245). The rule runs in bipe-core (`noticeLinkClickable`,
 * ADR 0267) and matches bipe-shared tools/lib/notice.mjs and test-vectors/notice-links.json. Fail closed.
 */
object BipeNoticeLinks {
    /** A verified domain in an `institution` badge: lower case, at least one dot. */
    fun isDomain(value: String): Boolean = noticeDomainValid(value)

    /** True when [url] may be a clickable link in a notice from an institution with the verified [domains]. */
    fun isClickable(url: String, domains: List<String>): Boolean = noticeLinkClickable(url, domains)
}
