package com.gesturecontrol.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SettingsRecord(
    val fps: Int,
    val pauseOnScreenOff: Boolean,
    val doomScroll: Boolean,
    val mouthLevel: Int,
    val updatedAt: Long,
)

/** Ustawienia (bateria, usta). Jeden znacznik czasu dla całości – do synchronizacji. */
class SettingsRepository private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Wywoływane po każdej zmianie zrobionej przez użytkownika. */
    @Volatile var onLocalChange: (() -> Unit)? = null

    private fun touched() {
        prefs.edit().putLong(KEY_TS, System.currentTimeMillis()).apply()
        onLocalChange?.invoke()
    }

    fun export() = SettingsRecord(fps.value, pauseOnScreenOff.value, doomScroll.value, mouthLevel.value, prefs.getLong(KEY_TS, 0L))

    /** Dane z serwera – tylko jeśli nowsze. Nie wywołuje onLocalChange. */
    fun applyRemote(r: SettingsRecord) {
        if (r.updatedAt <= prefs.getLong(KEY_TS, 0L)) return
        val f = r.fps.coerceIn(MIN_FPS, MAX_FPS); val m = r.mouthLevel.coerceIn(0, 2)
        prefs.edit().putInt(KEY_FPS, f).putBoolean(KEY_PAUSE, r.pauseOnScreenOff)
            .putBoolean(KEY_DOOM, r.doomScroll).putInt(KEY_MOUTH_LEVEL, m).putLong(KEY_TS, r.updatedAt).apply()
        _fps.value = f; _pauseOnScreenOff.value = r.pauseOnScreenOff; _doomScroll.value = r.doomScroll; _mouthLevel.value = m
    }

    private val _fps = MutableStateFlow(prefs.getInt(KEY_FPS, DEFAULT_FPS))
    val fps: StateFlow<Int> = _fps

    private val _pauseOnScreenOff = MutableStateFlow(prefs.getBoolean(KEY_PAUSE, true))
    val pauseOnScreenOff: StateFlow<Boolean> = _pauseOnScreenOff

    private val _doomScroll = MutableStateFlow(prefs.getBoolean(KEY_DOOM, false))
    /** Tryb doom scrolling: tylko 1× otwarcie ust, natychmiast i przy minimalnym otwarciu. */
    val doomScroll: StateFlow<Boolean> = _doomScroll

    private val _mouthLevel = MutableStateFlow(prefs.getInt(KEY_MOUTH_LEVEL, 1))
    /** 0 = lekko, 1 = średnio, 2 = mocno. */
    val mouthLevel: StateFlow<Int> = _mouthLevel

    fun setDoomScroll(value: Boolean) {
        prefs.edit().putBoolean(KEY_DOOM, value).apply()
        _doomScroll.value = value
        touched()
    }

    fun setMouthLevel(value: Int) {
        val v = value.coerceIn(0, 2)
        prefs.edit().putInt(KEY_MOUTH_LEVEL, v).apply()
        _mouthLevel.value = v
        touched()
    }

    fun setFps(value: Int) {
        val v = value.coerceIn(MIN_FPS, MAX_FPS)
        prefs.edit().putInt(KEY_FPS, v).apply()
        _fps.value = v
        touched()
    }

    fun setPauseOnScreenOff(value: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSE, value).apply()
        _pauseOnScreenOff.value = value
        touched()
    }

    companion object {
        const val MIN_FPS = 5
        const val MAX_FPS = 30
        const val DEFAULT_FPS = 15
        private const val KEY_FPS = "fps"
        private const val KEY_PAUSE = "pause_on_screen_off"
        private const val KEY_DOOM = "doom_scroll"
        private const val KEY_MOUTH_LEVEL = "mouth_level"
        private const val KEY_TS = "updated_at"
        val MOUTH_LEVEL_NAMES = listOf("Lekko", "Średnio", "Mocno")

        @Volatile private var instance: SettingsRepository? = null
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
