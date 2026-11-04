// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import chat.bipe.app.BipeApplication
import chat.bipe.app.BuildConfig
import chat.bipe.app.chatlist.PromptAction
import chat.bipe.app.chatlist.PromptCard
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Spec app-update (ADR 0233): the tester APK finds a newer build in bipe.chat latest.json (bipe-infra apk-pull), and
 * on a tap downloads it, checks its SHA-256 and installs it over itself. Only the tester APK of testflight.yml has
 * BIPE_SELF_UPDATE and REQUEST_INSTALL_PACKAGES; the Play and foss builds never offer an update.
 */
class AppUpdate(private val context: Context, private val scope: CoroutineScope) {
    data class Offer(val versionName: String, val build: Long, val url: String, val sha256: String)

    enum class Phase { IDLE, DOWNLOADING, INSTALLING, NEEDS_PERMISSION, FAILED }

    data class State(val offer: Offer? = null, val phase: Phase = Phase.IDLE)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var lastCheck = 0L

    /** Action check: when the chat list appears and on foreground, at most once an hour. A failure keeps the last offer. */
    fun check() {
        if (!BuildConfig.BIPE_SELF_UPDATE) return
        // Back from the system screen that allows installs from Bipe.
        if (_state.value.phase == Phase.NEEDS_PERMISSION && context.packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(phase = Phase.IDLE) }
        }
        val now = SystemClock.elapsedRealtime()
        if (lastCheck != 0L && now - lastCheck < CHECK_EVERY_MS) return
        lastCheck = now
        scope.launch {
            val offer = runCatching { fetchOffer() }.getOrElse { return@launch }
            _state.update { current ->
                when {
                    current.phase == Phase.DOWNLOADING || current.phase == Phase.INSTALLING -> current
                    offer == current.offer -> current
                    else -> State(offer)
                }
            }
        }
    }

    /** Action install. */
    fun install() {
        val current = _state.value
        val offer = current.offer ?: return
        if (current.phase == Phase.DOWNLOADING || current.phase == Phase.INSTALLING) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = State(offer, Phase.NEEDS_PERMISSION)
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            context.startActivity(settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        _state.value = State(offer, Phase.DOWNLOADING)
        scope.launch {
            runCatching {
                val apk = download(offer)
                try {
                    _state.value = State(offer, Phase.INSTALLING)
                    commit(apk)
                } finally {
                    apk.delete()
                }
            }.onFailure { failed() }
        }
    }

    /** The system installer refused the APK, or the download failed. */
    fun failed() = _state.update { it.copy(phase = Phase.FAILED) }

    /** The person cancelled the system confirm: the card offers the update again. */
    fun cancelled() = _state.update { it.copy(phase = Phase.IDLE) }

    /** latest.json, fail closed: another app ID, a URL off bipe.chat or a bad hash is no offer. */
    private suspend fun fetchOffer(): Offer? = withContext(Dispatchers.IO) {
        val connection = URL(LATEST_URL).openConnection() as HttpURLConnection
        val body = try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != 200) error("latest.json: HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val json = Json.parseToJsonElement(body).jsonObject
        if (json.text("app_id") != context.packageName) return@withContext null
        val build = json["build"]?.jsonPrimitive?.longOrNull ?: return@withContext null
        if (build <= installedBuild()) return@withContext null
        val abi = if ("arm64-v8a" in Build.SUPPORTED_ABIS) "arm64" else "universal"
        val file = json[abi] as? JsonObject ?: return@withContext null
        val url = file.text("url") ?: return@withContext null
        val sha256 = file.text("sha256") ?: return@withContext null
        if (!url.startsWith(APK_PREFIX) || !SHA256.matches(sha256)) return@withContext null
        Offer(json.text("version").orEmpty(), build, url, sha256)
    }

    /** The APK into the app cache; deleted at once when its SHA-256 differs from latest.json. */
    private suspend fun download(offer: Offer): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(folder, "bipe-${offer.build}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URL(offer.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            // A redirect off bipe.chat is refused: HttpURLConnection follows redirects on the same protocol only.
            if (connection.responseCode != 200 || !connection.url.toString().startsWith(APK_PREFIX)) {
                error("apk: HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash != offer.sha256) {
            file.delete()
            error("apk: SHA-256 mismatch")
        }
        file
    }

    /** One installer session. The status comes back to [AppUpdateReceiver]. */
    private suspend fun commit(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Android 12 and later: no confirm once Bipe is the installer of record (after the first update).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("bipe.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val status = Intent(context, AppUpdateReceiver::class.java).setPackage(context.packageName)
            // Mutable: the installer adds the status extras.
            val pending = PendingIntent.getBroadcast(context, id, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    private fun installedBuild(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val LATEST_URL = "https://bipe.chat/baixar/android/latest.json"
        const val APK_PREFIX = "https://bipe.chat/baixar/android/"
        const val CHECK_EVERY_MS = 60 * 60 * 1000L
        const val TIMEOUT_MS = 30_000
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

/** The installer session status (spec app-update install). Declared only in the tester APK manifest (src/selfUpdate). */
class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val update = (context.applicationContext as BipeApplication).appUpdate
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) update.failed() else context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // The system replaces the process with the new build.
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> update.cancelled()
            else -> update.failed()
        }
    }
}

/** The appUpdate entry of chat-list-prompt holds: an offer exists. */
@Composable
fun AppUpdate.collectShows(): Boolean {
    val current by state.collectAsState()
    return current.offer != null
}

/** The appUpdate card of chat-list-prompt. No Agora não: it holds the slot until the new build is installed. */
@Composable
fun AppUpdateCard(model: AppUpdate) {
    val current by model.state.collectAsState()
    val offer = current.offer ?: return
    val busy = current.phase == AppUpdate.Phase.DOWNLOADING || current.phase == AppUpdate.Phase.INSTALLING
    val body = when (current.phase) {
        AppUpdate.Phase.IDLE -> stringResource(R.string.bipe_app_update_body, "${offer.versionName} (${offer.build})")
        AppUpdate.Phase.DOWNLOADING, AppUpdate.Phase.INSTALLING -> stringResource(R.string.bipe_app_update_downloading)
        AppUpdate.Phase.NEEDS_PERMISSION -> stringResource(R.string.bipe_app_update_permission)
        AppUpdate.Phase.FAILED -> stringResource(R.string.bipe_app_update_failed)
    }
    PromptCard(
        title = stringResource(R.string.bipe_app_update_title),
        body = body,
        primary = if (busy) null else PromptAction(stringResource(R.string.bipe_remote_banner_update), model::install),
        texts = emptyList(),
        modifier = Modifier.testTag(BipeScreen.APP_UPDATE.id),
    )
}
