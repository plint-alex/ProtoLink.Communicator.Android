package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Offline coverage for Notes-without-login shell gate (#notes-without-login). */
class AuthShellGateTest {
    @Test
    fun tabRequiresLogin_onlyMessengerAndCloud() {
        assertThat(AuthShellGate.tabRequiresLogin(AuthShellGate.MESSENGER_TAB)).isTrue()
        assertThat(AuthShellGate.tabRequiresLogin(AuthShellGate.CLOUD_TAB)).isTrue()
        assertThat(AuthShellGate.tabRequiresLogin(AuthShellGate.NOTES_TAB)).isFalse()
    }

    @Test
    fun shouldPromptLoginForTab_whenUnsignedMessengerOrCloud() {
        assertThat(AuthShellGate.shouldPromptLoginForTab(false, AuthShellGate.MESSENGER_TAB)).isTrue()
        assertThat(AuthShellGate.shouldPromptLoginForTab(false, AuthShellGate.CLOUD_TAB)).isTrue()
        assertThat(AuthShellGate.shouldPromptLoginForTab(false, AuthShellGate.NOTES_TAB)).isFalse()
        assertThat(AuthShellGate.shouldPromptLoginForTab(true, AuthShellGate.MESSENGER_TAB)).isFalse()
        assertThat(AuthShellGate.shouldPromptLoginForTab(true, AuthShellGate.CLOUD_TAB)).isFalse()
    }

    @Test
    fun resolveTabAfterAuthChange_bouncesLoginTabsToNotesWhenUnsigned() {
        assertThat(
            AuthShellGate.resolveTabAfterAuthChange(false, AuthShellGate.MESSENGER_TAB)
        ).isEqualTo(AuthShellGate.NOTES_TAB)
        assertThat(
            AuthShellGate.resolveTabAfterAuthChange(false, AuthShellGate.CLOUD_TAB)
        ).isEqualTo(AuthShellGate.NOTES_TAB)
        assertThat(
            AuthShellGate.resolveTabAfterAuthChange(false, AuthShellGate.NOTES_TAB)
        ).isEqualTo(AuthShellGate.NOTES_TAB)
        assertThat(
            AuthShellGate.resolveTabAfterAuthChange(true, AuthShellGate.MESSENGER_TAB)
        ).isEqualTo(AuthShellGate.MESSENGER_TAB)
    }

    @Test
    fun unsignedDefaultTab_isNotes() {
        assertThat(AuthShellGate.unsignedDefaultTab()).isEqualTo(AuthShellGate.NOTES_TAB)
    }
}
