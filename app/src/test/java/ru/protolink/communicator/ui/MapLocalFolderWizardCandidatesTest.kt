package ru.protolink.communicator.ui

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonPrimitive
import org.junit.Test
import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.api.EntityDto
import ru.protolink.communicator.data.api.EntityValueDto
import ru.protolink.communicator.util.MapLocalFolderStrings

/** Offline AT-NMR-7: interactive-bounded wizard candidates (no SameNamed cross-root). */
class MapLocalFolderWizardCandidatesTest {
    private fun folder(id: String, name: String) = EntityDto(
        id = id,
        code = CloudCodes.CLOUD_FOLDER,
        values = listOf(
            EntityValueDto(
                value = JsonPrimitive(name),
                parents = listOf(CloudCodes.NAME_TYPE_ID)
            )
        )
    )

    @Test
    fun fromParentListing_skipsMappedAndCloudRoot() {
        val mappedId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val keepId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        val rootId = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        val entities = listOf(
            folder(mappedId, "Mapped"),
            folder(keepId, "Keep"),
            EntityDto(
                id = rootId,
                code = CloudCodes.CLOUD_ROOT,
                values = listOf(
                    EntityValueDto(
                        value = JsonPrimitive("Root"),
                        parents = listOf(CloudCodes.NAME_TYPE_ID)
                    )
                )
            )
        )

        val result = MapLocalFolderWizardCandidates.fromParentListing(
            entities,
            setOf(mappedId)
        )

        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(keepId)
        assertThat(result[0].name).isEqualTo("Keep")
    }

    @Test
    fun fromParentListing_disambiguatesDuplicateNames() {
        val a = "11111111-1111-1111-1111-111111111111"
        val b = "22222222-2222-2222-2222-222222222222"
        val result = MapLocalFolderWizardCandidates.fromParentListing(
            listOf(folder(a, "Twin"), folder(b, "Twin")),
            emptySet()
        )

        assertThat(result).hasSize(2)
        assertThat(result.all { it.name.contains("Twin", ignoreCase = true) }).isTrue()
        assertThat(result.any { it.name.contains('·') }).isTrue()
    }

    @Test
    fun preferSameNamedUnderParent_findsCaseInsensitiveMatch() {
        val id = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        val hit = MapLocalFolderWizardCandidates.preferSameNamedUnderParent(
            "MyNotesFolder",
            listOf(folder(id, "mynotesfolder"))
        )

        assertThat(hit).isNotNull()
        assertThat(hit!!.first).isEqualTo(id)
    }

    @Test
    fun preferSameNamedUnderParent_returnsNullWhenMissing() {
        val hit = MapLocalFolderWizardCandidates.preferSameNamedUnderParent(
            "Missing",
            listOf(folder("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee", "Other"))
        )
        assertThat(hit).isNull()
    }

    @Test
    fun strings_wizardOpeningAndFailed_enAndRu() {
        assertThat(MapLocalFolderStrings.wizardOpening("en"))
            .contains("Opening map wizard")
        assertThat(MapLocalFolderStrings.wizardOpening("ru")).contains("мастера")
        assertThat(MapLocalFolderStrings.wizardOpenFailed("en"))
            .contains("Could not open")
        assertThat(MapLocalFolderStrings.wizardOpenFailed("ru")).contains("мастер")
        assertThat(MapLocalFolderStrings.wizardParentMissing("en"))
            .contains("My Cloud")
    }
}
