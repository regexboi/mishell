package ai.mishell.app

import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import java.lang.ref.WeakReference
import java.util.WeakHashMap

private const val ULTRA_DIM_BRIGHTNESS = 0.01f
private const val INTERACTION_BRIGHT_TIMEOUT_MS = 30_000L
private val pendingDimRunnables = WeakHashMap<Window, Runnable>()
private val initializedWindows = WeakHashMap<Window, Boolean>()

internal fun AppCompatActivity.applyAlwaysOnUltraDimMode() {
    val activeWindow = window
    if (AppSettings.isAlwaysOnUltraDimEnabled(this)) {
        activeWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (initializedWindows[activeWindow] != true) {
            initializedWindows[activeWindow] = true
            brightenTemporarilyAfterInteraction()
        } else if (pendingDimRunnables.containsKey(activeWindow)) {
            setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        } else {
            setWindowBrightness(ULTRA_DIM_BRIGHTNESS)
        }
    } else {
        clearDisplayPowerModeTimer()
        initializedWindows.remove(activeWindow)
        activeWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }
}

internal fun AppCompatActivity.onDisplayTouchInteraction(event: MotionEvent) {
    if (!AppSettings.isAlwaysOnUltraDimEnabled(this)) {
        return
    }
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN,
        MotionEvent.ACTION_MOVE,
        MotionEvent.ACTION_POINTER_DOWN,
        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_POINTER_UP -> brightenTemporarilyAfterInteraction()
    }
}

internal fun AppCompatActivity.onDisplayForegrounded() {
    if (!AppSettings.isAlwaysOnUltraDimEnabled(this)) {
        return
    }
    brightenTemporarilyAfterInteraction()
}

internal fun AppCompatActivity.clearDisplayPowerModeTimer() {
    val activeWindow = window
    val pendingDimRunnable = pendingDimRunnables.remove(activeWindow)
    if (pendingDimRunnable != null) {
        activeWindow.decorView.removeCallbacks(pendingDimRunnable)
    }
    initializedWindows.remove(activeWindow)
}

private fun AppCompatActivity.brightenTemporarilyAfterInteraction() {
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)

    val decorView = window.decorView
    val activeWindow = window
    val existingRunnable = pendingDimRunnables.remove(activeWindow)
    if (existingRunnable != null) {
        decorView.removeCallbacks(existingRunnable)
    }

    val activityRef = WeakReference(this)
    val dimRunnable = Runnable {
        pendingDimRunnables.remove(activeWindow)
        val activity = activityRef.get() ?: return@Runnable
        if (!AppSettings.isAlwaysOnUltraDimEnabled(activity)) {
            return@Runnable
        }
        activity.setWindowBrightness(ULTRA_DIM_BRIGHTNESS)
    }
    pendingDimRunnables[activeWindow] = dimRunnable
    decorView.postDelayed(dimRunnable, INTERACTION_BRIGHT_TIMEOUT_MS)
}

private fun AppCompatActivity.setWindowBrightness(targetBrightness: Float) {
    val currentAttributes = window.attributes
    if (currentAttributes.screenBrightness == targetBrightness) {
        return
    }
    currentAttributes.screenBrightness = targetBrightness
    window.attributes = currentAttributes
}
