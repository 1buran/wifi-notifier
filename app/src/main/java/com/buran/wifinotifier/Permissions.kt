package com.buran.wifinotifier

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager

/**
 * Everything the app knows about the permissions the network name depends on.
 *
 * Android hides the SSID behind a "nearby Wi-Fi devices" permission plus,
 * in practice, location: the exact location permission on most builds, the
 * approximate one is not enough. When someone picks "approximate" in the
 * system dialog the app still has a location permission, so nothing looks
 * broken — but the name stays hidden. That case has to be recognised and
 * explained, which is what the hint below is for.
 */
internal object Permissions {

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
     * What the user has to change to see the network name, as a string resource.
     * Only meaningful when Wi-Fi is joined but the system hides the SSID.
     */
    fun hiddenSsidHint(context: Context): Int = when {
        !isLocationEnabled(context) -> R.string.hint_location_services
        !isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
            isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION) -> R.string.hint_precise_location
        !isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) -> R.string.hint_location_permission
        else -> R.string.hint_ssid_unavailable
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
