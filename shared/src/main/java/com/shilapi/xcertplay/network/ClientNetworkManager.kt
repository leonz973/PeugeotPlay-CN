package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Joins an existing Wi-Fi network (phone hotspot or LAN router) as a station so the iPhone and
 * the head unit share one network. This is the only wireless mode where the head unit is a
 * client instead of the access point.
 *
 * Discovery relies on multicast reaching both peers: networks with client isolation (common on
 * phone hotspots) cannot work in this mode. Uses the legacy WifiManager add/enable API, which is
 * still the supported way to connect without system privileges on the Android 9 head units.
 */
class ClientNetworkManager(
    context: Context,
    ssid: String,
    passphrase: String,
    private val onDiagnostic: (String) -> Unit = {},
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val expectedSsid = ssid
    private val passphrase = passphrase

    @Volatile
    private var closed = false

    init {
        require(expectedSsid.isNotBlank()) { "ssid must not be blank" }
        require('\u0000' !in expectedSsid) { "ssid must not contain U+0000" }
        require('\u0000' !in passphrase) { "passphrase must not contain U+0000" }
        require(passphrase.isEmpty() || passphrase.length in 8..63) {
            "passphrase must be empty or between 8 and 63 characters"
        }
    }

    @SuppressLint("WifiManagerPotentialLeak")
    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ClientNetworkManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val deadlineNanos = deadlineAfter(timeoutMillis)
        val joinedInPlace = isJoinedToTargetNetwork()
        if (!joinedInPlace) {
            ensureNetworkAndConnect()
        }

        var lastReason = "the station interface had no usable IPv4 address"
        while (true) {
            check(!closed) { "ClientNetworkManager is closed" }
            val connection = wifiManager.connectionInfo
            val match = clientNetworkMatch(connection?.ssid, expectedSsid)
            val localInterface = findStationInterface()
            val address = localInterface?.let {
                wirelessHostAddress(
                    Collections.list(it.networkInterface.inetAddresses),
                    it.index,
                    preferIpv4 = true,
                )
            }
            if (localInterface != null && address is Inet4Address && match != false) {
                if (match == null) {
                    onDiagnostic("Client network SSID is hidden by firmware; " +
                        "accepting the network that was joined before start")
                }
                val frequencyMHz = connection?.frequency?.takeIf { it > 0 }
                onDiagnostic(
                    "Client network ready iface=${localInterface.networkInterface.name} " +
                        "address=$address frequencyMHz=$frequencyMHz match=$match",
                )
                return WirelessHotspotInfo(
                    ssid = expectedSsid,
                    passphrase = passphrase,
                    security = if (passphrase.isEmpty()) {
                        Iap2WirelessSecurity.NONE
                    } else {
                        Iap2WirelessSecurity.WPA_WPA2
                    },
                    channel = frequencyMHz?.let(::wifiFrequencyMhzToChannel) ?: 0,
                    frequencyMHz = frequencyMHz,
                    bssid = connection?.bssid?.takeUnless {
                        it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" || it == "<unknown>"
                    },
                    interfaceName = localInterface.networkInterface.name,
                    hostAddress = address,
                    bandLabel = frequencyMHz?.let(::bandLabel) ?: "Auto",
                    backend = WirelessHotspotBackend.CLIENT_NETWORK,
                )
            }
            lastReason = when {
                match == false -> "the device is connected to a different network"
                localInterface == null -> "the station interface was not found"
                else -> "the station interface has no usable IPv4 address"
            }
            val remainingNanos = remainingNanos(deadlineNanos)
            if (remainingNanos <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms waiting for the client network: " +
                        lastReason,
                )
            }
            sleep(minOf(remainingNanos, INTERFACE_POLL_NANOS))
        }
    }

    override fun close() {
        closed = true
    }

    /** True when the device is already associated with the expected network. */
    private fun isJoinedToTargetNetwork(): Boolean {
        val connection = wifiManager.connectionInfo ?: return false
        val dhcpAddress = wifiManager.dhcpInfo?.ipAddress ?: 0
        return clientNetworkMatch(connection.ssid, expectedSsid) == true && dhcpAddress != 0
    }

    @SuppressLint("WifiManagerLeak")
    private fun ensureNetworkAndConnect() {
        val netId = findOrAddNetwork()
        if (!wifiManager.enableNetwork(netId, true)) {
            throw IOException("Android refused to enable the client network (netId=$netId)")
        }
        if (!wifiManager.reconnect()) {
            throw IOException("Android refused to start connecting to the client network")
        }
        onDiagnostic("Client network connect requested netId=$netId")
    }

    private fun findOrAddNetwork(): Int {
        val configured = try {
            wifiManager.configuredNetworks
        } catch (_: SecurityException) {
            null
        }
        configured?.firstOrNull { unquoteSsid(it.SSID) == expectedSsid }?.let { return it.networkId }
        val configuration = WifiConfiguration().apply {
            SSID = "\"$expectedSsid\""
            if (passphrase.isEmpty()) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = "\"$passphrase\""
            }
        }
        val netId = wifiManager.addNetwork(configuration)
        if (netId == -1) {
            throw IOException(
                "Android refused to save the client network '$expectedSsid'; " +
                    "check that Wi-Fi is on",
            )
        }
        return netId
    }

    private class StationInterface(
        val networkInterface: NetworkInterface,
        val index: Int,
    )

    /**
     * The station lives on the active network's interface (usually wlan0). Falls back to any
     * usable wlan* interface when the active network is not readable.
     */
    private fun findStationInterface(): StationInterface? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: SocketException) {
            null
        } ?: return null
        val all = Collections.list(interfaces)
        val primaryName = connectivityManager?.activeNetwork
            ?.let { connectivityManager.getLinkProperties(it)?.interfaceName }
        primaryName?.let { name ->
            all.firstOrNull { it.name == name && it.isUp && !it.isLoopback }?.let {
                return StationInterface(it, it.index)
            }
        }
        val fallback = all
            .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
            .maxByOrNull { candidate ->
                candidate.inetAddresses.toList().count { it is Inet4Address && !it.isLoopbackAddress }
            } ?: return null
        return StationInterface(fallback, fallback.index)
    }

    private fun bandLabel(frequencyMHz: Int): String = when (frequencyMHz) {
        in 2400..2500 -> "2.4 GHz"
        in 5150..5895 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "Unknown band"
    }

    private fun sleep(nanos: Long) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for the client network", interrupted)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingNanos(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val INTERFACE_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(250)
    }
}

/** WifiInfo hides the SSID without location permission; Android quotes configured SSIDs. */
internal const val CLIENT_NETWORK_UNKNOWN_SSID = "<unknown ssid>"

/** Strips the quotes Android wraps around configured network SSIDs. */
internal fun unquoteSsid(value: String?): String? = when {
    value == null -> null
    value == CLIENT_NETWORK_UNKNOWN_SSID -> null
    value.length >= 2 && value.first() == '"' && value.last() == '"' ->
        value.substring(1, value.length - 1)
    else -> value
}

/**
 * True when Android reports the expected SSID, null when firmware hides it (then the caller may
 * trust the network it joined itself), false when another network is live.
 */
internal fun clientNetworkMatch(reportedSsid: String?, expectedSsid: String): Boolean? =
    when (val ssid = unquoteSsid(reportedSsid)) {
        expectedSsid -> true
        null -> null
        else -> false
    }
