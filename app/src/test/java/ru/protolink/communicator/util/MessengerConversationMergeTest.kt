package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.protolink.communicator.data.messenger.PendingAttachmentDraft
import ru.protolink.communicator.ui.DeliveryStatus
import ru.protolink.communicator.ui.MessageItem

class MessengerConversationMergeTest {
    private val attachmentId = "3f2b1c44-9a0e-4f2b-8b1e-7a0c5d2e9f10"
    private val messageId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    private fun draft(name: String = "waveb-messenger-test.pdf") =
        PendingAttachmentDraft(
            localUri = "content://test/$name",
            fileName = name,
            mimeType = "application/pdf",
            byteLength = 1234L,
            cloudFileEntityId = attachmentId,
            uploadCompleted = true
        )

    private fun sentWithAttachment() =
        MessageItem(
            id = messageId,
            text = "",
            mine = true,
            timestampMillis = 1_000L,
            deliveryStatus = DeliveryStatus.Sent,
            attachmentIds = listOf(attachmentId),
            pendingLocalAttachments = listOf(draft())
        )

    @Test
    fun retain_keepsAttachmentIdsWhenServerRowEmpty() {
        val prev = sentWithAttachment()
        val server = MessageItem(
            id = messageId,
            text = "",
            mine = true,
            timestampMillis = 1_000L,
            deliveryStatus = DeliveryStatus.Sent,
            attachmentIds = emptyList()
        )
        val merged = MessengerConversationMerge.retainClientAttachmentState(prev, server)
        assertThat(merged.attachmentIds).containsExactly(attachmentId)
        assertThat(merged.pendingLocalAttachments).isNotEmpty()
        assertThat(merged.pendingLocalAttachments!![0].fileName).isEqualTo("waveb-messenger-test.pdf")
    }

    @Test
    fun retain_prefersServerAttachmentIdsWhenPresent() {
        val other = "11111111-2222-3333-4444-555555555555"
        val prev = sentWithAttachment()
        val server = MessageItem(
            id = messageId,
            text = "",
            mine = true,
            timestampMillis = 1_000L,
            deliveryStatus = DeliveryStatus.Sent,
            attachmentIds = listOf(other)
        )
        val merged = MessengerConversationMerge.retainClientAttachmentState(prev, server)
        assertThat(merged.attachmentIds).containsExactly(other)
        assertThat(merged.pendingLocalAttachments).isNotEmpty()
    }

    @Test
    fun mergeServerAndPending_doesNotBlankJustSentFileRow() {
        val prev = listOf(sentWithAttachment())
        val serverThin = listOf(
            MessageItem(
                id = messageId,
                text = "",
                mine = true,
                timestampMillis = 1_000L,
                deliveryStatus = DeliveryStatus.Sent,
                attachmentIds = emptyList()
            )
        )
        val merged = MessengerConversationMerge.mergeServerAndPending(prev, serverThin, emptyList())
        assertThat(merged).hasSize(1)
        assertThat(merged[0].attachmentIds).containsExactly(attachmentId)
        assertThat(merged[0].pendingLocalAttachments!!.map { it.fileName })
            .containsExactly("waveb-messenger-test.pdf")
    }
}
