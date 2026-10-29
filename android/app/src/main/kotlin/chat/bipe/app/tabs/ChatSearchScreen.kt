// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.chatlist.ChatSearchModel
import chat.bipe.app.chatlist.ChatSearchResults
import chat.bipe.app.chatlist.ChatRow
import chat.bipe.app.common.MediaLoader
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import java.text.Normalizer

/** Search, the top-bar action: the current account's chats whose name matches. */
@Composable
internal fun ChatSearchScreen(
    model: ChatSearchModel,
    media: MediaLoader,
    onOpenChat: (String) -> Unit,
    onFindHandle: (String) -> Unit,
    onOpenMessage: (String, String) -> Unit = { _, _ -> },
) {
    val colors = BipeTheme.colors
    val state by model.state.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    Surface(modifier = Modifier.fillMaxSize(), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            TextField(
                value = state.query,
                onValueChange = model::edit,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.bipe_app_tab_search)) },
                leadingIcon = {
                    Icon(painterResource(PhosphorIcon.MAGNIFYING_GLASS.drawable), contentDescription = null, modifier = Modifier.size(20.dp))
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceSunken,
                    unfocusedContainerColor = colors.surfaceSunken,
                    focusedIndicatorColor = colors.surfaceSunken,
                    unfocusedIndicatorColor = colors.surfaceSunken,
                ),
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus),
            )
            ChatSearchResults(model, media, onOpenChat, onFindHandle, onOpenMessage)
        }
    }
}
