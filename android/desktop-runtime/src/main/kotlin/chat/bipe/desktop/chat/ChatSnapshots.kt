// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.app.common.VectorDiff
import chat.bipe.app.common.applyDiff
import chat.bipe.desktop.safety.MessageSafety

data class ChatLabels(val unnamed: String, val noName: String, val undecryptable: String, val unsupported: String)
data class ChatAvatar(val name: String, val url: String?)
data class ChatRow(
    val id: String, val name: String, val avatar: String?, val members: List<ChatAvatar>,
    val isDm: Boolean, val favourite: Boolean, val archived: Boolean, val unread: Long,
    val preview: String?, val timestamp: Long?,
    val draft: String? = null,
)
data class MessageRow(
    val id: String, val sender: String, val own: Boolean, val body: String, val kind: Kind,
    val timestamp: Long, val delivery: Delivery, val canRetry: Boolean = false,
    val safety: MessageSafety = MessageSafety.NotApplicable,
) {
    enum class Kind { TEXT, UNDECRYPTABLE, UNSUPPORTED }
    enum class Delivery { SENT, SENDING, FAILED }
}
sealed interface LoadPhase<out T> {
    data object Loading : LoadPhase<Nothing>
    data class Ready<T>(val value: T) : LoadPhase<T>
    data object Failed : LoadPhase<Nothing>
}

fun realName(raw: String?, userId: String?, fallback: String): String {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value == userId || value == userId?.removePrefix("@")?.substringBefore(':') ||
        ((value.startsWith('@') || value.startsWith('!') || value.startsWith('#')) && ':' in value && ' ' !in value)) return fallback
    return value
}

/** SDK operations are converted to immutable snapshots before reaching Compose; no native Room/Item escapes. */
internal suspend fun <T, R> VectorDiff<T>.snapshot(convert: suspend (T) -> R): VectorDiff<R> = when (this) {
    is VectorDiff.Append -> VectorDiff.Append(values.map { convert(it) })
    VectorDiff.Clear -> VectorDiff.Clear
    is VectorDiff.PushFront -> VectorDiff.PushFront(convert(value))
    is VectorDiff.PushBack -> VectorDiff.PushBack(convert(value))
    VectorDiff.PopFront -> VectorDiff.PopFront
    VectorDiff.PopBack -> VectorDiff.PopBack
    is VectorDiff.Insert -> VectorDiff.Insert(index, convert(value))
    is VectorDiff.Set -> VectorDiff.Set(index, convert(value))
    is VectorDiff.Remove -> VectorDiff.Remove(index)
    is VectorDiff.Truncate -> VectorDiff.Truncate(length)
    is VectorDiff.Reset -> VectorDiff.Reset(values.map { convert(it) })
}

internal fun <T> VectorDiff<T>.forEachValue(operation: (T) -> Unit) {
    when (this) {
        is VectorDiff.Append -> values.forEach(operation)
        is VectorDiff.Reset -> values.forEach(operation)
        is VectorDiff.PushFront -> operation(value)
        is VectorDiff.PushBack -> operation(value)
        is VectorDiff.Insert -> operation(value)
        is VectorDiff.Set -> operation(value)
        else -> Unit
    }
}

/** The index space includes virtual/state items even when their rendered snapshot is null. */
internal class SnapshotVector<T> {
    private val values = mutableListOf<T?>()
    fun apply(diff: VectorDiff<T?>) { values.applyDiff(diff) }
    fun visible(): List<T> = values.filterNotNull()
}
