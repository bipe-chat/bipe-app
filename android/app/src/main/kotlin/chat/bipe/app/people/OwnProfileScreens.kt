// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.people

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeProfileLink
import chat.bipe.shared.BipeProfileProblem
import chat.bipe.shared.BipeProfileRules
import chat.bipe.shared.BipeNameLookalikes
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeMyProfile
import chat.bipe.shared.api.BipeProfileUpdate
import chat.bipe.shared.api.deleteMyProfilePhoto
import chat.bipe.shared.api.myProfile
import chat.bipe.shared.api.setMyProfile
import chat.bipe.shared.api.setMyProfilePhoto
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.MediaSource

/** Square JPEG for profile photos: at most `profilePhotoMaxPixels` on each side and `profilePhotoMaxBytes`. */
object BipeProfilePhoto {
    /** Crops the centre square, scales it down and re-encodes it as JPEG with no metadata. `null` when it does not fit. */
    fun jpeg(bitmap: Bitmap, maxPixels: Int = BipeRegistries.PROFILE_PHOTO_MAX_PIXELS, maxBytes: Int = BipeRegistries.PROFILE_PHOTO_MAX_BYTES): ByteArray? {
        val side = minOf(bitmap.width, bitmap.height)
        if (side <= 0) return null
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val target = minOf(maxPixels, side)
        val scaled = if (target == side) square else Bitmap.createScaledBitmap(square, target, target, true)
        for (quality in listOf(85, 75, 60, 45, 30)) {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= maxBytes) return out.toByteArray()
        }
        return null
    }

    /** Reads a picked image. */
    suspend fun load(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }
}

private fun ByteArray.toImage(): ImageBitmap? = BitmapFactory.decodeByteArray(this, 0, size)?.asImageBitmap()

@Composable
private fun PhotoPreview(jpeg: ByteArray?, fallback: @Composable () -> Unit, isCompany: Boolean = false) {
    val image = remember(jpeg) { jpeg?.toImage() }
    if (image != null) {
        Image(
            BitmapPainter(image),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(96.dp).clip(if (isCompany) RoundedCornerShape(percent = 25) else CircleShape),
        )
    } else {
        fallback()
    }
}

/** Spec profile-edit: the name and photo that people who chat with the user see (the Matrix profile). */
@Composable
fun ProfileEditScreen(people: PeopleService, onBack: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var originalName by remember { mutableStateOf("") }
    var avatarUrl by remember { mutableStateOf<String?>(null) }
    var newPhoto by remember { mutableStateOf<ByteArray?>(null) }
    var removesPhoto by remember { mutableStateOf(false) }
    var isCompany by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val bitmap = BipeProfilePhoto.load(context, uri) ?: return@launch
                BipeProfilePhoto.jpeg(bitmap, maxPixels = 1024, maxBytes = 2_000_000)?.let {
                    newPhoto = it
                    removesPhoto = false
                }
            }
        }
    }
    // Action appear: Client.cachedAvatarUrl first, to show at once.
    LaunchedEffect(Unit) {
        val client = people.session.client
        runCatching { client.cachedAvatarUrl() }.getOrNull()?.let { avatarUrl = it }
        runCatching { client.displayName() }.getOrNull()?.let {
            originalName = it
            if (name.isEmpty()) name = it
        }
        runCatching { client.avatarUrl() }.getOrNull()?.let { avatarUrl = it }
        isCompany = people.isCompany(people.session.userId)
    }
    // Spec profile-edit nameProblem: lookalikeEmoji after the shared rules (security rule 8).
    val problem = BipeProfileRules.problem(name, "")
        ?: if (BipeNameLookalikes.sealLookalike(name) != null) BipeProfileProblem.NAME_LOOKALIKE_EMOJI else null
    val changed = name.trim() != originalName || newPhoto != null || removesPhoto

    PeopleScaffold(stringResource(R.string.bipe_settings_profile_name_photo), BipeScreen.PROFILE_EDIT.id, onBack = { if (!saving) onBack() }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PhotoPreview(newPhoto, isCompany = isCompany, fallback = {
                Avatar(name, if (removesPhoto) null else avatarUrl, people.media, size = 96.dp, isCompany = isCompany)
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(stringResource(R.string.bipe_public_profile_photo_change))
                }
                if (newPhoto != null || (avatarUrl != null && !removesPhoto)) {
                    TextButton(onClick = { newPhoto = null; removesPhoto = true }) {
                        Text(stringResource(R.string.bipe_public_profile_photo_remove), color = colors.trustDangerInk)
                    }
                }
            }
        }
        val label = stringResource(if (isCompany) R.string.bipe_company_name else R.string.bipe_profile_display_name)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(
            problem?.localizedMessage(context) ?: stringResource(R.string.bipe_profile_display_name_footer),
            style = MaterialTheme.typography.bodySmall,
            color = if (problem != null) colors.trustDangerInk else colors.inkSecondary,
        )
        if (failed) Text(stringResource(R.string.bipe_app_save_error), color = colors.trustDangerInk)
        Button(
            enabled = problem == null && changed && !saving,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                saving = true
                failed = false
                scope.launch {
                    // Action save: sends only what changed, then closes.
                    val client = people.session.client
                    val trimmed = name.trim()
                    val result = runCatching {
                        if (trimmed != originalName) {
                            client.setDisplayName(trimmed)
                            originalName = trimmed
                        }
                        val photo = newPhoto
                        if (photo != null) {
                            client.uploadAvatar("image/jpeg", photo)
                        } else if (removesPhoto) {
                            client.removeAvatar()
                        }
                        Unit
                    }
                    saving = false
                    if (result.isSuccess) onBack() else failed = true
                }
            },
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.bipe_app_save))
        }
    }
}

/** Spec public-profile-edit: the public profile behind bipe.chat/@handle, in no search (ADR 0041). */
@Composable
fun PublicProfileEditScreen(people: PeopleService, pickHandle: () -> Unit, openLink: () -> Unit, onBack: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var isCompany by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var handle by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var photoUrl by remember { mutableStateOf<String?>(null) }
    var remotePhoto by remember { mutableStateOf<ByteArray?>(null) }
    var newPhoto by remember { mutableStateOf<ByteArray?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var savedOnce by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun applyProfile(profile: BipeMyProfile) {
        enabled = profile.enabled
        handle = profile.handle
        name = profile.name
        bio = profile.bio
        photoUrl = profile.photoUrl
    }

    fun message(failure: Throwable): String {
        val server = failure as? BipeApiException.Server
        val problem = server?.takeIf { it.code == BipeApiErrorCode.PROFILE_INVALID }?.reason?.let(BipeProfileProblem::fromId)
        return problem?.localizedMessage(context)
            ?: (failure as? BipeApiException)?.localizedMessage(context)
            ?: context.getString(R.string.bipe_error_network)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val jpeg = BipeProfilePhoto.load(context, uri)?.let { BipeProfilePhoto.jpeg(it) }
                if (jpeg == null) error = context.getString(R.string.bipe_public_profile_photo_invalid) else { error = null; newPhoto = jpeg }
            }
        }
    }

    // Action appear: GET /v1/me/profile. A profile never saved starts from the Matrix profile.
    LaunchedEffect(Unit) {
        isCompany = people.media.isCompany(people.session.userId)
        runCatching { people.withToken { people.api.myProfile(it) } }
            .onSuccess { profile ->
                applyProfile(profile)
                savedOnce = profile.enabled
                if (profile.name.isEmpty()) {
                    name = runCatching { people.session.client.displayName() }.getOrNull().orEmpty()
                    if (!profile.hasPhoto) {
                        val mxc = runCatching { people.session.client.avatarUrl() }.getOrNull()
                        val bytes = mxc?.let { url -> runCatching { people.session.client.getMediaContent(MediaSource.fromUrl(url)) }.getOrNull() }
                        newPhoto = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { BipeProfilePhoto.jpeg(it) }
                    }
                }
            }
            .onFailure { error = message(it) }
        loading = false
    }
    LaunchedEffect(photoUrl) {
        val url = photoUrl
        remotePhoto = if (url == null) null else withContext(Dispatchers.IO) { runCatching { URL(url).readBytes() }.getOrNull() }
    }

    val problem = BipeProfileRules.problem(name, bio)
    PeopleScaffold(stringResource(R.string.bipe_settings_public_profile), BipeScreen.PUBLIC_PROFILE_EDIT.id, onBack) {
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
            return@PeopleScaffold
        }
        SwitchRow(stringResource(R.string.bipe_public_profile_toggle), enabled && handle != null) { if (handle != null) enabled = it }
        if (handle == null) {
            Text(stringResource(R.string.bipe_public_profile_needs_handle), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            TextButton(onClick = pickHandle) { Text(stringResource(R.string.bipe_handle_prompt_action)) }
        } else {
            Text(stringResource(R.string.bipe_public_profile_footer), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            PhotoPreview(newPhoto ?: remotePhoto, isCompany = isCompany,
                fallback = { Avatar(name, null, people.media, size = 96.dp, userId = people.session.userId, isCompany = isCompany) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(stringResource(R.string.bipe_public_profile_photo_change))
                }
                if (newPhoto != null || photoUrl != null) {
                    TextButton(onClick = {
                        if (newPhoto != null) {
                            newPhoto = null
                        } else {
                            // Action removePhoto: DELETE /v1/me/profile/photo.
                            scope.launch {
                                runCatching { people.withToken { people.api.deleteMyProfilePhoto(it) } }
                                    .onSuccess { photoUrl = it.photoUrl }
                                    .onFailure { error = message(it) }
                            }
                        }
                    }) {
                        Text(stringResource(R.string.bipe_public_profile_photo_remove), color = colors.trustDangerInk)
                    }
                }
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; saved = false },
            label = { Text(stringResource(R.string.bipe_public_profile_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = bio,
            onValueChange = { bio = it; saved = false },
            label = { Text(stringResource(R.string.bipe_public_profile_bio)) },
            placeholder = { Text(stringResource(R.string.bipe_public_profile_bio_placeholder)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            problem != null -> Text(problem.localizedMessage(context), color = colors.trustDangerInk)
            error != null -> Text(error.orEmpty(), color = colors.trustDangerInk)
            saved -> Text(stringResource(R.string.bipe_public_profile_saved), color = colors.statusSuccess)
        }
        Button(
            enabled = problem == null && !saving,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                // Action save: PUT /v1/me/profile, then the new photo.
                saving = true
                error = null
                val isOn = enabled && handle != null
                scope.launch {
                    runCatching {
                        var profile = people.withToken { people.api.setMyProfile(BipeProfileUpdate(isOn, name.trim(), bio.trim()), it) }
                        newPhoto?.let { jpeg -> profile = people.withToken { people.api.setMyProfilePhoto(jpeg, it) } }
                        profile
                    }.onSuccess {
                        newPhoto = null
                        applyProfile(it)
                        saved = true
                        savedOnce = true
                    }.onFailure { error = message(it) }
                    saving = false
                }
            },
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.bipe_public_profile_save))
        }
        if (enabled && savedOnce) {
            IconRow(PhosphorIcon.SHARE_NETWORK, stringResource(R.string.bipe_public_profile_share), onClick = openLink)
        }
    }
}

/** Spec profile-link: the user's link (bipe.chat/@handle) and its QR code. */
@Composable
fun ProfileLinkScreen(people: PeopleService, pickHandle: () -> Unit, onBack: () -> Unit, showQr: Boolean = false) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(false) }
    var handle by remember { mutableStateOf<String?>(null) }
    var showingQr by remember { mutableStateOf(showQr) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        handle = runCatching { people.withToken { people.api.myHandle(it) } }.getOrNull()?.handle
        loaded = true
    }
    PeopleScaffold(
        stringResource(R.string.bipe_app_profile_link),
        BipeScreen.PROFILE_LINK.id,
        onBack,
        notice = notice,
        onNoticeShown = { notice = null },
    ) {
        val link = handle?.let(BipeProfileLink::url)
        when {
            link != null -> {
                Text(link, fontFamily = FontFamily.Monospace, color = colors.inkPrimary)
                RowGroup {
                    IconRow(PhosphorIcon.SHARE_NETWORK, stringResource(R.string.bipe_profile_share)) {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null))
                    }
                    IconRow(PhosphorIcon.COPY, stringResource(R.string.bipe_app_copy)) {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText(link, link))
                        notice = context.getString(R.string.bipe_app_copied)
                    }
                    IconRow(PhosphorIcon.QR_CODE, stringResource(R.string.bipe_profile_qr_action)) { showingQr = !showingQr }
                }
                if (showingQr) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.bipe_profile_qr_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                        QrCode(link)
                        Text(stringResource(R.string.bipe_profile_qr_footer), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                    }
                }
            }
            loaded -> {
                Text(stringResource(R.string.bipe_public_profile_needs_handle), color = colors.inkSecondary)
                TextButton(onClick = pickHandle) { Text(stringResource(R.string.bipe_handle_prompt_action)) }
            }
            else -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
        }
    }
}

/** A QR code that holds exactly [text], dark on light in both themes, drawn on the device (ZXing). */
@Composable
fun QrCode(text: String) {
    val image = remember(text) { qrBitmap(text)?.asImageBitmap() } ?: return
    // Scanners need dark modules on a light ground in both themes (spec profile-link); no token is fixed light.
    Box(Modifier.background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Image(BitmapPainter(image, filterQuality = androidx.compose.ui.graphics.FilterQuality.None), contentDescription = text, modifier = Modifier.size(240.dp))
    }
}

/** The QR code of [text], one pixel per module and no margin; also the counter QR code of queue-owner. */
internal fun qrBitmap(text: String): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix.get(index % matrix.width, index / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}.getOrNull()
