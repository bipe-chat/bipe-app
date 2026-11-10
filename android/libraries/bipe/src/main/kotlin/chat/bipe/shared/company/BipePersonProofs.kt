// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.company

import android.content.Context
import androidx.compose.runtime.Immutable
import chat.bipe.shared.BadgeLevel
import chat.bipe.shared.BadgePayload
import chat.bipe.shared.ListType
import chat.bipe.shared.R
import chat.bipe.shared.SignedLists
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

/**
 * Scheme names (ADR 0272). A Brazilian scheme has its name in the app build. Another country's scheme takes its name
 * from the newest valid issuer registry, so a new country needs no app release. Null: the scheme is unknown.
 */
object BipeSchemeNames {
    fun name(
        id: String,
        english: Boolean,
        registryJson: String? = SignedLists.shared.current(ListType.ISSUERS)?.payloadJson,
    ): String? {
        BipeRegistries.personSchemes.firstOrNull { it.id == id }?.let { return if (english) it.nameEn else it.namePtBR }
        val root = registryJson?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.getOrNull() }
            as? kotlinx.serialization.json.JsonObject ?: return null
        val scheme = (root["schemes"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            ?.firstOrNull { (it["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content == id } ?: return null
        val names = scheme["names"] as? kotlinx.serialization.json.JsonObject ?: return null
        fun text(key: String) = (names[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        return (if (english) text("en") else text("pt-BR")) ?: text("pt-BR") ?: text("en")
    }
}

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

    /**
     * The proofs of badges that already passed the verifier, strongest first. Brazilian schemes come first in their
     * registry order. A person scheme of another country follows, state before bank, named from the issuer registry
     * (ADR 0272). A scheme with no name anywhere gives no proof (fail closed).
     */
    fun proofs(payloads: List<BadgePayload>, english: Boolean = false): List<BipePersonProof> {
        val local = schemes(payloads.map { Badge(if (it.lvl == BadgeLevel.PERSON) "person" else it.lvl.alias, it.scheme) }).mapNotNull { scheme ->
            payloads.firstOrNull { it.lvl.alias == scheme.alias || (it.lvl == BadgeLevel.PERSON && it.scheme == scheme.id) }
                ?.let { BipePersonProof(scheme, it) }
        }
        val foreign = payloads.mapNotNull { payload ->
            val info = payload.schemeInfo ?: return@mapNotNull null
            if (payload.lvl != BadgeLevel.PERSON || BipeRegistries.personSchemes.any { it.id == info.id }) return@mapNotNull null
            val name = BipeSchemeNames.name(info.id, english) ?: return@mapNotNull null
            val mark = if (info.assurance == "state") "fill" else "regular"
            BipePersonProof(PersonScheme(info.id, null, info.assurance, mark, name, name), payload)
        }
        return local + foreign.sortedByDescending { it.isFill }
    }

    /** The registry alias of a person level. Any other level has none and gives no proof. */
    private val BadgeLevel.alias: String
        get() = when (this) {
            BadgeLevel.PIX -> "pix"
            BadgeLevel.GOVBR -> "govbr"
            else -> ""
        }
}
