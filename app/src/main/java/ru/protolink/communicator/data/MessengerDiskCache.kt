package ru.protolink.communicator.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import ru.protolink.communicator.util.PreferOnlineUiCache
import java.io.File

/**
 * Last-good Messenger contacts + open-chat pages for sick-connection paint.
 * Writes ASAP on successful Api fetch; never blocks UI (best-effort IO).
 * Parity with Windows MessengerDiskCache (filesDir instead of LocalAppData).
 */
class MessengerDiskCache(
    rootDir: File
) {
    private val root = rootDir.also { it.mkdirs() }
    private val gson = Gson()
    private val io = Any()

    data class CachedContact(
        val id: String,
        val name: String
    )

    data class CachedMessage(
        val entityId: String,
        val text: String,
        val senderName: String,
        val timestampUtcMillis: Long,
        val isFromMe: Boolean,
        val attachmentIds: List<String> = emptyList()
    )

    fun tryLoadContacts(ownerUserId: String): List<CachedContact> {
        if (ownerUserId.isBlank()) return emptyList()
        synchronized(io) {
            return try {
                val dto = readContactsFile(ownerUserId) ?: return emptyList()
                dto.contacts.orEmpty()
                    .filter { it.id.isNotBlank() }
                    .map { CachedContact(it.id, it.name.ifBlank { it.id }) }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** Last-good UserContacts bag id for StartupFast prefer-online cold start. */
    fun tryLoadUserContactsBagId(ownerUserId: String): String? {
        if (ownerUserId.isBlank()) return null
        synchronized(io) {
            return try {
                val dto = readContactsFile(ownerUserId) ?: return null
                if (!dto.ownerUserId.equals(ownerUserId, ignoreCase = true)) return null
                dto.userContactsBagId?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun saveContactsAsap(
        ownerUserId: String,
        contacts: Iterable<CachedContact>,
        userContactsBagId: String? = null
    ) {
        if (ownerUserId.isBlank()) return
        if (!PreferOnlineUiCache.shouldWriteCacheAsap(onlineFetchSucceeded = true)) return
        synchronized(io) {
            try {
                root.mkdirs()
                val priorBag = runCatching {
                    readContactsFile(ownerUserId)?.userContactsBagId?.takeIf { it.isNotBlank() }
                }.getOrNull()
                val bagId = userContactsBagId?.takeIf { it.isNotBlank() } ?: priorBag.orEmpty()
                val dto = ContactsFile(
                    ownerUserId = ownerUserId,
                    savedUtcMillis = System.currentTimeMillis(),
                    userContactsBagId = bagId,
                    contacts = contacts
                        .filter { it.id.isNotBlank() }
                        .map { ContactDto(id = it.id, name = it.name) }
                )
                contactsPath(ownerUserId).writeText(gson.toJson(dto))
            } catch (_: Exception) {
                // Best-effort; UI must not fail.
            }
        }
    }

    fun tryLoadMessages(ownerUserId: String, partnerUserId: String): List<CachedMessage> {
        if (ownerUserId.isBlank() || partnerUserId.isBlank()) return emptyList()
        synchronized(io) {
            return try {
                val path = messagesPath(ownerUserId, partnerUserId)
                if (!path.isFile) return emptyList()
                val dto = gson.fromJson(path.readText(), MessagesFile::class.java) ?: return emptyList()
                if (!dto.ownerUserId.equals(ownerUserId, ignoreCase = true) ||
                    !dto.partnerUserId.equals(partnerUserId, ignoreCase = true)
                ) {
                    return emptyList()
                }
                dto.messages.orEmpty()
                    .filter { it.entityId.isNotBlank() }
                    .map {
                        CachedMessage(
                            entityId = it.entityId,
                            text = it.text.orEmpty(),
                            senderName = it.senderName.orEmpty(),
                            timestampUtcMillis = it.timestampUtcMillis,
                            isFromMe = it.isFromMe,
                            attachmentIds = it.attachmentIds.orEmpty().filter { id -> id.isNotBlank() }
                        )
                    }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun saveMessagesAsap(
        ownerUserId: String,
        partnerUserId: String,
        messages: Iterable<CachedMessage>
    ) {
        if (ownerUserId.isBlank() || partnerUserId.isBlank()) return
        if (!PreferOnlineUiCache.shouldWriteCacheAsap(onlineFetchSucceeded = true)) return
        synchronized(io) {
            try {
                root.mkdirs()
                val dto = MessagesFile(
                    ownerUserId = ownerUserId,
                    partnerUserId = partnerUserId,
                    savedUtcMillis = System.currentTimeMillis(),
                    messages = messages.map {
                        MessageDto(
                            entityId = it.entityId,
                            text = it.text,
                            senderName = it.senderName,
                            timestampUtcMillis = it.timestampUtcMillis,
                            isFromMe = it.isFromMe,
                            attachmentIds = it.attachmentIds
                        )
                    }
                )
                messagesPath(ownerUserId, partnerUserId).writeText(gson.toJson(dto))
            } catch (_: Exception) {
                // Best-effort.
            }
        }
    }

    private fun readContactsFile(ownerUserId: String): ContactsFile? {
        val path = contactsPath(ownerUserId)
        if (!path.isFile) return null
        val dto = gson.fromJson(path.readText(), ContactsFile::class.java) ?: return null
        if (!dto.ownerUserId.equals(ownerUserId, ignoreCase = true)) return null
        return dto
    }

    private fun contactsPath(ownerUserId: String): File =
        File(root, "contacts-${normalizeId(ownerUserId)}.json")

    private fun messagesPath(ownerUserId: String, partnerUserId: String): File =
        File(root, "messages-${normalizeId(ownerUserId)}-${normalizeId(partnerUserId)}.json")

    private fun normalizeId(id: String): String = id.replace("-", "").lowercase()

    private data class ContactsFile(
        @SerializedName("ownerUserId") val ownerUserId: String = "",
        @SerializedName("savedUtcMillis") val savedUtcMillis: Long = 0L,
        @SerializedName("userContactsBagId") val userContactsBagId: String? = null,
        @SerializedName("contacts") val contacts: List<ContactDto>? = null
    )

    private data class ContactDto(
        @SerializedName("id") val id: String = "",
        @SerializedName("name") val name: String = ""
    )

    private data class MessagesFile(
        @SerializedName("ownerUserId") val ownerUserId: String = "",
        @SerializedName("partnerUserId") val partnerUserId: String = "",
        @SerializedName("savedUtcMillis") val savedUtcMillis: Long = 0L,
        @SerializedName("messages") val messages: List<MessageDto>? = null
    )

    private data class MessageDto(
        @SerializedName("entityId") val entityId: String = "",
        @SerializedName("text") val text: String? = null,
        @SerializedName("senderName") val senderName: String? = null,
        @SerializedName("timestampUtcMillis") val timestampUtcMillis: Long = 0L,
        @SerializedName("isFromMe") val isFromMe: Boolean = false,
        @SerializedName("attachmentIds") val attachmentIds: List<String>? = null
    )
}
