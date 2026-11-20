package ru.protolink.communicator.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import ru.protolink.communicator.util.PreferOnlineUiCache
import java.io.File

/**
 * Last-good Cloud folder listings for sick-connection paint.
 * Writes ASAP on successful listing; lazy deeper expand stays on live tree load.
 * Parity with Windows CloudListingDiskCache.
 */
class CloudListingDiskCache(
    rootDir: File
) {
    private val root = rootDir.also { it.mkdirs() }
    private val gson = Gson()
    private val io = Any()

    data class CachedListingItem(
        val id: String,
        val name: String,
        val isFolder: Boolean,
        val mimeType: String? = null,
        val updateTimeUtcMillis: Long = 0L
    )

    fun tryLoad(folderId: String): List<CachedListingItem> {
        if (folderId.isBlank()) return emptyList()
        synchronized(io) {
            return try {
                val path = pathFor(folderId)
                if (!path.isFile) return emptyList()
                val dto = gson.fromJson(path.readText(), ListingFile::class.java) ?: return emptyList()
                if (!dto.folderId.equals(folderId, ignoreCase = true)) return emptyList()
                dto.items.orEmpty()
                    .filter { it.id.isNotBlank() }
                    .map {
                        CachedListingItem(
                            id = it.id,
                            name = it.name.ifBlank { it.id },
                            isFolder = it.isFolder,
                            mimeType = it.mimeType,
                            updateTimeUtcMillis = it.updateTimeUtcMillis
                        )
                    }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun saveAsap(folderId: String, items: Iterable<CachedListingItem>) {
        if (folderId.isBlank()) return
        if (!PreferOnlineUiCache.shouldWriteCacheAsap(onlineFetchSucceeded = true)) return
        synchronized(io) {
            try {
                root.mkdirs()
                val dto = ListingFile(
                    folderId = folderId,
                    savedUtcMillis = System.currentTimeMillis(),
                    items = items
                        .filter { it.id.isNotBlank() }
                        .map {
                            ItemDto(
                                id = it.id,
                                name = it.name,
                                isFolder = it.isFolder,
                                mimeType = it.mimeType,
                                updateTimeUtcMillis = it.updateTimeUtcMillis
                            )
                        }
                )
                pathFor(folderId).writeText(gson.toJson(dto))
            } catch (_: Exception) {
                // Best-effort.
            }
        }
    }

    private fun pathFor(folderId: String): File =
        File(root, "listing-${folderId.replace("-", "").lowercase()}.json")

    private data class ListingFile(
        @SerializedName("folderId") val folderId: String = "",
        @SerializedName("savedUtcMillis") val savedUtcMillis: Long = 0L,
        @SerializedName("items") val items: List<ItemDto>? = null
    )

    private data class ItemDto(
        @SerializedName("id") val id: String = "",
        @SerializedName("name") val name: String = "",
        @SerializedName("isFolder") val isFolder: Boolean = false,
        @SerializedName("mimeType") val mimeType: String? = null,
        @SerializedName("updateTimeUtcMillis") val updateTimeUtcMillis: Long = 0L
    )
}
