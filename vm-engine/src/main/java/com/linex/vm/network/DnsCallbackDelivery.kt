package com.linex.vm.network

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Cancels a resolver operation if its framework callback cannot be scheduled. */
internal class DnsCallbackDelivery(
    private val executor: Executor,
    private val cancel: () -> Unit,
    private val rejected: () -> Unit
) : Executor {
    override fun execute(command: Runnable) {
        try { executor.execute(command) } catch (_: RejectedExecutionException) {
            // Android removes its FD-ready listener before scheduling this
            // Runnable. Dropping it without cancellation strands the query FD.
            try { cancel() } finally { rejected() }
        }
    }
}

/** Keeps cancellation ownership until the framework has delivered its result. */
internal class DnsCallbackOperations : AutoCloseable {
    private val lock = Any()
    private val active = LinkedHashSet<Operation>()
    private var closed = false

    fun register(cancel: () -> Unit): Operation? = synchronized(lock) {
        if (closed || active.size >= DnsRequestBroker.MAX_PENDING) return null
        Operation(cancel).also { active.add(it) }
    }

    inner class Operation internal constructor(private val cancelResolver: () -> Unit) {
        fun complete(): Boolean = synchronized(lock) { active.remove(this) }
        fun cancel() {
            if (complete()) cancelResolver()
        }
        internal fun cancelOnClose() = cancelResolver()
    }

    override fun close() {
        val cancelled = synchronized(lock) {
            if (closed) return
            closed = true
            active.toList().also { active.clear() }
        }
        cancelled.forEach {
            try { it.cancelOnClose() } catch (_: RuntimeException) { }
        }
    }
}
