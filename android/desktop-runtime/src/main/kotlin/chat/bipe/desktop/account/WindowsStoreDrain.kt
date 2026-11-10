// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.account

import chat.bipe.desktop.storage.AccountPaths
import chat.bipe.desktop.storage.StorageCode
import chat.bipe.desktop.storage.StorageFailure
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Checks actual Windows handle release without deleting a persistent SDK database as a probe. */
object WindowsStoreDrain {
    suspend fun await(paths: AccountPaths) {
        withTimeout(10_000) {
            while (!released(paths)) delay(25)
        }
    }

    private fun released(paths: AccountPaths): Boolean {
        for (directory in listOf(paths.data, paths.cache)) {
            if (!Files.exists(directory, NOFOLLOW_LINKS)) continue
            val files = Files.walk(directory).use { stream ->
                stream.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList()
            }
            for (file in files) if (!exclusive(file)) return false
        }
        return true
    }

    private fun exclusive(file: Path): Boolean {
        val handle = Kernel32.INSTANCE.CreateFile(file.toString(), WinNT.GENERIC_READ, 0, null,
                                                  WinNT.OPEN_EXISTING, WinNT.FILE_ATTRIBUTE_NORMAL, null)
        if (handle == WinBase.INVALID_HANDLE_VALUE) {
            return when (val error = Kernel32.INSTANCE.GetLastError()) {
                2, 3 -> true // An SDK worker removed a journal between enumeration and the probe.
                32, 33 -> false // Sharing/lock violation: a native worker or another process still owns it.
                else -> throw StorageFailure(StorageCode.NATIVE_FAILURE, error)
            }
        }
        if (!Kernel32.INSTANCE.CloseHandle(handle)) {
            throw StorageFailure(StorageCode.NATIVE_FAILURE, Kernel32.INSTANCE.GetLastError())
        }
        return true
    }
}
