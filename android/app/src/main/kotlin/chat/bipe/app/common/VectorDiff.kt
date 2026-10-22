// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem

/** The operations of an SDK vector diff. Room list entries and timeline items use the same shape. */
sealed interface VectorDiff<out T> {
    data class Append<T>(val values: List<T>) : VectorDiff<T>
    data object Clear : VectorDiff<Nothing>
    data class PushFront<T>(val value: T) : VectorDiff<T>
    data class PushBack<T>(val value: T) : VectorDiff<T>
    data object PopFront : VectorDiff<Nothing>
    data object PopBack : VectorDiff<Nothing>
    data class Insert<T>(val index: Int, val value: T) : VectorDiff<T>
    data class Set<T>(val index: Int, val value: T) : VectorDiff<T>
    data class Remove(val index: Int) : VectorDiff<Nothing>
    data class Truncate(val length: Int) : VectorDiff<Nothing>
    data class Reset<T>(val values: List<T>) : VectorDiff<T>
}

fun <T> MutableList<T>.applyDiff(diff: VectorDiff<T>) {
    when (diff) {
        is VectorDiff.Append -> addAll(diff.values)
        VectorDiff.Clear -> clear()
        is VectorDiff.PushFront -> add(0, diff.value)
        is VectorDiff.PushBack -> add(diff.value)
        VectorDiff.PopFront -> if (isNotEmpty()) removeAt(0)
        VectorDiff.PopBack -> if (isNotEmpty()) removeAt(lastIndex)
        is VectorDiff.Insert -> if (diff.index <= size) add(diff.index, diff.value)
        is VectorDiff.Set -> if (diff.index in indices) set(diff.index, diff.value)
        is VectorDiff.Remove -> if (diff.index in indices) removeAt(diff.index)
        is VectorDiff.Truncate -> while (size > diff.length) removeAt(lastIndex)
        is VectorDiff.Reset -> {
            clear()
            addAll(diff.values)
        }
    }
}

fun RoomListEntriesUpdate.toVectorDiff(): VectorDiff<Room> = when (this) {
    is RoomListEntriesUpdate.Append -> VectorDiff.Append(values)
    RoomListEntriesUpdate.Clear -> VectorDiff.Clear
    is RoomListEntriesUpdate.PushFront -> VectorDiff.PushFront(value)
    is RoomListEntriesUpdate.PushBack -> VectorDiff.PushBack(value)
    RoomListEntriesUpdate.PopFront -> VectorDiff.PopFront
    RoomListEntriesUpdate.PopBack -> VectorDiff.PopBack
    is RoomListEntriesUpdate.Insert -> VectorDiff.Insert(index.toInt(), value)
    is RoomListEntriesUpdate.Set -> VectorDiff.Set(index.toInt(), value)
    is RoomListEntriesUpdate.Remove -> VectorDiff.Remove(index.toInt())
    is RoomListEntriesUpdate.Truncate -> VectorDiff.Truncate(length.toInt())
    is RoomListEntriesUpdate.Reset -> VectorDiff.Reset(values)
}

fun TimelineDiff.toVectorDiff(): VectorDiff<TimelineItem> = when (this) {
    is TimelineDiff.Append -> VectorDiff.Append(values)
    TimelineDiff.Clear -> VectorDiff.Clear
    is TimelineDiff.PushFront -> VectorDiff.PushFront(value)
    is TimelineDiff.PushBack -> VectorDiff.PushBack(value)
    TimelineDiff.PopFront -> VectorDiff.PopFront
    TimelineDiff.PopBack -> VectorDiff.PopBack
    is TimelineDiff.Insert -> VectorDiff.Insert(index.toInt(), value)
    is TimelineDiff.Set -> VectorDiff.Set(index.toInt(), value)
    is TimelineDiff.Remove -> VectorDiff.Remove(index.toInt())
    is TimelineDiff.Truncate -> VectorDiff.Truncate(length.toInt())
    is TimelineDiff.Reset -> VectorDiff.Reset(values)
}
