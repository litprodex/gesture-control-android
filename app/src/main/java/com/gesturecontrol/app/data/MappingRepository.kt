package com.gesturecontrol.app.data

import android.content.Context
import com.gesturecontrol.app.model.ActionType
import com.gesturecontrol.app.model.GestureType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Jeden wiersz przypisania – taki sam kształt jak w API serwera. */
data class MappingRecord(
    val gesture: GestureType,
    val action: ActionType,
    val appPackage: String?,
    val appLabel: String?,
    val updatedAt: Long,
)

/**
 * Przechowuje przypisania gest -> akcja (SharedPreferences) i udostępnia je jako StateFlow.
 * Każdy gest ma znacznik czasu ostatniej zmiany – potrzebny do synchronizacji („ostatni zapis wygrywa”).
 */
class MappingRepository private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("gesture_mappings", Context.MODE_PRIVATE)
    private val _mappings = MutableStateFlow(load())
    val mappings: StateFlow<Map<GestureType, ActionType>> = _mappings

    /** Dla akcji „Otwórz aplikację”: gest -> nazwa pakietu wybranej aplikacji. */
    private val _apps = MutableStateFlow(loadApps())
    val apps: StateFlow<Map<GestureType, String>> = _apps

    /** Wywoływane po każdej zmianie zrobionej przez użytkownika (null = zmieniło się wszystko). */
    @Volatile var onLocalChange: ((GestureType?) -> Unit)? = null

    private fun loadApps(): Map<GestureType, String> = GestureType.entries
        .mapNotNull { g -> prefs.getString(g.name + APP_SUFFIX, null)?.let { g to it } }
        .toMap()

    private fun load(): Map<GestureType, ActionType> = GestureType.entries.associateWith { g ->
        prefs.getString(g.name, null)
            ?.let { runCatching { ActionType.valueOf(it) }.getOrNull() }
            ?: DEFAULTS[g] ?: ActionType.NONE
    }

    fun appFor(gesture: GestureType): String? = _apps.value[gesture]

    /** Nazwa aplikacji zapamiętana przy wyborze (przydaje się, gdy apki brak na tym telefonie). */
    fun appLabelFor(gesture: GestureType): String? = prefs.getString(gesture.name + LABEL_SUFFIX, null)

    fun actionFor(gesture: GestureType): ActionType = _mappings.value[gesture] ?: ActionType.NONE

    /** Przypisuje „Otwórz aplikację” z konkretnym pakietem. */
    fun setApp(gesture: GestureType, packageName: String) {
        writeLocal(gesture, ActionType.OPEN_APP, packageName, AppList.label(context, packageName), now())
        onLocalChange?.invoke(gesture)
    }

    fun set(gesture: GestureType, action: ActionType) {
        writeLocal(gesture, action, null, null, now())
        onLocalChange?.invoke(gesture)
    }

    /** Przywraca domyślne – z nowym znacznikiem czasu, żeby zmiana dotarła też na inne telefony. */
    fun resetToDefaults() {
        val t = now()
        GestureType.entries.forEach { g -> writeLocal(g, DEFAULTS[g] ?: ActionType.NONE, null, null, t) }
        onLocalChange?.invoke(null)
    }

    // ---------- synchronizacja

    fun export(): List<MappingRecord> = GestureType.entries.map { g ->
        val action = actionFor(g)
        MappingRecord(
            g, action,
            if (action == ActionType.OPEN_APP) appFor(g) else null,
            if (action == ActionType.OPEN_APP) appLabelFor(g) else null,
            prefs.getLong(g.name + TS_SUFFIX, 0L),
        )
    }

    fun record(gesture: GestureType): MappingRecord = export().first { it.gesture == gesture }

    /** Przyjmuje dane z serwera: nadpisuje tylko to, co jest nowsze od lokalnego. Nie wywołuje onLocalChange. */
    fun applyRemote(records: List<MappingRecord>) {
        records.forEach { r ->
            if (r.updatedAt > prefs.getLong(r.gesture.name + TS_SUFFIX, 0L)) {
                if (r.action == ActionType.OPEN_APP && r.appPackage.isNullOrBlank()) return@forEach
                writeLocal(r.gesture, r.action, r.appPackage, r.appLabel, r.updatedAt)
            }
        }
    }

    private fun writeLocal(g: GestureType, action: ActionType, pkg: String?, label: String?, ts: Long) {
        prefs.edit().apply {
            putString(g.name, action.name)
            putLong(g.name + TS_SUFFIX, ts)
            if (action == ActionType.OPEN_APP && pkg != null) {
                putString(g.name + APP_SUFFIX, pkg)
                if (label != null) putString(g.name + LABEL_SUFFIX, label) else remove(g.name + LABEL_SUFFIX)
            } else {
                remove(g.name + APP_SUFFIX); remove(g.name + LABEL_SUFFIX)
            }
        }.apply()
        _mappings.value = _mappings.value + (g to action)
        _apps.value = if (action == ActionType.OPEN_APP && pkg != null) _apps.value + (g to pkg) else _apps.value - g
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val APP_SUFFIX = "__app"
        private const val LABEL_SUFFIX = "__label"
        private const val TS_SUFFIX = "__ts"

        val DEFAULTS = mapOf(
            GestureType.MOUTH_OPEN_ONCE to ActionType.SWIPE_UP,
            GestureType.MOUTH_OPEN_TWICE to ActionType.MEDIA_NEXT,
            GestureType.MOUTH_OPEN_THRICE to ActionType.MEDIA_PREVIOUS,
            GestureType.OPEN_PALM to ActionType.MEDIA_PLAY_PAUSE,
            GestureType.VICTORY to ActionType.SWIPE_UP,
            GestureType.POINTING_UP to ActionType.SWIPE_DOWN,
            GestureType.THUMB_UP to ActionType.DOUBLE_TAP,
            GestureType.CLOSED_FIST to ActionType.BACK,
        )

        @Volatile private var instance: MappingRepository? = null

        fun get(context: Context): MappingRepository =
            instance ?: synchronized(this) {
                instance ?: MappingRepository(context.applicationContext).also { instance = it }
            }
    }
}
