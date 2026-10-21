package dev.swipeto.sender.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors

/** A receiver found over mDNS (docs/protocol.md, section 2). Identified by [id], never by address. */
data class DiscoveredReceiver(
    val id: String,
    val name: String,
    val platform: String,
    val version: Int,
    val caps: List<String>,
    val host: InetAddress,
    val port: Int,
) {
    val webSocketUrl: String
        get() = "ws://${if (host is Inet4Address) host.hostAddress else "[${host.hostAddress?.substringBefore('%')}]"}:$port/v1"

    companion object {
        /** Builds a receiver from TXT attributes; null if `id` is missing or the version is not one we speak. */
        fun fromTxt(txt: Map<String, String>, host: InetAddress, port: Int): DiscoveredReceiver? {
            val id = txt["id"]?.lowercase()?.takeIf { UUID.matches(it) } ?: return null
            val version = txt["ver"]?.toIntOrNull() ?: 1
            if (version != 1) return null
            return DiscoveredReceiver(
                id = id,
                name = txt["name"]?.takeIf { it.isNotBlank() } ?: "Screen",
                platform = txt["platform"] ?: "unknown",
                version = version,
                caps = txt["caps"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                host = host,
                port = port,
            )
        }

        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}

/**
 * Browses `_swipeto._tcp` with [NsdManager] and keeps [receivers] up to date. Holds a Wi-Fi
 * multicast lock while browsing, since some phones drop mDNS answers without one.
 */
class ReceiverDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val byService = linkedMapOf<String, DiscoveredReceiver>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val infoCallbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _receivers = MutableStateFlow<List<DiscoveredReceiver>>(emptyList())
    val receivers: StateFlow<List<DiscoveredReceiver>> = _receivers.asStateFlow()

    @Synchronized
    fun start() {
        if (listener != null) return
        multicastLock = wifi?.createMulticastLock("swipeto-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = onFound(info)
            override fun onServiceLost(info: NsdServiceInfo) = onLost(info.serviceName)
        }
        listener = discovery
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        if (Build.VERSION.SDK_INT >= 34) infoCallbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        infoCallbacks.clear()
        resolveQueue.clear()
        resolving = false
        multicastLock?.release()
        multicastLock = null
    }

    /** Forget everything and browse again, e.g. after the Wi-Fi network changed. */
    fun restart() {
        stop()
        synchronized(this) { byService.clear() }
        publish()
        start()
    }

    @Synchronized
    private fun onFound(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            watch(info)
        } else {
            resolveQueue.addLast(info)
            resolveNext()
        }
    }

    @Synchronized
    private fun onLost(serviceName: String) {
        if (Build.VERSION.SDK_INT >= 34) infoCallbacks.remove(serviceName)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        if (byService.remove(serviceName) != null) publish()
    }

    /** API 34+: follows the service, including address changes, without the one-resolve-at-a-time limit. */
    @RequiresApi(34)
    private fun watch(info: NsdServiceInfo) {
        if (info.serviceName in infoCallbacks) return
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "resolve failed: $errorCode")
            }
            override fun onServiceUpdated(updated: NsdServiceInfo) {
                val host = updated.hostAddresses.firstOrNull { it is Inet4Address } ?: updated.hostAddresses.firstOrNull() ?: return
                record(updated, host)
            }
            override fun onServiceLost() = onLost(info.serviceName)
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        infoCallbacks[info.serviceName] = callback
        nsd.registerServiceInfoCallback(info, executor, callback)
    }

    /** Before API 34 NsdManager resolves one service at a time, so resolves are queued. */
    @Suppress("DEPRECATION")
    @Synchronized
    private fun resolveNext() {
        if (resolving) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()
            override fun onServiceResolved(info: NsdServiceInfo) {
                info.host?.let { record(info, it) }
                done()
            }

            private fun done() {
                synchronized(this@ReceiverDiscovery) { resolving = false }
                resolveNext()
            }
        })
    }

    @Synchronized
    private fun record(info: NsdServiceInfo, host: InetAddress) {
        val txt = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
        val receiver = DiscoveredReceiver.fromTxt(txt, host, info.port) ?: return
        if (byService[info.serviceName] == receiver) return
        byService[info.serviceName] = receiver
        publish()
    }

    @Synchronized
    private fun publish() {
        // One entry per receiver id, even if it shows up under two service names.
        _receivers.value = byService.values.associateBy { it.id }.values.sortedBy { it.name.lowercase() }
    }

    private companion object {
        const val SERVICE_TYPE = "_swipeto._tcp"
        const val TAG = "SwipeToDiscovery"
    }
}
