// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.core.BipeServerStatus
import chat.bipe.core.ListType
import chat.bipe.core.SignedListResult
import chat.bipe.core.SignerSet
import chat.bipe.core.TrustedKey
import chat.bipe.core.VerifiedList
import chat.bipe.core.bipeServerStatus
import chat.bipe.core.signedListResultCode
import chat.bipe.core.verifyBundledList
import chat.bipe.core.verifySignedList
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

/**
 * The signed operator list and issuer registry on this device (bipe-shared docs/signed-lists.md, bipe-chat/bipe#645).
 * bipe-core verifies every envelope; this class only stores, fetches and picks. Same rules as BipeKit `SignedLists`:
 * - Start from the newest valid list: the stored envelope or the bundled one ([BipeRegistries]), whichever has the
 *   higher version. The bundled check skips expiry, so an old build still starts.
 * - Fetch from the core only (A-3), at the current list's `region.core_api`, when the app comes to the foreground and
 *   the last fetch is more than 1 hour old, and every 6 hours while it runs.
 * - Keep a fetched envelope only when `verifySignedList` says valid against the current list. Store the bytes as
 *   received. A failed fetch or a rejected list keeps the current list and shows nothing.
 * - "Who is a Bipe user" is `bipeServerStatus` on the current list, with the core's stale rule.
 */
class SignedLists internal constructor(private val directory: File?) {
    private val lock = Any()
    private val lists = HashMap<ListType, VerifiedList>()
    private var lastFetchMs: Long? = null
    private var loop: Job? = null

    // Answers of isBipeServer for the current operator list, until it changes or its expires passes.
    private val memo = HashMap<String, Boolean>()
    private var memoUntil = Long.MAX_VALUE

    init {
        for (type in TYPES) {
            val stored = directory?.let { runCatching { file(it, type).readText() }.getOrNull() }
            newest(bundled(type), stored, type)?.let { lists[type] = it }
        }
    }

    fun current(type: ListType): VerifiedList? = synchronized(lock) { lists[type] }

    /**
     * True when the server name is a valid operator of the current list (bipe-core `bipeServerStatus`, stale rule
     * included). Before any valid list, and while the list is stale, only the default server counts.
     */
    fun isBipeServer(serverName: String, nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean = synchronized(lock) {
        if (nowSeconds >= memoUntil) {
            memo.clear()
            memoUntil = Long.MAX_VALUE
        }
        memo[serverName]?.let { return it }
        val current = lists[ListType.OPERATORS]
        val answer = bipeServerStatus(current, serverName, nowSeconds) == BipeServerStatus.VALID
        memo[serverName] = answer
        current?.expires?.let { if (nowSeconds < it) memoUntil = minOf(memoUntil, it) }
        answer
    }

    /** Call when the app comes to the foreground. Starts the 6-hour loop once, and fetches when the last fetch is old. */
    fun appBecameActive() {
        synchronized(lock) {
            if (loop == null) {
                loop = scope.launch {
                    while (isActive) {
                        delay(RUNNING_INTERVAL_MS)
                        refresh()
                    }
                }
            }
        }
        scope.launch { refreshIfDue() }
    }

    suspend fun refreshIfDue(nowMs: Long = System.currentTimeMillis()) {
        val due = synchronized(lock) { lastFetchMs?.let { nowMs - it >= FOREGROUND_INTERVAL_MS } ?: true }
        if (due) refresh(nowMs)
    }

    /** Fetches both lists from the core and keeps each valid one. Errors keep the current list. */
    suspend fun refresh(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) { lastFetchMs = nowMs }
        for (type in TYPES) {
            val base = current(ListType.OPERATORS)?.operators?.region?.coreApi ?: return
            if (!base.startsWith("https://")) return
            val text = fetch("${base.trimEnd('/')}/${path(type)}") ?: continue
            accept(text, type, nowMs / 1000)
        }
    }

    /** Verifies one fetched envelope against the current list and keeps it when valid. Returns the result code. */
    internal fun accept(text: String, type: ListType, nowSeconds: Long): String = synchronized(lock) {
        val result = verifySignedList(text, type, signerSet(), nowSeconds, lists[type]?.stored)
        if (result is SignedListResult.Valid) {
            directory?.let { dir ->
                runCatching {
                    dir.mkdirs()
                    val target = file(dir, type)
                    val temp = File(dir, "${target.name}.tmp")
                    temp.writeText(text)
                    if (!temp.renameTo(target)) {
                        target.delete()
                        temp.renameTo(target)
                    }
                }
            }
            lists[type] = result.list
            if (type == ListType.OPERATORS) {
                memo.clear()
                memoUntil = Long.MAX_VALUE
            }
        }
        signedListResultCode(result)
    }

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode != 200) return@runCatching null
                val bytes = connection.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
                if (bytes.size > MAX_BYTES) null else bytes.toString(Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    companion object {
        const val FOREGROUND_INTERVAL_MS = 60L * 60 * 1000
        const val RUNNING_INTERVAL_MS = 6L * 60 * 60 * 1000

        /** A list is a few kilobytes. A larger answer is not a list (bipe-shared#431: every delivery path sets a size limit). */
        const val MAX_BYTES = 256 * 1024

        private val TYPES = listOf(ListType.OPERATORS, ListType.ISSUERS)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile private var instance: SignedLists? = null

        /** Call once in Application.onCreate with Context.filesDir. Before that, the bundled lists only, kept nowhere. */
        fun init(filesDir: File) {
            synchronized(this) { instance = SignedLists(File(filesDir, "signed-lists")) }
        }

        val shared: SignedLists
            get() = instance ?: synchronized(this) { instance ?: SignedLists(null).also { instance = it } }

        /** The signer set that this build trusts (registries/list-signers.json). A key that is not base64url is dropped. */
        internal fun signerSet(): SignerSet = SignerSet(
            BipeRegistries.LIST_SIGNER_THRESHOLD.toUInt(),
            BipeRegistries.listSignerKeys.mapNotNull { (kid, key) ->
                runCatching { Base64.getUrlDecoder().decode(key) }.getOrNull()?.let { TrustedKey(kid, it) }
            },
        )

        internal fun bundled(type: ListType): String =
            if (type == ListType.OPERATORS) BipeRegistries.BUNDLED_OPERATOR_LIST else BipeRegistries.BUNDLED_ISSUER_REGISTRY

        /** The higher version of the two valid envelopes. Expiry is not checked here: a stale list follows the stale rule. */
        internal fun newest(bundled: String, stored: String?, type: ListType): VerifiedList? =
            listOfNotNull(bundled, stored)
                .mapNotNull { (verifyBundledList(it, type, signerSet()) as? SignedListResult.Valid)?.list }
                .maxByOrNull { it.version }

        private fun file(directory: File, type: ListType) =
            File(directory, if (type == ListType.OPERATORS) "operators.json" else "issuers.json")

        private fun path(type: ListType) = if (type == ListType.OPERATORS) "v1/operators" else "v1/issuers"
    }
}
