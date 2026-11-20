package ru.protolink.communicator.data

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class CloudListingDiskCacheTest {
    private lateinit var dir: File
    private lateinit var cache: CloudListingDiskCache

    @Before
    fun setUp() {
        dir = File(
            System.getProperty("java.io.tmpdir"),
            "protolink-cloud-listing-disk-cache-${UUID.randomUUID()}"
        )
        dir.mkdirs()
        cache = CloudListingDiskCache(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun listing_saveAsap_thenLoad_roundTrips() {
        val folder = UUID.randomUUID().toString()
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        cache.saveAsap(
            folder,
            listOf(
                CloudListingDiskCache.CachedListingItem(
                    id = a,
                    name = "Notes",
                    isFolder = true,
                    mimeType = null,
                    updateTimeUtcMillis = 100L
                ),
                CloudListingDiskCache.CachedListingItem(
                    id = b,
                    name = "readme.txt",
                    isFolder = false,
                    mimeType = "text/plain",
                    updateTimeUtcMillis = 200L
                )
            )
        )

        val loaded = cache.tryLoad(folder)
        assertThat(loaded).hasSize(2)
        assertThat(loaded.any { it.id == a && it.name == "Notes" && it.isFolder }).isTrue()
        assertThat(loaded.any { it.id == b && it.mimeType == "text/plain" }).isTrue()
        assertThat(cache.tryLoad(UUID.randomUUID().toString())).isEmpty()
    }
}
