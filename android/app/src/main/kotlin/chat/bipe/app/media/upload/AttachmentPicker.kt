// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media.upload

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import chat.bipe.app.BipeTheme
import chat.bipe.app.location.MapPin
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Spec attachment-picker: the sheet behind the composer's attach button. Each choice hands over to
 * the system picker; the picked items (file and media type) open upload-preview through onPicked.
 * The Android photo picker and the system camera app need no permission, so permissionProblem stays none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPicker(
    onPicked: (List<Pair<File, String?>>) -> Unit,
    onStickers: () -> Unit,
    /** Action location: opens location-send. */
    onLocation: () -> Unit,
    /** Action poll: opens poll-create. null hides the row (canPoll false). */
    onPoll: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = BipeTheme.colors
    var cameraTargets by remember { mutableStateOf<Pair<File, File>?>(null) }

    fun deliver(uris: List<Uri>) {
        scope.launch {
            val files = uris.mapNotNull { copyToCache(context, it) }
            onDismiss()
            if (files.isNotEmpty()) onPicked(files)
        }
    }

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(UploadPreviewModel.MAX_ITEMS)) { uris ->
        if (uris.isEmpty()) onDismiss() else deliver(uris)
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) onDismiss() else deliver(listOf(uri))
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val targets = cameraTargets
        cameraTargets = null
        val taken = targets?.toList()?.firstOrNull { it.length() > 0 }
        onDismiss()
        if (result.resultCode == Activity.RESULT_OK && taken != null) {
            onPicked(listOf(taken to MediaPreparation.mimeType(taken)))
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = colors.surfaceRaised) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp).testTag(BipeScreen.ATTACHMENT_PICKER.id)) {
            Text(
                stringResource(R.string.bipe_app_composer_attach),
                style = MaterialTheme.typography.titleMedium,
                color = colors.inkPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            // Order of rows: photos and videos, camera, file, location, sticker, poll (spec attachment-picker notes).
            PickerRow(PhosphorIcon.IMAGE, stringResource(R.string.bipe_app_attach_photos)) {
                photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
            PickerRow(PhosphorIcon.CAMERA, stringResource(R.string.bipe_app_attach_camera)) {
                // The system camera app for a photo or a video.
                val folder = MediaPreparation.workFolder(context)
                val photo = File(folder, "IMG-${System.currentTimeMillis()}.jpg")
                val video = File(folder, "VID-${System.currentTimeMillis()}.mp4")
                cameraTargets = photo to video
                val authority = "${context.packageName}.media"
                val photoIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, FileProvider.getUriForFile(context, authority, photo))
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val videoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, FileProvider.getUriForFile(context, authority, video))
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val chooser = Intent.createChooser(photoIntent, null).putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(videoIntent))
                runCatching { camera.launch(chooser) }.onFailure { onDismiss() }
            }
            PickerRow(PhosphorIcon.FILE, stringResource(R.string.bipe_app_attach_file)) {
                file.launch(arrayOf("*/*"))
            }
            // Phosphor has no pin in tools/phosphor.json: the row draws the map pin.
            PickerRow(stringResource(R.string.bipe_app_attach_location), icon = { MapPin() }) {
                onDismiss()
                onLocation()
            }
            PickerRow(PhosphorIcon.STICKER, stringResource(R.string.bipe_action_stickers)) {
                onDismiss()
                onStickers()
            }
            if (onPoll != null) {
                PickerRow(PhosphorIcon.CHART_BAR, stringResource(R.string.bipe_app_attach_poll)) {
                    onDismiss()
                    onPoll()
                }
            }
        }
    }
}

@Composable
private fun PickerRow(icon: PhosphorIcon, title: String, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    PickerRow(title, icon = {
        Icon(painterResource(icon.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(22.dp))
    }, onClick = onClick)
}

@Composable
private fun PickerRow(title: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.size(40.dp).background(colors.accentWash, CircleShape), contentAlignment = Alignment.Center) {
            icon()
        }
        Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
    }
}

/** Copies a picked item into the app cache with its display name; the picker's grant ends with the activity. */
suspend fun copyToCache(context: Context, uri: Uri): Pair<File, String?>? = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "file-${System.currentTimeMillis()}"
        val target = File(MediaPreparation.workFolder(context), name.replace('/', '_'))
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
        target to resolver.getType(uri)
    }.getOrNull()
}
