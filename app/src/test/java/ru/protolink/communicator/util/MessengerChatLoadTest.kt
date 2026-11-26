package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessengerChatLoadTest {

    @Test
    fun interactiveBudget_coversSlowDeviceGe() {
        assertThat(MessengerChatLoad.InteractiveHttpTimeoutMs).isEqualTo(12_000L)
        assertThat(MessengerChatLoad.InteractiveHttpTimeoutMs).isAtMost(15_000L)
        assertThat(MessengerChatLoad.InteractiveHttpTimeoutMs).isAtLeast(8_000L)
    }

    @Test
    fun shouldShowLoading_tracksFlag() {
        assertThat(MessengerChatLoad.shouldShowLoading(true)).isTrue()
        assertThat(MessengerChatLoad.shouldShowLoading(false)).isFalse()
    }

    @Test
    fun shouldShowEmpty_neverWhileLoading() {
        assertThat(MessengerChatLoad.shouldShowEmpty(true, 0)).isFalse()
        assertThat(MessengerChatLoad.shouldShowEmpty(true, 3)).isFalse()
        assertThat(MessengerChatLoad.shouldShowEmpty(false, 0)).isTrue()
        assertThat(MessengerChatLoad.shouldShowEmpty(false, 2)).isFalse()
    }

    @Test
    fun shouldShowLoadingOnSelect_coldMissOnly() {
        assertThat(MessengerChatLoad.shouldShowLoadingOnSelect(true, false)).isTrue()
        assertThat(MessengerChatLoad.shouldShowLoadingOnSelect(true, true)).isFalse()
        assertThat(MessengerChatLoad.shouldShowLoadingOnSelect(false, false)).isFalse()
    }

    @Test
    fun shouldClearLoadingOnFault_anyThrowable() {
        assertThat(MessengerChatLoad.shouldClearLoadingOnFault(RuntimeException("x"))).isTrue()
        assertThat(MessengerChatLoad.shouldClearLoadingOnFault(null)).isFalse()
    }

    @Test
    fun chromeStrings_nonBlank() {
        assertThat(MessengerChatLoad.LoadingStatus).isEqualTo("Loading…")
        assertThat(MessengerChatLoad.LoadFailedSoft).isNotEmpty()
        assertThat(MessengerChatLoad.EmptyHint).isNotEmpty()
    }
}
