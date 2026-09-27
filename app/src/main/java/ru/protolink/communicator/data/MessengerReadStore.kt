package ru.protolink.communicator.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Local per-contact last-opened watermarks for unread badges. */
@Singleton
class MessengerReadStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("protolink_messenger_read", Context.MODE_PRIVATE)

    fun getOpenedUtcMillis(contactId: String): Long =
        prefs.getLong(contactId.lowercase(), 0L)

    fun markOpened(contactId: String, utcMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(contactId.lowercase(), utcMillis).apply()
    }
}
