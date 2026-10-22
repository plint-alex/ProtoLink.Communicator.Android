package ru.protolink.communicator.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.protolink.communicator.util.CloudRecentList
import ru.protolink.communicator.util.CloudVirtualNodes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable local Recent list for Cloud (C6) — SharedPreferences keyed by user id (web twin).
 * No BE required.
 */
@Singleton
class CloudRecentStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val gson = Gson()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun load(userId: String): List<CloudRecentList.Entry> {
        val key = storageKey(userId) ?: return emptyList()
        synchronized(lock) {
            return loadUnlocked(key)
        }
    }

    fun recordOpen(userId: String, entry: CloudRecentList.Entry): List<CloudRecentList.Entry> {
        val key = storageKey(userId) ?: return emptyList()
        synchronized(lock) {
            val next = CloudRecentList.push(loadUnlocked(key), entry)
            saveUnlocked(key, next)
            return next
        }
    }

    fun remove(userId: String, entityId: String): List<CloudRecentList.Entry> {
        val key = storageKey(userId) ?: return emptyList()
        synchronized(lock) {
            val next = CloudRecentList.remove(loadUnlocked(key), entityId)
            saveUnlocked(key, next)
            return next
        }
    }

    fun replace(userId: String, entries: List<CloudRecentList.Entry>): List<CloudRecentList.Entry> {
        val key = storageKey(userId) ?: return emptyList()
        synchronized(lock) {
            val next = entries
                .filter { it.entityId.isNotBlank() && !CloudVirtualNodes.isVirtual(it.entityId) }
                .take(CloudRecentList.DEFAULT_CAPACITY)
            saveUnlocked(key, next)
            return next
        }
    }

    private fun loadUnlocked(key: String): List<CloudRecentList.Entry> {
        return try {
            val json = prefs.getString(key, null) ?: return emptyList()
            val rows = gson.fromJson(json, Array<Dto>::class.java)?.toList().orEmpty()
            rows.mapNotNull { r ->
                val id = r.entityId?.trim().orEmpty()
                if (id.isEmpty() || CloudVirtualNodes.isVirtual(id)) return@mapNotNull null
                CloudRecentList.Entry(
                    entityId = id,
                    name = r.name?.trim()?.ifEmpty { id } ?: id,
                    isFolder = r.isFolder,
                    openedUtcMillis = r.openedUtcMillis
                )
            }.take(CloudRecentList.DEFAULT_CAPACITY)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveUnlocked(key: String, entries: List<CloudRecentList.Entry>) {
        try {
            val dto = entries.map {
                Dto(
                    entityId = it.entityId,
                    name = it.name,
                    isFolder = it.isFolder,
                    openedUtcMillis = it.openedUtcMillis
                )
            }
            prefs.edit().putString(key, gson.toJson(dto)).apply()
        } catch (_: Exception) {
            // Best-effort; navigation must not fail.
        }
    }

    private fun storageKey(userId: String): String? {
        val uid = userId.trim().lowercase()
        if (uid.isEmpty()) return null
        return KEY_PREFIX + uid
    }

    private data class Dto(
        @SerializedName("entityId") val entityId: String? = null,
        @SerializedName("name") val name: String? = null,
        @SerializedName("isFolder") val isFolder: Boolean = false,
        @SerializedName("openedUtcMillis") val openedUtcMillis: Long = 0L
    )

    companion object {
        const val PREFS_NAME = "protolink_cloud_recent"
        const val KEY_PREFIX = "protolink-cloud-recent:"
    }
}
