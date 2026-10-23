// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.notifications.NotificationSettingsScreen
import chat.bipe.app.BipeTheme
import chat.bipe.app.encryption.RecoveryKeySaveScreen
import chat.bipe.app.account.AccountSession
import chat.bipe.app.people.PeopleService
import chat.bipe.app.people.PeopleRoute
import chat.bipe.app.people.PeopleScreen
import chat.bipe.app.people.PeopleNavigation
import chat.bipe.app.invites.InvitesModel
import chat.bipe.app.invites.InvitesScreen
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.handle.HandlePickModel
import chat.bipe.app.handle.HandlePickScreen
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.bipeHaptic
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.generated.BipeSettingsGroup
import chat.bipe.shared.generated.BipeSettingsRow
import chat.bipe.shared.generated.BipeSettingsRowKind
import chat.bipe.shared.generated.BipeSettingsSection
import chat.bipe.shared.settings.BipeWhoCanMessage
import chat.bipe.shared.settings.isShownOnAndroid
import kotlinx.coroutines.launch

/** The screens that the settings stack pushes. */
sealed interface SettingsRoute {
    data class People(val route: PeopleRoute) : SettingsRoute
    data object HandlePick : SettingsRoute
    data object Invites : SettingsRoute
    data class Group(val group: BipeSettingsGroup) : SettingsRoute
    data object Notifications : SettingsRoute
    data object Messaging : SettingsRoute
    data object Support : SettingsRoute
    data object PinChange : SettingsRoute
    data object AppLockSetup : SettingsRoute
    data object AppLockOff : SettingsRoute
    data object LinkedDevices : SettingsRoute
    data object ProblemReport : SettingsRoute
    data object Licences : SettingsRoute
    data object ShowMediaChoice : SettingsRoute
    data object RecoveryKey : SettingsRoute
}

/**
 * The settings stack. Until the Você tab (bipe-chat/bipe#567) is on main, chat-list opens it; the Você tab then shows
 * [SettingsRootSection] in its own list.
 */
@Composable
fun SettingsHost(
    session: AccountSession,
    api: BipeApiClient,
    invites: InvitesModel,
    onClose: () -> Unit,
    onSignedOut: () -> Unit,
    onOpenChat: (String) -> Unit = {},
    onProfileChanged: () -> Unit = {},
    rootContent: (@Composable (SettingsModel, (BipeSettingsGroup) -> Unit, (PeopleRoute) -> Unit, () -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(session) { SettingsModel(context.applicationContext, session, api, scope) }
    val media = remember(session) { MediaLoader(session.client) }
    val people = remember(session) { PeopleService(context, session, api, media, scope) }
    androidx.compose.runtime.DisposableEffect(people) {
        people.start()
        onDispose { people.close() }
    }
    val stack = remember { mutableStateListOf<SettingsRoute>() }
    val pop: () -> Unit = {
        if (stack.isEmpty()) onClose() else {
            stack.removeAt(stack.lastIndex)
            onProfileChanged()
        }
    }
    BackHandler(onBack = pop)
    LaunchedEffect(model) { model.load() }
    val openGroup: (BipeSettingsGroup) -> Unit = { group ->
        stack.add(if (group == BipeSettingsGroup.NOTIFICATIONS) SettingsRoute.Notifications else SettingsRoute.Group(group))
    }
    when (val route = stack.lastOrNull()) {
        null -> {
            if (rootContent != null) rootContent(model, openGroup,
                { stack.add(SettingsRoute.People(it)) }, { stack.add(SettingsRoute.Invites) })
            else {
                SettingsPage(stringResource(R.string.bipe_settings_section), BipeScreen.SETTINGS_ROOT.id, onBack = onClose) {
                    SettingsRootSection(model, openGroup)
                }
            }
        }
        is SettingsRoute.Group -> SettingsGroupScreen(model, route.group, onBack = pop, onOpen = { stack.add(it) }, onSignedOut = onSignedOut)
        is SettingsRoute.People -> PeopleScreen(route.route, people, PeopleNavigation(
            push = { stack.add(SettingsRoute.People(it)) }, pop = pop,
            openChat = { stack.clear(); onOpenChat(it) },
            pickHandle = { stack.add(SettingsRoute.HandlePick) },
            invite = { stack.add(SettingsRoute.Invites) },
        ))
        SettingsRoute.Invites -> InvitesScreen(invites, people, onBack = pop,
            openChat = { stack.clear(); onOpenChat(it) })
        SettingsRoute.HandlePick -> {
            val handle = remember(session) { HandlePickModel(context, api, session, scope) }
            LaunchedEffect(handle) { handle.load() }
            HandlePickScreen(handle, onClose = pop)
        }
        SettingsRoute.Notifications -> NotificationSettingsScreen(session, onBack = pop)
        SettingsRoute.Messaging -> MessagingPickerScreen(model, onBack = pop)
        SettingsRoute.Support -> SupportOpenScreen(session, api, onBack = pop)
        SettingsRoute.PinChange -> PinChangeScreen(model, onClose = pop)
        SettingsRoute.LinkedDevices -> LinkedDevicesScreen(model, onBack = pop)
        SettingsRoute.Licences -> OpenSourceLicencesScreen(onBack = pop)
        SettingsRoute.RecoveryKey -> RecoveryKeySaveScreen(session, onClose = pop)
        SettingsRoute.ShowMediaChoice -> ChoiceListScreen(
            title = stringResource(R.string.bipe_chats_show_media),
            options = ShowMedia.entries.map { it.id to stringResource(it.title) },
            key = SettingsKeys.SHOW_MEDIA,
            default = ShowMedia.ALWAYS.id,
            onBack = pop,
        )
        SettingsRoute.ProblemReport -> ProblemReportScreen(
            model,
            onBack = pop,
            onOpenSupport = {
                stack.removeAt(stack.lastIndex)
                stack.add(SettingsRoute.Support)
            },
        )
        SettingsRoute.AppLockSetup -> AppLockSetupScreen(onClose = pop)
        // Spec app-lock, turnOff on a phone with no screen lock: the app lock PIN.
        SettingsRoute.AppLockOff -> AppLockPinCheck(
            stringResource(R.string.bipe_account_app_lock),
            onPass = {
                AppLockModel.get(context).store.remove()
                AppLockModel.get(context).refresh()
                pop()
            },
            onCancel = pop,
        )
    }
}

/** A settings screen: the Material 3 top app bar with back and the title, then the list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(title: String, tag: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    val colors = BipeTheme.colors
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag(tag),
        containerColor = colors.surfaceBase,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(PhosphorIcon.CARET_LEFT.drawable), contentDescription = null, tint = colors.glyphPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surfaceBase, titleContentColor = colors.inkPrimary),
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding, content = content)
    }
}

/** Spec settings-root: one row per group of BipeSettings root, with the current state as the subtitle (ADR 0112). */
fun LazyListScope.SettingsRootSection(model: SettingsModel, onOpenGroup: (BipeSettingsGroup) -> Unit) {
    item(key = "settings-root-header") { SectionHeader(stringResource(R.string.bipe_settings_section)) }
    items(BipeSettingsGroup.root, key = { "settings-root-${it.name}" }) { group ->
        val state by model.state.collectAsState()
        val attention = model.needsAttention(state, group)
        SettingsRowItem(
            title = stringResource(group.title),
            value = model.summary(state, group),
            valueColor = if (attention) BipeTheme.colors.trustCautionInk else null,
            showsWarning = attention,
            showsChevron = true,
            onClick = { onOpenGroup(group) },
        )
    }
}

/** Spec settings-group: renders any group of registries/settings.json. Every row says its current value (ADR 0112). */
@Composable
fun SettingsGroupScreen(
    model: SettingsModel,
    group: BipeSettingsGroup,
    onBack: () -> Unit,
    onOpen: (SettingsRoute) -> Unit,
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val state by model.state.collectAsState()
    val prefs = remember { context.getSharedPreferences(SettingsKeys.FILE, Context.MODE_PRIVATE) }
    var hideRequestPhotos by remember { mutableStateOf(prefs.getBoolean(SettingsKeys.HIDE_REQUEST_PHOTOS, true)) }
    var sharePresence by remember { mutableStateOf(prefs.getBoolean(SettingsKeys.SHARE_PRESENCE, true)) }
    var showsSignOut by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lock = remember { AppLockModel.get(context) }
    val lockOn by lock.isOn.collectAsState()
    var biometricUnlockOn by remember(lockOn) { mutableStateOf(lock.store.biometricUnlock) }
    val hasBiometrics = remember { OwnerCheck.hasBiometrics(context) }
    val export = remember { DataExportState() }
    val sections = remember(group, lockOn) {
        group.sections.mapNotNull { section ->
            val rows = section.rows.filter { it.isShownOnAndroid() && isAvailable(it, lockOn, hasBiometrics) }
            if (rows.isEmpty()) null else section to rows
        }
    }
    val word = biometricWord(context)
    val lockOffReason = stringResource(R.string.bipe_account_app_lock_off_reason)
    val biometricReason = stringResource(R.string.bipe_app_lock_biometric, word)
    val cancel = stringResource(R.string.bipe_app_cancel)
    val copied = stringResource(R.string.bipe_help_device_id_copied)

    fun tap(row: BipeSettingsRow) {
        if (row.kind == BipeSettingsRowKind.URL) {
            row.url(appLanguage(context))?.let { openPage(context, it) }
            return
        }
        when (row) {
            BipeSettingsRow.PROFILE_NAME_PHOTO -> onOpen(SettingsRoute.People(PeopleRoute.ProfileEdit))
            BipeSettingsRow.PROFILE_HANDLE -> onOpen(SettingsRoute.People(PeopleRoute.ProfileLink))
            BipeSettingsRow.PROFILE_PUBLIC_PROFILE -> onOpen(SettingsRoute.People(PeopleRoute.PublicProfile))
            BipeSettingsRow.PRIVACY_BLOCKED -> onOpen(SettingsRoute.People(PeopleRoute.BlockedList))
            BipeSettingsRow.PRIVACY_CONTACTS -> onOpen(SettingsRoute.People(PeopleRoute.Contacts))
            BipeSettingsRow.PRIVACY_MESSAGING -> onOpen(SettingsRoute.Messaging)
            BipeSettingsRow.HELP_SUPPORT -> onOpen(SettingsRoute.Support)
            BipeSettingsRow.ACCOUNT_SIGN_OUT -> showsSignOut = true
            BipeSettingsRow.ACCOUNT_CHANGE_PIN -> onOpen(SettingsRoute.PinChange)
            BipeSettingsRow.ACCOUNT_LINKED_DEVICES -> onOpen(SettingsRoute.LinkedDevices)
            BipeSettingsRow.HELP_REPORT_PROBLEM -> onOpen(SettingsRoute.ProblemReport)
            BipeSettingsRow.HELP_LICENSES -> onOpen(SettingsRoute.Licences)
            BipeSettingsRow.ACCOUNT_RECOVERY_KEY -> onOpen(SettingsRoute.RecoveryKey)
            BipeSettingsRow.CHATS_SHOW_MEDIA -> onOpen(SettingsRoute.ShowMediaChoice)
            BipeSettingsRow.PRIVACY_EXPORT -> scope.launch { startDataExport(context, model, export) }
            // The screens of the other rows come with their own specs (see settings-group notes).
            else -> Unit
        }
    }

    SettingsPage(stringResource(group.title), BipeScreen.SETTINGS_GROUP.id, onBack) {
        val warning = state.waysBack?.warning
        if (group == BipeSettingsGroup.ACCOUNT && warning != null) {
            item(key = "ways-back-warning") { StatusCard(stringResource(warning)) }
        }
        sections.forEach { (section, rows) ->
            item(key = "header-${section.key}") { SectionHeader(section.title?.let { stringResource(it) }) }
            items(rows, key = { it.id }) { row ->
                val title = stringResource(row.title)
                val footer = row.footer?.let { stringResource(it) }
                when (row) {
                    BipeSettingsRow.PRIVACY_REQUEST_PHOTOS -> SettingsSwitchItem(title, footer, hideRequestPhotos) {
                        hideRequestPhotos = it
                        prefs.edit().putBoolean(SettingsKeys.HIDE_REQUEST_PHOTOS, it).apply()
                    }
                    BipeSettingsRow.PRIVACY_PRESENCE -> SettingsSwitchItem(title, footer, sharePresence) {
                        sharePresence = it
                        prefs.edit().putBoolean(SettingsKeys.SHARE_PRESENCE, it).apply()
                    }
                    BipeSettingsRow.ACCOUNT_APP_LOCK -> SettingsSwitchItem(title, footer, lockOn) { isOn ->
                        if (isOn) {
                            onOpen(SettingsRoute.AppLockSetup)
                        } else if (OwnerCheck.canCheckOwner(context)) {
                            // Spec app-lock, action turnOff: the owner check, then the PIN and biometric unlock go.
                            scope.launch {
                                if (OwnerCheck.owner(context, lockOffReason)) {
                                    lock.store.remove()
                                    lock.refresh()
                                }
                            }
                        } else {
                            onOpen(SettingsRoute.AppLockOff)
                        }
                    }
                    BipeSettingsRow.ACCOUNT_BIOMETRIC_UNLOCK -> SettingsSwitchItem(
                        stringResource(R.string.bipe_account_biometric_unlock, word),
                        footer,
                        biometricUnlockOn,
                    ) { isOn ->
                        scope.launch {
                            // Turning it on asks for biometrics once.
                            if (!isOn || OwnerCheck.biometrics(context, biometricReason, cancel)) {
                                lock.store.setBiometricUnlock(isOn)
                                biometricUnlockOn = isOn
                            }
                        }
                    }
                    // Saved on the server; wired by the screen that owns it.
                    else -> if (row.kind == BipeSettingsRowKind.SWITCH) {
                        SettingsSwitchItem(title, footer, isOn = false, enabled = false) {}
                    } else {
                        SettingsRowItem(
                            title = title,
                            value = rowValue(context, state, row),
                            footer = footer,
                            titleColor = if (row.kind == BipeSettingsRowKind.DESTRUCTIVE) BipeTheme.colors.trustDangerInk else null,
                            showsChevron = row.kind == BipeSettingsRowKind.LINK || row.kind == BipeSettingsRowKind.CHOICE,
                            onClick = if (row.kind == BipeSettingsRowKind.INFO) null else ({ tap(row) }),
                        )
                    }
                }
            }
            item(key = "footer-${section.key}") {
                SectionFooter(section, group) {
                    // Action longPressVersion: copies the Matrix device ID, which never shows as text (ADR 0112).
                    val deviceId = runCatching { model.session.client.deviceId() }.getOrNull() ?: return@SectionFooter
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText(null, deviceId))
                    view.bipeHaptic(BipeHaptic.Tap)
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    DataExportDialogs(model, export)
    if (showsSignOut) {
        SignOutSheet(model, onDismiss = { showsSignOut = false }, onSignedOut = onSignedOut)
    }
}

/** Rows that exist only in some states. */
private fun isAvailable(row: BipeSettingsRow, lockOn: Boolean, hasBiometrics: Boolean): Boolean = when (row) {
    // Exists only while the app lock is on and the phone has biometrics (spec settings-group notes).
    BipeSettingsRow.ACCOUNT_BIOMETRIC_UNLOCK -> lockOn && hasBiometrics
    else -> true
}

/** The current value of a row, formatted (spec settings-group, SettingsRow.value). */
private fun rowValue(context: Context, state: SettingsModel.State, row: BipeSettingsRow): String? = when (row) {
    BipeSettingsRow.PRIVACY_MESSAGING -> state.whoCanMessage?.let { context.getString(it.settingsSummary) }
    BipeSettingsRow.PRIVACY_READ_RECEIPTS -> context.getString(R.string.bipe_privacy_read_receipts_value)
    BipeSettingsRow.ACCOUNT_WAYS_BACK_STATUS -> state.waysBack?.summary(context)
    BipeSettingsRow.ACCOUNT_EMAIL -> state.account?.let { it.email ?: context.getString(R.string.bipe_account_email_none) }
    BipeSettingsRow.ACCOUNT_RECOVERY_KEY -> context.getString(
        if (state.hasRecoveryKey) R.string.bipe_account_recovery_key_saved else R.string.bipe_account_recovery_key_missing,
    )
    BipeSettingsRow.ACCOUNT_THIS_DEVICE -> deviceModel()
    BipeSettingsRow.CHATS_SHOW_MEDIA -> {
        val id = context.getSharedPreferences(SettingsKeys.FILE, Context.MODE_PRIVATE).getString(SettingsKeys.SHOW_MEDIA, null)
        context.getString((ShowMedia.entries.firstOrNull { it.id == id } ?: ShowMedia.ALWAYS).title)
    }
    else -> null
}

private fun deviceModel(): String =
    if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "${Build.MANUFACTURER} ${Build.MODEL}"

/** Keys of the settings that live on this phone. */
object SettingsKeys {
    const val FILE = "bipe_settings"
    const val HIDE_REQUEST_PHOTOS = "hide_request_photos"
    const val SHARE_PRESENCE = "share_presence"
    const val SHOW_MEDIA = "show_media"
}

/** The chats.showMedia choice: when chats load photos and videos by themselves. */
enum class ShowMedia(val id: String, val title: Int) {
    ALWAYS("always", R.string.bipe_chats_show_media_always),
    PRIVATE("private", R.string.bipe_chats_show_media_private),
    NEVER("never", R.string.bipe_chats_show_media_never),
}

/** A picker of one choice that lives on this phone. */
@Composable
fun ChoiceListScreen(title: String, options: List<Pair<String, String>>, key: String, default: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val prefs = remember { context.getSharedPreferences(SettingsKeys.FILE, Context.MODE_PRIVATE) }
    var selection by remember { mutableStateOf(prefs.getString(key, null) ?: default) }
    SettingsPage(title, BipeScreen.SETTINGS_GROUP.id, onBack) {
        items(options, key = { it.first }) { (id, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    SettingsRowItem(title = label, onClick = {
                        selection = id
                        prefs.edit().putString(key, id).apply()
                        view.bipeHaptic(BipeHaptic.Tick)
                    })
                }
                if (selection == id) {
                    Icon(
                        painterResource(PhosphorIcon.CHECK.drawable),
                        contentDescription = null,
                        tint = BipeTheme.colors.accentInk,
                        modifier = Modifier.padding(end = 16.dp).size(18.dp),
                    )
                }
            }
        }
    }
}

/** "pt" or "en", for the url rows of registries/settings.json. */
fun appLanguage(context: Context): String =
    if (context.resources.configuration.locales[0].language == "en") "en" else "pt"

/** Opens a page in the in-app browser (Custom Tabs), or the browser when none is there. */
fun openPage(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (error: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun SectionHeader(title: String?) {
    if (title == null) {
        Spacer(Modifier.size(16.dp))
        return
    }
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = BipeTheme.colors.accentInk,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SectionFooter(section: BipeSettingsSection, group: BipeSettingsGroup, onLongPressVersion: () -> Unit) {
    val colors = BipeTheme.colors
    val modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val text = when {
            section.key == "pin" -> stringResource(R.string.bipe_account_pin_footer, BipeRegistries.PIN_LENGTH.toString())
            else -> section.footer?.let { stringResource(it) }
        }
        if (text != null) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        }
        if (group == BipeSettingsGroup.HELP) {
            Text(
                stringResource(R.string.bipe_help_version, appVersion(LocalContext.current)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPressVersion),
            )
        }
    }
}

/** The version name and build, for bipe_help_version. */
private fun appVersion(context: Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val build = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    return "${info.versionName} ($build)"
}

@Composable
private fun StatusCard(text: String) {
    val colors = BipeTheme.colors
    Surface(
        color = colors.trustCautionWash,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, color = colors.trustCautionInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

/** A Material 3 list item: the title, the current value and a footer under it, a chevron when it opens a screen. */
@Composable
fun SettingsRowItem(
    title: String,
    value: String? = null,
    footer: String? = null,
    titleColor: Color? = null,
    valueColor: Color? = null,
    showsWarning: Boolean = false,
    showsChevron: Boolean = false,
    onClick: (() -> Unit)?,
) {
    val colors = BipeTheme.colors
    val supporting: (@Composable () -> Unit)? = if (value == null && footer == null) null else {
        {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (value != null) Text(value, color = valueColor ?: colors.inkSecondary)
                if (footer != null) Text(footer, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            }
        }
    }
    val chevron: (@Composable () -> Unit)? = if (!showsChevron) null else {
        { Icon(painterResource(PhosphorIcon.CARET_RIGHT.drawable), contentDescription = null, tint = colors.glyphSecondary, modifier = Modifier.size(16.dp)) }
    }
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = titleColor ?: colors.inkPrimary)
                if (showsWarning) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        painterResource(PhosphorIcon.WARNING_CIRCLE.drawable),
                        contentDescription = null,
                        tint = colors.trustCautionInk,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        supportingContent = supporting,
        trailingContent = chevron,
        colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

/** A Material 3 list item with a switch; a tap anywhere on the row toggles it. */
@Composable
fun SettingsSwitchItem(title: String, footer: String?, isOn: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = BipeTheme.colors
    ListItem(
        headlineContent = { Text(title, color = if (enabled) colors.inkPrimary else colors.inkDisabled) },
        supportingContent = footer?.let { text -> @Composable { Text(text, color = colors.inkSecondary) } },
        trailingContent = {
            Switch(
                checked = isOn,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.accentFill, checkedThumbColor = colors.inkOnAccent),
            )
        },
        colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
        modifier = Modifier.toggleable(value = isOn, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

/** The who-can-message picker of the privacy group (PUT /v1/me/messaging). */
@Composable
private fun MessagingPickerScreen(model: SettingsModel, onBack: () -> Unit) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    SettingsPage(stringResource(R.string.bipe_messaging_title), BipeScreen.SETTINGS_GROUP.id, onBack) {
        items(BipeWhoCanMessage.entries, key = { it.name }) { option ->
            val (title, body) = when (option) {
                BipeWhoCanMessage.EVERYONE -> R.string.bipe_messaging_everyone to R.string.bipe_messaging_everyone_body
                BipeWhoCanMessage.VERIFIED -> R.string.bipe_messaging_verified to R.string.bipe_messaging_verified_body
                BipeWhoCanMessage.CONTACTS -> R.string.bipe_messaging_contacts to R.string.bipe_messaging_contacts_body
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    SettingsRowItem(
                        title = stringResource(title),
                        footer = stringResource(body),
                        onClick = if (saving) null else ({
                            saving = true
                            scope.launch {
                                failed = !model.setWhoCanMessage(option)
                                if (!failed) view.bipeHaptic(BipeHaptic.Tick)
                                saving = false
                            }
                        }),
                    )
                }
                if (state.whoCanMessage == option) {
                    Icon(
                        painterResource(PhosphorIcon.CHECK.drawable),
                        contentDescription = null,
                        tint = BipeTheme.colors.accentInk,
                        modifier = Modifier.padding(end = 16.dp).size(18.dp),
                    )
                }
            }
        }
        if (failed) {
            item(key = "error") {
                Text(
                    stringResource(R.string.bipe_error_generic),
                    color = BipeTheme.colors.trustDangerInk,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
