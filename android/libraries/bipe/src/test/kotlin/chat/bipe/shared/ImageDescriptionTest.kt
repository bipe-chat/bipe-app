// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.media.BipeImageDescription
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ImageDescriptionTest {
    private val field = BipeRegistries.IMAGE_DESCRIPTION_FIELD

    @Test
    fun sendsTrimmedTextAndNothingWhenBlank() {
        assertThat(BipeImageDescription.extraContentJson("  ")).isNull()
        assertThat(BipeImageDescription.extraContentJson(null)).isNull()
        val json = Json.parseToJsonElement(BipeImageDescription.extraContentJson("  Um cachorro na praia \n")!!).jsonObject
        assertThat(json.keys).containsExactly(field)
        assertThat(json[field]!!.jsonPrimitive.content).isEqualTo("Um cachorro na praia")
    }

    @Test
    fun cutsAtTheLimit() {
        val long = "a".repeat(BipeRegistries.IMAGE_DESCRIPTION_MAX_LENGTH + 50)
        assertThat(BipeImageDescription.clean(long)!!.length).isEqualTo(BipeRegistries.IMAGE_DESCRIPTION_MAX_LENGTH)
    }

    @Test
    fun readsOnlyAStringOnAnImage() {
        val image = """{"type":"m.room.message","content":{"msgtype":"m.image","body":"a.jpg","$field":" Bolo de fubá "}}"""
        assertThat(BipeImageDescription.read(image)).isEqualTo("Bolo de fubá")
        assertThat(BipeImageDescription.read(image.replace("m.image", "m.video"))).isNull()
        assertThat(BipeImageDescription.read("""{"content":{"msgtype":"m.image","$field":42}}""")).isNull()
        assertThat(BipeImageDescription.read("not json")).isNull()
        assertThat(BipeImageDescription.read(null)).isNull()
    }
}
