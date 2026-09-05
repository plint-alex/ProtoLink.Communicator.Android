package ru.protolink.communicator.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.protolink.communicator.BuildConfig
import ru.protolink.communicator.data.AppSettings
import ru.protolink.communicator.data.AuthRepository
import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.CloudSyncMappingDto
import ru.protolink.communicator.data.MappingStore
import ru.protolink.communicator.data.NotesTreeBuilder
import ru.protolink.communicator.data.SettingsStore
import ru.protolink.communicator.data.SignalRService
import ru.protolink.communicator.data.SystemEntities
import ru.protolink.communicator.data.api.AddEntityRequest
import ru.protolink.communicator.data.api.AddValueRequest
import ru.protolink.communicator.data.api.EntityDto
import ru.protolink.communicator.data.api.GetEntitiesRequest
import ru.protolink.communicator.data.api.ProtoLinkApi
import ru.protolink.communicator.data.api.SendCommandRequest
import ru.protolink.communicator.sync.engine.ContentHashUtil
import ru.protolink.communicator.sync.engine.SyncEngine
import ru.protolink.communicator.sync.model.SyncMapping
import ru.protolink.communicator.sync.ports.MetadataStore
import ru.protolink.communicator.syncadapt.ApiRemoteCloud
import ru.protolink.communicator.syncadapt.SafLocalFileSystem
import ru.protolink.communicator.syncadapt.SyncFlight
import ru.protolink.communicator.util.ChatTime
import ru.protolink.communicator.util.JsonValues
import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject

data class CloudItem(val id: String, val name: String, val isFolder: Boolean)
data class ContactItem(val userId: String, val displayName: String)
data class MessageItem(
    val id: String,
    val text: String,
    val mine: Boolean,
    val timestampMillis: Long
) {
    val timeLabel: String get() = ru.protolink.communicator.util.ChatTime.formatTime(timestampMillis)
    val dayLabel: String get() = ru.protolink.communicator.util.ChatTime.formatDayLabel(timestampMillis)
}
data class NoteTreeRow(
    val documentId: String,
    val safDocumentId: String,
    val name: String,
    val relativePath: String,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
    val folderUri: String,
    val indexUri: String?
)

data class PendingForce(
    val cloudFolderId: String,
    val push: Boolean
)

data class UiState(
    val authenticated: Boolean = false,
    val login: String = "",
    val status: String = "",
    val settings: AppSettings = AppSettings(),
    val cloudItems: List<CloudItem> = emptyList(),
    val breadcrumb: List<Pair<String, String>> = emptyList(),
    val mappings: List<CloudSyncMappingDto> = emptyList(),
    val contacts: List<ContactItem> = emptyList(),
    val selectedContact: ContactItem? = null,
    val messages: List<MessageItem> = emptyList(),
    val notesTreeRows: List<NoteTreeRow> = emptyList(),
    val notesExpandedIds: Set<String> = emptySet(),
    val notesContent: String = "",
    val notesDirty: Boolean = false,
    val notesReloadPrompt: Boolean = false,
    val selectedNotePath: String? = null,
    val selectedNoteRelativePath: String? = null,
    val selectedNoteDocumentId: String? = null,
    val selectedNoteIndexUri: String? = null,
    val showSettings: Boolean = false,
    val syncing: Boolean = false,
    val forceRunning: Boolean = false,
    val pendingForce: PendingForce? = null,
    val syncError: String? = null,
    val syncSuccess: String? = null,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val apiVersionText: String = ""
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val api: ProtoLinkApi,
    private val settingsStore: SettingsStore,
    private val mappingStore: MappingStore,
    private val signalR: SignalRService,
    private val metadataStore: MetadataStore,
    private val remoteCloud: ApiRemoteCloud,
    private val localFs: SafLocalFileSystem,
    @ApplicationContext private val appContext: Context
) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var autoSyncJob: Job? = null

    init {
        refreshAuth()
        _state.update {
            it.copy(
                settings = settingsStore.load(),
                mappings = mappingStore.load(),
                authenticated = auth.isAuthenticated,
                login = auth.current()?.login.orEmpty()
            )
        }
        if (auth.isAuthenticated) {
            // Contacts first — never wait on cloud root/sync or SignalR.
            loadContacts()
            viewModelScope.launch {
                signalR.onCommand = { handleRealtimeCommand(it) }
                runCatching { signalR.start() }
            }
            viewModelScope.launch {
                runCatching { ensureCloudRoot() }
            }
            startAutoSyncInterval()
            requestFullSync()
        }
        if (!settingsStore.load().notesRootUri.isNullOrBlank()) {
            refreshNotesTree()
        }
    }

    /** Interval: local-only push (no remote scan). Skips while any sync is active. */
    private fun startAutoSyncInterval(intervalMs: Long = 15_000L) {
        autoSyncJob?.cancel()
        autoSyncJob = viewModelScope.launch {
            while (isActive) {
                delay(intervalMs)
                if (!auth.isAuthenticated) continue
                if (mappingStore.load().isEmpty()) continue
                if (_state.value.syncing || SyncFlight.mutex.isLocked) continue
                requestLocalPush()
            }
        }
    }

    private fun stopAutoSyncInterval() {
        autoSyncJob?.cancel()
        autoSyncJob = null
    }

    fun refreshAuth() {
        _state.update { it.copy(authenticated = auth.isAuthenticated, login = auth.current()?.login.orEmpty()) }
    }

    /** Messenger always; Cloud full sync only on data_changed. */
    private fun handleRealtimeCommand(commandType: String?) {
        viewModelScope.launch {
            loadContacts()
            _state.value.selectedContact?.let { loadMessages(it) }
            if (!settingsStore.load().notesRootUri.isNullOrBlank()) {
                refreshNotesTree()
            }
            if (commandType.equals("data_changed", ignoreCase = true)) {
                requestFullSync()
            }
            _state.update { it.copy(status = "Live update${commandType?.let { t -> " ($t)" } ?: ""}") }
        }
    }

    fun toggleSettings(show: Boolean) {
        _state.update { it.copy(showSettings = show) }
        if (show) refreshApiVersion()
    }

    fun saveSettings(settings: AppSettings) {
        settingsStore.save(settings)
        _state.update { it.copy(settings = settings) }
        refreshApiVersion()
    }

    fun refreshApiVersion() = viewModelScope.launch {
        _state.update { it.copy(apiVersionText = "Loading…") }
        val text = runCatching {
            val ver = api.getVersion()
            val raw = ver.api?.version?.substringBefore('+').orEmpty()
            when {
                raw.isBlank() -> "Unavailable"
                ver.api?.buildDate.isNullOrBlank() -> raw
                else -> "$raw (${ver.api!!.buildDate})"
            }
        }.getOrElse { "Unavailable" }
        _state.update { it.copy(apiVersionText = text) }
    }

    fun login(user: String, pass: String) = viewModelScope.launch {
        _state.update { it.copy(status = "Logging in…") }
            auth.login(user, pass)
            .onSuccess {
                _state.update { s -> s.copy(authenticated = true, login = it.login, status = "Logged in", showSettings = false) }
                loadContacts()
                viewModelScope.launch {
                    signalR.onCommand = { handleRealtimeCommand(it) }
                    runCatching { signalR.start() }
                }
                viewModelScope.launch { runCatching { ensureCloudRoot() } }
                startAutoSyncInterval()
                requestFullSync()
            }
            .onFailure { e ->
                _state.update { it.copy(status = "Login failed: ${e.message}") }
            }
    }

    fun logout() {
        stopAutoSyncInterval()
        signalR.stop()
        auth.logout()
        _state.update { it.copy(authenticated = false, login = "", contacts = emptyList(), messages = emptyList()) }
    }

    private var cloudRootId: String? = null
    private var currentFolderId: String? = null

    suspend fun ensureCloudRoot() {
        val userId = auth.current()?.userId ?: return
        val list = api.getEntities(GetEntitiesRequest(parentIds = listOf(userId), includeValues = false, take = 200))
        val root = list.firstOrNull { it.code == CloudCodes.CLOUD_ROOT }
        val id = root?.id ?: api.addEntity(
            AddEntityRequest(code = CloudCodes.CLOUD_ROOT, parentIds = listOf(userId))
        ).id ?: return
        cloudRootId = id
        currentFolderId = id
        _state.update { it.copy(breadcrumb = listOf(id to "Cloud")) }
        loadCloudFolder(id)
    }

    fun loadCloudFolder(folderId: String) = viewModelScope.launch {
        currentFolderId = folderId
        val children = api.getEntities(
            GetEntitiesRequest(parentIds = listOf(folderId), includeValues = true, take = 500)
        )
        val items = children.mapNotNull { e ->
            val name = e.values?.firstOrNull()?.value?.asString ?: return@mapNotNull null
            when (e.code) {
                CloudCodes.CLOUD_FOLDER -> CloudItem(e.id, name, true)
                CloudCodes.CLOUD_FILE -> CloudItem(e.id, name, false)
                else -> null
            }
        }
        _state.update { it.copy(cloudItems = items, status = "Cloud loaded") }
    }

    /** Jump to a mapped folder (stories strip) with a sensible breadcrumb. */
    fun openMappedFolder(folderId: String, name: String) {
        val root = cloudRootId ?: _state.value.breadcrumb.firstOrNull()?.first
        val crumbs = when {
            root != null && !root.equals(folderId, true) ->
                listOf(root to "Cloud", folderId to name.ifBlank { "Folder" })
            else -> listOf(folderId to name.ifBlank { "Folder" })
        }
        _state.update { it.copy(breadcrumb = crumbs) }
        loadCloudFolder(folderId)
    }

    fun openCloudFolder(item: CloudItem) {
        if (!item.isFolder) return
        _state.update { it.copy(breadcrumb = it.breadcrumb + (item.id to item.name)) }
        loadCloudFolder(item.id)
    }

    fun cloudNavigateTo(index: Int) {
        val crumb = _state.value.breadcrumb.getOrNull(index) ?: return
        _state.update { it.copy(breadcrumb = it.breadcrumb.take(index + 1)) }
        loadCloudFolder(crumb.first)
    }

    fun mapCurrentFolder(treeUri: String) {
        val folderId = currentFolderId ?: run {
            _state.update { it.copy(status = "Open a cloud folder first, then map") }
            return
        }
        val name = _state.value.breadcrumb.lastOrNull()?.second ?: "Folder"
        val list = mappingStore.load().toMutableList()
        list.removeAll { it.cloudFolderId == folderId }
        list.add(CloudSyncMappingDto(folderId, treeUri, name))
        mappingStore.save(list)
        _state.update { it.copy(mappings = list, status = "Mapped “$name” → local folder. Syncing…") }
        syncNow()
    }

    fun unmapFolder(cloudFolderId: String) {
        val list = mappingStore.load().toMutableList()
        val removed = list.firstOrNull { it.cloudFolderId == cloudFolderId }
        if (removed == null) {
            _state.update { it.copy(status = "Mapping not found") }
            return
        }
        list.removeAll { it.cloudFolderId == cloudFolderId }
        mappingStore.save(list)
        val mappingId = cloudFolderId.replace("-", "")
        runCatching { metadataStore.clearMapping(mappingId) }
        _state.update {
            it.copy(
                mappings = list,
                status = "Unmapped “${removed.cloudFolderName.ifBlank { cloudFolderId.take(8) }}”"
            )
        }
    }

    fun syncNow() {
        requestFullSync()
    }

    /** Full reconcile (startup / SignalR / manual). Coalesces if busy. */
    private fun requestFullSync() {
        viewModelScope.launch {
            if (!auth.isAuthenticated) {
                _state.update { it.copy(status = "Log in before sync", syncing = false) }
                return@launch
            }
            val mappings = mappingStore.load()
            if (mappings.isEmpty()) {
                _state.update {
                    it.copy(
                        status = "No mapped folders. Cloud → open a folder → Map local folder",
                        syncing = false
                    )
                }
                return@launch
            }
            if (!SyncFlight.mutex.tryLock()) {
                SyncFlight.deferredFull.set(true)
                _state.update { it.copy(status = "Full sync queued…") }
                return@launch
            }
            _state.update { it.copy(status = "Syncing ${mappings.size} folder(s)…", syncing = true) }
            try {
                var lastLocal = 0
                var lastRemote = 0
                do {
                    SyncFlight.deferredFull.set(false)
                    val currentMappings = mappingStore.load()
                    val result = withContext(Dispatchers.IO) {
                        val engineMappings = currentMappings.map {
                            SyncMapping(
                                id = it.cloudFolderId.replace("-", ""),
                                cloudFolderId = it.cloudFolderId,
                                localRootPath = it.localPath,
                                cloudFolderName = it.cloudFolderName
                            )
                        }
                        SyncEngine(metadataStore, localFs, remoteCloud).reconcileAll(engineMappings)
                    }
                    lastLocal = result.localChanges
                    lastRemote = result.remoteChanges
                    currentFolderId?.let { loadCloudFolder(it) }
                } while (SyncFlight.deferredFull.getAndSet(false))
                _state.update {
                    it.copy(
                        status = "Sync complete (local $lastLocal, remote $lastRemote)",
                        syncing = false,
                        mappings = mappingStore.load()
                    )
                }
            } catch (e: Exception) {
                Log.e("ProtoLinkSync", "sync failed", e)
                _state.update {
                    it.copy(
                        status = "Sync failed: ${e.message ?: e.javaClass.simpleName}",
                        syncing = false,
                        syncError = e.message ?: e.javaClass.simpleName
                    )
                }
            } finally {
                if (SyncFlight.mutex.isLocked) SyncFlight.mutex.unlock()
            }
        }
    }

    /** Local-only push (interval / note save). Skips if sync busy. Notifies peers after uploads. */
    private fun requestLocalPush() {
        viewModelScope.launch {
            if (!auth.isAuthenticated) return@launch
            val mappings = mappingStore.load()
            if (mappings.isEmpty()) return@launch
            if (_state.value.syncing || !SyncFlight.mutex.tryLock()) return@launch
            _state.update { it.copy(status = "Uploading local changes…", syncing = true) }
            try {
                val applied = withContext(Dispatchers.IO) {
                    val engineMappings = mappings.map {
                        SyncMapping(
                            id = it.cloudFolderId.replace("-", ""),
                            cloudFolderId = it.cloudFolderId,
                            localRootPath = it.localPath,
                            cloudFolderName = it.cloudFolderName
                        )
                    }
                    SyncEngine(metadataStore, localFs, remoteCloud).pushLocalChanges(engineMappings)
                }
                if (applied > 0) {
                    notifyOtherDevices()
                    currentFolderId?.let { loadCloudFolder(it) }
                }
                // Drain deferred full sync requested while we held the lock.
                while (SyncFlight.deferredFull.getAndSet(false)) {
                    val currentMappings = mappingStore.load()
                    withContext(Dispatchers.IO) {
                        val engineMappings = currentMappings.map {
                            SyncMapping(
                                id = it.cloudFolderId.replace("-", ""),
                                cloudFolderId = it.cloudFolderId,
                                localRootPath = it.localPath,
                                cloudFolderName = it.cloudFolderName
                            )
                        }
                        SyncEngine(metadataStore, localFs, remoteCloud).reconcileAll(engineMappings)
                    }
                    currentFolderId?.let { loadCloudFolder(it) }
                }
                _state.update {
                    it.copy(
                        status = if (applied > 0) "Uploaded local changes ($applied)" else "No local changes",
                        syncing = false,
                        mappings = mappingStore.load()
                    )
                }
            } catch (e: Exception) {
                Log.e("ProtoLinkSync", "local push failed", e)
                _state.update {
                    it.copy(
                        status = "Upload failed: ${e.message ?: e.javaClass.simpleName}",
                        syncing = false,
                        syncError = e.message ?: e.javaClass.simpleName
                    )
                }
            } finally {
                if (SyncFlight.mutex.isLocked) SyncFlight.mutex.unlock()
            }
        }
    }

    private suspend fun notifyOtherDevices() {
        val me = auth.current()?.userId ?: return
        runCatching {
            api.sendCommand(
                SendCommandRequest(
                    commandType = "data_changed",
                    targetUserId = me,
                    parameters = mapOf("source" to "cloud_sync")
                )
            )
        }.onFailure { Log.w("ProtoLinkSync", "notifyOtherDevices failed", it) }
    }

    fun clearSyncError() {
        _state.update { it.copy(syncError = null) }
    }

    fun clearSyncSuccess() {
        _state.update { it.copy(syncSuccess = null) }
    }

    fun requestForceUpload(cloudFolderId: String) {
        _state.update {
            it.copy(
                pendingForce = PendingForce(cloudFolderId = cloudFolderId, push = true)
            )
        }
    }

    fun requestForceDownload(cloudFolderId: String) {
        _state.update {
            it.copy(
                pendingForce = PendingForce(cloudFolderId = cloudFolderId, push = false)
            )
        }
    }

    fun dismissForceConfirm() {
        _state.update { it.copy(pendingForce = null) }
    }

    fun confirmForceMapping() {
        val pending = _state.value.pendingForce ?: return
        _state.update { it.copy(pendingForce = null) }
        runForceMapping(pending.cloudFolderId, push = pending.push)
    }

    private fun runForceMapping(cloudFolderId: String, push: Boolean) {
        viewModelScope.launch {
            val mapping = mappingStore.load().firstOrNull { it.cloudFolderId == cloudFolderId }
            if (mapping == null) {
                _state.update { it.copy(syncError = "Mapped folder not found") }
                return@launch
            }
            if (!auth.isAuthenticated) {
                _state.update { it.copy(syncError = "Log in before force sync") }
                return@launch
            }
            if (_state.value.forceRunning) {
                _state.update { it.copy(syncError = "Force sync already running") }
                return@launch
            }
            _state.update {
                it.copy(
                    forceRunning = true,
                    status = if (push) "Force upload…" else "Force download…"
                )
            }
            var locked = false
            try {
                if (!SyncFlight.mutex.tryLock()) {
                    _state.update { it.copy(status = "Waiting for sync lock…") }
                    val acquired = withTimeoutOrNull(30_000) { SyncFlight.mutex.lock() } != null
                    if (!acquired) {
                        throw IllegalStateException("Timed out waiting for sync lock")
                    }
                }
                locked = true
                val count = withContext(Dispatchers.IO) {
                    val engineMapping = SyncMapping(
                        id = mapping.cloudFolderId.replace("-", ""),
                        cloudFolderId = mapping.cloudFolderId,
                        localRootPath = mapping.localPath,
                        cloudFolderName = mapping.cloudFolderName
                    )
                    val engine = SyncEngine(metadataStore, localFs, remoteCloud)
                    if (push) engine.forcePushMapping(engineMapping)
                    else engine.forcePullMapping(engineMapping)
                }
                val msg = if (push) {
                    "Force upload complete ($count file(s))"
                } else {
                    "Force download complete ($count file(s))"
                }
                if (push) {
                    notifyOtherDevices()
                }
                _state.update {
                    it.copy(
                        forceRunning = false,
                        status = msg,
                        syncSuccess = msg
                    )
                }
            } catch (e: Exception) {
                Log.e("ProtoLinkSync", "force sync failed", e)
                _state.update {
                    it.copy(
                        forceRunning = false,
                        status = "Force sync failed",
                        syncError = e.message ?: e.javaClass.simpleName
                    )
                }
            } finally {
                if (locked && SyncFlight.mutex.isLocked) SyncFlight.mutex.unlock()
            }
        }
    }

    fun createCloudFolder(name: String) = viewModelScope.launch {
        val parent = currentFolderId ?: return@launch
        api.addEntity(
            AddEntityRequest(
                code = CloudCodes.CLOUD_FOLDER,
                parentIds = listOf(parent),
                values = listOf(AddValueRequest(value = name, parentIds = listOf(CloudCodes.NAME_TYPE_ID)))
            )
        )
        loadCloudFolder(parent)
    }

    fun loadContacts() = viewModelScope.launch {
        val userId = auth.current()?.userId ?: return@launch
        try {
            var userContacts = api.getEntities(
                GetEntitiesRequest(
                    parentIds = listOf(SystemEntities.CONTACTS, userId),
                    includeValues = false,
                    take = 50
                )
            ).firstOrNull()

            if (userContacts == null) {
                val created = api.addEntity(
                    AddEntityRequest(
                        code = "UserContacts",
                        parentIds = listOf(SystemEntities.CONTACTS, userId)
                    )
                )
                val id = created.id ?: return@launch
                userContacts = EntityDto(id = id, code = "UserContacts")
            }

            val parentId = userContacts.id
            val contacts = api.getEntities(
                GetEntitiesRequest(parentIds = listOf(parentId), includeValues = true, take = 2000)
            )
            val items = mutableListOf<ContactItem>()
            for (e in contacts) {
                val uid = e.values?.mapNotNull { it.value?.asString }
                    ?.firstOrNull { it.startsWith("userid:", true) }
                    ?.substringAfter(":")
                    ?: e.code?.takeIf { it.length == 36 }
                    ?: continue
                // Provisional name — resolve logins after the list is visible
                items.add(ContactItem(uid, uid))
            }

            val selfHit = api.getEntities(
                GetEntitiesRequest(
                    parentIds = listOf(parentId, userId),
                    includeValues = true,
                    take = 10
                )
            ).firstOrNull()
            val hasSelf = items.any { it.userId.equals(userId, true) }
            if (!hasSelf) {
                val name = selfHit?.code?.takeIf { it.isNotBlank() }
                    ?: auth.current()?.login?.takeIf { it.isNotBlank() }
                    ?: "Me"
                if (selfHit == null) {
                    runCatching {
                        api.addEntity(
                            AddEntityRequest(
                                code = name,
                                parentIds = listOf(parentId, userId),
                                values = listOf(
                                    AddValueRequest(value = "userid:$userId", parentIds = emptyList())
                                )
                            )
                        )
                    }
                }
                items.add(0, ContactItem(userId, name))
            }

            val ready = items.distinctBy { c -> c.userId.lowercase() }
            _state.update {
                it.copy(
                    contacts = ready,
                    status = "Contacts: ${ready.size}"
                )
            }
            // Stay on chat list until the user opens a conversation (phones show list first).

            // Enrich display names without blocking list/chat
            launch {
                val enriched = ready.map { c ->
                    if (c.userId.equals(userId, true) && !c.displayName.equals(c.userId, true)) c
                    else {
                        val display = runCatching {
                            val u = api.getUserById(c.userId)
                            u.name?.takeIf { it.isNotBlank() } ?: u.login
                        }.getOrNull()?.takeIf { it.isNotBlank() } ?: c.displayName
                        c.copy(displayName = display)
                    }
                }.sortedBy { it.displayName.lowercase() }
                val selectedId = _state.value.selectedContact?.userId
                _state.update { s ->
                    s.copy(
                        contacts = enriched,
                        selectedContact = selectedId?.let { id -> enriched.firstOrNull { it.userId.equals(id, true) } }
                            ?: s.selectedContact,
                        status = "Contacts: ${enriched.size}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("ProtoLinkContacts", "loadContacts failed", e)
            _state.update { it.copy(status = "Contacts failed: ${e.message}") }
        }
    }

    fun selectContact(c: ContactItem) {
        _state.update { it.copy(selectedContact = c) }
        loadMessages(c)
    }

    fun clearSelectedContact() {
        _state.update { it.copy(selectedContact = null, messages = emptyList()) }
    }

    fun loadMessages(c: ContactItem) = viewModelScope.launch {
        val me = auth.current()?.userId ?: return@launch
        try {
            val all = api.getEntities(
                GetEntitiesRequest(parentIds = listOf(SystemEntities.MESSAGE), includeValues = true, take = 5000)
            )
            val msgs = all.mapNotNull { e ->
                val vals = e.values ?: return@mapNotNull null
                val texts = vals.mapNotNull { JsonValues.asText(it.value)?.trim()?.takeIf { t -> t.isNotEmpty() } }
                val sender = texts.firstOrNull { it.startsWith("sender:", true) }?.substringAfter(":")
                val receiver = texts.firstOrNull { it.startsWith("receiver:", true) }?.substringAfter(":")
                if (sender.isNullOrBlank() || receiver.isNullOrBlank()) return@mapNotNull null
                val participants = setOf(sender.lowercase(), receiver.lowercase())
                val wanted = setOf(me.lowercase(), c.userId.lowercase())
                if (participants != wanted) return@mapNotNull null

                val body = texts.firstOrNull { t ->
                    !t.startsWith("sender:", true) &&
                        !t.startsWith("receiver:", true) &&
                        !ChatTime.looksLikeIsoDate(t)
                } ?: return@mapNotNull null

                val timeRaw = texts.firstOrNull { ChatTime.looksLikeIsoDate(it) }
                    ?: e.creationTime
                    ?: e.updateTime
                val millis = ChatTime.parseMillis(timeRaw).takeIf { it > 0 }
                    ?: ChatTime.parseMillis(e.creationTime)
                MessageItem(e.id, body, sender.equals(me, ignoreCase = true), millis)
            }.sortedBy { it.timestampMillis }
            _state.update { it.copy(messages = msgs, status = "Messages: ${msgs.size}") }
        } catch (ex: Exception) {
            Log.e("ProtoLinkChat", "loadMessages failed", ex)
            _state.update { it.copy(status = "Messages failed: ${ex.message}") }
        }
    }

    fun sendMessage(text: String) = viewModelScope.launch {
        val me = auth.current()?.userId ?: return@launch
        val peer = _state.value.selectedContact?.userId ?: return@launch
        val body = text.trim()
        if (body.isEmpty()) return@launch
        // .NET DateTimeValue.GetDateTime() rejects Instant nanos (9 digits); use millis ISO-8601.
        val sentAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString()
        try {
            val id = api.addEntity(
                AddEntityRequest(
                    code = "Message",
                    parentIds = listOf(SystemEntities.MESSAGE),
                    values = listOf(
                        AddValueRequest(
                            type = AddValueRequest.TYPE_STRING,
                            value = body,
                            parentIds = listOf(SystemEntities.MESSAGE)
                        ),
                        AddValueRequest(
                            type = AddValueRequest.TYPE_DATETIME,
                            value = sentAt,
                            parentIds = listOf(SystemEntities.MESSAGE)
                        ),
                        AddValueRequest(
                            type = AddValueRequest.TYPE_STRING,
                            value = "sender:$me",
                            parentIds = listOf(SystemEntities.SENT)
                        ),
                        AddValueRequest(
                            type = AddValueRequest.TYPE_STRING,
                            value = "receiver:$peer",
                            parentIds = listOf(SystemEntities.RECEIVED)
                        )
                    )
                )
            ).id
            runCatching {
                api.sendCommand(
                    SendCommandRequest(
                        commandType = "message_sent",
                        targetUserId = peer,
                        parameters = mapOf(
                            "senderId" to me,
                            "messageText" to body,
                            "timestamp" to sentAt
                        )
                    )
                )
            }
            if (peer != me) {
                runCatching {
                    api.sendCommand(
                        SendCommandRequest(
                            commandType = "message_sent",
                            targetUserId = me,
                            parameters = mapOf(
                                "senderId" to me,
                                "messageText" to body,
                                "timestamp" to sentAt
                            )
                        )
                    )
                }
            }
            _state.value.selectedContact?.let { loadMessages(it) }
            _state.update { it.copy(status = "Sent ${id ?: ""}") }
        } catch (ex: Exception) {
            val detail = (ex as? retrofit2.HttpException)?.response()?.errorBody()?.string()?.take(300)
            Log.e("ProtoLinkChat", "sendMessage failed: ${ex.message} $detail", ex)
            _state.update {
                it.copy(status = "Send failed: ${ex.message}${detail?.let { d -> " — $d" } ?: ""}")
            }
        }
    }

    fun setNotesRoot(uri: String) {
        val s = settingsStore.load().copy(notesRootUri = uri)
        settingsStore.save(s)
        _state.update {
            it.copy(
                settings = s,
                notesExpandedIds = emptySet(),
                selectedNotePath = null,
                selectedNoteRelativePath = null,
                selectedNoteDocumentId = null,
                selectedNoteIndexUri = null,
                notesContent = "",
                notesTreeRows = emptyList()
            )
        }
        refreshNotesTree()
    }

    private var notesRootCache: NotesTreeBuilder.Node? = null

    fun refreshNotesTree() = viewModelScope.launch {
        val rootUriStr = settingsStore.load().notesRootUri
        if (rootUriStr.isNullOrBlank()) {
            notesRootCache = null
            _state.update {
                it.copy(notesTreeRows = emptyList(), status = "Choose a notes folder")
            }
            return@launch
        }
        val treeUri = Uri.parse(rootUriStr)
        val root = withContext(Dispatchers.IO) {
            NotesTreeBuilder.build(appContext, treeUri)
        }
        notesRootCache = root
        // Expand root by default (Windows tree shows root children)
        val expanded = _state.value.notesExpandedIds.ifEmpty { setOf(root.documentId) }
        val rows = NotesTreeBuilder.flattenVisible(root, expanded).map { it.toTreeRow() }
        _state.update {
            it.copy(
                notesExpandedIds = expanded,
                notesTreeRows = rows,
                status = "Notes tree: ${countFolders(root)} page folder(s)"
            )
        }
    }

    private fun countFolders(node: NotesTreeBuilder.Node): Int =
        1 + node.children.sumOf { countFolders(it) }

    private fun NotesTreeBuilder.VisibleRow.toTreeRow() = NoteTreeRow(
        documentId = documentId,
        safDocumentId = safDocumentId,
        name = name,
        relativePath = relativePath,
        depth = depth,
        hasChildren = hasChildren,
        expanded = expanded,
        folderUri = folderUri,
        indexUri = indexUri
    )

    fun toggleNotesExpand(documentId: String) {
        val root = notesRootCache ?: return
        val next = _state.value.notesExpandedIds.toMutableSet()
        if (!next.add(documentId)) next.remove(documentId)
        _state.update {
            it.copy(
                notesExpandedIds = next,
                notesTreeRows = NotesTreeBuilder.flattenVisible(root, next).map { r -> r.toTreeRow() }
            )
        }
    }

    /** Windows parity: selecting a folder node opens/edits that folder's index.html. */
    fun selectNotesNode(row: NoteTreeRow) = viewModelScope.launch {
        val rootUriStr = settingsStore.load().notesRootUri ?: return@launch
        val treeUri = Uri.parse(rootUriStr)
        _state.update { it.copy(status = "Opening ${row.name}…") }
        try {
            val (indexUri, html) = withContext(Dispatchers.IO) {
                var index = NotesTreeBuilder.resolveIndex(
                    context = appContext,
                    treeUri = treeUri,
                    relativePath = row.relativePath,
                    safDocumentId = row.safDocumentId.ifBlank { null },
                    cachedIndexUri = row.indexUri
                )
                var text = index?.let { NotesTreeBuilder.readUtf8(appContext, it) }.orEmpty()
                // Missing index only: create empty page so Save works (Windows creates on new folder).
                // Never invent a title stub when a real file exists but failed to open.
                if (index == null) {
                    Log.w("ProtoLinkNotes", "No index for ${row.relativePath.ifBlank { row.name }}; creating")
                    val empty = "<p><br></p>"
                    index = NotesTreeBuilder.ensureIndexHtml(
                        appContext,
                        treeUri,
                        row.safDocumentId.ifBlank {
                            NotesTreeBuilder.folderDocumentId(treeUri, row.relativePath)
                        },
                        empty
                    )
                    text = empty
                } else {
                    val healed = healJsEscapedHtml(text)
                    if (healed != text) {
                        Log.w("ProtoLinkNotes", "Healing JS-escaped HTML on disk for ${row.relativePath}")
                        appContext.contentResolver.openOutputStream(index, "wt")?.use { out ->
                            out.write(healed.toByteArray(Charsets.UTF_8))
                        }
                        text = healed
                    }
                }
                index to text
            }
            val body = html.ifBlank { "<p><br></p>" }
            val root = notesRootCache
            val expanded = if (root != null && row.hasChildren) {
                _state.value.notesExpandedIds + row.documentId
            } else {
                _state.value.notesExpandedIds
            }
            val rows = root?.let { NotesTreeBuilder.flattenVisible(it, expanded).map { r -> r.toTreeRow() } }
                ?: _state.value.notesTreeRows
            _state.update {
                it.copy(
                    selectedNotePath = row.name,
                    selectedNoteRelativePath = row.relativePath,
                    selectedNoteDocumentId = row.documentId,
                    selectedNoteIndexUri = indexUri?.toString(),
                    notesContent = body,
                    notesDirty = false,
                    notesReloadPrompt = false,
                    notesExpandedIds = expanded,
                    notesTreeRows = rows,
                    status = if (indexUri == null) {
                        "No index.html in “${row.name}”"
                    } else {
                        "Editing ${row.name} (${body.length} chars)"
                    }
                )
            }
            if (indexUri != null) {
                notesDiskFingerprint = ContentHashUtil.sha256Hex(body.toByteArray(Charsets.UTF_8))
                startNotesDiskPoller()
            } else {
                stopNotesDiskPoller()
            }
        } catch (e: Exception) {
            Log.e("ProtoLinkNotes", "select failed ${row.name}", e)
            _state.update {
                it.copy(
                    selectedNotePath = row.name,
                    selectedNoteRelativePath = row.relativePath,
                    selectedNoteDocumentId = row.documentId,
                    notesContent = "<p>Open failed: ${e.message}</p>",
                    status = "Open failed: ${e.message}"
                )
            }
        }
    }

    private var notesSaveJob: Job? = null
    private var notesDiskPollJob: Job? = null
    /** Fingerprint of open note on disk after our last load/save; poller ignores matches. */
    private var notesDiskFingerprint: String? = null

    fun clearSelectedNote() {
        notesSaveJob?.cancel()
        notesSaveJob = null
        stopNotesDiskPoller()
        _state.update {
            it.copy(
                selectedNotePath = null,
                selectedNoteRelativePath = null,
                selectedNoteDocumentId = null,
                selectedNoteIndexUri = null,
                notesContent = "",
                notesDirty = false,
                notesReloadPrompt = false
            )
        }
    }

    fun markNotesDirty() {
        if (!_state.value.notesDirty) {
            _state.update { it.copy(notesDirty = true) }
        }
    }

    /** Debounce note writes ~5s after editor changes (disk only — Cloud watches FS separately). */
    fun scheduleSaveSelectedNote(html: String) {
        if (_state.value.selectedNoteIndexUri.isNullOrBlank()) return
        val cleaned = healJsEscapedHtml(html)
        if (cleaned == _state.value.notesContent && !_state.value.notesDirty) return
        markNotesDirty()
        _state.update { it.copy(status = "Changes will be saved in 5 seconds…") }
        notesSaveJob?.cancel()
        notesSaveJob = viewModelScope.launch {
            delay(5_000)
            persistSelectedNote(cleaned)
        }
    }

    fun cancelNotesPendingSave() {
        notesSaveJob?.cancel()
        notesSaveJob = null
    }

    /** Immediate save (e.g. leave note). */
    fun saveSelectedNoteNow(html: String, clearAfter: Boolean = false) {
        notesSaveJob?.cancel()
        notesSaveJob = null
        viewModelScope.launch {
            persistSelectedNote(healJsEscapedHtml(html))
            if (clearAfter) clearSelectedNote()
        }
    }

    fun confirmNotesReloadFromDisk() = viewModelScope.launch {
        cancelNotesPendingSave()
        _state.update { it.copy(notesReloadPrompt = false, notesDirty = false) }
        forceReloadSelectedNoteFromDisk()
    }

    fun dismissNotesReloadPrompt() {
        _state.update {
            it.copy(
                notesReloadPrompt = false,
                status = "Keeping your edits; disk changes ignored until auto-save."
            )
        }
    }

    private fun startNotesDiskPoller() {
        stopNotesDiskPoller()
        notesDiskPollJob = viewModelScope.launch {
            while (isActive) {
                delay(1_500)
                pollOpenNoteFromDisk()
            }
        }
    }

    private fun stopNotesDiskPoller() {
        notesDiskPollJob?.cancel()
        notesDiskPollJob = null
        notesDiskFingerprint = null
    }

    private suspend fun pollOpenNoteFromDisk() {
        val indexUri = _state.value.selectedNoteIndexUri ?: return
        if (_state.value.notesReloadPrompt) return
        try {
            val text = withContext(Dispatchers.IO) {
                NotesTreeBuilder.readUtf8(appContext, Uri.parse(indexUri))
            }
            val healed = healJsEscapedHtml(text)
            val body = healed.ifBlank { "<p><br></p>" }
            val fp = ContentHashUtil.sha256Hex(body.toByteArray(Charsets.UTF_8))
            if (fp == notesDiskFingerprint) return
            if (body == _state.value.notesContent && !_state.value.notesDirty) {
                notesDiskFingerprint = fp
                return
            }
            if (_state.value.notesDirty) {
                _state.update {
                    it.copy(
                        notesReloadPrompt = true,
                        status = "Note changed on disk — reload or keep edits?"
                    )
                }
                return
            }
            notesDiskFingerprint = fp
            _state.update {
                it.copy(
                    notesContent = body,
                    notesDirty = false,
                    status = "Reloaded"
                )
            }
        } catch (e: Exception) {
            Log.w("ProtoLinkNotes", "disk poll failed", e)
        }
    }

    private suspend fun forceReloadSelectedNoteFromDisk() {
        val indexUri = _state.value.selectedNoteIndexUri ?: return
        try {
            val text = withContext(Dispatchers.IO) {
                NotesTreeBuilder.readUtf8(appContext, Uri.parse(indexUri))
            }
            val healed = healJsEscapedHtml(text)
            val body = healed.ifBlank { "<p><br></p>" }
            notesDiskFingerprint = ContentHashUtil.sha256Hex(body.toByteArray(Charsets.UTF_8))
            _state.update {
                it.copy(
                    notesContent = body,
                    notesDirty = false,
                    status = "Reloaded"
                )
            }
        } catch (e: Exception) {
            Log.e("ProtoLinkNotes", "forced reload failed", e)
            _state.update { it.copy(status = "Reload failed: ${e.message}") }
        }
    }

    private suspend fun persistSelectedNote(cleaned: String) {
        val indexUri = _state.value.selectedNoteIndexUri
        if (indexUri.isNullOrBlank()) {
            _state.update { it.copy(status = "Nothing to save") }
            return
        }
        try {
            val bytes = cleaned.toByteArray(Charsets.UTF_8)
            withContext(Dispatchers.IO) {
                appContext.contentResolver.openOutputStream(Uri.parse(indexUri), "wt")?.use { out ->
                    out.write(bytes)
                } ?: error("Cannot open output stream")
            }
            notesDiskFingerprint = ContentHashUtil.sha256Hex(bytes)
            _state.update {
                it.copy(
                    notesContent = cleaned,
                    notesDirty = false,
                    notesReloadPrompt = false,
                    status = "Saved ${_state.value.selectedNotePath}"
                )
            }
            requestLocalPush()
        } catch (e: Exception) {
            Log.e("ProtoLinkNotes", "save failed", e)
            _state.update { it.copy(status = "Save failed: ${e.message}") }
        }
    }

    fun saveNoteContent(html: String) {
        _state.update { it.copy(notesContent = html) }
    }
}
