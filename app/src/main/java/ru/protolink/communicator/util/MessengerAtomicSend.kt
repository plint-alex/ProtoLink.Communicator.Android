package ru.protolink.communicator.util

import com.google.gson.Gson
import com.google.gson.JsonParser
import ru.protolink.communicator.data.messenger.PendingAttachmentDraft

/**
 * Helpers for `POST api/Messenger/sendWithAttachments` (client-contract §9).
 * Api assigns new CloudFile ids — retry of new uploads is a full re-send.
 */
object MessengerAtomicSend {
    const val ENDPOINT_PATH = "api/Messenger/sendWithAttachments"

    private val gson = Gson()

    fun formatExistingAttachmentIds(ids: List<String>): String =
        gson.toJson(ids)

    fun parseResponse(json: String): Pair<String, List<String>> {
        val root = JsonParser.parseString(json).asJsonObject
        val messageId = root.get("messageId")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        if (messageId.isBlank()) {
            error("sendWithAttachments response missing messageId")
        }
        val attachmentIds = mutableListOf<String>()
        val arr = root.getAsJsonArray("attachmentIds")
        if (arr != null) {
            for (el in arr) {
                val id = el?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                if (id.isNotBlank()) attachmentIds.add(id)
            }
        }
        return messageId to attachmentIds
    }

    fun looksLikeFileTooLarge(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return message.contains("file_too_large", ignoreCase = true)
            || message.contains("413", ignoreCase = true)
    }

    fun prepareDraftsForAtomicRetry(drafts: List<PendingAttachmentDraft>): List<PendingAttachmentDraft> =
        drafts.map { d ->
            if (d.isCloudPick) {
                val id = MessengerSendStateMachine.resolveEntityIdForRetry(d.cloudFileEntityId, null)
                d.copy(cloudFileEntityId = id, uploadCompleted = !id.isNullOrBlank())
            } else {
                d.copy(cloudFileEntityId = null, uploadCompleted = false)
            }
        }

    fun collectExistingIds(drafts: List<PendingAttachmentDraft>): List<String> =
        drafts.mapNotNull { d ->
            if (d.isCloudPick) d.cloudFileEntityId?.takeIf { it.isNotBlank() } else null
        }

    fun collectNewUploads(drafts: List<PendingAttachmentDraft>): List<PendingAttachmentDraft> =
        drafts.filter { !it.isCloudPick }
}
