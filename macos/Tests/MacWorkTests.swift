// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import XCTest
@testable import BipeMac

private final class WorkTransport: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private static var requests: [(String, String)] = []
    static func reset() { lock.withLock { requests = [] } }
    static var recorded: [(String, String)] { lock.withLock { requests } }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard request.url?.host == "example.invalid" else { fatalError("Synthetic tests must never contact a real API") }
        let token = request.value(forHTTPHeaderField: "Authorization") ?? ""
        Self.lock.withLock { Self.requests.append((request.httpMethod ?? "", token)) }
        let route = request.url?.lastPathComponent
        let body: String
        var status = 200
        switch route {
        case "tools": status = 403; body = #"{"error":"forbidden"}"#
        case "today":
            let waiting = token == "Bearer company-one" ? 1 : 2
            body = #"{"bookings":[],"orders":[],"queueOpen":true,"queueWaiting":\#(waiting),"quoteRequests":[]}"#
        case "catalog": body = #"{"items":[]}"#
        case "seats": body = #"{"me":{"role":"none"},"seats":[],"unassignedDevices":[],"maxPeople":10,"backupRotationPending":false}"#
        default: status = 404; body = #"{"error":"not_found"}"#
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class MacWorkTests: XCTestCase {
    @MainActor
    func testWorkReadsStayAccountScopedAndOneFailureDoesNotHideOtherSections() async throws {
        WorkTransport.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [WorkTransport.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let api = BipeAPIClient(baseURL: URL(string: "https://example.invalid")!, session: session)
        let first = MacWorkModel(business: BipeBusinessAPI(api: api, accessToken: { "company-one" }))
        let second = MacWorkModel(business: BipeBusinessAPI(api: api, accessToken: { "company-two" }))
        async let one: Void = first.refresh()
        async let two: Void = second.refresh()
        _ = await (one, two)
        for model in [first, second] {
            XCTAssertEqual(model.failed, ["tools"])
            XCTAssertNil(model.tools)
            XCTAssertEqual(model.catalog?.count, 0)
            XCTAssertEqual(model.people?.me.role, "none", "A linked computer never infers owner rights")
            XCTAssertFalse(model.loading)
        }
        XCTAssertEqual(first.today?.queueWaiting, 1)
        XCTAssertEqual(second.today?.queueWaiting, 2)
        XCTAssertEqual(WorkTransport.recorded.filter { $0.1 == "Bearer company-one" }.count, 4)
        XCTAssertEqual(WorkTransport.recorded.filter { $0.1 == "Bearer company-two" }.count, 4)
        XCTAssertTrue(WorkTransport.recorded.allSatisfy { $0.0 == "GET" })
    }
}
