package ru.protolink.communicator.util

import ru.protolink.communicator.ui.MessageItem

/**
 * Soft-merge helpers so conversation reload cannot blank a just-sent attachment bubble
 * (Wave B File… empty-green race: Sent → loadMessages cancels/clobbers attachmentIds /
 * pendingLocalAttachments before remote settle finishes).
 */
object MessengerConversationMerge {
    /**
     * Keep prior attachment ids and local draft metadata when the server row for the same
     * message id arrives thinner (empty attachmentIds) or without client-only fields.
     */
    fun retainClientAttachmentState(prev: MessageItem?, server: MessageItem): MessageItem {
        if (prev == null) return server
        if (!prev.id.equals(server.id, ignoreCase = true)) return server

        val ids =
            if (server.attachmentIds.isEmpty() && prev.attachmentIds.isNotEmpty()) {
                prev.attachmentIds
            } else {
                server.attachmentIds
            }

        val pending =
            when {
                !prev.pendingLocalAttachments.isNullOrEmpty() -> prev.pendingLocalAttachments
                else -> server.pendingLocalAttachments
            }

        if (ids == server.attachmentIds && pending == server.pendingLocalAttachments) {
            return server
        }
        return server.copy(attachmentIds = ids, pendingLocalAttachments = pending)
    }

    fun mergeServerAndPending(
        previous: List<MessageItem>,
        serverMessages: List<MessageItem>,
        pendingForChat: List<MessageItem>
    ): List<MessageItem> {
        val prevById = previous.associateBy { it.id.lowercase() }
        val enriched = serverMessages.map { retainClientAttachmentState(prevById[it.id.lowercase()], it) }
        return (enriched + pendingForChat)
            .distinctBy { it.id.lowercase() }
            .sortedWith(compareBy({ it.timestampMillis }, { it.id }))
    }
}
