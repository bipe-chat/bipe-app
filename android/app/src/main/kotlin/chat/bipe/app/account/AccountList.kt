// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.content.Context
import org.json.JSONArray

/**
 * The accounts on this phone in the order they were added, and the one used last (spec account-start notes,
 * ADR 0086). Holds Matrix IDs only, no secrets.
 */
class AccountList(context: Context) {
    private val prefs = context.getSharedPreferences("bipe_accounts", Context.MODE_PRIVATE)

    /** Oldest first. */
    var order: List<String>
        get() = prefs.getString(ORDER, null)?.let { json ->
            JSONArray(json).let { array -> List(array.length()) { array.getString(it) } }
        }.orEmpty()
        set(value) {
            prefs.edit().putString(ORDER, JSONArray(value).toString()).apply()
        }

    /** The account used last (app-tabs switchAccount). */
    var current: String?
        get() = prefs.getString(CURRENT, null)
        set(value) {
            prefs.edit().putString(CURRENT, value).apply()
        }

    /**
     * Adds the accounts found on this phone that the list does not know yet, in the order given, and returns the found
     * accounts in list order. Known accounts keep their place, so the primary account stays first.
     */
    fun merge(found: List<String>): List<String> {
        val list = order.toMutableList()
        found.filterNot(list::contains).forEach(list::add)
        order = list
        return list.filter(found::contains)
    }

    /** The account to show first: the one used last, else the oldest. */
    fun preferred(accounts: List<String>): List<String> {
        val current = current ?: return accounts
        if (current !in accounts) return accounts
        return listOf(current) + accounts.filter { it != current }
    }

    fun remove(userId: String) {
        order = order - userId
        if (current == userId) current = null
        prefs.edit().remove(OWNER + userId).apply()
    }

    // Company owners (bipe-chat/bipe#444)

    /** The personal account that made this company account on this phone. A phone-side grouping only (ADR 0138). */
    fun owner(companyId: String): String? = prefs.getString(OWNER + companyId, null)

    fun setOwner(ownerId: String, companyId: String) {
        prefs.edit().putString(OWNER + companyId, ownerId).apply()
    }

    /** The name each account showed last, so the company's Sua conta can name its owner. */
    fun rememberName(userId: String, name: String) {
        if (name.isNotBlank()) prefs.edit().putString(NAME + userId, name).apply()
    }

    /** The owner's name: only while the owner is on this phone and its name is known. */
    fun ownerName(companyId: String): String? {
        val owner = owner(companyId)?.takeIf { it in order } ?: return null
        return prefs.getString(NAME + owner, null)
    }

    private companion object {
        const val ORDER = "order"
        const val CURRENT = "current"
        const val OWNER = "owner_"
        const val NAME = "name_"
    }
}
