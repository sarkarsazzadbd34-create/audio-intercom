package com.example.audiorx

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

data class DiscoveredTransmitter(val name: String, val host: String, val port: Int)

/**
 * Lets the Receiver discover Transmitters advertising on the LAN
 * (see NsdAdvertiser in the transmitter app) as an alternative to QR
 * scanning / manual code entry.
 */
class NsdDiscovery(private val context: Context) {
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start(onFound: (DiscoveredTransmitter) -> Unit, onLost: (String) -> Unit) {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Resolve failed: $errorCode")
                    }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        onFound(DiscoveredTransmitter(si.serviceName, si.host.hostAddress ?: "", si.port))
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) = onLost(service.serviceName)
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stop() {
        discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        discoveryListener = null
    }

    companion object {
        private const val TAG = "NsdDiscovery"
        const val SERVICE_TYPE = "_audiotx._tcp."
    }
}
