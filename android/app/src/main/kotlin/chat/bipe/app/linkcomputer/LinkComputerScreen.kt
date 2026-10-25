// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.linkcomputer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import chat.bipe.app.common.BipeButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import chat.bipe.app.common.BipeOutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import chat.bipe.app.BipeTheme
import chat.bipe.app.settings.OwnerCheck
import chat.bipe.app.settings.SettingsModel
import chat.bipe.shared.weblink.BipeWebLink
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.json.JSONObject

/**
 * The link-a-computer flow: link-computer-scan, then link-computer-warning, then link-computer-approve. The only way
 * into it is Settings > Linked devices > Link a computer (ADR 0116, security.md rule 5). Mode PHONE: link-phone-show,
 * then link-phone-approve; the only way in is Você > Conectar outro aparelho (ADR 0059).
 */
@Composable
fun LinkComputerScreen(settings: SettingsModel, onClose: () -> Unit, mode: LinkComputerModel.Mode = LinkComputerModel.Mode.COMPUTER) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(settings, mode) { LinkComputerModel(context.applicationContext, settings, scope, mode) }
    val state by model.state.collectAsState()
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), model::cameraResult)
    LaunchedEffect(model) {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        model.open(cameraAllowed = allowed)
        // The old phone shows a code: no camera.
        if (!allowed && mode == LinkComputerModel.Mode.COMPUTER) cameraPermission.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(state.isClosed) { if (state.isClosed) onClose() }
    val closeOrCancel = { if (state.step == LinkComputerModel.Step.APPROVE) model.close() else model.cancel() }
    BackHandler(onBack = closeOrCancel)
    val colors = BipeTheme.colors

    Surface(modifier = Modifier.fillMaxSize(), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = closeOrCancel) {
                Text(stringResource(if (state.step == LinkComputerModel.Step.APPROVE) R.string.bipe_app_close else R.string.bipe_app_cancel))
            }
            when (state.step) {
                LinkComputerModel.Step.SCAN -> ScanContent(state, model)
                LinkComputerModel.Step.SHOW -> LinkPhoneShowContent(state, model)
                LinkComputerModel.Step.WARNING -> WarningContent(state, model)
                LinkComputerModel.Step.APPROVE -> ApproveContent(state, model)
            }
        }
    }
}

/** Spec link-computer-scan. */
@Composable
private fun ScanContent(state: LinkComputerModel.State, model: LinkComputerModel) {
    val context = LocalContext.current
    val colors = BipeTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().testTag(BipeScreen.LINK_COMPUTER_SCAN.id),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state.support) {
            LinkComputerModel.Support.CHECKING -> CircularProgressIndicator(color = colors.accentFill)
            // Creating the PIN opens the signup-pin steps (bipe-chat/bipe#554), which land with sign-up.
            LinkComputerModel.Support.NEEDS_PIN -> Message(stringResource(R.string.bipe_link_computer_needs_pin, BipeRegistries.PIN_LENGTH.toString()))
            LinkComputerModel.Support.NOT_SUPPORTED -> Message(stringResource(R.string.bipe_link_computer_not_supported))
            LinkComputerModel.Support.SUPPORTED -> {
                Text(stringResource(R.string.bipe_app_link_computer_scan_title), style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary, textAlign = TextAlign.Center)
                when (state.scanPhase) {
                    LinkComputerModel.ScanPhase.CAMERA_DENIED -> {
                        Message(stringResource(R.string.bipe_camera_permission_body))
                        BipeButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        }) { Text(stringResource(R.string.bipe_contacts_open_settings_android)) }
                    }
                    LinkComputerModel.ScanPhase.SCANNING, LinkComputerModel.ScanPhase.FAILED -> {
                        Text(stringResource(R.string.bipe_app_link_computer_scan_body), color = colors.inkSecondary, textAlign = TextAlign.Center)
                        QrScanner(onCode = model::codeRead, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)))
                        // Rule 5, at the moment of the scan.
                        Text(stringResource(R.string.bipe_recovery_qr_never_from_others), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary, textAlign = TextAlign.Center)
                        state.scanError?.let { error ->
                            Text(stringResource(R.string.bipe_link_computer_error_title), style = MaterialTheme.typography.titleMedium, color = colors.trustDangerInk)
                            Text(stringResource(error.message(model.mode)), color = colors.inkPrimary, textAlign = TextAlign.Center)
                            BipeOutlinedButton(onClick = model::scanAgain) { Text(stringResource(R.string.bipe_app_link_computer_scan_again)) }
                        }
                    }
                    LinkComputerModel.ScanPhase.CONNECTING -> {
                        CircularProgressIndicator(color = colors.accentFill)
                        Text(stringResource(R.string.bipe_app_link_computer_connecting), color = colors.inkSecondary)
                    }
                    LinkComputerModel.ScanPhase.SHOW_CHECK_CODE -> {
                        Text(stringResource(R.string.bipe_app_link_computer_check_code_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                        // Large; never read aloud or sent anywhere.
                        Text(
                            state.checkCode.orEmpty(),
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = colors.inkPrimary,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(stringResource(R.string.bipe_app_link_computer_check_code_body), color = colors.inkSecondary, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** Spec link-computer-warning: full access, the scam warning and the cooldown. */
@Composable
private fun WarningContent(state: LinkComputerModel.State, model: LinkComputerModel) {
    val colors = BipeTheme.colors
    val view = LocalView.current
    // The countdown starts when the screen is in front, and again each time it comes back (MainActivity resumes it).
    DisposableEffect(model) {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
        model.warningAppeared()
        onDispose { model.warningLeftFront() }
    }
    LifecycleResumeEffect(onResume = model::warningAppeared, onPause = model::warningLeftFront)
    val phone = model.mode == LinkComputerModel.Mode.PHONE
    val screen = if (phone) BipeScreen.LINK_PHONE_APPROVE else BipeScreen.LINK_COMPUTER_WARNING
    Column(modifier = Modifier.fillMaxSize().testTag(screen.id), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = colors.trustCautionInk, modifier = Modifier.size(48.dp))
        Text(
            stringResource(if (phone) R.string.bipe_app_link_phone_warning_title else R.string.bipe_link_computer_warning_title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.inkPrimary,
        )
        Text(stringResource(if (phone) R.string.bipe_app_link_phone_warning_body else R.string.bipe_link_computer_warning_body), color = colors.inkPrimary)
        // ADR 0059: the time of the request; the model and the place show on the MAS page.
        val requestedAt = state.requestedAt
        if (phone && requestedAt != null) {
            val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(requestedAt.atZone(ZoneId.systemDefault()))
            Text(stringResource(R.string.bipe_app_link_phone_time, time), fontWeight = FontWeight.Bold, color = colors.inkPrimary)
        }
        Text(
            stringResource(if (phone) R.string.bipe_app_link_phone_scam else R.string.bipe_link_computer_warning_scam),
            color = colors.inkPrimary,
            modifier = Modifier.fillMaxWidth().background(colors.trustCautionWash, RoundedCornerShape(12.dp)).padding(12.dp),
        )
        Spacer(Modifier.weight(1f))
        BipeButton(onClick = model::continueToApprove, enabled = state.canContinue, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (state.canContinue) stringResource(R.string.bipe_link_computer_continue)
                else stringResource(R.string.bipe_pause_countdown, state.secondsLeft.toString()),
            )
        }
    }
}

/** Calls [onResume] and [onPause] when the activity goes to the front and back. */
@Composable
private fun LifecycleResumeEffect(onResume: () -> Unit, onPause: () -> Unit) {
    val activity = LocalContext.current as androidx.activity.ComponentActivity
    DisposableEffect(activity) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> onPause()
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
}

/** Spec link-computer-approve: the owner check, then the MAS consent page in a private web view. */
@Composable
private fun ApproveContent(state: LinkComputerModel.State, model: LinkComputerModel) {
    val context = LocalContext.current
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val phone = model.mode == LinkComputerModel.Mode.PHONE
    val reason = stringResource(if (phone) R.string.bipe_app_link_phone_biometric else R.string.bipe_link_computer_biometric)
    Column(
        modifier = Modifier.fillMaxSize().testTag(if (phone) BipeScreen.LINK_PHONE_APPROVE.id else BipeScreen.LINK_COMPUTER_APPROVE.id),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state.approvePhase) {
            LinkComputerModel.ApprovePhase.CONFIRM_IDENTITY -> {
                CircularProgressIndicator(color = colors.accentFill)
                LaunchedEffect(Unit) {
                    scope.launch { model.identityChecked(OwnerCheck.owner(context, reason)) }
                }
            }
            LinkComputerModel.ApprovePhase.LOADING, LinkComputerModel.ApprovePhase.CONSENT -> {
                if (state.approvePhase == LinkComputerModel.ApprovePhase.CONSENT) {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(if (phone) R.string.bipe_app_link_phone_consent_hint else R.string.bipe_link_computer_consent_hint), color = colors.inkPrimary)
                        if (state.placeKnown) {
                            val place = state.place
                            Text(
                                if (place != null) stringResource(R.string.bipe_link_computer_place, place) else stringResource(R.string.bipe_link_computer_place_unknown),
                                fontWeight = FontWeight.Bold,
                                color = colors.inkPrimary,
                            )
                            Text(stringResource(R.string.bipe_link_computer_place_source), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                        }
                    }
                } else {
                    Text(stringResource(R.string.bipe_link_computer_loading), color = colors.inkSecondary)
                }
                val url = state.consentUrl
                val host = state.masHost
                if (url != null && host != null) {
                    // The user never sees the MAS login form.
                    MasWebView(url, host, model, Modifier.fillMaxWidth().weight(1f).alpha(if (state.approvePhase == LinkComputerModel.ApprovePhase.CONSENT) 1f else 0f))
                }
            }
            LinkComputerModel.ApprovePhase.FINISHING -> {
                CircularProgressIndicator(color = colors.accentFill)
                Text(stringResource(if (phone) R.string.bipe_app_link_phone_finishing else R.string.bipe_app_link_computer_finishing), color = colors.inkSecondary)
            }
            LinkComputerModel.ApprovePhase.DONE -> {
                Icon(painterResource(PhosphorIcon.CHECK.drawable), contentDescription = null, tint = colors.trustVerifiedInk, modifier = Modifier.size(48.dp))
                Text(stringResource(if (phone) R.string.bipe_app_link_phone_done else R.string.bipe_link_computer_done), style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary, textAlign = TextAlign.Center)
            }
            LinkComputerModel.ApprovePhase.FAILED -> {
                Text(
                    stringResource(if (phone) R.string.bipe_app_link_phone_error_title else R.string.bipe_link_computer_error_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.trustDangerInk,
                )
                Text(stringResource(state.approveError?.message(model.mode) ?: R.string.bipe_error_generic), color = colors.inkPrimary, textAlign = TextAlign.Center)
                BipeButton(onClick = model::retry) {
                    Text(stringResource(if (phone) R.string.bipe_app_link_phone_new_code else R.string.bipe_app_link_computer_scan_again))
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, color = BipeTheme.colors.inkPrimary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
}

/**
 * The private web view of link-computer-approve: its own cookies and storage, cleared before and after, https pages
 * on the MAS host only, no file access, no new windows. Never a Custom Tab: it shares cookies with the browser.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MasWebView(url: String, masHost: String, model: LinkComputerModel, modifier: Modifier) {
    DisposableEffect(Unit) {
        clearWebData()
        // Action finish: the web view's cookies and storage go when the screen goes.
        onDispose { clearWebData() }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportMultipleWindows(false)
                settings.saveFormData = false
                if (Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url.toString()
                        if (BipeWebLink.isAllowed(target, masHost)) return false
                        model.blockedNavigation()
                        return true
                    }

                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        if (!BipeWebLink.isAllowed(pageUrl, masHost)) return
                        val probe = "JSON.stringify({password: document.querySelector('input[type=password]') !== null, " +
                            "form: document.querySelector('form') !== null, text: document.body ? document.body.innerText : ''})"
                        view.evaluateJavascript(probe) { result ->
                            val page = runCatching { JSONObject(JSONObject("{\"v\":$result}").getString("v")) }.getOrNull() ?: return@evaluateJavascript
                            model.pageLoaded(pageUrl, page.optBoolean("password"), page.optBoolean("form"), page.optString("text"))
                                ?.let { view.evaluateJavascript(it, null) }
                        }
                    }
                }
                loadUrl(url)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
    )
}

private fun clearWebData() {
    CookieManager.getInstance().removeAllCookies(null)
    WebStorage.getInstance().deleteAllData()
}
