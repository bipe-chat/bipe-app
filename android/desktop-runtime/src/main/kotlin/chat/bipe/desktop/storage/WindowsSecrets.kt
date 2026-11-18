// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.storage

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.Win32Exception
import com.sun.jna.platform.win32.WinBase.FILETIME
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.security.MessageDigest

/** Current-user Credential Manager entry. Only the 32-byte SDK store key enters the vault blob. */
class WindowsStoreKeyVault(private val namespace: String = "chat.bipe.app.windows/store-key/v1") : StoreKeyVault {
    private val api: CredentialApi by lazy {
        requireWindows()
        Native.load("Advapi32", CredentialApi::class.java, W32APIOptions.UNICODE_OPTIONS)
    }

    override fun read(slot: AccountSlot): ByteArray? {
        val result = PointerByReference()
        if (!api.CredReadW(WString(target(slot)), GENERIC, 0, result)) {
            val status = Native.getLastError()
            if (status == NOT_FOUND) return null
            throw StorageFailure(StorageCode.NATIVE_FAILURE, status)
        }
        val pointer = result.value ?: throw StorageFailure(StorageCode.NATIVE_FAILURE)
        val credential = Credential(pointer)
        try {
            if (credential.type != GENERIC || credential.blobSize != KEY_SIZE || credential.blob == null) {
                throw StorageFailure(StorageCode.CORRUPT_RECORD)
            }
            return credential.blob!!.getByteArray(0, KEY_SIZE)
        } finally {
            // CredRead allocates a single block. Clear the secret before CredFree releases that block.
            if (credential.blobSize in 1..2560) credential.blob?.clear(credential.blobSize.toLong())
            api.CredFree(pointer)
        }
    }

    override fun create(slot: AccountSlot, key: ByteArray) {
        if (key.size != KEY_SIZE) throw StorageFailure(StorageCode.CORRUPT_RECORD)
        read(slot)?.let { it.fill(0); throw StorageFailure(StorageCode.IDENTITY_MISMATCH) }
        val memory = Memory(KEY_SIZE.toLong())
        try {
            memory.write(0, key, 0, key.size)
            val credential = Credential().apply {
                type = GENERIC
                targetName = WString(target(slot))
                blobSize = key.size
                blob = memory
                persist = LOCAL_MACHINE
                userName = WString("Bipe")
            }
            if (!api.CredWriteW(credential, 0)) {
                throw StorageFailure(StorageCode.NATIVE_FAILURE, Native.getLastError())
            }
            val stored = read(slot) ?: throw StorageFailure(StorageCode.MISSING_KEY)
            try {
                if (!MessageDigest.isEqual(key, stored)) throw StorageFailure(StorageCode.CORRUPT_RECORD)
            } finally { stored.fill(0) }
        } finally {
            memory.clear()
            memory.close()
        }
    }

    override fun remove(slot: AccountSlot) {
        if (!api.CredDeleteW(WString(target(slot)), GENERIC, 0)) {
            val status = Native.getLastError()
            if (status != NOT_FOUND) throw StorageFailure(StorageCode.NATIVE_FAILURE, status)
        }
    }

    private fun target(slot: AccountSlot) = "$namespace/${slot.value}"

    private interface CredentialApi : StdCallLibrary {
        fun CredReadW(target: WString, type: Int, flags: Int, result: PointerByReference): Boolean
        fun CredWriteW(credential: Credential, flags: Int): Boolean
        fun CredDeleteW(target: WString, type: Int, flags: Int): Boolean
        fun CredFree(pointer: Pointer)
    }

    // CREDENTIALW field order/widths from Microsoft wincred.h; JNA supplies pointer alignment and FILETIME.
    @Structure.FieldOrder("flags", "type", "targetName", "comment", "lastWritten", "blobSize", "blob",
                          "persist", "attributeCount", "attributes", "targetAlias", "userName")
    class Credential : Structure {
        @JvmField var flags = 0
        @JvmField var type = 0
        @JvmField var targetName: WString? = null
        @JvmField var comment: WString? = null
        @JvmField var lastWritten = FILETIME()
        @JvmField var blobSize = 0
        @JvmField var blob: Pointer? = null
        @JvmField var persist = 0
        @JvmField var attributeCount = 0
        @JvmField var attributes: Pointer? = null
        @JvmField var targetAlias: WString? = null
        @JvmField var userName: WString? = null

        constructor() : super()
        constructor(pointer: Pointer) : super(pointer) { read() }
    }

    companion object {
        const val KEY_SIZE = 32
        private const val GENERIC = 1
        // Persist for the current user on this machine; do not roam a database key to another device.
        private const val LOCAL_MACHINE = 2
        private const val NOT_FOUND = 1168
    }
}

/** DPAPI's current-user scope; never CRYPTPROTECT_LOCAL_MACHINE and never an interactive prompt. */
class WindowsDataProtection : UserDataProtection {
    override fun protect(clear: ByteArray, entropy: ByteArray): ByteArray = native {
        Crypt32Util.cryptProtectData(clear, entropy, UI_FORBIDDEN, "Bipe session", null)
    }

    override fun unprotect(sealed: ByteArray, entropy: ByteArray): ByteArray = native {
        Crypt32Util.cryptUnprotectData(sealed, entropy, UI_FORBIDDEN, null)
    }

    private fun native(operation: () -> ByteArray): ByteArray {
        requireWindows()
        return try { operation() }
        catch (error: Win32Exception) { throw StorageFailure(StorageCode.NATIVE_FAILURE, error.errorCode) }
    }

    companion object { private const val UI_FORBIDDEN = 1 }
}

internal fun requireWindows() {
    if (!Platform.isWindows()) throw StorageFailure(StorageCode.WINDOWS_UNAVAILABLE)
}
