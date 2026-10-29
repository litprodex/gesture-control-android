package com.gesturecontrol.app.service

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import android.widget.Toast
import com.gesturecontrol.app.data.AppList
import com.gesturecontrol.app.model.ActionType

/** Wykonuje akcję. Multimedia przez AudioManager, resztę przez usługę dostępności. */
class ActionExecutor(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)

    fun execute(action: ActionType, appPackage: String? = null): Boolean = when (action) {
        ActionType.NONE -> true
        ActionType.OPEN_APP -> openApp(appPackage)
        ActionType.MEDIA_NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        ActionType.MEDIA_PREVIOUS -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        ActionType.MEDIA_PLAY_PAUSE -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        ActionType.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
        ActionType.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
        else -> {
            val svc = GestureAccessibilityService.instance
            if (svc == null) {
                Toast.makeText(
                    context,
                    "Włącz usługę dostępności „Gesty”, żeby ta akcja działała",
                    Toast.LENGTH_SHORT
                ).show()
                false
            } else svc.perform(action)
        }
    }

    /**
     * Android 10+ blokuje uruchamianie aktywności z tła. Wyjątkiem jest aplikacja z aktywną
     * usługą dostępności, dlatego startujemy przez nią.
     */
    private fun openApp(pkg: String?): Boolean {
        if (pkg == null) return false
        val intent = AppList.launchIntent(context, pkg)
        if (intent == null) {
            toast("Nie znaleziono aplikacji – mogła zostać odinstalowana")
            return false
        }
        val starter: Context = GestureAccessibilityService.instance ?: context
        return try {
            starter.startActivity(intent)
            if (GestureAccessibilityService.instance == null)
                toast("Włącz usługę dostępności „Gesty”, żeby otwieranie aplikacji działało w tle")
            true
        } catch (e: Exception) {
            toast("Nie udało się otworzyć aplikacji")
            false
        }
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    private fun mediaKey(code: Int): Boolean {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return true
    }

    private fun volume(direction: Int): Boolean {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return true
    }
}
