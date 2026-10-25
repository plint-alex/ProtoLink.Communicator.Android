package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.api.EntityDto

class CloudMainParentTest {
    @Test
    fun pickParent_prefersMainParentId() {
        val main = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val other = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val parents = listOf(
            EntityDto(id = other, code = CloudCodes.CLOUD_FOLDER),
            EntityDto(id = main, code = CloudCodes.CLOUD_FOLDER)
        )
        val picked = CloudMainParent.pickParent(main, parents)
        assertThat(picked).isNotNull()
        assertThat(picked!!.id).isEqualTo(main)
    }

    @Test
    fun pickParent_withoutMain_prefersCloudFolder() {
        val folder = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val parents = listOf(
            EntityDto(id = "dddddddd-dddd-4ddd-8ddd-dddddddddddd", code = "User"),
            EntityDto(id = folder, code = CloudCodes.CLOUD_FOLDER)
        )
        val picked = CloudMainParent.pickParent(null, parents)
        assertThat(picked!!.id).isEqualTo(folder)
    }

    @Test
    fun pickParent_empty_returnsNull() {
        assertThat(
            CloudMainParent.pickParent("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", emptyList())
        ).isNull()
    }
}
