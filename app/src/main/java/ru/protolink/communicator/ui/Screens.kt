package ru.protolink.communicator.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun CommunicatorAppScreen(vm: MainViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val config = LocalConfiguration.current
    val compactWidth = config.screenWidthDp < 600
    val notesEditing = tab == 1 && state.selectedNotePath != null
    val messengerInChat = tab == 0 && state.selectedContact != null
    // On phones, hide chrome while in chat/editor so content + keyboard get full height.
    val hideBottomBar = compactWidth && (notesEditing || messengerInChat)
    val hideTopBar = compactWidth && (messengerInChat || notesEditing)
    val context = LocalContext.current
    val activity = context as? androidx.activity.ComponentActivity

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — notifications simply won't show if denied */ }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DisposableEffect(activity) {
        val owner = activity ?: return@DisposableEffect onDispose { }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> vm.setAppForeground(true)
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> vm.setAppForeground(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(activity?.intent?.getStringExtra("openContactId"), state.authenticated, state.contacts.size) {
        val openId = activity?.intent?.getStringExtra("openContactId") ?: return@LaunchedEffect
        if (!state.authenticated || openId.isBlank()) return@LaunchedEffect
        tab = 0
        vm.openContactById(openId)
        activity.intent?.removeExtra("openContactId")
    }

    // adb: am start -n …/.MainActivity --es protolink_force download
    // Prefer MainActivity → PendingDebugIntent (survives auth timing). This is a backup.
    LaunchedEffect(state.authenticated, state.mappings.size) {
        if (!state.authenticated || state.mappings.isEmpty()) return@LaunchedEffect
        activity?.intent?.let { ru.protolink.communicator.PendingDebugIntent.consumeFrom(it) }
        if (!ru.protolink.communicator.PendingDebugIntent.takeForceDownload()) return@LaunchedEffect
        tab = 2
        vm.forceDownloadAllMappedNow()
    }

    // adb self-test: force_create_note / force_delete_note under files/
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(800)
            val act = activity ?: continue
            ru.protolink.communicator.PendingDebugIntent.consumeCreateNoteFile(act.applicationContext)
            if (ru.protolink.communicator.PendingDebugIntent.hasPendingCreateNote()) {
                tab = 1
                vm.consumePendingDebugCreateNote()
            }
            ru.protolink.communicator.PendingDebugIntent.consumeDeleteNoteFile(act.applicationContext)
            if (ru.protolink.communicator.PendingDebugIntent.hasPendingDeleteNote()) {
                tab = 1
                vm.consumePendingDebugDeleteNote()
            }
        }
    }

    if (!state.authenticated && !state.showSettings) {
        AuthFlowScreen(
            vm = vm,
            status = state.status,
            onOpenSettings = { vm.toggleSettings(true) }
        )
        return
    }

    if (state.showSettings) {
        if (state.pendingConflict != null) {
            val path = state.pendingConflict!!.relativePath
            AlertDialog(
                onDismissRequest = { vm.dismissConflict() },
                title = { Text("Sync conflict") },
                text = {
                    Text(
                        "This file differs on the phone and on the server:\n\n$path\n\n" +
                            "Take from server — overwrite the phone.\n" +
                            "Keep local — upload the phone version to the server."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.resolveConflictTakeServer() }) {
                        Text("Take from server")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { vm.resolveConflictKeepLocal() }) {
                        Text("Keep local")
                    }
                }
            )
        }
        if (state.syncError != null) {
            AlertDialog(
                onDismissRequest = { vm.clearSyncError() },
                title = { Text("Sync error") },
                text = { Text(state.syncError!!) },
                confirmButton = {
                    TextButton(onClick = { vm.clearSyncError() }) { Text("OK") }
                }
            )
        }
        if (state.syncSuccess != null) {
            AlertDialog(
                onDismissRequest = { vm.clearSyncSuccess() },
                title = { Text("Done") },
                text = { Text(state.syncSuccess!!) },
                confirmButton = {
                    TextButton(onClick = { vm.clearSyncSuccess() }) { Text("OK") }
                }
            )
        }
        if (state.pendingForce != null) {
            val push = state.pendingForce!!.push
            AlertDialog(
                onDismissRequest = { vm.dismissForceConfirm() },
                title = { Text(if (push) "Force upload" else "Force download") },
                text = {
                    Text(
                        if (push) {
                            "Overwrite server files with all local files in this mapped folder?"
                        } else {
                            "Overwrite local files with all server files for this mapped folder?"
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.confirmForceMapping() }) {
                        Text(if (push) "Upload all" else "Download all")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismissForceConfirm() }) { Text("Cancel") }
                }
            )
        }
        SettingsScreen(vm, state) { vm.toggleSettings(false) }
        return
    }

    if (state.userError != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissUserError() },
            title = { Text(state.userError!!.title) },
            text = { Text(state.userError!!.detail) },
            confirmButton = {
                TextButton(onClick = { vm.dismissUserError() }) { Text("OK") }
            }
        )
    }

    if (state.pendingConflict != null) {
        val path = state.pendingConflict!!.relativePath
        AlertDialog(
            onDismissRequest = { vm.dismissConflict() },
            title = { Text("Sync conflict") },
            text = {
                Text(
                    "This file differs on the phone and on the server:\n\n$path\n\n" +
                        "Take from server — overwrite the phone.\n" +
                        "Keep local — upload the phone version to the server."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.resolveConflictTakeServer() }) {
                    Text("Take from server")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.resolveConflictKeepLocal() }) {
                    Text("Keep local")
                }
            }
        )
    }
    if (state.syncError != null) {
        AlertDialog(
            onDismissRequest = { vm.clearSyncError() },
            title = { Text("Sync error") },
            text = { Text(state.syncError!!) },
            confirmButton = {
                TextButton(onClick = { vm.clearSyncError() }) { Text("OK") }
            }
        )
    }
    if (state.syncSuccess != null) {
        AlertDialog(
            onDismissRequest = { vm.clearSyncSuccess() },
            title = { Text("Done") },
            text = { Text(state.syncSuccess!!) },
            confirmButton = {
                TextButton(onClick = { vm.clearSyncSuccess() }) { Text("OK") }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (!hideTopBar) {
                TopAppBar(
                    title = { Text("ProtoLink") },
                    actions = {
                        IconButton(onClick = { vm.syncNow() }, enabled = !state.syncing) {
                            Icon(Icons.Default.Sync, "Sync")
                        }
                        IconButton(onClick = { vm.toggleSettings(true) }) { Icon(Icons.Default.Settings, "Settings") }
                    }
                )
            }
        },
        bottomBar = {
            val showStatus =
                state.status.isNotBlank() && (!hideBottomBar || state.syncing)
            if (!hideBottomBar || showStatus) {
                Column(Modifier.fillMaxWidth()) {
                    if (!hideBottomBar) {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = {
                                    // Returning to Messenger shows the chat list (not a leftover thread).
                                    if (tab != 0 && compactWidth) vm.clearSelectedContact()
                                    tab = 0
                                },
                                icon = { Icon(Icons.Default.Message, null) }, label = { Text("Messenger") })
                            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                                icon = { Icon(Icons.Default.Note, null) }, label = { Text("Notes") })
                            NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                                icon = { Icon(Icons.Default.Cloud, null) }, label = { Text("Cloud") })
                        }
                    }
                    if (showStatus) {
                        Text(
                            state.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .fillMaxSize()
        ) {
            when (tab) {
                0 -> MessengerScreen(vm, state, compactWidth = compactWidth)
                1 -> NotesScreen(vm, state, compactWidth = compactWidth)
                2 -> CloudScreen(vm, state)
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, state: UiState, onClose: () -> Unit) {
    var api by remember { mutableStateOf(state.settings.apiBaseAddress) }
    val tgBg = Color(0xFFEFEFF4)
    val tgBlue = Color(0xFF2AABEE)
    val tgCell = Color.White
    val tgDivider = Color(0xFFD1D1D6)
    val tgMuted = Color(0xFF8E8E93)

    Column(Modifier.fillMaxSize().background(tgBg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(tgCell)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text("Close", color = tgBlue) }
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.width(64.dp))
        }
        Divider(color = tgDivider, thickness = 0.5.dp)

        if (state.status.isNotBlank()) {
            Text(
                state.status,
                color = tgMuted,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tgCell)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Divider(color = tgDivider, thickness = 0.5.dp)
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                TgSettingsSectionLabel("Account")
                TgSettingsCard {
                    if (state.authenticated) {
                        TgSettingsRow(
                            title = state.login.ifBlank { "Signed in" },
                            subtitle = "Logged in",
                            trailing = {
                                TextButton(onClick = { vm.logout() }) {
                                    Text("Log out", color = Color(0xFFFF3B30))
                                }
                            }
                        )
                    } else {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Sign in or create an account to use Messenger.",
                                color = tgMuted,
                                fontSize = 14.sp
                            )
                            Button(
                                onClick = onClose,
                                modifier = Modifier.fillMaxWidth(),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = tgBlue)
                            ) { Text("Sign in / Create account") }
                        }
                    }
                }
            }

            item {
                TgSettingsSectionLabel("Connection")
                TgSettingsCard {
                    TgSettingsRow(
                        title = "App version",
                        subtitle = state.appVersion
                    )
                    Divider(Modifier.padding(start = 16.dp), color = tgDivider, thickness = 0.5.dp)
                    TgSettingsRow(
                        title = "API version",
                        subtitle = state.apiVersionText.ifBlank { "…" },
                        trailing = {
                            TextButton(onClick = { vm.refreshApiVersion() }) {
                                Text("Refresh", color = tgBlue)
                            }
                        }
                    )
                    Divider(Modifier.padding(start = 16.dp), color = tgDivider, thickness = 0.5.dp)
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("API base URL", color = tgMuted, fontSize = 13.sp)
                        OutlinedTextField(api, { api = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        TextButton(onClick = { vm.saveSettings(state.settings.copy(apiBaseAddress = api)) }) {
                            Text("Save API", color = tgBlue)
                        }
                    }
                }
            }

            if (state.mappings.isNotEmpty()) {
                item {
                    TgSettingsSectionLabel("Mapped folders")
                    TgSettingsCard {
                        state.mappings.forEachIndexed { index, m ->
                            if (index > 0) {
                                Divider(Modifier.padding(start = 16.dp), color = tgDivider, thickness = 0.5.dp)
                            }
                            Column(Modifier.padding(vertical = 4.dp)) {
                                TgSettingsRow(
                                    title = m.cloudFolderName.ifBlank { m.cloudFolderId.take(8) },
                                    subtitle = m.localPath
                                )
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = { vm.requestForceUpload(m.cloudFolderId) },
                                        enabled = !state.forceRunning
                                    ) { Text("Upload all", color = tgBlue) }
                                    TextButton(
                                        onClick = { vm.requestForceDownload(m.cloudFolderId) },
                                        enabled = !state.forceRunning
                                    ) { Text("Download all", color = tgBlue) }
                                    TextButton(onClick = { vm.unmapFolder(m.cloudFolderId) }) {
                                        Text("Unmap", color = Color(0xFFFF3B30))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TgSettingsSectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Color(0xFF8E8E93),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun TgSettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp)
            .background(Color.White)
    ) {
        content()
    }
}

@Composable
private fun TgSettingsRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 17.sp, color = Color(0xFF000000))
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    color = Color(0xFF8E8E93),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke()
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
    var showCreateFolder by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf("") }
    val folderName = state.breadcrumb.lastOrNull()?.second ?: "Cloud"
    val igSurface = Color(0xFFFAFAFA)
    val igBorder = Color(0xFFDBDBDB)
    val igAccent = Color(0xFF262626)
    val mappedIds = remember(state.mappings) { state.mappings.map { it.cloudFolderId }.toSet() }
    val canGoUp = state.breadcrumb.size > 1

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canGoUp) {
                IconButton(onClick = { vm.cloudNavigateTo(state.breadcrumb.lastIndex - 1) }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Up", tint = igAccent)
                }
            }
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF0F0F0))
                    .border(1.5.dp, igBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    folderName.take(1).uppercase(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = igAccent
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    folderName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = igAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${state.cloudItems.size} items · ${state.mappings.size} mapped",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8E8E8E)
                )
            }
            IconButton(onClick = { state.breadcrumb.lastOrNull()?.first?.let { vm.loadCloudFolder(it) } }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = igAccent)
            }
            IconButton(onClick = { vm.syncNow() }, enabled = !state.syncing) {
                Icon(
                    Icons.Default.Sync,
                    contentDescription = "Sync",
                    tint = if (state.syncing) Color(0xFF8E8E8E) else igAccent
                )
            }
            IconButton(onClick = { showCreateFolder = true }) {
                Icon(Icons.Default.Add, contentDescription = "New", tint = igAccent)
            }
        }

        Divider(color = igBorder, thickness = 0.5.dp)

        if (state.mappings.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(state.mappings, key = { it.cloudFolderId }) { m ->
                    val label = m.cloudFolderName.ifBlank { m.cloudFolderId.take(6) }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(72.dp)
                            .clickable {
                                vm.openMappedFolder(
                                    m.cloudFolderId,
                                    m.cloudFolderName.ifBlank { m.cloudFolderId.take(8) }
                                )
                            }
                    ) {
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                        listOf(Color(0xFFF58529), Color(0xFFDD2A7B), Color(0xFF8134AF))
                                    )
                                )
                                .padding(2.5.dp)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .padding(2.dp)
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .background(igSurface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label.take(1).uppercase(),
                                        fontWeight = FontWeight.Bold,
                                        color = igAccent
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 11.sp,
                            color = igAccent,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            Divider(color = igBorder, thickness = 0.5.dp)
        }

        if (state.breadcrumb.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(state.breadcrumb.size) { i ->
                    val crumb = state.breadcrumb[i]
                    Text(
                        crumb.second,
                        fontSize = 13.sp,
                        fontWeight = if (i == state.breadcrumb.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (i == state.breadcrumb.lastIndex) igAccent else Color(0xFF8E8E8E),
                        modifier = Modifier.clickable { vm.cloudNavigateTo(i) }
                    )
                    if (i < state.breadcrumb.lastIndex) {
                        Text("  ›  ", fontSize = 13.sp, color = Color(0xFF8E8E8E))
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = { pickTree.launch(null) }) {
                Icon(Icons.Default.Link, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Map folder")
            }
            val currentId = state.breadcrumb.lastOrNull()?.first
            if (currentId != null && mappedIds.contains(currentId)) {
                TextButton(onClick = { vm.unmapFolder(currentId) }) {
                    Text("Unmap")
                }
            }
        }

        if (state.cloudItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Cloud, null, Modifier.size(48.dp), tint = Color(0xFFDBDBDB))
                    Spacer(Modifier.height(8.dp))
                    Text("No items here", color = Color(0xFF8E8E8E))
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(1.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                gridItems(state.cloudItems, key = { it.id }) { item ->
                    val mapped = mappedIds.contains(item.id)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.openCloudFolder(item) }
                            .background(Color.White)
                            .padding(bottom = 8.dp)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .background(igSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (item.isFolder) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = if (item.isFolder) Color(0xFF0095F6) else Color(0xFF8E8E8E)
                            )
                            if (mapped) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF0095F6))
                                )
                            }
                        }
                        Text(
                            item.name,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp,
                            color = igAccent,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }

    if (showCreateFolder) {
        AlertDialog(
            onDismissRequest = { showCreateFolder = false },
            icon = { Icon(Icons.Default.CreateNewFolder, null) },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = newFolder,
                    onValueChange = { newFolder = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newFolder.isNotBlank()) {
                            vm.createCloudFolder(newFolder.trim())
                            newFolder = ""
                            showCreateFolder = false
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolder = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun MessengerScreen(vm: MainViewModel, state: UiState, compactWidth: Boolean = true) {
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(state.composerClearNonce) {
        if (state.composerClearNonce > 0) draft = ""
    }
    LaunchedEffect(state.composerDraftRestore) {
        val restore = state.composerDraftRestore ?: return@LaunchedEffect
        draft = restore
        vm.consumeDraftRestore()
    }
    var stickyDate by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    if (!state.authenticated) {
        Text("Please log in in Settings.", modifier = Modifier.padding(16.dp))
        return
    }

    val inChat = state.selectedContact != null
    if (compactWidth && inChat) {
        BackHandler { vm.clearSelectedContact() }
    }

    val chatRows = remember(state.messages) {
        buildChatRows(state.messages)
    }

    LaunchedEffect(listState, chatRows) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
        }.collect { first ->
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

    LaunchedEffect(chatRows.size, state.selectedContact?.userId) {
        if (chatRows.isNotEmpty()) {
            listState.scrollToItem(chatRows.lastIndex)
        }
    }

    // Compact (Telegram): contacts XOR chat. Wide: side-by-side.
    val showContacts = !compactWidth || !inChat
    val showChat = !compactWidth || inChat

    if (compactWidth) {
        if (showContacts) {
            MessengerContactsPane(
                contacts = state.contacts,
                selectedId = null,
                onRefresh = { vm.loadContacts() },
                onSelect = { vm.selectContact(it) },
                modifier = Modifier.fillMaxSize()
            )
        } else if (showChat) {
            MessengerChatPane(
                contact = state.selectedContact,
                chatRows = chatRows,
                listState = listState,
                stickyDate = stickyDate,
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    if (draft.isNotBlank()) {
                        vm.sendMessage(draft)
                    }
                },
                onBack = { vm.clearSelectedContact() },
                showBack = true,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            MessengerContactsPane(
                contacts = state.contacts,
                selectedId = state.selectedContact?.userId,
                onRefresh = { vm.loadContacts() },
                onSelect = { vm.selectContact(it) },
                modifier = Modifier.weight(0.38f)
            )
            MessengerChatPane(
                contact = state.selectedContact,
                chatRows = chatRows,
                listState = listState,
                stickyDate = stickyDate,
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    if (draft.isNotBlank()) {
                        vm.sendMessage(draft)
                    }
                },
                onBack = { vm.clearSelectedContact() },
                showBack = false,
                modifier = Modifier.weight(0.62f)
            )
        }
    }
}

private val TgChatBg = Color(0xFFECE5DD)
private val TgHeaderBg = Color(0xFF527998)
private val TgMineBubble = Color(0xFFDCF8C6)
private val TgPeerBubble = Color(0xFFFFFFFF)
private val TgAccent = Color(0xFF2AABEE)
private val TgTickRead = Color(0xFF53BDEB)
private val TgTickSent = Color(0xFF667781)

@Composable
private fun MessengerContactsPane(
    contacts: List<ContactItem>,
    selectedId: String?,
    onRefresh: () -> Unit,
    onSelect: (ContactItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        Divider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(contacts, key = { it.userId }) { c ->
                val selected = selectedId != null && selectedId.equals(c.userId, true)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) Color(0x142AABEE) else Color.Transparent)
                        .clickable { onSelect(c) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ContactAvatar(c.displayName)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            c.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        Text(
                            "Tap to open chat",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (c.hasUnread) {
                        Box(
                            modifier = Modifier
                                .background(TgAccent, RoundedCornerShape(percent = 50))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                c.unreadLabel,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Divider(modifier = Modifier.padding(start = 68.dp), color = Color(0x14000000))
            }
        }
    }
}

@Composable
private fun ContactAvatar(name: String, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val colors = listOf(
        Color(0xFF5B8DEE), Color(0xFF4FAE4E), Color(0xFFE17055),
        Color(0xFF9B59B6), Color(0xFF16A085), Color(0xFFF39C12)
    )
    val bg = colors[(name.hashCode().and(0x7fffffff)) % colors.size]
    Box(
        modifier = Modifier
            .size(size)
            .background(bg, RoundedCornerShape(percent = 50)),
        contentAlignment = Alignment.Center
    ) {
        Text(initial, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MessengerChatPane(
    contact: ContactItem?,
    chatRows: List<ChatRow>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    stickyDate: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().background(TgChatBg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(TgHeaderBg)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            if (contact != null) {
                ContactAvatar(contact.displayName, size = 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        contact.displayName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                    Text(
                        if (showBack) "back to chats" else "online",
                        color = Color(0xCCFFFFFF),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            } else {
                Text(
                    "Select a chat",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
            }
        }

        if (contact == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Select a contact to start messaging", color = Color(0xFF667781))
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp),
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

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onSend,
                    shape = RoundedCornerShape(50),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = TgAccent)
                ) {
                    Text("Send")
                }
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
            color = Color(0xFF54656F),
            modifier = Modifier
                .background(Color(0x99FFFFFF), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun MessageBubble(m: MessageItem) {
    val bubbleColor = if (m.mine) TgMineBubble else TgPeerBubble
    val align = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart
    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .padding(horizontal = 4.dp)
                .padding(
                    start = if (m.mine) 40.dp else 0.dp,
                    end = if (m.mine) 0.dp else 40.dp
                )
        ) {
            Column(
                modifier = Modifier
                    .align(if (m.mine) Alignment.End else Alignment.Start)
            ) {
                Column(
                    modifier = Modifier
                        .background(bubbleColor, shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(m.text, color = Color(0xFF111111))
                    if (m.timeLabel.isNotBlank() || m.mine) {
                        Row(
                            modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (m.timeLabel.isNotBlank()) {
                                Text(
                                    m.timeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF667781)
                                )
                            }
                            if (m.mine) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    m.ticksText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (m.ticksRead) TgTickRead else TgTickSent
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotesScreen(vm: MainViewModel, state: UiState, compactWidth: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val editing = state.selectedNotePath != null
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

    if (compactWidth && editing) {
        BackHandler {
            flushNoteAndLeave(webViewRef, scope, vm)
        }
    }

    if (state.notesReloadPrompt) {
        AlertDialog(
            onDismissRequest = { vm.dismissNotesReloadPrompt() },
            title = { Text("Note changed on disk") },
            text = {
                Text("This note was updated while you have unsaved edits. Reload from disk or keep your edits?")
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmNotesReloadFromDisk() }) { Text("Reload") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissNotesReloadPrompt() }) { Text("Keep edits") }
            }
        )
    }

    if (state.pendingCreateNote != null) {
        NewNoteDialog(vm = vm, pending = state.pendingCreateNote!!)
    }

    if (state.pendingDeleteNote != null) {
        val pending = state.pendingDeleteNote!!
        val body = if (pending.hasChildren) {
            "Delete “${pending.name}” and all notes inside? This cannot be undone."
        } else {
            "Delete “${pending.name}”? This cannot be undone."
        }
        AlertDialog(
            onDismissRequest = { vm.dismissDeleteNote() },
            title = { Text("Delete note") },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = { vm.confirmDeleteNote() },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissDeleteNote() }) { Text("Cancel") }
            }
        )
    }

    val showTree = !compactWidth || !editing
    val showEditor = editing

    if (compactWidth) {
        if (showTree) {
            NotesTreePane(
                rows = state.notesTreeRows,
                selectedId = state.selectedNoteDocumentId,
                hasRoot = !state.settings.notesRootUri.isNullOrBlank(),
                onPickRoot = { pickTree.launch(null) },
                onRefresh = { vm.refreshNotesTree() },
                onToggleExpand = { vm.toggleNotesExpand(it) },
                onOpen = { vm.selectNotesNode(it) },
                onCreateNote = { vm.requestCreateNote() },
                onCreateNoteHere = { vm.requestCreateNoteHere(it) },
                onDeleteNote = { vm.requestDeleteNote(it) },
                modifier = Modifier.fillMaxSize()
            )
        } else if (showEditor) {
            NotesEditorPane(
                title = state.selectedNotePath.orEmpty(),
                content = state.notesContent,
                documentId = state.selectedNoteDocumentId,
                showBack = true,
                onBack = { web, sc -> flushNoteAndLeave(web, sc, vm) },
                onScheduleSave = { html -> vm.scheduleSaveSelectedNote(html) },
                onNewNote = { vm.requestCreateNoteFromEditor() },
                onDeleteNote = { vm.requestDeleteCurrentNote() },
                webViewRef = { webViewRef = it },
                getWebView = { webViewRef },
                scope = scope,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            NotesTreePane(
                rows = state.notesTreeRows,
                selectedId = state.selectedNoteDocumentId,
                hasRoot = !state.settings.notesRootUri.isNullOrBlank(),
                onPickRoot = { pickTree.launch(null) },
                onRefresh = { vm.refreshNotesTree() },
                onToggleExpand = { vm.toggleNotesExpand(it) },
                onOpen = { vm.selectNotesNode(it) },
                onCreateNote = { vm.requestCreateNote() },
                onCreateNoteHere = { vm.requestCreateNoteHere(it) },
                onDeleteNote = { vm.requestDeleteNote(it) },
                modifier = Modifier.weight(0.38f)
            )
            if (showEditor) {
                NotesEditorPane(
                    title = state.selectedNotePath.orEmpty(),
                    content = state.notesContent,
                    documentId = state.selectedNoteDocumentId,
                    showBack = false,
                    onBack = { web, sc -> flushNoteAndLeave(web, sc, vm) },
                    onScheduleSave = { html -> vm.scheduleSaveSelectedNote(html) },
                    onNewNote = { vm.requestCreateNoteFromEditor() },
                    onDeleteNote = { vm.requestDeleteCurrentNote() },
                    webViewRef = { webViewRef = it },
                    getWebView = { webViewRef },
                    scope = scope,
                    modifier = Modifier.weight(0.62f)
                )
            } else {
                Box(
                    Modifier.weight(0.62f).fillMaxSize().background(Color(0xFFF0F2F5)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Select a note", color = Color(0xFF667781))
                }
            }
        }
    }
}

@Composable
private fun NewNoteDialog(vm: MainViewModel, pending: PendingCreateNote) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    AlertDialog(
        onDismissRequest = { vm.dismissCreateNote() },
        title = { Text("New note") },
        text = {
            Column {
                if (pending.fromEditor) {
                    Text("Where to create:", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.setCreateNoteInsideCurrent(false) }
                    ) {
                        RadioButton(
                            selected = !pending.insideCurrent,
                            onClick = { vm.setCreateNoteInsideCurrent(false) }
                        )
                        Text("Same section", modifier = Modifier.padding(start = 4.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.setCreateNoteInsideCurrent(true) }
                    ) {
                        RadioButton(
                            selected = pending.insideCurrent,
                            onClick = { vm.setCreateNoteInsideCurrent(true) }
                        )
                        Text("Inside this note", modifier = Modifier.padding(start = 4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "Section: ${pending.sectionLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF667781)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pending.titleDraft,
                    onValueChange = { vm.updateCreateNoteTitle(it) },
                    singleLine = true,
                    label = { Text("Title") },
                    isError = pending.titleError != null,
                    supportingText = pending.titleError?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onKeyEvent { ev ->
                            if (ev.key == Key.Enter) {
                                vm.confirmCreateNote()
                                true
                            } else false
                        }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.confirmCreateNote() }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissCreateNote() }) { Text("Cancel") }
        }
    )
}

private val NotesHeaderBg = Color(0xFF527998)
private val NotesFolderIcon = Color(0xFFFFC107)
private val NotesFileIcon = Color(0xFF64B5F6)

@Composable
private fun NotesTreePane(
    rows: List<NoteTreeRow>,
    selectedId: String?,
    hasRoot: Boolean,
    onPickRoot: () -> Unit,
    onRefresh: () -> Unit,
    onToggleExpand: (String) -> Unit,
    onOpen: (NoteTreeRow) -> Unit,
    onCreateNote: () -> Unit,
    onCreateNoteHere: (NoteTreeRow) -> Unit,
    onDeleteNote: (NoteTreeRow) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Notes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onPickRoot) { Text(if (hasRoot) "Folder" else "Choose") }
                TextButton(onClick = onRefresh) { Text("Refresh") }
                if (hasRoot && LocalConfiguration.current.screenWidthDp >= 600) {
                    TextButton(onClick = onCreateNote) { Text("New note") }
                }
            }
            Divider()

            if (!hasRoot) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No notes folder", color = Color(0xFF667781))
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onPickRoot) { Text("Choose notes folder") }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(rows, key = { it.documentId }) { row ->
                        NotesTreeItem(
                            row = row,
                            selected = row.documentId == selectedId,
                            onToggleExpand = { onToggleExpand(row.documentId) },
                            onOpen = { onOpen(row) },
                            onLongCreateHere = { onCreateNoteHere(row) },
                            onDelete = { onDeleteNote(row) }
                        )
                        Divider(
                            modifier = Modifier.padding(start = (12 + row.depth * 20 + 28 + 40).dp),
                            color = Color(0x14000000)
                        )
                    }
                }
            }
        }
        if (hasRoot) {
            FloatingActionButton(
                onClick = onCreateNote,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = NotesHeaderBg,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "New note")
            }
        }
    }
}

/**
 * Telegram-style tree row: same-level icons share one vertical line.
 * [indent][chevron 28dp][type icon 40dp][title]
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotesTreeItem(
    row: NoteTreeRow,
    selected: Boolean,
    onToggleExpand: () -> Unit,
    onOpen: () -> Unit,
    onLongCreateHere: () -> Unit,
    onDelete: () -> Unit
) {
    val indent = (row.depth * 20).dp
    var showMenu by remember { mutableStateOf(false) }
    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0x142AABEE) else Color.Transparent)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { showMenu = true }
            )
            .padding(start = 12.dp + indent, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Fixed chevron column — keeps folder/file icons aligned at each depth.
        Box(
            modifier = Modifier
                .size(28.dp)
                .clickable(enabled = row.hasChildren, onClick = onToggleExpand),
            contentAlignment = Alignment.Center
        ) {
            if (row.hasChildren) {
                Icon(
                    imageVector = if (row.expanded) Icons.Default.ExpandMore else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (row.expanded) "Collapse" else "Expand",
                    tint = Color(0xFF8E8E93),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Fixed type-icon column — folder and page icons share the same X at this depth.
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (row.hasChildren) Color(0x1AFFC107) else Color(0x1A2AABEE),
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (row.hasChildren) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                tint = if (row.hasChildren) NotesFolderIcon else NotesFileIcon,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                if (row.hasChildren) {
                    if (row.expanded) "Folder · expanded" else "Folder · tap to open"
                } else {
                    "Note"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8E8E93),
                maxLines = 1
            )
        }
    }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("New note here") },
                onClick = {
                    showMenu = false
                    onLongCreateHere()
                }
            )
            if (row.relativePath.replace('\\', '/').trim('/').isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun NotesEditorPane(
    title: String,
    content: String,
    documentId: String?,
    showBack: Boolean,
    onBack: (WebView?, kotlinx.coroutines.CoroutineScope) -> Unit,
    onScheduleSave: (String) -> Unit,
    onNewNote: () -> Unit,
    onDeleteNote: () -> Unit,
    webViewRef: (WebView?) -> Unit,
    getWebView: () -> WebView?,
    scope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selBold by remember { mutableStateOf(false) }
    var selItalic by remember { mutableStateOf(false) }
    var selUnderline by remember { mutableStateOf(false) }
    var selStrike by remember { mutableStateOf(false) }
    var selCheckbox by remember { mutableStateOf(false) }
    var selBlock by remember { mutableStateOf("p") }
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkUrl by remember { mutableStateOf("https://") }
    var showHeadingMenu by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }

    fun runJs(js: String) {
        getWebView()?.evaluateJavascript(js, null)
    }

    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text("Insert link") },
            text = {
                OutlinedTextField(
                    value = linkUrl,
                    onValueChange = { linkUrl = it },
                    singleLine = true,
                    label = { Text("URL") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = linkUrl.trim()
                    showLinkDialog = false
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        runJs(notesEditorInsertLinkJs(url))
                    }
                }) { Text("Insert") }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showHeadingMenu) {
        AlertDialog(
            onDismissRequest = { showHeadingMenu = false },
            title = { Text("Paragraph style") },
            text = {
                Column {
                    listOf(
                        "p" to "Normal",
                        "h1" to "Heading 1",
                        "h2" to "Heading 2",
                        "h3" to "Heading 3"
                    ).forEach { (tag, label) ->
                        TextButton(
                            onClick = {
                                showHeadingMenu = false
                                runJs(notesEditorApplyJs(tag))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showHeadingMenu = false }) { Text("Close") }
            }
        )
    }

    Column(modifier.fillMaxSize().background(Color.White).imePadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(NotesHeaderBg)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBack) {
                IconButton(onClick = { onBack(getWebView(), scope) }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title.ifBlank { "Note" },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { showOverflow = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                }
                DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                    DropdownMenuItem(
                        text = { Text("New note…") },
                        onClick = {
                            showOverflow = false
                            onNewNote()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete note", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showOverflow = false
                            onDeleteNote()
                        }
                    )
                }
            }
        }

        // Format bar above the editor (not under IME) — mobile UX preference.
        NotesFormatBar(
            bold = selBold,
            italic = selItalic,
            underline = selUnderline,
            strike = selStrike,
            checkbox = selCheckbox,
            block = selBlock,
            onBold = { runJs(notesEditorApplyJs("bold")) },
            onItalic = { runJs(notesEditorApplyJs("italic")) },
            onUnderline = { runJs(notesEditorApplyJs("underline")) },
            onStrike = { runJs(notesEditorApplyJs("strikeThrough")) },
            onHeading = { showHeadingMenu = true },
            onBullet = { runJs(notesEditorApplyJs("insertUnorderedList")) },
            onNumbered = { runJs(notesEditorApplyJs("insertOrderedList")) },
            onCheckbox = { runJs(notesEditorApplyJs("checkboxList")) },
            onOutdent = { runJs(notesEditorApplyJs("outdent")) },
            onIndent = { runJs(notesEditorApplyJs("indent")) },
            onLink = {
                linkUrl = "https://"
                showLinkDialog = true
            }
        )

        key(documentId) {
            var loadedSig by remember(documentId) { mutableStateOf<Int?>(null) }
            val scheduleState = rememberUpdatedState(onScheduleSave)
            val contentState = rememberUpdatedState(content)
            val bridge = remember(documentId) {
                NotesEditorBridge(
                    onDirty = {
                        val web = getWebView() ?: return@NotesEditorBridge
                        web.evaluateJavascript(NOTES_GET_HTML_JS) { value ->
                            val html = decodeEvaluateJavascriptString(value).ifBlank { contentState.value }
                            scheduleState.value(html)
                        }
                    },
                    onSelectionState = { bold, italic, underline, strike, checkbox, block ->
                        selBold = bold
                        selItalic = italic
                        selUnderline = underline
                        selStrike = strike
                        selCheckbox = checkbox
                        selBlock = block
                    },
                    onRequestLink = {
                        linkUrl = "https://"
                        showLinkDialog = true
                    },
                    onOpenLink = { url ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        }
                    }
                )
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                        addJavascriptInterface(bridge, "ProtoLinkNotes")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.evaluateJavascript(FOCUS_SCROLL_JS, null)
                            }
                        }
                        webViewRef(this)
                    }
                },
                update = { web ->
                    webViewRef(web)
                    val sig = content.length xor content.hashCode()
                    if (loadedSig != sig) {
                        loadedSig = sig
                        val editable = NotesEditorHtml.wrap(context, content)
                        val encoded = android.util.Base64.encodeToString(
                            editable.toByteArray(Charsets.UTF_8),
                            android.util.Base64.NO_WRAP
                        )
                        web.loadData(encoded, "text/html; charset=utf-8", "base64")
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            )
        }
    }
}

@Composable
private fun NotesFormatBar(
    bold: Boolean,
    italic: Boolean,
    underline: Boolean,
    strike: Boolean,
    checkbox: Boolean,
    block: String,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onUnderline: () -> Unit,
    onStrike: () -> Unit,
    onHeading: () -> Unit,
    onBullet: () -> Unit,
    onNumbered: () -> Unit,
    onCheckbox: () -> Unit,
    onOutdent: () -> Unit,
    onIndent: () -> Unit,
    onLink: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFF7F8FA))
            .border(1.dp, Color(0xFFE0E3E7))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FmtToggle("B", bold, onBold, boldWeight = true)
        FmtToggle("I", italic, onItalic, italicStyle = true)
        FmtToggle("U", underline, onUnderline, underline = true)
        FmtToggle("S", strike, onStrike, strike = true)
        FmtSep()
        FmtToggle(
            when (block) {
                "h1" -> "H1"
                "h2" -> "H2"
                "h3" -> "H3"
                else -> "¶"
            },
            active = block == "h1" || block == "h2" || block == "h3",
            onClick = onHeading
        )
        FmtSep()
        FmtToggle("•", false, onBullet)
        FmtToggle("1.", false, onNumbered)
        FmtToggle("☐", checkbox, onCheckbox)
        FmtToggle("⇤", false, onOutdent)
        FmtToggle("⇥", false, onIndent)
        FmtSep()
        FmtToggle("🔗", false, onLink)
    }
}

@Composable
private fun FmtSep() {
    Spacer(Modifier.width(4.dp))
    Box(
        Modifier
            .width(1.dp)
            .height(28.dp)
            .background(Color(0xFFD0D5DB))
    )
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun FmtToggle(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    boldWeight: Boolean = false,
    italicStyle: Boolean = false,
    underline: Boolean = false,
    strike: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 44.dp)
            .padding(2.dp)
            .background(
                if (active) Color(0x1A2AABEE) else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = if (boldWeight) FontWeight.Bold else FontWeight.Medium,
                fontStyle = if (italicStyle) FontStyle.Italic else FontStyle.Normal,
                textDecoration = when {
                    underline && strike -> TextDecoration.Underline + TextDecoration.LineThrough
                    underline -> TextDecoration.Underline
                    strike -> TextDecoration.LineThrough
                    else -> TextDecoration.None
                }
            ),
            color = Color(0xFF1C1C1E)
        )
    }
}

private fun flushNoteAndLeave(
    web: WebView?,
    scope: kotlinx.coroutines.CoroutineScope,
    vm: MainViewModel
) {
    if (web == null) {
        vm.clearSelectedNote()
        return
    }
    scope.launch {
        val html = suspendCancellableCoroutine { cont ->
            web.evaluateJavascript(NOTES_GET_HTML_JS) { value ->
                cont.resume(decodeEvaluateJavascriptString(value))
            }
        }
        vm.saveSelectedNoteNow(html.ifBlank { vm.state.value.notesContent }, clearAfter = true)
    }
}

/** Keep caret visible above the soft keyboard inside the contenteditable WebView. */
private const val FOCUS_SCROLL_JS = """
(function(){
  if (window.__plFocusScroll) return;
  window.__plFocusScroll = true;
  function scrollCaret(){
    try {
      var sel = window.getSelection && window.getSelection();
      if (!sel || sel.rangeCount === 0) return;
      var r = sel.getRangeAt(0).cloneRange();
      if (r.collapsed) {
        var probe = document.createElement('span');
        probe.appendChild(document.createTextNode('\u200b'));
        r.insertNode(probe);
        probe.scrollIntoView({block:'center', inline:'nearest'});
        var p = probe.parentNode;
        if (p) p.removeChild(probe);
        sel.removeAllRanges();
        sel.addRange(r);
      } else {
        var rect = r.getBoundingClientRect();
        if (rect && (rect.bottom > (window.innerHeight - 24) || rect.top < 24)) {
          var el = sel.focusNode && sel.focusNode.nodeType === 1 ? sel.focusNode : (sel.focusNode && sel.focusNode.parentElement);
          if (el && el.scrollIntoView) el.scrollIntoView({block:'center', inline:'nearest'});
        }
      }
    } catch(e) {}
  }
  document.addEventListener('focusin', scrollCaret, true);
  document.addEventListener('keyup', scrollCaret, true);
  document.addEventListener('input', scrollCaret, true);
  window.addEventListener('resize', scrollCaret);
})();
"""

private class NotesEditorBridge(
    private val onDirty: () -> Unit,
    private val onSelectionState: (Boolean, Boolean, Boolean, Boolean, Boolean, String) -> Unit,
    private val onRequestLink: () -> Unit,
    private val onOpenLink: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onMessage(json: String?) {
        if (json.isNullOrBlank()) return
        main.post {
            runCatching {
                val obj = org.json.JSONObject(json)
                when (obj.optString("type")) {
                    "contentChanged" -> onDirty()
                    "requestLink" -> onRequestLink()
                    "openLink" -> {
                        val url = obj.optString("url")
                        if (url.startsWith("http://") || url.startsWith("https://")) onOpenLink(url)
                    }
                    "selectionState" -> onSelectionState(
                        obj.optBoolean("bold"),
                        obj.optBoolean("italic"),
                        obj.optBoolean("underline"),
                        obj.optBoolean("strike"),
                        obj.optBoolean("checkbox"),
                        obj.optString("block", "p")
                    )
                }
            }
        }
    }

    /** Backward-compatible alias if older injected scripts call markDirty. */
    @JavascriptInterface
    fun markDirty() {
        main.post { onDirty() }
    }
}
