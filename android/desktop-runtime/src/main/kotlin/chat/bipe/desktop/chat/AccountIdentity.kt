// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountLifetime
import chat.bipe.desktop.account.AccountResource
import chat.bipe.desktop.account.AccountTaskGroup
import chat.bipe.desktop.account.NativeResource
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

enum class AccountType { PERSONAL, COMPANY, UNKNOWN }

/** ADR0132: account type comes from the API, never a name, avatar, verification badge or localpart. */
class AccountIdentity private constructor(
    private val group: AccountTaskGroup,
    private val fetch: suspend (String) -> AccountType,
) : AccountResource {
    private val monitor = Any()
    private val cache = LinkedHashMap<String, AccountType>(16, 0.75f, true)
    private val requests = mutableMapOf<String, Deferred<AccountType>>()
    private val downloads = Semaphore(4)

    suspend fun type(userId: String): AccountType {
        group.requireCurrent()
        if (!userId.startsWith('@') || ':' !in userId) return AccountType.UNKNOWN
        val task = synchronized(monitor) {
            cache[userId]?.let { return it }
            requests[userId] ?: group.async {
                downloads.withPermit {
                    val value = try { fetch(userId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { AccountType.UNKNOWN }
                    group.requireCurrent()
                    if (value != AccountType.UNKNOWN) group.publish { synchronized(monitor) {
                        cache[userId] = value
                        while (cache.size > 200) cache.remove(cache.entries.iterator().next().key)
                    } }
                    value
                }
            }.also { created ->
                requests[userId] = created
                created.invokeOnCompletion { synchronized(monitor) { requests.remove(userId, created) } }
            }
        }
        val value = task.await()
        group.requireCurrent()
        return value
    }

    override suspend fun close() = group.close()

    companion object {
        suspend fun create(owner: AccountLifetime): AccountIdentity {
            val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build() }
            return create(owner) { userId -> runInterruptible {
                val id = URLEncoder.encode(userId, StandardCharsets.UTF_8).replace("+", "%20")
                val request = HttpRequest.newBuilder(URI("https://api.bipe.net.br/v1/accounts/$id/type"))
                    .timeout(Duration.ofSeconds(5)).GET().build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
                if (response.statusCode() == 200 && response.body().size <= 16 * 1024) decode(response.body().toString(Charsets.UTF_8))
                else AccountType.UNKNOWN
            } }
        }

        internal suspend fun create(owner: AccountLifetime, fetch: suspend (String) -> AccountType): AccountIdentity {
            val group = AccountTaskGroup.create(owner)
            val identity = AccountIdentity(group, fetch)
            group.own(NativeResource { synchronized(identity.monitor) { identity.cache.clear(); identity.requests.clear() } })
            return identity
        }

        internal fun decode(value: String): AccountType = runCatching {
            when (Json.parseToJsonElement(value).jsonObject["account_type"]?.jsonPrimitive?.takeIf { it.isString }?.content) {
                "company" -> AccountType.COMPANY
                "personal" -> AccountType.PERSONAL
                else -> AccountType.UNKNOWN
            }
        }.getOrDefault(AccountType.UNKNOWN)
    }
}
