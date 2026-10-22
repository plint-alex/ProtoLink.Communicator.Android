package ru.protolink.communicator.data.messenger

import retrofit2.HttpException
import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.SettingsStore
import ru.protolink.communicator.data.api.AddEntityRequest
import ru.protolink.communicator.data.api.AddValueRequest
import ru.protolink.communicator.data.api.GetEntitiesRequest
import ru.protolink.communicator.data.api.ProtoLinkApi
import ru.protolink.communicator.util.ChatFolderNaming
import ru.protolink.communicator.util.EntityMeta

/**
 * Cache-first chat folder under CloudRoot/Chats/{contact} (client-contract §4).
 * Never grants the folder to the peer — only CloudFile entities get Permissions on create.
 */
class MessengerChatFolderResolver(
    private val api: ProtoLinkApi,
    private val settingsStore: SettingsStore
) {
    fun cacheKey(ownerUserId: String, partnerUserId: String): String =
        "${ownerUserId.lowercase()}:${partnerUserId.lowercase()}"

    fun tryGetCached(ownerUserId: String, partnerUserId: String): String? {
        val key = cacheKey(ownerUserId, partnerUserId)
        val id = settingsStore.load().messengerChatFolderIds[key]
        return id?.takeIf { it.isNotBlank() }
    }

    fun setCached(ownerUserId: String, partnerUserId: String, folderId: String) {
        val key = cacheKey(ownerUserId, partnerUserId)
        val s = settingsStore.load()
        val map = s.messengerChatFolderIds.toMutableMap()
        map[key] = folderId
        settingsStore.save(s.copy(messengerChatFolderIds = map))
    }

    fun clearCached(ownerUserId: String, partnerUserId: String) {
        val key = cacheKey(ownerUserId, partnerUserId)
        val s = settingsStore.load()
        if (!s.messengerChatFolderIds.containsKey(key)) return
        val map = s.messengerChatFolderIds.toMutableMap()
        map.remove(key)
        settingsStore.save(s.copy(messengerChatFolderIds = map))
    }

    suspend fun resolveOrCreate(
        ownerUserId: String,
        partnerUserId: String,
        partnerDisplayName: String?
    ): String {
        val cached = tryGetCached(ownerUserId, partnerUserId)
        if (!cached.isNullOrBlank()) return cached
        return reResolveAndCreate(ownerUserId, partnerUserId, partnerDisplayName)
    }

    /** Single-call fallback after 403/404 on file create (stale cache). */
    suspend fun reResolveAndCreate(
        ownerUserId: String,
        partnerUserId: String,
        partnerDisplayName: String?
    ): String {
        clearCached(ownerUserId, partnerUserId)

        val cloudRootId = ensureCloudRoot(ownerUserId)
        val chatsId = ensureNamedFolder(cloudRootId, ChatFolderNaming.CHATS_FOLDER_NAME)

        val siblings = api.getEntities(
            GetEntitiesRequest(parentIds = listOf(chatsId), includeValues = true, take = 500)
        )
        val siblingNames = siblings
            .filter { it.code.equals(CloudCodes.CLOUD_FOLDER, ignoreCase = true) }
            .map { EntityMeta.displayName(it) }

        val folderName = ChatFolderNaming.chooseFolderName(partnerDisplayName, partnerUserId, siblingNames)
        val existing = siblings.firstOrNull { e ->
            e.code.equals(CloudCodes.CLOUD_FOLDER, ignoreCase = true) &&
                EntityMeta.displayName(e).equals(folderName, ignoreCase = true)
        }

        val folderId = existing?.id ?: addFolder(chatsId, folderName)
        if (folderId.isBlank()) error("Chat folder create returned empty id")
        setCached(ownerUserId, partnerUserId, folderId)
        return folderId
    }

    companion object {
        fun isStaleFolderFailure(ex: Throwable): Boolean {
            val http = ex as? HttpException
            if (http != null) {
                return http.code() == 403 || http.code() == 404
            }
            val msg = ex.message.orEmpty()
            return msg.contains("HTTP 403", ignoreCase = true) ||
                msg.contains("HTTP 404", ignoreCase = true) ||
                msg.contains(" 403", ignoreCase = true) ||
                msg.contains(" 404", ignoreCase = true)
        }
    }

    private suspend fun ensureCloudRoot(ownerUserId: String): String {
        val underUser = api.getEntities(
            GetEntitiesRequest(parentIds = listOf(ownerUserId), includeValues = false, take = 50)
        )
        val root = underUser.firstOrNull { it.code.equals(CloudCodes.CLOUD_ROOT, ignoreCase = true) }
        if (root?.id != null) return root.id
        val created = api.addEntity(
            AddEntityRequest(code = CloudCodes.CLOUD_ROOT, parentIds = listOf(ownerUserId))
        )
        return created.id ?: error("CloudRoot create returned empty id")
    }

    private suspend fun ensureNamedFolder(parentId: String, name: String): String {
        val children = api.getEntities(
            GetEntitiesRequest(parentIds = listOf(parentId), includeValues = true, take = 200)
        )
        val hit = children.firstOrNull { e ->
            e.code.equals(CloudCodes.CLOUD_FOLDER, ignoreCase = true) &&
                EntityMeta.displayName(e).equals(name, ignoreCase = true)
        }
        if (hit?.id != null) return hit.id
        return addFolder(parentId, name)
    }

    private suspend fun addFolder(parentId: String, name: String): String {
        val res = api.addEntity(
            AddEntityRequest(
                code = CloudCodes.CLOUD_FOLDER,
                parentIds = listOf(parentId),
                values = listOf(
                    AddValueRequest(value = name, parentIds = listOf(CloudCodes.NAME_TYPE_ID))
                )
            )
        )
        return res.id ?: error("AddEntity folder returned empty id")
    }
}
