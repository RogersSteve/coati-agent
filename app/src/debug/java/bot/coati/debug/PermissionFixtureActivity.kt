package bot.coati.debug

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView

/**
 * Debug source set only. Shows the platform runtime-permission dialog on demand, so device tests
 * can check what the accessibility service reads from it (Android 14 hides such "accessibility
 * data sensitive" windows from services that are not accessibility tools). The contacts
 * permission is only requested, never used; it is a runtime permission on every Android version.
 *
 * adb shell pm revoke bot.coati android.permission.READ_CONTACTS
 * adb shell pm clear-permission-flags bot.coati android.permission.READ_CONTACTS user-set user-fixed
 * adb shell am start -n bot.coati/.debug.PermissionFixtureActivity
 */
class PermissionFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Permission fixture" })
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "Permission fixture result: granted=$granted")
    }

    private companion object {
        const val TAG = "CoatiDebugAgent"
        const val REQUEST_CODE = 1
    }
}
