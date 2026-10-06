package com.linex.vm.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

enum class PrivateDnsState { NOT_SUPPORTED, UNKNOWN, INACTIVE, OPPORTUNISTIC_ACTIVE, STRICT_ACTIVE, STRICT_UNAVAILABLE }

/** Observes policy; never binds the app, changes settings, or logs provider names. */
internal class DefaultDnsNetworkObserver(context: Context, private val changed: () -> Unit) : Closeable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val closed = AtomicBoolean()
    private val lock = Any()
    private var network: Network? = connectivity.activeNetwork
    // Framework supplies parcelled snapshots; do not modify these objects.
    private var properties = network?.let { connectivity.getLinkProperties(it) }
    @Volatile var privateDnsState = state(properties)
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(available: Network) {
            val notify = synchronized(lock) {
                if (closed.get() || network == available) false else {
                    network = available; properties = null; privateDnsState = state(null); true
                }
            }
            if (notify) changed()
        }
        override fun onLinkPropertiesChanged(available: Network, updated: LinkProperties) {
            val notify = synchronized(lock) {
                if (closed.get() || network != available || properties == updated) false else {
                    properties = updated; privateDnsState = state(updated); true
                }
            }
            if (notify) changed()
        }
        override fun onLost(lost: Network) {
            val notify = synchronized(lock) {
                if (closed.get() || network != lost) false else {
                    network = null; properties = null; privateDnsState = state(null); true
                }
            }
            if (notify) changed()
        }
    }

    init { connectivity.registerDefaultNetworkCallback(callback) }

    override fun close() {
        if (closed.compareAndSet(false, true)) connectivity.unregisterNetworkCallback(callback)
    }

    private fun state(properties: LinkProperties?): PrivateDnsState {
        if (Build.VERSION.SDK_INT < 28) return PrivateDnsState.NOT_SUPPORTED
        if (properties == null) return PrivateDnsState.UNKNOWN
        val strict = !properties.privateDnsServerName.isNullOrEmpty()
        return if (properties.isPrivateDnsActive) {
            if (strict) PrivateDnsState.STRICT_ACTIVE else PrivateDnsState.OPPORTUNISTIC_ACTIVE
        } else if (strict) PrivateDnsState.STRICT_UNAVAILABLE else PrivateDnsState.INACTIVE
    }
}
