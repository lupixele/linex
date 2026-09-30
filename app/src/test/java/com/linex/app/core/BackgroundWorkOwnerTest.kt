package com.linex.app.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BackgroundWorkOwnerTest {
    @Test fun cancellingUiCallerDoesNotCancelServiceOperation() = runBlocking {
        val owner = BackgroundWorkOwner(Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        var completed = false
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.start("setup", true) { release.await(); completed = true }
            awaitCancellation()
        }
        caller.cancelAndJoin()
        assertTrue(owner.isRunning)
        release.complete(Unit)
        assertTrue(completed)
        assertFalse(owner.isRunning)
        owner.close()
    }

    @Test fun duplicateStartIsRejectedAndDoesNotReplaceActiveWork() {
        val owner = BackgroundWorkOwner(Dispatchers.Unconfined)
        var released = false
        owner.start("first", true) { try { awaitCancellation() } finally { released = true } }
        assertThrows(IllegalStateException::class.java) { owner.start("second", true) {} }
        assertFalse(released)
        owner.cancel("first")
        assertTrue(released)
        owner.close()
    }

    @Test fun cancellationRequiresMatchingInstanceAndCleanupRuns() {
        val owner = BackgroundWorkOwner(Dispatchers.Unconfined)
        var released = false
        owner.start("first", true) { try { awaitCancellation() } finally { released = true } }
        owner.cancel("other")
        assertTrue(owner.isRunning)
        assertFalse(released)
        owner.cancel("first")
        assertTrue(released)
        assertFalse(owner.isRunning)
        owner.start("retry", true) {}
        owner.close()
    }

    @Test fun deletionCannotBeUserCancelledButServiceShutdownCancelsIt() {
        val owner = BackgroundWorkOwner(Dispatchers.Unconfined)
        var released = false
        owner.start("delete", false) { try { awaitCancellation() } finally { released = true } }
        owner.cancel("delete")
        assertTrue(owner.isRunning)
        owner.close()
        assertTrue(released)
        assertFalse(owner.isRunning)
    }
}
