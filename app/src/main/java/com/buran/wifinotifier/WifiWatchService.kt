package com.buran.wifinotifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.util.Collections
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

    /**
     * The permission state as a string, from [Permissions.describe]: any change
     * in it (precise location appearing, location services going off) has to be
     * noticed, not just "some Wi-Fi permission is granted".
     */
    private var permissionSnapshot: String? = null

    /**
     * The Wi-Fi networks the connectivity callbacks currently report, whether
     * or not their names are readable. The [Network] object is the identity the
     * system uses to say when a network has disappeared.
     */
    private val wifiNetworks: MutableSet<Network> =
        Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * The last name read for each reported Wi-Fi network.
     *
     * A name is deliberately kept when the system later starts hiding it: that
     * is what happens when location services are switched off after the name
     * was read, and dropping the entry there would make the app forget a name
     * it has already seen. An entry disappears only with the network itself
     * ([ConnectivityManager.NetworkCallback.onLost]), which is also what
     * happens when the phone moves to another network.
     */
    private val knownNames = ConcurrentHashMap<Network, String>()

    /** The foreground service type that worked, and what the notification shows. */
    private var foregroundType: Int? = null
    private var ongoingText: String? = null
    private var ongoingFix: Permissions.Fix? = null

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
        knownNames.clear()
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
                wifiNetworks.add(network)
                refresh("onAvailable")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                wifiNetworks.add(network)
                // A redacted SSID here means the name is unavailable right now,
                // not that the network changed: keep the last name read for it
                // (see knownNames) instead of forgetting it.
                WifiSsid.fromCapabilities(caps)?.let { knownNames[network] = it }
                refresh("onCapabilitiesChanged")
            }

            override fun onLost(network: Network) {
                wifiNetworks.remove(network)
                knownNames.remove(network)
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

    /**
     * Serialized: the poller runs on the main thread while the callbacks arrive
     * on binder threads, and two overlapping refreshes both compare against the
     * same stale state, which re-registers the callback twice and updates the
     * notification twice.
     */
    @Synchronized
    private fun refresh(reason: String) {
        val canReadSsid = Permissions.canReadSsid(this)
        val permissionState = Permissions.describe(this)
        if (permissionSnapshot != null && permissionSnapshot != permissionState) {
            Log.i(TAG, "permissions changed: $permissionSnapshot -> $permissionState")
            registerWifiCallback()
            // With no location permission the location type cannot be started
            // and the service runs as specialUse, which in turn keeps the SSID
            // hidden. Upgrade back to the preferred type as soon as the
            // permission appears, or the app would stay stuck on "name hidden"
            // until it is restarted by hand.
            if (foregroundType != FOREGROUND_SERVICE_TYPES.first()) {
                Log.i(TAG, "retrying the preferred foreground service type")
                startForegroundWith(ongoingText ?: getString(R.string.state_detecting), ongoingFix)
            }
        }
        permissionSnapshot = permissionState

        val ssid = currentSsid()
        // Joined to a network while the name stays unreadable: the user has to
        // fix something, and telling them "not connected" would be a lie.
        val associated = ssid == null && WifiSsid.isAssociated(this)

        val state = when {
            ssid != null -> "$STATE_CONNECTED:$ssid"
            associated && !canReadSsid -> STATE_NO_PERMISSION
            associated -> STATE_NAME_HIDDEN
            else -> STATE_DISCONNECTED
        }
        if (state == shownState) return
        shownState = state
        Log.i(TAG, "Wi-Fi state: $state ($reason) [${Permissions.describe(this)}]")

        if (ssid != null) {
            updateOngoing(getString(R.string.state_connected, ssid))
            if (ssid != alertedSsid) {
                alertedSsid = ssid
                prefs.edit().putString(KEY_LAST_ALERTED, ssid).apply()
                // Switched off for now: the silent ongoing notification above
                // already carries the name, and no sound is wanted. The alert
                // is kept in the code for a future "notify me" switch, see
                // ALERTS_ENABLED.
                if (ALERTS_ENABLED) notifyConnected(ssid)
            }
            return
        }

        // No network name: joining the next one must alert again.
        alertedSsid = null
        prefs.edit().remove(KEY_LAST_ALERTED).apply()

        val fix = if (associated) Permissions.fixForHiddenName(this) else null
        val hint = getString(Permissions.hintRes(fix ?: Permissions.Fix.UNKNOWN))
        when (state) {
            STATE_NO_PERMISSION -> updateOngoing(getString(R.string.state_no_permission, hint), fix)
            STATE_NAME_HIDDEN -> updateOngoing(getString(R.string.state_name_hidden, hint), fix)
            else -> updateOngoing(getString(R.string.state_disconnected))
        }
    }

    /**
     * The name of the network the phone is on, or null when it cannot be read.
     *
     * A name read earlier is reused for as long as the system keeps reporting
     * that network: switching location services off after the name was read must
     * not make the app forget it. Every remembered entry is validated against
     * the system first, so a network that went away cannot linger here.
     */
    private fun currentSsid(): String? {
        WifiSsid.readableSsid(this)?.let { ssid ->
            rememberName(ssid)
            return ssid
        }

        // The name is hidden right now: reuse the last name read for a network
        // the system still reports. A network that went away is dropped here as
        // well, so switching to another one cannot resurrect its name.
        for (network in wifiNetworks) {
            if (!isLiveWifi(network)) {
                wifiNetworks.remove(network)
                knownNames.remove(network)
                continue
            }
            knownNames[network]?.let { return it }
        }
        return null
    }

    /**
     * Ties a freshly read name to the Wi-Fi network it belongs to, so that it
     * can be reused while the system keeps hiding the name.
     *
     * The name may come from [WifiSsid.readableSsid] instead of a callback: the
     * active network is not always Wi-Fi (an emulator reports its virtual Wi-Fi
     * this way, and a phone may sit on mobile data with Wi-Fi joined alongside),
     * and then the callback-reported Wi-Fi network is the only identity there is.
     */
    private fun rememberName(ssid: String) {
        val active = runCatching { connectivity.activeNetwork }.getOrNull()
        if (active != null && isLiveWifi(active)) {
            wifiNetworks.add(active)
            knownNames[active] = ssid
            return
        }

        val candidates = wifiNetworks.filter { isLiveWifi(it) }
        if (candidates.size == 1) knownNames[candidates.first()] = ssid
    }

    private fun isLiveWifi(network: Network): Boolean =
        runCatching {
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)

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
        if (ALERTS_ENABLED) {
            notifications.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_EVENTS,
                    getString(R.string.channel_events_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = getString(R.string.channel_events_desc)
                }
            )
        } else {
            // Nothing posts to the alerting channel while the alert is off, so
            // it must not sit in the app's notification settings doing nothing.
            // Turning ALERTS_ENABLED back on recreates it above.
            notifications.deleteNotificationChannel(CHANNEL_EVENTS)
        }
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
    private fun startForegroundWith(text: String, fix: Permissions.Fix? = null) {
        Log.i(TAG, "ongoing notification: $text")
        val notification = buildOngoing(text, fix)
        ongoingText = text
        ongoingFix = fix

        // The location type is rejected while the user has not granted location
        // at all ("requires any of COARSE/FINE"), and that is an expected state:
        // complain loudly only when no type works. The fallback keeps the
        // service alive so it can explain what to fix.
        FOREGROUND_SERVICE_TYPES.forEachIndexed { index, type ->
            try {
                startForeground(ONGOING_ID, notification, type)
                foregroundType = type
                return
            } catch (e: Exception) {
                if (index == FOREGROUND_SERVICE_TYPES.lastIndex) {
                    Log.e(TAG, "could not become a foreground service", e)
                } else {
                    Log.w(TAG, "foreground service type $type is not available: ${e.message}")
                }
            }
        }
        stopSelf()
    }

    private fun updateOngoing(text: String, fix: Permissions.Fix? = null) =
        startForegroundWith(text, fix)

    private fun buildOngoing(text: String, fix: Permissions.Fix?): Notification =
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
            .apply {
                // One tap on the body of the notification and one tap on the
                // action both go to the screen that matches the diagnosis: the
                // system location switch lives in its own settings screen,
                // everything else is the app permission screen. Without the
                // content intent a tap on the body would do nothing, and the
                // user would have to find and expand the action first.
                val fixIntent = fix?.let {
                    if (it == Permissions.Fix.LOCATION_SERVICES) {
                        locationSettingsPendingIntent()
                    } else {
                        appSettingsPendingIntent()
                    }
                }
                if (fixIntent != null) {
                    setContentIntent(fixIntent)
                    addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(this@WifiWatchService, R.drawable.ic_stat_wifi),
                            getString(
                                if (fix == Permissions.Fix.LOCATION_SERVICES) {
                                    R.string.action_location
                                } else {
                                    R.string.action_permissions
                                }
                            ),
                            fixIntent,
                        ).build()
                    )
                }
            }
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

    /** Opens the system screen where the app permissions can be changed. */
    private fun appSettingsPendingIntent(): PendingIntent {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        return PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Opens the screen with the system location switch. Apps cannot flip that
     * switch themselves, so this is as close as the notification can get: one
     * tap, then the watcher picks the name up on its next check.
     */
    private fun locationSettingsPendingIntent(): PendingIntent {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        return PendingIntent.getActivity(
            this,
            2,
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
         * Whether joining a network also posts the separate alerting
         * notification on [CHANNEL_EVENTS].
         *
         * Currently off: the silent ongoing notification already shows the
         * name, and the user asked for one notification and no sound. The
         * alerting code and its channel are deliberately kept, so bringing the
         * alert back is this flag plus, later, a switch in the app.
         */
        private const val ALERTS_ENABLED = false

        /**
         * The real work is done by the system callbacks; polling is only
         * a safety net for a missed event, hence the long interval.
         */
        private const val POLL_INTERVAL_MS = 20_000L

        private const val PREFS_NAME = "wifi_notifier"
        private const val KEY_LAST_ALERTED = "last_alerted_ssid"

        private const val STATE_NO_PERMISSION = "no_permission"
        private const val STATE_NAME_HIDDEN = "name_hidden"
        private const val STATE_DISCONNECTED = "disconnected"
        private const val STATE_CONNECTED = "connected"

        /** Foreground service types in order of preference: primary, then fallback. */
        private val FOREGROUND_SERVICE_TYPES = intArrayOf(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }
}
