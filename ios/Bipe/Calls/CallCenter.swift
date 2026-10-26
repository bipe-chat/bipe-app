// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// The one call of the app (spec call): who is in a call, and whether the call screen shows.
@MainActor
final class CallCenter: ObservableObject {
    static let shared = CallCenter()

    @Published private(set) var active: CallSession?
    @Published var isPresented = false
    private(set) var media: MediaLoader?
    /// The LiveKit focus of each server's well-known, nil when it names none.
    private var foci: [String: String?] = [:]

    /// The focus of the account's server (org.matrix.msc4143.rtc_foci). nil: no call buttons.
    func focusUrl(for session: AccountSession) async -> String? {
        let server = session.userId.split(separator: ":", maxSplits: 1).last.map(String.init) ?? ""
        if let known = foci[server] { return known }
        guard !server.isEmpty, let url = URL(string: "https://\(server)/.well-known/matrix/client") else { return nil }
        guard let (data, response) = try? await URLSession.shared.data(from: url),
              (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let focus = BipeCall.focusUrl(wellKnown: json)
        foci[server] = .some(focus)
        return focus
    }

    func isInCall(roomId: String) -> Bool { active?.roomId == roomId }

    /// Starts or joins the call of a room. A call in another room stays; its screen shows instead.
    func start(session: AccountSession, room: Room, roomName: String, video: Bool) async {
        if active != nil {
            isPresented = true
            return
        }
        guard let focus = await focusUrl(for: session),
              let call = try? CallSession(session: session, room: room, roomName: roomName, focusUrl: focus, video: video) else { return }
        call.onFinished = { [weak self, weak call] in
            guard let self, self.active === call else { return }
            self.active = nil
            self.isPresented = false
        }
        media = MediaLoader(client: session.client)
        active = call
        isPresented = true
        await call.start()
    }

    /// Closes a call that never joined (no microphone, no connection).
    func dismissFailed() {
        guard let call = active, call.phase == .failed else {
            isPresented = false
            return
        }
        active = nil
        isPresented = false
    }
}
