package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CloudFileTypeCueTest {
    @Test
    fun shortLabel_prefersExtensionThenMime() {
        assertThat(CloudFileTypeCue.shortLabel(null, "report.pdf")).isEqualTo("PDF")
        assertThat(CloudFileTypeCue.shortLabel("image/png", "photo.PNG")).isEqualTo("PNG")
        assertThat(CloudFileTypeCue.shortLabel("text/plain", "notes.txt")).isEqualTo("TXT")
        assertThat(CloudFileTypeCue.shortLabel(null, "archive.zip")).isEqualTo("ZIP")
        assertThat(CloudFileTypeCue.shortLabel("application/pdf", "noext")).isEqualTo("PDF")
        assertThat(CloudFileTypeCue.shortLabel("image/jpeg", "noext")).isEqualTo("Image")
        assertThat(CloudFileTypeCue.shortLabel("video/mp4", "noext")).isEqualTo("Video")
        assertThat(CloudFileTypeCue.shortLabel("audio/mpeg", "noext")).isEqualTo("Audio")
        assertThat(CloudFileTypeCue.shortLabel("application/json", "noext")).isEqualTo("JSON")
        assertThat(CloudFileTypeCue.shortLabel(null, "noext")).isEqualTo("File")
        assertThat(CloudFileTypeCue.shortLabel("application/octet-stream", "noext")).isEqualTo("File")
    }

    @Test
    fun looksLikeImage_mimeOrExtension() {
        assertThat(CloudFileTypeCue.looksLikeImage(null, "a.png")).isTrue()
        assertThat(CloudFileTypeCue.looksLikeImage("image/webp", "a.bin")).isTrue()
        assertThat(CloudFileTypeCue.looksLikeImage("application/pdf", "a.pdf")).isFalse()
        assertThat(CloudFileTypeCue.looksLikeImage(null, "a.txt")).isFalse()
    }
}
