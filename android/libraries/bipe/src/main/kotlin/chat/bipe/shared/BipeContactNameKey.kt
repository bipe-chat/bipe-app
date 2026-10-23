// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import java.security.MessageDigest

/** Local ADR0018 answer key. Preferences contain the digest, never a raw name, phone number or account ID. */
object BipeContactNameKey {
    fun make(accountId: String, peerId: String, savedName: String, verifiedName: String, identityKey: String): String {
        val fields = listOf("v1", accountId, peerId, NameNormalizer.normalize(savedName), NameNormalizer.normalize(verifiedName), identityKey)
        val input = fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return "bipe_contact_name_" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
