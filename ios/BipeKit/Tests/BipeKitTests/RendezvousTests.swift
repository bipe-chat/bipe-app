// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// Link a computer accepts only codes whose rendezvous is on the account's homeserver (bipe-chat/bipe#785).
struct RendezvousTests {
    /// An MSC4108 login code: MATRIX, version 0x02, intent 0x03, a 32-byte key, the rendezvous URL.
    private func msc4108(_ url: String, version: UInt8 = 0x02) -> Data {
        let text = Array(url.utf8)
        return Data(Array("MATRIX".utf8) + [version, 0x03] + [UInt8](repeating: 7, count: 32)
            + [UInt8(text.count >> 8), UInt8(text.count & 0xff)] + text)
    }

    private let homeserver = "https://matrix.bipe.net.br/"

    @Test
    func acceptsOwnHomeserver() {
        let code = msc4108("https://matrix.bipe.net.br/_synapse/client/rendezvous/abc")
        #expect(BipeWebLink.rendezvousURL(qrBytes: code, baseURL: nil)?.host() == "matrix.bipe.net.br")
        #expect(BipeWebLink.isOwnRendezvous(qrBytes: code, baseURL: nil, homeserver: homeserver))
    }

    @Test
    func refusesOtherServer() {
        #expect(!BipeWebLink.isOwnRendezvous(qrBytes: msc4108("https://matrix-client.matrix.org/_synapse/client/rendezvous/x"),
                                             baseURL: nil, homeserver: homeserver))
        // A suffix is not the same host.
        #expect(!BipeWebLink.isOwnRendezvous(qrBytes: msc4108("https://evilmatrix.bipe.net.br.example.com/r"),
                                             baseURL: nil, homeserver: homeserver))
        #expect(!BipeWebLink.isOwnRendezvous(qrBytes: msc4108("http://matrix.bipe.net.br/r"), baseURL: nil, homeserver: homeserver))
    }

    @Test
    func refusesBrokenCodes() {
        #expect(!BipeWebLink.isOwnRendezvous(qrBytes: msc4108("https://matrix.bipe.net.br/r", version: 0x01),
                                             baseURL: nil, homeserver: homeserver))
        #expect(!BipeWebLink.isOwnRendezvous(qrBytes: msc4108("https://matrix.bipe.net.br/r").prefix(50),
                                             baseURL: nil, homeserver: homeserver))
        #expect(!BipeWebLink.isOwnRendezvous(qrBytes: Data([1, 2, 3]), baseURL: nil, homeserver: homeserver))
    }

    @Test
    func usesBaseURLOfOtherCodes() {
        #expect(BipeWebLink.isOwnRendezvous(qrBytes: Data([1, 2, 3]), baseURL: "https://matrix.bipe.net.br", homeserver: homeserver))
        #expect(!BipeWebLink.isOwnRendezvous(qrBytes: Data([1, 2, 3]), baseURL: "https://matrix.org", homeserver: homeserver))
    }
}
