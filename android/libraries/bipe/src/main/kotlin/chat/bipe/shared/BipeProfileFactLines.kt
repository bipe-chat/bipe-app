// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import android.content.Context
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.icu.text.MeasureFormat
import chat.bipe.core.ProfileFact
import chat.bipe.core.ProfileFacts
import chat.bipe.core.profileFactLines
import chat.bipe.shared.api.BipeProfileFacts
import chat.bipe.shared.generated.BipeRegistries
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.max

/**
 * The facts lines of chat-request and person-profile, and the amber notice of chat-timeline (ADR 0049).
 * bipe-shared `profileFactLines`, run in bipe-core; test-vectors/profile-facts.json must pass.
 * Same rule as BipeKit `BipeProfileFactLines` on iOS.
 */
data class BipeProfileFactLines(
    /** In order: created, photo, name, numberOut, numberIn. Empty when nothing applies. */
    val lines: List<Line>,
    /** A name change and a photo change at most noticePairHours apart, the later one younger than noticeDays. */
    val notice: Boolean,
    /** The newest number move in the window (bipe-chat/bipe#161), null without one. */
    val numberNotice: NumberNotice? = null,
) {
    enum class Fact(val id: String) { CREATED("created"), PHOTO("photo"), NAME("name"), NUMBER_OUT("numberOut"), NUMBER_IN("numberIn") }

    enum class NumberNotice(val id: String) { OUT("out"), IN("in") }

    /** The text of the amber bar of chat-timeline, null without a notice. A number move wins over a name and photo change. */
    fun noticeText(context: Context, name: String): String? = when {
        numberNotice == NumberNotice.OUT -> context.getString(R.string.bipe_profile_notice_number_out, name)
        numberNotice == NumberNotice.IN -> context.getString(R.string.bipe_profile_notice_number_in, name)
        notice -> context.getString(R.string.bipe_profile_notice_name_photo, name)
        else -> null
    }

    /** [count] is the number of changes in the window, `null` for [Fact.CREATED]. [at] is the creation time or the latest change. */
    data class Line(val fact: Fact, val count: Int?, val at: Instant)

    /** The lines as text (spec chat-request and person-profile profileFacts). */
    fun texts(context: Context, now: Instant = Instant.now()): List<String> = lines.map { line ->
        val age = age(line.at, now)
        val count = line.count ?: 1
        val window = BipeRegistries.PROFILE_FACTS_WINDOW_DAYS.toString()
        when (line.fact) {
            Fact.CREATED -> context.getString(R.string.bipe_profile_fact_created, age)
            Fact.PHOTO -> if (count >= 2) {
                context.getString(R.string.bipe_profile_fact_photo_times, count.toString(), window)
            } else {
                context.getString(R.string.bipe_profile_fact_photo_once, age)
            }
            Fact.NAME -> if (count >= 2) {
                context.getString(R.string.bipe_profile_fact_name_times, count.toString(), window)
            } else {
                context.getString(R.string.bipe_profile_fact_name_once, age)
            }
            Fact.NUMBER_OUT -> context.getString(R.string.bipe_profile_fact_number_out, age)
            Fact.NUMBER_IN -> context.getString(R.string.bipe_profile_fact_number_in, age)
        }
    }

    companion object {
        /** [alwaysCreated]: person-profile keeps the account age at any age (ADR 0275 point 13). The rule runs in bipe-core (`profileFactLines`). */
        fun of(facts: BipeProfileFacts, now: Instant = Instant.now(), alwaysCreated: Boolean = false): BipeProfileFactLines {
            val input = ProfileFacts(facts.createdAt, facts.nameChanges, facts.photoChanges, facts.numberMovedOut, facts.numberMovedIn)
            val result = profileFactLines(input, now.toEpochMilli(), alwaysCreated)
            val lines = result.lines.map { line ->
                val fact = when (line.fact) {
                    ProfileFact.CREATED -> Fact.CREATED
                    ProfileFact.PHOTO -> Fact.PHOTO
                    ProfileFact.NAME -> Fact.NAME
                    ProfileFact.NUMBER_OUT -> Fact.NUMBER_OUT
                    ProfileFact.NUMBER_IN -> Fact.NUMBER_IN
                }
                Line(fact, line.count?.toInt(), Instant.ofEpochMilli(line.atMs))
            }
            val numberNotice = when (result.numberNotice) {
                chat.bipe.core.NumberNotice.IN -> NumberNotice.IN
                chat.bipe.core.NumberNotice.OUT -> NumberNotice.OUT
                null -> null
            }
            return BipeProfileFactLines(lines, result.notice, numberNotice)
        }

        /** An ISO 8601 date with a zone. Anything else is `null`. */
        fun parse(value: String): Instant? =
            runCatching { Instant.parse(value) }.getOrNull() ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()

        /** The time since [at] in words, one unit, rounded down: "3 dias", "5 horas", "1 minuto". */
        fun age(at: Instant, now: Instant = Instant.now()): String {
            val minutes = max(1L, (now.toEpochMilli() - at.toEpochMilli()) / 60_000L)
            val measure = when {
                minutes >= 1440 -> Measure(minutes / 1440, MeasureUnit.DAY)
                minutes >= 60 -> Measure(minutes / 60, MeasureUnit.HOUR)
                else -> Measure(minutes, MeasureUnit.MINUTE)
            }
            return MeasureFormat.getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.WIDE).format(measure)
        }
    }
}
