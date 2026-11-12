// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.useResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.chat.LoadPhase
import chat.bipe.desktop.settings.*
import java.awt.Desktop
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

private sealed interface SettingsPage {
    data object Root : SettingsPage
    data class Group(val key: String) : SettingsPage
    data object Colors : SettingsPage
    data object Appearance : SettingsPage
    data object Accessibility : SettingsPage
    data object Devices : SettingsPage
    data object Licences : SettingsPage
    data class Licence(val index: Int) : SettingsPage
}
private enum class LinkPhase { IDLE, FAILED }

/** Native settings pages consume the same group/section/row order and icon tiles as the other clients. */
@Composable internal fun YouSettings(model: DesktopAppModel, account: AppPhase.Ready, colors: DesktopColors) {
    val s = model.catalog
    val profile by account.profile.collectAsState()
    val appearance by account.appearance.phase.collectAsState()
    val local by model.deviceAppearance.phase.collectAsState()
    val values = (appearance as? AppearancePhase.Ready)?.values ?: AppearanceValues(emptyMap())
    var page by remember(account) { mutableStateOf<SettingsPage>(SettingsPage.Root) }
    var linkPhase by remember(account) { mutableStateOf(LinkPhase.IDLE) }
    val openUrl: (String) -> Unit = { url ->
        linkPhase = if (runCatching { Desktop.getDesktop().browse(URI(url)) }.isSuccess) LinkPhase.IDLE else LinkPhase.FAILED
    }
    val back = {
        page = when (page) {
            SettingsPage.Colors, SettingsPage.Appearance, SettingsPage.Accessibility -> SettingsPage.Group("chats")
            SettingsPage.Devices -> SettingsPage.Group("account")
            SettingsPage.Licences -> SettingsPage.Group("help")
            is SettingsPage.Licence -> SettingsPage.Licences
            else -> SettingsPage.Root
        }
    }
    val title = when (val current = page) {
        SettingsPage.Root -> s.text("bipe_settings_section")
        is SettingsPage.Group -> s.text(s.settingGroup(current.key).getValue("title").jsonPrimitive.content)
        SettingsPage.Colors -> s.text("bipe_chats_colors")
        SettingsPage.Appearance -> s.text("bipe_chats_theme")
        SettingsPage.Accessibility -> s.text("bipe_chats_accessibility")
        SettingsPage.Devices -> s.text("bipe_linked_devices_title")
        SettingsPage.Licences -> s.text("bipe_help_licenses")
        is SettingsPage.Licence -> s.text("bipe_help_licenses")
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page != SettingsPage.Root) TextButton(onClick = back) { Text(s.text("bipe_a11y_back")) }
                Text(title, style = MaterialTheme.typography.headlineSmall)
            }
            if (linkPhase == LinkPhase.FAILED) Text(s.text("bipe_error_generic"), style = MaterialTheme.typography.bodySmall)
            if (page == SettingsPage.Colors || page == SettingsPage.Group("chats")) AppearanceStatus(appearance, account.appearance::retry, s)
            when (val current = page) {
                SettingsPage.Root -> LazyColumn {
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            AvatarPhoto(profile.name, profile.avatar, account.runtime.media, account.runtime.identity, colors, 72.dp,
                                userId = profile.userId, isCompany = profile.kind == AccountKind.COMPANY)
                            Text(profile.name, style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                    items(s.settingsGroups, key = { it.getValue("key").jsonPrimitive.content }) { group ->
                        val key = group.getValue("key").jsonPrimitive.content
                        val subtitle = when (key) {
                            "chats" -> s.text(s.appearanceOptions.first { it.first == local.mode }.second) + " · " +
                                s.text("bipe_colors_${values.palette(s.appearancePolicy)}")
                            "help" -> s.text("bipe_help_version", s.shortVersion)
                            else -> null
                        }
                        PreferenceRow(s.text(group.getValue("title").jsonPrimitive.content), subtitle, onClick = { page = SettingsPage.Group(key) },
                            leading = {
                                Box(Modifier.size(29.dp).background(colors["accent.fill"], RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                                    Icon(sharedIcon(group.getValue("icon").jsonPrimitive.content), contentDescription = null,
                                        tint = colors["ink.onAccent"], modifier = Modifier.size(18.dp))
                                }
                            })
                    }
                    item { Text(s.text("bipe_help_version", s.shortVersion), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
                }
                is SettingsPage.Group -> LazyColumn {
                    if (current.key == "notifications") item {
                        PreferenceRow(s.text("bipe_accessibility_open_system"), onClick = { openUrl("ms-settings:notifications") })
                    }
                    val group = s.settingGroup(current.key)
                    for (sectionValue in group.getValue("sections").jsonArray) {
                        val section = sectionValue.jsonObject
                        val rows = section.getValue("rows").jsonArray.map { it.jsonObject }.filter { row ->
                            val key = row.getValue("key").jsonPrimitive.content
                            row["gate"] == null && (row.getValue("kind").jsonPrimitive.content == "url" ||
                                (current.key == "chats" && key in setOf("appearance", "colors", "accessibility")) ||
                                (current.key == "account" && key == "linkedDevices") || (current.key == "help" && key in setOf("licenses", "versions")))
                        }
                        if (rows.isEmpty()) continue
                        section["title"]?.jsonPrimitive?.content?.let { key -> item { SectionTitle(s.text(key)) } }
                        items(rows, key = { current.key + ":" + it.getValue("key").jsonPrimitive.content }) { row ->
                            val key = row.getValue("key").jsonPrimitive.content
                            val subtitle = when (key) {
                                "appearance" -> s.text(s.appearanceOptions.first { it.first == local.mode }.second)
                                "colors" -> s.text("bipe_colors_${values.palette(s.appearancePolicy)}")
                                else -> null
                            }
                            PreferenceRow(s.text(row.getValue("title").jsonPrimitive.content), subtitle, onClick = {
                                when {
                                    row["url"] != null -> openUrl(s.settingsUrl(row.getValue("url").jsonPrimitive.content))
                                    key == "appearance" -> page = SettingsPage.Appearance
                                    key == "colors" -> page = SettingsPage.Colors
                                    key == "accessibility" -> page = SettingsPage.Accessibility
                                    key == "linkedDevices" -> page = SettingsPage.Devices
                                    key == "licenses" -> page = SettingsPage.Licences
                                    key == "versions" -> openUrl(s.settingsUrl("versions"))
                                }
                            })
                        }
                        section["footer"]?.jsonPrimitive?.content?.let { key -> item { Footer(s.text(key)) } }
                    }
                    if (current.key == "account" || current.key == "privacy") item { Footer(s.text("bipe_mac_account_change_on_phone")) }
                    if (current.key == "account") item {
                        TextButton(onClick = { model.link() }) { Text(s.text("bipe_linked_devices_link_computer")) }
                    }
                    if (current.key == "help") item { Footer(s.text("bipe_help_version", s.shortVersion)) }
                }
                SettingsPage.Appearance -> LazyColumn {
                    items(s.appearanceOptions, key = { it.first }) { (id, key) ->
                        ChoiceRow(s.text(key), local.mode == id, local !is DeviceAppearancePhase.Saving, { model.deviceAppearance.pick(id) }, colors)
                    }
                    if (local is DeviceAppearancePhase.Failed) item {
                        val failed = local as DeviceAppearancePhase.Failed
                        Footer(s.text("bipe_error_generic"))
                        TextButton(onClick = { model.deviceAppearance.pick(failed.requested) }) { Text(s.text("bipe_app_retry")) }
                    }
                }
                SettingsPage.Colors -> LazyColumn {
                    val enabled = (appearance as? AppearancePhase.Ready)?.sync != AppearanceSync.SAVING && appearance is AppearancePhase.Ready
                    items(s.palettes, key = { it }) { palette ->
                        ChoiceRow(s.text("bipe_colors_$palette"), values.palette(s.appearancePolicy) == palette, enabled,
                            { account.appearance.pickPalette(palette) }, colors, leading = {
                                Image(painterResource("bipe-icon-$palette.png"), contentDescription = null, modifier = Modifier.size(44.dp))
                            })
                    }
                    item { Footer(s.text("bipe_chats_colors_footer")); SectionTitle(s.text("bipe_chats_background")) }
                    items(s.backgroundOptions, key = { "background:$it" }) { background ->
                        ChoiceRow(s.text("bipe_chats_background_$background"), values.background(s.appearancePolicy) == background, enabled,
                            { account.appearance.pickBackground(background) }, colors, leading = {
                                Box(Modifier.size(36.dp, 48.dp).background(colors["surface.base"], RoundedCornerShape(6.dp))
                                    .chatBackdrop(s, colors, background).border(1.dp, colors["line.subtle"], RoundedCornerShape(6.dp)))
                            })
                    }
                    item { Footer(s.text("bipe_chats_background_footer")) }
                }
                SettingsPage.Accessibility -> LazyColumn {
                    item {
                        Footer(s.text("bipe_accessibility_intro"))
                        PreferenceRow(s.text("bipe_accessibility_contrast"), s.text(if (colors.highContrast) "bipe_accessibility_on" else "bipe_accessibility_off"))
                        PreferenceRow(s.text("bipe_accessibility_open_system"), onClick = { openUrl("ms-settings:easeofaccess") })
                        PreferenceRow(s.text("bipe_accessibility_page"), onClick = { openUrl(s.settingsUrl("accessibility")) })
                    }
                }
                SettingsPage.Devices -> LinkedComputers(account, s)
                SettingsPage.Licences -> {
                    val components = remember { DesktopCatalog.json("open-source-licences.json").getValue("components").jsonArray }
                    LazyColumn { items(components.size) { index ->
                        val component = components[index].jsonObject
                        PreferenceRow(component.getValue("name").jsonPrimitive.content, component.getValue("licence").jsonPrimitive.content,
                            onClick = { page = SettingsPage.Licence(index) })
                    } }
                }
                is SettingsPage.Licence -> {
                    val document = remember { DesktopCatalog.json("open-source-licences.json") }
                    val component = document.getValue("components").jsonArray[current.index].jsonObject
                    val text = component["text"] ?: document.getValue("texts").jsonObject.getValue(component.getValue("licence").jsonPrimitive.content)
                    LazyColumn { item {
                        SectionTitle(component.getValue("name").jsonPrimitive.content)
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(text.jsonPrimitive.content, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
                    } }
                }
            }
        }
    }
}

@Composable private fun LinkedComputers(account: AppPhase.Ready, s: DesktopCatalog) {
    var revision by remember(account) { mutableIntStateOf(0) }
    val devices by produceState<LoadPhase<List<LinkedComputer>>>(LoadPhase.Loading, account, revision) {
        value = LoadPhase.Loading
        value = try { LoadPhase.Ready(account.runtime.lifetime.async { MatrixSettingsReader(account.runtime.client).devices() }.await()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { LoadPhase.Failed }
    }
    LazyColumn {
        when (val state = devices) {
            LoadPhase.Loading -> item { Footer(s.text("bipe_web_loading")) }
            LoadPhase.Failed -> item {
                Footer(s.text("bipe_linked_devices_error"))
                TextButton(onClick = { revision++ }) { Text(s.text("bipe_app_retry")) }
            }
            is LoadPhase.Ready -> {
                if (state.value.isEmpty()) item { Footer(s.text("bipe_linked_devices_empty")) }
                items(state.value, key = { it.id }) { device ->
                    val lastUsed = device.lastUsed?.let { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                        .format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
                    PreferenceRow(device.name?.takeIf { it.isNotBlank() } ?: s.text("bipe_linked_devices_unknown_name"),
                        lastUsed?.let { s.text("bipe_linked_devices_last_used", it) })
                }
            }
        }
        item { Footer(s.text("bipe_mac_account_change_on_phone")) }
    }
}

@Composable private fun AppearanceStatus(phase: AppearancePhase, retry: () -> Any, s: DesktopCatalog) {
    when (phase) {
        AppearancePhase.Loading -> Footer(s.text("bipe_web_loading"))
        AppearancePhase.Unavailable -> {
            Footer(s.text("bipe_error_generic"))
            TextButton(onClick = { retry() }) { Text(s.text("bipe_app_retry")) }
        }
        is AppearancePhase.Ready -> when (phase.sync) {
            AppearanceSync.IDLE -> Unit
            AppearanceSync.SAVING -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is AppearanceSync.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.text("bipe_error_generic"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { retry() }) { Text(s.text("bipe_app_retry")) }
            }
        }
    }
}

@Composable private fun PreferenceRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null,
                                     leading: (@Composable () -> Unit)? = null) {
    ListItem(headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        leadingContent = leading, trailingContent = onClick?.let { { Icon(sharedIcon("caret-right"), contentDescription = null, modifier = Modifier.size(18.dp)) } },
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)))
}

@Composable private fun ChoiceRow(title: String, selected: Boolean, enabled: Boolean, pick: () -> Unit, colors: DesktopColors,
                                  leading: (@Composable () -> Unit)? = null) {
    ListItem(headlineContent = { Text(title) }, leadingContent = leading,
        trailingContent = if (selected) ({ Icon(sharedIcon("check"), contentDescription = null, tint = colors["accent.ink"], modifier = Modifier.size(20.dp)) }) else null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = { if (!selected) pick() }))
}

@Composable private fun SectionTitle(text: String) = Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
@Composable private fun Footer(text: String) = Text(text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
@Composable private fun sharedIcon(id: String): Painter {
    val density = LocalDensity.current
    return remember(id, density) { useResource("phosphor-$id.svg") { loadSvgPainter(it, density) } }
}
