// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads up to [limit] bytes. `InputStream.readNBytes` needs API 33, and minSdk is 26.
 * Callers read `limit + 1` bytes to tell an answer at the limit from a larger one.
 */
fun InputStream.readAtMost(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (output.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (read < 0) break
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
