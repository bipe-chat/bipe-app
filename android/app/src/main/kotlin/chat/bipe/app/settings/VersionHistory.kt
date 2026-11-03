// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.generated.BipeSettingsUrl
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** One change of the changelog. Headline and detail are English and not translated. */
data class ChangelogUpdate(val id: String, val kind: String, val headline: String, val detail: String)

/** One build with its Android changes. */
data class ChangelogBuild(val build: Int, val date: String, val updates: List<ChangelogUpdate>)

/** The builds of one version, newest first. */
data class ChangelogVersion(val version: String?, val released: Boolean, val releaseDate: String?, val builds: List<ChangelogBuild>)

/**
 * tools/changelog.mjs --json, grouped by version, with only the Android changes. Builds with no Android change drop
 * out. Fails closed: a build without a number or a date is left out.
 */
internal fun parseChangelog(body: String): List<ChangelogVersion> {
    val out = mutableListOf<ChangelogVersion>()
    for (element in Json.parseToJsonElement(body).jsonArray) {
        val b = element as? JsonObject ?: continue
        val build = b["build"]?.jsonPrimitive?.intOrNull ?: continue
        val date = b.text("date") ?: continue
        val updates = (b["updates"] as? JsonArray).orEmpty().mapNotNull { element ->
            val u = element as? JsonObject ?: return@mapNotNull null
            val platforms = (u["platforms"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
            if ("android" !in platforms) return@mapNotNull null
            ChangelogUpdate(
                id = u.text("id") ?: return@mapNotNull null,
                kind = u.text("kind").orEmpty(),
                headline = u.text("headline") ?: return@mapNotNull null,
                detail = u.text("detail").orEmpty(),
            )
        }
        if (updates.isEmpty()) continue
        val version = b.text("version")
        val last = out.lastOrNull()
        if (last != null && last.version == version) {
            out[out.lastIndex] = last.copy(builds = last.builds + ChangelogBuild(build, date, updates))
        } else {
            out += ChangelogVersion(
                version = version,
                released = b["released"]?.jsonPrimitive?.booleanOrNull ?: false,
                releaseDate = b.text("releaseDate"),
                builds = listOf(ChangelogBuild(build, date, updates)),
            )
        }
    }
    return out
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private suspend fun fetchChangelog(url: String): List<ChangelogVersion> = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection() as HttpURLConnection
    val body = try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        if (connection.responseCode != 200) error("changelog.json: HTTP ${connection.responseCode}")
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
    parseChangelog(body)
}

/** "2026-11-03" as a day in the app language, for example "3 de novembro de 2026". */
private fun day(text: String, language: String): String? = runCatching {
    val locale = if (language == "en") Locale.UK else Locale.forLanguageTag("pt-BR")
    LocalDate.parse(text).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
}.getOrNull()

/**
 * Spec version-history: what changed in each version and build, newest first. The list is the changelog JSON that
 * bipe-web publishes at the versionsData url (registries/settings.json).
 */
@Composable
fun VersionHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val language = appLanguage(context)
    val colors = BipeTheme.colors
    var versions by remember { mutableStateOf<List<ChangelogVersion>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        runCatching { fetchChangelog(BipeSettingsUrl.VERSIONS_DATA.url(language)) }
            .onSuccess { versions = it }
            .onFailure { failed = true }
    }
    SettingsPage(stringResource(R.string.bipe_help_versions), BipeScreen.VERSION_HISTORY.id, onBack) {
        val loaded = versions
        when {
            failed -> item(key = "failed") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.bipe_versions_error), color = colors.inkSecondary)
                    // Action retry.
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.bipe_app_retry)) }
                }
            }
            loaded == null -> item(key = "loading") {
                Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                if (language == "pt") {
                    item(key = "note") {
                        Text(
                            stringResource(R.string.bipe_versions_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        )
                    }
                }
                for (version in loaded) {
                    val key = version.version ?: "build"
                    item(key = "header-$key") {
                        Column {
                            SectionHeader(version.version?.let { stringResource(R.string.bipe_help_version, it) } ?: "")
                            val released = version.releaseDate?.let { day(it, language) }
                            Text(
                                if (version.released && released != null) {
                                    stringResource(R.string.bipe_versions_released, released)
                                } else {
                                    stringResource(R.string.bipe_versions_testing)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkSecondary,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    for (build in version.builds) {
                        items(build.updates, key = { "update-${it.id}" }) { update -> UpdateRow(update, build, language) }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(update: ChangelogUpdate, build: ChangelogBuild, language: String) {
    val colors = BipeTheme.colors
    val kind = when (update.kind) {
        "new" -> stringResource(R.string.bipe_versions_kind_new)
        "improved" -> stringResource(R.string.bipe_versions_kind_improved)
        "fixed" -> stringResource(R.string.bipe_versions_kind_fixed)
        "removed" -> stringResource(R.string.bipe_versions_kind_removed)
        else -> null
    }
    val tags = listOfNotNull(stringResource(R.string.bipe_versions_build, build.build.toString()), kind, day(build.date, language))
        .joinToString(" · ")
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(update.headline, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary)
        if (update.detail.isNotEmpty()) {
            Text(update.detail, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        }
        Text(tags, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
    }
}
