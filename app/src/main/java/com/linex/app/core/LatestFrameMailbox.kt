package com.linex.app.core

/** Coalesces owned frames without postponing an already scheduled presentation.
 * Scheduling and cancellation share the lock so close cannot race a late post.
 * The consumer owns a taken frame and must release it after copying its pixels.
 */
internal class LatestFrameMailbox<T>(
    private val schedule: () -> Unit,
    private val cancel: () -> Unit,
    private val release: (T) -> Unit
) : AutoCloseable {
    private var pending: T? = null
    private var scheduled = false
    private var closed = false

    @Synchronized fun offer(frame: T) {
        if (closed) { release(frame); return }
        pending?.let(release)
        pending = frame
        if (!scheduled) {
            scheduled = true
            schedule()
        }
    }

    @Synchronized fun take(): T? = pending.also { pending = null }

    /** Keep arrivals during copying queued for the following animation tick. */
    @Synchronized fun complete() {
        if (closed) return
        if (pending != null) schedule() else scheduled = false
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        cancel()
        pending?.let(release)
        pending = null
        scheduled = false
    }
}
