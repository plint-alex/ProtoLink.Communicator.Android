package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** UX-NS-1 — Notes Share… / Not in Cloud yet EN+RU. */
class ShareSheetStringsNotesMenuTest {
    @Test
    fun shareMenu_enRu() {
        assertThat(ShareSheetStrings.shareMenu("en-US")).isEqualTo("Share…")
        assertThat(ShareSheetStrings.shareMenu("ru-RU")).isEqualTo("Поделиться…")
    }

    @Test
    fun notInCloudYet_enRu() {
        assertThat(ShareSheetStrings.notInCloudYet("en")).isEqualTo("Not in Cloud yet")
        assertThat(ShareSheetStrings.notInCloudYet("ru")).isEqualTo("Ещё не в облаке")
    }
}
