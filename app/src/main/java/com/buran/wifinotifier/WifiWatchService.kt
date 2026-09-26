package com.buran.wifinotifier

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Does exactly one thing: notices which Wi-Fi network the phone connected to
 * and reports its name with a notification.
 *
 * The ongoing notification keeps the service alive (otherwise the system kills
 * a background process) and always shows the current network. A separate,
 * alerting notification is posted the moment a new network is joined.
 */
class WifiWatchService : Service() {

    private lateinit var connectivity: ConnectivityManager
    private lateinit var notifications: NotificationManager
    private lateinit var prefs: SharedPreferences

    private val handler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** State already reflected in the ongoing notification. */
    private var shownState: String? = null

    /** Network we have already notified about, so we do not repeat ourselves. */
    private var alertedSsid: String? = null

    /** Last known value of "the Wi-Fi permission is granted". */
    private var permissionSnapshot: Boolean? = null

    /** Wi-Fi networks the system currently reports as connected. */
    private val wifiNetworks = ConcurrentHashMap<Network, String>()

    /**
     * Safety net for the case when the system did not deliver a callback,
     * e.g. the service started after the connection was already established.
     */
    private val poller = object : Runnable {
        override fun run() {
            refresh("periodic check")
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(ConnectivityManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        alertedSsid = prefs.getString(KEY_LAST_ALERTED, null)

        createChannels()
        startForegroundSafely()
        registerWifiCallback()
        refresh("service start")
        handler.postDelayed(poller, POLL_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stopping on the notification action")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        wifiNetworks.clear()
        Log.i(TAG, "service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ----------------------------------------------------------------- watching

    private fun registerWifiCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refresh("onAvailable")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val ssid = WifiSsid.fromCapabilities(caps)
                if (ssid != null) wifiNetworks[network] = ssid else wifiNetworks.remove(network)
                refresh("onCapabilitiesChanged")
            }

            override fun onLost(network: Network) {
                wifiNetworks.remove(network)
                refresh("onLost")
            }
        }

        // Register again without the old callback: otherwise, after the user has
        // granted the permission, the system keeps handing out the SSID hidden
        // at the time of the first registration.
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = networkCallback
        runCatching { connectivity.registerNetworkCallback(request, networkCallback) }
            .onSuccess { Log.i(TAG, "network callback registered") }
            .onFailure { Log.w(TAG, "could not register the network callback", it) }
    }

    private fun refresh(reason: String) {
        val hasPermission = hasWifiPermission()
        if (permissionSnapshot != null && permissionSnapshot != hasPermission) {
            Log.i(TAG, "the permission set changed, re-registering the network callback")
            registerWifiCallback()
        }
        permissionSnapshot = hasPermission

        val ssid = if (hasPermission) currentSsid() else null

        val state = when {
            !hasPermission -> STATE_NO_PERMISSION
            ssid != null -> "$STATE_CONNECTED:$ssid"
            else -> STATE_DISCONNECTED
        }
        if (state == shownState) return
        shownState = state
        Log.i(TAG, "Wi-Fi state: $state ($reason)")

        if (ssid == null) {
            // Disconnected: joining the next network must alert again.
            alertedSsid = null
            prefs.edit().remove(KEY_LAST_ALERTED).apply()
            updateOngoing(getString(if (hasPermission) R.string.state_disconnected else R.string.state_no_permission))
        } else {
            updateOngoing(getString(R.string.state_connected, ssid))
            if (ssid != alertedSsid) {
                alertedSsid = ssid
                prefs.edit().putString(KEY_LAST_ALERTED, ssid).apply()
                notifyConnected(ssid)
            }
        }
    }

    private fun currentSsid(): String? {
        WifiSsid.current(this)?.let { return it }
        // The active network may be the mobile one while Wi-Fi is joined
        // alongside it, so we also look at the Wi-Fi networks the callbacks
        // have reported. Every entry is checked against the system first,
        // so a network that went away cannot linger here.
        for ((network, ssid) in wifiNetworks) {
            val alive = runCatching {
                connectivity.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }.getOrDefault(false)
            if (alive) return ssid
            wifiNetworks.remove(network)
        }
        return null
    }

    private fun hasWifiPermission(): Boolean =
        checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // -------------------------------------------------------------- notifications

    private fun createChannels() {
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CURRENT,
                getString(R.string.channel_current_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_current_desc)
                setShowBadge(false)
            }
        )
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                getString(R.string.channel_events_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.channel_events_desc)
            }
        )
    }

    private fun startForegroundSafely() {
        startForegroundWith(getString(R.string.state_detecting))
    }

    /**
     * The text of the ongoing notification is updated by calling
     * startForeground again: that is the only path that reliably updates
     * a notification owned by a foreground service.
     *
     * The location type is requested explicitly. Without it the system treats
     * the app as a background one and stops handing out the network name as
     * soon as the activity is closed: a "while in use" location permission
     * works for a service only when it is declared as a location service.
     * If such a service cannot be started we fall back to specialUse, and the
     * app keeps working while its screen is open.
     */
    private fun startForegroundWith(text: String) {
        Log.i(TAG, "ongoing notification: $text")
        val notification = buildOngoing(text)

        for (type in FOREGROUND_SERVICE_TYPES) {
            try {
                startForeground(ONGOING_ID, notification, type)
                return
            } catch (e: Exception) {
                Log.w(TAG, "could not start a foreground service of type $type", e)
            }
        }
        Log.e(TAG, "could not become a foreground service")
        stopSelf()
    }

    private fun updateOngoing(text: String) = startForegroundWith(text)

    private fun buildOngoing(text: String): Notification =
        Notification.Builder(this, CHANNEL_CURRENT)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle(getString(R.string.ongoing_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_wifi),
                    getString(R.string.action_stop),
                    stopPendingIntent(),
                ).build()
            )
            .build()

    private fun notifyConnected(ssid: String) {
        val notification = Notification.Builder(this, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_wifi)
            .setContentTitle(getString(R.string.event_title))
            .setContentText(ssid)
            .setAutoCancel(true)
            .build()

        runCatching { notifications.notify(EVENT_ID, notification) }
            .onFailure { Log.w(TAG, "could not post the connection notification", it) }
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, WifiWatchService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "WifiWatchService"

        const val ACTION_STOP = "com.buran.wifinotifier.action.STOP"

        private const val CHANNEL_CURRENT = "wifi_current"
        private const val CHANNEL_EVENTS = "wifi_events"
        private const val ONGOING_ID = 1
        private const val EVENT_ID = 2

        /**
         * The real work is done by the system callbacks; polling is only
         * a safety net for a missed event, hence the long interval.
         */
        private const val POLL_INTERVAL_MS = 20_000L

        private const val PREFS_NAME = "wifi_notifier"
        private const val KEY_LAST_ALERTED = "last_alerted_ssid"

        private const val STATE_NO_PERMISSION = "no_permission"
        private const val STATE_DISCONNECTED = "disconnected"
        private const val STATE_CONNECTED = "connected"

        /** Foreground service types in order of preference: primary, then fallback. */
        private val FOREGROUND_SERVICE_TYPES = intArrayOf(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }
}
