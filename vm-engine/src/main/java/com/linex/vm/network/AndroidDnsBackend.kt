package com.linex.vm.network

import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class DnsResolverMode { WIRE, LIMITED_ADDRESSES, TEST_BOUNDARY }

internal object AndroidDnsBackends {
    fun create(context: Context): DnsBackend = if (Build.VERSION.SDK_INT >= 29) WireBackend()
        else LegacyBackend(context.getSystemService(ConnectivityManager::class.java))

    private fun executor(threads: Int, name: String) = ThreadPoolExecutor(threads, threads, 0,
        TimeUnit.MILLISECONDS, ArrayBlockingQueue(64), { task -> Thread(task, name) },
        ThreadPoolExecutor.AbortPolicy())

    /** Public API29 wire resolver; Android owns strict/opportunistic Private DNS policy. */
    @TargetApi(29)
    private class WireBackend : DnsBackend {
        private val callbacks = executor(1, "LinexDnsCallback")
        private val operations = DnsCallbackOperations()
        private val closed = AtomicBoolean()
        override fun query(question: DnsQuestion, completion: (DnsBackendResult) -> Unit): DnsCancellation {
            val cancellation = CancellationSignal()
            val operation = operations.register(cancellation::cancel)
            if (operation == null) {
                completion(DnsBackendResult.Failure(if (closed.get()) DnsBridgeError.STOPPED else DnsBridgeError.CAPACITY))
                return DnsCancellation { }
            }
            val delivery = DnsCallbackDelivery(callbacks, operation::cancel) {
                completion(DnsBackendResult.Failure(DnsBridgeError.CAPACITY))
            }
            try { DnsResolver.getInstance().rawQuery(null, question.wire(), DnsResolver.FLAG_EMPTY,
                delivery, cancellation, object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        if (operation.complete() && !closed.get() && !cancellation.isCanceled) {
                            // Preserve NXDOMAIN/NODATA and flags; the broker validates
                            // wire identity/size and forms legal UDP TC when needed.
                            completion(DnsBackendResult.Answer(answer))
                        }
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        if (operation.complete() && !closed.get() && !cancellation.isCanceled)
                            completion(DnsBackendResult.Failure(DnsBridgeError.RESOLVER_FAILURE))
                    }
                }) } catch (failure: RuntimeException) {
                operation.cancel()
                throw failure
            }
            return DnsCancellation { operation.cancel() }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                // Queued framework tasks still own resolver descriptors. Cancel
                // each operation before shutdownNow discards those tasks.
                operations.close()
                callbacks.shutdownNow()
            }
        }
    }

    /** API26–28: only IN A/AAAA, synthetic TTLs, no authenticated-data claim. */
    private class LegacyBackend(private val connectivity: ConnectivityManager) : DnsBackend {
        private val workers = executor(2, "LinexDnsLegacy")
        private val closed = AtomicBoolean()

        override fun query(question: DnsQuestion, completion: (DnsBackendResult) -> Unit): DnsCancellation {
            val name = try { DnsWire.legacyName(question) } catch (_: UnsupportedDnsQueryException) {
                completion(DnsBackendResult.Failure(DnsBridgeError.UNSUPPORTED))
                return DnsCancellation { }
            }
            val network = connectivity.activeNetwork
            if (closed.get() || network == null) {
                completion(DnsBackendResult.Failure(DnsBridgeError.RESOLVER_FAILURE))
                return DnsCancellation { }
            }
            val cancelled = AtomicBoolean()
            val future = try {
                workers.submit {
                    val result = try {
                        val addresses = network.getAllByName(name).take(33).map { it.address }
                        DnsBackendResult.Answer(DnsWire.legacyAnswer(question, addresses))
                    } catch (_: Exception) {
                        // UnknownHostException cannot distinguish NXDOMAIN from
                        // outage/policy failure. Never invent a negative DNS result.
                        DnsBackendResult.Failure(DnsBridgeError.RESOLVER_FAILURE)
                    }
                    if (!closed.get() && !cancelled.get()) completion(result)
                }
            } catch (_: RejectedExecutionException) {
                completion(DnsBackendResult.Failure(DnsBridgeError.CAPACITY))
                return DnsCancellation { }
            }
            return DnsCancellation {
                cancelled.set(true); future.cancel(true); workers.purge()
            }
        }

        override fun close() {
            // Native resolver calls may ignore interruption. Never create
            // replacement pools; at most two blocked workers remain in :vm.
            if (closed.compareAndSet(false, true)) workers.shutdownNow()
        }
    }
}
