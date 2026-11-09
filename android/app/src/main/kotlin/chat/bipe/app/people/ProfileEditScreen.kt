// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.people

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.BipeToasts
import chat.bipe.app.handle.HandlePickModel
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeNameLookalikes
import chat.bipe.shared.BipeProfileLink
import chat.bipe.shared.BipeProfileProblem
import chat.bipe.shared.BipeProfileRules
import chat.bipe.shared.BipeSocialHandles
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeMyProfile
import chat.bipe.shared.api.BipeProfileUpdate
import chat.bipe.shared.api.myProfile
import chat.bipe.shared.api.setMyProfile
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.generated.SocialPlatform
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/**
 * Spec profile-edit: Seu perfil on one screen (MZ, 3 Nov 2026). The Matrix name and photo, the @, the public profile
 * (ADR 0041) with its socials (ADR 0228), then Membro desde and the internal ID. One Salvar saves every part.
 * [pickHandle] opens handle-pick and [openLink] opens profile-link; null where the screen has no way to them.
 */
@Composable
fun ProfileEditScreen(people: PeopleService, onBack: () -> Unit, pickHandle: (() -> Unit)? = null, openLink: (() -> Unit)? = null) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Matrix profile.
    var name by remember { mutableStateOf("") }
    var originalName by remember { mutableStateOf("") }
    var avatarUrl by remember { mutableStateOf<String?>(null) }
    var newPhoto by remember { mutableStateOf<ByteArray?>(null) }
    var removesPhoto by remember { mutableStateOf(false) }
    var isCompany by remember { mutableStateOf(false) }
    // Public profile (GET /v1/me/profile).
    var stored by remember { mutableStateOf<BipeMyProfile?>(null) }
    var publicEnabled by remember { mutableStateOf(false) }
    var publicName by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    // Spec socials: the text of each field by registries/profiles.json socials id, without the fixed @.
    var socials by remember { mutableStateOf(mapOf<String, String>()) }
    // MyProfile.socials ids this build does not know. Saved back unchanged, so a newer network is not removed.
    var otherSocials by remember { mutableStateOf(mapOf<String, String>()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val handle = stored?.handle
    // Spec socials: the public page (https://bipe.chat/@handle), only while the saved profile is on, so the link works.
    val publicLink = handle?.takeIf { stored?.enabled == true }?.let(BipeProfileLink::url)

    fun applyProfile(profile: BipeMyProfile) {
        stored = profile
        publicEnabled = profile.enabled
        publicName = profile.name
        bio = profile.bio
        socials = profile.socials.filterKeys { BipeSocialHandles.platform(it) != null }
        otherSocials = profile.socials.filterKeys { BipeSocialHandles.platform(it) == null }
    }

    fun socialProblem(platform: SocialPlatform): BipeProfileProblem? =
        BipeSocialHandles.problem(platform, socials[platform.id].orEmpty(), handle)

    // Every non-empty field after socialHandleClean. The whole object goes, so a cleared field is removed.
    fun socialsUpdate(): Map<String, String> = otherSocials + BipeRegistries.profileSocials
        .associate { it.id to BipeSocialHandles.clean(socials[it.id].orEmpty()) }
        .filterValues { it.isNotEmpty() }

    fun message(failure: Throwable): String {
        val server = failure as? BipeApiException.Server
        val problem = server?.takeIf { it.code == BipeApiErrorCode.PROFILE_INVALID }?.reason?.let(BipeProfileProblem::fromId)
        // The server does not name the network: the first field with the same problem here is the one.
        val network = problem?.takeIf { it.isSocialProblem }?.let { p -> BipeRegistries.profileSocials.firstOrNull { socialProblem(it) == p }?.name }
        return problem?.localizedMessage(context, network)
            ?: (failure as? BipeApiException)?.localizedMessage(context)
            ?: context.getString(R.string.bipe_error_network)
    }

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

    // Action appear: the Matrix profile first (cached avatar at once), then GET /v1/me/profile and syncPhoto.
    LaunchedEffect(Unit) {
        val client = people.session.client
        runCatching { client.cachedAvatarUrl() }.getOrNull()?.let { avatarUrl = it }
        runCatching { client.displayName() }.getOrNull()?.let {
            originalName = it
            if (name.isEmpty()) name = it
        }
        runCatching { client.avatarUrl() }.getOrNull()?.let { avatarUrl = it }
        isCompany = people.isCompany(people.session.userId)
        runCatching { people.withToken { people.api.myProfile(it) } }.onSuccess { profile ->
            applyProfile(profile)
            if (profile.name.isEmpty()) publicName = originalName
        }
        loading = false
        stored?.let { people.syncPublicPhoto(it) }
    }

    // Back from handle-pick: the @ changed, so the public profile switch can turn on at once (bipe-chat/bipe#1328).
    val handleChanges by HandlePickModel.changes.collectAsState()
    LaunchedEffect(handleChanges) {
        if (handleChanges == 0) return@LaunchedEffect
        runCatching { people.withToken { people.api.myProfile(it) } }.onSuccess { profile ->
            if (stored == null || !(publicEnabled != stored?.enabled || publicName.trim() != stored?.name || bio.trim() != stored?.bio)) {
                applyProfile(profile)
            } else {
                stored = profile
            }
        }
    }

    val nameProblem = BipeProfileRules.problem(name, "")
        ?: if (BipeNameLookalikes.sealLookalike(name) != null) BipeProfileProblem.NAME_LOOKALIKE_EMOJI else null
    val publicProblem = if (publicEnabled) BipeProfileRules.problem(publicName, bio) else null
    val hasSocialProblem = publicEnabled && BipeRegistries.profileSocials.any { socialProblem(it) != null }
    val matrixChanged = name.trim() != originalName || newPhoto != null || removesPhoto
    val publicChanged = stored?.let {
        publicEnabled != it.enabled || publicName.trim() != it.name || bio.trim() != it.bio || socialsUpdate() != it.socials
    } ?: false
    val canSave = (matrixChanged || publicChanged) && nameProblem == null && publicProblem == null && !hasSocialProblem && !saving

    // Action save: the Matrix profile, then the public profile, then syncPhoto. Closes after both.
    fun save() {
        saving = true
        error = null
        scope.launch {
            val client = people.session.client
            val trimmed = name.trim()
            val photo = newPhoto
            val photoChanged = photo != null || removesPhoto
            val matrix = runCatching {
                if (trimmed != originalName) {
                    client.setDisplayName(trimmed)
                    originalName = trimmed
                }
                if (photo != null) client.uploadAvatar("image/jpeg", photo) else if (removesPhoto) client.removeAvatar()
                newPhoto = null
                removesPhoto = false
            }
            if (matrix.isFailure) {
                error = context.getString(R.string.bipe_app_save_error)
                saving = false
                return@launch
            }
            if (publicChanged) {
                val update = BipeProfileUpdate(publicEnabled && handle != null, publicName.trim(), bio.trim(), socialsUpdate())
                val result = runCatching { people.syncPublicPhoto(people.withToken { people.api.setMyProfile(update, it) }) }
                result.exceptionOrNull()?.let {
                    error = message(it)
                    saving = false
                    return@launch
                }
                result.getOrNull()?.let(::applyProfile)
            } else if (photoChanged) {
                // The public profile photo is a copy of this one (ADR 0041, amended 2 Nov 2026).
                people.syncPublicPhoto()
            }
            saving = false
            onBack()
        }
    }

    PeopleScaffold(
        stringResource(R.string.bipe_settings_profile),
        BipeScreen.PROFILE_EDIT.id,
        onBack = { if (!saving) onBack() },
        actions = {
            if (saving) {
                CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = ::save, enabled = canSave) { Text(stringResource(R.string.bipe_app_save)) }
            }
        },
    ) {
        // (1) Photo and name.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val preview = remember(newPhoto) { newPhoto?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
            if (preview != null) {
                Image(
                    BitmapPainter(preview),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp).clip(if (isCompany) RoundedCornerShape(percent = 25) else CircleShape),
                )
            } else {
                Avatar(name, if (removesPhoto) null else avatarUrl, people.media, size = 96.dp, isCompany = isCompany)
            }
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
        Footnote(nameProblem?.localizedMessage(context) ?: stringResource(R.string.bipe_profile_display_name_footer), isError = nameProblem != null)

        // (2) The @ row (action openHandle).
        RowGroup {
            when {
                handle != null -> ValueItem(stringResource(R.string.bipe_settings_handle), "@$handle", onClick = openLink)
                !loading && pickHandle != null -> ValueItem(stringResource(R.string.bipe_handle_prompt_action), null, onClick = pickHandle)
            }
        }

        // (3) Public profile.
        SectionTitle(stringResource(R.string.bipe_settings_public_profile))
        SwitchRow(stringResource(R.string.bipe_public_profile_toggle), publicEnabled && handle != null) { if (handle != null && stored != null) publicEnabled = it }
        Footnote(stringResource(if (handle == null && !loading) R.string.bipe_public_profile_needs_handle else R.string.bipe_public_profile_footer))
        if (publicEnabled && handle != null) {
            OutlinedTextField(
                value = publicName,
                onValueChange = { publicName = it },
                label = { Text(stringResource(R.string.bipe_public_profile_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text(stringResource(R.string.bipe_public_profile_bio)) },
                placeholder = { Text(stringResource(R.string.bipe_public_profile_bio_placeholder)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            publicProblem?.let { Footnote(it.localizedMessage(context), isError = true) }
            // Spec socials (ADR 0228): the public page link with copy and share, then one field per network in registry
            // order, a fixed @ before the text. The field takes a handle, never a URL: plain text keyboard, no autocorrect,
            // no autocapitalisation.
            SectionTitle(stringResource(R.string.bipe_public_profile_socials))
            publicLink?.let { link ->
                RowGroup {
                    ValueItem(stringResource(R.string.bipe_app_profile_link), link, monospaced = true)
                    // Action copyLink.
                    IconRow(PhosphorIcon.COPY, stringResource(R.string.bipe_app_copy), containerColor = colors.surfaceBase) {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(link, link))
                        BipeToasts.copied(context)
                    }
                    // Action shareLink.
                    IconRow(PhosphorIcon.SHARE_NETWORK, stringResource(R.string.bipe_app_share), containerColor = colors.surfaceBase) {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null))
                    }
                }
            }
            BipeRegistries.profileSocials.forEach { platform ->
                val fieldProblem = socialProblem(platform)
                OutlinedTextField(
                    value = socials[platform.id].orEmpty(),
                    // Action setSocial.
                    onValueChange = { socials = socials + (platform.id to it) },
                    label = { Text(platform.name) },
                    prefix = { Text("@", color = colors.inkSecondary) },
                    singleLine = true,
                    isError = fieldProblem != null,
                    supportingText = fieldProblem?.let { p -> @Composable { Text(p.localizedMessage(context, platform.name), color = colors.trustDangerInk) } },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Footnote(stringResource(R.string.bipe_public_profile_socials_footer))
        }

        // (4) Membro desde and the internal ID (MZ, 3 Nov 2026).
        RowGroup {
            stored?.memberSince?.let { iso -> memberSinceText(iso)?.let { ValueItem(stringResource(R.string.bipe_profile_member_since), it) } }
            // Action copyUserId.
            ValueItem(stringResource(R.string.bipe_profile_internal_id), people.session.userId, monospaced = true) {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(people.session.userId, people.session.userId))
                BipeToasts.copied(context)
            }
        }
        Footnote(stringResource(R.string.bipe_profile_internal_id_footer))
        error?.let { Footnote(it, isError = true) }
    }
}

/** MyProfile.member_since as a medium date in the phone's locale and time zone. */
private fun memberSinceText(iso: String): String? = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}.getOrNull()

/** A list item with a title and, under it, a value; tappable when [onClick] is set. */
@Composable
private fun ValueItem(title: String, value: String?, monospaced: Boolean = false, onClick: (() -> Unit)? = null) {
    val colors = BipeTheme.colors
    ListItem(
        headlineContent = { Text(title, color = colors.inkPrimary) },
        supportingContent = value?.let { v ->
            @Composable { Text(v, color = colors.inkSecondary, fontFamily = if (monospaced) FontFamily.Monospace else null) }
        },
        colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
        modifier = if (onClick != null) Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick) else Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = BipeTheme.colors.inkPrimary, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Footnote(text: String, isError: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (isError) BipeTheme.colors.trustDangerInk else BipeTheme.colors.inkSecondary)
}
