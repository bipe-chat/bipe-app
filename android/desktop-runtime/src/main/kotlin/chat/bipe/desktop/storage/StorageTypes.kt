// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.storage

import java.nio.file.Path
import java.util.UUID
import org.matrix.rustcomponents.sdk.Session

/** Random local slot, assigned before the phone has disclosed the account. Never a user-supplied path. */
@JvmInline
value class AccountSlot private constructor(val value: String) {
    companion object {
        fun create(): AccountSlot = AccountSlot(UUID.randomUUID().toString())
        fun parse(value: String): AccountSlot {
            val parsed = try { UUID.fromString(value).toString() }
            catch (_: IllegalArgumentException) { throw StorageFailure(StorageCode.INVALID_SLOT) }
            if (parsed != value) throw StorageFailure(StorageCode.INVALID_SLOT)
            return AccountSlot(parsed)
        }
    }
}

enum class StorageCode {
    WINDOWS_UNAVAILABLE, INVALID_SLOT, SLOT_BUSY, MISSING_KEY, MISSING_STORE,
    CORRUPT_RECORD, IDENTITY_MISMATCH, REVOKED, STALE_OWNER, IO_FAILURE, NATIVE_FAILURE,
    LINK_INCOMPLETE, DUPLICATE_ACCOUNT,
}

/** No SDK exception, JSON body, credential, user name or filesystem path enters an error message. */
class StorageFailure(val code: StorageCode, val nativeStatus: Int? = null) :
    Exception("Desktop storage: ${code.name}" + (nativeStatus?.let { " ($it)" } ?: ""))

data class AccountPaths(val root: Path) {
    val data: Path get() = root.resolve("data")
    val cache: Path get() = root.resolve("cache")
    val drafts: Path get() = root.resolve("drafts")
    val media: Path get() = root.resolve("media")
}

sealed interface StoredSlot {
    val slot: AccountSlot
    data class Linked(override val slot: AccountSlot, val userId: String, val deviceId: String) : StoredSlot
    data class Pending(override val slot: AccountSlot) : StoredSlot
    data class Discarding(override val slot: AccountSlot, val remoteEnded: Boolean) : StoredSlot
    data class Revoked(override val slot: AccountSlot) : StoredSlot
    data class Unavailable(override val slot: AccountSlot, val reason: StorageCode) : StoredSlot
}

internal sealed interface StoredRecord {
    val session: Session?
    class Pending(override val session: Session?) : StoredRecord
    class Linked(override val session: Session) : StoredRecord
    /** Durable cleanup intent; never restores a client or recreates a server device. */
    class Discarding(val remoteEnded: Boolean) : StoredRecord { override val session: Session? = null }
}

/** Vault implementations return an owned copy; callers must erase it after use. */
interface StoreKeyVault {
    fun read(slot: AccountSlot): ByteArray?
    fun create(slot: AccountSlot, key: ByteArray)
    fun remove(slot: AccountSlot)
}

interface UserDataProtection {
    fun protect(clear: ByteArray, entropy: ByteArray): ByteArray
    fun unprotect(sealed: ByteArray, entropy: ByteArray): ByteArray
}

sealed interface RevocationResult {
    data object Complete : RevocationResult
    data class CleanupRequired(val reason: StorageCode) : RevocationResult
    data class Blocked(val reason: StorageCode) : RevocationResult
}
