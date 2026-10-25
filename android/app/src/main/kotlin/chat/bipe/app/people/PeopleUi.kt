// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import kotlinx.coroutines.delay

/** A people screen: a back button and a title, then [content] in a scrolling column. [notice] shows at the bottom for a while. */
@Composable
fun PeopleScaffold(
    title: String,
    testTag: String,
    onBack: () -> Unit,
    notice: String? = null,
    onNoticeShown: () -> Unit = {},
    scrolls: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BipeTheme.colors
    Surface(modifier = Modifier.fillMaxSize().testTag(testTag), color = colors.surfaceBase) {
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(PhosphorIcon.CARET_LEFT.drawable), contentDescription = stringResource(R.string.bipe_a11y_back), tint = colors.glyphPrimary)
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val modifier = if (scrolls) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize()
                Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
            if (notice != null) {
                LaunchedEffect(notice) {
                    delay(3_000)
                    onNoticeShown()
                }
                Text(
                    notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkOnAccent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)
                        .background(colors.accentFill, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** A tappable row with a Phosphor icon in front of its text. */
@Composable
fun IconRow(icon: PhosphorIcon, text: String, tint: Color = BipeTheme.colors.inkPrimary, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon.drawable), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/** One trust mark or warning under a name. */
@Composable
fun MarkLine(icon: PhosphorIcon, text: String, tint: Color, onClick: (() -> Unit)? = null) {
    val modifier = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(modifier = modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(icon.drawable), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/** A grouped block of rows on a raised surface. */
@Composable
fun RowGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(BipeTheme.colors.surfaceRaised, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp),
        content = content,
    )
}
