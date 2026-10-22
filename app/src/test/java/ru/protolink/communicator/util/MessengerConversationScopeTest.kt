package ru.protolink.communicator.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessengerConversationScopeTest {
    @Test
    fun messagePageSize_isModest() {
        assertEquals(100, MessengerConversationScope.MESSAGE_PAGE_SIZE)
        assertTrue(MessengerConversationScope.MESSAGE_PAGE_SIZE <= 100)
    }

    @Test
    fun conversationUserIds_pair_returnsBoth() {
        val me = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val partner = "11111111-2222-3333-4444-555555555555"
        val ids = MessengerConversationScope.conversationUserIds(me, partner)
        assertEquals(2, ids.size)
        assertTrue(ids.contains(me))
        assertTrue(ids.contains(partner))
    }

    @Test
    fun conversationUserIds_selfChat_returnsOne() {
        val me = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val ids = MessengerConversationScope.conversationUserIds(me, me)
        assertEquals(listOf(me), ids)
    }

    @Test
    fun conversationUserIds_selfChat_caseInsensitive() {
        val me = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
        val ids = MessengerConversationScope.conversationUserIds(me, me.lowercase())
        assertEquals(1, ids.size)
    }
}
