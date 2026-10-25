// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeAttestation
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.ProviderException
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android Key Attestation for sign-up (bipe-chat/bipe#92, ADR 0043, spec signup-create): a new EC key in the Android
 * Keystore carries the server's one-time challenge, and its certificate chain goes to the server, which checks it
 * against Google's attestation roots (bipe-api `src/key-attest.ts`). Platform API only, no Google library, so it works
 * in the `foss` flavour too. The key signs nothing and is deleted after the chain is read.
 */
object BipeKeyAttestation {
    /** Contract limits of `Attestation.key_attestation_chain`. */
    internal const val MIN_CERTIFICATES = 2
    internal const val MAX_CERTIFICATES = 8
    internal const val MAX_CERTIFICATE_CHARS = 8192

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS_PREFIX = "bipe-signup-attestation-"

    /**
     * The attestation for one sign-up try, or `null` when the phone cannot make one or the challenge does not come.
     * Then the app sends none and the server decides (spec notes: no error on the phone).
     */
    suspend fun attestation(context: Context, api: BipeApiClient): BipeAttestation? {
        val challenge = try {
            api.attestChallenge()
        } catch (_: BipeApiException) {
            // The sign-up request then meets the same network or server problem and reports it.
            return null
        }
        val chain = withContext(Dispatchers.Default) { certificateChain(context, challenge) } ?: return null
        return encode(chain)?.let { BipeAttestation(keyAttestationChain = it, challenge = challenge) }
    }

    /** Base64 of each certificate, or `null` when the chain is outside the contract limits. */
    internal fun encode(chain: List<ByteArray>): List<String>? {
        if (chain.size !in MIN_CERTIFICATES..MAX_CERTIFICATES) return null
        val encoded = chain.map { Base64.getEncoder().encodeToString(it) }
        return encoded.takeIf { list -> list.none { it.isEmpty() || it.length > MAX_CERTIFICATE_CHARS } }
    }

    private fun certificateChain(context: Context, challenge: String): List<ByteArray>? {
        val alias = ALIAS_PREFIX + UUID.randomUUID()
        val keyStore = try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }
        } catch (_: Exception) {
            return null
        }
        return try {
            // StrongBox when the phone has it (Android 9+); some phones list the feature and still refuse
            // (StrongBoxUnavailableException, a ProviderException), then the TEE.
            val strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            try {
                generate(alias, challenge, strongBox)
            } catch (error: ProviderException) {
                if (!strongBox) throw error
                runCatching { keyStore.deleteEntry(alias) }
                generate(alias, challenge, strongBox = false)
            }
            keyStore.getCertificateChain(alias)?.map { it.encoded }
        } catch (_: Exception) {
            // ProviderException and others: no attestation on this phone.
            null
        } finally {
            runCatching { keyStore.deleteEntry(alias) }
        }
    }

    private fun generate(alias: String, challenge: String, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge.toByteArray(Charsets.UTF_8))
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
    }
}
