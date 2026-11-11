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

    @Test
    fun displayName_folderWithoutValues_usesFolderFallback_notSkippedBySync() {
        val id = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        val entity = EntityDto(id = id, code = CloudCodes.CLOUD_FOLDER, values = null)
        val name = EntityMeta.displayName(entity)
        assertThat(name).isEqualTo("folder-${id.replace("-", "")}")
        assertThat(EntityMeta.isSyntheticCloudFileDisplayName(entity, name)).isFalse()
    }

    @Test
    fun syntheticCloudFile_skippedBySyncWalk_foldersKept() {
        val fileId = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        val file = EntityDto(id = fileId, code = CloudCodes.CLOUD_FILE, values = emptyList())
        val fileName = EntityMeta.displayName(file)
        assertThat(fileName).isEqualTo("file-${fileId.replace("-", "")}")
        assertThat(EntityMeta.isSyntheticCloudFileDisplayName(file, fileName)).isTrue()

        val folderId = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        val folder = EntityDto(id = folderId, code = CloudCodes.CLOUD_FOLDER, values = emptyList())
        val folderName = EntityMeta.displayName(folder)
        assertThat(EntityMeta.isSyntheticCloudFileDisplayName(folder, folderName)).isFalse()
    }
}
