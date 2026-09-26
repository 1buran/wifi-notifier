package com.buran.wifinotifier

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager

/**
 * Everything the app knows about the state the network name depends on.
 *
 * Android hides the SSID behind a "nearby Wi-Fi devices" permission plus, in
 * practice, precise location with location services turned on. The app cannot
 * switch location services on by itself — no API for that exists since
 * Android 4.4 — so the best it can do is name the exact thing that is missing
 * and offer a shortcut to the screen where it can be changed.
 */
internal object Permissions {

    /** What stands between the app and the network name. */
    enum class Fix { LOCATION_SERVICES, PRECISE_LOCATION, LOCATION_PERMISSION, UNKNOWN }

    fun isGranted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Whether any of the two permissions that may reveal the SSID is granted. */
    fun canReadSsid(context: Context): Boolean =
        isGranted(context, Manifest.permission.NEARBY_WIFI_DEVICES) ||
            isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return runCatching { lm.isLocationEnabled }.getOrDefault(false)
    }

    /** Everything that has to be granted for the app to work at all. */
    fun required(): List<String> = listOf(
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    /**
     * What the user has to change to see the network name.
     *
     * The order matters: with any location permission at all the system switch
     * comes first (without it nothing is readable), then the precise/approximate
     * choice, then the permission itself.
     */
    fun fixForHiddenName(context: Context): Fix {
        val precise = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val approximate = isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

        return when {
            (precise || approximate) && !isLocationEnabled(context) -> Fix.LOCATION_SERVICES
            approximate && !precise -> Fix.PRECISE_LOCATION
            !precise -> Fix.LOCATION_PERMISSION
            else -> Fix.UNKNOWN
        }
    }

    fun hintRes(fix: Fix): Int = when (fix) {
        Fix.LOCATION_SERVICES -> R.string.hint_location_services
        Fix.PRECISE_LOCATION -> R.string.hint_precise_location
        Fix.LOCATION_PERMISSION -> R.string.hint_location_permission
        Fix.UNKNOWN -> R.string.hint_ssid_unavailable
    }

    /**
     * A one-line diagnostic for the log: without it a report of "not connected"
     * cannot be told apart from a permission problem on the user's device.
     */
    fun describe(context: Context): String =
        "nearby=${isGranted(context, Manifest.permission.NEARBY_WIFI_DEVICES)} " +
            "fine=${isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)} " +
            "coarse=${isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)} " +
            "locationEnabled=${isLocationEnabled(context)}"
}
