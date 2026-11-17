// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountCloseResult
import chat.bipe.shared.BipeOfficialAccounts
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AccountIdentityTest {
    @Test fun onlyExplicitApiTypesDetermineCompanyShape() {
        assertEquals(AccountType.COMPANY, AccountIdentity.decode("""{"account_type":"company"}"""))
        assertEquals(AccountType.PERSONAL, AccountIdentity.decode("""{"account_type":"personal"}"""))
        for (body in listOf("broken", "[]", "null", """{"name":"Company","verified":true}""", """{"account_type":true}""", """{"account_type":"future"}"""))
            assertEquals(AccountType.UNKNOWN, AccountIdentity.decode(body))
    }

    @Test fun requestsCoalesceAndKnownTypesStayInTheOwningAccountCache() = runBlocking {
        withMediaOwner { owner ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val identity = AccountIdentity.create(owner) { calls++; entered.complete(Unit); release.await(); AccountType.COMPANY }
            val first = async { identity.type("@company:example.test") }
            entered.await()
            val second = async { identity.type("@company:example.test") }
            release.complete(Unit)
            assertEquals(AccountType.COMPANY, first.await()); assertEquals(AccountType.COMPANY, second.await())
            assertEquals(AccountType.COMPANY, identity.type("@company:example.test"))
            assertEquals(1, calls)
        }
    }

    @Test fun unknownTypesAreNotCachedAndInvalidIdsNeverReachTheApi() = runBlocking {
        withMediaOwner { owner ->
            var calls = 0
            val identity = AccountIdentity.create(owner) { if (++calls == 1) AccountType.UNKNOWN else AccountType.COMPANY }
            assertEquals(AccountType.UNKNOWN, identity.type("Company"))
            assertEquals(0, calls)
            assertEquals(AccountType.UNKNOWN, identity.type("@company:example.test"))
            assertEquals(AccountType.COMPANY, identity.type("@company:example.test"))
            assertEquals(AccountType.COMPANY, identity.type("@company:example.test"))
            assertEquals(2, calls)
        }
    }

    @Test fun retiringAnAccountDropsItsLateTypeAndNeverReusesItForAnotherAccount() = runBlocking {
        withMediaOwner { first ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val identity = AccountIdentity.create(first) { withContext(NonCancellable) { entered.complete(Unit); release.await(); AccountType.COMPANY } }
            val request = async { runCatching { identity.type("@same:example.test") } }
            entered.await()
            val closing = first.closeAsync()
            release.complete(Unit)
            assertEquals(AccountCloseResult.Closed, closing.await())
            assertTrue(request.await().isFailure)
            withMediaOwner { second ->
                val other = AccountIdentity.create(second) { AccountType.PERSONAL }
                assertEquals(AccountType.PERSONAL, other.type("@same:example.test"))
            }
        }
    }

    @Test fun officialMarksRequireTheExactSharedUserId() {
        assertTrue(BipeOfficialAccounts.isOfficial("@bipe:bipe.net.br"))
        for (id in listOf(null, "Bipe", "bipe", "@bipe:example.test", "@bipe:bipe.net.br.example.test"))
            assertFalse(BipeOfficialAccounts.isOfficial(id))
    }
}
