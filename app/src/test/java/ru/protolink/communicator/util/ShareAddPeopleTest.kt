package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Offline helpers for Share sheet Add people (UX §4). */
class ShareAddPeopleTest {
    @Test
    fun parseUserIdInput_acceptsGuidAndPath() {
        val id = "8f3a1111-2222-3333-4444-555555555555"
        assertThat(ShareAddPeople.parseUserIdInput(id)).isEqualTo(id)
        assertThat(
            ShareAddPeople.parseUserIdInput("https://protolink.ru/$id?lang=ru-RU")
        ).isEqualTo(id)
        assertThat(ShareAddPeople.parseUserIdInput("not-a-guid")).isNull()
        assertThat(ShareAddPeople.parseUserIdInput("")).isNull()
    }

    @Test
    fun pickResolvedDisplayName_rejectsGuidShaped() {
        val id = "8f3a1111-2222-3333-4444-555555555555"
        assertThat(ShareAddPeople.pickResolvedDisplayName(id, id)).isNull()
        assertThat(ShareAddPeople.pickResolvedDisplayName("Sergey L.", id)).isEqualTo("Sergey L.")
        assertThat(ShareAddPeople.pickResolvedDisplayName(null, "login")).isEqualTo("login")
    }

    @Test
    fun extractContactUserId_fromValueOrCode() {
        val uid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        assertThat(
            ShareAddPeople.extractContactUserId("nick", listOf("userid:$uid"))
        ).isEqualTo(uid)
        assertThat(ShareAddPeople.extractContactUserId(uid, emptyList())).isEqualTo(uid)
        assertThat(ShareAddPeople.extractContactUserId("nick", listOf("other"))).isNull()
    }

    @Test
    fun filterContacts_isClientSideCaseInsensitive() {
        data class Row(val name: String)
        val rows = listOf(Row("Maria K."), Row("Pavel D."), Row("Sergey L."))
        val filtered = ShareAddPeople.filterContacts(rows, { it.name }, "pav")
        assertThat(filtered).hasSize(1)
        assertThat(filtered[0].name).isEqualTo("Pavel D.")
        assertThat(ShareAddPeople.filterContacts(rows, { it.name }, null)).hasSize(3)
    }

    @Test
    fun isPeopleListPrincipal_excludesGuestLink() {
        assertThat(ShareAddPeople.isPeopleListPrincipal("user")).isTrue()
        assertThat(ShareAddPeople.isPeopleListPrincipal("public")).isTrue()
        assertThat(ShareAddPeople.isPeopleListPrincipal("guest_link")).isFalse()
        assertThat(ShareAddPeople.isPeopleListPrincipal("Guest_Link")).isFalse()
    }

    @Test
    fun hasAddTargets_requiresSelectionOrResolvedPaste() {
        assertThat(ShareAddPeople.hasAddTargets(emptyList(), null, null)).isFalse()
        assertThat(ShareAddPeople.hasAddTargets(listOf("a"), null, null)).isTrue()
        assertThat(ShareAddPeople.hasAddTargets(emptyList(), "uid", "Maria")).isTrue()
        assertThat(ShareAddPeople.hasAddTargets(emptyList(), "uid", null)).isFalse()
    }

    @Test
    fun addPeopleStrings_englishAndRussian() {
        assertThat(ShareSheetStrings.addPeopleButton("en")).isEqualTo("+ Add people")
        assertThat(ShareSheetStrings.addPeopleButton("ru-RU")).isEqualTo("+ Добавить людей")
        assertThat(ShareSheetStrings.canEditHelper("en")).contains("share")
        assertThat(ShareSheetStrings.addedToast("en", "Maria K.", canWrite = false))
            .contains("Maria K.")
        assertThat(ShareSheetStrings.addedToast("en", "Maria K.", canWrite = false).lowercase())
            .contains("view")
        assertThat(ShareSheetStrings.pasteIdHint("en").lowercase()).doesNotContain("token")
        assertThat(ShareSheetLogic.assertNoForbiddenUserText(ShareSheetStrings.canEditHelper("ru")))
            .isTrue()
    }
}
