package com.neekhar.pccontrol.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.neekhar.pccontrol.core.storage.AppPrefs
import com.neekhar.pccontrol.core.storage.AppPrefsSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Active API transport used for `/pc/api/...` requests.
 *
 * - [CLOUDFLARE]: Public API via `apiUrl` (`https://api.neekhar.dev`) with Cloudflare Access +
 *   API Bearer dual authentication. Always the immediate initial transport when configured.
 * - [TAILSCALE]: Direct Tailscale Serve origin (`serverUrl`), promoted opportunistically via a
 *   single 500 ms probe only when a Tailscale VPN is detected as active.
 */
enum class ApiTransport(val label: String) {
    CLOUDFLARE("Cloudflare"),
    TAILSCALE("Tailscale"),
}

/**
 * Event-driven, zero-polling transport manager switching between Cloudflare and Tailscale.
 *
 * Uses [ConnectivityManager.registerDefaultNetworkCallback] (requires only `ACCESS_NETWORK_STATE`,
 * no Location permission) to detect VPN presence and network transitions:
 * - If public API is configured (`apiUrl` and `apiToken` non-blank):
 *   - Starts immediately on [ApiTransport.CLOUDFLARE] (zero mount delay).
 *   - If Tailscale VPN is not active: stays on [ApiTransport.CLOUDFLARE] with zero probes.
 *   - If Tailscale VPN is active (at start or on network/VPN change): fires a single one-shot
 *     500 ms `GET <serverUrl>/pc/api/info` probe; promotes to [ApiTransport.TAILSCALE] on HTTP 200.
 *   - If VPN drops, or any Tailscale API request fails with a transport error (`statusCode == 0`),
 *     immediately flips back to [ApiTransport.CLOUDFLARE].
 * - If public API is not configured yet (`apiToken` blank):
 *   - Falls back to [ApiTransport.TAILSCALE] so existing installs keep working until configured.
 */
class ApiTransportManager(
    private val prefs: AppPrefs,
    private val probeClient: OkHttpClient,
    context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val vpnDetector: (() -> Boolean)? = null,
    private val probeTimeoutMs: Long = TAILSCALE_PROBE_TIMEOUT_MS,
) {
    private val connectivityManager: ConnectivityManager? =
        context?.applicationContext?.getSystemService(ConnectivityManager::class.java)

    private val _activeTransport = MutableStateFlow(initialTransport(prefs.current.value))
    val activeTransport: StateFlow<ApiTransport> = _activeTransport.asStateFlow()

    private val probeLock = Any()
    private var probeJob: Job? = null

    @Volatile
    private var lastVpnActive: Boolean? = null

    @Volatile
    private var lastNetworkHandle: Long? = null

    private val started = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        start()
    }

    private fun start() {
        if (!started.compareAndSet(false, true)) return

        registerNetworkCallback()

        scope.launch {
            prefs.snapshots
                .map { ConfigKey(it.serverUrl, it.apiUrl, it.isPublicApiConfigured) }
                .distinctUntilChanged()
                .collect { key ->
                    onConfigChanged(key)
                }
        }
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkEvent(networkHandle = runCatching { network.networkHandle }.getOrNull(), forceProbeIfVpn = true)
            }

            override fun onLost(network: Network) {
                onNetworkEvent(networkHandle = null, forceProbeIfVpn = false)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                onNetworkEvent(networkHandle = runCatching { network.networkHandle }.getOrNull(), forceProbeIfVpn = false)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                onNetworkEvent(networkHandle = runCatching { network.networkHandle }.getOrNull(), forceProbeIfVpn = false)
            }
        }
        networkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    /** Returns true when Tailscale (or any active VPN) is present according to [ConnectivityManager]. */
    fun isVpnActive(): Boolean {
        val custom = vpnDetector
        if (custom != null) return custom()
        return detectTailscaleVpnActive(connectivityManager)
    }

    /**
     * Resolves the effective transport for a given [snapshot], honoring fallback to Tailscale
     * whenever the public API is not yet configured.
     */
    fun resolveTransport(snapshot: AppPrefsSnapshot = prefs.current.value): ApiTransport {
        if (!snapshot.isPublicApiConfigured) return ApiTransport.TAILSCALE
        return _activeTransport.value
    }

    /**
     * Called by [PcApiClient] whenever an API call over [ApiTransport.TAILSCALE] fails with a
     * network/connection error (`statusCode == 0`). Immediately flips to [ApiTransport.CLOUDFLARE]
     * if public API credentials are configured.
     */
    fun reportTailscaleFailure() {
        val snap = prefs.current.value
        if (!snap.isPublicApiConfigured) return
        cancelProbe()
        _activeTransport.value = ApiTransport.CLOUDFLARE
    }

    /** Triggers a single one-shot transport re-evaluation (e.g. after saving Settings). */
    fun reprobeNow() {
        val snap = prefs.current.value
        if (!snap.isPublicApiConfigured) {
            cancelProbe()
            _activeTransport.value = ApiTransport.TAILSCALE
            return
        }
        _activeTransport.value = ApiTransport.CLOUDFLARE
        val vpn = isVpnActive()
        lastVpnActive = vpn
        if (vpn) {
            launchTailscaleProbe(snap.serverUrl)
        } else {
            cancelProbe()
        }
    }

    fun close() {
        cancelProbe()
        val cm = connectivityManager
        val cb = networkCallback
        if (cm != null && cb != null) {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
    }

    private fun onConfigChanged(key: ConfigKey) {
        if (!key.publicApiConfigured) {
            cancelProbe()
            _activeTransport.value = ApiTransport.TAILSCALE
            return
        }
        // Cloudflare-first: start immediately on Cloudflare without waiting on any probe.
        _activeTransport.value = ApiTransport.CLOUDFLARE
        val vpn = isVpnActive()
        lastVpnActive = vpn
        if (vpn) {
            launchTailscaleProbe(key.serverUrl)
        } else {
            cancelProbe()
        }
    }

    private fun onNetworkEvent(networkHandle: Long?, forceProbeIfVpn: Boolean) {
        val snap = prefs.current.value
        if (!snap.isPublicApiConfigured) {
            _activeTransport.value = ApiTransport.TAILSCALE
            return
        }
        val vpn = isVpnActive()
        val prevVpn = lastVpnActive
        val prevHandle = lastNetworkHandle
        lastVpnActive = vpn
        if (networkHandle != null) {
            lastNetworkHandle = networkHandle
        }

        if (!vpn) {
            // VPN dropped -> immediately switch back to Cloudflare with zero delay.
            cancelProbe()
            _activeTransport.value = ApiTransport.CLOUDFLARE
            return
        }

        // VPN is active: probe once if VPN just became active or default network switched while on Cloudflare.
        val vpnJustActivated = prevVpn != true
        val networkSwitched = forceProbeIfVpn && networkHandle != null && networkHandle != prevHandle
        if (vpnJustActivated || (networkSwitched && _activeTransport.value == ApiTransport.CLOUDFLARE)) {
            launchTailscaleProbe(snap.serverUrl)
        }
    }

    private fun launchTailscaleProbe(serverUrl: String) {
        val cleanServer = serverUrl.trim().trimEnd('/')
        if (cleanServer.isEmpty()) return
        synchronized(probeLock) {
            probeJob?.cancel()
            probeJob = scope.launch {
                val ok = probeTailscaleOnce(cleanServer)
                if (ok && prefs.current.value.isPublicApiConfigured && isVpnActive()) {
                    _activeTransport.value = ApiTransport.TAILSCALE
                }
            }
        }
    }

    private fun cancelProbe() {
        synchronized(probeLock) {
            probeJob?.cancel()
            probeJob = null
        }
    }

    private fun probeTailscaleOnce(serverUrl: String): Boolean {
        return try {
            val client = probeClient.newBuilder()
                .connectTimeout(probeTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(probeTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(probeTimeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(probeTimeoutMs, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()
            val req = Request.Builder()
                .url("$serverUrl/pc/api/info")
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", PcApiClient.USER_AGENT)
                .build()
            client.newCall(req).execute().use { resp ->
                resp.code == 200
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private data class ConfigKey(
        val serverUrl: String,
        val apiUrl: String,
        val publicApiConfigured: Boolean,
    )

    companion object {
        const val TAILSCALE_PROBE_TIMEOUT_MS = 500L
        const val TAILSCALE_MAGIC_DNS = "100.100.100.100"

        internal fun initialTransport(snapshot: AppPrefsSnapshot): ApiTransport =
            if (snapshot.isPublicApiConfigured) ApiTransport.CLOUDFLARE else ApiTransport.TAILSCALE

        /**
         * Synchronously inspects active Android networks for Tailscale VPN presence
         * (`TRANSPORT_VPN` or Tailscale MagicDNS `100.100.100.100`).
         */
        fun detectTailscaleVpnActive(cm: ConnectivityManager?): Boolean {
            if (cm == null) return false
            val networks = buildList {
                cm.activeNetwork?.let { add(it) }
                @Suppress("DEPRECATION")
                runCatching { cm.allNetworks }.getOrNull()?.forEach { net ->
                    if (net !in this) add(net)
                }
            }
            for (net in networks) {
                val caps = runCatching { cm.getNetworkCapabilities(net) }.getOrNull()
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    return true
                }
                val lp = runCatching { cm.getLinkProperties(net) }.getOrNull()
                if (lp?.dnsServers?.any { it.hostAddress == TAILSCALE_MAGIC_DNS } == true) {
                    return true
                }
            }
            return false
        }
    }
}
