package ru.protolink.communicator.util

import java.util.concurrent.TimeUnit

/**
 * Open-chat history load contract (#android-messenger-messages-missing / Win MessengerChatLoad).
 * Paint Loading immediately on cold/miss; leave within Interactive budget — never blank chrome-only
 * while ConversationUserIds GetEntities is in flight. Soft-fail surfaces when empty+failed.
 */
object MessengerChatLoad {
    /**
     * Leave Loading within this wall-clock budget from contact select.
     * Device ConversationUserIds GE often 5–10s; align with contacts Interactive (12s), not Win 8s.
     */
    val InteractiveHttpTimeoutMs: Long = TimeUnit.SECONDS.toMillis(12)

    const val LoadingStatus = "Loading…"
    const val EmptyHint = "No messages yet."
    const val LoadFailedSoft = "Couldn't load messages."

    fun shouldShowLoading(isLoading: Boolean): Boolean = isLoading

    /** True empty thread after a finished load — never confuse with in-flight empty. */
    fun shouldShowEmpty(isLoading: Boolean, messageCount: Int): Boolean =
        !isLoading && messageCount <= 0

    /** Fire-and-forget loadMessages must clear loading for any fault. */
    fun shouldClearLoadingOnFault(ex: Throwable?): Boolean = ex != null

    /**
     * Cold/miss open: show Loading when a partner is selected and no last-good thread is painted yet.
     */
    fun shouldShowLoadingOnSelect(hasSelectedContact: Boolean, hasCachedThread: Boolean): Boolean =
        hasSelectedContact && !hasCachedThread
}
