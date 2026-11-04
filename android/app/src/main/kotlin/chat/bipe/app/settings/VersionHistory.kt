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
import androidx.compose.ui.semantics.heading
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** One change of the changelog. Headline and detail are English and not translated. */
data class ChangelogUpdate(val id: String, val kind: String, val headline: String, val detail: String)

/** One build with its Android changes. */
data class ChangelogBuild(val build: Int, val version: String?, val updates: List<ChangelogUpdate>)

/** The builds of one day, newest first. */
data class ChangelogDay(val date: String, val builds: List<ChangelogBuild>)

/**
 * tools/changelog.mjs --json, grouped by day, with only the Android changes. Builds with no Android change drop
 * out. Fails closed: a build without a number or a date is left out.
 */
internal fun parseChangelog(body: String): List<ChangelogDay> {
    val days = linkedMapOf<String, MutableList<ChangelogBuild>>()
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
        days.getOrPut(date) { mutableListOf() }.add(ChangelogBuild(build, b.text("version"), updates))
    }
    return days.map { (date, builds) -> ChangelogDay(date, builds) }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private suspend fun fetchChangelog(url: String): List<ChangelogDay> = withContext(Dispatchers.IO) {
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
 * Spec version-history: what changed by day and then by build, newest first. The list is the changelog JSON that
 * bipe-web publishes at the versionsData url (registries/settings.json).
 */
@Composable
fun VersionHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val language = appLanguage(context)
    val colors = BipeTheme.colors
    var days by remember { mutableStateOf<List<ChangelogDay>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        runCatching { fetchChangelog(BipeSettingsUrl.VERSIONS_DATA.url(language)) }
            .onSuccess { days = it }
            .onFailure { failed = true }
    }
    SettingsPage(stringResource(R.string.bipe_help_versions), BipeScreen.VERSION_HISTORY.id, onBack) {
        val loaded = days
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
                for (entry in loaded) {
                    item(key = "day-${entry.date}") { SectionHeader(day(entry.date, language) ?: entry.date) }
                    for (build in entry.builds) {
                        item(key = "build-${build.build}") {
                            Text(
                                // "Versão 1.0.1 (446)", the same text as the Ajuda e sobre row.
                                build.version?.let { stringResource(R.string.bipe_help_version, "$it (${build.build})") }
                                    ?: stringResource(R.string.bipe_versions_build, build.build.toString()),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.inkSecondary,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).semantics { heading() },
                            )
                        }
                        items(build.updates, key = { "update-${it.id}" }) { update -> UpdateRow(update) }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(update: ChangelogUpdate) {
    val colors = BipeTheme.colors
    val kind = when (update.kind) {
        "new" -> stringResource(R.string.bipe_versions_kind_new)
        "improved" -> stringResource(R.string.bipe_versions_kind_improved)
        "fixed" -> stringResource(R.string.bipe_versions_kind_fixed)
        "removed" -> stringResource(R.string.bipe_versions_kind_removed)
        else -> null
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(update.headline, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary)
        if (update.detail.isNotEmpty()) {
            Text(update.detail, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        }
        if (kind != null) Text(kind, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
    }
}
