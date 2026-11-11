// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.ui.graphics.Color
import chat.bipe.desktop.chat.ChatLabels
import chat.bipe.desktop.settings.AppearancePolicy
import java.util.Locale
import kotlinx.serialization.json.*

/** Canonical translations and resolved tokens, generated with bipe-shared's own colour solver. */
class DesktopCatalog private constructor(private val root: JsonObject, val build: JsonObject, config: JsonObject) {
    val locale: String = if (Locale.getDefault().language == "en") "en" else "pt-BR"
    private val strings = root.getValue("strings").jsonObject.getValue(locale).jsonObject
    val palettes = root.getValue("palettes").jsonArray.map { it.jsonPrimitive.content }
    val defaultPalette = root.getValue("defaultPalette").jsonPrimitive.content
    val backgroundGeometry = root.getValue("backgrounds").jsonObject
    val markGeometry = root.getValue("mark").jsonObject
    val fruitGeometry = root.getValue("fruit").jsonObject
    val settingsRegistry = root.getValue("settings").jsonObject
    val appearanceOptions = root.getValue("appearance").jsonArray.map { option ->
        option.jsonObject.let { it.getValue("id").jsonPrimitive.content to it.getValue("title").jsonPrimitive.content }
    }
    val backgroundOptions = backgroundGeometry.getValue("options").jsonArray.map { it.jsonPrimitive.content }
    val appearancePolicy = AppearancePolicy(palettes, defaultPalette, backgroundOptions, backgroundGeometry.getValue("default").jsonPrimitive.content)
    val settingsEventType = root.getValue("syncedSettings").jsonObject.getValue("eventType").jsonPrimitive.content
    val windowsClientId = config["windowsOAuthClientId"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
    val moneyPauseKinds = root.getValue("safety").jsonObject.getValue("moneyPauseKinds").jsonArray.map { it.jsonPrimitive.content }.toSet()
    val moneyPauseSeconds = root.getValue("safety").jsonObject.getValue("moneyPauseSeconds").jsonPrimitive.int
    fun warningKey(id: String): String = root.getValue("safety").jsonObject.getValue("warnings").jsonArray
        .first { it.jsonObject.getValue("id").jsonPrimitive.content == id }.jsonObject.getValue("messageKey").jsonPrimitive.content
    val versionLabel get() = "${build.getValue("version").jsonPrimitive.content} (${build.getValue("build").jsonPrimitive.content}) · ${build.getValue("commit").jsonPrimitive.content}"
    val shortVersion get() = "${build.getValue("version").jsonPrimitive.content} (${build.getValue("build").jsonPrimitive.content})"
    fun settingsUrl(key: String) = settingsRegistry.getValue("urls").jsonObject.getValue(key).jsonObject
        .getValue(if (locale == "en") "en" else "pt").jsonPrimitive.content
    fun settingGroup(key: String) = settingsRegistry.getValue("groups").jsonArray.first { it.jsonObject.getValue("key").jsonPrimitive.content == key }.jsonObject
    val settingsGroups get() = settingsRegistry.getValue("root").jsonArray.map { settingGroup(it.jsonPrimitive.content) }

    fun text(key: String, vararg args: String): String {
        var value = strings.getValue(key).jsonPrimitive.content
        args.forEachIndexed { index, argument -> value = value.replace("%${index + 1}\$s", argument) }
        return value
    }

    fun color(role: String, palette: String, dark: Boolean, highContrast: Boolean): Color {
        val variant = (if (dark) "dark" else "light") + (if (highContrast) "HighContrast" else "")
        val hex = root.getValue("themes").jsonObject.getValue(palette.takeIf { it in palettes } ?: defaultPalette)
            .jsonObject.getValue(role).jsonObject.getValue(variant).jsonPrimitive.content.removePrefix("#")
        return Color(0xff000000L or hex.toLong(16))
    }

    fun chatLabels() = ChatLabels(text("bipe_dm_no_name"), text("bipe_person_no_name"),
        text("bipe_web_timeline_undecryptable"), text("bipe_app_timeline_unsupported"))

    companion object {
        internal fun json(name: String): JsonObject = DesktopCatalog::class.java.getResourceAsStream("/$name")!!.use {
            Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject
        }
        fun load(): DesktopCatalog = DesktopCatalog(json("bipe-desktop.json"), json("bipe-build.json"), json("bipe-config.json"))
    }
}
