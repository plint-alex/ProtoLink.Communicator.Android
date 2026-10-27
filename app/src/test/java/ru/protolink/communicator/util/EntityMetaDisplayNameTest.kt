package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonPrimitive
import org.junit.Test
import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.api.EntityDto
import ru.protolink.communicator.data.api.EntityValueDto

class EntityMetaDisplayNameTest {
    @Test
    fun displayName_prefersNameSlot_skipsMimeFirst() {
        // AddFile stamps mime before name — firstOrNull would show mime as title.
        val entity = EntityDto(
            id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            code = CloudCodes.CLOUD_FILE,
            values = listOf(
                EntityValueDto(
                    value = JsonPrimitive("image/png"),
                    parents = listOf(CloudCodes.MIME_TYPE_ID)
                ),
                EntityValueDto(
                    value = JsonPrimitive("vacation.png"),
                    parents = listOf(CloudCodes.NAME_TYPE_ID)
                )
            )
        )
        assertThat(EntityMeta.displayName(entity)).isEqualTo("vacation.png")
        assertThat(EntityMeta.mimeType(entity)).isEqualTo("image/png")
    }

    @Test
    fun displayName_neverUsesMimeAsTitle_whenNoNameSlot() {
        val entity = EntityDto(
            id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            code = CloudCodes.CLOUD_FILE,
            values = listOf(
                EntityValueDto(
                    value = JsonPrimitive("application/pdf"),
                    parents = listOf(CloudCodes.MIME_TYPE_ID)
                ),
                EntityValueDto(
                    value = JsonPrimitive("orphan-label"),
                    parents = listOf("cccccccc-cccc-cccc-cccc-cccccccccccc")
                )
            )
        )
        assertThat(EntityMeta.displayName(entity)).isEqualTo("orphan-label")
    }
}
