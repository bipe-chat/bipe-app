// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import chat.bipe.app.account.LegacyData
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.settings.BipeProblemReport
import chat.bipe.shared.settings.BipeReportFile
import chat.bipe.shared.settings.submitProblemReport
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_SCREENSHOTS = 10
private const val MAX_TEXT = 5000

/** Spec problem-report, phase. */
enum class ProblemReportPhase { EDITING, SENDING, SENT, FAILED }

/**
 * Spec problem-report: a report the user starts, with an optional screenshot and, only when the user turns it on, the
 * app logs (ADR 0114). No shake gesture, no automatic report.
 */
@Composable
fun ProblemReportScreen(model: SettingsModel, onBack: () -> Unit, onOpenSupport: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = BipeTheme.colors
    var text by remember { mutableStateOf("") }
    // Off by default, for this report only (ADR 0114).
    var includeLogs by remember { mutableStateOf(false) }
    val screenshots = remember { mutableStateListOf<Bitmap>() }
    var phase by remember { mutableStateOf(ProblemReportPhase.EDITING) }
    var error by remember { mutableStateOf<Int?>(null) }
    // Action addScreenshot: the system photo picker, one image. Never takes a screenshot by itself.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null || screenshots.size >= MAX_SCREENSHOTS) return@rememberLauncherForActivityResult
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
            }
            if (bitmap != null && screenshots.size < MAX_SCREENSHOTS) screenshots.add(bitmap)
        }
    }

    fun send() {
        phase = ProblemReportPhase.SENDING
        scope.launch {
            try {
                val report = withContext(Dispatchers.Default) { buildReport(context, text.trim(), screenshots.toList(), includeLogs) }
                model.authorized { model.api.submitProblemReport(report, it) }
                phase = ProblemReportPhase.SENT
            } catch (failure: Exception) {
                error = when {
                    failure is BipeApiException.Network -> R.string.bipe_error_network
                    failure is BipeApiException.Server && failure.code == BipeApiErrorCode.RATE_LIMITED -> R.string.bipe_error_rate_limited
                    else -> R.string.bipe_app_report_error
                }
                phase = ProblemReportPhase.FAILED
            }
        }
    }

    if (phase == ProblemReportPhase.SENT) {
        Surface(Modifier.fillMaxSize().testTag(BipeScreen.PROBLEM_REPORT.id), color = colors.surfaceBase) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Icon(painterResource(PhosphorIcon.CHECK.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(48.dp))
                Text(stringResource(R.string.bipe_help_report_sent_title), style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary, textAlign = TextAlign.Center)
                Text(stringResource(R.string.bipe_help_report_sent_body), color = colors.inkSecondary, textAlign = TextAlign.Center)
                // Action openSupport.
                Button(onClick = onOpenSupport, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bipe_settings_support)) }
                // Action close.
                TextButton(onClick = onBack) { Text(stringResource(R.string.bipe_app_close)) }
            }
        }
        return
    }

    SettingsPage(stringResource(R.string.bipe_help_report_problem), BipeScreen.PROBLEM_REPORT.id, onBack) {
        item(key = "text") {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_TEXT) },
                placeholder = { Text(stringResource(R.string.bipe_help_problem_description)) },
                minLines = 5,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            Text(
                stringResource(R.string.bipe_help_problem_language),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item(key = "screenshots") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                screenshots.forEachIndexed { index, bitmap ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.weight(1f))
                        // Action removeScreenshot.
                        IconButton(onClick = { screenshots.removeAt(index) }) {
                            Icon(painterResource(PhosphorIcon.X.drawable), contentDescription = stringResource(R.string.bipe_a11y_remove_screenshot), tint = colors.glyphSecondary)
                        }
                    }
                }
                if (screenshots.size < MAX_SCREENSHOTS) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(painterResource(PhosphorIcon.IMAGE.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(22.dp))
                        Text(stringResource(R.string.bipe_app_report_screenshot), color = colors.accentInk)
                    }
                }
            }
        }
        item(key = "logs") {
            // Action toggleLogs.
            SettingsSwitchItem(stringResource(R.string.bipe_app_report_logs), stringResource(R.string.bipe_app_report_logs_footer), includeLogs) {
                includeLogs = it
            }
        }
        item(key = "send") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (phase == ProblemReportPhase.FAILED) {
                    Text(stringResource(error ?: R.string.bipe_app_report_error), color = colors.trustDangerInk)
                }
                // Action send: works when the text is not empty after trimming.
                Button(
                    onClick = ::send,
                    enabled = text.isNotBlank() && phase != ProblemReportPhase.SENDING,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (phase == ProblemReportPhase.SENDING) {
                        CircularProgressIndicator(color = colors.inkOnAccent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.bipe_app_timeline_sending))
                    } else {
                        Text(stringResource(R.string.bipe_app_report_send))
                    }
                }
            }
        }
    }
}

private fun buildReport(context: Context, text: String, screenshots: List<Bitmap>, includeLogs: Boolean): BipeProblemReport {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val build = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    return BipeProblemReport(
        text = text,
        app = context.packageName,
        version = info.versionName.orEmpty(),
        build = build.toString(),
        os = "Android ${Build.VERSION.RELEASE}",
        screenshots = screenshots.mapIndexed { index, bitmap ->
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            BipeReportFile("screenshot-${index + 1}.jpg", "image/jpeg", out.toByteArray())
        },
        // Spec problem-report notes: legacy-data.json (structure only) when the earlier app's data is here.
        logs = if (includeLogs) appLogFiles(context).take(19) + listOfNotNull(runCatching { LegacyData.reportFile(context) }.getOrNull()) else emptyList(),
    )
}

/**
 * The app's own log files (spec problem-report notes): no message text, no PIN, no password, no token. The app writes
 * them under files/logs; this sends the gzip files found there.
 */
private fun appLogFiles(context: Context): List<BipeReportFile> =
    File(context.filesDir, "logs").listFiles { file -> file.extension == "gz" }.orEmpty().take(20).map {
        BipeReportFile(it.name, "application/gzip", it.readBytes())
    }
