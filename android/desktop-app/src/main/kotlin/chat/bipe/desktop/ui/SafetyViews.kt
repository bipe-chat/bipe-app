// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.useResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.bipe.core.ScamKind
import chat.bipe.desktop.chat.DesktopTimeline
import chat.bipe.desktop.safety.registryId

@Composable
fun ScamWarningCard(model: DesktopTimeline, rowId: String, kind: ScamKind, seconds: Int,
                    s: DesktopCatalog, colors: DesktopColors) {
    LaunchedEffect(model, rowId, kind) { model.warningShown(rowId) }
    val title = s.text("bipe_scam_warning_title")
    val body = s.text(s.warningKey(kind.registryId()))
    val announcement = title + ". " + body + if (seconds > 0) " " + s.text("bipe_pause_countdown", s.moneyPauseSeconds.toString()) else ""
    val density = LocalDensity.current
    val painter = remember(density) { useResource("warning-circle.svg") { loadSvgPainter(it, density) } }
    Surface(color = colors["trust.caution.wash"], contentColor = colors["ink.primary"], shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors["trust.caution.line"]),
        modifier = Modifier.widthIn(max = 540.dp).semantics(mergeDescendants = true) { contentDescription = announcement }) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painter, contentDescription = null, tint = colors["trust.caution.ink"], modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = colors["trust.caution.ink"])
                Text(body, style = MaterialTheme.typography.bodySmall)
                if (seconds > 0) Text(s.text("bipe_pause_countdown", seconds.toString()),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}
