package ru.protolink.communicator

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Holds adb/debug force-download requests.
 *
 * Preferred (reliable on Huawei):
 *   adb shell run-as ru.protolink.communicator.debug sh -c 'echo 1 > files/force_download'
 *   adb shell am start -n ru.protolink.communicator.debug/ru.protolink.communicator.MainActivity
 *
 * Also: am start … --es protolink_force download
 */
object PendingDebugIntent {
    private const val TAG = "ProtoLinkSync"
    private const val EXTRA_FORCE = "protolink_force"
    const val FORCE_FILE_NAME = "force_download"
    /** One-line title; triggers createNote at notes root (debug self-test). */
    const val CREATE_NOTE_FILE_NAME = "force_create_note"
    /** One-line relative path; triggers deleteNote (debug self-test). */
    const val DELETE_NOTE_FILE_NAME = "force_delete_note"

    @Volatile
    var forceDownload: Boolean = false
        private set

    @Volatile
    private var pendingCreateNoteTitle: String? = null

    @Volatile
    private var pendingDeleteNotePath: String? = null

    fun consumeFrom(intent: Intent?) {
        if (intent == null) return
        val keys = intent.extras?.keySet()?.joinToString(",") ?: "(none)"
        val force = intent.getStringExtra(EXTRA_FORCE)
        Log.e(TAG, "intent extras=[$keys] protolink_force=$force")
        if (force == null) return
        intent.removeExtra(EXTRA_FORCE)
        if (force.equals("download", ignoreCase = true)) {
            forceDownload = true
            Log.e(TAG, "pending force download from intent")
        }
    }

    /** File drop under app filesDir — survives Huawei intent-extra quirks. */
    fun consumeForceFile(context: Context): Boolean {
        val f = File(context.filesDir, FORCE_FILE_NAME)
        if (!f.exists()) return false
        runCatching { f.delete() }
        forceDownload = true
        Log.e(TAG, "pending force download from file")
        return true
    }

    fun consumeCreateNoteFile(context: Context): Boolean {
        val f = File(context.filesDir, CREATE_NOTE_FILE_NAME)
        if (!f.exists()) return false
        val title = runCatching { f.readText(Charsets.UTF_8).lines().firstOrNull().orEmpty().trim() }
            .getOrDefault("")
        runCatching { f.delete() }
        if (title.isBlank()) return false
        pendingCreateNoteTitle = title
        Log.e(TAG, "pending create note from file: $title")
        return true
    }

    fun takeForceDownload(): Boolean {
        if (!forceDownload) return false
        forceDownload = false
        return true
    }

    fun hasPendingCreateNote(): Boolean = pendingCreateNoteTitle != null

    fun takeCreateNoteTitle(): String? {
        val t = pendingCreateNoteTitle ?: return null
        pendingCreateNoteTitle = null
        return t
    }

    fun consumeDeleteNoteFile(context: Context): Boolean {
        val f = File(context.filesDir, DELETE_NOTE_FILE_NAME)
        if (!f.exists()) return false
        val path = runCatching { f.readText(Charsets.UTF_8).lines().firstOrNull().orEmpty().trim() }
            .getOrDefault("")
        runCatching { f.delete() }
        if (path.isBlank()) return false
        pendingDeleteNotePath = path
        Log.e(TAG, "pending delete note from file: $path")
        return true
    }

    fun hasPendingDeleteNote(): Boolean = pendingDeleteNotePath != null

    fun takeDeleteNotePath(): String? {
        val t = pendingDeleteNotePath ?: return null
        pendingDeleteNotePath = null
        return t
    }
}
