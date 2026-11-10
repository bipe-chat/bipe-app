// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.storage

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import java.util.UUID
import org.matrix.rustcomponents.sdk.Session

/** Windows-only OS services sit behind interfaces so failure/isolation tests need no real account. */
class AccountRepository(
    root: Path,
    private val vault: StoreKeyVault = WindowsStoreKeyVault(),
    private val protection: UserDataProtection = WindowsDataProtection(),
) {
    private val root = root.toAbsolutePath().normalize()

    /** Only this explicit operation can create a key, always in a new random slot. */
    fun provision(): AccountLease = safeIo {
        ensureRoot()
        val slot = AccountSlot.create()
        val directory = root.resolve(slot.value)
        Files.createDirectory(directory)
        val lease = lock(slot, directory, StoredRecord.Pending(null))
        val key = ByteArray(WindowsStoreKeyVault.KEY_SIZE).also { SecureRandom().nextBytes(it) }
        try {
            vault.create(slot, key)
            lease.initialize()
            lease
        } catch (error: Exception) {
            // Never turn a partially provisioned slot into an apparently fresh account on restart.
            try { lease.revoke() } finally { lease.close() }
            throw error
        } finally { key.fill(0) }
    }

    /** Serialize promotion across slots/processes; a second phone link cannot replace a linked account. */
    fun completeLink(lease: AccountLease, session: Session) = safeIo {
        if (lease.paths.root != root.resolve(lease.slot.value)) throw StorageFailure(StorageCode.IDENTITY_MISMATCH)
        FileChannel.open(root.resolve("accounts.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock = try { channel.tryLock() ?: throw StorageFailure(StorageCode.SLOT_BUSY) }
            catch (_: OverlappingFileLockException) { throw StorageFailure(StorageCode.SLOT_BUSY) }
            lock.use {
                if (slots().filterIsInstance<StoredSlot.Linked>().any { it.slot != lease.slot && it.userId == session.userId }) {
                    throw StorageFailure(StorageCode.DUPLICATE_ACCOUNT)
                }
                lease.completeLink(session)
            }
        }
    }

    /** Existing slots never generate replacement keys, stores, sessions or devices. */
    fun open(slot: AccountSlot): AccountLease = safeIo {
        val directory = existingDirectory(slot)
        if (Files.exists(directory.resolve(REVOKED), NOFOLLOW_LINKS)) throw StorageFailure(StorageCode.REVOKED)
        val lease = lock(slot, directory, null)
        try { lease.load(); lease }
        catch (error: Exception) { lease.close(); throw error }
    }

    /** An atomic encrypted record can be inspected without opening the SDK or taking its lifetime lock. */
    fun slots(): List<StoredSlot> = safeIo {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return@safeIo emptyList()
        ensureRoot()
        Files.list(root).use { paths ->
            paths.sorted().map { path ->
                val slot = try { AccountSlot.parse(path.fileName.toString()) }
                catch (_: StorageFailure) { return@map null }
                try {
                    existingDirectory(slot)
                    if (Files.exists(path.resolve(REVOKED), NOFOLLOW_LINKS)) StoredSlot.Revoked(slot)
                    else when (val record = readRecord(slot, path)) {
                        is StoredRecord.Pending -> StoredSlot.Pending(slot)
                        is StoredRecord.Linked -> StoredSlot.Linked(slot, record.session.userId, record.session.deviceId)
                    }
                } catch (error: StorageFailure) { StoredSlot.Unavailable(slot, error.code) }
            }.filter { it != null }.map { it!! }.toList()
        }
    }

    /** Called only after SDK teardown, lease release and a committed key revocation. Does not follow links. */
    fun removeRevokedFiles(slot: AccountSlot) = safeIo {
        val directory = existingDirectory(slot)
        if (!Files.isRegularFile(directory.resolve(REVOKED), NOFOLLOW_LINKS)) throw StorageFailure(StorageCode.REVOKED)
        vault.read(slot)?.let { it.fill(0); throw StorageFailure(StorageCode.STALE_OWNER) }
        // Acquire once more to reject cleanup while another owner still holds the slot.
        lock(slot, directory, null).close()
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun lock(slot: AccountSlot, directory: Path, record: StoredRecord?): AccountLease {
        val lockFile = directory.resolve("owner.lock")
        if (Files.isSymbolicLink(lockFile)) throw StorageFailure(StorageCode.IO_FAILURE)
        val channel = FileChannel.open(lockFile, CREATE, WRITE, NOFOLLOW_LINKS)
        val lock: FileLock = try { channel.tryLock() ?: throw StorageFailure(StorageCode.SLOT_BUSY) }
        catch (_: OverlappingFileLockException) { channel.close(); throw StorageFailure(StorageCode.SLOT_BUSY) }
        catch (error: Exception) { channel.close(); throw error }
        return AccountLease(slot, AccountPaths(directory), vault, protection, channel, lock, record)
    }

    private fun existingDirectory(slot: AccountSlot): Path {
        ensureRoot()
        val path = root.resolve(slot.value)
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path) ||
            !path.toRealPath().startsWith(root.toRealPath())) throw StorageFailure(StorageCode.MISSING_STORE)
        return path
    }

    private fun ensureRoot() {
        Files.createDirectories(root)
        if (!Files.isDirectory(root, NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) throw StorageFailure(StorageCode.IO_FAILURE)
    }

    private fun readRecord(slot: AccountSlot, path: Path): StoredRecord = withKey(vault, slot) { key ->
        decodeRecord(slot, path, key, protection)
    }

    companion object {
        internal const val RECORD = "session.dpapi"
        internal const val REVOKED = "revoked"

        fun windowsDefault(): AccountRepository {
            requireWindows()
            val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?: throw StorageFailure(StorageCode.WINDOWS_UNAVAILABLE)
            val path = Path.of(local)
            if (!path.isAbsolute) throw StorageFailure(StorageCode.WINDOWS_UNAVAILABLE)
            return AccountRepository(path.resolve("Bipe").resolve("Accounts"))
        }

        internal fun decodeRecord(slot: AccountSlot, path: Path, key: ByteArray, protection: UserDataProtection): StoredRecord {
            val file = path.resolve(RECORD)
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.isSymbolicLink(file) || Files.size(file) !in 1..2_097_152) {
                throw StorageFailure(StorageCode.CORRUPT_RECORD)
            }
            val plain = protection.unprotect(Files.readAllBytes(file), key)
            try { return SessionCodec.decode(slot, plain) }
            finally { plain.fill(0) }
        }

        internal fun <T> withKey(vault: StoreKeyVault, slot: AccountSlot, operation: (ByteArray) -> T): T {
            val key = vault.read(slot) ?: throw StorageFailure(StorageCode.MISSING_KEY)
            try {
                if (key.size != WindowsStoreKeyVault.KEY_SIZE) throw StorageFailure(StorageCode.CORRUPT_RECORD)
                return operation(key)
            } finally { key.fill(0) }
        }

        internal fun atomicWrite(file: Path, bytes: ByteArray) {
            if (Files.isSymbolicLink(file)) throw StorageFailure(StorageCode.IO_FAILURE)
            val temporary = file.resolveSibling(".${file.fileName}.${UUID.randomUUID()}.tmp")
            try {
                FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                // No delete-then-rename fallback: a failed replacement preserves the previous session.
                Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }

        internal fun <T> safeIo(operation: () -> T): T = try { operation() }
        catch (failure: StorageFailure) { throw failure }
        catch (_: IOException) { throw StorageFailure(StorageCode.IO_FAILURE) }
    }
}

/** A fixed owner, captured by SDK delegates. A later owner of the same slot cannot revive this one. */
class AccountLease internal constructor(
    val slot: AccountSlot,
    val paths: AccountPaths,
    private val vault: StoreKeyVault,
    private val protection: UserDataProtection,
    private val channel: FileChannel,
    private val lock: FileLock,
    initial: StoredRecord?,
) : AutoCloseable {
    private sealed interface Phase {
        class Active(val record: StoredRecord?) : Phase
        data object Retired : Phase
        data object Closed : Phase
    }
    private var phase: Phase = Phase.Active(initial)

    @Synchronized internal fun initialize() = AccountRepository.safeIo {
        write(StoredRecord.Pending(null))
        for (path in listOf(paths.data, paths.cache, paths.drafts, paths.media)) Files.createDirectory(path)
    }

    @Synchronized internal fun load() = AccountRepository.safeIo {
        val record = AccountRepository.withKey(vault, slot) { key -> AccountRepository.decodeRecord(slot, paths.root, key, protection) }
        SdkStoreLayout.requireDirectories(paths)
        if (record is StoredRecord.Linked) SdkStoreLayout.requireExisting(paths)
        phase = Phase.Active(record)
    }

    @Synchronized fun savedSession(): Session? = active().record?.session

    @Synchronized fun linkedSession(): Session = (active().record as? StoredRecord.Linked)?.session
        ?: throw StorageFailure(StorageCode.LINK_INCOMPLETE)

    @Synchronized fun persistSession(session: Session) = AccountRepository.safeIo {
        val current = active().record ?: throw StorageFailure(StorageCode.CORRUPT_RECORD)
        validateIdentity(current.session, session)
        write(if (current is StoredRecord.Linked) StoredRecord.Linked(session) else StoredRecord.Pending(session))
    }

    /** Only the completed phone-link protocol may promote a pending slot to linked. */
    @Synchronized internal fun completeLink(session: Session) = AccountRepository.safeIo {
        val current = active().record ?: throw StorageFailure(StorageCode.CORRUPT_RECORD)
        validateIdentity(current.session, session)
        SdkStoreLayout.requireExisting(paths)
        write(StoredRecord.Linked(session))
    }

    @Synchronized fun isCurrent(): Boolean = phase is Phase.Active

    fun <T> useStoreKey(operation: (ByteArray) -> T): T = synchronized(this) {
        active()
        AccountRepository.withKey(vault, slot, operation)
    }

    /** Invalidates callbacks first. Persistence is a separate required step for an explicit unlink. */
    @Synchronized fun retire() {
        if (phase !is Phase.Closed) phase = Phase.Retired
    }

    @Synchronized fun revoke(): RevocationResult {
        if (phase is Phase.Closed) throw StorageFailure(StorageCode.STALE_OWNER)
        phase = Phase.Retired
        val marker = runCatching {
            AccountRepository.safeIo { AccountRepository.atomicWrite(paths.root.resolve(AccountRepository.REVOKED), byteArrayOf()) }
        }
        val key = runCatching { vault.remove(slot) }
        return when {
            marker.isSuccess && key.isSuccess -> RevocationResult.Complete
            marker.isSuccess || key.isSuccess -> RevocationResult.CleanupRequired(StorageCode.IO_FAILURE)
            else -> RevocationResult.Blocked(StorageCode.IO_FAILURE)
        }
    }

    @Synchronized override fun close() {
        if (phase is Phase.Closed) return
        phase = Phase.Closed
        try { lock.release() } finally { channel.close() }
    }

    private fun active(): Phase.Active {
        if (Files.exists(paths.root.resolve(AccountRepository.REVOKED), NOFOLLOW_LINKS)) throw StorageFailure(StorageCode.REVOKED)
        return phase as? Phase.Active ?: throw StorageFailure(StorageCode.STALE_OWNER)
    }

    private fun write(record: StoredRecord) {
        active()
        val plain = SessionCodec.encode(slot, record)
        try {
            if (plain.size > 1_048_576) throw StorageFailure(StorageCode.CORRUPT_RECORD)
            val sealed = AccountRepository.withKey(vault, slot) { key -> protection.protect(plain, key) }
            AccountRepository.atomicWrite(paths.root.resolve(AccountRepository.RECORD), sealed)
            phase = Phase.Active(record)
        } finally { plain.fill(0) }
    }

    private fun validateIdentity(previous: Session?, next: Session) {
        if (next.userId.isBlank() || next.deviceId.isBlank() || next.accessToken.isBlank() ||
            (previous != null && (previous.userId != next.userId || previous.deviceId != next.deviceId ||
                                 previous.homeserverUrl != next.homeserverUrl))) {
            throw StorageFailure(StorageCode.IDENTITY_MISMATCH)
        }
    }

    override fun toString(): String = "AccountLease(${slot.value})"
}
