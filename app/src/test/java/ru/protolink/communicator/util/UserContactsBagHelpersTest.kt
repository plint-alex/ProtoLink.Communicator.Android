package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonPrimitive
import org.junit.Test
import ru.protolink.communicator.data.api.EntityDto
import ru.protolink.communicator.data.api.EntityValueDto

class UserContactsBagHelpersTest {
    @Test
    fun shouldCreateBag_whenEmpty() {
        assertThat(UserContactsBagHelpers.shouldCreateBag(null)).isTrue()
        assertThat(UserContactsBagHelpers.shouldCreateBag(emptyList())).isTrue()
    }

    @Test
    fun shouldCreateBag_falseWhenAnyId() {
        val bags = listOf(EntityDto(id = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", code = "UserContacts"))
        assertThat(UserContactsBagHelpers.shouldCreateBag(bags)).isFalse()
    }

    @Test
    fun pickBagId_oldestWhenNoCounts() {
        val a = UserContactsBagHelpers.BagSnapshot(
            id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            creationTime = "2024-01-02T00:00:00Z"
        )
        val b = UserContactsBagHelpers.BagSnapshot(
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            creationTime = "2024-01-01T00:00:00Z"
        )
        assertThat(UserContactsBagHelpers.pickBagId(listOf(a, b)))
            .isEqualTo("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    }

    @Test
    fun pickBagId_prefersRicherWhenCountsKnown() {
        val thin = UserContactsBagHelpers.BagSnapshot(
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            creationTime = "2024-01-01T00:00:00Z",
            personRowCount = 1
        )
        val rich = UserContactsBagHelpers.BagSnapshot(
            id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            creationTime = "2024-01-02T00:00:00Z",
            personRowCount = 5
        )
        assertThat(UserContactsBagHelpers.pickBagId(listOf(thin, rich)))
            .isEqualTo("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    }

    @Test
    fun startupFastFirstPage_insufficientWhenFull_tinaOffPage() {
        val full = (1..UserContactsBagHelpers.ListPageSize).map { i ->
            EntityDto(
                id = "00000000-0000-4000-8000-${i.toString().padStart(12, '0')}",
                code = "UserContacts"
            )
        }
        assertThat(UserContactsBagHelpers.canPickFromStartupFastFirstPage(full)).isFalse()
        assertThat(UserContactsBagHelpers.canPickFromStartupFastFirstPage(full.take(3))).isTrue()
        assertThat(UserContactsBagHelpers.canPickFromStartupFastFirstPage(emptyList())).isTrue()
    }

    @Test
    fun isListPageFull_atPageSize() {
        val page = (1..50).map {
            EntityDto(id = "id-$it", code = "UserContacts")
        }
        assertThat(UserContactsBagHelpers.isListPageFull(page)).isTrue()
        assertThat(UserContactsBagHelpers.isListPageFull(page.take(49))).isFalse()
    }

    @Test
    fun orderBagsForEnrich_oldestFirst() {
        val newer = UserContactsBagHelpers.BagSnapshot(
            id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            creationTime = "2025-01-02T00:00:00Z"
        )
        val older = UserContactsBagHelpers.BagSnapshot(
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            creationTime = "2024-01-01T00:00:00Z"
        )
        val ordered = UserContactsBagHelpers.orderBagsForEnrich(listOf(newer, older))
        assertThat(ordered.map { it.id }).containsExactly(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        ).inOrder()
    }

    @Test
    fun hasPartnerBearingBag_threshold() {
        val selfOnly = listOf(
            UserContactsBagHelpers.BagSnapshot(id = "a", personRowCount = 1)
        )
        val partner = listOf(
            UserContactsBagHelpers.BagSnapshot(id = "a", personRowCount = 1),
            UserContactsBagHelpers.BagSnapshot(id = "b", personRowCount = 3)
        )
        assertThat(UserContactsBagHelpers.hasPartnerBearingBag(selfOnly)).isFalse()
        assertThat(UserContactsBagHelpers.hasPartnerBearingBag(partner)).isTrue()
    }

    @Test
    fun pickBagId_prefersPartnerBearingOverThinNewest() {
        // Newest-first thin spawn vs older Tina bag — enrich counts must win.
        val thinNewest = UserContactsBagHelpers.BagSnapshot(
            id = "960f129e-57b8-4654-adb1-a42dc5deec3c",
            creationTime = "2025-11-20T00:00:00Z",
            personRowCount = 1
        )
        val tinaBag = UserContactsBagHelpers.BagSnapshot(
            id = "255e17ed-f620-4e0b-8f00-4cf778b513ee",
            creationTime = "2024-06-01T00:00:00Z",
            personRowCount = 3
        )
        assertThat(UserContactsBagHelpers.pickBagId(listOf(thinNewest, tinaBag)))
            .isEqualTo("255e17ed-f620-4e0b-8f00-4cf778b513ee")
    }

    @Test
    fun mergeBagPages_dedupesById() {
        val a = EntityDto(id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", code = "UserContacts")
        val b = EntityDto(id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", code = "UserContacts")
        val merged = UserContactsBagHelpers.mergeBagPages(listOf(a), listOf(a, b))
        assertThat(merged.map { it.id }).containsExactly(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        ).inOrder()
    }

    @Test
    fun countDistinctContactUserIds_duplicateSelfIsNotPartnerBearing() {
        // Bag 9a58b29e-style: kids=2 raw but both alex — must not trip PartnerRowThreshold.
        val selfId = "a52b6095-7952-4535-90a1-ce8bdaf7f9c7"
        val kids = listOf(
            EntityDto(
                id = "11111111-1111-4111-8111-111111111111",
                code = "alex.plint@gmail.com",
                values = listOf(EntityValueDto(value = JsonPrimitive("userid:$selfId"), parents = null))
            ),
            EntityDto(
                id = "22222222-2222-4222-8222-222222222222",
                code = "alex.plint@gmail.com",
                values = listOf(EntityValueDto(value = JsonPrimitive("userid:$selfId"), parents = null))
            )
        )
        val distinct = UserContactsBagHelpers.countDistinctContactUserIds(kids)
        assertThat(distinct).isEqualTo(1)
        assertThat(distinct >= UserContactsBagHelpers.PartnerRowThreshold).isFalse()
    }

    @Test
    fun countDistinctContactUserIds_tinaPlusSelfIsPartnerBearing() {
        val selfId = "a52b6095-7952-4535-90a1-ce8bdaf7f9c7"
        val tinaId = "3a9a71a3-8c00-4b68-bf41-99f4a12245f1"
        val kids = listOf(
            EntityDto(
                id = "11111111-1111-4111-8111-111111111111",
                code = tinaId,
                values = listOf(EntityValueDto(value = JsonPrimitive("userid:$tinaId"), parents = null))
            ),
            EntityDto(
                id = "22222222-2222-4222-8222-222222222222",
                code = "alex.plint@gmail.com",
                values = listOf(EntityValueDto(value = JsonPrimitive("userid:$selfId"), parents = null))
            )
        )
        val distinct = UserContactsBagHelpers.countDistinctContactUserIds(kids)
        assertThat(distinct).isEqualTo(2)
        assertThat(distinct >= UserContactsBagHelpers.PartnerRowThreshold).isTrue()
    }

    @Test
    fun countDistinctContactUserIds_emptyValuesSelfLoginCode() {
        // Bag 0559e5ef-style: Tina GUID code + alex email code, empty values.
        val selfId = "a52b6095-7952-4535-90a1-ce8bdaf7f9c7"
        val tinaId = "3a9a71a3-8c00-4b68-bf41-99f4a12245f1"
        val login = "alex.plint@gmail.com"
        val kids = listOf(
            EntityDto(id = "11111111-1111-4111-8111-111111111111", code = tinaId, values = emptyList()),
            EntityDto(id = "22222222-2222-4222-8222-222222222222", code = login, values = emptyList())
        )
        assertThat(
            UserContactsBagHelpers.countDistinctContactUserIds(kids, selfId, login)
        ).isEqualTo(2)
        assertThat(
            UserContactsBagHelpers.countDistinctContactUserIds(kids)
        ).isEqualTo(1) // without selfLogin, email code does not parse
    }
}
