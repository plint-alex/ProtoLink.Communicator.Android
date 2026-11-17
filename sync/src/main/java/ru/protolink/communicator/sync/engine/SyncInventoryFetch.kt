package ru.protolink.communicator.sync.engine

/**
 * Force structure-first inventory GetEntities: transient HTTP 5xx must not abort the whole
 * pass (live FAIL class: retrofit2.HttpException HTTP 500 mid parent-walk). Retry with
 * backoff, then soft-fail that parent/chunk so siblings still fill+prune (NFR-SYNC-1).
 * Auth / 4xx stay hard. Root listing exhaustion stays hard (empty inventory would prune all).
 */
object SyncInventoryFetch {
    const val MAX_ATTEMPTS = 4

    /**
     * MatchAny / fat ParentIds batches: one 5xx is enough to fall back to per-parent soft-fail.
     * Live 1.0.57: 4×~90–180s gateway timeouts per batch kept Force pre-getFile for 23m+.
     */
    const val BATCH_MAX_ATTEMPTS = 1

    /** Delay before attempt index 0..3 (ms). First try immediate. */
    private val BACKOFF_MS = longArrayOf(0L, 500L, 1_500L, 4_000L)

    private val HTTP_5XX = Regex("""HTTP\s+5\d{2}\b""", RegexOption.IGNORE_CASE)
    private val HTTP_4XX = Regex("""HTTP\s+4\d{2}\b""", RegexOption.IGNORE_CASE)
    private val GATEWAY_HINT = Regex("""Gateway\s+Time-?out|504""", RegexOption.IGNORE_CASE)

    fun backoffMs(attemptIndex: Int): Long =
        BACKOFF_MS.getOrElse(attemptIndex) { BACKOFF_MS.last() }

    fun isTransientServerError(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            val msg = cur.message.orEmpty()
            if (HTTP_5XX.containsMatchIn(msg) || GATEWAY_HINT.containsMatchIn(msg)) return true
            // Some wrappers omit "HTTP 5xx" in message but keep the type name.
            if (cur.javaClass.name.contains("HttpException", ignoreCase = true) &&
                GATEWAY_HINT.containsMatchIn(msg)
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    fun isHardClientError(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (HTTP_4XX.containsMatchIn(cur.message.orEmpty())) return true
            cur = cur.cause
        }
        return false
    }

    fun logRetry(phase: String, parentOrChunk: String, attempt: Int, detail: String) {
        System.err.println(
            "[SyncEngine] inventory GetEntities retry ($phase): key=$parentOrChunk attempt=$attempt detail=$detail"
        )
    }

    fun logSoftFail(phase: String, parentOrChunk: String, detail: String) {
        System.err.println(
            "[SyncEngine] soft-fail inventory GetEntities ($phase): key=$parentOrChunk detail=$detail"
        )
    }
}
