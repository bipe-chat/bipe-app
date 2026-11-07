// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.company

import android.content.Context
import androidx.compose.runtime.Immutable
import chat.bipe.shared.BadgeLevel
import chat.bipe.shared.BadgePayload
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.PersonScheme

/** One valid person proof of an account (ADR 0275): a badge that verified on this device, with its scheme. */
@Immutable
data class BipePersonProof(val scheme: PersonScheme, val payload: BadgePayload) {
    /** `sealCheck` fill for assurance state, `sealCheck` regular for assurance bank. */
    val isFill: Boolean get() = scheme.mark == "fill"

    fun schemeName(context: Context): String = scheme.localizedName(context)

    /** The label and the profile line: "Nome verificado via gov.br". */
    fun line(context: Context): String = context.getString(R.string.bipe_badge_verified_via, schemeName(context))
}

/** The name of the scheme in the app's language (registries/schemes.json). pt-BR is the default language. */
fun PersonScheme.localizedName(context: Context): String =
    if (context.resources.configuration.locales[0].language == "en") nameEn else namePtBR

/** Port of bipe-shared `personProofs` (tools/lib/registries.mjs); test-vectors/person-proofs.json must pass. */
object BipePersonProofs {
    /** A badge level and, for `lvl` person, its scheme. */
    data class Badge(val lvl: String, val scheme: String?)

    /** The person schemes of these badges, strongest first, one per scheme. Anything outside the registry gives nothing (fail closed). */
    fun schemes(badges: List<Badge>): List<PersonScheme> {
        val all = BipeRegistries.personSchemes
        val found = badges.mapNotNull { badge ->
            (if (badge.lvl == "person") all.firstOrNull { it.id == badge.scheme } else all.firstOrNull { it.alias == badge.lvl })?.id
        }
        return all.filter { it.id in found }
    }

    /** The proofs of badges that already passed the verifier, strongest first. */
    fun proofs(payloads: List<BadgePayload>): List<BipePersonProof> =
        schemes(payloads.map { Badge(it.lvl.alias, null) }).mapNotNull { scheme ->
            payloads.firstOrNull { it.lvl.alias == scheme.alias }?.let { BipePersonProof(scheme, it) }
        }

    /** The registry alias of a person level. Any other level has none and gives no proof. */
    private val BadgeLevel.alias: String
        get() = when (this) {
            BadgeLevel.PIX -> "pix"
            BadgeLevel.GOVBR -> "govbr"
            else -> ""
        }
}
