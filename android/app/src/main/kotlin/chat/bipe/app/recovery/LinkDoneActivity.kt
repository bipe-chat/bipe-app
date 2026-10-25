// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.recovery

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import chat.bipe.app.MainActivity

/**
 * Receives `<application id>:/link-done` from the Custom Tab of a linked-account web flow (ADR 0096), gives it to
 * [LinkWebFlow] and brings MainActivity back to the top, which closes the tab above it.
 */
class LinkDoneActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let(LinkWebFlow::offer)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
