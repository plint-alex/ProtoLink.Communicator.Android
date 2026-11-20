package ru.protolink.communicator.util

/**
 * Notes-without-login shell policy (#notes-without-login) + signed-in Messenger main tab
 * (#messenger-startup-priority). Unsigned users keep the tab shell; Messenger / Cloud require login.
 */
object AuthShellGate {
    const val MESSENGER_TAB = 0
    const val NOTES_TAB = 1
    const val CLOUD_TAB = 2

    fun tabRequiresLogin(tab: Int): Boolean =
        tab == MESSENGER_TAB || tab == CLOUD_TAB

    fun shouldPromptLoginForTab(authenticated: Boolean, tab: Int): Boolean =
        !authenticated && tabRequiresLogin(tab)

    fun unsignedDefaultTab(): Int = NOTES_TAB

    /** Cold start / after login: Messenger is the main signed-in tab (Job #2). */
    fun signedInDefaultTab(): Int = MESSENGER_TAB

    /**
     * Initial tab for a fresh shell: signed-in → Messenger; unsigned → Notes.
     * Does not bounce intentional Notes/Cloud selection while already authenticated.
     */
    fun resolveInitialTab(authenticated: Boolean): Int =
        if (authenticated) signedInDefaultTab() else unsignedDefaultTab()

    /**
     * After logout (or unsigned boot), bounce Messenger/Cloud selection to Notes.
     * Signed-in users keep their current tab (Notes stays reachable after cold Messenger land).
     */
    fun resolveTabAfterAuthChange(authenticated: Boolean, currentTab: Int): Int =
        if (shouldPromptLoginForTab(authenticated, currentTab)) unsignedDefaultTab() else currentTab
}
