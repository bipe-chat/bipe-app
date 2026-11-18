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
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The device key of one account on this phone (ADR 0283, bipe-chat/bipe#1351, spec account-start deviceKey): an EC
 * P-256 key in the Android Keystore, StrongBox when present. It signs only, cannot leave the phone, and Keystore keys
 * are never in a backup. bipe-api keeps its public key for the Matrix device; the key then asks for a one-time hidden
 * password (`POST /v1/me/password-once`). Platform API only, so it works in the `foss` flavour too.
 *
 * A local mark keeps the device ID the key was registered for. The mark counts only while the key is in the Keystore,
 * so a mark restored from a backup without its key counts for nothing.
 */
class BipeDeviceKey(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Result of [register]. */
    enum class Registration {
        /** 204, or this device already had this key. */
        REGISTERED,
        /** 409 device_key_exists: another key is on this device. No new key; links need a recovery. */
        EXISTS,
        /** Network, server or Keystore failure. The next launch tries again. */
        FAILED,
    }

    /** The key failed on this phone (missing, or the Keystore refused). Never holds a secret. */
    class KeyFailure(cause: Throwable? = null) : Exception("device key unavailable", cause)

    /** true when this phone registered its key for [deviceId] of [userId] and the key is still in the Keystore. */
    suspend fun isRegistered(userId: String, deviceId: String): Boolean = withContext(Dispatchers.IO) {
        prefs.getString(markKey(userId), null) == deviceId && runCatching { keyStore().containsAlias(alias(userId)) }.getOrDefault(false)
    }

    /**
     * Makes the key when missing and sends its public key (`POST /v1/me/device-key`). Runs on every new session and every
     * launch; a registered key sends nothing.
     */
    suspend fun register(api: BipeApiClient, userId: String, deviceId: String, accessToken: suspend () -> String?): Registration =
        // One run at a time in the process: two runs could make two keys under one alias.
        lock.withLock { registerNow(api, userId, deviceId, accessToken) }

    private suspend fun registerNow(api: BipeApiClient, userId: String, deviceId: String, accessToken: suspend () -> String?): Registration {
        if (isRegistered(userId, deviceId)) return Registration.REGISTERED
        val publicKey = try {
            withContext(Dispatchers.Default) { publicKey(userId) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return Registration.FAILED
        }
        return try {
            BipeApiClient.withTokenRetry(accessToken) { api.registerDeviceKey(publicKey, it) }
            withContext(Dispatchers.IO) { prefs.edit().putString(markKey(userId), deviceId).apply() }
            Registration.REGISTERED
        } catch (error: BipeApiException) {
            if ((error as? BipeApiException.Server)?.code == BipeApiErrorCode.DEVICE_KEY_EXISTS) Registration.EXISTS else Registration.FAILED
        }
    }

    /**
     * A one-time hidden password (ADR 0283 decision 3): a challenge, a signature with the device key, then
     * `POST /v1/me/password-once`. The caller keeps it in memory only and never logs it. Throws [BipeApiException] or
     * [KeyFailure].
     */
    suspend fun oneTimePassword(api: BipeApiClient, userId: String, deviceId: String, accessToken: suspend () -> String?): String =
        BipeApiClient.withTokenRetry(accessToken) { token ->
            val challenge = api.passwordOnceChallenge(token)
            val signature = withContext(Dispatchers.Default) { sign(userId, message(userId, deviceId, challenge)) }
            api.passwordOnce(deviceId, challenge, signature, token)
        }

    /** Sign-out (spec sign-out): the key and the mark of the account leave this phone. */
    suspend fun delete(userId: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching { keyStore().deleteEntry(alias(userId)) }
            prefs.edit().remove(markKey(userId)).apply()
        }
    }

    /** Base64 SubjectPublicKeyInfo DER of the key, made when missing. */
    private fun publicKey(userId: String): String {
        val alias = alias(userId)
        val store = keyStore()
        val certificate = store.getCertificate(alias) ?: run {
            generate(alias)
            store.getCertificate(alias) ?: throw KeyFailure()
        }
        return Base64.getEncoder().encodeToString(certificate.publicKey.encoded)
    }

    private fun sign(userId: String, message: ByteArray): String {
        val key = try {
            keyStore().getKey(alias(userId), null) as? PrivateKey
        } catch (error: Exception) {
            throw KeyFailure(error)
        } ?: throw KeyFailure()
        return try {
            val signer = Signature.getInstance(SIGNATURE)
            signer.initSign(key)
            signer.update(message)
            Base64.getEncoder().encodeToString(signer.sign())
        } catch (error: Exception) {
            throw KeyFailure(error)
        }
    }

    private fun generate(alias: String) {
        // StrongBox when the phone has it (Android 9+); some phones list the feature and still refuse
        // (StrongBoxUnavailableException, a ProviderException), then the TEE.
        val strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        try {
            generate(alias, strongBox)
        } catch (error: ProviderException) {
            if (!strongBox) throw error
            runCatching { keyStore().deleteEntry(alias) }
            generate(alias, strongBox = false)
        }
    }

    private fun generate(alias: String, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun markKey(userId: String) = "device_" + hash(userId)

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val PREFS = "bipe_device_keys"
        private const val ALIAS_PREFIX = "bipe_device_key_"
        private const val SIGNATURE = "SHA256withECDSA"
        private val lock = Mutex()

        /** The tag at the start of the signed message (contract `POST /v1/me/password-once`). */
        const val MESSAGE_TAG = "bipe-password-once:v1"

        /** The bytes the device key signs: UTF-8 of tag, user ID, device ID and challenge, one per line, no final newline. */
        fun message(userId: String, deviceId: String, challenge: String): ByteArray =
            listOf(MESSAGE_TAG, userId, deviceId, challenge).joinToString("\n").toByteArray(Charsets.UTF_8)

        /** The Keystore alias of the account's key. The Matrix ID is hashed, so the alias has only safe characters. */
        fun alias(userId: String): String = ALIAS_PREFIX + hash(userId)

        private fun hash(userId: String): String =
            MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
