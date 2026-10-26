package ru.protolink.communicator.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Cycle-2 M6 — client-side image downscale before Messenger upload (Android first).
 * Max long edge 2048, JPEG ~q82 — same box as server [Files/getImage].
 * Pure sizing/decision helpers are unit-tested; encode uses Android Bitmap.
 */
object MessengerImageDownscale {
    const val MaxLongEdge = 2048
    const val JpegQuality = 82

    /** When false, Camera/Gallery/File… image paths keep original bytes (no re-encode). */
    fun shouldApply(enabled: Boolean, mimeType: String?, isCloudPick: Boolean): Boolean {
        if (!enabled || isCloudPick) return false
        val mime = mimeType?.trim()?.lowercase().orEmpty()
        return mime.startsWith("image/")
    }

    /**
     * Fit inside [maxLongEdge] box without upscale; aspect preserved.
     * Returns (width, height) both ≥ 1 when inputs are positive.
     */
    fun targetSize(width: Int, height: Int, maxLongEdge: Int = MaxLongEdge): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val longEdge = maxOf(width, height)
        if (longEdge <= maxLongEdge) return width to height
        val scale = maxLongEdge.toDouble() / longEdge.toDouble()
        val tw = (width * scale).toInt().coerceAtLeast(1)
        val th = (height * scale).toInt().coerceAtLeast(1)
        return tw to th
    }

    /** Power-of-two inSampleSize so decoded max edge is still ≥ [maxLongEdge] when possible. */
    fun decodeInSampleSize(srcWidth: Int, srcHeight: Int, maxLongEdge: Int = MaxLongEdge): Int {
        if (srcWidth <= 0 || srcHeight <= 0) return 1
        var sample = 1
        var w = srcWidth
        var h = srcHeight
        while (maxOf(w / 2, h / 2) >= maxLongEdge) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample.coerceAtLeast(1)
    }

    fun jpegFileName(originalName: String): String {
        val base = originalName.substringBeforeLast('.', originalName).ifBlank { "image" }
        return "$base.jpg"
    }

    /**
     * Decode [uri] (or [localFilePath]), downscale to max long edge, write JPEG to cache.
     * Returns null when decode/encode fails (caller keeps original).
     */
    fun encodeToCacheJpeg(
        context: Context,
        uri: Uri,
        localFilePath: String?,
        originalFileName: String
    ): DownscaledImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = openDecodeStream(context, uri, localFilePath) ?: return null
        // inJustDecodeBounds always returns null; dimensions land in bounds.outWidth/outHeight.
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        val sample = decodeInSampleSize(srcW, srcH)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = openDecodeStream(context, uri, localFilePath)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return null

        val (tw, th) = targetSize(decoded.width, decoded.height)
        val scaled = if (decoded.width == tw && decoded.height == th) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, tw, th, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }

        val dir = File(context.cacheDir, "messenger_downscale").also { it.mkdirs() }
        val outFile = File(dir, "ds-${UUID.randomUUID()}.jpg")
        return try {
            FileOutputStream(outFile).use { fos ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, JpegQuality, fos)) {
                    outFile.delete()
                    return null
                }
            }
            DownscaledImage(
                file = outFile,
                fileName = jpegFileName(originalFileName),
                mimeType = "image/jpeg",
                byteLength = outFile.length()
            )
        } catch (ex: Exception) {
            Log.w("ProtoLinkChat", "image downscale failed", ex)
            outFile.delete()
            null
        } finally {
            scaled.recycle()
        }
    }

    private fun openDecodeStream(context: Context, uri: Uri, localFilePath: String?) =
        runCatching {
            context.contentResolver.openInputStream(uri)
                ?: localFilePath?.let { File(it).inputStream() }
        }.getOrNull()
}

data class DownscaledImage(
    val file: File,
    val fileName: String,
    val mimeType: String,
    val byteLength: Long
)
