// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Spec business-claim (ADR 0042, ADR 0036): the calls of `/v1/me/business`. The server decides; the app shows.
public extension BipeAPIClient {
    /// `POST /v1/me/business`. One claim per account.
    func claimBusiness(cnpj: String, accessToken: String) async throws(BipeAPIError) -> BipeBusinessClaim {
        try await send(method: "POST", path: ["v1", "me", "business"], body: CnpjBody(cnpj: cnpj), accessToken: accessToken)
    }

    /// `DELETE /v1/me/business`. Also ends a verification.
    func withdrawBusiness(accessToken: String) async throws(BipeAPIError) {
        _ = try await perform(method: "DELETE", path: ["v1", "me", "business"], rawBody: nil, accessToken: accessToken)
    }

    /// `POST /v1/me/business/ecnpj`. `signature` is the .p7s file as it is (DER CMS); it goes out in base64.
    func proveBusinessWithEcnpj(signature: Data, accessToken: String) async throws(BipeAPIError) -> BipeBusinessClaim {
        try await send(method: "POST", path: ["v1", "me", "business", "ecnpj"],
                       body: SignatureBody(signature: signature.base64EncodedString()), accessToken: accessToken)
    }

    /// `POST /v1/me/business/domains`. The answer holds the TXT record to publish.
    func addBusinessDomain(_ domain: String, accessToken: String) async throws(BipeAPIError) -> BipeBusinessClaim.Domain {
        try await send(method: "POST", path: ["v1", "me", "business", "domains"], body: DomainBody(domain: domain), accessToken: accessToken)
    }

    /// `POST /v1/me/business/domains/{domain}/check`. `verifiedAt` is set when the record matched.
    func checkBusinessDomain(_ domain: String, accessToken: String) async throws(BipeAPIError) -> BipeBusinessClaim.Domain {
        try await send(method: "POST", path: ["v1", "me", "business", "domains", domain, "check"], body: Empty?.none, accessToken: accessToken)
    }
}

private struct CnpjBody: Encodable { let cnpj: String }
private struct SignatureBody: Encodable { let signature: String }
private struct DomainBody: Encodable { let domain: String }

public extension BipeCNPJ {
    /// The CNPJ as 14 uppercase characters, or nil when the format or a check digit is wrong
    /// (bipe-shared normalizeCnpj, test-vectors/cnpj.json). Numeric and alphanumeric CNPJs.
    static func normalized(_ input: String) -> String? {
        let s = input.filter { !".-/ \t\n".contains($0) }.uppercased()
        guard s.wholeMatch(of: /[0-9A-Z]{12}[0-9]{2}/) != nil, Set(s).count > 1 else { return nil }
        let values = s.unicodeScalars.map { Int($0.value) - 48 }
        func digit(_ count: Int, _ weights: [Int]) -> Int {
            let r = zip(values.prefix(count), weights).reduce(0) { $0 + $1.0 * $1.1 } % 11
            return r < 2 ? 0 : 11 - r
        }
        let d1 = digit(12, [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2])
        let d2 = digit(13, [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2])
        return values[12] == d1 && values[13] == d2 ? s : nil
    }

    /// A domain as the API takes it: lower case, without scheme, path or a final dot. nil when it is not a host name.
    static func domain(_ input: String) -> String? {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if let range = s.range(of: "://") { s = String(s[range.upperBound...]) }
        s = String(s.prefix { $0 != "/" && $0 != "?" && $0 != "#" })
        if s.hasPrefix("www.") { s = String(s.dropFirst(4)) }
        if s.hasSuffix(".") { s = String(s.dropLast()) }
        guard s.count <= 253, s.wholeMatch(of: /([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}/) != nil else { return nil }
        return s
    }
}
