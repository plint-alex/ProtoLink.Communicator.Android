package ru.protolink.communicator.util

/**
 * Conversation-scoped GetEntities for messenger messages (API ConversationUserIds).
 */
object MessengerConversationScope {
    /** Newest page size for one conversation; do not raise without paging older history. */
    const val MESSAGE_PAGE_SIZE = 100

    /** 1 id for self-chat; 2 ids for a pair. */
    fun conversationUserIds(me: String, partner: String): List<String> =
        if (me.equals(partner, ignoreCase = true)) listOf(me) else listOf(me, partner)
}
