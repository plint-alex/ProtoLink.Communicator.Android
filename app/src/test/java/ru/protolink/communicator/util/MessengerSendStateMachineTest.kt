package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.protolink.communicator.ui.DeliveryStatus

class MessengerSendStateMachineTest {
    @Test
    fun bubbleCreated_withAttachments_isUploading() {
        assertThat(MessengerSendStateMachine.onBubbleCreated(hasAttachments = true))
            .isEqualTo(DeliveryStatus.Uploading)
        assertThat(MessengerSendStateMachine.onBubbleCreated(hasAttachments = false))
            .isEqualTo(DeliveryStatus.Sending)
    }

    @Test
    fun happyPath_uploadThenSendThenSent() {
        assertThat(MessengerSendStateMachine.onUploadFinished()).isEqualTo(DeliveryStatus.Sending)
        assertThat(MessengerSendStateMachine.onMessageCommitted()).isEqualTo(DeliveryStatus.Sent)
    }

    @Test
    fun failureAndRetry() {
        assertThat(MessengerSendStateMachine.onFailure()).isEqualTo(DeliveryStatus.Failed)
        assertThat(MessengerSendStateMachine.onRetry()).isEqualTo(DeliveryStatus.Uploading)
    }

    @Test
    fun resolveEntityIdForRetry_keepsPrevious() {
        val prev = "3f2b1c44-9a0e-4f2b-8b1e-7a0c5d2e9f10"
        assertThat(MessengerSendStateMachine.resolveEntityIdForRetry(prev, "new-id")).isEqualTo(prev)
        assertThat(MessengerSendStateMachine.resolveEntityIdForRetry(null, "new-id")).isEqualTo("new-id")
        assertThat(MessengerSendStateMachine.resolveEntityIdForRetry("", null)).isNull()
    }
}
