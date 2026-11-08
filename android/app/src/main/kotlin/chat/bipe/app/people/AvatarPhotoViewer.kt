// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.common.bipeTapTarget
import chat.bipe.app.media.LoadedImage
import chat.bipe.app.media.ZoomableImage
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeDarkColors
import chat.bipe.shared.generated.BipeScreen

/** Spec person-profile and group-details openPhoto: the avatar as a button that opens spec avatar-viewer. */
fun Modifier.opensAvatarPhoto(enabled: Boolean, label: String, onOpen: () -> Unit): Modifier =
    bipeTapTarget().clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onOpen)

/** Spec avatar-viewer (bipe-chat/bipe#829): a person's or a group's photo full screen, with zoom. No save and no share. */
@Composable
fun AvatarPhotoViewer(name: String, mxcUrl: String?, publicPhotoUrl: String?, loader: MediaLoader, onClose: () -> Unit) {
    // The viewer is dark in both themes, like spec media-viewer.
    val colors = BipeDarkColors
    var loading by remember { mutableStateOf(true) }
    var image by remember { mutableStateOf<LoadedImage?>(null) }
    LaunchedEffect(mxcUrl, publicPhotoUrl) {
        val bitmap = mxcUrl?.let { loader.thumbnail(it, 1600) } ?: publicPhotoUrl?.let { loader.publicPhoto(it) }
        image = bitmap?.let { LoadedImage(it) }
        loading = false
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfaceBase)
                .testTag(BipeScreen.AVATAR_VIEWER.id)
                .pointerInput(Unit) {
                    // Swipe down closes (action close).
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (total > 300f) onClose() },
                    ) { _, dragAmount -> total += dragAmount }
                },
            contentAlignment = Alignment.Center,
        ) {
            val shown = image
            when {
                shown != null -> ZoomableImage(shown, name, onTap = {})
                loading -> CircularProgressIndicator(color = colors.inkPrimary)
                else -> Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = colors.inkPrimary)
            }
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopStart).safeDrawingPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(painterResource(PhosphorIcon.X.drawable), contentDescription = stringResource(R.string.bipe_app_close), tint = colors.inkPrimary)
                }
                Text(name, style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
