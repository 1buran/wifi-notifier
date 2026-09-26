package com.buran.wifinotifier

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * The app has no screen. This activity only exists to obtain the permissions
 * and to start the watching service, then it closes itself right away.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        step()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return

        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast(getString(R.string.toast_no_permission))
        }
        onPermissionsReady()
    }

    private fun step() {
        val missing = neededPermissions().filter { !isGranted(it) }
        if (missing.isEmpty()) {
            onPermissionsReady()
        } else {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun onPermissionsReady() {
        // Restart the service so that it registers its callbacks with the
        // permissions already granted: otherwise the system keeps hiding
        // the network name.
        stopService(Intent(this, WifiWatchService::class.java))
        startForegroundService(Intent(this, WifiWatchService::class.java))
        toast(getString(R.string.toast_started))
        finish()
    }

    /**
     * Notifications and access to Wi-Fi are required.
     *
     * Location is requested together with NEARBY_WIFI_DEVICES: on Android 13+
     * the network name is officially available with NEARBY_WIFI_DEVICES alone,
     * but in practice (and on many firmware builds) the system returns the SSID
     * only when the precise location permission is granted as well. Both
     * location permissions are declared so the system offers the
     * precise/approximate choice; picking approximate leaves the name hidden,
     * and the next launch asks for precise again.
     */
    private fun neededPermissions(): List<String> = Permissions.required()

    private fun isGranted(permission: String): Boolean = Permissions.isGranted(this, permission)

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
    }
}
