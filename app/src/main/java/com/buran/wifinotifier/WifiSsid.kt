package com.buran.wifinotifier

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager

/**
 * The only place in the app that asks the system for the Wi-Fi network name.
 */
internal object WifiSsid {

    /** Network name from [NetworkCapabilities] - the recommended way since Android 13. */
    fun fromCapabilities(caps: NetworkCapabilities?): String? {
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val info = caps.transportInfo as? WifiInfo ?: return null
        return sanitize(info.ssid)
    }

    /**
     * Name of the network the phone is connected to, or null when there is no
     * connection (or the system hides the name because of a missing permission).
     */
    fun current(context: Context): String? {
        // The usual path: Wi-Fi is the active network and the system tells us
        // the SSID right away.
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        fromCapabilities(caps)?.let { return it }

        // The active network is not always Wi-Fi: a phone may sit on Ethernet
        // or mobile data with a Wi-Fi network joined alongside, and an emulator
        // reports its virtual Wi-Fi this way as well.
        return connectedWifi(context)
    }

    /**
     * The SSID is taken from WifiManager only when Wi-Fi is really associated:
     * [WifiManager.getConnectionInfo] keeps the last network around after
     * a disconnect, so the name alone would be misleading.
     */
    private fun connectedWifi(context: Context): String? {
        val wm = context.getSystemService(WifiManager::class.java) ?: return null
        if (runCatching { wm.isWifiEnabled }.getOrDefault(false) != true) return null

        @Suppress("DEPRECATION")
        val info = runCatching { wm.connectionInfo }.getOrNull() ?: return null
        if (info.supplicantState != SupplicantState.COMPLETED) return null
        return sanitize(info.ssid)
    }

    /**
     * Turns an SSID into a readable form.
     * Returns null for an empty value and for the placeholders the system uses
     * for a hidden SSID ("<unknown ssid>", "0x").
     */
    fun sanitize(raw: String?): String? {
        val value = raw?.trim()?.removeSurrounding("\"")?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.equals(WifiManager.UNKNOWN_SSID, ignoreCase = true)) return null
        if (value.startsWith("0x")) return null
        return value
    }
}
