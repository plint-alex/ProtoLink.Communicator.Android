package ru.protolink.communicator.util

import android.graphics.Bitmap

/** In-memory unfurl cards per entity id (M8). Cleared on sign-out; nothing persisted. */
object MessengerUnfurlCache {
    data class Entry(
        val allowed: Boolean,
        val title: String = "",
        val typeName: String = "",
        val thumb: Bitmap? = null
    )

    private val lock = Any()
    private val map = mutableMapOf<String, Entry>()

    fun get(entityId: String): Entry? = synchronized(lock) {
        map[entityId.lowercase()]
    }

    fun put(entityId: String, entry: Entry) = synchronized(lock) {
        map[entityId.lowercase()] = entry
    }

    fun clear() = synchronized(lock) {
        map.clear()
    }
}
