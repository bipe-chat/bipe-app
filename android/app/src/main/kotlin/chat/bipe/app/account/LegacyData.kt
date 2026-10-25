// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import chat.bipe.shared.account.BipeForkVault
import chat.bipe.shared.account.BipeForkVaultCipher
import chat.bipe.shared.account.BipeLegacyShape
import chat.bipe.shared.account.BipeVaultStore
import chat.bipe.shared.settings.BipeReportFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The earlier Bipe app's data on this phone (the fork, same application ID; bipe-chat/bipe#603, spec account-start
 * legacyKept). Read only: nothing here deletes, moves or writes a legacy file, a SharedPreferences file or a Keystore
 * key. The one move of the fork's vault to `bipe-account.fork` happens in BipeAccountVault and keeps every byte.
 */
object LegacyData {
    /** The fork's vault files that BipeAccountVault set aside, oldest first. */
    fun forkVaultFiles(context: Context): List<File> =
        context.noBackupFilesDir.listFiles { file -> file.name.startsWith(BipeForkVault.FILE_NAME) }.orEmpty()
            .sortedBy { it.name }

    /** Spec account-start legacyKept: the fork's vault is on this phone. */
    fun isPresent(context: Context): Boolean = forkVaultFiles(context).isNotEmpty()

    /**
     * Imports the fork's vault into this app's vault once per file, only with the layout the rehearsal confirmed
     * ([BipeForkVaultCipher.CONFIRMED]). What this app holds wins ([BipeForkVault.merge]). The fork's files stay.
     */
    suspend fun importForkVault(context: Context, vault: BipeVaultStore) {
        if (BipeForkVaultCipher.CONFIRMED == null) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val open = runCatching { forkKeyOpener() }.getOrNull() ?: return
        for (file in forkVaultFiles(context)) {
            if (prefs.getBoolean(file.name, false)) continue
            val fork = runCatching { BipeForkVaultCipher.read(file.readText(), open) }.getOrNull() ?: continue
            vault.update { BipeForkVault.merge(it, fork) }
            if (vault.read().accounts.keys.containsAll(fork.accounts.keys)) prefs.edit().putBoolean(file.name, true).apply()
        }
    }

    /** legacy-data.json for problem-report (structure only), or `null` when the phone holds no legacy data. */
    fun reportFile(context: Context): BipeReportFile? {
        if (!isPresent(context)) return null
        val open = runCatching { forkKeyOpener() }.getOrNull()
        val report = buildJsonObject {
            put("format", 1)
            put("forkKey", open != null)
            put("confirmedLayout", BipeForkVaultCipher.CONFIRMED?.id)
            put(
                "forkVault",
                JsonArray(
                    forkVaultFiles(context).map { file ->
                        val probe = BipeForkVaultCipher.probe(runCatching { file.readText() }.getOrDefault("")) { sealed ->
                            open?.invoke(sealed)
                        }
                        buildJsonObject {
                            put("name", file.name)
                            put("textLength", probe.textLength)
                            put("base64", probe.base64)
                            put("bytes", probe.byteCount)
                            put("layout", probe.layout?.id)
                            put("accounts", probe.accounts)
                            put("hasClaim", probe.hasClaim)
                            put("hasPendingSignup", probe.hasPendingSignup)
                            put("pendingInviters", probe.pendingInviters)
                            put("shape", probe.shape)
                        }
                    },
                ),
            )
            put("noBackup", tree(context.noBackupFilesDir))
            put("files", tree(context.filesDir))
            put("databases", tree(context.getDatabasePath("bipe-probe").parentFile))
            put("sharedPreferences", sharedPreferences(context))
        }
        return BipeReportFile("legacy-data.json", "application/json", report.toString().encodeToByteArray())
    }

    /** Decrypts with the fork's Keystore key, read only: a missing key is never made. */
    private fun forkKeyOpener(): ((BipeForkVaultCipher.Sealed) -> ByteArray?)? {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(BipeForkVault.KEY_ALIAS, null) as? SecretKey ?: return null
        return { sealed ->
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.iv))
            cipher.doFinal(sealed.body)
        }
    }

    /** Every entry under [root] (at most [MAX_ENTRIES]) by redacted relative path: size, and SQLite table names. */
    private fun tree(root: File?): JsonElement {
        root ?: return JsonObject(emptyMap())
        val out = sortedMapOf<String, JsonElement>()
        for (file in root.walkTopDown().drop(1)) {
            if (out.size >= MAX_ENTRIES) {
                out["…"] = JsonPrimitive("more entries")
                break
            }
            val key = file.relativeTo(root).invariantSeparatorsPath.split('/').joinToString("/", transform = BipeLegacyShape::redact)
            if (file.isDirectory) {
                out["$key/"] = JsonPrimitive("folder")
                continue
            }
            out[key] = buildJsonObject {
                put("bytes", file.length())
                sqlite(file)?.let { put("sqlite", it) }
            }
        }
        return JsonObject(out)
    }

    /** "not plain" for a database file without the SQLite header, table names for a plain one, `null` otherwise. */
    private fun sqlite(file: File): JsonElement? {
        val header = runCatching {
            file.inputStream().use { input -> ByteArray(16).also { buffer -> if (input.read(buffer) != buffer.size) return null } }
        }.getOrNull() ?: return null
        if (!header.contentEquals("SQLite format 3\u0000".encodeToByteArray())) {
            return if (file.extension.lowercase() in setOf("db", "sqlite", "sqlite3")) JsonPrimitive("not plain") else null
        }
        val tables = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", null).use { cursor ->
                    buildList { while (cursor.moveToNext() && size < 100) add(BipeLegacyShape.redact(cursor.getString(0))) }
                }
            }
        }.getOrNull()
        return buildJsonObject { put("tables", JsonArray(tables.orEmpty().map { JsonPrimitive(it) })) }
    }

    /** Key names and value types of every SharedPreferences file. Never a value. */
    private fun sharedPreferences(context: Context): JsonElement {
        val folder = File(context.dataDir, "shared_prefs")
        val files = folder.listFiles { file -> file.extension == "xml" }.orEmpty().sortedBy { it.name }
        return JsonObject(
            files.associate { file ->
                val name = file.nameWithoutExtension
                val entries = runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE).all }.getOrDefault(emptyMap())
                BipeLegacyShape.redact(name) to JsonObject(
                    entries.entries.associate { (key, value) -> BipeLegacyShape.redact(key) to JsonPrimitive(BipeLegacyShape.typeName(value)) }
                        .toSortedMap(),
                )
            },
        )
    }

    private const val PREFS = "bipe_fork_vault_import"
    private const val MAX_ENTRIES = 400
}
