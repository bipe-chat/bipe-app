// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.composer

import org.matrix.rustcomponents.sdk.ComposerDraft
import org.matrix.rustcomponents.sdk.ComposerDraftType
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.Mentions
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomMember
import org.matrix.rustcomponents.sdk.RoomMessageEventContentWithoutRelation
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown

/** Type ComposerMention of spec composer: one pill. userId null is the whole group. */
data class ComposerMention(val userId: String?, val label: String)

/** Type MentionSuggestion of spec composer. */
data class MentionSuggestion(val userId: String?, val name: String, val handle: String? = null, val avatarUrl: String? = null) {
    val id: String get() = userId ?: "@room"
}

/** Markdown text with pills (spec composer, action send). */
object ComposerMarkdown {
    /**
     * The pills become matrix.to links to the user, then messageEventContentFromMarkdown, then withMentions
     * (user IDs of the pills, room true for the whole group).
     */
    fun content(text: String, mentions: List<ComposerMention>): RoomMessageEventContentWithoutRelation {
        var markdown = text
        for (mention in mentions) {
            val userId = mention.userId ?: continue
            val index = markdown.indexOf("@${mention.label}")
            if (index < 0) continue
            val link = "[${escape(mention.label)}](https://matrix.to/#/$userId)"
            markdown = markdown.replaceRange(index, index + mention.label.length + 1, link)
        }
        val content = messageEventContentFromMarkdown(markdown)
        val userIds = mentions.mapNotNull { it.userId }.distinct().sorted()
        val room = mentions.any { it.userId == null }
        if (userIds.isEmpty() && !room) return content
        return content.withMentions(Mentions(userIds, room))
    }

    private fun escape(label: String) = label.replace("[", "\\[").replace("]", "\\]")

    /** The pills still present in the text, in order. */
    fun mentions(mentions: List<ComposerMention>, text: String): List<ComposerMention> =
        mentions.filter { text.contains("@${it.label}") }

    /** The word at the end of the text when it starts with @, without the @. */
    fun mentionQuery(text: String): String? {
        val word = text.split(" ").lastOrNull() ?: return null
        return if (word.startsWith("@")) word.drop(1) else null
    }

    /** Replaces the @word at the end of the text with the pill label and a space. */
    fun insert(suggestion: MentionSuggestion, text: String, everyoneLabel: String): Pair<String, ComposerMention> {
        // A person named by the @handle (People names) gets one @, not two.
        val label = if (suggestion.userId == null) everyoneLabel else suggestion.name.removePrefix("@")
        val words = text.split(" ").dropLast(1) + "@$label "
        return words.joinToString(" ") to ComposerMention(suggestion.userId, label)
    }
}

/** The members of a group for @ suggestions (ADR 0141: groups only, no suggestions in a DM). */
class MentionDirectory {
    private var members: List<RoomMember> = emptyList()
    private var loaded = false
    var canMentionEveryone = false
        private set

    suspend fun load(room: Room, ownUserId: String) {
        if (loaded) return
        loaded = true
        runCatching {
            val iterator = room.members()
            val all = mutableListOf<RoomMember>()
            while (true) {
                val chunk = iterator.nextChunk(200u) ?: break
                all += chunk
            }
            members = all.filter { it.membership is MembershipState.Join && it.userId != ownUserId && !it.isIgnored }
        }
        canMentionEveryone = runCatching { room.getPowerLevels().canOwnUserTriggerRoomNotification() }.getOrDefault(false)
    }

    /**
     * Members whose name or @handle contains the query, the whole group first when allowed. Never matched or labelled
     * by the Matrix ID (People names).
     */
    fun suggestions(query: String, everyoneLabel: String, handles: chat.bipe.shared.BipeHandleResolver?): List<MentionSuggestion> {
        val needle = query.lowercase()
        val result = mutableListOf<MentionSuggestion>()
        if (canMentionEveryone && (needle.isEmpty() || everyoneLabel.lowercase().contains(needle))) {
            result += MentionSuggestion(null, everyoneLabel)
        }
        result += members
            .map { member ->
                val handle = handles?.handle(member.userId)
                Triple(member, chat.bipe.app.people.Person.name(member.userId, member.displayName, handle), handle)
            }
            .filter { (_, name, handle) -> needle.isEmpty() || name.lowercase().contains(needle) || handle?.lowercase()?.contains(needle) == true }
            .take(8)
            .map { (member, name, handle) ->
                MentionSuggestion(member.userId, name, handle = handle?.let { "@$it" }?.takeIf { it != name }, avatarUrl = member.avatarUrl)
            }
        return result
    }
}

/** Action appear of spec composer: the saved newMessage draft, if any. */
suspend fun Room.newMessageDraft(threadRoot: String? = null): String? {
    val draft = runCatching { loadComposerDraft(threadRoot) }.getOrNull() ?: return null
    if (draft.draftType !is ComposerDraftType.NewMessage) return null
    return draft.plainText.takeIf { it.isNotBlank() }
}

/** Action leave of spec composer: saves the text when not blank, else clears the draft. */
suspend fun Room.keepDraft(text: String, threadRoot: String? = null) {
    runCatching {
        if (text.isBlank()) {
            clearComposerDraft(threadRoot)
        } else {
            saveComposerDraft(ComposerDraft(text, null, ComposerDraftType.NewMessage, emptyList()), threadRoot)
        }
    }
}
