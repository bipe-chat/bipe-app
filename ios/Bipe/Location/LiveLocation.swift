// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import CoreLocation
import Foundation
import MatrixRustSDK

/// Type OwnShare of spec live-location-banner: one running live location share of an account.
struct OwnLiveShare: Codable, Equatable, Identifiable {
    let userId: String
    let roomId: String
    let endsAt: Date
    var id: String { roomId }
}

/// The update rule of spec location-send notes (registries/maps.json live).
enum LiveLocationRule {
    /// The chats.liveLocation setting: metres the phone moves before the next update.
    static let distanceKey = "bipe.settings.liveLocationDistance"

    static var distance: Int {
        let saved = UserDefaults.standard.integer(forKey: distanceKey)
        return BipeRegistries.mapsLiveDistancesMetres.contains(saved) ? saved : BipeRegistries.mapsLiveDefaultDistanceMetres
    }

    /// true when a position is due for a room: the first one, or after the floor when the phone moved the setting's
    /// distance, or after the ceiling when it moved at all (the system sends positions only after 10 m).
    static func isDue(moved: Double?, sinceLast: TimeInterval?, sinceTry: TimeInterval?, distance: Int) -> Bool {
        if let sinceTry, sinceTry < Double(BipeRegistries.mapsLiveMinIntervalSeconds) { return false }
        guard let moved, let sinceLast else { return true }
        if sinceLast < Double(BipeRegistries.mapsLiveMinIntervalSeconds) { return false }
        return moved >= Double(distance) || (sinceLast >= Double(BipeRegistries.mapsLiveMaxIntervalSeconds) && moved >= 1)
    }

    /// "15 min", "1 h", "8 h" (spec location-send action startLive).
    static func durationLabel(minutes: Int) -> String {
        minutes < 60 ? BipeStrings.appLocationLiveMinutes(String(minutes)) : BipeStrings.appLocationLiveHours(String(minutes / 60))
    }
}

/// Live location of this phone (MSC3489, spec location-send and live-location-banner): starts and stops the shares,
/// reads positions with CLLocationManager and sends them to each room with Room.sendLiveLocation.
/// Only one account shares at a time. The app keeps no position history: only the room, the account and the end time.
@MainActor
final class LiveLocationCenter: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = LiveLocationCenter()

    @Published private(set) var shares: [OwnLiveShare] = []
    @Published private(set) var authorization: CLAuthorizationStatus

    private let manager: CLLocationManager
    private var session: AccountSession?
    private var rooms: [String: Room] = [:]
    private var lastSent: [String: (location: CLLocation, at: Date)] = [:]
    private var lastTry: [String: Date] = [:]
    private var latest: CLLocation?
    private var flush: Task<Void, Never>?
    private var endTimers: [String: Task<Void, Never>] = [:]
    /// Shares that a closed app left behind; stopped when their account opens (spec location-send notes, end).
    private var stale: [OwnLiveShare]

    private static let storeKey = "bipe.liveLocation.shares"

    override init() {
        let manager = CLLocationManager()
        self.manager = manager
        authorization = manager.authorizationStatus
        stale = (UserDefaults.standard.data(forKey: Self.storeKey))
            .flatMap { try? JSONDecoder().decode([OwnLiveShare].self, from: $0) } ?? []
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 10
        manager.activityType = .other
        manager.pausesLocationUpdatesAutomatically = false
        manager.showsBackgroundLocationIndicator = true
    }

    func share(in roomId: String, userId: String) -> OwnLiveShare? {
        shares.first { $0.roomId == roomId && $0.userId == userId }
    }

    func shares(of userId: String) -> [OwnLiveShare] {
        shares.filter { $0.userId == userId }
    }

    /// iOS "when in use" only: positions go out while the app is open (spec live-location-banner foregroundOnly).
    var isForegroundOnly: Bool { authorization == .authorizedWhenInUse }

    /// Action startLive of spec location-send. Asks for "Always" here, never before. Another account's shares stop.
    func start(session: AccountSession, room: Room, minutes: Int) async throws {
        if let other = self.session, other.userId != session.userId {
            await stopAll(userId: other.userId)
        }
        let roomId = room.id()
        _ = try await room.startLiveLocationShare(durationMillis: UInt64(minutes) * 60_000)
        self.session = session
        rooms[roomId] = room
        lastSent[roomId] = nil
        lastTry[roomId] = nil
        let share = OwnLiveShare(userId: session.userId, roomId: roomId, endsAt: Date().addingTimeInterval(Double(minutes) * 60))
        shares.removeAll { $0.roomId == roomId }
        shares.append(share)
        stale.removeAll { $0.roomId == roomId }
        save()
        endTimers[roomId]?.cancel()
        endTimers[roomId] = Task { [weak self] in
            try? await Task.sleep(for: .seconds(max(0, share.endsAt.timeIntervalSinceNow)))
            guard !Task.isCancelled else { return }
            await self?.stop(roomId: roomId)
        }
        if manager.authorizationStatus == .authorizedWhenInUse {
            manager.requestAlwaysAuthorization()
        }
        applyBackgroundMode()
        manager.startUpdatingLocation()
        if let latest { await consider(latest) }
    }

    /// Action stop of spec live-location-banner for one chat; also the end of the duration.
    func stop(roomId: String) async {
        guard let index = shares.firstIndex(where: { $0.roomId == roomId }) else { return }
        shares.remove(at: index)
        save()
        endTimers.removeValue(forKey: roomId)?.cancel()
        lastSent[roomId] = nil
        lastTry[roomId] = nil
        let room = rooms.removeValue(forKey: roomId)
        if shares.isEmpty { finish() }
        _ = try? await room?.stopLiveLocationShare()
    }

    /// Stops every share of an account: the chat list row, sign-out and account switch.
    func stopAll(userId: String) async {
        for share in shares where share.userId == userId {
            await stop(roomId: share.roomId)
        }
    }

    /// A switch to another account stops the shares of the account before it (spec location-send notes, end).
    func accountChanged(to userId: String) {
        guard let current = session?.userId, current != userId, !shares(of: current).isEmpty else { return }
        Task { await stopAll(userId: current) }
    }

    /// Stops the shares that a closed app left for this account, while they still run on the server.
    func cleanUp(_ session: AccountSession) async {
        let left = stale.filter { $0.userId == session.userId }
        guard !left.isEmpty else { return }
        stale.removeAll { $0.userId == session.userId }
        save()
        for share in left where share.endsAt > Date() {
            _ = try? await session.client.getRoom(roomId: share.roomId)?.stopLiveLocationShare()
        }
    }

    private func finish() {
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
        flush?.cancel()
        flush = nil
        latest = nil
        session = nil
    }

    private func save() {
        let all = shares + stale
        if all.isEmpty {
            UserDefaults.standard.removeObject(forKey: Self.storeKey)
        } else if let data = try? JSONEncoder().encode(all) {
            UserDefaults.standard.set(data, forKey: Self.storeKey)
        }
    }

    /// Background updates only with "Always" (spec location-send notes, background iOS).
    private func applyBackgroundMode() {
        manager.allowsBackgroundLocationUpdates = !shares.isEmpty && manager.authorizationStatus == .authorizedAlways
    }

    /// Sends the position to each room where it is due; plans one more try for a room that must wait.
    private func consider(_ location: CLLocation) async {
        let now = Date()
        for share in shares where share.endsAt <= now {
            await stop(roomId: share.roomId)
        }
        let distance = LiveLocationRule.distance
        var nextCheck: TimeInterval?
        for share in shares {
            let last = lastSent[share.roomId]
            let due = LiveLocationRule.isDue(moved: last.map { location.distance(from: $0.location) },
                                             sinceLast: last.map { now.timeIntervalSince($0.at) },
                                             sinceTry: lastTry[share.roomId].map { now.timeIntervalSince($0) },
                                             distance: distance)
            if due {
                if !(await send(location, roomId: share.roomId)) {
                    nextCheck = Double(BipeRegistries.mapsLiveMinIntervalSeconds)
                }
            } else if let last, location.distance(from: last.location) >= Double(distance) {
                let wait = Double(BipeRegistries.mapsLiveMinIntervalSeconds) - now.timeIntervalSince(last.at)
                nextCheck = min(nextCheck ?? wait, max(1, wait))
            }
        }
        guard let nextCheck else { return }
        flush?.cancel()
        flush = Task { [weak self] in
            try? await Task.sleep(for: .seconds(nextCheck))
            guard !Task.isCancelled, let self, let latest = self.latest else { return }
            await self.consider(latest)
        }
    }

    private func send(_ location: CLLocation, roomId: String) async -> Bool {
        guard let room = rooms[roomId] else { return false }
        lastTry[roomId] = Date()
        let uri = GeoURI.make(latitude: location.coordinate.latitude, longitude: location.coordinate.longitude,
                              uncertainty: location.horizontalAccuracy)
        do {
            // The first try right after the start can fail until the sync brings the beacon_info back; the next one sends.
            try await room.sendLiveLocation(geoUri: uri)
            lastSent[roomId] = (location, Date())
            return true
        } catch {
            return false
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last, location.horizontalAccuracy >= 0 else { return }
        Task { @MainActor in
            guard !self.shares.isEmpty else { return }
            self.latest = location
            await self.consider(location)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorization = status
            switch status {
            case .denied, .restricted:
                // No positions any more: the shares end instead of showing an old place as live.
                for share in self.shares { await self.stop(roomId: share.roomId) }
            default:
                self.applyBackgroundMode()
            }
        }
    }
}
