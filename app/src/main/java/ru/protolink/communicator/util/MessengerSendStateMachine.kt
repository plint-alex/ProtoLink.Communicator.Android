package ru.protolink.communicator.util

import ru.protolink.communicator.ui.DeliveryStatus

/**
 * Pure transitions for media send (client-contract §3 / §9). Offline-testable.
 * Cycle-2 atomic send: Failed → Retry is a full re-send for new uploads (Api assigns ids).
 * Cloud picks still reuse their existing entity id via [resolveEntityIdForRetry].
 */
object MessengerSendStateMachine {
    fun onBubbleCreated(hasAttachments: Boolean): DeliveryStatus =
        if (hasAttachments) DeliveryStatus.Uploading else DeliveryStatus.Sending

    fun onUploadFinished(): DeliveryStatus = DeliveryStatus.Sending

    fun onMessageCommitted(): DeliveryStatus = DeliveryStatus.Sent

    fun onFailure(): DeliveryStatus = DeliveryStatus.Failed

    fun onRetry(): DeliveryStatus = DeliveryStatus.Uploading

    /**
     * Retry must keep the CloudFile entity id from the failed attempt
     * (no second AddEntity for that file).
     */
    fun resolveEntityIdForRetry(previousEntityId: String?, newlyCreatedId: String?): String? {
        if (!previousEntityId.isNullOrBlank()) return previousEntityId
        return newlyCreatedId?.takeIf { it.isNotBlank() }
    }
}
