package ai.mishell.app

import android.content.pm.ActivityInfo
import androidx.appcompat.app.AppCompatActivity

internal fun AppCompatActivity.lockLandscapeToCurrentRotation() {
    val targetOrientation = if (AppSettings.isOrientationLockEnabled(this)) {
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    if (requestedOrientation != targetOrientation) {
        requestedOrientation = targetOrientation
    }
}
