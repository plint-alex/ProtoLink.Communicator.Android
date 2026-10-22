package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageStringValuesParseTest {
    private val attachmentId = "3f2b1c44-9a0e-4f2b-8b1e-7a0c5d2e9f10"
    private val attachmentId2 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    @Test
    fun prefixParsing_yieldsBodyAndOneAttachmentId() {
        val values = listOf(
            "sender:11111111-1111-1111-1111-111111111111",
            "receiver:22222222-2222-2222-2222-222222222222",
            "status:read",
            "attachment:$attachmentId",
            "hello body"
        )

        val parsed = MessageStringValues.parse(values)

        assertThat(parsed.bodyText).isEqualTo("hello body")
        assertThat(parsed.attachmentIds).containsExactly(attachmentId)
    }

    @Test
    fun severalAttachments_preservesOrder() {
        val values = listOf(
            "caption",
            "attachment:$attachmentId",
            "attachment:$attachmentId2"
        )

        val parsed = MessageStringValues.parse(values)

        assertThat(parsed.bodyText).isEqualTo("caption")
        assertThat(parsed.attachmentIds).containsExactly(attachmentId, attachmentId2).inOrder()
    }

    @Test
    fun malformedAttachment_isSkippedNotRenderedAsText() {
        val values = listOf(
            "sender:11111111-1111-1111-1111-111111111111",
            "attachment:not-a-guid",
            "attachment:",
            "real text",
            "attachment:$attachmentId"
        )

        val parsed = MessageStringValues.parse(values)

        assertThat(parsed.bodyText).isEqualTo("real text")
        assertThat(parsed.bodyText).doesNotContain("not-a-guid")
        assertThat(parsed.bodyText).doesNotContain("attachment:")
        assertThat(parsed.attachmentIds).containsExactly(attachmentId)
    }

    @Test
    fun attachmentOnly_yieldsEmptyBodyAndIds() {
        val parsed = MessageStringValues.parse(listOf("attachment:$attachmentId"))

        assertThat(parsed.bodyText).isEmpty()
        assertThat(parsed.attachmentIds).containsExactly(attachmentId)
    }
}
