// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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

/** Spec group-create. onCreated opens the new group. */
@Composable
fun GroupCreateScreen(
    session: AccountSession,
    media: MediaLoader,
    onClose: () -> Unit,
    onCreated: (roomId: String, allInvited: Boolean) -> Unit,
    people: List<Person> = emptyList(),
) {
    val scope = rememberCoroutineScope()
    val model = remember(session) { GroupCreateModel(session, scope, people) }
    LaunchedEffect(model) { model.appear() }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        BackHandler { picking = false }
        GroupInviteMembersScreen(
            session,
            media,
            mode = GroupInviteMembersModel.Mode.Pick(model.people.value),
            onBack = { picking = false },
            onDone = {
                model.setPeople(it)
                picking = false
            },
        )
        return
    }
    BackHandler(onBack = onClose)

    val colors = BipeTheme.colors
    val context = LocalContext.current
    val name by model.name.collectAsState()
    val photo by model.photo.collectAsState()
    val chosen by model.people.collectAsState()
    val phase by model.phase.collectAsState()
    val isQuietDefault by model.isQuietDefault.collectAsState()
    val creating = phase == GroupCreateModel.Phase.CREATING
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                readPhoto(context, uri)?.let { (mime, bytes) -> model.setPhoto(GroupCreateModel.Photo(uri, mime, bytes)) }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.GROUP_CREATE.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            GroupTopBar(stringResource(R.string.bipe_app_group_create_title), onBack = onClose.takeUnless { creating }, backIcon = PhosphorIcon.X) {
                if (creating) {
                    CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(24.dp).padding(end = 4.dp))
                } else {
                    TextButton(onClick = { model.create(onCreated) }, enabled = model.canCreate(name, phase)) {
                        Text(stringResource(R.string.bipe_groups_empty_action))
                    }
                }
            }
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier.size(96.dp).clip(CircleShape).background(colors.accentWash),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bitmap = remember(photo) {
                            photo?.let { BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size)?.asImageBitmap() }
                        }
                        if (bitmap != null) {
                            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp))
                        } else {
                            Icon(painterResource(PhosphorIcon.USERS_THREE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(40.dp))
                        }
                    }
                    TextButton(
                        onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !creating,
                    ) {
                        Text(stringResource(R.string.bipe_app_group_photo_choose))
                    }
                    if (photo != null) {
                        TextButton(onClick = model::removePhoto, enabled = !creating) {
                            Text(stringResource(R.string.bipe_app_group_photo_remove), color = colors.trustDangerInk)
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = model::setName,
                    enabled = !creating,
                    singleLine = true,
                    label = { Text(stringResource(R.string.bipe_app_group_name)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                if (isQuietDefault) {
                    GroupFooter(stringResource(R.string.bipe_app_group_create_quiet_footer))
                }
                if (chosen.isNotEmpty()) {
                    GroupSectionHeader(
                        stringResource(R.string.bipe_app_group_create_people, NumberFormat.getIntegerInstance().format(chosen.size)),
                    )
                }
                chosen.forEach { person ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(person.displayName, person.avatarUrl, media, size = 36.dp, userId = person.userId)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            person.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { model.removePerson(person.userId) }, enabled = !creating) {
                            Icon(
                                painterResource(PhosphorIcon.X.drawable),
                                contentDescription = stringResource(R.string.bipe_a11y_remove_person, person.displayName),
                                tint = colors.glyphSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                GroupRow(PhosphorIcon.USER_PLUS, stringResource(R.string.bipe_app_group_add_people)) {
                    if (!creating) picking = true
                }
                if (phase == GroupCreateModel.Phase.FAILED) {
                    GroupFooter(stringResource(R.string.bipe_app_group_create_error), color = colors.trustDangerInk)
                }
                if (creating) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .background(colors.surfaceRaised, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.bipe_app_group_creating), color = colors.inkSecondary)
                    }
                }
            }
        }
    }
}
