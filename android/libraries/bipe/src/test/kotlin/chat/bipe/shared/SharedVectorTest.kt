// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import com.google.common.truth.Truth.assertWithMessage
import chat.bipe.shared.account.BipePin
import chat.bipe.shared.api.BipeProfileFacts
import chat.bipe.shared.clearedchats.ClearedChats
import chat.bipe.shared.drafts.BipeAssistantDrafts
import chat.bipe.shared.drafts.BipeAssistantSend
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeDmCandidate
import chat.bipe.shared.generated.BipeRelationshipNames
import chat.bipe.shared.generated.BipeDmSelection
import chat.bipe.shared.generated.BipeTrustFacts
import chat.bipe.shared.generated.BipeTrustInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import chat.bipe.shared.onboarding.BipeOnboardingSchedule
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Test
import java.io.File

/** Every case in bipe-shared/test-vectors must pass (ADR 0031, rule 4). iOS runs the same files. */
class SharedVectorTest {
    @Test
    fun photoMatch() {
        val cases = vectors("photo-match.json", "cases")
        assertWithMessage("photo match vectors").that(cases).isNotEmpty()
        for (vector in cases) {
            val name = vector.string("name")
            fun grid(key: String) = vector.getValue(key).jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.content.toInt() } }
            val hashA = BipePhotoMatch.hash(grid("gridA"))!!
            val hashB = BipePhotoMatch.hash(grid("gridB"))!!
            assertWithMessage("photo match $name").that(hashA).isEqualTo(vector.string("hashA"))
            assertWithMessage("photo match $name").that(hashB).isEqualTo(vector.string("hashB"))
            assertWithMessage("photo match $name").that(BipePhotoMatch.distance(hashA, hashB)).isEqualTo(vector.getValue("distance").jsonPrimitive.content.toInt())
            assertWithMessage("photo match $name").that(BipePhotoMatch.matches(hashA, hashB)).isEqualTo(vector.getValue("matches").jsonPrimitive.boolean)
        }
    }

    @Test
    fun relationshipNames() {
        val cases = vectors("relationship-names.json", "vectors")
        assertWithMessage("relationship vectors").that(cases).isNotEmpty()
        for (vector in cases) {
            assertWithMessage("relationship ${vector.string("name")}").that(BipeRelationshipNames.warning(
                vector.string("savedName"), vector.string("verifiedName"), vector["nicknameConfirmed"]?.jsonPrimitive?.boolean ?: false,
            )).isEqualTo(vector.string("expected"))
        }
    }

    @Test
    fun dmSelection() {
        for (vector in vectors("dm-selection.json", "cases")) {
            val candidates = vector.getValue("candidates").jsonArray.map { raw ->
                val item = raw.jsonObject
                BipeDmCandidate(item.getValue("roomId").jsonPrimitive.content,
                    item.getValue("peerUserId").jsonPrimitive.content,
                    item.getValue("latestActivityMs").jsonPrimitive.longOrNull,
                    item.getValue("eligible").jsonPrimitive.boolean,
                    item["cleared"]?.jsonPrimitive?.boolean ?: false)
            }
            fun expected(key: String) = vector.getValue(key).jsonObject.mapValues { it.value.jsonPrimitive.content }
            assertWithMessage("DM selection ${vector.string("name")}").that(BipeDmSelection.newestRooms(candidates)).isEqualTo(expected("expected"))
            assertWithMessage("DM reverse order").that(BipeDmSelection.newestRooms(candidates.reversed())).isEqualTo(expected("expected"))
            if ("includingCleared" in vector) {
                assertWithMessage("DM reuse when all cleared").that(BipeDmSelection.newestRooms(candidates, includingCleared = true)).isEqualTo(expected("includingCleared"))
            }
        }
    }

    @Test
    fun trustFacts() {
        val cases = vectors("trust-facts.json", "vectors")
        assertWithMessage("trust vectors").that(cases).isNotEmpty()
        for (vector in cases) {
            val input = Json.decodeFromJsonElement<BipeTrustInput>(vector.getValue("input"))
            val expected = Json.decodeFromJsonElement<BipeTrustFacts>(vector.getValue("expected"))
            assertWithMessage("trust facts ${vector.string("name")}").that(BipeTrustFacts.evaluate(input)).isEqualTo(expected)
        }
    }

    @Test
    fun normalization() {
        for (vector in vectors("normalization.json", "vectors")) {
            val input = vector.string("input")!!
            assertWithMessage("normalize($input)").that(NameNormalizer.normalize(input)).isEqualTo(vector.string("normalized"))
            assertWithMessage("reserved($input)").that(NameNormalizer.reservedCategory(input)?.id).isEqualTo(vector.string("reserved"))
        }
    }

    @Test
    fun scamDetection() {
        for (vector in vectors("scam-detection.json", "vectors")) {
            val message = vector.string("message")!!
            val expected = vector.getValue("matches").jsonArray.map { it.jsonPrimitive.content }
            assertWithMessage("matches($message)").that(ScamDetector.matchIds(message)).isEqualTo(expected)
        }
    }

    @Test
    fun scamWarnings() {
        for (vector in vectors("scam-warnings.json", "vectors")) {
            val message = vector.string("message")!!
            val kind = ScamDetector.warningKind(ScamDetector.matchIds(message), message)
            assertWithMessage("kind($message)").that(kind?.id).isEqualTo(vector.string("kind"))
        }
    }

    @Test
    fun pixCheck() {
        val vectors = vectors("pix-check.json", "vectors")
        assertWithMessage("pix-check.json is empty").that(vectors).isNotEmpty()
        for (vector in vectors) {
            val text = vector.string("text")!!
            val label = "pixCheck(${text.take(40)})"
            val result = PixCheck.check(text)
            val expected = vector["code"]?.takeUnless { it is JsonNull }?.jsonObject
            if (expected == null) {
                assertWithMessage("$label code").that(result.code).isNull()
            } else {
                val code = result.code
                assertWithMessage("$label code").that(code).isNotNull()
                code!!
                assertWithMessage("$label status").that(code.status.id).isEqualTo(expected.string("status"))
                if (code.status != PixCheck.Status.MALFORMED) {
                    assertWithMessage("$label dynamic").that(code.dynamic.toString()).isEqualTo(expected.string("dynamic"))
                    assertWithMessage("$label singleUse").that(code.singleUse.toString()).isEqualTo(expected.string("singleUse"))
                    assertWithMessage("$label key").that(code.key).isEqualTo(expected.string("key"))
                    assertWithMessage("$label keyType").that(code.keyType?.id).isEqualTo(expected.string("keyType"))
                    assertWithMessage("$label url").that(code.url).isEqualTo(expected.string("url"))
                    assertWithMessage("$label name").that(code.name).isEqualTo(expected.string("name"))
                    assertWithMessage("$label city").that(code.city).isEqualTo(expected.string("city"))
                    assertWithMessage("$label amount").that(code.amount).isEqualTo(expected.string("amount"))
                    assertWithMessage("$label txid").that(code.txid).isEqualTo(expected.string("txid"))
                    assertWithMessage("$label info").that(code.info).isEqualTo(expected.string("info"))
                }
            }
            val keys = vector.getValue("keys").jsonArray.map { it.jsonObject }
            assertWithMessage("$label keys").that(result.keys.map { it.value }).isEqualTo(keys.map { it.string("value") })
            assertWithMessage("$label key types").that(result.keys.map { key -> key.types.map { it.id } })
                .isEqualTo(keys.map { key -> key.getValue("types").jsonArray.map { it.jsonPrimitive.content } })
            assertWithMessage("$label warning").that(result.warning?.id).isEqualTo(vector.string("warning"))
        }
    }

    /** The client checks length and characters only. Reserved names and lookalikes are the server's call. */
    @Test
    fun handles() {
        val localProblems = setOf("too_short", "too_long", "invalid_characters")
        for (vector in vectors("handles.json", "cases")) {
            val input = vector.string("input")!!
            val expected = vector.string("problem")?.takeIf { it in localProblems }
            assertWithMessage("localProblem($input)").that(BipeHandle.localProblem(input)?.id).isEqualTo(expected)
        }
    }

    @Test
    fun handleSuggestions() {
        for (vector in vectors("handle-suggestions.json", "cases")) {
            val name = vector.string("name")!!
            val expected = vector["suggestions"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertWithMessage("suggestions($name)").that(BipeHandle.suggestions(name)).isEqualTo(expected)
        }
    }

    @Test
    fun pinReminderSchedule() {
        for (vector in vectors("onboarding-schedule.json", "pinReminder")) {
            val step = vector.string("step")!!.toInt()
            val result = BipeOnboardingSchedule.PinResult.entries.first { it.id == vector.string("result") }
            val next = BipeOnboardingSchedule.pinReminderNext(step, result)
            assertWithMessage("pinReminderNext($step, ${result.id}) step").that(next.step).isEqualTo(vector.string("nextStep")!!.toInt())
            assertWithMessage("pinReminderNext($step, ${result.id}) gapDays").that(next.gapDays).isEqualTo(vector.string("gapDays")!!.toInt())
        }
    }

    @Test
    fun pins() {
        for (vector in vectors("pins.json", "cases")) {
            val input = vector.string("input")!!
            assertWithMessage("pinProblem($input)").that(BipePin.problem(input)?.id).isEqualTo(vector.string("problem"))
        }
    }

    @Test
    fun phoneNumbers() {
        val cases = vectors("phone-numbers.json", "vectors")
        assertWithMessage("phone-numbers.json is empty").that(cases).isNotEmpty()
        for (vector in cases) {
            val input = vector.string("input")!!
            assertWithMessage("normalizePhone($input)").that(BipePhoneNumber.normalize(input)).isEqualTo(vector.string("e164"))
        }
        val display = vectors("phone-numbers.json", "display")
        assertWithMessage("phone-numbers.json display is empty").that(display).isNotEmpty()
        for (vector in display) {
            val e164 = vector.string("e164")!!
            assertWithMessage("displayPhone($e164)").that(BipePhoneNumber.display(e164)).isEqualTo(vector.string("display"))
        }
    }

    @Test
    fun bipeServers() {
        val cases = vectors("bipe-servers.json", "vectors")
        assertWithMessage("bipe-servers.json is empty").that(cases).isNotEmpty()
        for (vector in cases) {
            val userId = vector.string("userId")!!
            assertWithMessage("isBipeUserId($userId)").that(BipeServers.isBipeUserId(userId))
                .isEqualTo(vector.getValue("bipe").jsonPrimitive.boolean)
        }
    }

    @Test
    fun inviteCodes() {
        for (vector in vectors("invite-codes.json", "vectors")) {
            val input = vector.string("input")!!
            assertWithMessage("normalizedCode($input)").that(BipeInviteLink.normalizedCode(input)).isEqualTo(vector.string("code"))
        }
    }

    @Test
    fun badges() {
        val file = load("badges.json")
        val nowMillis = file.getValue("now").jsonPrimitive.long * 1000
        val keys = file.getValue("publicKeys").jsonObject.mapValues { (_, key) -> Base64Url.decode(key.jsonPrimitive.content)!! }
        val verifier = BadgeVerifier(keys)
        for (vector in file.getValue("vectors").jsonArray.map { it.jsonObject }) {
            val result = verifier.verify(
                envelopeJson = vector.getValue("envelope").toString(),
                sender = vector.string("sender")!!,
                senderDevice = vector.string("senderDevice")!!,
                senderMasterKey = vector.string("senderMasterKey"),
                nowMillis = nowMillis,
            )
            val outcome = when (result) {
                is BadgeVerificationResult.Valid -> "valid"
                is BadgeVerificationResult.Invalid -> result.failure.code
            }
            assertWithMessage("badge ${vector.string("name")}").that(outcome).isEqualTo(vector.string("expect"))
        }
    }

    @Test
    fun profileFacts() {
        val now = BipeProfileFactLines.parse(load("profile-facts.json").string("now")!!)!!
        val cases = vectors("profile-facts.json", "vectors")
        assertWithMessage("profile facts vectors").that(cases).isNotEmpty()
        for (vector in cases) {
            val name = vector.string("name")
            val raw = vector.getValue("facts").jsonObject
            fun dates(key: String) = raw[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val facts = BipeProfileFacts("@a:bipe.net.br", raw.string("created_at"), dates("name_changes"), dates("photo_changes"))
            val result = BipeProfileFactLines.of(facts, now)
            val expected = vector.getValue("expected").jsonObject
            val lines = expected.getValue("lines").jsonArray.map { it.jsonObject }
            assertWithMessage("profile facts notice $name").that(result.notice).isEqualTo(expected.getValue("notice").jsonPrimitive.boolean)
            assertWithMessage("profile facts lines $name").that(result.lines.map { listOf(it.fact.id, it.count, it.at) })
                .isEqualTo(lines.map { listOf(it.string("fact"), it.string("count")?.toInt(), BipeProfileFactLines.parse(it.string("at")!!)) })
        }
    }

    @Test
    fun theftMarks() {
        val file = load("theft-marks.json")
        val nowMillis = file.getValue("now").jsonPrimitive.long * 1000
        val keys = file.getValue("publicKeys").jsonObject.mapValues { (_, key) -> Base64Url.decode(key.jsonPrimitive.content)!! }
        val verifier = BipeTheftMarkVerifier(keys)
        val cases = file.getValue("vectors").jsonArray.map { it.jsonObject }
        assertWithMessage("theft mark vectors").that(cases).isNotEmpty()
        for (vector in cases) {
            val name = vector.string("name")
            val expected = vector.getValue("expected").jsonObject
            when (val result = verifier.verify(vector.getValue("envelope").toString(), vector.string("subject")!!, nowMillis)) {
                is BipeTheftMarkResult.Valid -> assertWithMessage("theft mark $name").that(result.statement.stolenAt)
                    .isEqualTo(expected["stolenAt"]?.jsonPrimitive?.longOrNull)
                is BipeTheftMarkResult.Invalid -> assertWithMessage("theft mark $name").that(result.failure.code)
                    .isEqualTo(expected.string("error"))
            }
        }
    }

    @Test
    fun assistantDrafts() {
        val context = load("drafts.json").getValue("context").jsonObject
        val ownUserId = context.string("ownUserId")!!
        val joined = context.getValue("joinedRoomIds").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val cases = vectors("drafts.json", "cases")
        assertWithMessage("drafts vectors").that(cases).isNotEmpty()
        for (vector in cases) {
            val name = vector.string("name")
            val event = vector.getValue("event").jsonObject
            val expected = vector.getValue("expected").jsonObject
            val result = BipeAssistantDrafts.fromEvent(event, ownUserId, joined)
            val want = if (expected.getValue("show").jsonPrimitive.boolean) {
                val draft = expected.getValue("draft").jsonObject
                BipeAssistantDrafts.Result.Show(BipeAssistantDrafts.Draft(draft.string("roomId")!!, draft.string("body")!!,
                    draft.string("replyTo"), draft.string("threadRoot"), draft.string("client")!!))
            } else {
                BipeAssistantDrafts.Result.Hide(expected.string("reason")!!)
            }
            assertWithMessage("draft $name").that(result).isEqualTo(want)
            // The same event as raw JSON, the way the app reads it from the timeline.
            assertWithMessage("draft json $name").that(BipeAssistantDrafts.fromEventJson(event.toString(), ownUserId = ownUserId, joinedRoomIds = joined))
                .isEqualTo(want)
        }
        assertWithMessage("drafts room id").that(BipeAssistantDrafts.roomId("""{"room_id":"!drafts:bipe.net.br"}""")).isEqualTo("!drafts:bipe.net.br")
        assertWithMessage("drafts room id without !").that(BipeAssistantDrafts.roomId("""{"room_id":"drafts"}""")).isNull()
        assertWithMessage("no drafts room").that(BipeAssistantDrafts.roomId(null)).isNull()
    }

    @Test
    fun assistantSend() {
        val switches = vectors("assistant-send.json", "switch")
        assertWithMessage("assistant-send switch vectors").that(switches).isNotEmpty()
        for (vector in switches) {
            val content = vector.getValue("content").takeUnless { it is JsonNull }
            val expected = vector.getValue("expected").jsonPrimitive.boolean
            assertWithMessage("switch ${vector.string("name")}").that(BipeAssistantSend.allowed(content)).isEqualTo(expected)
            assertWithMessage("switch json ${vector.string("name")}").that(BipeAssistantSend.allowed(content?.toString())).isEqualTo(expected)
        }
        assertWithMessage("switch on").that(BipeAssistantSend.allowed(BipeAssistantSend.json(true))).isTrue()
        assertWithMessage("switch off").that(BipeAssistantSend.allowed(BipeAssistantSend.json(false))).isFalse()
        val labels = vectors("assistant-send.json", "label")
        assertWithMessage("assistant-send label vectors").that(labels).isNotEmpty()
        for (vector in labels) {
            val content = vector.getValue("content").jsonObject
            val expected = vector.string("expected")
            assertWithMessage("label ${vector.string("name")}").that(BipeAssistantSend.sender(content)).isEqualTo(expected)
            val event = """{"type":"m.room.message","content":$content}"""
            assertWithMessage("label json ${vector.string("name")}").that(BipeAssistantSend.sender(null) { event }).isEqualTo(expected)
        }
    }

    @Test
    fun clearedChats() {
        val file = load("cleared-chats.json")
        assertWithMessage("cleared-chats event type").that(file.string("event_type")).isEqualTo(BipeRegistries.CLEARED_CHATS_EVENT_TYPE)
        val markers = vectors("cleared-chats.json", "marker")
        assertWithMessage("cleared-chats marker vectors").that(markers).isNotEmpty()
        for (vector in markers) {
            val chats = ClearedChats.parse(vector.getValue("content").toString())
            assertWithMessage("marker ${vector.string("name")}").that(chats.marker(vector.string("room_id")!!))
                .isEqualTo(vector.getValue("expected").jsonPrimitive.longOrNull)
        }
        for (vector in vectors("cleared-chats.json", "hidden")) {
            val marker = vector.getValue("marker").jsonPrimitive.longOrNull
            val latestTs = vector.getValue("latest_ts").jsonPrimitive.longOrNull
            assertWithMessage("hidden ${vector.string("name")}").that(ClearedChats.isHidden(marker, latestTs))
                .isEqualTo(vector.getValue("expected").jsonPrimitive.boolean)
        }
        for (vector in vectors("cleared-chats.json", "keeps")) {
            val marker = vector.getValue("marker").jsonPrimitive.longOrNull
            val eventTs = vector.getValue("event_ts").jsonPrimitive.long
            assertWithMessage("keeps ${vector.string("name")}").that(ClearedChats.keepsEvent(marker, eventTs))
                .isEqualTo(vector.getValue("expected").jsonPrimitive.boolean)
        }
        for (vector in vectors("cleared-chats.json", "clear")) {
            val content = vector.getValue("content").takeUnless { it is JsonNull }?.toString()
            val written = ClearedChats.contentClearing(content, vector.string("room_id")!!, vector.getValue("ts").jsonPrimitive.long)
            assertWithMessage("clear ${vector.string("name")}").that(Json.parseToJsonElement(written)).isEqualTo(vector.getValue("expected"))
        }
    }

    private fun vectors(name: String, key: String): List<JsonObject> = (load(name).getValue(key) as JsonArray).map { it.jsonObject }

    private fun load(name: String): JsonObject = Json.parseToJsonElement(File(vectorsDir, name).readText()).jsonObject

    private fun JsonObject.string(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private companion object {
        /** bipe-shared next to bipe-app. Unit tests run in the module directory (android/libraries/bipe). BIPE_SHARED overrides it. */
        val vectorsDir: File = File(System.getenv("BIPE_SHARED") ?: "../../../../bipe-shared", "test-vectors")
    }
}
