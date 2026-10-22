package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.protolink.communicator.data.messenger.PendingAttachmentDraft

class MessengerAtomicSendTest {
    @Test
    fun formatExistingIds_jsonArray() {
        assertThat(MessengerAtomicSend.formatExistingAttachmentIds(emptyList())).isEqualTo("[]")
        val json = MessengerAtomicSend.formatExistingAttachmentIds(
            listOf("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222")
        )
        assertThat(json).contains("11111111-1111-1111-1111-111111111111")
        assertThat(json).contains("22222222-2222-2222-2222-222222222222")
    }

    @Test
    fun parseResponse_messageAndAttachments() {
        val (messageId, ids) = MessengerAtomicSend.parseResponse(
            """{"messageId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","attachmentIds":["3f2b1c44-9a0e-4f2b-8b1e-7a0c5d2e9f10"]}"""
        )
        assertThat(messageId).isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertThat(ids).containsExactly("3f2b1c44-9a0e-4f2b-8b1e-7a0c5d2e9f10")
    }

    @Test
    fun prepareRetry_clearsNewUpload_keepsCloudPick() {
        val pickId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val drafts = listOf(
            PendingAttachmentDraft(
                localUri = "file:///a.png",
                fileName = "a.png",
                mimeType = "image/png",
                byteLength = 10,
                cloudFileEntityId = "11111111-1111-1111-1111-111111111111",
                uploadCompleted = false,
                isCloudPick = false
            ),
            PendingAttachmentDraft(
                localUri = "",
                fileName = "cloud.bin",
                mimeType = "application/octet-stream",
                byteLength = 0,
                cloudFileEntityId = pickId,
                uploadCompleted = true,
                isCloudPick = true
            )
        )
        val prepared = MessengerAtomicSend.prepareDraftsForAtomicRetry(drafts)
        assertThat(prepared[0].cloudFileEntityId).isNull()
        assertThat(prepared[0].uploadCompleted).isFalse()
        assertThat(prepared[1].cloudFileEntityId).isEqualTo(pickId)
        assertThat(prepared[1].uploadCompleted).isTrue()
    }
}
