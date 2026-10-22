// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.settings

/** The server data export as the API sends it (`GET /v1/me/export`). The app saves the body as it is. */
data class BipeDataExportFile(val fileName: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is BipeDataExportFile && other.fileName == fileName && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()

    /** Never prints the content. */
    override fun toString(): String = "BipeDataExportFile($fileName, ${bytes.size} bytes)"
}
