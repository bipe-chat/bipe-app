// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK

// SDK listeners call back on SDK threads. Each one feeds an AsyncStream, so the screen models
// read the updates in order on the main actor.

final class RoomListEntriesStream: RoomListEntriesListener {
    let updates: AsyncStream<[RoomListEntriesUpdate]>
    private let continuation: AsyncStream<[RoomListEntriesUpdate]>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func onUpdate(roomEntriesUpdate: [RoomListEntriesUpdate]) {
        continuation.yield(roomEntriesUpdate)
    }
}

final class RoomListLoadingStream: RoomListLoadingStateListener {
    let updates: AsyncStream<RoomListLoadingState>
    private let continuation: AsyncStream<RoomListLoadingState>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func onUpdate(state: RoomListLoadingState) {
        continuation.yield(state)
    }
}

final class TimelineDiffStream: TimelineListener {
    let updates: AsyncStream<[TimelineDiff]>
    private let continuation: AsyncStream<[TimelineDiff]>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func onUpdate(diff: [TimelineDiff]) {
        continuation.yield(diff)
    }
}

final class TypingStream: TypingNotificationsListener {
    let updates: AsyncStream<[String]>
    private let continuation: AsyncStream<[String]>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func call(typingUserIds: [String]) {
        continuation.yield(typingUserIds)
    }
}

/// The operations of an SDK vector diff. Room list entries and timeline items use the same shape.
enum VectorDiff<Element> {
    case append([Element])
    case clear
    case pushFront(Element)
    case pushBack(Element)
    case popFront
    case popBack
    case insert(Int, Element)
    case set(Int, Element)
    case remove(Int)
    case truncate(Int)
    case reset([Element])
}

extension Array {
    mutating func apply(_ diff: VectorDiff<Element>) {
        switch diff {
        case .append(let values): append(contentsOf: values)
        case .clear: removeAll()
        case .pushFront(let value): insert(value, at: 0)
        case .pushBack(let value): append(value)
        case .popFront: if !isEmpty { removeFirst() }
        case .popBack: if !isEmpty { removeLast() }
        case .insert(let index, let value): if index <= count { insert(value, at: index) }
        case .set(let index, let value): if indices.contains(index) { self[index] = value }
        case .remove(let index): if indices.contains(index) { remove(at: index) }
        case .truncate(let length): if length < count { removeLast(count - length) }
        case .reset(let values): self = values
        }
    }
}

extension RoomListEntriesUpdate {
    var vectorDiff: VectorDiff<Room> {
        switch self {
        case .append(let values): .append(values)
        case .clear: .clear
        case .pushFront(let value): .pushFront(value)
        case .pushBack(let value): .pushBack(value)
        case .popFront: .popFront
        case .popBack: .popBack
        case .insert(let index, let value): .insert(Int(index), value)
        case .set(let index, let value): .set(Int(index), value)
        case .remove(let index): .remove(Int(index))
        case .truncate(let length): .truncate(Int(length))
        case .reset(let values): .reset(values)
        }
    }
}

extension TimelineDiff {
    var vectorDiff: VectorDiff<TimelineItem> {
        switch self {
        case .append(let values): .append(values)
        case .clear: .clear
        case .pushFront(let value): .pushFront(value)
        case .pushBack(let value): .pushBack(value)
        case .popFront: .popFront
        case .popBack: .popBack
        case .insert(let index, let value): .insert(Int(index), value)
        case .set(let index, let value): .set(Int(index), value)
        case .remove(let index): .remove(Int(index))
        case .truncate(let length): .truncate(Int(length))
        case .reset(let values): .reset(values)
        }
    }
}

extension SearchServiceResultsUpdate {
    var vectorDiff: VectorDiff<SearchServiceResult> {
        switch self {
        case .append(let values): .append(values)
        case .clear: .clear
        case .pushFront(let value): .pushFront(value)
        case .pushBack(let value): .pushBack(value)
        case .popFront: .popFront
        case .popBack: .popBack
        case .insert(let index, let value): .insert(Int(index), value)
        case .set(let index, let value): .set(Int(index), value)
        case .remove(let index): .remove(Int(index))
        case .truncate(let length): .truncate(Int(length))
        case .reset(let values): .reset(values)
        }
    }
}
