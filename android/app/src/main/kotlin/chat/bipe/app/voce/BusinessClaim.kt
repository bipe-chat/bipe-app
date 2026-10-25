// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.voce

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.settings.SettingsPage
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeBusinessClaim
import chat.bipe.shared.api.BipeReservedName
import chat.bipe.shared.company.BipeCnpj
import chat.bipe.shared.company.addBusinessDomain
import chat.bipe.shared.company.checkBusinessDomain
import chat.bipe.shared.company.claimBusiness
import chat.bipe.shared.company.domain
import chat.bipe.shared.company.normalized
import chat.bipe.shared.company.proveBusinessWithEcnpj
import chat.bipe.shared.company.withdrawBusiness
import chat.bipe.shared.generated.BipeScreen
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Spec business-claim (bipe-shared product/screens/business-claim.json). The server decides every step (ADR 0042). */
class BusinessClaimModel(
    private val session: AccountSession,
    private val api: BipeApiClient,
    private val scope: CoroutineScope,
    private val onChange: () -> Unit,
) {
    sealed interface Phase {
        data object Loading : Phase
        data object NoClaim : Phase
        data class Claim(val claim: BipeBusinessClaim) : Phase
        data class Failed(val message: Int) : Phase
    }

    /** Which part a notice belongs to, so it shows under that part. [domain] null: the add field. */
    sealed interface Part {
        data object Cnpj : Part
        data object Proof : Part
        data class Domain(val domain: String?) : Part
    }

    data class Notice(val part: Part, val text: Int)

    data class State(
        val phase: Phase = Phase.Loading,
        val busy: Boolean = false,
        val notice: Notice? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Action appear: GET /v1/me/business. 404 is no claim. */
    fun appear() {
        scope.launch {
            _state.update { it.copy(phase = if (it.phase is Phase.Claim) it.phase else Phase.Loading) }
            _state.update {
                try {
                    val claim = BipeApiClient.withTokenRetry({ session.accessToken() }) { api.myBusiness(it) }
                    it.copy(phase = claim?.let(Phase::Claim) ?: Phase.NoClaim)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    it.copy(phase = Phase.Failed(message(error)))
                }
            }
        }
    }

    /** Action submitCnpj. */
    fun submitCnpj(input: String) {
        val cnpj = BipeCnpj.normalized(input) ?: return
        run(Part.Cnpj) { token -> _state.update { it.copy(phase = Phase.Claim(api.claimBusiness(cnpj, token))) } }
    }

    /** Action pickSignature: the bytes of the .p7s file the user chose. */
    fun submitSignature(context: Context, uri: Uri) {
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input -> input.readAtMost(MAX_SIGNATURE_BYTES + 1) }
                }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty() || bytes.size > MAX_SIGNATURE_BYTES || bytes[0] != 0x30.toByte()) {
                _state.update { it.copy(notice = Notice(Part.Proof, R.string.bipe_claim_signature_unreadable)) }
                return@launch
            }
            run(Part.Proof) { token -> _state.update { it.copy(phase = Phase.Claim(api.proveBusinessWithEcnpj(bytes, token))) } }
        }
    }

    /** Action addDomain. Returns false when the input is not a website. */
    fun addDomain(input: String): Boolean {
        val domain = BipeCnpj.domain(input)
        if (domain == null) {
            _state.update { it.copy(notice = Notice(Part.Domain(null), R.string.bipe_claim_domain_invalid)) }
            return false
        }
        run(Part.Domain(null)) { token ->
            api.addBusinessDomain(domain, token)
            reload(token)
        }
        return true
    }

    /** Action checkDomain. */
    fun checkDomain(domain: String) {
        run(Part.Domain(domain)) { token ->
            val checked = api.checkBusinessDomain(domain, token)
            reload(token)
            if (checked.verifiedAt == null) _state.update { it.copy(notice = Notice(Part.Domain(domain), R.string.bipe_claim_domain_not_yet)) }
        }
    }

    /** Action withdraw, after the confirmation. */
    fun withdraw() {
        run(Part.Cnpj) { token ->
            api.withdrawBusiness(token)
            _state.update { it.copy(phase = Phase.NoClaim) }
        }
    }

    /** Actions copyChallenge and copyTxt. */
    fun copy(context: Context, value: String, part: Part) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, value))
        _state.update { it.copy(notice = Notice(part, R.string.bipe_claim_copied)) }
    }

    private suspend fun reload(token: String) {
        api.myBusiness(token)?.let { claim -> _state.update { it.copy(phase = Phase.Claim(claim)) } }
    }

    private fun run(part: Part, body: suspend (String) -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        scope.launch {
            try {
                BipeApiClient.withTokenRetry({ session.accessToken() }) { body(it) }
                onChange()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(notice = Notice(part, message(error))) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun message(error: Exception): Int = when {
        error is BipeApiException.Network -> R.string.bipe_error_network
        error is BipeApiException.Server -> when (error.code) {
            BipeApiErrorCode.RATE_LIMITED -> R.string.bipe_error_rate_limited
            BipeApiErrorCode.CNPJ_INVALID -> R.string.bipe_claim_cnpj_invalid
            BipeApiErrorCode.CNPJ_TAKEN -> R.string.bipe_claim_cnpj_taken
            BipeApiErrorCode.CNPJ_NOT_ACTIVE -> R.string.bipe_claim_rejected
            BipeApiErrorCode.SIGNATURE_INVALID -> R.string.bipe_claim_signature_invalid
            BipeApiErrorCode.BAD_REQUEST -> R.string.bipe_claim_domain_invalid.takeIf { _state.value.phase is Phase.Claim } ?: R.string.bipe_claim_cnpj_invalid
            else -> R.string.bipe_error_generic
        }
        else -> R.string.bipe_error_generic
    }

    private companion object {
        /** The .p7s file of an e-CNPJ signature is a few KB; the API takes 64 KB of base64. */
        const val MAX_SIGNATURE_BYTES = 48 * 1024
    }
}

@Composable
fun BusinessClaimScreen(session: AccountSession, api: BipeApiClient, onChange: () -> Unit, openSupport: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val model = remember(session) { BusinessClaimModel(session, api, scope, onChange) }
    val state by model.state.collectAsState()
    var cnpjInput by remember { mutableStateOf("") }
    var domainInput by remember { mutableStateOf("") }
    var confirmingWithdraw by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.submitSignature(context, it) } }
    LaunchedEffect(model) { model.appear() }
    val colors = BipeTheme.colors

    SettingsPage(stringResource(R.string.bipe_claim_title), BipeScreen.BUSINESS_CLAIM.id, onBack = onBack) {
        when (val phase = state.phase) {
            BusinessClaimModel.Phase.Loading -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accentFill) }
            }
            is BusinessClaimModel.Phase.Failed -> item(key = "failed") {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(phase.message), color = colors.inkSecondary)
                    Button(onClick = model::appear) { Text(stringResource(R.string.bipe_app_retry)) }
                }
            }
            BusinessClaimModel.Phase.NoClaim -> item(key = "form") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.bipe_claim_intro), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                    OutlinedTextField(
                        value = cnpjInput,
                        onValueChange = { value -> cnpjInput = BipeCnpj.normalized(value)?.let(BipeCnpj::formatted) ?: value },
                        label = { Text(stringResource(R.string.bipe_badge_company_cnpj)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { model.submitCnpj(cnpjInput) }, enabled = BipeCnpj.normalized(cnpjInput) != null && !state.busy, modifier = Modifier.fillMaxWidth()) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), color = colors.inkOnAccent, strokeWidth = 2.dp)
                        else Text(stringResource(R.string.bipe_claim_submit))
                    }
                    NoticeLine(state, BusinessClaimModel.Part.Cnpj)
                }
            }
            is BusinessClaimModel.Phase.Claim -> {
                val claim = phase.claim
                item(key = "status") { StatusBlock(claim, state) }
                val challenge = claim.challenge
                if (claim.status == "awaiting_proof" && challenge != null) {
                    item(key = "proof") {
                        Section(stringResource(R.string.bipe_claim_proof_title)) {
                            Text(stringResource(R.string.bipe_claim_proof_ecnpj), style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
                            Text(stringResource(R.string.bipe_claim_proof_ecnpj_body), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                            // The exact text to sign: never trimmed or changed.
                            SelectionContainer { Text(challenge, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = colors.inkPrimary) }
                            OutlinedButton(onClick = { model.copy(context, challenge, BusinessClaimModel.Part.Proof) }) { Text(stringResource(R.string.bipe_claim_copy_text)) }
                            OutlinedButton(onClick = {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, challenge), null))
                            }) { Text(stringResource(R.string.bipe_claim_send_text)) }
                            Button(onClick = { picker.launch(arrayOf("application/pkcs7-signature", "application/x-pkcs7-signature", "application/octet-stream", "*/*")) }, enabled = !state.busy) {
                                if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), color = colors.inkOnAccent, strokeWidth = 2.dp)
                                else Text(stringResource(R.string.bipe_claim_pick_signature))
                            }
                            NoticeLine(state, BusinessClaimModel.Part.Proof)
                        }
                    }
                }
                if (claim.status == "awaiting_proof" || claim.status == "verified") {
                    item(key = "domains") {
                        Section(stringResource(R.string.bipe_claim_domains_title)) {
                            Text(stringResource(R.string.bipe_claim_domains_body), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                            claim.domains.forEach { domain ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(domain.domain, style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
                                    if (domain.verifiedAt != null) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Icon(painterResource(PhosphorIcon.CHECK_CIRCLE.drawable), contentDescription = null, tint = colors.statusSuccess, modifier = Modifier.size(16.dp))
                                            Text(stringResource(R.string.bipe_claim_domain_verified), color = colors.inkPrimary)
                                        }
                                    } else {
                                        domain.txtName?.let { TxtRow(stringResource(R.string.bipe_claim_domain_txt_name), it) { model.copy(context, it, BusinessClaimModel.Part.Domain(domain.domain)) } }
                                        domain.txtValue?.let { TxtRow(stringResource(R.string.bipe_claim_domain_txt_value), it) { model.copy(context, it, BusinessClaimModel.Part.Domain(domain.domain)) } }
                                        OutlinedButton(onClick = { model.checkDomain(domain.domain) }, enabled = !state.busy) { Text(stringResource(R.string.bipe_claim_domain_check)) }
                                        NoticeLine(state, BusinessClaimModel.Part.Domain(domain.domain))
                                    }
                                }
                            }
                            if (claim.domains.size < 5) {
                                OutlinedTextField(
                                    value = domainInput,
                                    onValueChange = { domainInput = it },
                                    placeholder = { Text(stringResource(R.string.bipe_claim_domain_placeholder)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedButton(onClick = { if (model.addDomain(domainInput)) domainInput = "" }, enabled = domainInput.isNotBlank() && !state.busy) {
                                    Text(stringResource(R.string.bipe_claim_domain_add))
                                }
                                NoticeLine(state, BusinessClaimModel.Part.Domain(null))
                            }
                        }
                    }
                }
                if (claim.reservedNames.isNotEmpty()) {
                    item(key = "reserved") { ReservedNames(claim.reservedNames, openSupport) }
                }
                item(key = "withdraw") {
                    TextButton(onClick = { confirmingWithdraw = true }, enabled = !state.busy, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.bipe_claim_withdraw), color = colors.trustDangerInk)
                    }
                }
            }
        }
    }
    if (confirmingWithdraw) {
        AlertDialog(
            onDismissRequest = { confirmingWithdraw = false },
            text = { Text(stringResource(R.string.bipe_claim_withdraw_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmingWithdraw = false; model.withdraw(); cnpjInput = "" }) {
                    Text(stringResource(R.string.bipe_claim_withdraw), color = colors.trustDangerInk)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingWithdraw = false }) { Text(stringResource(R.string.bipe_app_cancel)) } },
        )
    }
}

@Composable
private fun StatusBlock(claim: BipeBusinessClaim, state: BusinessClaimModel.State) {
    val colors = BipeTheme.colors
    val (icon, tint) = when (claim.status) {
        "verified" -> PhosphorIcon.CHECK_CIRCLE to colors.statusSuccess
        "registry_pending", "awaiting_proof" -> PhosphorIcon.CLOCK to colors.inkSecondary
        else -> PhosphorIcon.WARNING_CIRCLE to colors.trustCautionInk
    }
    val text = when (claim.status) {
        "registry_pending" -> R.string.bipe_claim_registry_pending
        "awaiting_proof" -> R.string.bipe_claim_awaiting_proof
        "verified" -> R.string.bipe_claim_verified
        "rejected" -> R.string.bipe_claim_rejected
        else -> if (claim.reason == "not_active" || claim.reason == "not_found") R.string.bipe_badge_company_revoked_not_active else R.string.bipe_badge_company_revoked
    }
    Section(null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(icon.drawable), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Text(stringResource(text), color = colors.inkPrimary)
        }
        claim.cnpj?.let { Fact(stringResource(R.string.bipe_badge_company_cnpj), BipeCnpj.formatted(it)) }
        claim.registry?.let { registry ->
            Fact(stringResource(R.string.bipe_badge_company_legal_name), registry.legalName)
            registry.tradeName?.let { Fact(stringResource(R.string.bipe_badge_company_trade_name), it) }
            Text(
                stringResource(if (registry.active) R.string.bipe_claim_registry_active else R.string.bipe_claim_registry_inactive),
                color = if (registry.active) colors.inkPrimary else colors.trustCautionInk,
            )
            BipeCnpj.checkedDate(registry.checkedMonth)?.let {
                Fact(stringResource(R.string.bipe_badge_company_checked), it.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())))
            }
        }
        NoticeLine(state, BusinessClaimModel.Part.Cnpj)
    }
}

@Composable
private fun ReservedNames(names: List<BipeReservedName>, openSupport: () -> Unit) {
    val colors = BipeTheme.colors
    Section(stringResource(R.string.bipe_claim_reserved_title)) {
        Text(stringResource(R.string.bipe_claim_reserved_body), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        names.forEach { name ->
            Row(Modifier.fillMaxWidth()) {
                Text(name.name, color = colors.inkPrimary, modifier = Modifier.weight(1f))
                Text(stringResource(if (name.approved) R.string.bipe_claim_reserved_approved else R.string.bipe_claim_reserved_pending), color = colors.inkSecondary)
            }
        }
        if (names.any { !it.approved }) {
            OutlinedButton(onClick = openSupport) { Text(stringResource(R.string.bipe_claim_reserved_request)) }
        }
    }
}

@Composable
private fun Section(title: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = BipeTheme.colors.accentInk) }
        content()
    }
}

@Composable
private fun Fact(label: String, value: String) {
    val colors = BipeTheme.colors
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary) }
    }
}

@Composable
private fun TxtRow(label: String, value: String, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Fact(label, value) }
        TextButton(onClick = onCopy) { Text(stringResource(R.string.bipe_claim_copy)) }
    }
}

/** An error or a confirmation under the part it belongs to. */
@Composable
private fun NoticeLine(state: BusinessClaimModel.State, part: BusinessClaimModel.Part) {
    val notice = state.notice?.takeIf { it.part == part } ?: return
    Text(stringResource(notice.text), style = MaterialTheme.typography.bodySmall, color = BipeTheme.colors.inkSecondary)
}

/** Reads up to [limit] bytes (InputStream.readNBytes needs API 33). */
private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (output.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (read < 0) break
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
