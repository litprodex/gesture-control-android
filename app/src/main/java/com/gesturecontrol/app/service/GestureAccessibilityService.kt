package com.gesturecontrol.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.gesturecontrol.app.model.ActionType

/** Wstrzykuje gesty dotykowe (swipe, tap) i akcje systemowe do dowolnej aplikacji. */
class GestureAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun perform(action: ActionType): Boolean {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        return when (action) {
            ActionType.SWIPE_UP -> swipe(w / 2, h * 0.72f, w / 2, h * 0.28f)
            ActionType.SWIPE_DOWN -> swipe(w / 2, h * 0.28f, w / 2, h * 0.72f)
            ActionType.SWIPE_LEFT -> swipe(w * 0.85f, h / 2, w * 0.15f, h / 2)
            ActionType.SWIPE_RIGHT -> swipe(w * 0.15f, h / 2, w * 0.85f, h / 2)
            ActionType.DOUBLE_TAP -> taps(w / 2, h / 2, count = 2)
            ActionType.TAP_CENTER -> taps(w / 2, h / 2, count = 1)
            ActionType.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ActionType.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            ActionType.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            ActionType.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            ActionType.SCREENSHOT ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            ActionType.LOCK_SCREEN ->
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            else -> false
        }
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 220): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun taps(x: Float, y: Float, count: Int): Boolean {
        val builder = GestureDescription.Builder()
        repeat(count) { i ->
            val p = Path().apply { moveTo(x, y) }
            builder.addStroke(GestureDescription.StrokeDescription(p, i * 140L, 40L))
        }
        return dispatchGesture(builder.build(), null, null)
    }

    companion object {
        @Volatile var instance: GestureAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, GestureAccessibilityService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
