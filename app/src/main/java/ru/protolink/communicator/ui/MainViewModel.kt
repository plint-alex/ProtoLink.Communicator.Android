package ru.protolink.communicator.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.protolink.communicator.BuildConfig
import ru.protolink.communicator.PendingDebugIntent
import ru.protolink.communicator.data.AppSettings
import ru.protolink.communicator.data.AuthRepository
import ru.protolink.communicator.data.TokenRefresher
import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.CloudSyncMappingDto
import ru.protolink.communicator.data.MappingStore
import ru.protolink.communicator.data.MessengerNotifier
import ru.protolink.communicator.data.MessengerReadStore
import ru.protolink.communicator.data.NotesTreeBuilder
import ru.protolink.communicator.data.SettingsStore
import ru.protolink.communicator.data.SignalRService
import ru.protolink.communicator.data.SystemEntities
import ru.protolink.communicator.data.api.AddEntityRequest
import ru.protolink.communicator.data.api.AddPermissionRequest
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
data class ContactItem(
    val userId: String,
    val displayName: String,
    val unreadCount: Int = 0
) {
    val hasUnread: Boolean get() = unreadCount > 0
    val unreadLabel: String get() = if (unreadCount > 99) "99+" else unreadCount.toString()
}

enum class DeliveryStatus { Sending, Sent, Read }

data class MessageItem(
    val id: String,
    val text: String,
    val mine: Boolean,
    val timestampMillis: Long,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.Sent
) {
    val timeLabel: String get() = ru.protolink.communicator.util.ChatTime.formatTime(timestampMillis)
    val dayLabel: String get() = ru.protolink.communicator.util.ChatTime.formatDayLabel(timestampMillis)
    val ticksText: String
        get() = when (deliveryStatus) {
            DeliveryStatus.Sending -> "◌"
            DeliveryStatus.Read -> "✓✓"
            DeliveryStatus.Sent -> "✓"
        }
    val ticksRead: Boolean get() = deliveryStatus == DeliveryStatus.Read
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

data class UserError(val title: String, val detail: String)

data class PendingForce(
    val cloudFolderId: String,
    val push: Boolean
)

/** One-file sync conflict: user picks server or local. */
data class PendingConflict(
    val cloudFolderId: String,
    val relativePath: String
)

/** Dialog: create note under [parentRelativePath] (empty = notes root). */
data class PendingCreateNote(
    val parentRelativePath: String,
    val sectionLabel: String,
    val titleDraft: String = "",
    val titleError: String? = null,
    /** Editor flow: choose Same section vs Inside this note. */
    val fromEditor: Boolean = false,
    val insideCurrent: Boolean = false,
    val editorNoteRelativePath: String? = null,
    val editorNoteName: String? = null
)

/** Dialog: confirm delete page folder (recursive). */
data class PendingDeleteNote(
    val relativePath: String,
    val name: String,
    val hasChildren: Boolean
)

data class UiState(
    val authenticated: Boolean = false,
    val login: String = "",
    val status: String = "",
    /** Bumped after a send is accepted locally so the composer can clear the draft. */
    val composerClearNonce: Int = 0,
    /** When set, MessengerScreen puts this text back into the draft field. */
    val composerDraftRestore: String? = null,
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
    val pendingConflict: PendingConflict? = null,
    val pendingCreateNote: PendingCreateNote? = null,
    val pendingDeleteNote: PendingDeleteNote? = null,
    val syncError: String? = null,
    val userError: UserError? = null,
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
    private val messengerReadStore: MessengerReadStore,
    private val messengerNotifier: MessengerNotifier,
    private val tokenRefresher: TokenRefresher,
    @ApplicationContext private val appContext: Context
) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var autoSyncJob: Job? = null
    private var chatPollJob: Job? = null
    private var loadMessagesJob: Job? = null
    private val readReceiptNotified = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile var appInForeground: Boolean = true

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
                signalR.onCommand = { type, params -> handleRealtimeCommand(type, params) }
                runCatching { signalR.start() }
            }
            viewModelScope.launch {
                runCatching { ensureCloudRoot() }
            }
            startAutoSyncInterval()
            // adb force-download must win over startup reconcile (conflicts abort full sync).
            if (PendingDebugIntent.takeForceDownload()) {
                forceDownloadAllMappedNow()
            } else {
                requestFullSync()
            }
        }
        if (!settingsStore.load().notesRootUri.isNullOrBlank()) {
            refreshNotesTree()
        }
        PendingDebugIntent.takeCreateNoteTitle()?.let { title ->
            Log.e("ProtoLinkNotes", "debug create note: $title")
            createNote("", title)
        }
        PendingDebugIntent.takeDeleteNotePath()?.let { path ->
            Log.e("ProtoLinkNotes", "debug delete note: $path")
            deleteNote(path)
        }
    }

    /** adb self-test: files/force_create_note → create + open at notes root. */
    fun consumePendingDebugCreateNote() {
        val title = PendingDebugIntent.takeCreateNoteTitle() ?: return
        Log.e("ProtoLinkNotes", "debug create note (resume): $title")
        createNote("", title)
    }

    /** adb self-test: files/force_delete_note → recursive delete by relative path. */
    fun consumePendingDebugDeleteNote() {
        val path = PendingDebugIntent.takeDeleteNotePath() ?: return
        Log.e("ProtoLinkNotes", "debug delete note (resume): $path")
        deleteNote(path)
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

    fun dismissUserError() {
        _state.update { it.copy(userError = null) }
    }

    private fun showError(title: String, detail: String, ex: Throwable? = null) {
        val full = buildString {
            append(detail)
            if (ex != null) {
                append("\n\n")
                append(ex.javaClass.simpleName)
                append(": ")
                append(ex.message ?: "")
                val cause = ex.cause
                if (cause != null) {
                    append("\nCaused by: ")
                    append(cause.javaClass.simpleName)
                    append(": ")
                    append(cause.message ?: "")
                }
            }
        }
        Log.e("ProtoLinkChat", "$title — $full", ex)
        _state.update {
            it.copy(
                status = "$title: ${detail.take(120)}",
                userError = UserError(title, full.take(4000))
            )
        }
    }

    private fun httpDetail(ex: Throwable): String {
        val http = ex as? retrofit2.HttpException
        if (http == null) return ex.message ?: ex.javaClass.simpleName
        val body = runCatching { http.response()?.errorBody()?.string() }.getOrNull()?.take(1500)
        return "HTTP ${http.code()} ${http.message()}" + (body?.let { "\n$it" } ?: "")
    }


    /** Messenger always; Cloud full sync only on data_changed. */
    private fun handleRealtimeCommand(commandType: String?, parameters: Map<String, Any?>? = null) {
        viewModelScope.launch {
            Log.i("ProtoLinkChat", "realtime command=$commandType selected=${_state.value.selectedContact?.userId}")
            if (commandType.equals("message_read", ignoreCase = true)) {
                handleMessageRead(parameters)
                return@launch
            }
            if (commandType.equals("message_sent", ignoreCase = true)) {
                handleIncomingMessageSent(parameters)
                return@launch
            }
            val openChat = _state.value.selectedContact
            if (openChat != null) loadMessages(openChat)
            loadContacts()
            if (!settingsStore.load().notesRootUri.isNullOrBlank()) {
                refreshNotesTree()
            }
            if (commandType.equals("data_changed", ignoreCase = true)) {
                requestFullSync()
            }
            _state.update { it.copy(status = "Live update${commandType?.let { t -> " ($t)" } ?: ""}") }
        }
    }

    private suspend fun handleIncomingMessageSent(parameters: Map<String, Any?>?) {
        val senderId = parameters?.get("senderId")?.toString()
            ?: parameters?.get("SenderId")?.toString()
        val text = parameters?.get("messageText")?.toString()
            ?: parameters?.get("MessageText")?.toString()
            ?: "New message"
        val me = auth.current()?.userId
        val fromOther = !senderId.isNullOrBlank() && me != null && !senderId.equals(me, true)
        val openId = _state.value.selectedContact?.userId
        val chatOpen = fromOther && openId != null && senderId.equals(openId, true)
        if (fromOther && (!chatOpen || !appInForeground)) {
            val title = _state.value.contacts.firstOrNull { it.userId.equals(senderId, true) }?.displayName
                ?: senderId!!
            messengerNotifier.notifyMessage(senderId!!, title, text)
        }
        val openChat = _state.value.selectedContact
        if (openChat != null) loadMessages(openChat)
        refreshUnreadBadges()
        _state.update { it.copy(status = "Live update (message_sent)") }
    }

    private suspend fun handleMessageRead(parameters: Map<String, Any?>?) {
        val rawIds = parameters?.get("messageIds") ?: parameters?.get("MessageIds")
        val ids = when (rawIds) {
            is Collection<*> -> rawIds.mapNotNull { it?.toString() }.filter { it.isNotBlank() }
            is Array<*> -> rawIds.mapNotNull { it?.toString() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
        if (ids.isEmpty()) return
        for (id in ids) {
            runCatching {
                api.addValue(
                    id,
                    AddValueRequest(
                        type = AddValueRequest.TYPE_STRING,
                        value = "status:read",
                        parentIds = listOf(SystemEntities.MESSAGE)
                    )
                )
            }
        }
        _state.update { s ->
            s.copy(
                messages = s.messages.map { m ->
                    if (m.mine && ids.any { it.equals(m.id, true) })
                        m.copy(deliveryStatus = DeliveryStatus.Read)
                    else m
                }
            )
        }
    }

    private suspend fun markOpenChatRead(contactId: String, messages: List<MessageItem>) {
        messengerReadStore.markOpened(contactId)
        messengerNotifier.cancelForContact(contactId)
        val me = auth.current()?.userId ?: return
        val incomingIds = messages
            .filter { !it.mine && !it.id.startsWith("local-") }
            .map { it.id }
            .filter { readReceiptNotified.add(it.lowercase()) }
        if (incomingIds.isEmpty()) {
            refreshUnreadBadges()
            return
        }
        runCatching {
            api.sendCommand(
                SendCommandRequest(
                    commandType = "message_read",
                    targetUserId = contactId,
                    parameters = mapOf(
                        "messageIds" to incomingIds,
                        "peerId" to me
                    )
                )
            )
        }
        refreshUnreadBadges()
    }

    private suspend fun refreshUnreadBadges() {
        val me = auth.current()?.userId ?: return
        val contacts = _state.value.contacts
        if (contacts.isEmpty()) return
        val all = runCatching {
            api.getEntities(
                GetEntitiesRequest(parentIds = listOf(SystemEntities.MESSAGE), includeValues = true, take = 5000)
            )
        }.getOrElse { return }
        val counts = contacts.associate { it.userId.lowercase() to 0 }.toMutableMap()
        for (e in all) {
            val vals = e.values ?: continue
            val texts = vals.mapNotNull { JsonValues.asText(it.value)?.trim()?.takeIf { t -> t.isNotEmpty() } }
            val sender = texts.firstOrNull { it.startsWith("sender:", true) }?.substringAfter(":") ?: continue
            val receiver = texts.firstOrNull { it.startsWith("receiver:", true) }?.substringAfter(":") ?: continue
            if (!receiver.equals(me, true)) continue
            if (sender.equals(me, true)) continue
            val key = sender.lowercase()
            if (!counts.containsKey(key)) continue
            val millis = ChatTime.resolveMessageMillis(e.creationTime, e.updateTime, null)
            val opened = messengerReadStore.getOpenedUtcMillis(sender)
            if (millis > opened) counts[key] = (counts[key] ?: 0) + 1
        }
        _state.update { s ->
            s.copy(
                contacts = s.contacts.map { c ->
                    c.copy(unreadCount = counts[c.userId.lowercase()] ?: 0)
                },
                selectedContact = s.selectedContact?.let { sel ->
                    sel.copy(unreadCount = counts[sel.userId.lowercase()] ?: 0)
                }
            )
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
                    signalR.onCommand = { type, params -> handleRealtimeCommand(type, params) }
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

    suspend fun registerAsync(
        email: String,
        password: String,
        lang: String = java.util.Locale.getDefault().toLanguageTag()
    ): ru.protolink.communicator.data.RegisterOutcome {
        _state.update { it.copy(status = "Creating account…") }
        val outcome = auth.register(email, password, lang).getOrElse { e ->
            _state.update { it.copy(status = "Registration failed: ${e.message}") }
            return ru.protolink.communicator.data.RegisterOutcome(
                success = false,
                error = e.message ?: "Registration failed"
            )
        }
        _state.update {
            it.copy(
                status = when {
                    !outcome.emailError.isNullOrBlank() -> "Email send failed"
                    !outcome.error.isNullOrBlank() -> outcome.error
                    outcome.success -> "Check your email"
                    else -> "Registration failed"
                }
            )
        }
        return outcome
    }

    fun logout() {
        stopAutoSyncInterval()
        chatPollJob?.cancel()
        chatPollJob = null
        loadMessagesJob?.cancel()
        signalR.stop()
        auth.logout()
        _state.update { it.copy(authenticated = false, login = "", contacts = emptyList(), messages = emptyList(), selectedContact = null) }
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
                handleSyncFailure(e)
            } finally {
                if (SyncFlight.mutex.isLocked) SyncFlight.mutex.unlock()
            }
        }
    }

    private fun handleSyncFailure(e: Exception) {
        val conflict = e as? ru.protolink.communicator.sync.engine.SyncConflictException
        if (conflict != null && !conflict.relativePath.isNullOrBlank()) {
            val path = conflict.relativePath!!
            val mappingId = conflict.mappingId
            val dto = mappingStore.load().firstOrNull { m ->
                mappingId != null && m.cloudFolderId.replace("-", "").equals(mappingId, ignoreCase = true)
            } ?: mappingStore.load().singleOrNull()
            if (dto != null) {
                _state.update {
                    it.copy(
                        status = "Sync conflict: $path",
                        syncing = false,
                        pendingConflict = PendingConflict(
                            cloudFolderId = dto.cloudFolderId,
                            relativePath = path
                        ),
                        syncError = null
                    )
                }
                return
            }
        }
        val detail = e.message ?: e.javaClass.simpleName
        _state.update {
            it.copy(
                status = "Sync failed: $detail",
                syncing = false,
                syncError = detail
            )
        }
    }

    fun dismissConflict() {
        _state.update { it.copy(pendingConflict = null) }
    }

    /** Keep phone file and overwrite server for the conflicted path, then resume sync. */
    fun resolveConflictKeepLocal() {
        val pending = _state.value.pendingConflict ?: return
        _state.update { it.copy(pendingConflict = null) }
        resolveConflictPath(pending, takeServer = false)
    }

    /** Take server file and overwrite phone for the conflicted path, then resume sync. */
    fun resolveConflictTakeServer() {
        val pending = _state.value.pendingConflict ?: return
        _state.update { it.copy(pendingConflict = null) }
        resolveConflictPath(pending, takeServer = true)
    }

    private fun resolveConflictPath(pending: PendingConflict, takeServer: Boolean) {
        viewModelScope.launch {
            val mapping = mappingStore.load().firstOrNull { it.cloudFolderId == pending.cloudFolderId }
            if (mapping == null) {
                _state.update { it.copy(syncError = "Mapped folder not found for conflict resolve") }
                return@launch
            }
            if (!auth.isAuthenticated) {
                _state.update { it.copy(syncError = "Log in before resolving conflict") }
                return@launch
            }
            _state.update {
                it.copy(
                    status = if (takeServer) "Taking server version…" else "Uploading local version…",
                    forceRunning = true
                )
            }
            var locked = false
            try {
                if (!SyncFlight.mutex.tryLock()) {
                    val acquired = withTimeoutOrNull(120_000) { SyncFlight.mutex.lock() } != null
                    if (!acquired) throw IllegalStateException("Timed out waiting for sync lock")
                }
                locked = true
                withContext(Dispatchers.IO) {
                    val engineMapping = SyncMapping(
                        id = mapping.cloudFolderId.replace("-", ""),
                        cloudFolderId = mapping.cloudFolderId,
                        localRootPath = mapping.localPath,
                        cloudFolderName = mapping.cloudFolderName
                    )
                    val engine = SyncEngine(metadataStore, localFs, remoteCloud)
                    if (takeServer) engine.forcePullPath(engineMapping, pending.relativePath)
                    else engine.forcePushPath(engineMapping, pending.relativePath)
                }
                if (!takeServer) notifyOtherDevices()
                _state.update {
                    it.copy(
                        forceRunning = false,
                        status = if (takeServer) "Took server: ${pending.relativePath}"
                        else "Uploaded local: ${pending.relativePath}",
                        syncSuccess = if (takeServer) "Server version saved on phone"
                        else "Phone version uploaded to server"
                    )
                }
            } catch (e: Exception) {
                Log.e("ProtoLinkSync", "conflict resolve failed", e)
                _state.update {
                    it.copy(
                        forceRunning = false,
                        status = "Conflict resolve failed",
                        syncError = e.message ?: e.javaClass.simpleName
                    )
                }
                return@launch
            } finally {
                if (locked && SyncFlight.mutex.isLocked) SyncFlight.mutex.unlock()
            }
            // Continue sync — may hit another conflict file.
            requestFullSync()
            refreshNotesTree()
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
                handleSyncFailure(e)
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

    /**
     * Overwrite phone FS from cloud for every mapped folder (no confirm dialog).
     * Used by adb: `am start … --es protolink_force download`
     */
    fun forceDownloadAllMappedNow() {
        viewModelScope.launch {
            android.util.Log.e("ProtoLinkSync", "forceDownloadAllMappedNow start")
            withContext(Dispatchers.IO) { tokenRefresher.refreshBlocking() }
            val ids = mappingStore.load().map { it.cloudFolderId }
            if (ids.isEmpty()) {
                _state.update { it.copy(syncError = "No mapped folders for force download") }
                android.util.Log.e("ProtoLinkSync", "force download: no mapped folders")
                return@launch
            }
            for (id in ids) {
                // runForceMapping launches its own job; await by calling body inline sequentially
                runForceMappingAndAwait(id, push = false)
            }
            refreshNotesTree()
            android.util.Log.e("ProtoLinkSync", "forceDownloadAllMappedNow finished status=${_state.value.status}")
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
        viewModelScope.launch { runForceMappingAndAwait(cloudFolderId, push) }
    }

    private suspend fun runForceMappingAndAwait(cloudFolderId: String, push: Boolean) {
        val mapping = mappingStore.load().firstOrNull { it.cloudFolderId == cloudFolderId }
        if (mapping == null) {
            _state.update { it.copy(syncError = "Mapped folder not found") }
            return
        }
        if (!auth.isAuthenticated) {
            _state.update { it.copy(syncError = "Log in before force sync") }
            return
        }
        if (_state.value.forceRunning) {
            _state.update { it.copy(syncError = "Force sync already running") }
            return
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
                // Force pull of a large Notes tree can wait behind startup sync.
                val acquired = withTimeoutOrNull(600_000) { SyncFlight.mutex.lock() } != null
                if (!acquired) {
                    throw IllegalStateException("Timed out waiting for sync lock")
                }
            }
            locked = true
            android.util.Log.e("ProtoLinkSync", "force ${if (push) "upload" else "download"} locked, pulling…")
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
            android.util.Log.e("ProtoLinkSync", msg)
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
            runCatching {
                java.io.File(appContext.filesDir, "force_last_error.txt")
                    .writeText("${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString().take(4000)}")
            }
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
            refreshUnreadBadges()

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
                        contacts = enriched.map { e ->
                            val prev = s.contacts.firstOrNull { it.userId.equals(e.userId, true) }
                            e.copy(unreadCount = prev?.unreadCount ?: 0)
                        },
                        selectedContact = selectedId?.let { id ->
                            val hit = enriched.firstOrNull { it.userId.equals(id, true) }
                            val prev = s.selectedContact
                            hit?.copy(unreadCount = prev?.unreadCount ?: 0) ?: s.selectedContact
                        } ?: s.selectedContact,
                        status = "Contacts: ${enriched.size}"
                    )
                }
                refreshUnreadBadges()
            }
        } catch (e: Exception) {
            Log.e("ProtoLinkContacts", "loadContacts failed", e)
            _state.update { it.copy(status = "Contacts failed: ${e.message}") }
        }
    }

    fun selectContact(c: ContactItem) {
        _state.update { it.copy(selectedContact = c) }
        loadMessages(c)
        startChatPoll()
    }

    fun clearSelectedContact() {
        chatPollJob?.cancel()
        chatPollJob = null
        loadMessagesJob?.cancel()
        _state.update { it.copy(selectedContact = null, messages = emptyList()) }
    }

    private fun startChatPoll() {
        chatPollJob?.cancel()
        chatPollJob = viewModelScope.launch {
            while (isActive) {
                delay(2_000)
                val open = _state.value.selectedContact ?: break
                loadMessages(open)
            }
        }
    }

    fun loadMessages(c: ContactItem) {
        loadMessagesJob?.cancel()
        loadMessagesJob = viewModelScope.launch {
        val me = auth.current()?.userId ?: return@launch
        val contactId = c.userId
        Log.i("ProtoLinkChat", "loadMessages start contact=$contactId")
        try {
            val all = api.getEntities(
                GetEntitiesRequest(parentIds = listOf(SystemEntities.MESSAGE), includeValues = true, take = 5000)
            )
            val selectedId = _state.value.selectedContact?.userId
            if (selectedId == null || !selectedId.equals(contactId, ignoreCase = true)) {
                Log.w("ProtoLinkChat", "loadMessages skip stale contact=$contactId selected=$selectedId")
                return@launch
            }

            val msgs = all.mapNotNull { e ->
                val vals = e.values ?: return@mapNotNull null
                val texts = vals.mapNotNull { JsonValues.asText(it.value)?.trim()?.takeIf { t -> t.isNotEmpty() } }
                val sender = texts.firstOrNull { it.startsWith("sender:", true) }?.substringAfter(":")
                val receiver = texts.firstOrNull { it.startsWith("receiver:", true) }?.substringAfter(":")
                if (sender.isNullOrBlank() || receiver.isNullOrBlank()) return@mapNotNull null
                val participants = setOf(sender.lowercase(), receiver.lowercase())
                val wanted = setOf(me.lowercase(), contactId.lowercase())
                if (participants != wanted) return@mapNotNull null

                val body = texts.firstOrNull { t ->
                    !t.startsWith("sender:", true) &&
                        !t.startsWith("receiver:", true) &&
                        !t.equals("status:read", true) &&
                        !t.startsWith("status:", true) &&
                        !ChatTime.looksLikeIsoDate(t)
                } ?: return@mapNotNull null

                val valueDateRaw = texts.firstOrNull { ChatTime.looksLikeIsoDate(it) }
                val millis = ChatTime.resolveMessageMillis(e.creationTime, e.updateTime, valueDateRaw)
                val isMine = sender.equals(me, ignoreCase = true)
                val isRead = isMine && texts.any { it.equals("status:read", true) }
                MessageItem(
                    id = e.id,
                    text = body,
                    mine = isMine,
                    timestampMillis = millis,
                    deliveryStatus = when {
                        !isMine -> DeliveryStatus.Sent
                        isRead -> DeliveryStatus.Read
                        else -> DeliveryStatus.Sent
                    }
                )
            }.sortedWith(compareBy({ it.timestampMillis }, { it.id }))

            // Drop pending sends that now exist on the server (same body from me).
            val serverMineBodies = msgs.filter { it.mine }.map { it.text.trim() }.toHashSet()
            val contactKey = contactId.lowercase()
            pendingChatSends.keys.toList().forEach { localId ->
                val pendingContact = pendingChatSendContact[localId] ?: return@forEach
                if (!pendingContact.equals(contactKey, ignoreCase = true)) return@forEach
                val pendingMsg = pendingChatSends[localId] ?: return@forEach
                if (pendingMsg.text.trim() in serverMineBodies) {
                    pendingChatSends.remove(localId)
                    pendingChatSendContact.remove(localId)
                }
            }

            val pendingForChat = pendingChatSends.mapNotNull { (localId, msg) ->
                val pc = pendingChatSendContact[localId] ?: return@mapNotNull null
                if (pc.equals(contactKey, ignoreCase = true)) msg else null
            }
            val merged = (msgs + pendingForChat)
                .distinctBy { it.id }
                .sortedWith(compareBy({ it.timestampMillis }, { it.id }))
            Log.i("ProtoLinkChat", "loadMessages done raw=${all.size} matched=${msgs.size} pending=${pendingForChat.size} merged=${merged.size}")
            val prev = _state.value.messages
            if (prev.size == merged.size &&
                prev.zip(merged).all { (a, b) ->
                    a.id == b.id && a.text == b.text && a.mine == b.mine && a.deliveryStatus == b.deliveryStatus
                }
            ) {
                markOpenChatRead(contactId, merged)
                return@launch
            }
            _state.update { it.copy(messages = merged, status = "Messages: ${merged.size}") }
            markOpenChatRead(contactId, merged)
        } catch (ex: Exception) {
            if (ex is kotlinx.coroutines.CancellationException) throw ex
            showError("Load messages failed", httpDetail(ex), ex)
        }
        }
    }

    fun consumeDraftRestore() {
        _state.update { it.copy(composerDraftRestore = null) }
    }

    fun sendMessage(text: String) = viewModelScope.launch {
        val me = auth.current()?.userId?.trim()?.lowercase()
        if (me.isNullOrBlank()) {
            Log.e("ProtoLinkChat", "sendMessage aborted: no userId")
            showError("Send failed", "Not logged in"); _state.update { it.copy(composerDraftRestore = text) }
            return@launch
        }
        val peer = _state.value.selectedContact?.userId?.trim()?.lowercase()
        if (peer.isNullOrBlank()) {
            Log.e("ProtoLinkChat", "sendMessage aborted: no selected contact")
            showError("Send failed", "No contact selected"); _state.update { it.copy(composerDraftRestore = text) }
            return@launch
        }
        val body = text.trim()
        if (body.isEmpty()) return@launch
        val sentAtMillis = System.currentTimeMillis()

        val optimisticId = "local-${System.nanoTime()}"
        val optimistic = MessageItem(
            optimisticId,
            body,
            mine = true,
            timestampMillis = sentAtMillis,
            deliveryStatus = DeliveryStatus.Sending
        )
        pendingChatSends[optimisticId] = optimistic
        pendingChatSendContact[optimisticId] = peer
        _state.update {
            it.copy(
                messages = it.messages + optimistic,
                status = "Sending.",
                composerClearNonce = it.composerClearNonce + 1
            )
        }
        Log.i("ProtoLinkChat", "sendMessage start me=$me peer=$peer bodyLen=${body.length}")

        try {
            runCatching { ensureMessengerContainer(SystemEntities.SENT, me, "Sent", grantWrite = false) }
            runCatching { ensureMessengerContainer(SystemEntities.RECEIVED, peer, "Received", grantWrite = true) }

            // Omit DateTime value: Instant JSON has caused AddEntity failures; creationTime is enough for UI.
            val created = api.addEntity(
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
                            type = AddValueRequest.TYPE_STRING,
                            value = "sender:$me",
                            parentIds = listOf(SystemEntities.SENT)
                        ),
                        AddValueRequest(
                            type = AddValueRequest.TYPE_STRING,
                            value = "receiver:$peer",
                            parentIds = listOf(SystemEntities.RECEIVED)
                        )
                    ),
                    // Same as Windows: peer must get read permission or GetEntities hides the message.
                    permissions = if (peer != me) {
                        listOf(AddPermissionRequest(permissionForId = peer, canWrite = false))
                    } else {
                        null
                    }
                )
            )
            val id = created.id
            Log.i("ProtoLinkChat", "sendMessage AddEntity ok id=$id")
            if (id.isNullOrBlank()) error("AddEntity returned empty id")

            if (peer != me) {
                try {
                    api.addPermission(AddPermissionRequest(permissionForId = peer, canWrite = false, id = id))
                    Log.i("ProtoLinkChat", "sendMessage AddPermission ok peer=$peer")
                } catch (ex: Exception) {
                    val detail = (ex as? retrofit2.HttpException)?.response()?.errorBody()?.string()?.take(300)
                    showError("Peer share failed", "Message id=$id" + "\n" + (detail ?: httpDetail(ex)), ex)
                }
            }

            val sentAt = java.time.Instant.ofEpochMilli(sentAtMillis)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString()
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

            val sentMsg = MessageItem(
                id = id,
                text = body,
                mine = true,
                timestampMillis = sentAtMillis,
                deliveryStatus = DeliveryStatus.Sent
            )
            pendingChatSends.remove(optimisticId)
            pendingChatSendContact.remove(optimisticId)
            _state.update { s ->
                s.copy(
                    messages = s.messages.map { if (it.id == optimisticId) sentMsg else it },
                    status = "Sent $id"
                )
            }
            _state.value.selectedContact?.let { loadMessages(it) }
        } catch (ex: Exception) {
            pendingChatSends.remove(optimisticId)
            pendingChatSendContact.remove(optimisticId)
            _state.update { s ->
                s.copy(
                    messages = s.messages.filterNot { it.id == optimisticId },
                    composerDraftRestore = body
                )
            }
            showError("Send failed", httpDetail(ex), ex)
        }
    }

    fun openContactById(contactId: String) {
        if (contactId.isBlank()) return
        val hit = _state.value.contacts.firstOrNull { it.userId.equals(contactId, true) }
            ?: ContactItem(contactId, contactId)
        selectContact(hit)
    }

    fun setAppForeground(foreground: Boolean) {
        appInForeground = foreground
    }

    private suspend fun ensureMessengerContainer(
        systemParentId: String,
        userId: String,
        code: String,
        grantWrite: Boolean
    ): Boolean {
        return try {
            val existing = api.getEntities(
                GetEntitiesRequest(parentIds = listOf(systemParentId, userId), includeValues = false, take = 10)
            ).firstOrNull()
            val containerId = existing?.id ?: api.addEntity(
                AddEntityRequest(code = code, parentIds = listOf(systemParentId, userId))
            ).id
            if (containerId.isNullOrBlank()) return false
            if (grantWrite) {
                runCatching {
                    api.addPermission(AddPermissionRequest(permissionForId = userId, canWrite = true, id = containerId))
                }
            }
            true
        } catch (ex: Exception) {
            Log.e("ProtoLinkChat", "ensureMessengerContainer($code) failed", ex)
            false
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

    /** Survives loadMessages races that rewrite state.messages. */
    private val pendingChatSends = java.util.concurrent.ConcurrentHashMap<String, MessageItem>()
    private val pendingChatSendContact = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun refreshNotesTree() = viewModelScope.launch { refreshNotesTreeSuspend() }

    private suspend fun refreshNotesTreeSuspend() {
        val rootUriStr = settingsStore.load().notesRootUri
        if (rootUriStr.isNullOrBlank()) {
            notesRootCache = null
            _state.update {
                it.copy(notesTreeRows = emptyList(), status = "Choose a notes folder")
            }
            return
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

    /** FAB / long-press: parent = selected tree node (or explicit [parentRelativePath]). */
    fun requestCreateNote(parentRelativePath: String? = null) {
        if (settingsStore.load().notesRootUri.isNullOrBlank()) {
            _state.update { it.copy(syncError = "Choose a notes folder first") }
            return
        }
        val selectedRel = parentRelativePath
            ?: _state.value.selectedNoteRelativePath
            ?: ""
        val label = if (selectedRel.isBlank()) {
            "Notes root"
        } else {
            selectedRel.substringAfterLast('/').ifBlank { selectedRel }
        }
        _state.update {
            it.copy(
                pendingCreateNote = PendingCreateNote(
                    parentRelativePath = selectedRel,
                    sectionLabel = label
                )
            )
        }
    }

    /** Long-press “New note here”: parent is the pressed node. */
    fun requestCreateNoteHere(row: NoteTreeRow) {
        requestCreateNote(parentRelativePath = row.relativePath)
    }

    /** From editor menu: destination radios (default = same section / sibling). */
    fun requestCreateNoteFromEditor() {
        if (settingsStore.load().notesRootUri.isNullOrBlank()) {
            _state.update { it.copy(syncError = "Choose a notes folder first") }
            return
        }
        val noteRel = _state.value.selectedNoteRelativePath.orEmpty()
        val noteName = _state.value.selectedNotePath.orEmpty().ifBlank { "note" }
        val parentRel = parentRelativeOf(noteRel)
        val sectionLabel = if (parentRel.isBlank()) "Notes root"
        else parentRel.substringAfterLast('/').ifBlank { parentRel }
        _state.update {
            it.copy(
                pendingCreateNote = PendingCreateNote(
                    parentRelativePath = parentRel,
                    sectionLabel = sectionLabel,
                    fromEditor = true,
                    insideCurrent = false,
                    editorNoteRelativePath = noteRel,
                    editorNoteName = noteName
                )
            )
        }
    }

    fun dismissCreateNote() {
        _state.update { it.copy(pendingCreateNote = null) }
    }

    fun requestDeleteNote(row: NoteTreeRow) {
        val rel = row.relativePath.replace('\\', '/').trim('/')
        if (rel.isEmpty()) {
            _state.update { it.copy(status = "Cannot delete the notes root") }
            return
        }
        _state.update {
            it.copy(
                pendingDeleteNote = PendingDeleteNote(
                    relativePath = rel,
                    name = row.name,
                    hasChildren = row.hasChildren
                )
            )
        }
    }

    fun requestDeleteCurrentNote() {
        val rel = _state.value.selectedNoteRelativePath?.replace('\\', '/')?.trim('/').orEmpty()
        if (rel.isEmpty()) {
            _state.update { it.copy(status = "Cannot delete the notes root") }
            return
        }
        val name = _state.value.selectedNotePath?.ifBlank { rel.substringAfterLast('/') }
            ?: rel.substringAfterLast('/')
        val hasChildren = _state.value.notesTreeRows
            .firstOrNull { it.relativePath.replace('\\', '/').trim('/').equals(rel, ignoreCase = true) }
            ?.hasChildren
            ?: run {
                val root = notesRootCache ?: return@run false
                var cur = root
                for (part in rel.split('/').filter { it.isNotEmpty() }) {
                    cur = cur.children.firstOrNull { it.name.equals(part, ignoreCase = true) }
                        ?: return@run false
                }
                cur.children.isNotEmpty()
            }
        _state.update {
            it.copy(
                pendingDeleteNote = PendingDeleteNote(
                    relativePath = rel,
                    name = name,
                    hasChildren = hasChildren
                )
            )
        }
    }

    fun dismissDeleteNote() {
        _state.update { it.copy(pendingDeleteNote = null) }
    }

    fun confirmDeleteNote() {
        val pending = _state.value.pendingDeleteNote ?: return
        _state.update { it.copy(pendingDeleteNote = null) }
        deleteNote(pending.relativePath, pending.name)
    }

    /**
     * Recursive delete of page folder under notes root (Windows parity).
     * Empty [relativePath] = root — refused.
     */
    fun deleteNote(relativePath: String, displayName: String? = null) {
        viewModelScope.launch {
            val rootUri = settingsStore.load().notesRootUri
            if (rootUri.isNullOrBlank()) {
                _state.update { it.copy(syncError = "Choose a notes folder first") }
                return@launch
            }
            val target = relativePath.replace('\\', '/').trim('/')
            if (target.isEmpty()) {
                _state.update { it.copy(status = "Cannot delete the notes root") }
                return@launch
            }
            val name = displayName?.ifBlank { null } ?: target.substringAfterLast('/')
            val openRel = _state.value.selectedNoteRelativePath?.replace('\\', '/')?.trim('/').orEmpty()
            val affectsOpen = openRel.isNotEmpty() &&
                (openRel.equals(target, ignoreCase = true) ||
                    openRel.startsWith("$target/", ignoreCase = true))
            if (affectsOpen) {
                cancelNotesPendingSave()
            }
            try {
                withContext(Dispatchers.IO) {
                    localFs.delete(rootUri, target, isFolder = true)
                }
            } catch (e: Exception) {
                Log.e("ProtoLinkNotes", "deleteNote failed $target", e)
                val msg = e.message ?: e.javaClass.simpleName
                _state.update { it.copy(syncError = "Could not delete note: $msg") }
                writeDeleteNoteDebugResult("FAIL delete=$target msg=$msg")
                runCatching { refreshNotesTreeSuspend() }
                return@launch
            }
            if (affectsOpen) {
                clearSelectedNote()
            }
            refreshNotesTreeSuspend()
            _state.update { it.copy(status = "Deleted “$name”") }
            writeDeleteNoteDebugResult("OK delete=$target")
            requestLocalPush()
        }
    }

    private fun writeDeleteNoteDebugResult(line: String) {
        runCatching {
            java.io.File(appContext.filesDir, "delete_note_last.txt")
                .writeText("${java.time.Instant.now()}\n$line\n", Charsets.UTF_8)
            Log.e("ProtoLinkNotes", "delete_note_last: $line")
        }
    }

    fun updateCreateNoteTitle(title: String) {
        val pending = _state.value.pendingCreateNote ?: return
        _state.update {
            it.copy(pendingCreateNote = pending.copy(titleDraft = title, titleError = null))
        }
    }

    fun setCreateNoteInsideCurrent(inside: Boolean) {
        val pending = _state.value.pendingCreateNote ?: return
        if (!pending.fromEditor) return
        val noteRel = pending.editorNoteRelativePath.orEmpty()
        val parentRel = if (inside) noteRel else parentRelativeOf(noteRel)
        val label = when {
            inside -> pending.editorNoteName?.ifBlank { "this note" } ?: "this note"
            parentRel.isBlank() -> "Notes root"
            else -> parentRel.substringAfterLast('/').ifBlank { parentRel }
        }
        _state.update {
            it.copy(
                pendingCreateNote = pending.copy(
                    insideCurrent = inside,
                    parentRelativePath = parentRel,
                    sectionLabel = label,
                    titleError = null
                )
            )
        }
    }

    fun confirmCreateNote() {
        val pending = _state.value.pendingCreateNote ?: return
        val raw = pending.titleDraft.trim()
        if (raw.isEmpty()) {
            _state.update {
                it.copy(pendingCreateNote = pending.copy(titleError = "Title is required"))
            }
            return
        }
        val name = sanitizeNoteName(raw)
        if (name.isEmpty()) {
            _state.update {
                it.copy(pendingCreateNote = pending.copy(titleError = "Title has no valid characters"))
            }
            return
        }
        if (name != raw.trim()) {
            // Soft sanitize — keep going with cleaned name; surface if empty already handled.
        }
        createNote(pending.parentRelativePath, name)
    }

    /**
     * Create folder + index.html under [parentRelativePath] (empty = root).
     * Opens the new note in the editor (Windows parity).
     */
    fun createNote(parentRelativePath: String, name: String) {
        viewModelScope.launch {
            val rootUri = settingsStore.load().notesRootUri
            if (rootUri.isNullOrBlank()) {
                _state.update { it.copy(pendingCreateNote = null, syncError = "Choose a notes folder first") }
                return@launch
            }
            val parent = parentRelativePath.replace('\\', '/').trim('/')
            val folderName = sanitizeNoteName(name)
            if (folderName.isEmpty()) {
                _state.update {
                    it.copy(
                        pendingCreateNote = it.pendingCreateNote?.copy(titleError = "Title is required")
                    )
                }
                return@launch
            }
            val childRel = if (parent.isEmpty()) folderName else "$parent/$folderName"
            val created: CreatedNoteRefs
            try {
                created = withContext(Dispatchers.IO) {
                    val treeUri = Uri.parse(rootUri)
                    // Duplicate sibling check (case-insensitive).
                    val parentId = if (parent.isEmpty()) {
                        ru.protolink.communicator.data.SafTreeLister.treeDocumentId(treeUri)
                    } else {
                        NotesTreeBuilder.folderDocumentId(treeUri, parent)
                    }
                    val siblings = ru.protolink.communicator.data.SafTreeLister.listChildren(
                        appContext, treeUri, parentId
                    )
                    if (siblings.any { it.isDirectory && it.name.equals(folderName, ignoreCase = true) }) {
                        throw IllegalStateException("A note with this name already exists")
                    }
                    localFs.createDirectory(rootUri, childRel)
                    val html =
                        "<h1>${android.text.Html.escapeHtml(folderName)}</h1>\n<p><br></p>"
                    localFs.writeFile(
                        rootUri,
                        "$childRel/index.html",
                        html.toByteArray(Charsets.UTF_8)
                    )
                    // Resolve *actual* SAF ids (guess "$root/$rel" is wrong on some OEMs).
                    val folderEntry = ru.protolink.communicator.data.SafTreeLister.listChildren(
                        appContext, treeUri, parentId
                    ).firstOrNull { it.isDirectory && it.name.equals(folderName, ignoreCase = true) }
                    val safId = folderEntry?.documentId
                        ?: NotesTreeBuilder.folderDocumentId(treeUri, childRel)
                    val indexUri = ru.protolink.communicator.data.SafTreeLister.resolveIndexHtml(
                        appContext, treeUri, safId
                    ) ?: NotesTreeBuilder.resolveIndexViaDocumentFile(appContext, treeUri, childRel)
                    CreatedNoteRefs(safId, folderEntry?.uri, indexUri)
                }
            } catch (e: Exception) {
                Log.e("ProtoLinkNotes", "createNote failed", e)
                val msg = e.message ?: e.javaClass.simpleName
                if (msg.contains("already exists", ignoreCase = true)) {
                    _state.update {
                        it.copy(
                            pendingCreateNote = it.pendingCreateNote?.copy(
                                titleError = "A note with this name already exists"
                            )
                        )
                    }
                } else {
                    _state.update {
                        it.copy(pendingCreateNote = null, syncError = "Could not create note: $msg")
                    }
                }
                return@launch
            }
            _state.update { it.copy(pendingCreateNote = null, status = "Created “$folderName”") }
            val treeUri = Uri.parse(rootUri)
            val folderUri = created.folderUri
                ?: ru.protolink.communicator.data.SafTreeLister.documentUri(treeUri, created.safDocumentId)
            val openRow = NoteTreeRow(
                documentId = folderUri.toString(),
                safDocumentId = created.safDocumentId,
                name = folderName,
                relativePath = childRel,
                depth = childRel.count { it == '/' },
                hasChildren = false,
                expanded = false,
                folderUri = folderUri.toString(),
                indexUri = created.indexUri?.toString()
            )
            // Await open — selectNotesNode is fire-and-forget and raced with tree refresh.
            openNotesNode(openRow)
            val opened = !_state.value.selectedNoteIndexUri.isNullOrBlank() &&
                _state.value.selectedNoteRelativePath == childRel
            writeCreateNoteDebugResult(
                if (opened) "OK open=$childRel index=${_state.value.selectedNoteIndexUri}"
                else "FAIL open=$childRel status=${_state.value.status}"
            )
            refreshNotesTreeSuspend()
            expandPathAncestors(childRel)
            refreshNotesTreeSuspend()
            requestLocalPush()
        }
    }

    private fun writeCreateNoteDebugResult(line: String) {
        runCatching {
            java.io.File(appContext.filesDir, "create_note_last.txt")
                .writeText("${java.time.Instant.now()}\n$line\n", Charsets.UTF_8)
            Log.e("ProtoLinkNotes", "create_note_last: $line")
        }
    }

    private data class CreatedNoteRefs(
        val safDocumentId: String,
        val folderUri: Uri?,
        val indexUri: Uri?
    )

    private fun parentRelativeOf(relativePath: String): String {
        val n = relativePath.replace('\\', '/').trim('/')
        val i = n.lastIndexOf('/')
        return if (i <= 0) "" else n.substring(0, i)
    }

    private fun sanitizeNoteName(raw: String): String {
        var s = raw.trim()
        val invalid = Regex("""[\\/:*?"<>|]""")
        s = invalid.replace(s, " ").replace(Regex("""\s+"""), " ").trim()
        while (s.startsWith('.')) s = s.drop(1).trim()
        while (s.endsWith('.')) s = s.dropLast(1).trim()
        if (s.length > 120) s = s.take(120).trim()
        return s
    }

    private fun expandPathAncestors(relativePath: String) {
        val root = notesRootCache ?: return
        val parts = relativePath.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
        val ids = mutableSetOf(root.documentId)
        var cur = root
        for (part in parts) {
            val child = cur.children.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: break
            ids.add(child.documentId)
            cur = child
        }
        // Also expand parent chain without the leaf if leaf is the new page (still expand parents).
        _state.update { it.copy(notesExpandedIds = it.notesExpandedIds + ids) }
    }

    /** Windows parity: selecting a folder node opens/edits that folder's index.html. */
    fun selectNotesNode(row: NoteTreeRow) = viewModelScope.launch {
        openNotesNode(row)
    }

    private suspend fun openNotesNode(row: NoteTreeRow) {
        val rootUriStr = settingsStore.load().notesRootUri ?: return
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
            stopNotesDiskPoller()
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
