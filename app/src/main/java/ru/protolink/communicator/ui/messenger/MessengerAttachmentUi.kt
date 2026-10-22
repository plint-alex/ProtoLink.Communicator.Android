package ru.protolink.communicator.ui.messenger

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.api.GetEntitiesRequest
import ru.protolink.communicator.data.api.ProtoLinkApi
import ru.protolink.communicator.util.EntityLinkUnfurl
import ru.protolink.communicator.util.EntityMeta
import ru.protolink.communicator.util.MessengerAttachmentRender
import ru.protolink.communicator.util.MessengerAttachmentVisualKind
import ru.protolink.communicator.util.MessengerMediaLimits
import ru.protolink.communicator.util.MessengerThumbMemoryCache
import ru.protolink.communicator.util.MessengerUnfurlCache
import java.util.Locale

private val TgAccent = Color(0xFF2AABEE)
private val MetaMuted = Color(0xFF667781)
private val TimeBrushBorder = Color(0x4D667781)

data class AttachmentUiState(
    val entityId: String,
    val kind: MessengerAttachmentVisualKind = MessengerAttachmentVisualKind.Loading,
    val fileName: String = "…",
    val mimeType: String? = null,
    val byteLength: Long? = null,
    val thumb: Bitmap? = null
)

data class UnfurlUiState(
    val entityId: String,
    val sourceUrl: String,
    val title: String,
    val typeName: String,
    val thumb: Bitmap? = null
)

@Composable
fun MessageAttachmentsColumn(
    attachmentIds: List<String>,
    api: ProtoLinkApi,
    multiImageMaxHeight: Boolean,
    modifier: Modifier = Modifier
) {
    if (attachmentIds.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        attachmentIds.forEach { id ->
            AttachmentBlock(
                entityId = id,
                api = api,
                multiImageMaxHeight = multiImageMaxHeight,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

@Composable
fun MessageUnfurlCards(
    bodyText: String,
    publicSiteBaseUrl: String,
    api: ProtoLinkApi,
    modifier: Modifier = Modifier
) {
    val ids = remember(bodyText, publicSiteBaseUrl) {
        EntityLinkUnfurl.findEntityIds(bodyText, publicSiteBaseUrl)
    }
    if (ids.isEmpty()) return

    var cards by remember(bodyText, publicSiteBaseUrl) { mutableStateOf<List<UnfurlUiState>>(emptyList()) }
    val context = LocalContext.current

    LaunchedEffect(ids, publicSiteBaseUrl) {
        val loaded = mutableListOf<UnfurlUiState>()
        for (id in ids) {
            val cached = MessengerUnfurlCache.get(id)
            if (cached != null) {
                if (cached.allowed) {
                    loaded.add(
                        UnfurlUiState(
                            entityId = id,
                            sourceUrl = EntityLinkUnfurl.buildEntityUrl(publicSiteBaseUrl, id),
                            title = cached.title,
                            typeName = cached.typeName,
                            thumb = cached.thumb
                        )
                    )
                }
                continue
            }
            val card = loadUnfurlCard(api, id, publicSiteBaseUrl)
            if (card != null) loaded.add(card)
        }
        cards = loaded
    }

    if (cards.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().padding(top = 6.dp)) {
        cards.forEach { card ->
            UnfurlCardRow(
                card = card,
                onOpen = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(card.sourceUrl))
                    context.startActivity(intent)
                }
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun UnfurlCardRow(card: UnfurlUiState, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, TimeBrushBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (card.thumb != null) {
            Image(
                bitmap = card.thumb.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFFE8E8E8), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = MetaMuted)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                card.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                color = Color(0xFF111111)
            )
            Text(
                card.typeName,
                style = MaterialTheme.typography.labelSmall,
                color = MetaMuted,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AttachmentBlock(
    entityId: String,
    api: ProtoLinkApi,
    multiImageMaxHeight: Boolean,
    modifier: Modifier = Modifier
) {
    var state by remember(entityId) {
        mutableStateOf(AttachmentUiState(entityId = entityId))
    }
    var lightboxOpen by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRu = remember { Locale.getDefault().language.equals("ru", ignoreCase = true) }

    LaunchedEffect(entityId) {
        state = loadRemoteAttachment(api, entityId, density.density)
    }

    when (state.kind) {
        MessengerAttachmentVisualKind.Loading -> {
            Box(
                modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Color(0x22000000), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = TgAccent, strokeWidth = 2.dp)
            }
        }
        MessengerAttachmentVisualKind.Image -> {
            val thumb = state.thumb
            if (thumb != null) {
                val maxH = if (multiImageMaxHeight) 280.dp else 420.dp
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = state.fileName,
                    modifier = modifier
                        .fillMaxWidth()
                        .heightIn(max = maxH)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { lightboxOpen = true },
                    contentScale = ContentScale.FillWidth
                )
            }
        }
        MessengerAttachmentVisualKind.File -> {
            FileRow(
                fileName = state.fileName,
                sizeLabel = state.byteLength?.let { MessengerAttachmentRender.formatByteSize(it) },
                onDownload = {
                    scope.launch {
                        val ok = downloadAttachmentToDevice(context, api, state)
                        Toast.makeText(
                            context,
                            if (ok) "Saved: ${state.fileName}" else MessengerAttachmentRender.DOWNLOAD_FAILED_EN,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = modifier
            )
        }
        MessengerAttachmentVisualKind.TombstoneRemoved -> {
            TombstoneRow(
                label = MessengerAttachmentRender.tombstoneRemovedLabel(isRu),
                locked = false,
                modifier = modifier
            )
        }
        MessengerAttachmentVisualKind.TombstoneNoAccess -> {
            TombstoneRow(
                label = MessengerAttachmentRender.tombstoneNoAccessLabel(isRu),
                locked = true,
                modifier = modifier
            )
        }
    }

    if (lightboxOpen && state.kind == MessengerAttachmentVisualKind.Image) {
        LightboxDialog(
            entityId = entityId,
            fileName = state.fileName,
            api = api,
            density = density.density,
            onClose = { lightboxOpen = false },
            onTombstone = { kind ->
                state = state.copy(kind = kind, thumb = null)
                lightboxOpen = false
            }
        )
    }
}

@Composable
private fun FileRow(
    fileName: String,
    sizeLabel: String?,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color(0xFFE8F4FC), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = TgAccent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(fileName, maxLines = 1, color = Color(0xFF111111), style = MaterialTheme.typography.bodyMedium)
            if (!sizeLabel.isNullOrBlank()) {
                Text(sizeLabel, color = MetaMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
        TextButton(onClick = onDownload) {
            Text("Download", color = TgAccent)
        }
    }
}

@Composable
private fun TombstoneRow(label: String, locked: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .border(1.dp, MetaMuted.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (locked) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MetaMuted, modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = MetaMuted.copy(alpha = 0.6f))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = MetaMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LightboxDialog(
    entityId: String,
    fileName: String,
    api: ProtoLinkApi,
    density: Float,
    onClose: () -> Unit,
    onTombstone: (MessengerAttachmentVisualKind) -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(entityId) {
        loading = true
        error = null
        val longSide = kotlin.math.ceil(MessengerMediaLimits.LightboxImageLongSideLogical * density).toInt()
        val result = withContext(Dispatchers.IO) {
            fetchImageSuspend(api, entityId, longSide, longSide)
        }
        when {
            result.status == 403 -> onTombstone(MessengerAttachmentVisualKind.TombstoneNoAccess)
            result.status == 404 -> onTombstone(MessengerAttachmentVisualKind.TombstoneRemoved)
            result.bitmap != null -> bitmap = result.bitmap
            else -> error = MessengerAttachmentRender.DOWNLOAD_FAILED_EN
        }
        loading = false
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xEB000000))
        ) {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
                error != null -> Text(error!!, color = Color.White, modifier = Modifier.align(Alignment.Center))
                bitmap != null -> Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = fileName,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            downloadOriginal(context, api, entityId, fileName)
                        }
                        Toast.makeText(
                            context,
                            if (ok) "Saved: $fileName" else MessengerAttachmentRender.DOWNLOAD_FAILED_EN,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) {
                    Text("Download", color = Color.White)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

private data class ImageFetch(
    val status: Int?,
    val contentType: String?,
    val bitmap: Bitmap?
)

private suspend fun loadRemoteAttachment(
    api: ProtoLinkApi,
    entityId: String,
    density: Float
): AttachmentUiState = withContext(Dispatchers.IO) {
    var fileName = "…"
    var mime: String? = null
    try {
        val entities = api.getEntities(GetEntitiesRequest(ids = listOf(entityId), take = 1, includeValues = true))
        if (entities.isNotEmpty()) {
            val e = entities[0]
            fileName = EntityMeta.displayName(e)
            mime = EntityMeta.mimeType(e)
        } else {
            val probe = api.getFile(entityId)
            return@withContext when (probe.code()) {
                404 -> AttachmentUiState(entityId, MessengerAttachmentVisualKind.TombstoneRemoved, fileName)
                403 -> AttachmentUiState(entityId, MessengerAttachmentVisualKind.TombstoneNoAccess, fileName)
                else -> {
                    probe.body()?.close()
                    AttachmentUiState(
                        entityId = entityId,
                        kind = MessengerAttachmentVisualKind.File,
                        fileName = EntityMeta.displayName(
                            ru.protolink.communicator.data.api.EntityDto(id = entityId, code = CloudCodes.CLOUD_FILE)
                        ),
                        mimeType = "application/octet-stream"
                    )
                }
            }
        }

        val initial = MessengerAttachmentRender.initialKind(mime, forceFileRow = false)
        if (initial == MessengerAttachmentVisualKind.File) {
            val len = contentLengthOrNull(api, entityId)
            return@withContext AttachmentUiState(
                entityId = entityId,
                kind = MessengerAttachmentVisualKind.File,
                fileName = fileName,
                mimeType = mime,
                byteLength = len
            )
        }

        val longSide = kotlin.math.ceil(MessengerMediaLimits.BubbleImageLongSideLogical * density).toInt()
        val cached = MessengerThumbMemoryCache.get(entityId, longSide, longSide)
        if (cached != null) {
            return@withContext AttachmentUiState(
                entityId = entityId,
                kind = MessengerAttachmentVisualKind.Image,
                fileName = fileName,
                mimeType = mime,
                thumb = cached
            )
        }

        val img = fetchImageSuspend(api, entityId, longSide, longSide)
        val kind = MessengerAttachmentRender.afterImageProbe(
            forceFileRow = false,
            httpStatus = img.status,
            contentType = img.contentType,
            decodedAsImage = img.bitmap != null
        )
        when (kind) {
            MessengerAttachmentVisualKind.Image -> {
                img.bitmap?.let { MessengerThumbMemoryCache.put(entityId, longSide, longSide, it) }
                AttachmentUiState(
                    entityId = entityId,
                    kind = MessengerAttachmentVisualKind.Image,
                    fileName = fileName,
                    mimeType = mime,
                    thumb = img.bitmap
                )
            }
            MessengerAttachmentVisualKind.TombstoneRemoved,
            MessengerAttachmentVisualKind.TombstoneNoAccess ->
                AttachmentUiState(entityId, kind, fileName, mime)
            else -> {
                val len = contentLengthOrNull(api, entityId)
                AttachmentUiState(
                    entityId = entityId,
                    kind = MessengerAttachmentVisualKind.File,
                    fileName = fileName,
                    mimeType = mime,
                    byteLength = len
                )
            }
        }
    } catch (_: Exception) {
        AttachmentUiState(
            entityId = entityId,
            kind = MessengerAttachmentVisualKind.File,
            fileName = fileName,
            mimeType = mime
        )
    }
}

private suspend fun loadUnfurlCard(
    api: ProtoLinkApi,
    entityId: String,
    publicSiteBaseUrl: String
): UnfurlUiState? = withContext(Dispatchers.IO) {
    try {
        val entities = api.getEntities(GetEntitiesRequest(ids = listOf(entityId), take = 1, includeValues = true))
        if (entities.isEmpty()) {
            MessengerUnfurlCache.put(entityId, MessengerUnfurlCache.Entry(allowed = false))
            return@withContext null
        }
        val e = entities[0]
        val title = EntityMeta.displayName(e)
        val typeName = EntityMeta.typeLabel(e.code)
        var thumb: Bitmap? = null
        if (e.code == CloudCodes.CLOUD_FILE && MessengerAttachmentRender.looksLikeImageMime(EntityMeta.mimeType(e))) {
            thumb = fetchImageSuspend(api, entityId, 96, 96).bitmap
        }
        MessengerUnfurlCache.put(
            entityId,
            MessengerUnfurlCache.Entry(allowed = true, title = title, typeName = typeName, thumb = thumb)
        )
        UnfurlUiState(
            entityId = entityId,
            sourceUrl = EntityLinkUnfurl.buildEntityUrl(publicSiteBaseUrl, entityId),
            title = title,
            typeName = typeName,
            thumb = thumb
        )
    } catch (_: Exception) {
        MessengerUnfurlCache.put(entityId, MessengerUnfurlCache.Entry(allowed = false))
        null
    }
}

private suspend fun fetchImageSuspend(api: ProtoLinkApi, entityId: String, w: Int, h: Int): ImageFetch {
    return try {
        val resp = api.getImage(entityId, w, h)
        val code = resp.code()
        val ct = resp.headers()["Content-Type"] ?: resp.body()?.contentType()?.toString()
        if (!resp.isSuccessful) {
            resp.body()?.close()
            return ImageFetch(code, ct, null)
        }
        val bytes = resp.body()?.bytes()
        if (bytes == null || bytes.isEmpty()) return ImageFetch(code, ct, null)
        if (!MessengerAttachmentRender.isImageContentType(ct)) return ImageFetch(code, ct, null)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ImageFetch(code, ct, bmp)
    } catch (_: Exception) {
        ImageFetch(null, null, null)
    }
}

private suspend fun contentLengthOrNull(api: ProtoLinkApi, entityId: String): Long? {
    return try {
        val resp = api.getFile(entityId)
        if (!resp.isSuccessful) {
            resp.body()?.close()
            return null
        }
        val body = resp.body() ?: return null
        try {
            val len = body.contentLength()
            if (len > 0L) len else null
        } finally {
            body.close()
        }
    } catch (_: Exception) {
        null
    }
}

private suspend fun downloadAttachmentToDevice(
    context: android.content.Context,
    api: ProtoLinkApi,
    state: AttachmentUiState
): Boolean = downloadOriginal(context, api, state.entityId, state.fileName)

private suspend fun downloadOriginal(
    context: android.content.Context,
    api: ProtoLinkApi,
    entityId: String,
    fileName: String
): Boolean = withContext(Dispatchers.IO) {
    try {
        val resp = api.getFile(entityId)
        if (!resp.isSuccessful) {
            resp.body()?.close()
            return@withContext false
        }
        val bytes = resp.body()?.bytes() ?: return@withContext false
        saveBytesNeverLaunch(context, fileName, bytes)
    } catch (_: Exception) {
        false
    }
}

/** Save to Downloads / MediaStore only — never open or install the file (AT-M17). */
private fun saveBytesNeverLaunch(
    context: android.content.Context,
    fileName: String,
    bytes: ByteArray
): Boolean {
    val safeName = fileName.ifBlank { "download.bin" }
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val out = java.io.File(dir, safeName)
            out.outputStream().use { it.write(bytes) }
            true
        }
    } catch (_: Exception) {
        false
    }
}
