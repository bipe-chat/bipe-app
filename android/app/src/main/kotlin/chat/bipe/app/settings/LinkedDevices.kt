// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateUtils
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import chat.bipe.app.BipeTheme
import chat.bipe.app.linkcomputer.LinkComputerScreen
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.account.BipeAccountVault
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.shared.weblink.BipeMatrixAccountApi
import chat.bipe.shared.weblink.BipeMatrixDevice
import chat.bipe.shared.weblink.BipeWebLink
import chat.bipe.shared.weblink.finishWebLink
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Spec linked-devices: the other devices of the account, and signing one out on the MAS page (ADR 0116). */
class LinkedDevicesModel(private val context: Context, private val model: SettingsModel, private val scope: CoroutineScope) {
    enum class Phase { LOADING, LOADED, FAILED }

    sealed interface SignOutPhase {
        data object Idle : SignOutPhase
        data object ConfirmIdentity : SignOutPhase
        data object Loading : SignOutPhase
        data class Page(val url: String, val host: String) : SignOutPhase
        data object Failed : SignOutPhase
    }

    data class State(
        val phase: Phase = Phase.LOADING,
        val devices: List<BipeMatrixDevice> = emptyList(),
        val signOutCandidate: BipeMatrixDevice? = null,
        val signOutPhase: SignOutPhase = SignOutPhase.Idle,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var startedAt: Instant? = null
    private var password: String? = null
    private val matrix get() = BipeMatrixAccountApi(model.session.client.homeserver())

    /** The MAS login name: the localpart of the Matrix ID. */
    val username: String get() = BipeWebLink.localpart(model.session.userId)

    /** Action appear: the devices of the account without this phone, newest use first. */
    fun load() {
        scope.launch {
            val token = model.session.accessToken()
            val ownId = runCatching { model.session.client.deviceId() }.getOrNull()
            val devices = token?.let { runCatching { matrix.devices(it) }.getOrNull() }
            _state.update {
                if (devices == null) {
                    it.copy(phase = Phase.FAILED)
                } else {
                    it.copy(phase = Phase.LOADED, devices = devices.filter { d -> d.deviceId != ownId }.sortedByDescending { d -> d.lastSeenTs ?: 0 })
                }
            }
        }
    }

    /** Action retry. */
    fun retry() {
        _state.update { it.copy(phase = Phase.LOADING) }
        load()
    }

    /** Action askSignOut. */
    fun askSignOut(device: BipeMatrixDevice) = _state.update { it.copy(signOutCandidate = device) }

    fun cancelSignOut() = _state.update { it.copy(signOutCandidate = null, signOutPhase = SignOutPhase.Idle) }

    /** Action confirmSignOut: the biometric check, then the MAS page that ends the device's session. */
    fun confirmSignOut(activity: Context) {
        val device = _state.value.signOutCandidate ?: return
        scope.launch {
            _state.update { it.copy(signOutPhase = SignOutPhase.ConfirmIdentity) }
            if (!OwnerCheck.owner(activity, activity.getString(R.string.bipe_linked_devices_sign_out_biometric))) {
                _state.update { it.copy(signOutPhase = SignOutPhase.Idle) }
                return@launch
            }
            _state.update { it.copy(signOutPhase = SignOutPhase.Loading) }
            // The hidden password is read only after the check passes.
            val secret = runCatching { BipeAccountVault(context).read().accounts[model.session.userId]?.password }.getOrNull()
            val metadata = runCatching { matrix.authMetadata() }.getOrNull()
            val host = metadata?.host
            val accountUri = metadata?.accountManagementUri
            val url = accountUri?.let { BipeWebLink.sessionEndUrl(it, device.deviceId) }
            if (secret == null || host == null || url == null || !BipeWebLink.isAllowed(url, host)) {
                _state.update { it.copy(signOutPhase = SignOutPhase.Failed) }
                return@launch
            }
            password = secret
            startedAt = Instant.now()
            _state.update { it.copy(signOutPhase = SignOutPhase.Page(url, host)) }
        }
    }

    /** The login form: the hidden password, once. The second time the sign-in failed. */
    fun takePassword(): String? = password.also { password = null }

    fun pageFailed() {
        password = null
        _state.update { it.copy(signOutPhase = SignOutPhase.Failed) }
    }

    /** Action pageClosed: clears the web view, ends the MAS browser session, reloads the list. */
    fun pageClosed() {
        password = null
        val device = _state.value.signOutCandidate
        _state.update { it.copy(signOutCandidate = null, signOutPhase = SignOutPhase.Idle) }
        clearWebData()
        scope.launch {
            startedAt?.let { since ->
                startedAt = null
                // A failure is not shown.
                runCatching { model.authorized { model.api.finishWebLink(since, it) } }
            }
            val token = model.session.accessToken() ?: return@launch
            val ownId = runCatching { model.session.client.deviceId() }.getOrNull()
            val devices = runCatching { matrix.devices(token) }.getOrNull() ?: return@launch
            _state.update { it.copy(phase = Phase.LOADED, devices = devices.filter { d -> d.deviceId != ownId }.sortedByDescending { d -> d.lastSeenTs ?: 0 }) }
            if (device != null && devices.none { it.deviceId == device.deviceId }) {
                Toast.makeText(context, R.string.bipe_app_linked_devices_signed_out, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        /** The app has no other web view: its cookies and storage go before and after each MAS page. */
        fun clearWebData() {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }
}

@Composable
fun LinkedDevicesScreen(model: SettingsModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices = remember { LinkedDevicesModel(context.applicationContext, model, scope) }
    val state by devices.state.collectAsState()
    val colors = BipeTheme.colors
    LaunchedEffect(devices) { devices.load() }
    var showsLinkComputer by rememberSaveable { mutableStateOf(false) }
    if (showsLinkComputer) {
        // Spec link-computer-scan: the only entry to a web sign-in (ADR 0116, rule 5).
        LinkComputerScreen(model, onClose = {
            showsLinkComputer = false
            devices.load()
        })
        return
    }
    val page = state.signOutPhase
    if (page is LinkedDevicesModel.SignOutPhase.Page || page == LinkedDevicesModel.SignOutPhase.Loading) {
        MasPage(devices, page)
        return
    }
    SettingsPage(stringResource(R.string.bipe_linked_devices_title), BipeScreen.LINKED_DEVICES.id, onBack) {
        item(key = "link-computer") {
            // Action linkComputer: opens link-computer-scan.
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showsLinkComputer = true }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(PhosphorIcon.DESKTOP.drawable), contentDescription = null, tint = colors.glyphPrimary, modifier = Modifier.size(22.dp))
                Text(
                    stringResource(R.string.bipe_linked_devices_link_computer),
                    color = colors.inkPrimary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                stringResource(R.string.bipe_linked_devices_footer),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            HorizontalDivider(color = colors.lineSubtle, modifier = Modifier.padding(top = 12.dp))
        }
        when (state.phase) {
            LinkedDevicesModel.Phase.LOADING -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accentFill)
                }
            }
            LinkedDevicesModel.Phase.FAILED -> item(key = "failed") {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.bipe_linked_devices_error), color = colors.inkSecondary)
                    TextButton(onClick = devices::retry) { Text(stringResource(R.string.bipe_app_retry)) }
                }
            }
            LinkedDevicesModel.Phase.LOADED -> if (state.devices.isEmpty()) {
                item(key = "empty") {
                    Text(stringResource(R.string.bipe_linked_devices_empty), color = colors.inkSecondary, modifier = Modifier.padding(16.dp))
                }
            } else {
                items(state.devices, key = { it.deviceId }) { device ->
                    DeviceRow(device) { devices.askSignOut(device) }
                }
            }
        }
    }
    val candidate = state.signOutCandidate
    if (candidate != null && state.signOutPhase == LinkedDevicesModel.SignOutPhase.Idle) {
        AlertDialog(
            onDismissRequest = devices::cancelSignOut,
            title = { Text(stringResource(R.string.bipe_linked_devices_sign_out_title, deviceName(context, candidate))) },
            text = { Text(stringResource(R.string.bipe_linked_devices_sign_out_body)) },
            confirmButton = {
                TextButton(onClick = { devices.confirmSignOut(context) }) {
                    Text(stringResource(R.string.bipe_linked_devices_sign_out), color = colors.trustDangerInk)
                }
            },
            dismissButton = { TextButton(onClick = devices::cancelSignOut) { Text(stringResource(R.string.bipe_app_cancel)) } },
        )
    }
    if (state.signOutPhase == LinkedDevicesModel.SignOutPhase.Failed) {
        // Closing the error also ends the MAS browser session that the web view may have made.
        AlertDialog(
            onDismissRequest = devices::pageClosed,
            title = { Text(stringResource(R.string.bipe_linked_devices_title)) },
            text = { Text(stringResource(R.string.bipe_app_linked_devices_sign_out_error)) },
            confirmButton = { TextButton(onClick = devices::pageClosed) { Text(stringResource(R.string.bipe_app_close)) } },
        )
    }
}

private fun deviceName(context: Context, device: BipeMatrixDevice): String =
    device.displayName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.bipe_linked_devices_unknown_name)

@Composable
private fun DeviceRow(device: BipeMatrixDevice, onClick: () -> Unit) {
    val context = LocalContext.current
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(PhosphorIcon.DESKTOP.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(22.dp))
        Column {
            Text(deviceName(context, device), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
            device.lastSeenTs?.let { ts ->
                val ago = DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                Text(stringResource(R.string.bipe_linked_devices_last_used, ago), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            }
        }
    }
    HorizontalDivider(color = colors.lineSubtle, modifier = Modifier.padding(start = 16.dp))
}

/**
 * The MAS page in a private web view (link-computer-approve notes): cookies and storage cleared before and after,
 * only https pages on the MAS host, no new windows, no file access, the login form filled once with the hidden password.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MasPage(devices: LinkedDevicesModel, phase: LinkedDevicesModel.SignOutPhase) {
    val colors = BipeTheme.colors
    BackHandler(onBack = devices::pageClosed)
    Surface(Modifier.fillMaxSize(), color = colors.surfaceBase) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = devices::pageClosed) { Text(stringResource(R.string.bipe_app_close)) }
            }
            if (phase is LinkedDevicesModel.SignOutPhase.Page) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        LinkedDevicesModel.clearWebData()
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.setSupportMultipleWindows(false)
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            webViewClient = MasClient(phase.host, devices)
                            loadUrl(phase.url)
                        }
                    },
                    onRelease = { webView ->
                        webView.stopLoading()
                        webView.clearHistory()
                        webView.clearCache(true)
                        webView.destroy()
                    },
                )
            } else {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = colors.accentFill)
                    Text(stringResource(R.string.bipe_app_linked_devices_sign_out_loading), color = colors.inkSecondary)
                }
            }
        }
    }
}

private class MasClient(private val host: String, private val devices: LinkedDevicesModel) : WebViewClient() {
    private var filledLogin = false

    /** A navigation or a form post off the MAS host stops the load. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !BipeWebLink.isAllowed(request.url.toString(), host)

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (!BipeWebLink.isAllowed(url, host)) view.stopLoading()
    }

    override fun onPageFinished(view: WebView, url: String) {
        view.evaluateJavascript(BipeWebLink.IS_LOGIN_FORM_SCRIPT) { isLoginForm ->
            if (isLoginForm != "true") return@evaluateJavascript
            val password = if (filledLogin) null else devices.takePassword()
            if (password == null) {
                // The login form a second time: the sign-in failed.
                devices.pageFailed()
                return@evaluateJavascript
            }
            filledLogin = true
            view.evaluateJavascript(BipeWebLink.loginScript(devices.username, password), null)
        }
    }
}
