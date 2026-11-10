package ru.protolink.communicator.util

/**
 * Notes-without-login shell policy (#notes-without-login).
 * Unsigned users keep the tab shell; Messenger / Cloud require login (Settings/Auth).
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

    /** After logout (or unsigned boot), bounce Messenger/Cloud selection to Notes. */
    fun resolveTabAfterAuthChange(authenticated: Boolean, currentTab: Int): Int =
        if (shouldPromptLoginForTab(authenticated, currentTab)) unsignedDefaultTab() else currentTab
}
