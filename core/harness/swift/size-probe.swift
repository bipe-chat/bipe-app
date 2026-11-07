// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

// Links the core into a binary for the size report in core.yml. It calls one export,
// so dead-code stripping keeps what a real caller keeps. core.yml compiles it as main.swift with the generated bindings.
print(verifyPublicIssuerStatement(envelopeJson: CommandLine.arguments.last ?? "", homeserver: "", trustedKeys: [], nowSeconds: 0))
