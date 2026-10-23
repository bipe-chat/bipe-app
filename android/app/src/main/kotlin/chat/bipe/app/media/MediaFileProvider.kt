// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media

import androidx.core.content.FileProvider

/** Its own class, so the manifest can hold it next to the data export's FileProvider. */
class MediaFileProvider : FileProvider()
