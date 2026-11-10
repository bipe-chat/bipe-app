// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.app.common.VectorDiff
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatSnapshotsTest {
    @Test fun virtualItemsKeepSdkIndicesEvenWhenTheyAreNotRendered() {
        val vector = SnapshotVector<String>()
        vector.apply(VectorDiff.Reset(listOf(null, "first", null, "second")))
        vector.apply(VectorDiff.Set(3, "changed"))
        vector.apply(VectorDiff.Remove(1))
        vector.apply(VectorDiff.Insert(1, "inserted"))
        assertEquals(listOf("inserted", "changed"), vector.visible())
    }

    @Test fun appendAndResetConvertEveryOwnedNativeValue() = runBlocking {
        val visited = mutableListOf<Int>()
        val mapped = VectorDiff.Reset(listOf(1, 2, 3)).snapshot { visited += it; if (it == 2) null else it.toString() }
        val vector = SnapshotVector<String>()
        vector.apply(mapped)
        assertEquals(listOf(1, 2, 3), visited)
        assertEquals(listOf("1", "3"), vector.visible())
    }

    @Test fun disposalVisitsOnlyValuesCarriedByAnUpdate() {
        val visited = mutableListOf<Int>()
        val changes = listOf(VectorDiff.Append(listOf(1, 2)), VectorDiff.Set(0, 3), VectorDiff.Remove(1), VectorDiff.Clear)
        changes.forEach { it.forEachValue(visited::add) }
        assertEquals(listOf(1, 2, 3), visited)
    }

    @Test fun peopleNamesNeverExposeTheMatrixIdOrItsDefaultLocalpart() {
        for (raw in listOf(null, "", "@person:example.test", "person", "!room:example.test", "#alias:example.test")) {
            assertEquals("placeholder", realName(raw, "@person:example.test", "placeholder"))
        }
        assertEquals("Pessoa", realName(" Pessoa ", "@person:example.test", "placeholder"))
    }
}
