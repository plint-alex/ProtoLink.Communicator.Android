package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonPrimitive
import org.junit.Test
import ru.protolink.communicator.data.api.EntityDto
import ru.protolink.communicator.data.api.EntityValueDto

class PhotosLibraryTest {
    @Test
    fun parseFolderId_readsPhotosfolderPointer() {
        val lib = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val values = listOf(
            EntityValueDto(value = JsonPrimitive("noise")),
            EntityValueDto(value = JsonPrimitive(PhotosLibrary.FOLDER_PREFIX + lib))
        )
        assertThat(PhotosLibrary.parseFolderId(values)).isEqualTo(lib)
    }

    @Test
    fun parseFolderId_missing_returnsNull() {
        assertThat(
            PhotosLibrary.parseFolderId(
                listOf(EntityValueDto(value = JsonPrimitive("photoselected:x")))
            )
        ).isNull()
        assertThat(PhotosLibrary.parseFolderId(null)).isNull()
    }

    @Test
    fun resolveLibraryIdFromUserChildren_findsPhotosHost() {
        val lib = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val kids = listOf(
            EntityDto(id = "cccccccc-cccc-4ccc-8ccc-cccccccccccc", code = "Friends"),
            EntityDto(
                id = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                code = PhotosLibrary.HOST_CODE,
                values = listOf(
                    EntityValueDto(value = JsonPrimitive(PhotosLibrary.FOLDER_PREFIX + lib))
                )
            )
        )
        assertThat(PhotosLibrary.resolveLibraryIdFromUserChildren(kids)).isEqualTo(lib)
    }
}
