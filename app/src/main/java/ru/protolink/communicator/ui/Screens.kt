package ru.protolink.communicator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunicatorAppScreen(vm: MainViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    if (state.showSettings) {
        SettingsScreen(vm, state) { vm.toggleSettings(false) }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ProtoLink") },
                actions = {
                    IconButton(onClick = { vm.syncNow() }, enabled = !state.syncing) {
                        Icon(Icons.Default.Sync, "Sync")
                    }
                    IconButton(onClick = { vm.toggleSettings(true) }) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Message, null) }, label = { Text("Messenger") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Note, null) }, label = { Text("Notes") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Cloud, null) }, label = { Text("Cloud") })
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.status.isNotBlank()) {
                Text(state.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            }
            when (tab) {
                0 -> MessengerScreen(vm, state)
                1 -> NotesScreen(vm, state)
                2 -> CloudScreen(vm, state)
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, state: UiState, onClose: () -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var api by remember { mutableStateOf(state.settings.apiBaseAddress) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("App version: ${state.appVersion}", style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "API version: ${state.apiVersionText.ifBlank { "…" }}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { vm.refreshApiVersion() }) { Text("Refresh") }
        }
        OutlinedTextField(api, { api = it }, label = { Text("API base URL") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.saveSettings(state.settings.copy(apiBaseAddress = api)) }) { Text("Save API") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Compare size & time on sync", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "When on, sync compares file size and update time, then uploads (write) or downloads (read). Turn off for classic sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = state.settings.compareSizeAndTimeOnSync,
                onCheckedChange = { enabled ->
                    vm.saveSettings(state.settings.copy(compareSizeAndTimeOnSync = enabled))
                }
            )
        }
        if (!state.authenticated) {
            OutlinedTextField(user, { user = it }, label = { Text("Login") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.login(user, pass) }) { Text("Log in") }
        } else {
            Text("Signed in as ${state.login}")
            Button(onClick = { vm.logout() }) { Text("Log off") }
        }
        TextButton(onClick = onClose) { Text("Close") }
        Text("Mapped folders: ${state.mappings.size}")
        state.mappings.forEach { m ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "• ${m.cloudFolderName}: ${m.localPath.take(40)}…",
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.unmapFolder(m.cloudFolderId) }) { Text("Unmap") }
            }
        }
    }
}

@Composable
fun CloudScreen(vm: MainViewModel, state: UiState) {
    val context = LocalContext.current
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            vm.mapCurrentFolder(uri.toString())
        }
    }
    var newFolder by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text(
            "Current cloud: ${state.breadcrumb.lastOrNull()?.second ?: "(none)"} · Mapped: ${state.mappings.size}",
            style = MaterialTheme.typography.bodySmall
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            state.breadcrumb.forEachIndexed { i, crumb ->
                TextButton(onClick = { vm.cloudNavigateTo(i) }) { Text(crumb.second) }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { state.breadcrumb.lastOrNull()?.first?.let { vm.loadCloudFolder(it) } }) {
                Icon(Icons.Default.Refresh, null)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickTree.launch(null) }) { Text("Map local folder") }
            Button(onClick = { vm.syncNow() }, enabled = !state.syncing) {
                Text(if (state.syncing) "Syncing…" else "Sync now")
            }
            OutlinedTextField(newFolder, { newFolder = it }, label = { Text("New folder") }, modifier = Modifier.weight(1f))
            Button(onClick = { if (newFolder.isNotBlank()) { vm.createCloudFolder(newFolder); newFolder = "" } }) { Text("Create") }
        }
        if (state.mappings.isNotEmpty()) {
            Text("Mappings:", style = MaterialTheme.typography.labelMedium)
            state.mappings.forEach { m ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "• ${m.cloudFolderName.ifBlank { m.cloudFolderId.take(8) }}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    TextButton(onClick = { vm.unmapFolder(m.cloudFolderId) }) { Text("Unmap") }
                }
            }
        }
        LazyColumn {
            items(state.cloudItems) { item ->
                ListItem(
                    headlineContent = { Text(item.name) },
                    leadingContent = {
                        Icon(if (item.isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile, null)
                    },
                    modifier = Modifier.clickable { vm.openCloudFolder(item) }
                )
            }
        }
    }
}

@Composable
fun MessengerScreen(vm: MainViewModel, state: UiState) {
    var draft by remember { mutableStateOf("") }
    var stickyDate by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    if (!state.authenticated) {
        Text("Please log in in Settings.", modifier = Modifier.padding(16.dp))
        return
    }

    val chatRows = remember(state.messages) {
        buildChatRows(state.messages)
    }

    LaunchedEffect(listState, chatRows) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
        }.collect { first ->
            stickyDate = chatRows.getOrNull(first)?.dayLabel
                ?: chatRows.getOrNull(first)?.let { row ->
                    if (row is ChatRow.Bubble) row.message.dayLabel else null
                }.orEmpty()
            // Prefer nearest date header at or above first visible
            var label = ""
            for (i in first downTo 0) {
                val row = chatRows.getOrNull(i) ?: break
                if (row is ChatRow.DateHeader) {
                    label = row.label
                    break
                }
                if (row is ChatRow.Bubble) {
                    label = row.message.dayLabel
                    break
                }
            }
            stickyDate = label
        }
    }

    LaunchedEffect(chatRows.size) {
        if (chatRows.isNotEmpty()) {
            listState.scrollToItem(chatRows.lastIndex)
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(0.35f)) {
            TextButton(onClick = { vm.loadContacts() }) { Text("Refresh (${state.contacts.size})") }
            LazyColumn {
                items(state.contacts, key = { it.userId }) { c ->
                    val selected = state.selectedContact?.userId == c.userId
                    ListItem(
                        headlineContent = { Text(c.displayName) },
                        modifier = Modifier
                            .clickable { vm.selectContact(c) }
                            .then(
                                if (selected) Modifier.padding(start = 4.dp) else Modifier
                            )
                    )
                }
            }
        }
        Column(Modifier.weight(0.65f).padding(8.dp)) {
            Text(
                state.selectedContact?.displayName ?: "Select a contact",
                style = MaterialTheme.typography.titleMedium
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(chatRows.size) { index ->
                        when (val row = chatRows[index]) {
                            is ChatRow.DateHeader -> DateChip(row.label)
                            is ChatRow.Bubble -> MessageBubble(row.message)
                        }
                    }
                }
                if (stickyDate.isNotBlank() && listState.isScrollInProgress) {
                    DateChip(
                        stickyDate,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 4.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    draft,
                    { draft = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Message") },
                    maxLines = 4
                )
                Button(
                    onClick = {
                        if (draft.isNotBlank()) {
                            vm.sendMessage(draft)
                            draft = ""
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("Send") }
            }
        }
    }
}

private sealed class ChatRow {
    abstract val dayLabel: String?
    data class DateHeader(val label: String) : ChatRow() {
        override val dayLabel: String get() = label
    }
    data class Bubble(val message: MessageItem) : ChatRow() {
        override val dayLabel: String get() = message.dayLabel
    }
}

private fun buildChatRows(messages: List<MessageItem>): List<ChatRow> {
    val out = mutableListOf<ChatRow>()
    var lastDay: String? = null
    for (m in messages) {
        val day = m.dayLabel
        if (day.isNotBlank() && day != lastDay) {
            out.add(ChatRow.DateHeader(day))
            lastDay = day
        }
        out.add(ChatRow.Bubble(m))
    }
    return out
}

@Composable
private fun DateChip(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(vertical = 6.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun MessageBubble(m: MessageItem) {
    val bubbleColor = if (m.mine) Color(0xFFDCF8C6) else Color(0xFFFFFFFF)
    val align = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart
    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(horizontal = 4.dp)
                .padding(
                    start = if (m.mine) 48.dp else 0.dp,
                    end = if (m.mine) 0.dp else 48.dp
                )
        ) {
            Column(
                modifier = Modifier
                    .align(if (m.mine) Alignment.End else Alignment.Start)
                    .padding(4.dp)
            ) {
                // Use background via Surface-like Box
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .padding(2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .background(bubbleColor, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(m.text, color = Color(0xFF111111))
                        if (m.timeLabel.isNotBlank()) {
                            Text(
                                m.timeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF667781),
                                modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotesScreen(vm: MainViewModel, state: UiState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            vm.setNotesRoot(uri.toString())
        }
    }

    LaunchedEffect(state.settings.notesRootUri) {
        if (!state.settings.notesRootUri.isNullOrBlank() && state.notesTreeRows.isEmpty()) {
            vm.refreshNotesTree()
        }
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row {
            Button(onClick = { pickTree.launch(null) }) { Text("Choose notes folder") }
            TextButton(onClick = { vm.refreshNotesTree() }) { Text("Refresh") }
        }
        Text(
            "Tap a folder to edit its page · ▶ expands children",
            style = MaterialTheme.typography.bodySmall
        )

        // Tree (Windows NotesTreeView)
        LazyColumn(Modifier.fillMaxWidth().weight(if (state.selectedNotePath != null) 0.4f else 1f)) {
            items(state.notesTreeRows, key = { it.documentId }) { row ->
                val selected = row.documentId == state.selectedNoteDocumentId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.width((row.depth * 16).dp))
                    if (row.hasChildren) {
                        IconButton(onClick = { vm.toggleNotesExpand(row.documentId) }) {
                            Icon(
                                if (row.expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (row.expanded) "Collapse" else "Expand"
                            )
                        }
                    } else {
                        // Same width as IconButton so leaf labels align; still open on tap
                        Box(
                            modifier = Modifier
                                .width(48.dp)
                                .clickable { vm.selectNotesNode(row) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = "Page")
                        }
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { vm.selectNotesNode(row) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (row.hasChildren) {
                            Icon(Icons.Default.Folder, null, modifier = Modifier.padding(end = 8.dp))
                        }
                        Text(
                            row.name,
                            style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // Editor (Windows WebView) — same node: view/edit HTML while tree stays for children
        if (state.selectedNotePath != null) {
            Column(Modifier.fillMaxWidth().weight(0.6f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.selectedNotePath.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        val web = webViewRef ?: return@Button
                        scope.launch {
                            val html = suspendCancellableCoroutine { cont ->
                                web.evaluateJavascript(
                                    "(function(){var b=document.body;return b?(b.getAttribute('contenteditable')!==null?b.innerHTML:document.documentElement.outerHTML):'';})();"
                                ) { value ->
                                    val unquoted = value
                                        ?.removeSurrounding("\"")
                                        ?.replace("\\n", "\n")
                                        ?.replace("\\\"", "\"")
                                        ?.replace("\\/", "/")
                                        ?.replace("\\\\", "\\")
                                        .orEmpty()
                                    cont.resume(unquoted)
                                }
                            }
                            vm.saveSelectedNote(html.ifBlank { state.notesContent })
                        }
                    }) { Text("Save") }
                }
                key(state.selectedNoteDocumentId) {
                    var loadedSig by remember(state.selectedNoteDocumentId) { mutableStateOf<Int?>(null) }
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = WebViewClient()
                                webViewRef = this
                            }
                        },
                        update = { web ->
                            webViewRef = web
                            val sig = state.notesContent.length xor (state.notesContent.hashCode())
                            if (loadedSig != sig) {
                                loadedSig = sig
                                val editable = wrapEditableHtml(state.notesContent)
                                val encoded = android.util.Base64.encodeToString(
                                    editable.toByteArray(Charsets.UTF_8),
                                    android.util.Base64.NO_WRAP
                                )
                                web.loadData(encoded, "text/html; charset=utf-8", "base64")
                            }
                        },
                        modifier = Modifier.fillMaxSize().weight(1f)
                    )
                }
            }
        }
    }
}

private fun wrapEditableHtml(html: String): String {
    val trimmed = html.trim()
    if (trimmed.contains("contenteditable", ignoreCase = true)) return trimmed
    // Windows editable surface: body contenteditable around note HTML
    val inner = when {
        trimmed.contains("<body", ignoreCase = true) -> trimmed
        else -> "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/></head><body>$trimmed</body></html>"
    }
    return if (inner.contains("contenteditable", ignoreCase = true)) {
        inner
    } else {
        inner.replace(
            Regex("<body([^>]*)>", RegexOption.IGNORE_CASE),
            "<body$1 contenteditable=\"true\">"
        )
    }
}
