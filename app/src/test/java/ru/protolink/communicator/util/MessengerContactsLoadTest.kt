package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonPrimitive
import org.junit.Test
import ru.protolink.communicator.data.api.EntityValueDto

/** Offline coverage for Messenger contacts startup (#messenger-startup-priority). */
class MessengerContactsLoadTest {
    @Test
    fun interactiveHttpTimeout_isBoundedForStartup() {
        assertThat(MessengerContactsLoad.InteractiveHttpTimeoutMs).isEqualTo(12_000L)
        assertThat(MessengerContactsLoad.InteractiveHttpTimeoutMs).isAtMost(15_000L)
        assertThat(MessengerContactsLoad.InteractiveHttpTimeoutMs).isAtLeast(8_000L)
    }

    @Test
    fun shouldShowLoading_tracksFlag() {
        assertThat(MessengerContactsLoad.shouldShowLoading(true)).isTrue()
        assertThat(MessengerContactsLoad.shouldShowLoading(false)).isFalse()
    }

    @Test
    fun shouldShowEmpty_onlyAfterFinishedWithZero() {
        assertThat(MessengerContactsLoad.shouldShowEmpty(true, 0)).isFalse()
        assertThat(MessengerContactsLoad.shouldShowEmpty(true, 3)).isFalse()
        assertThat(MessengerContactsLoad.shouldShowEmpty(false, 0)).isTrue()
        assertThat(MessengerContactsLoad.shouldShowEmpty(false, 2)).isFalse()
    }

    @Test
    fun tryParseContactUserId_fromUserIdValue() {
        val uid = "11111111-2222-4333-8444-555555555555"
        val values = listOf(EntityValueDto(value = JsonPrimitive("userid:$uid"), parents = null))
        assertThat(MessengerContactsLoad.tryParseContactUserId(values, null)).isEqualTo(uid)
    }

    @Test
    fun tryParseContactUserId_fromCodeFallback() {
        val uid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        assertThat(MessengerContactsLoad.tryParseContactUserId(null, uid)).isEqualTo(uid)
    }

    @Test
    fun tryParseContactUserId_rejectsEmpty() {
        assertThat(MessengerContactsLoad.tryParseContactUserId(null, null)).isNull()
        assertThat(
            MessengerContactsLoad.tryParseContactUserId(
                listOf(EntityValueDto(value = JsonPrimitive("not-a-userid"), parents = null)),
                "not-guid"
            )
        ).isNull()
    }

    @Test
    fun tryParseContactUserId_mapsSelfLoginEmailCode() {
        val selfId = "a52b6095-7952-4535-90a1-ce8bdaf7f9c7"
        val login = "alex.plint@gmail.com"
        assertThat(
            MessengerContactsLoad.tryParseContactUserId(
                values = null,
                code = login,
                selfUserId = selfId,
                selfLogin = login
            )
        ).isEqualTo(selfId)
        assertThat(
            MessengerContactsLoad.tryParseContactUserId(
                values = null,
                code = login,
                selfUserId = selfId,
                selfLogin = "other@example.com"
            )
        ).isNull()
    }
}
