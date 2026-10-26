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
import ru.protolink.communicator.data.CloudRecentStore
import ru.protolink.communicator.data.MappingStore
import ru.protolink.communicator.data.MessengerNotifier
import ru.protolink.communicator.data.MessengerReadStore
import ru.protolink.communicator.data.NotesSiblingFiles
import ru.protolink.communicator.data.NotesTreeBuilder
import ru.protolink.communicator.data.SafTreeLister
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
import ru.protolink.communicator.data.messenger.MessengerChatFolderResolver
import ru.protolink.communicator.data.messenger.PendingAttachmentDraft
import ru.protolink.communicator.data.messenger.ProgressRequestBody
import ru.protolink.communicator.util.ChatTime
import ru.protolink.communicator.util.CloudFolderTree
import ru.protolink.communicator.util.CloudListingStatus
import ru.protolink.communicator.util.CloudMainParent
import ru.protolink.communicator.util.CloudRecentList
import ru.protolink.communicator.util.CloudSharedScope
import ru.protolink.communicator.util.CloudVirtualNodes
import ru.protolink.communicator.util.EntityMeta
import ru.protolink.communicator.util.JsonValues
import ru.protolink.communicator.util.MapLocalFolderStrings
import ru.protolink.communicator.util.MessageStringValues
import ru.protolink.communicator.util.MessengerAttachmentRender
import ru.protolink.communicator.util.MessengerConversationScope
import ru.protolink.communicator.util.MessengerAtomicSend
import ru.protolink.communicator.util.MessengerImageDownscale
import ru.protolink.communicator.util.MessengerMediaLimits
import ru.protolink.communicator.util.MessengerSendStateMachine
import androidx.core.content.FileProvider
import ru.protolink.communicator.util.NoteRemoteIdResolver
import ru.protolink.communicator.util.NotesDeleteNavigate
import ru.protolink.communicator.util.NotesTreeShare
import ru.protolink.communicator.util.PhotosLibrary
import ru.protolink.communicator.util.ShareAccountStrings
import ru.protolink.communicator.util.ShareAddPeople
import ru.protolink.communicator.util.ShareSheetCloudStrings
import ru.protolink.communicator.util.ShareSheetLogic
import ru.protolink.communicator.util.ShareSheetStrings
import ru.protolink.communicator.ui.share.ShareContactPickerItem
import ru.protolink.communicator.ui.share.SharePersonUi
import ru.protolink.communicator.ui.share.ShareSheetUiState
import ru.protolink.communicator.data.api.CreateShareLinkRequest
import ru.protolink.communicator.data.api.LeaveSharedWithMeRequest
import ru.protolink.communicator.data.api.RemovePermissionRequest
import ru.protolink.communicator.data.api.RevokeShareLinkRequest
import ru.protolink.communicator.data.api.UpdateShareLinkRequest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class CloudItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val isVirtualEntry: Boolean = false,
    /** True for Shared-with-me listing rows (share roots from S6). */
    val isShareRoot: Boolean = false,
    val subtitle: String? = null,
    /** MIME when known (CloudFile open / preview). */
    val mimeType: String? = null
)

/** Persistent Folders tree row (AT-PC1 / AT-PC8). */
data class CloudFolderTreeRow(
    val id: String,
    val name: String,
    val depth: Int,
    val selected: Boolean
)

/** Open CloudFile preview (AT-PC2 / AT-PC4). */
data class CloudOpenFile(
    val id: String,
    val name: String,
    val mimeType: String?,
    /** Parent folder id used for Up / tree highlight (MainParent). */
    val parentFolderId: String?
)
data class ContactItem(
    val userId: String,
    val displayName: String,
    val unreadCount: Int = 0
) {
    val hasUnread: Boolean get() = unreadCount > 0
    val unreadLabel: String get() = if (unreadCount > 99) "99+" else unreadCount.toString()
}

enum class DeliveryStatus { Uploading, Sending, Sent, Read, Failed }

data class MessageItem(
    val id: String,
    val text: String,
    val mine: Boolean,
    val timestampMillis: Long,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.Sent,
    val attachmentIds: List<String> = emptyList(),
    /** 0..1 while Uploading; null when not applicable. */
    val uploadProgress: Float? = null,
    /** Failed footer / oversize / offline copy (UX §7). */
    val statusLine: String? = null,
    /** Partner for Retry when chat selection may have changed. */
    val partnerUserId: String? = null,
    /** In-flight local drafts so Retry reuses CloudFile ids (AT-M26). */
    val pendingLocalAttachments: List<ru.protolink.communicator.data.messenger.PendingAttachmentDraft>? = null
) {
    val timeLabel: String get() = ru.protolink.communicator.util.ChatTime.formatTime(timestampMillis)
    val dayLabel: String get() = ru.protolink.communicator.util.ChatTime.formatDayLabel(timestampMillis)
    val showFailedActions: Boolean get() = mine && deliveryStatus == DeliveryStatus.Failed
    val showTicks: Boolean
        get() = mine && deliveryStatus != DeliveryStatus.Uploading
            && deliveryStatus != DeliveryStatus.Sending
            && deliveryStatus != DeliveryStatus.Failed
    val ticksText: String
        get() = when (deliveryStatus) {
            DeliveryStatus.Uploading, DeliveryStatus.Sending, DeliveryStatus.Failed -> ""
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
    /** Pending camera/gallery chips before Send. */
    val pendingAttachments: List<ru.protolink.communicator.data.messenger.PendingAttachmentDraft> = emptyList(),
    /** Oversize / cap / camera-permission toast under composer. */
    val attachStatusMessage: String? = null,
    val settings: AppSettings = AppSettings(),
    val cloudItems: List<CloudItem> = emptyList(),
    val breadcrumb: List<Pair<String, String>> = emptyList(),
    /** Persistent Folders tree (AT-PC1 / AT-PC8); empty on hub / virtual listings. */
    val cloudFolderTree: List<CloudFolderTreeRow> = emptyList(),
    /** When set, Cloud shows file preview instead of (or above) folder grid — AT-PC2/PC4. */
    val cloudOpenFile: CloudOpenFile? = null,
    /** Decoded preview bytes for [cloudOpenFile] when an image; null while loading or non-image. */
    val cloudPreviewBytes: ByteArray? = null,
    val cloudPreviewError: String? = null,
    /** Share-root id (lowercase) → CanWrite; retained while browsing Shared-with-me descendants. */
    val shareRootCanWrite: Map<String, Boolean> = emptyMap(),
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
    val selectedNoteSafDocumentId: String? = null,
    val selectedNoteIndexUri: String? = null,
    /** E5/E5a Folder files under the open note (direct-child files only; empty → hide panel). */
    val notesSiblingFiles: List<NotesSiblingFiles.SiblingFile> = emptyList(),
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
    val apiVersionText: String = "",
    val shareSheet: ShareSheetUiState = ShareSheetUiState(),
    /** Bumped when Notes lazy share-affordance cache changes (tree glyphs). */
    val notesShareAffordanceEpoch: Int = 0,
    /** E6: bump to switch Main tab to Cloud before showing the add-local wizard. */
    val preferCloudTabNonce: Int = 0,
    val mapWizardOpen: Boolean = false,
    val mapWizardPrefillUri: String? = null,
    val mapWizardCandidates: List<MapLocalFolderCandidate> = emptyList(),
    val mapWizardBusy: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val api: ProtoLinkApi,
    private val settingsStore: SettingsStore,
    private val mappingStore: MappingStore,
    private val cloudRecentStore: CloudRecentStore,
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
    private val chatFolderResolver by lazy { MessengerChatFolderResolver(api, settingsStore) }
    private val uploadGates = ConcurrentHashMap<String, Mutex>()
    /** RemoteId (lowercase) → has external share (Notes tree lazy glyph). */
    private val notesExternalShareByRemoteId = ConcurrentHashMap<String, Boolean>()
    @Volatile private var notesSharePrefetchBusy = false
    @Volatile var appInForeground: Boolean = true
    /** Photos library CloudFolder id from photosfolder: (E7). */
    private var photosLibraryId: String? = null
    /** Code by folder id for tree root pick (CloudRoot vs CloudFolder). */
    private val folderTreeCodes = mutableMapOf<String, String>()
    private var folderTreeRefreshJob: Job? = null
    private var previewLoadJob: Job? = null

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

    /** Exposed for Compose messenger attachment / unfurl loads (reader's own token via Retrofit). */
    val protoLinkApi: ProtoLinkApi get() = api

    fun currentUserId(): String? = auth.current()?.userId

    fun logout() {
        stopAutoSyncInterval()
        chatPollJob?.cancel()
        chatPollJob = null
        loadMessagesJob?.cancel()
        signalR.stop()
        auth.logout()
        ru.protolink.communicator.util.MessengerThumbMemoryCache.clear()
        ru.protolink.communicator.util.MessengerUnfurlCache.clear()
        _state.update { it.copy(authenticated = false, login = "", contacts = emptyList(), messages = emptyList(), selectedContact = null) }
    }

    /** Settings → Account Change password (Bearer POST changePassword). */
    suspend fun changePasswordAsync(
        currentPassword: String,
        newPassword: String
    ): ru.protolink.communicator.data.ChangePasswordOutcome =
        auth.changePassword(currentPassword, newPassword)

    private var cloudRootId: String? = null
    private var currentFolderId: String? = null

    private fun uiLang(): String = Locale.getDefault().toLanguageTag()

    private fun entityDisplayName(e: EntityDto): String =
        e.values?.firstOrNull()?.value?.asString?.trim()?.takeIf { it.isNotEmpty() }
            ?: e.code?.takeIf { it.isNotBlank() }
            ?: e.id.take(8)

    suspend fun ensureCloudRoot() {
        val userId = auth.current()?.userId ?: return
        val list = api.getEntities(GetEntitiesRequest(parentIds = listOf(userId), includeValues = false, take = 200))
        val root = list.firstOrNull { it.code == CloudCodes.CLOUD_ROOT }
        val id = root?.id ?: api.addEntity(
            AddEntityRequest(code = CloudCodes.CLOUD_ROOT, parentIds = listOf(userId))
        ).id ?: return
        cloudRootId = id
        openCloudHub()
    }

    /** AT-S17: Cloud hub with My Cloud + Photos + Shared with me + Recent siblings. */
    fun openCloudHub() {
        val lang = uiLang()
        val hubName = ShareSheetCloudStrings.cloudHub(lang)
        currentFolderId = CloudVirtualNodes.HUB_ID
        clearCloudFileOpen()
        _state.update {
            it.copy(
                breadcrumb = listOf(CloudVirtualNodes.HUB_ID to hubName),
                cloudFolderTree = emptyList()
            )
        }
        loadCloudFolder(CloudVirtualNodes.HUB_ID)
    }

    fun loadCloudFolder(folderId: String, statusAfterLoad: String? = null) = viewModelScope.launch {
        currentFolderId = folderId
        clearCloudFileOpen()
        val lang = uiLang()
        if (folderId.equals(CloudVirtualNodes.HUB_ID, ignoreCase = true)) {
            ensurePhotosLibraryId()
        }
        val items = when {
            folderId.equals(CloudVirtualNodes.HUB_ID, ignoreCase = true) -> buildHubListing(lang)
            folderId.equals(CloudVirtualNodes.SHARED_WITH_ME_ID, ignoreCase = true) ->
                loadSharedWithMeListing(lang)
            folderId.equals(CloudVirtualNodes.RECENT_ID, ignoreCase = true) ->
                loadRecentListing()
            else -> {
                val children = api.getEntities(
                    GetEntitiesRequest(parentIds = listOf(folderId), includeValues = true, take = 500)
                )
                children.mapNotNull { e ->
                    val name = entityDisplayName(e)
                    when (e.code) {
                        CloudCodes.CLOUD_FOLDER, CloudCodes.CLOUD_ROOT -> {
                            folderTreeCodes[e.id.lowercase()] = e.code ?: CloudCodes.CLOUD_FOLDER
                            CloudItem(e.id, name, true)
                        }
                        CloudCodes.CLOUD_FILE -> CloudItem(
                            id = e.id,
                            name = name,
                            isFolder = false,
                            mimeType = EntityMeta.mimeType(e)
                        )
                        else -> null
                    }
                }
            }
        }
        _state.update {
            it.copy(cloudItems = items, status = CloudListingStatus.afterReload(statusAfterLoad))
        }
        refreshFolderTree()
    }

    private suspend fun ensurePhotosLibraryId() {
        if (photosLibraryId != null || auth.current()?.userId == null) return
        try {
            val userId = auth.current()?.userId ?: return
            val kids = api.getEntities(
                GetEntitiesRequest(parentIds = listOf(userId), includeValues = true, take = 200)
            )
            photosLibraryId = PhotosLibrary.resolveLibraryIdFromUserChildren(kids)
        } catch (_: Exception) {
            // Best-effort; hub Photos row omitted when unresolved.
        }
    }

    private fun buildHubListing(lang: String): List<CloudItem> {
        val myCloudId = cloudRootId ?: return emptyList()
        val rows = mutableListOf(
            CloudItem(
                id = myCloudId,
                name = ShareSheetCloudStrings.myCloud(lang),
                isFolder = true,
                isVirtualEntry = false
            )
        )
        val photosLib = photosLibraryId
        if (!photosLib.isNullOrBlank()) {
            rows.add(
                CloudItem(
                    id = photosLib,
                    name = ShareSheetCloudStrings.photos(lang),
                    isFolder = true,
                    isVirtualEntry = false
                )
            )
        }
        rows.add(
            CloudItem(
                id = CloudVirtualNodes.SHARED_WITH_ME_ID,
                name = ShareSheetCloudStrings.sharedWithMe(lang),
                isFolder = true,
                isVirtualEntry = true
            )
        )
        rows.add(
            CloudItem(
                id = CloudVirtualNodes.RECENT_ID,
                name = ShareSheetCloudStrings.recent(lang),
                isFolder = true,
                isVirtualEntry = true
            )
        )
        return rows
    }

    /**
     * Recent (C6): show durable MRU; silently drop deleted/forbidden (prune store).
     */
    private suspend fun loadRecentListing(): List<CloudItem> {
        val userId = auth.current()?.userId ?: return emptyList()
        val stored = cloudRecentStore.load(userId)
        if (stored.isEmpty()) return emptyList()

        val ids = stored.map { it.entityId }
        val found = try {
            api.getEntities(GetEntitiesRequest(ids = ids, includeValues = true, take = ids.size))
        } catch (_: Exception) {
            // Offline / transient: show cached names without crashing chrome.
            return stored.map { e ->
                CloudItem(id = e.entityId, name = e.name, isFolder = e.isFolder)
            }
        }

        val byId = found
            .filter { isNavigableCloudCode(it.code) }
            .associateBy { it.id.lowercase() }
        val fresh = ArrayList<CloudItem>()
        val pruned = ArrayList<CloudRecentList.Entry>()
        for (entry in stored) {
            val entity = byId[entry.entityId.lowercase()] ?: continue
            val name = entityDisplayName(entity)
            val isFolder =
                entity.code == CloudCodes.CLOUD_FOLDER || entity.code == CloudCodes.CLOUD_ROOT
            fresh.add(CloudItem(id = entity.id, name = name, isFolder = isFolder))
            pruned.add(
                CloudRecentList.Entry(
                    entityId = entity.id,
                    name = name,
                    isFolder = isFolder,
                    openedUtcMillis = entry.openedUtcMillis
                )
            )
        }
        if (pruned.size != stored.size) {
            cloudRecentStore.replace(userId, pruned)
        }
        return fresh
    }

    private fun isNavigableCloudCode(code: String?): Boolean =
        code == CloudCodes.CLOUD_FILE ||
            code == CloudCodes.CLOUD_FOLDER ||
            code == CloudCodes.CLOUD_ROOT

    private fun recordRecentOpen(entityId: String, name: String, isFolder: Boolean) {
        if (!CloudRecentList.shouldRecordOpen(entityId, cloudRootId)) return
        val userId = auth.current()?.userId ?: return
        try {
            cloudRecentStore.recordOpen(
                userId,
                CloudRecentList.Entry(
                    entityId = entityId,
                    name = name,
                    isFolder = isFolder,
                    openedUtcMillis = System.currentTimeMillis()
                )
            )
        } catch (_: Exception) {
            // Best-effort; navigation must not fail.
        }
    }

    private suspend fun loadSharedWithMeListing(lang: String): List<CloudItem> {
        val list = api.getEntities(
            GetEntitiesRequest(sharedWithMe = true, includeValues = true, take = 500)
        )
        val creatorIds = list.mapNotNull { it.userWhoCreatedId?.takeIf { id -> id.isNotBlank() } }
            .distinct()
        val creatorNames = mutableMapOf<String, String>()
        if (creatorIds.isNotEmpty()) {
            val creators = api.getEntities(
                GetEntitiesRequest(ids = creatorIds, includeValues = true, take = creatorIds.size)
            )
            for (c in creators) {
                creatorNames[c.id.lowercase()] = entityDisplayName(c)
            }
        }
        val canWriteByRoot = mutableMapOf<String, Boolean>()
        val items = list
            .sortedWith(
                compareByDescending<EntityDto> {
                    it.code == CloudCodes.CLOUD_FOLDER || it.code == CloudCodes.CLOUD_ROOT
                }.thenByDescending { it.updateTime.orEmpty() }
                    .thenBy { entityDisplayName(it).lowercase() }
            )
            .map { e ->
                val canWrite = e.canWrite == true
                canWriteByRoot[e.id.lowercase()] = canWrite
                val creatorId = e.userWhoCreatedId?.takeIf { it.isNotBlank() }
                val creatorKey = creatorId?.lowercase()
                val creatorName = when {
                    creatorKey != null && creatorNames[creatorKey]?.isNotBlank() == true ->
                        creatorNames.getValue(creatorKey)
                    creatorId != null -> creatorId.replace("-", "").take(8)
                    else -> "—"
                }
                val isFolder =
                    e.code == CloudCodes.CLOUD_FOLDER || e.code == CloudCodes.CLOUD_ROOT
                CloudItem(
                    id = e.id,
                    name = entityDisplayName(e),
                    isFolder = isFolder,
                    isShareRoot = true,
                    subtitle = ShareSheetCloudStrings.sharedRowSubtitle(lang, creatorName, canWrite)
                )
            }
        _state.update { it.copy(shareRootCanWrite = canWriteByRoot.toMap()) }
        return items
    }

    /**
     * UX-LS: remove caller's direct personal grant on a Shared-with-me share root.
     * Confirm is owned by the UI; this performs the Api call + listing refresh.
     */
    fun leaveShared(entityId: String) = viewModelScope.launch {
        val id = entityId.takeIf { it.isNotBlank() && !CloudVirtualNodes.isVirtual(it) } ?: return@launch
        val lang = uiLang()
        val wasOpenRoot = currentFolderId.equals(id, ignoreCase = true)
        val leaveSuccess = ShareSheetCloudStrings.leaveSuccess(lang)
        try {
            api.leaveSharedWithMe(LeaveSharedWithMeRequest(entityId = id))
            val nextCanWrite = _state.value.shareRootCanWrite.toMutableMap().apply {
                remove(id.lowercase())
            }
            val nextItems = _state.value.cloudItems.filterNot { it.id.equals(id, ignoreCase = true) }
            _state.update {
                it.copy(
                    shareRootCanWrite = nextCanWrite,
                    cloudItems = nextItems,
                    status = leaveSuccess
                )
            }
            if (wasOpenRoot) {
                navigateToSharedWithMeListing(lang, statusAfterLoad = leaveSuccess)
            } else if (currentFolderId.equals(CloudVirtualNodes.SHARED_WITH_ME_ID, ignoreCase = true)) {
                loadCloudFolder(CloudVirtualNodes.SHARED_WITH_ME_ID, statusAfterLoad = leaveSuccess)
            }
        } catch (_: Exception) {
            _state.update { it.copy(status = ShareSheetCloudStrings.leaveFailed(lang)) }
        }
    }

    private fun navigateToSharedWithMeListing(lang: String, statusAfterLoad: String? = null) {
        val hub = CloudVirtualNodes.HUB_ID to ShareSheetCloudStrings.cloudHub(lang)
        val shared = CloudVirtualNodes.SHARED_WITH_ME_ID to ShareSheetCloudStrings.sharedWithMe(lang)
        _state.update { it.copy(breadcrumb = listOf(hub, shared)) }
        loadCloudFolder(CloudVirtualNodes.SHARED_WITH_ME_ID, statusAfterLoad = statusAfterLoad)
    }

    /** Jump to a mapped folder (stories strip) with a sensible breadcrumb. */
    fun openMappedFolder(folderId: String, name: String) {
        val lang = uiLang()
        val root = cloudRootId
        val hub = CloudVirtualNodes.HUB_ID to ShareSheetCloudStrings.cloudHub(lang)
        val myCloud = root?.let { id -> id to ShareSheetCloudStrings.myCloud(lang) }
        val leaf = folderId to name.ifBlank { "Folder" }
        val crumbs = when {
            root != null && !root.equals(folderId, true) && myCloud != null ->
                listOf(hub, myCloud, leaf)
            root != null && root.equals(folderId, true) && myCloud != null ->
                listOf(hub, myCloud)
            else -> listOf(hub, leaf)
        }
        _state.update { it.copy(breadcrumb = crumbs) }
        loadCloudFolder(folderId)
        recordRecentOpen(folderId, name.ifBlank { "Folder" }, isFolder = true)
    }

    /** Open Cloud folder or file row (AT-PC3/PC4 entity open; anti-SAP). */
    fun openCloudFolder(item: CloudItem) {
        // Recent rows are not children of the virtual folder — open via real Cloud path.
        if (currentFolderId.equals(CloudVirtualNodes.RECENT_ID, ignoreCase = true)) {
            openRecentItem(item)
            return
        }
        if (!item.isFolder) {
            openCloudFileInCurrentFolder(item)
            return
        }
        // Hub Photos row → library folder entity (anti-SAP AT-PC6).
        val photosLib = photosLibraryId
        if (currentFolderId.equals(CloudVirtualNodes.HUB_ID, ignoreCase = true) &&
            photosLib != null &&
            item.id.equals(photosLib, ignoreCase = true)
        ) {
            val lang = uiLang()
            val hub = CloudVirtualNodes.HUB_ID to ShareSheetCloudStrings.cloudHub(lang)
            _state.update {
                it.copy(breadcrumb = listOf(hub, item.id to ShareSheetCloudStrings.photos(lang)))
            }
            loadCloudFolder(item.id)
            recordRecentOpen(item.id, ShareSheetCloudStrings.photos(lang), isFolder = true)
            return
        }
        _state.update { it.copy(breadcrumb = it.breadcrumb + (item.id to item.name)) }
        loadCloudFolder(item.id)
        recordRecentOpen(item.id, item.name, isFolder = true)
    }

    /** Tree row click → open that folder entity (AT-PC1/PC8). */
    fun openCloudFolderTreeRow(folderId: String) {
        if (folderId.isBlank() || CloudVirtualNodes.isVirtual(folderId)) return
        if (currentFolderId.equals(folderId, ignoreCase = true) && _state.value.cloudOpenFile == null) {
            return
        }
        viewModelScope.launch { tryOpenCloudEntity(folderId) }
    }

    /**
     * Open a Recent row like normal browse; missing/forbidden skipped silently and pruned.
     * Files open with MainParent crumbs + preview (AT-PC2/PC4).
     */
    private fun openRecentItem(item: CloudItem) = viewModelScope.launch {
        val userId = auth.current()?.userId ?: return@launch
        try {
            val found = api.getEntities(
                GetEntitiesRequest(ids = listOf(item.id), includeValues = true, take = 1)
            )
            val entity = found.firstOrNull()
            if (entity == null || !isNavigableCloudCode(entity.code)) {
                cloudRecentStore.remove(userId, item.id)
                loadCloudFolder(CloudVirtualNodes.RECENT_ID)
                return@launch
            }
            tryOpenCloudEntity(entity.id)
        } catch (_: Exception) {
            // Silent skip — keep listing; no raw errors in chrome.
        }
    }

    fun cloudNavigateTo(index: Int) {
        val crumb = _state.value.breadcrumb.getOrNull(index) ?: return
        // AT-PC5: from a file, Up / crumb to last folder clears file preview first.
        if (_state.value.cloudOpenFile != null && index == _state.value.breadcrumb.lastIndex) {
            clearCloudFileOpen()
            refreshFolderTree()
            return
        }
        _state.update { it.copy(breadcrumb = it.breadcrumb.take(index + 1)) }
        loadCloudFolder(crumb.first)
    }

    /** Header Up: from file → containing folder; else parent crumb (AT-PC5). */
    fun cloudNavigateUp() {
        val open = _state.value.cloudOpenFile
        if (open != null) {
            clearCloudFileOpen()
            refreshFolderTree()
            return
        }
        val last = _state.value.breadcrumb.lastIndex
        if (last < 1) return
        cloudNavigateTo(last - 1)
    }

    private fun openCloudFileInCurrentFolder(item: CloudItem) {
        val parentId = currentFolderId?.takeIf { !CloudVirtualNodes.isVirtual(it) }
        setCloudFileOpen(
            CloudOpenFile(
                id = item.id,
                name = item.name,
                mimeType = item.mimeType,
                parentFolderId = parentId
            )
        )
        recordRecentOpen(item.id, item.name, isFolder = false)
        refreshFolderTree()
        loadCloudFilePreview(item.id, item.mimeType, item.name)
    }

    private fun setCloudFileOpen(file: CloudOpenFile?) {
        previewLoadJob?.cancel()
        _state.update {
            it.copy(
                cloudOpenFile = file,
                cloudPreviewBytes = null,
                cloudPreviewError = null
            )
        }
    }

    private fun clearCloudFileOpen() {
        previewLoadJob?.cancel()
        previewLoadJob = null
        _state.update {
            it.copy(cloudOpenFile = null, cloudPreviewBytes = null, cloudPreviewError = null)
        }
    }

    private fun loadCloudFilePreview(fileId: String, mimeType: String?, fileName: String?) {
        previewLoadJob?.cancel()
        val isImage = mimeType?.startsWith("image/", ignoreCase = true) == true ||
            guessImageFromName(fileName)
        if (!isImage) {
            // Still try getImage for Photos library files without mime — server may resize.
            previewLoadJob = viewModelScope.launch {
                tryLoadImagePreview(fileId)
            }
            return
        }
        previewLoadJob = viewModelScope.launch {
            tryLoadImagePreview(fileId)
        }
    }

    private suspend fun tryLoadImagePreview(fileId: String) {
        try {
            val resp = api.getImage(fileId, width = 1280, height = 1280)
            if (!resp.isSuccessful) {
                _state.update { it.copy(cloudPreviewError = "Preview unavailable") }
                return
            }
            val bytes = withContext(Dispatchers.IO) {
                resp.body()?.bytes()
            }
            if (_state.value.cloudOpenFile?.id.equals(fileId, ignoreCase = true) != true) return
            if (bytes == null || bytes.isEmpty()) {
                _state.update { it.copy(cloudPreviewError = "Preview unavailable") }
            } else {
                _state.update { it.copy(cloudPreviewBytes = bytes, cloudPreviewError = null) }
            }
        } catch (_: Exception) {
            if (_state.value.cloudOpenFile?.id.equals(fileId, ignoreCase = true) == true) {
                _state.update { it.copy(cloudPreviewError = "Preview unavailable") }
            }
        }
    }

    private fun guessImageFromName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".gif") ||
            lower.endsWith(".webp") || lower.endsWith(".heic")
    }

    private suspend fun tryOpenCloudEntity(entityId: String): Boolean {
        ensurePhotosLibraryId()
        return try {
            val found = api.getEntities(
                GetEntitiesRequest(ids = listOf(entityId), includeValues = true, take = 1)
            )
            val entity = found.firstOrNull() ?: return false
            if (entity.code.equals(PhotosLibrary.HOST_CODE, ignoreCase = false)) {
                val lib = photosLibraryId
                if (lib != null && !lib.equals(entityId, ignoreCase = true)) {
                    return tryOpenCloudEntity(lib)
                }
                return false
            }
            if (!isNavigableCloudCode(entity.code)) return false
            val isFolder =
                entity.code == CloudCodes.CLOUD_FOLDER || entity.code == CloudCodes.CLOUD_ROOT
            val chain = ArrayList<Pair<EntityDto, String>>()
            var current = entity
            var currentMainParentId: String? = null
            try {
                currentMainParentId = api.getEntity(entityId).mainParentId
            } catch (_: Exception) {
            }
            for (guard in 0 until 64) {
                val onPhotosLib = photosLibraryId != null &&
                    current.id.equals(photosLibraryId, ignoreCase = true)
                val display = if (current.code == CloudCodes.CLOUD_ROOT) {
                    ShareSheetCloudStrings.myCloud(uiLang())
                } else if (onPhotosLib) {
                    ShareSheetCloudStrings.photos(uiLang())
                } else {
                    entityDisplayName(current)
                }
                chain.add(0, current to display)
                folderTreeCodes[current.id.lowercase()] = current.code ?: ""
                if (current.code == CloudCodes.CLOUD_ROOT) break
                val parents = api.getEntities(
                    GetEntitiesRequest(
                        idsToFindParents = listOf(current.id),
                        includeValues = true,
                        take = 50
                    )
                )
                val parent = CloudMainParent.pickParent(currentMainParentId, parents)
                if (parent == null || !CloudMainParent.isCloudFolderOrRoot(parent.code)) break
                var nextMain: String? = null
                try {
                    nextMain = api.getEntity(parent.id).mainParentId
                } catch (_: Exception) {
                }
                current = parent
                currentMainParentId = nextMain
            }
            if (chain.isEmpty()) return false
            if (chain[0].first.code == CloudCodes.CLOUD_ROOT) {
                cloudRootId = chain[0].first.id
            }
            val lang = uiLang()
            val hub = CloudVirtualNodes.HUB_ID to ShareSheetCloudStrings.cloudHub(lang)
            val folderCrumbs = if (isFolder) {
                chain.map { pair -> pair.first.id to pair.second }
            } else {
                chain.dropLast(1).map { pair -> pair.first.id to pair.second }
            }
            val crumbs = listOf(hub) + folderCrumbs
            if (isFolder) {
                _state.update { s -> s.copy(breadcrumb = crumbs) }
                loadCloudFolder(entity.id)
                recordRecentOpen(entity.id, entityDisplayName(entity), isFolder = true)
            } else {
                val parentFolder = folderCrumbs.lastOrNull()?.first
                _state.update { s -> s.copy(breadcrumb = crumbs) }
                if (parentFolder == null) {
                    loadCloudFolder(CloudVirtualNodes.HUB_ID)
                } else {
                    currentFolderId = parentFolder
                    val children = api.getEntities(
                        GetEntitiesRequest(
                            parentIds = listOf(parentFolder),
                            includeValues = true,
                            take = 500
                        )
                    )
                    val items = children.mapNotNull { e ->
                        val name = entityDisplayName(e)
                        when (e.code) {
                            CloudCodes.CLOUD_FOLDER, CloudCodes.CLOUD_ROOT -> {
                                folderTreeCodes[e.id.lowercase()] =
                                    e.code ?: CloudCodes.CLOUD_FOLDER
                                CloudItem(e.id, name, true)
                            }
                            CloudCodes.CLOUD_FILE -> CloudItem(
                                id = e.id,
                                name = name,
                                isFolder = false,
                                mimeType = EntityMeta.mimeType(e)
                            )
                            else -> null
                        }
                    }
                    val mime = EntityMeta.mimeType(entity)
                    _state.update { s -> s.copy(cloudItems = items) }
                    setCloudFileOpen(
                        CloudOpenFile(
                            id = entity.id,
                            name = entityDisplayName(entity),
                            mimeType = mime,
                            parentFolderId = parentFolder
                        )
                    )
                    recordRecentOpen(entity.id, entityDisplayName(entity), isFolder = false)
                    refreshFolderTree()
                    loadCloudFilePreview(entity.id, mime, entityDisplayName(entity))
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun refreshFolderTree() {
        folderTreeRefreshJob?.cancel()
        folderTreeRefreshJob = viewModelScope.launch {
            val listed = currentFolderId
            if (listed.isNullOrBlank() || CloudVirtualNodes.isVirtual(listed)) {
                _state.update { s -> s.copy(cloudFolderTree = emptyList()) }
                return@launch
            }
            ensurePhotosLibraryId()
            val crumbMeta = _state.value.breadcrumb.map { pair ->
                val id = pair.first
                val code = if (id.equals(cloudRootId, ignoreCase = true)) {
                    CloudCodes.CLOUD_ROOT
                } else {
                    folderTreeCodes[id.lowercase()]
                }
                id to code
            }
            val rootId = CloudFolderTree.pickTreeRootId(
                crumbMeta,
                photosLibraryId,
                CloudVirtualNodes::isVirtual
            )
            if (rootId.isNullOrBlank()) {
                _state.update { s -> s.copy(cloudFolderTree = emptyList()) }
                return@launch
            }
            try {
                val childrenByParent = linkedMapOf<String, List<CloudFolderTree.Node>>()
                val names = mutableMapOf<String, String>()
                val queue = ArrayDeque<String>()
                queue.add(rootId)
                val crumbName = _state.value.breadcrumb.firstOrNull { pair ->
                    pair.first.equals(rootId, ignoreCase = true)
                }?.second
                names[rootId] = crumbName
                    ?: if (
                        photosLibraryId != null &&
                        rootId.equals(photosLibraryId, ignoreCase = true)
                    ) {
                        ShareSheetCloudStrings.photos(uiLang())
                    } else {
                        "Cloud"
                    }
                var folderCount = 0
                while (queue.isNotEmpty() && folderCount < CloudFolderTree.DEFAULT_MAX_FOLDERS) {
                    val parentId = queue.removeFirst()
                    val still = currentFolderId
                    if (still == null || CloudVirtualNodes.isVirtual(still)) {
                        _state.update { s -> s.copy(cloudFolderTree = emptyList()) }
                        return@launch
                    }
                    val list = api.getEntities(
                        GetEntitiesRequest(
                            parentIds = listOf(parentId),
                            includeValues = false,
                            take = 200
                        )
                    )
                    val folderKids = ArrayList<CloudFolderTree.Node>()
                    for (e in list.filter { CloudFolderTree.isFolderTreeCode(it.code) }
                        .sortedBy { entityDisplayName(it).lowercase() }) {
                        folderTreeCodes[e.id.lowercase()] = e.code ?: CloudCodes.CLOUD_FOLDER
                        val name = entityDisplayName(e)
                        names[e.id] = name
                        if (folderCount < CloudFolderTree.DEFAULT_MAX_FOLDERS) {
                            queue.add(e.id)
                            folderCount++
                        }
                        folderKids.add(CloudFolderTree.Node(e.id, name))
                    }
                    childrenByParent[parentId] = folderKids
                }
                val openFile = _state.value.cloudOpenFile
                val selected = CloudFolderTree.selectedFolderId(
                    isFileOpen = openFile != null,
                    openEntityId = if (openFile != null) openFile.id else currentFolderId,
                    parentFolderId = openFile?.parentFolderId ?: currentFolderId
                )
                val flat = CloudFolderTree.flatten(
                    CloudFolderTree.Node(rootId, names[rootId] ?: "Cloud"),
                    childrenByParent,
                    selected
                )
                _state.update { s ->
                    s.copy(
                        cloudFolderTree = flat.map { row ->
                            CloudFolderTreeRow(row.id, row.name, row.depth, row.selected)
                        }
                    )
                }
            } catch (_: Exception) {
                _state.update { s -> s.copy(cloudFolderTree = emptyList()) }
            }
        }
    }

    fun copyCanonicalLink(entityId: String?) {
        val id = entityId?.takeIf { it.isNotBlank() && !CloudVirtualNodes.isVirtual(it) } ?: return
        val url = ShareSheetLogic.buildCanonicalLink(_state.value.settings.publicSiteBaseUrl, id)
        copyTextToClipboard(url, ShareSheetStrings.copied(uiLang()))
    }

    /** Cloud row: copy entity Guid D (not URL). No-op for virtual hub rows / invalid ids. */
    fun copyEntityId(entityId: String?) {
        if (entityId.isNullOrBlank() || CloudVirtualNodes.isVirtual(entityId)) return
        val text = ShareAccountStrings.formatEntityIdForClipboard(entityId) ?: return
        copyTextToClipboard(text, ShareAccountStrings.copiedLabel(uiLang()))
    }

    fun copyMyUserId() {
        val id = auth.current()?.userId ?: return
        copyTextToClipboard(id, ShareAccountStrings.copiedLabel(uiLang()))
    }

    /** Messenger: copy contact user id (D format). No-op / clear status when unresolved. */
    fun copyContactUserId(userId: String?) {
        val text = ShareAccountStrings.formatUserIdForClipboard(userId)
        if (text == null) {
            _state.update { it.copy(status = "") }
            return
        }
        copyTextToClipboard(text, ShareAccountStrings.copiedLabel(uiLang()))
    }

    /** Messenger: open contact public user home in browser. No-op when Guid unresolved. */
    fun openContactInformation(userId: String?) {
        val id = ShareAccountStrings.formatUserIdForClipboard(userId) ?: return
        val url = ShareSheetLogic.buildCanonicalLink(_state.value.settings.publicSiteBaseUrl, id)
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        } catch (e: Exception) {
            Log.w("ProtoLinkMessenger", "open contact information failed", e)
            _state.update { it.copy(status = "Could not open browser") }
        }
    }

    private fun copyTextToClipboard(text: String, status: String) {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("ProtoLink", text))
        _state.update { it.copy(status = status) }
    }

    fun openShareSheet(
        entityId: String?,
        entityName: String?,
        isFolder: Boolean,
        childCount: Int = 0,
        isNote: Boolean = false
    ) {
        val id = entityId?.takeIf { it.isNotBlank() && !CloudVirtualNodes.isVirtual(it) } ?: return
        val online = isNetworkAvailable()
        val name = entityName?.takeIf { it.isNotBlank() } ?: id.take(8)
        _state.update {
            it.copy(
                shareSheet = ShareSheetUiState(
                    isOpen = true,
                    entityId = id,
                    entityName = name,
                    isFolder = isFolder,
                    isNote = isNote,
                    childCount = if (isFolder) childCount else 0,
                    canEdit = true,
                    isOnline = online,
                    isLoading = online
                )
            )
        }
        if (online) loadShareSheet() else {
            _state.update { s -> s.copy(shareSheet = s.shareSheet.copy(isLoading = false)) }
        }
    }

    /** Same E2 gate as Copy link (AC-NS-1 / AT-S47): Cloud-linked note folder RemoteId. */
    fun tryResolveNotesRemoteId(relativePath: String?): String? {
        val notesRoot = _state.value.settings.notesRootUri
        val mappings = mappingStore.load()
        return NoteRemoteIdResolver.resolve(
            notesRootUri = notesRoot,
            noteRelativePath = relativePath,
            mappings = mappings
        ) { mappingId ->
            runCatching { metadataStore.getAll(mappingId) }.getOrDefault(emptyList())
        }
    }

    fun canOpenShareForNotesRow(row: NoteTreeRow): Boolean =
        tryResolveNotesRemoteId(row.relativePath) != null

    fun showsExternalShareForNotesRow(row: NoteTreeRow): Boolean {
        val id = tryResolveNotesRemoteId(row.relativePath)?.lowercase() ?: return false
        return notesExternalShareByRemoteId[id] == true
    }

    fun openShareForNotesRow(row: NoteTreeRow) {
        val id = tryResolveNotesRemoteId(row.relativePath)
        if (id == null) {
            _state.update {
                it.copy(status = ShareSheetStrings.notInCloudYet(uiLang()))
            }
            return
        }
        openShareSheet(
            entityId = id,
            entityName = row.name,
            isFolder = true,
            childCount = 0,
            isNote = true
        )
    }

    /**
     * Lazy prefetch for one Cloud-linked Notes node when the long-press menu opens (no full-tree scan).
     */
    fun prefetchNotesShareAffordance(row: NoteTreeRow) {
        val id = tryResolveNotesRemoteId(row.relativePath) ?: return
        val key = id.lowercase()
        if (notesExternalShareByRemoteId.containsKey(key)) return
        if (!isNetworkAvailable()) return
        if (notesSharePrefetchBusy) return
        notesSharePrefetchBusy = true
        viewModelScope.launch {
            try {
                val links = api.listShareLinks(id)
                val grants = api.listShareGrants(id)
                val live = links.count { it.isLive }
                val has = NotesTreeShare.hasExternalShare(
                    grants,
                    live,
                    auth.current()?.userId
                )
                rememberNotesExternalShare(key, has)
            } catch (_: Exception) {
                // Offline / error: omit glyph rather than invent “shared”.
            } finally {
                notesSharePrefetchBusy = false
            }
        }
    }

    private fun rememberNotesExternalShare(remoteIdLower: String, hasExternal: Boolean) {
        val prev = notesExternalShareByRemoteId.put(remoteIdLower, hasExternal)
        if (prev == hasExternal) return
        _state.update { it.copy(notesShareAffordanceEpoch = it.notesShareAffordanceEpoch + 1) }
    }

    private fun captureNotesShareAffordanceFromOpenSheet() {
        val sheet = _state.value.shareSheet
        if (!sheet.isOpen || sheet.entityId.isBlank()) return
        val grants = sheet.people.map { it.grant }
        val has = NotesTreeShare.hasExternalShare(
            grants,
            sheet.liveLinks.size,
            auth.current()?.userId
        )
        rememberNotesExternalShare(sheet.entityId.lowercase(), has)
    }

    fun openShareForCurrentFolder() {
        val crumb = _state.value.breadcrumb.lastOrNull() ?: return
        if (CloudVirtualNodes.isVirtual(crumb.first)) return
        openShareSheet(
            entityId = crumb.first,
            entityName = crumb.second,
            isFolder = true,
            childCount = _state.value.cloudItems.size
        )
    }

    fun closeShareSheet(force: Boolean = false) {
        val sheet = _state.value.shareSheet
        if (!sheet.isOpen) return
        if (!force && sheet.showCreatedPanel && !sheet.createdUrlCopied && !sheet.showCloseWithoutCopy) {
            _state.update { it.copy(shareSheet = it.shareSheet.copy(showCloseWithoutCopy = true)) }
            return
        }
        _state.update { it.copy(shareSheet = ShareSheetUiState()) }
    }

    fun shareSheetBeginCreate() {
        updateShareSheet {
            it.copy(
                showCreateForm = true,
                showCreatedPanel = false,
                showCloseWithoutCopy = false,
                createdUrl = null,
                createdUrlCopied = false,
                newLabel = "",
                expiryChoice = 30,
                errorText = null,
                showAddPeople = false
            )
        }
    }

    fun shareSheetCancelCreate() {
        updateShareSheet { it.copy(showCreateForm = false, newLabel = "") }
    }

    fun shareSheetSetNewLabel(value: String) {
        updateShareSheet { it.copy(newLabel = value) }
    }

    fun shareSheetSetExpiryChoice(days: Int) {
        updateShareSheet { it.copy(expiryChoice = days) }
    }

    fun shareSheetCreateLink() = viewModelScope.launch {
        val sheet = _state.value.shareSheet
        if (!ShareSheetLogic.canMutate(sheet.isOnline, sheet.canEdit, sheet.showCreatedPanel) || sheet.busy) return@launch
        updateShareSheet { it.copy(busy = true, errorText = null) }
        val payload = ShareSheetLogic.expiryPayload(sheet.expiryChoice)
        try {
            val result = api.createShareLink(
                CreateShareLinkRequest(
                    entityId = sheet.entityId,
                    label = sheet.newLabel.trim().takeIf { it.isNotEmpty() },
                    expiresAt = payload.expiresAtIso,
                    neverExpires = payload.neverExpires,
                    includeSubtree = sheet.isFolder,
                    canWrite = false
                )
            )
            val url = result.url?.takeIf { it.isNotBlank() }
            updateShareSheet {
                it.copy(
                    busy = false,
                    showCreateForm = false,
                    newLabel = "",
                    createdUrl = url,
                    createdUrlCopied = false,
                    showCloseWithoutCopy = false,
                    showCreatedPanel = !url.isNullOrBlank()
                )
            }
            if (url.isNullOrBlank()) {
                updateShareSheet { it.copy(errorText = ShareSheetStrings.createFailed(uiLang())) }
            }
        } catch (_: Exception) {
            updateShareSheet {
                it.copy(busy = false, errorText = ShareSheetStrings.createFailed(uiLang()))
            }
        }
    }

    fun shareSheetCopyCreatedUrl() {
        val url = _state.value.shareSheet.createdUrl ?: return
        copyTextToClipboard(url, ShareSheetStrings.copied(uiLang()))
        updateShareSheet { it.copy(createdUrlCopied = true, showCloseWithoutCopy = false) }
    }

    fun shareSheetDoneCreated(force: Boolean = false) {
        val sheet = _state.value.shareSheet
        if (!force && !sheet.createdUrlCopied) {
            updateShareSheet { it.copy(showCloseWithoutCopy = true) }
            return
        }
        updateShareSheet {
            it.copy(
                showCreatedPanel = false,
                showCloseWithoutCopy = false,
                createdUrl = null,
                createdUrlCopied = false
            )
        }
        loadShareSheet()
    }

    fun shareSheetCopyAndClose() {
        shareSheetCopyCreatedUrl()
        shareSheetDoneCreated(force = true)
    }

    fun shareSheetConfirmTurnOff(linkId: String) {
        updateShareSheet { it.copy(confirmTurnOffId = linkId) }
    }

    fun shareSheetCancelTurnOff() {
        updateShareSheet { it.copy(confirmTurnOffId = null) }
    }

    fun shareSheetTurnOff(linkId: String) = viewModelScope.launch {
        val sheet = _state.value.shareSheet
        if (!ShareSheetLogic.canMutate(sheet.isOnline, sheet.canEdit, sheet.showCreatedPanel)) return@launch
        try {
            api.revokeShareLink(RevokeShareLinkRequest(shareLinkId = linkId))
            _state.update { it.copy(status = ShareSheetStrings.guestLinkTurnedOff(uiLang())) }
            updateShareSheet { it.copy(confirmTurnOffId = null) }
            loadShareSheet()
        } catch (_: Exception) {
            updateShareSheet { it.copy(errorText = ShareSheetStrings.revokeFailed(uiLang())) }
        }
    }

    fun shareSheetExtend(linkId: String, days: Int) = viewModelScope.launch {
        val sheet = _state.value.shareSheet
        if (!ShareSheetLogic.canMutate(sheet.isOnline, sheet.canEdit, sheet.showCreatedPanel)) return@launch
        val payload = ShareSheetLogic.expiryPayload(days)
        try {
            api.updateShareLink(
                UpdateShareLinkRequest(
                    shareLinkId = linkId,
                    expiresAt = payload.expiresAtIso,
                    neverExpires = payload.neverExpires
                )
            )
            updateShareSheet { it.copy(extendMenuId = null) }
            loadShareSheet()
        } catch (_: Exception) {
            updateShareSheet { it.copy(errorText = ShareSheetStrings.updateFailed(uiLang())) }
        }
    }

    fun shareSheetToggleExtendMenu(linkId: String?) {
        updateShareSheet { it.copy(extendMenuId = linkId) }
    }

    fun shareSheetStartEditLabel(linkId: String, current: String) {
        updateShareSheet { it.copy(editingLabelId = linkId, editingLabelDraft = current) }
    }

    fun shareSheetSetEditingLabel(value: String) {
        updateShareSheet { it.copy(editingLabelDraft = value) }
    }

    fun shareSheetCancelEditLabel() {
        updateShareSheet { it.copy(editingLabelId = null, editingLabelDraft = "") }
    }

    fun shareSheetSaveLabel() = viewModelScope.launch {
        val sheet = _state.value.shareSheet
        val linkId = sheet.editingLabelId ?: return@launch
        if (!ShareSheetLogic.canMutate(sheet.isOnline, sheet.canEdit, sheet.showCreatedPanel)) return@launch
        try {
            api.updateShareLink(
                UpdateShareLinkRequest(
                    shareLinkId = linkId,
                    label = sheet.editingLabelDraft.trim().takeIf { it.isNotEmpty() }
                )
            )
            updateShareSheet { it.copy(editingLabelId = null, editingLabelDraft = "") }
            loadShareSheet()
        } catch (_: Exception) {
            updateShareSheet { it.copy(errorText = ShareSheetStrings.updateFailed(uiLang())) }
        }
    }

    fun shareSheetRemovePerson(permissionForId: String) = viewModelScope.launch {
        val sheet = _state.value.shareSheet
        if (!ShareSheetLogic.canMutate(sheet.isOnline, sheet.canEdit, sheet.showCreatedPanel)) return@launch
        updateShareSheet { it.copy(peopleErrorText = null) }
        try {
            api.removePermission(
                RemovePermissionRequest(entityId = sheet.entityId, permissionForId = permissionForId)
            )
            loadShareSheet()
        } catch (_: Exception) {
            updateShareSheet { it.copy(peopleErrorText = ShareSheetStrings.removeFailed(uiLang())) }
        }
    }

    fun shareSheetToggleInactive() {
        updateShareSheet { it.copy(showInactiveExpanded = !it.showInactiveExpanded) }
    }

    fun shareSheetBeginAddPeople() {
        val sheet = _state.value.shareSheet
        if (!ShareSheetLogic.canMutate(sheet.isOnline, sheet.canEdit, sheet.showCreatedPanel)) return
        if (sheet.showCreateForm || sheet.showCreatedPanel) return
        if (_state.value.contacts.isEmpty()) loadContacts()
        updateShareSheet {
            it.copy(
                showAddPeople = true,
                showAddedBanner = false,
                addedBannerText = null,
                contactSearch = "",
                pasteId = "",
                pastePreviewName = null,
                pastePreviewError = null,
                pasteResolvedUserId = null,
                addCanWrite = false,
                errorText = null
            )
        }
        rebuildShareContactPicker()
    }

    fun shareSheetCancelAddPeople() {
        updateShareSheet {
            it.copy(
                showAddPeople = false,
                contactSearch = "",
                contactPicker = emptyList(),
                pasteId = "",
                pastePreviewName = null,
                pastePreviewError = null,
                pasteResolvedUserId = null,
                addCanWrite = false
            )
        }
    }

    fun shareSheetSetContactSearch(query: String) {
        updateShareSheet { it.copy(contactSearch = query) }
        rebuildShareContactPicker()
    }

    fun shareSheetToggleContact(userId: String) {
        updateShareSheet { sheet ->
            sheet.copy(
                contactPicker = sheet.contactPicker.map { row ->
                    if (row.userId.equals(userId, true)) row.copy(isSelected = !row.isSelected)
                    else row
                }
            )
        }
    }

    fun shareSheetSetPasteId(value: String) {
        updateShareSheet {
            it.copy(
                pasteId = value,
                pastePreviewName = null,
                pastePreviewError = null,
                pasteResolvedUserId = null
            )
        }
    }

    fun shareSheetSetAddCanWrite(canWrite: Boolean) {
        updateShareSheet { it.copy(addCanWrite = canWrite) }
    }

    fun shareSheetDismissAddedBanner() {
        updateShareSheet { it.copy(showAddedBanner = false, addedBannerText = null) }
    }

    fun shareSheetResolvePaste() = viewModelScope.launch {
        val sheet = _state.value.shareSheet
        if (!ShareSheetLogic.canMutate(sheet.isOnline, sheet.canEdit, sheet.showCreatedPanel) ||
            !sheet.showAddPeople || sheet.busy
        ) return@launch
        val parsed = ShareAddPeople.parseUserIdInput(sheet.pasteId)
        if (parsed == null) {
            updateShareSheet {
                it.copy(
                    pastePreviewName = null,
                    pasteResolvedUserId = null,
                    pastePreviewError = ShareSheetStrings.noUserWithId(uiLang())
                )
            }
            return@launch
        }
        updateShareSheet { it.copy(busy = true, pastePreviewError = null, pastePreviewName = null) }
        try {
            val user = api.getUserById(parsed)
            val name = ShareAddPeople.pickResolvedDisplayName(user.name, user.login)
            if (name == null) {
                updateShareSheet {
                    it.copy(
                        busy = false,
                        pasteResolvedUserId = null,
                        pastePreviewName = null,
                        pastePreviewError = ShareSheetStrings.noUserWithId(uiLang())
                    )
                }
            } else {
                updateShareSheet {
                    it.copy(
                        busy = false,
                        pasteResolvedUserId = parsed,
                        pastePreviewName = name,
                        pastePreviewError = null
                    )
                }
            }
        } catch (_: Exception) {
            updateShareSheet {
                it.copy(
                    busy = false,
                    pasteResolvedUserId = null,
                    pastePreviewName = null,
                    pastePreviewError = ShareSheetStrings.noUserWithId(uiLang())
                )
            }
        }
    }

    fun shareSheetAddPeople() = viewModelScope.launch {
        val sheet = _state.value.shareSheet
        if (!ShareSheetLogic.canMutate(sheet.isOnline, sheet.canEdit, sheet.showCreatedPanel) ||
            !sheet.showAddPeople || sheet.busy
        ) return@launch
        val selected = sheet.contactPicker.filter { it.isSelected }
        val targets = linkedMapOf<String, String>()
        for (c in selected) targets[c.userId.lowercase()] = c.name
        val pasteId = sheet.pasteResolvedUserId
        val pasteName = sheet.pastePreviewName
        if (!pasteId.isNullOrBlank() && !pasteName.isNullOrBlank()) {
            targets[pasteId.lowercase()] = pasteName
        }
        if (targets.isEmpty()) return@launch
        updateShareSheet { it.copy(busy = true, errorText = null) }
        val canWrite = sheet.addCanWrite
        try {
            for ((userId, _) in targets) {
                api.addPermission(
                    AddPermissionRequest(
                        permissionForId = userId,
                        canWrite = canWrite,
                        id = sheet.entityId
                    )
                )
            }
            val toast = if (targets.size > 1) {
                ShareSheetStrings.addedToastMany(uiLang(), targets.size)
            } else {
                ShareSheetStrings.addedToast(uiLang(), targets.values.first(), canWrite)
            }
            updateShareSheet {
                it.copy(
                    busy = false,
                    showAddPeople = false,
                    contactSearch = "",
                    contactPicker = emptyList(),
                    pasteId = "",
                    pastePreviewName = null,
                    pastePreviewError = null,
                    pasteResolvedUserId = null,
                    addCanWrite = false,
                    showAddedBanner = true,
                    addedBannerText = toast
                )
            }
            _state.update { it.copy(status = toast) }
            loadShareSheet()
        } catch (_: Exception) {
            updateShareSheet {
                it.copy(busy = false, errorText = ShareSheetStrings.loadFailed(uiLang()))
            }
        }
    }

    private fun rebuildShareContactPicker() {
        val sheet = _state.value.shareSheet
        if (!sheet.showAddPeople) return
        val me = auth.current()?.userId?.lowercase()
        val already = sheet.people
            .mapNotNull { it.grant.permissionForId?.lowercase() }
            .toSet()
        val base = _state.value.contacts
            .filter { c ->
                val id = c.userId.lowercase()
                id != me && id !in already
            }
            .map { c ->
                val name = ShareAddPeople.pickResolvedDisplayName(c.displayName, null)
                    ?: c.displayName.takeIf { !ShareAddPeople.isGuidText(it) }
                    ?: "…"
                ShareContactPickerItem(userId = c.userId, name = name, isSelected = false)
            }
        val selected = sheet.contactPicker.filter { it.isSelected }.map { it.userId.lowercase() }.toSet()
        val filtered = ShareAddPeople.filterContacts(base, { it.name }, sheet.contactSearch)
            .map { row -> row.copy(isSelected = selected.contains(row.userId.lowercase())) }
        updateShareSheet { it.copy(contactPicker = filtered) }
    }

    fun loadShareSheet() = viewModelScope.launch {
        val sheet = _state.value.shareSheet
        if (!sheet.isOpen) return@launch
        val online = isNetworkAvailable()
        if (!online) {
            updateShareSheet { it.copy(isOnline = false, isLoading = false) }
            return@launch
        }
        updateShareSheet { it.copy(isOnline = true, isLoading = true, errorText = null) }
        try {
            val entityId = sheet.entityId
            val me = auth.current()?.userId
            val entity = api.getEntities(
                GetEntitiesRequest(ids = listOf(entityId), includeValues = false, take = 1)
            ).firstOrNull()
            val creatorId = entity?.userWhoCreatedId
            val perms = runCatching { api.getPermissions(entityId) }.getOrDefault(emptyList())
            val canEdit = when {
                me != null && perms.any {
                    it.permissionForId.equals(me, true) && it.canWrite
                } -> true
                me != null && creatorId.equals(me, true) -> true
                CloudSharedScope.isInSharedWithMeScope(_state.value.breadcrumb.map { it.first }) -> {
                    val root = CloudSharedScope.tryGetActiveShareRootId(
                        _state.value.breadcrumb.map { it.first }
                    )
                    root != null && _state.value.shareRootCanWrite[root.lowercase()] == true
                }
                else -> true
            }
            val links = api.listShareLinks(entityId)
            val grants = api.listShareGrants(entityId)
            val parts = ShareSheetLogic.partitionLinks(links)
            val people = ShareSheetLogic.peopleFromGrants(grants, creatorId, currentUserId = me).map {
                SharePersonUi(it.grant, it.isCreator)
            }
            updateShareSheet {
                it.copy(
                    isLoading = false,
                    canEdit = canEdit,
                    creatorUserId = creatorId,
                    liveLinks = parts.live,
                    inactiveLinks = parts.inactive,
                    people = people
                )
            }
            if (sheet.isNote) captureNotesShareAffordanceFromOpenSheet()
        } catch (_: Exception) {
            updateShareSheet {
                it.copy(isLoading = false, errorText = ShareSheetStrings.loadFailed(uiLang()))
            }
        }
    }

    private fun updateShareSheet(block: (ShareSheetUiState) -> ShareSheetUiState) {
        _state.update { it.copy(shareSheet = block(it.shareSheet)) }
    }

    fun mapCurrentFolder(treeUri: String) {
        val folderId = currentFolderId ?: run {
            _state.update { it.copy(status = "Open a cloud folder first, then map") }
            return
        }
        val crumbIds = _state.value.breadcrumb.map { it.first }
        val mappedIds = mappingStore.load().map { it.cloudFolderId }.toSet()
        if (!CloudSharedScope.canMapCurrentFolder(
                folderId,
                crumbIds,
                mappedIds,
                _state.value.shareRootCanWrite
            )
        ) {
            _state.update { it.copy(status = ShareSheetCloudStrings.browseOnlyHint(uiLang())) }
            return
        }
        val name = _state.value.breadcrumb.lastOrNull()?.second ?: "Folder"
        mapFolder(folderId, treeUri, name)
    }

    /** Persist mapping + full reconcile into an existing Cloud folder id (never creates the mapped root). */
    fun mapFolder(folderId: String, treeUri: String, folderName: String) {
        if (treeUri.isBlank()) {
            _state.update { it.copy(status = MapLocalFolderStrings.localMissing(uiLang())) }
            return
        }
        val list = mappingStore.load().toMutableList()
        list.removeAll { it.cloudFolderId.equals(folderId, ignoreCase = true) }
        val name = folderName.ifBlank { "Folder" }
        list.add(CloudSyncMappingDto(folderId, treeUri, name))
        mappingStore.save(list)
        _state.update {
            it.copy(
                mappings = list,
                status = "Mapped — syncing…",
                mapWizardOpen = false,
                mapWizardBusy = false,
                mapWizardPrefillUri = null
            )
        }
        syncNow()
    }

    /**
     * E6 local-first wizard entry (Cloud hub CTA, Notes nudge, Settings deep-link).
     * Prefill skips SAF when Notes already chose a tree URI.
     */
    fun requestAddLocalFolderWizard(prefilledLocalUri: String? = null) {
        if (!auth.isAuthenticated) {
            _state.update { it.copy(status = "Log in before mapping a folder", showSettings = true) }
            return
        }
        viewModelScope.launch {
            ensureCloudRootQuiet()
            val parent = resolveWizardParentFolderId()
            val candidates = if (parent != null) loadUnmappedChildFolders(parent) else emptyList()
            _state.update {
                it.copy(
                    preferCloudTabNonce = it.preferCloudTabNonce + 1,
                    showSettings = false,
                    mapWizardOpen = true,
                    mapWizardPrefillUri = prefilledLocalUri?.takeIf { u -> u.isNotBlank() },
                    mapWizardCandidates = candidates,
                    mapWizardBusy = false
                )
            }
        }
    }

    fun dismissMapLocalFolderWizard() {
        _state.update {
            it.copy(
                mapWizardOpen = false,
                mapWizardBusy = false,
                mapWizardPrefillUri = null,
                mapWizardCandidates = emptyList()
            )
        }
    }

    fun refreshMapWizardCandidates() {
        viewModelScope.launch {
            ensureCloudRootQuiet()
            val parent = resolveWizardParentFolderId()
            val candidates = if (parent != null) loadUnmappedChildFolders(parent) else emptyList()
            _state.update { it.copy(mapWizardCandidates = candidates) }
        }
    }

    fun completeAddLocalFolderWizard(result: MapLocalFolderWizardResult) {
        viewModelScope.launch {
            _state.update { it.copy(mapWizardBusy = true) }
            try {
                ensureCloudRootQuiet()
                val parent = resolveWizardParentFolderId()
                    ?: run {
                        _state.update {
                            it.copy(
                                mapWizardBusy = false,
                                status = "Cloud root unavailable"
                            )
                        }
                        return@launch
                    }
                val folderId: String
                val folderName: String
                if (result.createNew) {
                    val name = result.newFolderName?.trim().orEmpty()
                    if (name.isEmpty()) {
                        _state.update { it.copy(mapWizardBusy = false) }
                        return@launch
                    }
                    val createdId = createCloudFolderUnder(parent, name)
                    if (createdId == null) {
                        _state.update {
                            it.copy(mapWizardBusy = false, status = "Could not create Cloud folder")
                        }
                        return@launch
                    }
                    folderId = createdId
                    folderName = name
                } else {
                    folderId = result.existingFolderId ?: run {
                        _state.update { it.copy(mapWizardBusy = false) }
                        return@launch
                    }
                    folderName = result.existingFolderName.orEmpty()
                }
                mapFolder(folderId, result.localUri, folderName)
                loadCloudFolder(parent)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        mapWizardBusy = false,
                        status = e.message ?: e.javaClass.simpleName
                    )
                }
            }
        }
    }

    private suspend fun ensureCloudRootQuiet() {
        if (cloudRootId != null) return
        val userId = auth.current()?.userId ?: return
        val list = api.getEntities(GetEntitiesRequest(parentIds = listOf(userId), includeValues = false, take = 200))
        val root = list.firstOrNull { it.code == CloudCodes.CLOUD_ROOT }
        cloudRootId = root?.id ?: api.addEntity(
            AddEntityRequest(code = CloudCodes.CLOUD_ROOT, parentIds = listOf(userId))
        ).id
    }

    private fun resolveWizardParentFolderId(): String? {
        val current = currentFolderId
        if (current != null && !CloudVirtualNodes.isVirtual(current)) return current
        return cloudRootId
    }

    private suspend fun loadUnmappedChildFolders(parentId: String): List<MapLocalFolderCandidate> {
        val children = api.getEntities(
            GetEntitiesRequest(parentIds = listOf(parentId), includeValues = true, take = 500)
        )
        val mapped = mappingStore.load().map { it.cloudFolderId.lowercase() }.toSet()
        return children.mapNotNull { e ->
            if (e.code != CloudCodes.CLOUD_FOLDER && e.code != CloudCodes.CLOUD_ROOT) return@mapNotNull null
            if (mapped.contains(e.id.lowercase())) return@mapNotNull null
            MapLocalFolderCandidate(id = e.id, name = entityDisplayName(e))
        }
    }

    private suspend fun createCloudFolderUnder(parentId: String, name: String): String? {
        if (CloudVirtualNodes.isVirtual(parentId)) return null
        return api.addEntity(
            AddEntityRequest(
                code = CloudCodes.CLOUD_FOLDER,
                parentIds = listOf(parentId),
                values = listOf(AddValueRequest(value = name, parentIds = listOf(CloudCodes.NAME_TYPE_ID)))
            )
        ).id
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
                        status = MapLocalFolderStrings.emptyMappingsHint(uiLang()),
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
        createCloudFolderUnder(parent, name) ?: return@launch
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
            if (_state.value.shareSheet.showAddPeople) rebuildShareContactPicker()

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
                if (_state.value.shareSheet.showAddPeople) rebuildShareContactPicker()
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
                GetEntitiesRequest(
                    parentIds = listOf(SystemEntities.MESSAGE),
                    includeValues = true,
                    skip = 0,
                    take = MessengerConversationScope.MESSAGE_PAGE_SIZE,
                    conversationUserIds = MessengerConversationScope.conversationUserIds(me, contactId)
                )
            )
            val selectedId = _state.value.selectedContact?.userId
            if (selectedId == null || !selectedId.equals(contactId, ignoreCase = true)) {
                Log.w("ProtoLinkChat", "loadMessages skip stale contact=$contactId selected=$selectedId")
                return@launch
            }

            // Server already scoped to this conversation (newest-first); UI sorts oldest→newest.
            val msgs = all.mapNotNull { e ->
                val vals = e.values ?: return@mapNotNull null
                val texts = vals.mapNotNull { JsonValues.asText(it.value)?.trim()?.takeIf { t -> t.isNotEmpty() } }
                val sender = texts.firstOrNull { it.startsWith("sender:", true) }?.substringAfter(":")
                if (sender.isNullOrBlank()) return@mapNotNull null

                val valueDateRaw = texts.firstOrNull { ChatTime.looksLikeIsoDate(it) }
                val forBodyParse = texts.filter { !ChatTime.looksLikeIsoDate(it) }
                val parsed = MessageStringValues.parse(forBodyParse)
                // Attachment-only: keep when ids present so GUID never becomes body.
                if (parsed.bodyText.isBlank() && parsed.attachmentIds.isEmpty()) return@mapNotNull null

                val millis = ChatTime.resolveMessageMillis(e.creationTime, e.updateTime, valueDateRaw)
                val isMine = sender.equals(me, ignoreCase = true)
                val isRead = isMine && texts.any { it.equals("status:read", true) }
                MessageItem(
                    id = e.id,
                    text = parsed.bodyText,
                    mine = isMine,
                    timestampMillis = millis,
                    deliveryStatus = when {
                        !isMine -> DeliveryStatus.Sent
                        isRead -> DeliveryStatus.Read
                        else -> DeliveryStatus.Sent
                    },
                    attachmentIds = parsed.attachmentIds
                )
            }.sortedWith(compareBy({ it.timestampMillis }, { it.id }))

            // Drop pending sends that now exist on the server (same non-blank body from me).
            // Never drop Failed / blank-body (attachment-only) — empty bodies collide and wipe Retry.
            val serverMineBodies = msgs.filter { it.mine }.map { it.text.trim() }.toHashSet()
            val contactKey = contactId.lowercase()
            pendingChatSends.keys.toList().forEach { localId ->
                val pendingContact = pendingChatSendContact[localId] ?: return@forEach
                if (!pendingContact.equals(contactKey, ignoreCase = true)) return@forEach
                val pendingMsg = pendingChatSends[localId] ?: return@forEach
                val body = pendingMsg.text.trim()
                if (!MessengerSendStateMachine.shouldDropPendingAfterServerBodyMatch(
                        body,
                        pendingMsg.deliveryStatus
                    )
                ) {
                    return@forEach
                }
                if (body in serverMineBodies) {
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
                    a.id == b.id && a.text == b.text && a.mine == b.mine &&
                        a.deliveryStatus == b.deliveryStatus && a.attachmentIds == b.attachmentIds &&
                        a.statusLine == b.statusLine && a.uploadProgress == b.uploadProgress
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

    fun clearAttachStatus() {
        _state.update { it.copy(attachStatusMessage = null) }
    }

    fun setCameraPermissionDenied() {
        _state.update { it.copy(attachStatusMessage = MessengerMediaLimits.CameraPermissionDeniedEn) }
    }

    /** Returns false when refused (oversize / soft cap). Image downscale runs off main when enabled. */
    fun tryAddPendingAttachment(
        localUri: String,
        fileName: String,
        mimeType: String,
        byteLength: Long,
        localFilePath: String? = null
    ): Boolean {
        _state.update { it.copy(attachStatusMessage = null) }
        if (localUri.isBlank()) return false
        if (_state.value.pendingAttachments.size >= 50) {
            _state.update { it.copy(attachStatusMessage = MessengerMediaLimits.CapRefuseEn) }
            return false
        }
        val name = fileName.ifBlank { "file" }
        val mime = mimeType.ifBlank { "application/octet-stream" }
        val reduceOn = _state.value.settings.reducePhotoSizeBeforeSend
        val wantDownscale = MessengerImageDownscale.shouldApply(reduceOn, mime, isCloudPick = false)
        if (!wantDownscale) {
            if (MessengerMediaLimits.isOversize(byteLength)) {
                _state.update { it.copy(attachStatusMessage = MessengerMediaLimits.OversizeMessageEn) }
                return false
            }
            commitPendingAttachment(localUri, name, mime, byteLength, localFilePath)
            return true
        }
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                MessengerImageDownscale.encodeToCacheJpeg(
                    context = appContext,
                    uri = Uri.parse(localUri),
                    localFilePath = localFilePath,
                    originalFileName = name
                )
            }
            if (prepared != null) {
                val contentUri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    prepared.file
                ).toString()
                if (MessengerMediaLimits.isOversize(prepared.byteLength)) {
                    _state.update { it.copy(attachStatusMessage = MessengerMediaLimits.OversizeMessageEn) }
                    return@launch
                }
                commitPendingAttachment(
                    localUri = contentUri,
                    fileName = prepared.fileName,
                    mimeType = prepared.mimeType,
                    byteLength = prepared.byteLength,
                    localFilePath = prepared.file.absolutePath
                )
            } else {
                Log.w("ProtoLinkChat", "image downscale fell back to original: $name ($mime, $byteLength B)")
                if (MessengerMediaLimits.isOversize(byteLength)) {
                    _state.update { it.copy(attachStatusMessage = MessengerMediaLimits.OversizeMessageEn) }
                    return@launch
                }
                commitPendingAttachment(localUri, name, mime, byteLength, localFilePath)
            }
        }
        return true
    }

    private fun commitPendingAttachment(
        localUri: String,
        fileName: String,
        mimeType: String,
        byteLength: Long,
        localFilePath: String?
    ) {
        if (_state.value.pendingAttachments.size >= 50) {
            _state.update { it.copy(attachStatusMessage = MessengerMediaLimits.CapRefuseEn) }
            return
        }
        val draft = PendingAttachmentDraft(
            localUri = localUri,
            fileName = fileName,
            mimeType = mimeType,
            byteLength = byteLength,
            localFilePath = localFilePath
        )
        _state.update { it.copy(pendingAttachments = it.pendingAttachments + draft) }
    }

    /** M3 — queue an existing CloudFile by id (no local path, no copy / addFile). */
    fun tryAddCloudPick(entityId: String, fileName: String, mimeType: String?): Boolean {
        _state.update { it.copy(attachStatusMessage = null) }
        val id = entityId.trim().lowercase()
        if (id.isBlank()) return false
        val maxPendingQueue = MessengerMediaLimits.MaxAttachmentsPerMessage * 3
        if (_state.value.pendingAttachments.size >= maxPendingQueue) {
            _state.update { it.copy(attachStatusMessage = MessengerMediaLimits.CapRefuseEn) }
            return false
        }
        if (_state.value.pendingAttachments.any {
                it.isCloudPick && it.cloudFileEntityId.equals(id, ignoreCase = true)
            }
        ) {
            return false
        }
        val name = fileName.ifBlank { "file" }
        val mime = mimeType?.takeIf { it.isNotBlank() } ?: guessMime(name, "application/octet-stream")
        val draft = PendingAttachmentDraft(
            localUri = "",
            fileName = name,
            mimeType = mime,
            byteLength = 0L,
            cloudFileEntityId = id,
            uploadCompleted = true,
            isCloudPick = true
        )
        _state.update { it.copy(pendingAttachments = it.pendingAttachments + draft) }
        return true
    }

    fun removePendingAttachment(index: Int) {
        _state.update { s ->
            if (index !in s.pendingAttachments.indices) return@update s
            s.copy(pendingAttachments = s.pendingAttachments.toMutableList().also { it.removeAt(index) })
        }
    }

    fun sendMessage(text: String) = viewModelScope.launch {
        val me = auth.current()?.userId?.trim()?.lowercase()
        if (me.isNullOrBlank()) {
            Log.e("ProtoLinkChat", "sendMessage aborted: no userId")
            showError("Send failed", "Not logged in")
            _state.update { it.copy(composerDraftRestore = text) }
            return@launch
        }
        val contact = _state.value.selectedContact
        val peer = contact?.userId?.trim()?.lowercase()
        if (peer.isNullOrBlank()) {
            Log.e("ProtoLinkChat", "sendMessage aborted: no selected contact")
            showError("Send failed", "No contact selected")
            _state.update { it.copy(composerDraftRestore = text) }
            return@launch
        }
        val body = text.trim()
        val drafts = _state.value.pendingAttachments.toList()
        if (body.isEmpty() && drafts.isEmpty()) return@launch

        _state.update {
            it.copy(
                pendingAttachments = emptyList(),
                attachStatusMessage = null,
                composerClearNonce = it.composerClearNonce + 1
            )
        }

        val batches = if (drafts.isEmpty()) {
            listOf(emptyList())
        } else {
            MessengerAttachmentRender.splitBatches(drafts, MessengerMediaLimits.MaxAttachmentsPerMessage)
        }

        for ((i, batch) in batches.withIndex()) {
            val batchText = if (i == 0) body else ""
            sendOneOptimistic(me, peer, contact.displayName, batchText, batch)
        }
    }

    fun retryFailedSend(messageId: String) = viewModelScope.launch {
        val optimistic = _state.value.messages.firstOrNull { it.id == messageId } ?: return@launch
        if (!optimistic.showFailedActions) return@launch
        val me = auth.current()?.userId?.trim()?.lowercase() ?: return@launch
        val peer = (optimistic.partnerUserId ?: _state.value.selectedContact?.userId)?.trim()?.lowercase()
            ?: return@launch
        if (!isNetworkAvailable()) {
            markFailed(optimistic.id, MessengerMediaLimits.OfflineSendFailedEn)
            return@launch
        }
        val partnerName = _state.value.contacts.firstOrNull { it.userId.equals(peer, true) }?.displayName
            ?: _state.value.selectedContact?.displayName
            ?: "Chat"
        val drafts = MessengerAtomicSend.prepareDraftsForAtomicRetry(
            optimistic.pendingLocalAttachments.orEmpty()
        )
        updatePendingMessage(optimistic.id) {
            it.copy(
                statusLine = null,
                deliveryStatus = MessengerSendStateMachine.onRetry(),
                pendingLocalAttachments = drafts
            )
        }
        val gate = uploadGates.getOrPut(peer) { Mutex() }
        gate.withLock {
            executeSendPipeline(
                optimisticId = optimistic.id,
                me = me,
                peer = peer,
                partnerName = partnerName,
                text = optimistic.text,
                drafts = drafts.toMutableList(),
                allowFolderReresolve = true
            )
        }
    }

    fun cancelFailedSend(messageId: String) = viewModelScope.launch {
        val optimistic = _state.value.messages.firstOrNull { it.id == messageId } ?: return@launch
        if (!optimistic.showFailedActions) return@launch
        for (draft in optimistic.pendingLocalAttachments.orEmpty()) {
            if (draft.isCloudPick) continue // M3 — never delete the original Cloud entity
            val id = draft.cloudFileEntityId ?: continue
            runCatching {
                api.deleteEntity(ru.protolink.communicator.data.api.DeleteEntityRequest(id))
            }
        }
        pendingChatSends.remove(messageId)
        pendingChatSendContact.remove(messageId)
        _state.update { s -> s.copy(messages = s.messages.filterNot { it.id == messageId }) }
    }

    private suspend fun sendOneOptimistic(
        me: String,
        peer: String,
        partnerName: String,
        text: String,
        drafts: List<PendingAttachmentDraft>
    ) {
        val sentAtMillis = System.currentTimeMillis()
        val optimisticId = "local-${System.nanoTime()}"
        val needsUpload = drafts.any { !it.isCloudPick }
        val optimistic = MessageItem(
            id = optimisticId,
            text = text,
            mine = true,
            timestampMillis = sentAtMillis,
            deliveryStatus = MessengerSendStateMachine.onBubbleCreated(needsUpload),
            attachmentIds = emptyList(),
            partnerUserId = peer,
            pendingLocalAttachments = drafts
        )
        pendingChatSends[optimisticId] = optimistic
        pendingChatSendContact[optimisticId] = peer
        _state.update {
            it.copy(
                messages = it.messages + optimistic,
                status = if (needsUpload) "Uploading…" else "Sending…"
            )
        }
        Log.i("ProtoLinkChat", "sendMessage start me=$me peer=$peer bodyLen=${text.length} attachments=${drafts.size}")

        if (!isNetworkAvailable()) {
            markFailed(optimisticId, MessengerMediaLimits.OfflineSendFailedEn)
            if (_state.value.composerDraftRestore == null && text.isNotBlank()) {
                _state.update { it.copy(composerDraftRestore = text) }
            }
            if (drafts.isNotEmpty()) {
                _state.update { s ->
                    s.copy(pendingAttachments = s.pendingAttachments + drafts.map { cloneDraft(it) })
                }
            }
            return
        }

        val gate = uploadGates.getOrPut(peer) { Mutex() }
        gate.withLock {
            executeSendPipeline(
                optimisticId = optimisticId,
                me = me,
                peer = peer,
                partnerName = partnerName,
                text = text,
                drafts = drafts.toMutableList(),
                allowFolderReresolve = true
            )
        }
    }

    private suspend fun executeSendPipeline(
        optimisticId: String,
        me: String,
        peer: String,
        partnerName: String,
        text: String,
        drafts: MutableList<PendingAttachmentDraft>,
        allowFolderReresolve: Boolean
    ) {
        try {
            if (drafts.isNotEmpty()) {
                executeAtomicMediaSend(
                    optimisticId = optimisticId,
                    me = me,
                    peer = peer,
                    partnerName = partnerName,
                    text = text,
                    drafts = drafts,
                    allowFolderReresolve = allowFolderReresolve
                )
                return
            }

            // Text-only: cycle-1 AddEntity Message + message_sent.
            updatePendingMessage(optimisticId) {
                it.copy(deliveryStatus = DeliveryStatus.Sending)
            }

            ensureMessengerContainer(SystemEntities.SENT, me, "Sent", grantWrite = false)
            ensureMessengerContainer(SystemEntities.RECEIVED, peer, "Received", grantWrite = true)

            val values = mutableListOf<AddValueRequest>()
            if (text.isNotBlank()) {
                values.add(
                    AddValueRequest(
                        type = AddValueRequest.TYPE_STRING,
                        value = text,
                        parentIds = listOf(SystemEntities.MESSAGE)
                    )
                )
            }
            values.add(
                AddValueRequest(
                    type = AddValueRequest.TYPE_STRING,
                    value = "sender:$me",
                    parentIds = listOf(SystemEntities.SENT)
                )
            )
            values.add(
                AddValueRequest(
                    type = AddValueRequest.TYPE_STRING,
                    value = "receiver:$peer",
                    parentIds = listOf(SystemEntities.RECEIVED)
                )
            )

            val created = api.addEntity(
                AddEntityRequest(
                    code = "Message",
                    parentIds = listOf(SystemEntities.MESSAGE),
                    values = values,
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

            val optimistic = pendingChatSends[optimisticId] ?: _state.value.messages.firstOrNull { it.id == optimisticId }
            val sentAtMillis = optimistic?.timestampMillis ?: System.currentTimeMillis()
            val sentAt = Instant.ofEpochMilli(sentAtMillis)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString()
            runCatching {
                api.sendCommand(
                    SendCommandRequest(
                        commandType = "message_sent",
                        targetUserId = peer,
                        parameters = mapOf(
                            "senderId" to me,
                            "messageText" to text,
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
                                "messageText" to text,
                                "timestamp" to sentAt
                            )
                        )
                    )
                }
            }

            val sentMsg = MessageItem(
                id = id,
                text = text,
                mine = true,
                timestampMillis = sentAtMillis,
                deliveryStatus = MessengerSendStateMachine.onMessageCommitted(),
                attachmentIds = emptyList(),
                partnerUserId = peer
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
            Log.e("ProtoLinkChat", "send pipeline failed", ex)
            markFailed(optimisticId, MessengerMediaLimits.UploadFailedEn)
            if (drafts.isEmpty()) {
                pendingChatSends.remove(optimisticId)
                pendingChatSendContact.remove(optimisticId)
                _state.update { s ->
                    s.copy(
                        messages = s.messages.filterNot { it.id == optimisticId },
                        composerDraftRestore = text
                    )
                }
                showError("Send failed", httpDetail(ex), ex)
            }
        }
    }

    /** Cycle-2: one `POST api/Messenger/sendWithAttachments` for new uploads and/or M3 picks. */
    private suspend fun executeAtomicMediaSend(
        optimisticId: String,
        me: String,
        peer: String,
        partnerName: String,
        text: String,
        drafts: MutableList<PendingAttachmentDraft>,
        allowFolderReresolve: Boolean
    ) {
        val newUploads = MessengerAtomicSend.collectNewUploads(drafts).toMutableList()
        val existingIds = MessengerAtomicSend.collectExistingIds(drafts)
        if (existingIds.size != drafts.count { it.isCloudPick }) {
            markFailed(optimisticId, MessengerMediaLimits.UploadFailedEn)
            return
        }
        for (draft in newUploads) {
            if (MessengerMediaLimits.isOversize(draft.byteLength)) {
                _state.update { it.copy(attachStatusMessage = MessengerMediaLimits.OversizeMessageEn) }
                markFailed(optimisticId, MessengerMediaLimits.OversizeMessageEn)
                return
            }
        }

        val needsUpload = newUploads.isNotEmpty()
        updatePendingMessage(optimisticId) {
            it.copy(
                deliveryStatus = if (needsUpload) DeliveryStatus.Uploading else DeliveryStatus.Sending,
                statusLine = null
            )
        }

        var chatFolderId: String? = null
        if (needsUpload) {
            try {
                chatFolderId = chatFolderResolver.resolveOrCreate(me, peer, partnerName)
            } catch (ex: Exception) {
                Log.e("ProtoLinkChat", "chat folder resolve failed", ex)
                markFailed(optimisticId, MessengerMediaLimits.UploadFailedEn)
                return
            }
        }

        try {
            try {
                postAtomicSend(optimisticId, peer, chatFolderId, text, existingIds, newUploads, drafts)
            } catch (ex: Exception) {
                if (allowFolderReresolve && needsUpload && MessengerChatFolderResolver.isStaleFolderFailure(ex)) {
                    chatFolderId = chatFolderResolver.reResolveAndCreate(me, peer, partnerName)
                    postAtomicSend(optimisticId, peer, chatFolderId, text, existingIds, newUploads, drafts)
                } else {
                    throw ex
                }
            }
        } catch (ex: Exception) {
            Log.e("ProtoLinkChat", "atomic media send failed", ex)
            val line = if (MessengerAtomicSend.looksLikeFileTooLarge(ex.message)) {
                _state.update { it.copy(attachStatusMessage = MessengerMediaLimits.OversizeMessageEn) }
                MessengerMediaLimits.OversizeMessageEn
            } else {
                MessengerMediaLimits.UploadFailedEn
            }
            markFailed(optimisticId, line)
        }
    }

    private suspend fun postAtomicSend(
        optimisticId: String,
        peer: String,
        chatFolderId: String?,
        text: String,
        existingIds: List<String>,
        newUploads: MutableList<PendingAttachmentDraft>,
        allDrafts: MutableList<PendingAttachmentDraft>
    ) {
        val plain = "text/plain".toMediaTypeOrNull()
        val partnerBody = peer.toRequestBody(plain)
        val folderBody = chatFolderId?.takeIf { it.isNotBlank() }?.toRequestBody(plain)
        val bodyTextPart = text.takeIf { it.isNotBlank() }?.toRequestBody(plain)
        val existingBody = MessengerAtomicSend.formatExistingAttachmentIds(existingIds).toRequestBody(plain)

        val grandTotal = newUploads.sumOf { it.byteLength.coerceAtLeast(0L) }.coerceAtLeast(1L)
        var bytesBefore = 0L
        val fileParts = mutableListOf<MultipartBody.Part>()
        val openStreams = mutableListOf<java.io.InputStream>()
        try {
            for (draft in newUploads) {
                val uri = Uri.parse(draft.localUri)
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: draft.localFilePath?.let { File(it).inputStream() }
                    ?: error("Cannot open attachment stream")
                openStreams.add(input)
                val offset = bytesBefore
                val body = ProgressRequestBody(
                    inputStream = input,
                    contentLength = draft.byteLength.coerceAtLeast(0L),
                    contentType = draft.mimeType,
                    onProgress = { written, _ ->
                        val fraction = ((offset + written).toFloat() / grandTotal.toFloat()).coerceIn(0f, 1f)
                        updatePendingMessage(optimisticId) { it.copy(uploadProgress = fraction) }
                    }
                )
                fileParts.add(MultipartBody.Part.createFormData("files", draft.fileName, body))
                bytesBefore += draft.byteLength.coerceAtLeast(0L)
            }

            val result = api.sendWithAttachments(
                partnerUserId = partnerBody,
                chatFolderId = folderBody,
                bodyText = bodyTextPart,
                existingAttachmentIds = existingBody,
                files = fileParts
            )
            val messageId = result.messageId?.takeIf { it.isNotBlank() }
                ?: error("sendWithAttachments returned empty messageId")
            val attachmentIds = result.attachmentIds.orEmpty()

            for (i in newUploads.indices) {
                if (i < attachmentIds.size) {
                    newUploads[i] = newUploads[i].copy(
                        cloudFileEntityId = attachmentIds[i],
                        uploadCompleted = true
                    )
                }
            }
            for (i in allDrafts.indices) {
                if (!allDrafts[i].isCloudPick) {
                    val match = newUploads.firstOrNull {
                        it.localUri == allDrafts[i].localUri && it.fileName == allDrafts[i].fileName
                    }
                    if (match != null) allDrafts[i] = match
                } else {
                    allDrafts[i] = allDrafts[i].copy(uploadCompleted = true)
                }
            }

            val optimistic = pendingChatSends[optimisticId]
                ?: _state.value.messages.firstOrNull { it.id == optimisticId }
            val sentAtMillis = optimistic?.timestampMillis ?: System.currentTimeMillis()
            val sentMsg = MessageItem(
                id = messageId,
                text = text,
                mine = true,
                timestampMillis = sentAtMillis,
                deliveryStatus = MessengerSendStateMachine.onMessageCommitted(),
                attachmentIds = attachmentIds,
                partnerUserId = peer,
                pendingLocalAttachments = allDrafts.toList()
            )
            pendingChatSends.remove(optimisticId)
            pendingChatSendContact.remove(optimisticId)
            _state.update { s ->
                s.copy(
                    messages = s.messages.map { if (it.id == optimisticId) sentMsg else it },
                    status = "Sent $messageId"
                )
            }
            _state.value.selectedContact?.let { loadMessages(it) }
        } finally {
            for (s in openStreams) runCatching { s.close() }
        }
    }

    private fun cloneDraft(d: PendingAttachmentDraft) =
        if (d.isCloudPick) {
            d.copy() // keep entity id + uploadCompleted for M3 re-queue
        } else {
            d.copy(cloudFileEntityId = null, uploadCompleted = false)
        }

    private fun markFailed(optimisticId: String, statusLine: String) {
        updatePendingMessage(optimisticId) {
            it.copy(
                deliveryStatus = MessengerSendStateMachine.onFailure(),
                statusLine = statusLine
            )
        }
    }

    private fun updatePendingMessage(optimisticId: String, transform: (MessageItem) -> MessageItem) {
        _state.update { s ->
            val base = pendingChatSends[optimisticId]
                ?: s.messages.firstOrNull { it.id == optimisticId }
                ?: return@update s
            val next = transform(base)
            pendingChatSends[optimisticId] = next
            s.copy(messages = s.messages.map { if (it.id == optimisticId) next else it })
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun guessMime(fileName: String, fallback: String = "image/jpeg"): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return fallback
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: fallback
    }

    fun newCameraCaptureFile(): File {
        val dir = File(appContext.cacheDir, "messenger_capture").also { it.mkdirs() }
        return File(dir, "camera-${UUID.randomUUID()}.jpg")
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
                selectedNoteSafDocumentId = null,
                selectedNoteIndexUri = null,
                notesSiblingFiles = emptyList(),
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
        refreshNotesSiblingFilesSuspend()
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
     * After delete of open page or ancestor: prev sibling → parent → notes root (never blank-only).
     */
    fun deleteNote(relativePath: String, displayName: String? = null) {
        viewModelScope.launch {
            val rootUri = settingsStore.load().notesRootUri
            if (rootUri.isNullOrBlank()) {
                _state.update { it.copy(syncError = "Choose a notes folder first") }
                return@launch
            }
            val target = NotesDeleteNavigate.normalize(relativePath)
            if (target.isEmpty()) {
                _state.update { it.copy(status = "Cannot delete the notes root") }
                return@launch
            }
            val name = displayName?.ifBlank { null } ?: target.substringAfterLast('/')
            val openRel = NotesDeleteNavigate.normalize(_state.value.selectedNoteRelativePath)
            val affectsOpen = NotesDeleteNavigate.isOpenPageAffected(openRel, target)
            // Resolve before delete so sibling order still includes the removed folder (AT-ND-1…3).
            val nextRel = NotesDeleteNavigate.resolveNextRelativePath(
                deletedRelativePath = target,
                openRelativePath = openRel,
                siblingFolderNamesUnderParent = siblingFolderNamesUnderParent(target)
            )
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
            refreshNotesTreeSuspend()
            if (affectsOpen) {
                expandPathAncestors(nextRel)
                refreshNotesTreeSuspend()
                openNoteByRelativePath(nextRel)
            }
            _state.update { it.copy(status = "Deleted “$name”") }
            writeDeleteNoteDebugResult(
                if (affectsOpen) "OK delete=$target next=${nextRel.ifEmpty { "(root)" }}"
                else "OK delete=$target"
            )
            requestLocalPush()
        }
    }

    /** Direct child folder names of [deletedRelativePath]'s parent (from tree cache, pre-delete). */
    private fun siblingFolderNamesUnderParent(deletedRelativePath: String): List<String> {
        val root = notesRootCache ?: return emptyList()
        val parentRel = NotesDeleteNavigate.parentRelativePath(deletedRelativePath)
        val parent = findNotesNodeByRelativePath(root, parentRel) ?: return emptyList()
        return parent.children.map { it.name }
    }

    private fun findNotesNodeByRelativePath(
        root: NotesTreeBuilder.Node,
        relativePath: String
    ): NotesTreeBuilder.Node? {
        val rel = NotesDeleteNavigate.normalize(relativePath)
        if (rel.isEmpty()) return root
        var cur = root
        for (part in rel.split('/').filter { it.isNotEmpty() }) {
            cur = cur.children.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
        }
        return cur
    }

    private fun notesNodeToRow(node: NotesTreeBuilder.Node): NoteTreeRow {
        val rel = NotesDeleteNavigate.normalize(node.relativePath)
        val depth = if (rel.isEmpty()) 0 else rel.count { it == '/' } + 1
        return NoteTreeRow(
            documentId = node.documentId,
            safDocumentId = node.safDocumentId,
            name = node.name,
            relativePath = node.relativePath,
            depth = depth,
            hasChildren = node.children.isNotEmpty(),
            expanded = _state.value.notesExpandedIds.contains(node.documentId),
            folderUri = node.folderUri.toString(),
            indexUri = node.indexUri?.toString()
        )
    }

    /** Open note by relative path after tree refresh; empty = notes root (not blank editor). */
    private suspend fun openNoteByRelativePath(relativePath: String) {
        val root = notesRootCache
        if (root == null) {
            clearSelectedNote()
            return
        }
        val rel = NotesDeleteNavigate.normalize(relativePath)
        val node = findNotesNodeByRelativePath(root, rel) ?: root
        openNotesNode(notesNodeToRow(node))
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
                    selectedNoteSafDocumentId = row.safDocumentId.ifBlank { null },
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
            refreshNotesSiblingFilesSuspend(
                safDocumentId = row.safDocumentId.ifBlank { null },
                relativePath = row.relativePath
            )
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
                    selectedNoteSafDocumentId = row.safDocumentId.ifBlank { null },
                    notesSiblingFiles = emptyList(),
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
                selectedNoteSafDocumentId = null,
                selectedNoteIndexUri = null,
                notesSiblingFiles = emptyList(),
                notesContent = "",
                notesDirty = false,
                notesReloadPrompt = false
            )
        }
    }

    /** Re-enumerate Folder files under the open note (E5/E5a). Soft-empty on gone URI / errors. */
    fun refreshNotesSiblingFiles() = viewModelScope.launch {
        refreshNotesSiblingFilesSuspend()
    }

    private suspend fun refreshNotesSiblingFilesSuspend(
        safDocumentId: String? = _state.value.selectedNoteSafDocumentId,
        relativePath: String? = _state.value.selectedNoteRelativePath
    ) {
        val rootUriStr = settingsStore.load().notesRootUri
        if (rootUriStr.isNullOrBlank() || _state.value.selectedNoteDocumentId.isNullOrBlank()) {
            _state.update { it.copy(notesSiblingFiles = emptyList()) }
            return
        }
        val treeUri = Uri.parse(rootUriStr)
        val folderId = safDocumentId?.takeIf { it.isNotBlank() }
            ?: NotesTreeBuilder.folderDocumentId(treeUri, relativePath.orEmpty())
        val siblings = withContext(Dispatchers.IO) {
            try {
                val entries = SafTreeLister.listChildren(appContext, treeUri, folderId)
                NotesSiblingFiles.listSiblingFiles(
                    entries.map {
                        NotesSiblingFiles.ChildEntry(
                            name = it.name,
                            isDirectory = it.isDirectory,
                            documentId = it.documentId,
                            uri = it.uri.toString()
                        )
                    }
                )
            } catch (e: Exception) {
                Log.w("ProtoLinkNotes", "sibling list soft-fail for $folderId: ${e.message}")
                emptyList()
            }
        }
        _state.update { it.copy(notesSiblingFiles = siblings) }
    }

    /** AT-N9: open sibling with VIEW intent; stay on Notes (no Cloud switch). Soft status on failure. */
    fun openNotesSiblingFile(file: NotesSiblingFiles.SiblingFile) {
        if (file.uri.isBlank()) {
            _state.update { it.copy(status = "File not found") }
            return
        }
        try {
            val uri = Uri.parse(file.uri)
            val ext = file.name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            _state.update { it.copy(status = "Opened ${file.name}") }
        } catch (e: Exception) {
            Log.w("ProtoLinkNotes", "open sibling failed ${file.name}", e)
            _state.update { it.copy(status = "Could not open ${file.name}") }
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
            refreshNotesSiblingFilesSuspend()
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
