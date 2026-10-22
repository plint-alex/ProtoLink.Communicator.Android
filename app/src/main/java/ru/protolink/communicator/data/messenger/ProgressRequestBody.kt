package ru.protolink.communicator.data.messenger

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream

/**
 * OkHttp [RequestBody] that reports write progress (same role as Windows ProgressReadStream).
 */
class ProgressRequestBody(
    private val inputStream: InputStream,
    private val contentLength: Long,
    private val contentType: String?,
    private val onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit
) : RequestBody() {
    override fun contentType(): MediaType? = contentType?.toMediaTypeOrNull()

    override fun contentLength(): Long = contentLength

    override fun writeTo(sink: BufferedSink) {
        inputStream.source().use { source ->
            var written = 0L
            val buffer = okio.Buffer()
            while (true) {
                val read = source.read(buffer, 8_192)
                if (read == -1L) break
                sink.write(buffer, read)
                written += read
                onProgress(written, contentLength)
            }
        }
    }
}
