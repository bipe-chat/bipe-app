// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.company

import android.content.Context
import androidx.compose.runtime.Immutable
import chat.bipe.core.BadgeLevel as CoreBadgeLevel
import chat.bipe.core.ListType
import chat.bipe.core.PersonBadge
import chat.bipe.core.PersonProof
import chat.bipe.core.SealMark
import chat.bipe.core.personProofs
import chat.bipe.shared.BadgeLevel
import chat.bipe.shared.BadgePayload
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

/** `personProofs` of bipe-shared, run in bipe-core (`personProofs`, ADR 0267, ADR 0275). test-vectors/person-proofs.json must pass. */
object BipePersonProofs {
    /** A badge level and, for `lvl` person, its scheme. */
    data class Badge(val lvl: String, val scheme: String?)

    /**
     * The person schemes of these badges: Brazilian first, then other countries', each strongest first, one per scheme.
     * A level or a scheme outside the issuer registry, or a scheme with no name, gives nothing (fail closed).
     */
    fun schemes(
        badges: List<Badge>,
        english: Boolean = false,
        registryJson: String? = SignedLists.shared.current(ListType.ISSUERS)?.payloadJson,
    ): List<PersonScheme> {
        val known = badges.mapNotNull { badge -> levelOf(badge.lvl)?.let { PersonBadge(it, badge.scheme) } }
        return personProofs(known, registryJson.orEmpty()).mapNotNull { scheme(it, english, registryJson) }
    }

    /** The proofs of badges that already passed the verifier, in the order of [schemes]. A foreign scheme takes its name from the issuer registry (ADR 0272). */
    fun proofs(
        payloads: List<BadgePayload>,
        english: Boolean = false,
        registryJson: String? = SignedLists.shared.current(ListType.ISSUERS)?.payloadJson,
    ): List<BipePersonProof> =
        personProofs(payloads.map { PersonBadge(it.lvl.core, it.scheme) }, registryJson.orEmpty()).mapNotNull { proof ->
            val payload = payloads.getOrNull(proof.badgeIndex.toInt()) ?: return@mapNotNull null
            scheme(proof, english, registryJson)?.let { BipePersonProof(it, payload) }
        }

    /** The scheme of a core proof: the build's entry for a Brazilian scheme, else one named from the registry. */
    private fun scheme(proof: PersonProof, english: Boolean, registryJson: String?): PersonScheme? {
        BipeRegistries.personSchemes.firstOrNull { it.id == proof.scheme }?.let { return it }
        val name = BipeSchemeNames.name(proof.scheme, english, registryJson) ?: return null
        val mark = if (proof.mark == SealMark.FILL) "fill" else "regular"
        return PersonScheme(proof.scheme, null, proof.assurance, mark, name, name)
    }

    private fun levelOf(name: String): CoreBadgeLevel? = BadgeLevel.entries.firstOrNull { it.wireName == name }?.core

    /** The wire name of a level (`@SerialName`). */
    private val BadgeLevel.wireName: String
        get() = when (this) {
            BadgeLevel.PIX -> "pix"
            BadgeLevel.GOVBR -> "govbr"
            BadgeLevel.INSTITUTION -> "institution"
            BadgeLevel.CNPJ -> "cnpj"
            BadgeLevel.BRAND_MARK -> "brand-mark"
            BadgeLevel.PERSON -> "person"
            BadgeLevel.ORG -> "org"
        }

    /** The bipe-core level of the same name. */
    private val BadgeLevel.core: CoreBadgeLevel
        get() = when (this) {
            BadgeLevel.PIX -> CoreBadgeLevel.PIX
            BadgeLevel.GOVBR -> CoreBadgeLevel.GOVBR
            BadgeLevel.INSTITUTION -> CoreBadgeLevel.INSTITUTION
            BadgeLevel.CNPJ -> CoreBadgeLevel.CNPJ
            BadgeLevel.BRAND_MARK -> CoreBadgeLevel.BRAND_MARK
            BadgeLevel.PERSON -> CoreBadgeLevel.PERSON
            BadgeLevel.ORG -> CoreBadgeLevel.ORG
        }
}
