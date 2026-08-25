package websnag.elopenmike.com.core.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Snapshot of current WiFi connectivity state.
 */
data class WifiState(
    val isConnectedToWifi: Boolean = false,
    val currentSsid: String? = null,
    val hasLocationPermission: Boolean = false
)

/**
 * Reactive monitor for Android network connectivity and WiFi state.
 */
interface NetworkMonitor {
    val wifiState: StateFlow<WifiState>
    fun refresh()
}

class AndroidNetworkMonitor(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) : NetworkMonitor {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _wifiState = MutableStateFlow(resolveCurrentWifiState())
    override val wifiState: StateFlow<WifiState> = _wifiState.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateWifiState()
        }

        override fun onLost(network: Network) {
            updateWifiState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            updateWifiState()
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {
            // Handle sandbox/restricted environments gracefully
        }
    }

    override fun refresh() {
        updateWifiState()
    }

    private fun updateWifiState() {
        coroutineScope.launch {
            _wifiState.value = resolveCurrentWifiState()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveCurrentWifiState(): WifiState {
        val cm = connectivityManager ?: return WifiState()
        val activeNetwork = cm.activeNetwork ?: return WifiState()
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return WifiState()

        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        if (!isWifi) {
            return WifiState(isConnectedToWifi = false)
        }

        val hasLocPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        var rawSsid: String? = null

        // Attempt 1: Via NetworkCapabilities transportInfo (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = capabilities.transportInfo as? WifiInfo
            if (info != null && !info.ssid.isNullOrBlank() && info.ssid != WifiManager.UNKNOWN_SSID && info.ssid != "<unknown ssid>") {
                rawSsid = info.ssid
            }
        }

        // Attempt 2: Via WifiManager connectionInfo fallback
        if (rawSsid == null && wifiManager != null) {
            try {
                val info = wifiManager.connectionInfo
                if (info != null && !info.ssid.isNullOrBlank() && info.ssid != WifiManager.UNKNOWN_SSID && info.ssid != "<unknown ssid>") {
                    rawSsid = info.ssid
                }
            } catch (_: Exception) {
            }
        }

        val cleanSsid = rawSsid?.removeSurrounding("\"")?.trim()?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }

        return WifiState(
            isConnectedToWifi = true,
            currentSsid = cleanSsid,
            hasLocationPermission = hasLocPerm
        )
    }
}
