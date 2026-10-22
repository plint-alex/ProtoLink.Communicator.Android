package ru.protolink.communicator.ui.messenger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.api.AddEntityRequest
import ru.protolink.communicator.data.api.GetEntitiesRequest
import ru.protolink.communicator.data.api.ProtoLinkApi
import ru.protolink.communicator.util.EntityMeta
import ru.protolink.communicator.util.MessengerAttachmentRender
import ru.protolink.communicator.util.MessengerCloudShareability

data class CloudPickerSelection(
    val entityId: String,
    val name: String,
    val mimeType: String?
)

private data class CloudPickerRow(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val mimeType: String?,
    val updateTime: String?,
    val subtitle: String,
    val canSelect: Boolean
)

/**
 * M3 — navigable Cloud entity picker (not an OS file dialog). Pattern-1 rows.
 */
@Composable
fun CloudPickerDialog(
    api: ProtoLinkApi,
    myUserId: String,
    recipientUserId: String,
    lang: String?,
    onDismiss: () -> Unit,
    onPick: (CloudPickerSelection) -> Unit
) {
    val scope = rememberCoroutineScope()
    var stack by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var items by remember { mutableStateOf<List<CloudPickerRow>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf<String?>(null) }
    val cantShare = MessengerCloudShareability.disabledShareSubtitle(lang)

    suspend fun loadFolder(folderId: String) {
        loading = true
        statusText = null
        try {
            val list = api.getEntities(
                GetEntitiesRequest(
                    parentIds = listOf(folderId),
                    includeValues = true,
                    take = MessengerCloudShareability.PickerPageSize
                )
            )
            val rows = list.mapNotNull { e ->
                val code = e.code.orEmpty()
                val isFolder =
                    code.equals(CloudCodes.CLOUD_FOLDER, true) ||
                        code.equals(CloudCodes.CLOUD_ROOT, true)
                val isFile = code.equals(CloudCodes.CLOUD_FILE, true)
                if (!isFolder && !isFile) return@mapNotNull null
                val mime = EntityMeta.mimeType(e)
                val name = EntityMeta.displayName(e)
                val subtitle = when {
                    isFolder -> "Folder"
                    MessengerAttachmentRender.looksLikeImageMime(mime) -> "File · image"
                    else -> "File"
                }
                CloudPickerRow(
                    id = e.id,
                    name = name,
                    isFolder = isFolder,
                    mimeType = mime,
                    updateTime = e.updateTime,
                    subtitle = subtitle,
                    canSelect = !isFolder
                )
            }.sortedWith(
                compareByDescending<CloudPickerRow> { it.isFolder }
                    .thenByDescending { it.updateTime.orEmpty() }
                    .thenBy { it.name.lowercase() }
            )

            val files = rows.filter { !it.isFolder }
            val assessed = coroutineScope {
                files.map { row ->
                    async {
                        try {
                            val perms = api.getPermissions(row.id)
                            val ok = MessengerCloudShareability.canSelectFromPermissionRows(
                                perms,
                                myUserId,
                                recipientUserId
                            )
                            if (ok) row else row.copy(canSelect = false, subtitle = cantShare)
                        } catch (_: Exception) {
                            row.copy(canSelect = false, subtitle = cantShare)
                        }
                    }
                }.awaitAll()
            }
            val byId = assessed.associateBy { it.id.lowercase() }
            items = rows.map { r ->
                if (r.isFolder) r
                else byId[r.id.lowercase()] ?: r.copy(canSelect = false, subtitle = cantShare)
            }
        } catch (_: Exception) {
            items = emptyList()
            statusText = "Waiting for network…"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(myUserId) {
        loading = true
        try {
            val underUser = api.getEntities(
                GetEntitiesRequest(parentIds = listOf(myUserId), includeValues = false, take = 200)
            )
            var rootId = underUser.firstOrNull {
                it.code.equals(CloudCodes.CLOUD_ROOT, true)
            }?.id
            if (rootId.isNullOrBlank()) {
                rootId = api.addEntity(
                    AddEntityRequest(code = CloudCodes.CLOUD_ROOT, parentIds = listOf(myUserId))
                ).id
            }
            if (rootId.isNullOrBlank()) {
                statusText = "Waiting for network…"
                loading = false
                return@LaunchedEffect
            }
            stack = listOf(rootId to "Cloud")
            loadFolder(rootId)
        } catch (_: Exception) {
            statusText = "Waiting for network…"
            loading = false
        }
    }

    fun activate(row: CloudPickerRow) {
        if (row.isFolder) {
            stack = stack + (row.id to row.name)
            scope.launch { loadFolder(row.id) }
            return
        }
        if (!row.canSelect) return
        onPick(
            CloudPickerSelection(
                entityId = row.id,
                name = row.name,
                mimeType = row.mimeType
            )
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.medium,
            color = Color.White
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (stack.size > 1) {
                        IconButton(onClick = {
                            stack = stack.dropLast(1)
                            val folderId = stack.last().first
                            scope.launch { loadFolder(folderId) }
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    } else {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp),
                            tint = Color(0xFF0088CC)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Choose from Cloud",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stack.joinToString(" › ") { it.second },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF667781),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Divider()
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    placeholder = { Text("Filter in folder") },
                    singleLine = true
                )
                statusText?.let { msg ->
                    Text(
                        msg,
                        color = Color(0xFFB00020),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else {
                        val q = filter.trim().lowercase()
                        val visible = if (q.isEmpty()) {
                            items
                        } else {
                            items.filter { it.name.lowercase().contains(q) }
                        }
                        LazyColumn {
                            items(visible, key = { it.id }) { row ->
                                val enabled = row.isFolder || row.canSelect
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .alpha(if (enabled) 1f else 0.45f)
                                        .then(
                                            if (enabled) Modifier.clickable { activate(row) }
                                            else Modifier
                                        )
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (row.isFolder) Icons.Default.Folder
                                        else Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        tint = if (row.isFolder) {
                                            Color(0xFFFFC107)
                                        } else {
                                            Color(0xFF667781)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            row.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            row.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF667781),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Divider(color = Color(0xFFECEFF1))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
