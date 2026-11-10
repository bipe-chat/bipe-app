// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.safety

import chat.bipe.core.ScamKind
import chat.bipe.core.scamWarningKind
import java.util.Locale
import kotlinx.coroutines.CancellationException

sealed interface MessageSafety {
    data object NotApplicable : MessageSafety
    data object Checking : MessageSafety
    data object Clear : MessageSafety
    data class Warning(val kind: ScamKind) : MessageSafety
    data object Unavailable : MessageSafety
}

interface SafetyEngine {
    fun warning(body: String): MessageSafety
}

/** Presentation adapter only: matching, normalization and warning priority run in shared Rust. */
object CoreSafety : SafetyEngine {
    override fun warning(body: String): MessageSafety = boundary(
        operation = { scamWarningKind(body, emptyList())?.let { MessageSafety.Warning(it) } ?: MessageSafety.Clear },
        unavailable = { MessageSafety.Unavailable },
    )

    /** Catch ABI/library availability failures separately from a successful no-warning result. No cause is logged. */
    internal fun <T> boundary(operation: () -> T, unavailable: () -> T): T = try { operation() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { unavailable() }
    catch (_: LinkageError) { unavailable() }
}

/** Own/deleted/undecryptable content never enters the detector. Each edit is assessed from its current body. */
fun messageSafety(body: String, own: Boolean, readableText: Boolean, engine: SafetyEngine = CoreSafety): MessageSafety =
    if (own || !readableText) MessageSafety.NotApplicable
    else CoreSafety.boundary({ engine.warning(body) }, { MessageSafety.Unavailable })

/** UniFFI enum spelling to registry id/translation key; no detector rules or warning priority live here. */
fun ScamKind.registryId(): String = name.lowercase(Locale.ROOT).split('_').mapIndexed { index, word ->
    if (index == 0) word else word.replaceFirstChar { it.uppercaseChar() }
}.joinToString("")

fun ScamKind.messageKey(): String = "bipe_scam_warning_${name.lowercase(Locale.ROOT)}"
