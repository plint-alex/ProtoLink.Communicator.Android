package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Messenger message Copy id (M13) — label + format gate (offline; no clipboard). */
class MessengerCopyIdTest {
    @Test
    fun copyIdLabel_matchesNotesCloudWording_notContactLabel() {
        assertThat(ShareAccountStrings.copyIdLabel("en")).isEqualTo("Copy id")
        assertThat(ShareAccountStrings.copyIdLabel("ru-RU")).isEqualTo("Копировать id")
        assertThat(ShareAccountStrings.copyIdLabel("en"))
            .isNotEqualTo(ShareAccountStrings.copyContactIdLabel("en"))
        assertThat(ShareAccountStrings.copyIdLabel("ru"))
            .isNotEqualTo(ShareAccountStrings.copyContactIdLabel("ru"))
    }

    @Test
    fun canCopyMessageEntityId_requiresLastingGuid() {
        assertThat(ShareAccountStrings.canCopyMessageEntityId(null)).isFalse()
        assertThat(ShareAccountStrings.canCopyMessageEntityId("")).isFalse()
        assertThat(ShareAccountStrings.canCopyMessageEntityId("local-123456789")).isFalse()
        assertThat(
            ShareAccountStrings.canCopyMessageEntityId("00000000-0000-0000-0000-000000000000")
        ).isFalse()
        assertThat(ShareAccountStrings.canCopyMessageEntityId("not-a-guid")).isFalse()

        val lasting = "A1B2C3D4-E5F6-7890-ABCD-EF1234567890"
        assertThat(ShareAccountStrings.canCopyMessageEntityId(lasting)).isTrue()
        assertThat(ShareAccountStrings.formatEntityIdForClipboard(lasting))
            .isEqualTo("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    }
}
