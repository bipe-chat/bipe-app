// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// The Matrix transport of a call (spec call, ADR 0186): the matrix-sdk-ffi widget driver with no page.
/// The app is the widget. It answers the driver's toWidget requests and sends fromWidget requests
/// (MSC2762 events and state, MSC3819 to-device, MSC4157 delayed events).
actor CallWidget {
    struct Failure: Error {
        let message: String
    }

    /// What the driver pushes to the widget.
    enum Incoming {
        case event([String: Any])
        case state([[String: Any]])
        case toDevice([String: Any])
    }

    /// A fromWidget request without an answer after this long fails.
    static let requestTimeout: UInt64 = 15

    let widgetId: String
    private let driver: WidgetDriver
    private let handle: WidgetDriverHandle
    private let capabilityNames: [String]
    private let approved: WidgetCapabilities
    private let onIncoming: @Sendable (Incoming) -> Void
    private var pending: [String: CheckedContinuation<[String: Any], Error>] = [:]
    private var waiting: [CheckedContinuation<Void, Never>] = []
    private var isReady = false
    private var isClosed = false
    private var counter = 0

    init(userId: String, deviceId: String, extraSend: [String] = [], extraRead: [String] = [],
         onIncoming: @escaping @Sendable (Incoming) -> Void) throws {
        let id = "bipe-call-\(UUID().uuidString.lowercased())"
        widgetId = id
        // The driver needs a URL; nothing loads it.
        let settings = WidgetSettings(widgetId: id, initAfterContentLoad: false, rawUrl: "https://bipe.chat/call")
        let made = try makeWidgetDriver(settings: settings)
        driver = made.driver
        handle = made.handle
        let ownKey = BipeCall.stateKey(userId: userId, deviceId: deviceId)
        let member = BipeCall.memberEventType
        let key = BipeCall.keyEventType
        let sendEvents = [key] + extraSend
        let readEvents = [key] + extraRead
        capabilityNames = [
            "org.matrix.msc4157.send.delayed_event",
            "org.matrix.msc4157.update_delayed_event",
            "org.matrix.msc2762.receive.state_event:\(member)",
            "org.matrix.msc2762.send.state_event:\(member)#\(ownKey)",
            "org.matrix.msc3819.send.to_device:\(key)",
            "org.matrix.msc3819.receive.to_device:\(key)",
        ] + sendEvents.map { "org.matrix.msc2762.send.event:\($0)" } + readEvents.map { "org.matrix.msc2762.receive.event:\($0)" }
        approved = WidgetCapabilities(
            read: [.stateWithType(eventType: member), .toDevice(eventType: key)] + readEvents.map { WidgetEventFilter.messageLikeWithType(eventType: $0) },
            send: [.stateWithTypeAndStateKey(eventType: member, stateKey: ownKey), .toDevice(eventType: key)]
                + sendEvents.map { WidgetEventFilter.messageLikeWithType(eventType: $0) },
            requiresClient: false, updateDelayedEvent: true, sendDelayedEvent: true, downloadFiles: false, rtcTransports: false)
        self.onIncoming = onIncoming
    }

    /// Runs the driver for the room and reads its messages until `close`.
    func start(room: Room) {
        let driver = driver
        let provider = CallCapabilities(approved: approved)
        Task.detached { await driver.run(room: room, capabilitiesProvider: provider) }
        Task { await readLoop() }
    }

    /// Waits until the driver approved the capabilities.
    func ready() async {
        if isReady { return }
        await withCheckedContinuation { waiting.append($0) }
    }

    func close() {
        isClosed = true
        for (_, continuation) in pending { continuation.resume(throwing: Failure(message: "closed")) }
        pending = [:]
        for continuation in waiting { continuation.resume() }
        waiting = []
    }

    // MARK: fromWidget

    /// A room event, or a state event with `stateKey`. `delayMs`: a delayed event (MSC4140); the answer has `delay_id`.
    @discardableResult
    func sendEvent(type: String, stateKey: String? = nil, content: [String: Any], delayMs: UInt64? = nil) async throws -> [String: Any] {
        var data: [String: Any] = ["type": type, "content": content]
        if let stateKey { data["state_key"] = stateKey }
        if let delayMs { data["delay"] = delayMs }
        return try await request("send_event", data: data)
    }

    /// action: restart, cancel or send.
    func updateDelayed(delayId: String, action: String) async throws {
        _ = try await request("org.matrix.msc4157.update_delayed_event", data: ["delay_id": delayId, "action": action])
    }

    private func request(_ action: String, data: [String: Any]) async throws -> [String: Any] {
        guard !isClosed else { throw Failure(message: "closed") }
        counter += 1
        let requestId = "bipe-\(counter)"
        let message: [String: Any] = ["api": "fromWidget", "widgetId": widgetId, "requestId": requestId, "action": action, "data": data]
        let text = try Self.text(message)
        let response = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<[String: Any], Error>) in
            pending[requestId] = continuation
            Task {
                if !(await handle.send(msg: text)) { self.fail(requestId, "driver stopped") }
            }
            Task {
                try? await Task.sleep(nanoseconds: Self.requestTimeout * 1_000_000_000)
                self.fail(requestId, "timeout")
            }
        }
        if let error = response["error"] as? [String: Any] {
            throw Failure(message: error["message"] as? String ?? action)
        }
        return response
    }

    private func fail(_ requestId: String, _ message: String) {
        pending.removeValue(forKey: requestId)?.resume(throwing: Failure(message: message))
    }

    // MARK: toWidget

    private func readLoop() async {
        while !isClosed, let text = await handle.recv() {
            await handleMessage(text)
        }
        close()
    }

    private func handleMessage(_ text: String) async {
        guard let object = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let requestId = object["requestId"] as? String else { return }
        if object["api"] as? String == "fromWidget" {
            pending.removeValue(forKey: requestId)?.resume(returning: object["response"] as? [String: Any] ?? [:])
            return
        }
        let data = object["data"] as? [String: Any] ?? [:]
        var answer: [String: Any] = [:]
        switch object["action"] as? String {
        case "capabilities":
            answer = ["capabilities": capabilityNames]
        case "notify_capabilities":
            isReady = true
            for continuation in waiting { continuation.resume() }
            waiting = []
        case "send_event":
            onIncoming(.event(data))
        case "update_state":
            onIncoming(.state(data["state"] as? [[String: Any]] ?? []))
        case "send_to_device":
            onIncoming(.toDevice(data))
        default:
            break
        }
        var reply = object
        reply["response"] = answer
        if let reply = try? Self.text(reply) { _ = await handle.send(msg: reply) }
    }

    private static func text(_ object: [String: Any]) throws -> String {
        String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self)
    }
}

/// Grants the call exactly what CallWidget asks for, and nothing else.
private final class CallCapabilities: WidgetCapabilitiesProvider, @unchecked Sendable {
    private let approved: WidgetCapabilities

    init(approved: WidgetCapabilities) {
        self.approved = approved
    }

    func acquireCapabilities(capabilities: WidgetCapabilities) -> WidgetCapabilities {
        WidgetCapabilities(read: capabilities.read.filter { approved.read.contains($0) },
                           send: capabilities.send.filter { approved.send.contains($0) },
                           requiresClient: false,
                           updateDelayedEvent: capabilities.updateDelayedEvent && approved.updateDelayedEvent,
                           sendDelayedEvent: capabilities.sendDelayedEvent && approved.sendDelayedEvent,
                           downloadFiles: false, rtcTransports: false)
    }
}
