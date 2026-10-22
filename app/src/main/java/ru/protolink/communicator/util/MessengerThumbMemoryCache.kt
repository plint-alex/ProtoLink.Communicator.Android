package ru.protolink.communicator.util

import android.graphics.Bitmap
import java.util.LinkedHashMap

/**
 * In-memory only thumbnail cache for chat media (cycle 1: no disk cache of chat media).
 * Cleared on sign-out.
 */
object MessengerThumbMemoryCache {
    const val MaxEntries = 64

    private val lock = Any()
    private val map = object : LinkedHashMap<String, Bitmap>(MaxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > MaxEntries
    }

    fun key(entityId: String, width: Int, height: Int): String =
        "${entityId.lowercase()}:${width}x$height"

    fun get(entityId: String, width: Int, height: Int): Bitmap? = synchronized(lock) {
        map[key(entityId, width, height)]
    }

    fun put(entityId: String, width: Int, height: Int, bitmap: Bitmap) = synchronized(lock) {
        map[key(entityId, width, height)] = bitmap
    }

    fun clear() = synchronized(lock) {
        map.clear()
    }

    val size: Int get() = synchronized(lock) { map.size }
}
