package ru.protolink.communicator.data

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class MessengerDiskCacheTest {
    private lateinit var dir: File
    private lateinit var cache: MessengerDiskCache

    @Before
    fun setUp() {
        dir = File(
            System.getProperty("java.io.tmpdir"),
            "protolink-messenger-disk-cache-${UUID.randomUUID()}"
        )
        dir.mkdirs()
        cache = MessengerDiskCache(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun contacts_saveAsap_thenLoad_roundTrips() {
        val owner = UUID.randomUUID().toString()
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val bag = UUID.randomUUID().toString()
        cache.saveContactsAsap(
            owner,
            listOf(
                MessengerDiskCache.CachedContact(a, "Alice"),
                MessengerDiskCache.CachedContact(b, "Bob")
            ),
            bag
        )

        val loaded = cache.tryLoadContacts(owner)
        assertThat(loaded).hasSize(2)
        assertThat(loaded.any { it.id == a && it.name == "Alice" }).isTrue()
        assertThat(loaded.any { it.id == b && it.name == "Bob" }).isTrue()
        assertThat(cache.tryLoadUserContactsBagId(owner)).isEqualTo(bag)
        assertThat(cache.tryLoadContacts(UUID.randomUUID().toString())).isEmpty()
        assertThat(cache.tryLoadUserContactsBagId(UUID.randomUUID().toString())).isNull()
    }

    @Test
    fun contacts_saveAsap_preservesBagId_whenPartnerBearingAndOmitted() {
        val owner = UUID.randomUUID().toString()
        val bag = UUID.randomUUID().toString()
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        cache.saveContactsAsap(
            owner,
            listOf(
                MessengerDiskCache.CachedContact(a, "A"),
                MessengerDiskCache.CachedContact(b, "B")
            ),
            bag
        )
        cache.saveContactsAsap(
            owner,
            listOf(
                MessengerDiskCache.CachedContact(a, "A"),
                MessengerDiskCache.CachedContact(b, "B")
            )
        )
        assertThat(cache.tryLoadUserContactsBagId(owner)).isEqualTo(bag)
    }

    @Test
    fun contacts_saveAsap_clearsThinBagAuthority() {
        val owner = UUID.randomUUID().toString()
        val bag = UUID.randomUUID().toString()
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        cache.saveContactsAsap(
            owner,
            listOf(
                MessengerDiskCache.CachedContact(a, "A"),
                MessengerDiskCache.CachedContact(b, "B")
            ),
            bag
        )
        assertThat(cache.tryLoadUserContactsBagId(owner)).isEqualTo(bag)

        // Self-only rewrite must drop sticky thin priorBag (UC_BAG_PICK_STARTUPFAST).
        cache.saveContactsAsap(owner, listOf(MessengerDiskCache.CachedContact(a, "A")), bag)
        assertThat(cache.tryLoadUserContactsBagId(owner)).isNull()
        assertThat(cache.tryLoadContacts(owner)).hasSize(1)
    }

    @Test
    fun messages_saveAsap_thenLoad_preferOnlineRoundTrip() {
        val owner = UUID.randomUUID().toString()
        val partner = UUID.randomUUID().toString()
        val msgId = UUID.randomUUID().toString()
        val att = UUID.randomUUID().toString()
        cache.saveMessagesAsap(
            owner,
            partner,
            listOf(
                MessengerDiskCache.CachedMessage(
                    entityId = msgId,
                    text = "hello",
                    senderName = "Alice",
                    timestampUtcMillis = 1_732_096_800_000L,
                    isFromMe = false,
                    attachmentIds = listOf(att)
                )
            )
        )

        val loaded = cache.tryLoadMessages(owner, partner)
        assertThat(loaded).hasSize(1)
        assertThat(loaded[0].entityId).isEqualTo(msgId)
        assertThat(loaded[0].text).isEqualTo("hello")
        assertThat(loaded[0].attachmentIds).containsExactly(att)
        assertThat(cache.tryLoadMessages(owner, UUID.randomUUID().toString())).isEmpty()
    }
}
