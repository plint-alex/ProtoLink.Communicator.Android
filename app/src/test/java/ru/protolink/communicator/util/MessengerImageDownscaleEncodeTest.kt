package ru.protolink.communicator.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowStrictBoundsBitmapFactory::class])
class MessengerImageDownscaleEncodeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun encodeToCacheJpeg_largeJpeg_downscalesToMaxLongEdge() {
        val source = writeNoisyJpeg(4000, 3000)

        val result = MessengerImageDownscale.encodeToCacheJpeg(
            context, Uri.fromFile(source), source.absolutePath, "IMG_0001.jpg"
        )

        assertThat(result).isNotNull()
        result!!
        val bounds = readBounds(result.file)
        assertThat(bounds).isEqualTo(2048 to 1536)
        assertThat(result.fileName).isEqualTo("IMG_0001.jpg")
        assertThat(result.mimeType).isEqualTo("image/jpeg")
        assertThat(result.byteLength).isLessThan(source.length())
    }

    @Test
    fun encodeToCacheJpeg_smallImage_keepsDimensions() {
        val source = writeNoisyJpeg(800, 600)

        val result = MessengerImageDownscale.encodeToCacheJpeg(
            context, Uri.fromFile(source), source.absolutePath, "small.png"
        )

        assertThat(result).isNotNull()
        assertThat(readBounds(result!!.file)).isEqualTo(800 to 600)
        assertThat(result.fileName).isEqualTo("small.jpg")
    }

    @Test
    fun encodeToCacheJpeg_missingSource_returnsNull() {
        val missing = File(context.cacheDir, "missing.jpg")

        val result = MessengerImageDownscale.encodeToCacheJpeg(
            context, Uri.fromFile(missing), missing.absolutePath, "missing.jpg"
        )

        assertThat(result).isNull()
    }

    private fun writeNoisyJpeg(width: Int, height: Int): File {
        val random = Random(42)
        val pixels = IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0xFFFFFF) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "src-${width}x$height.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun readBounds(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth to opts.outHeight
    }
}
