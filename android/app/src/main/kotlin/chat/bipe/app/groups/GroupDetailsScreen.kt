// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import java.text.NumberFormat
import kotlinx.coroutines.launch

/** The screens behind group-details. */
private enum class GroupSubScreen { NONE, MEMBERS, INVITE, NOTIFICATIONS, GALLERY, REPORT, POLLS, PRIVACY }

/** Spec group-details, with group-members, group-invite-members, chat-notifications and group-leave behind it. */
@Composable
fun GroupDetailsScreen(session: AccountSession, media: MediaLoader, roomId: String, onBack: () -> Unit, onLeft: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember(session, roomId) { GroupDetailsModel(session, roomId, scope) }
    DisposableEffect(model) {
        model.appear()
        onDispose { model.close() }
    }
    var sub by rememberSaveable { mutableStateOf(GroupSubScreen.NONE) }
    var leaving by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val sentText = stringResource(R.string.bipe_dm_invite_to_group_sent)
    val back = {
        sub = GroupSubScreen.NONE
        model.refresh()
    }
    when (sub) {
        GroupSubScreen.MEMBERS -> {
            GroupMembersScreen(session, media, roomId, onBack = back)
            return
        }
        GroupSubScreen.INVITE -> {
            GroupInviteMembersScreen(session, media, GroupInviteMembersModel.Mode.Invite(roomId), onBack = back) {
                Toast.makeText(context, sentText, Toast.LENGTH_SHORT).show()
                back()
            }
            return
        }
        GroupSubScreen.NOTIFICATIONS -> {
            BackHandler(onBack = back)
            ChatNotificationsScreen(session, roomId, onBack = back)
            return
        }
        // Spec chat-media-gallery (bipe-chat/bipe#672).
        GroupSubScreen.GALLERY -> chat.bipe.app.media.ChatMediaGalleryScreen(session, media, roomId, onClose = back)
        // Spec group-details notes, report (bipe-chat/bipe#674).
        GroupSubScreen.REPORT -> {
            BackHandler(onBack = back)
            chat.bipe.app.people.ReportChatScreen(
                session, null, roomId, isDm = false, otherUserId = null,
                onSent = { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    back()
                },
                onBack = back,
            )
            return
        }
        // Spec poll-history (bipe-chat/bipe#669).
        GroupSubScreen.POLLS -> {
            chat.bipe.app.polls.PollHistoryScreen(session, roomId, onBack = back)
            return
        }
        // Spec group-privacy (bipe-chat/bipe#772).
        GroupSubScreen.PRIVACY -> {
            GroupPrivacyScreen(session, media, roomId, onBack = back)
            return
        }
        GroupSubScreen.NONE -> Unit
    }
    if (leaving) {
        GroupLeaveSheet(
            session,
            roomId,
            onDismiss = { leaving = false },
            onChooseAdmin = {
                leaving = false
                sub = GroupSubScreen.MEMBERS
            },
            onLeft = onLeft,
        )
    }

    val colors = BipeTheme.colors
    val info by model.info.collectAsState()
    val notificationMode by model.notificationMode.collectAsState()
    val canEdit by model.canEdit.collectAsState()
    val canInvite by model.canInvite.collectAsState()
    val canChangePrivacy by model.canChangePrivacy.collectAsState()
    val phase by model.phase.collectAsState()
    val saveError by model.saveError.collectAsState()
    val editName by model.editName.collectAsState()
    val editDescription by model.editDescription.collectAsState()
    val editPhoto by model.editPhoto.collectAsState()
    val viewing = phase == GroupDetailsModel.Phase.VIEWING
    val saveErrorText = stringResource(R.string.bipe_app_save_error)
    LaunchedEffect(saveError) {
        if (saveError) {
            Toast.makeText(context, saveErrorText, Toast.LENGTH_LONG).show()
            model.dismissSaveError()
        }
    }
    BackHandler { if (viewing) onBack() else model.cancelEdit() }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                readPhoto(context, uri)?.let { (mime, bytes) -> model.editPhoto.value = GroupDetailsModel.PhotoEdit.New(mime, bytes) }
            }
        }
    }
    val count = NumberFormat.getIntegerInstance().format(info.memberCount)

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.GROUP_DETAILS.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            GroupTopBar(
                stringResource(R.string.bipe_app_group_details_title),
                onBack = if (viewing) onBack else model::cancelEdit,
                backIcon = if (viewing) PhosphorIcon.CARET_LEFT else PhosphorIcon.X,
            ) {
                when {
                    viewing && canEdit -> TextButton(onClick = model::edit) { Text(stringResource(R.string.bipe_app_edit)) }
                    phase == GroupDetailsModel.Phase.SAVING ->
                        CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(24.dp).padding(end = 4.dp))
                    phase == GroupDetailsModel.Phase.EDITING ->
                        TextButton(onClick = model::save, enabled = model.canSave(editName, phase)) { Text(stringResource(R.string.bipe_app_save)) }
                }
            }
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val newPhoto = (editPhoto as? GroupDetailsModel.PhotoEdit.New).takeUnless { viewing }
                    val bitmap = remember(newPhoto) { newPhoto?.let { BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size)?.asImageBitmap() } }
                    if (bitmap != null) {
                        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp).clip(CircleShape))
                    } else {
                        val removed = !viewing && editPhoto == GroupDetailsModel.PhotoEdit.Removed
                        Avatar(info.name, if (removed) null else info.avatarUrl, media, size = 96.dp)
                    }
                    if (viewing) {
                        Text(info.name, style = MaterialTheme.typography.headlineSmall, color = colors.inkPrimary, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.bipe_app_group_details_people, count), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                    } else {
                        TextButton(
                            onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            enabled = phase == GroupDetailsModel.Phase.EDITING,
                        ) {
                            Text(stringResource(R.string.bipe_app_group_photo_choose))
                        }
                        val hasPhoto = editPhoto is GroupDetailsModel.PhotoEdit.New ||
                            (editPhoto == GroupDetailsModel.PhotoEdit.Unchanged && info.avatarUrl != null)
                        if (hasPhoto) {
                            TextButton(onClick = { model.editPhoto.value = GroupDetailsModel.PhotoEdit.Removed }) {
                                Text(stringResource(R.string.bipe_app_group_photo_remove), color = colors.trustDangerInk)
                            }
                        }
                    }
                }
                if (viewing) {
                    val description = info.description
                    if (description != null || canEdit) {
                        GroupSectionHeader(stringResource(R.string.bipe_app_group_description))
                    }
                    if (description != null) {
                        Text(description, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary, modifier = Modifier.padding(horizontal = 16.dp))
                    } else if (canEdit) {
                        TextButton(onClick = model::edit, modifier = Modifier.padding(horizontal = 4.dp)) {
                            Text(stringResource(R.string.bipe_app_group_description_placeholder))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    GroupRow(PhosphorIcon.USERS_THREE, stringResource(R.string.bipe_app_group_details_people, count)) { sub = GroupSubScreen.MEMBERS }
                    if (canInvite) {
                        GroupRow(PhosphorIcon.USER_PLUS, stringResource(R.string.bipe_app_group_add_people)) { sub = GroupSubScreen.INVITE }
                    }
                    if (canChangePrivacy) {
                        GroupRow(PhosphorIcon.LOCK_SIMPLE, stringResource(R.string.bipe_group_privacy_title)) { sub = GroupSubScreen.PRIVACY }
                    }
                    GroupRow(PhosphorIcon.IMAGE, stringResource(R.string.bipe_gallery_title)) { sub = GroupSubScreen.GALLERY }
                    GroupRow(PhosphorIcon.CHART_BAR, stringResource(R.string.bipe_poll_history)) { sub = GroupSubScreen.POLLS }
                    GroupRow(
                        PhosphorIcon.BELL,
                        stringResource(R.string.bipe_settings_notifications),
                        value = notificationMode?.let { stringResource(it.title) },
                    ) { sub = GroupSubScreen.NOTIFICATIONS }
                    Spacer(Modifier.height(16.dp))
                    GroupRow(PhosphorIcon.FLAG, stringResource(R.string.bipe_app_report_group), tint = colors.trustDangerInk) { sub = GroupSubScreen.REPORT }
                    GroupRow(PhosphorIcon.SIGN_OUT, stringResource(R.string.bipe_app_group_leave_action), tint = colors.trustDangerInk) { leaving = true }
                } else {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { model.editName.value = it },
                        enabled = phase == GroupDetailsModel.Phase.EDITING,
                        singleLine = true,
                        label = { Text(stringResource(R.string.bipe_app_group_name)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    OutlinedTextField(
                        value = editDescription,
                        onValueChange = { model.editDescription.value = it },
                        enabled = phase == GroupDetailsModel.Phase.EDITING,
                        minLines = 2,
                        maxLines = 6,
                        label = { Text(stringResource(R.string.bipe_app_group_description)) },
                        placeholder = { Text(stringResource(R.string.bipe_app_group_description_placeholder)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
