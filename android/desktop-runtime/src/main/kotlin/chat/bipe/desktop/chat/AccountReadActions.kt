// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountLifetime
import chat.bipe.desktop.account.AccountTaskGroup
import chat.bipe.desktop.account.MatrixAccountRuntime
import chat.bipe.desktop.settings.MatrixSettingsReader
import chat.bipe.shared.BipeSyncedSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.ReceiptType

data class ReadIntent(val room: String, val unread: Boolean)
sealed interface ReadActionPhase {
    val failures: Map<String, ReadIntent>
    data class Idle(override val failures: Map<String, ReadIntent> = emptyMap()) : ReadActionPhase
    data class Sending(val intent: ReadIntent, override val failures: Map<String, ReadIntent>) : ReadActionPhase
}
internal fun interface ReadBackend { suspend fun apply(intent: ReadIntent) }

/** An explicit row command owns its original room/account. Read failure never blocks archive or navigation. */
class AccountReadActions private constructor(private val group: AccountTaskGroup, private val backend: ReadBackend) {
    private val mutablePhase = MutableStateFlow<ReadActionPhase>(ReadActionPhase.Idle())
    val phase = mutablePhase.asStateFlow()

    fun set(room: String, unread: Boolean) {
        if (room.isBlank()) return
        val intent = ReadIntent(room, unread)
        var accepted = false
        group.publish {
            val current = mutablePhase.value
            if (current is ReadActionPhase.Idle) {
                mutablePhase.value = ReadActionPhase.Sending(intent, current.failures - room); accepted = true
            }
        }
        if (!accepted) return
        group.launch {
            try {
                backend.apply(intent); group.requireCurrent()
                group.publish { mutablePhase.value = ReadActionPhase.Idle(mutablePhase.value.failures - room) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { group.publish { mutablePhase.value = ReadActionPhase.Idle(mutablePhase.value.failures + (room to intent)) } }
        }
    }
    fun retry() { (mutablePhase.value as? ReadActionPhase.Idle)?.failures?.values?.firstOrNull()?.let { set(it.room, it.unread) } }

    companion object {
        /** A malformed privacy event must never turn private DM receipts into public ones. */
        internal fun receipt(isDm: Boolean, raw: String?): ReceiptType {
            if (!isDm) return ReceiptType.READ
            val values = raw?.let { Json.parseToJsonElement(it).jsonObject }.orEmpty()
            val value = values[BipeSyncedSettings.READ_RECEIPTS]
            if (value != null) check(value is JsonPrimitive && !value.isString && value.booleanOrNull != null)
            return if (BipeSyncedSettings.bool(values, BipeSyncedSettings.READ_RECEIPTS) == false) ReceiptType.READ_PRIVATE else ReceiptType.READ
        }
        suspend fun open(runtime: MatrixAccountRuntime): AccountReadActions {
            val reader = MatrixSettingsReader(runtime.client)
            return create(runtime.lifetime, ReadBackend { intent ->
                val room = runtime.client.getRoom(intent.room) ?: error("Read room unavailable")
                try {
                    val info = room.roomInfo()
                    val isDm = try { check(info.membership == Membership.JOINED && !info.isSpace); info.isDm } finally { info.destroy() }
                    if (!intent.unread) {
                        val type = receipt(isDm, if (isDm) reader.accountData(BipeSyncedSettings.EVENT_TYPE) else null)
                        runtime.lifetime.requireCurrent()
                        room.markAsRead(type)
                    }
                    runtime.lifetime.requireCurrent()
                    room.setUnreadFlag(intent.unread)
                } finally { room.destroy() }
            })
        }
        internal suspend fun create(owner: AccountLifetime, backend: ReadBackend) = AccountReadActions(AccountTaskGroup.create(owner), backend)
    }
}
