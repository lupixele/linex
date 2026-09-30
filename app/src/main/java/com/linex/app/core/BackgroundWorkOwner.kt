package com.linex.app.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Owns operation lifetime independently of the UI caller; call its methods on the service dispatcher. */
internal class BackgroundWorkOwner(dispatcher: CoroutineDispatcher) {
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    private var operationId: String? = null
    private var cancellable = false
    val isRunning: Boolean get() = job?.isActive == true

    fun start(id: String, canCancel: Boolean, operation: suspend () -> Unit) {
        check(!isRunning) { "Another operation is already running" }
        operationId = id
        cancellable = canCancel
        // Assign first: even an immediately completing operation cannot leave the wrong job registered.
        job = scope.launch(start = CoroutineStart.LAZY) { operation() }
        job!!.start()
    }

    fun cancel(id: String) {
        if (id == operationId && cancellable) job?.cancel()
    }

    fun close() { scope.cancel() }
}
