package com.gesturecontrol.app.sync

import android.content.Context
import android.os.Build
import android.util.Log
import com.gesturecontrol.app.BuildConfig
import com.gesturecontrol.app.auth.AuthManager
import com.gesturecontrol.app.data.MappingRecord
import com.gesturecontrol.app.data.MappingRepository
import com.gesturecontrol.app.data.SettingsRecord
import com.gesturecontrol.app.data.SettingsRepository
import com.gesturecontrol.app.model.ActionType
import com.gesturecontrol.app.model.GestureType
import com.gesturecontrol.app.net.ApiClient
import com.gesturecontrol.app.net.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed interface SyncState {
    /** Niezalogowany albo brak adresu serwera – synchronizacja nie działa, apka działa normalnie. */
    data class Off(val reason: String) : SyncState
    data object Syncing : SyncState
    data class Ok(val at: Long) : SyncState
    data class Failed(val message: String, val lastOk: Long?) : SyncState
}

/**
 * Synchronizacja przypisań i ustawień z serwerem.
 * Telefon zawsze działa na danych lokalnych; serwer jest tylko kopią wspólną dla wszystkich telefonów.
 */
class SyncManager private constructor(context: Context) {

    private val auth = AuthManager.get(context)
    private val mappings = MappingRepository.get(context)
    private val settings = SettingsRepository.get(context)
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    val deviceId: String = prefs.getString(KEY_DEVICE, null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }

    val api = ApiClient(auth, deviceId)

    private val _state = MutableStateFlow<SyncState>(offState())
    val state: StateFlow<SyncState> = _state

    init {
        mappings.onLocalChange = { g -> if (canSync()) scope.launch { push(g) } }
        settings.onLocalChange = { if (canSync()) scope.launch { pushSettings() } }
    }

    private fun canSync() = auth.user.value != null && api.isConfigured

    private fun offState(): SyncState = when {
        auth.user.value == null -> SyncState.Off("Niezalogowany")
        !api.isConfigured -> SyncState.Off("Serwer nie jest jeszcze skonfigurowany")
        else -> SyncState.Off("")
    }

    private fun lastOk(): Long? = prefs.getLong(KEY_LAST_OK, 0L).takeIf { it > 0 }

    /** Pełna synchronizacja: wysyła cały lokalny stan, serwer scala, wynik zapisujemy lokalnie. */
    fun syncNow() {
        if (!canSync()) { _state.value = offState(); return }
        scope.launch {
            mutex.withLock {
                _state.value = SyncState.Syncing
                try {
                    runCatching {
                        api.registerDevice("${Build.MANUFACTURER} ${Build.MODEL}".trim(), BuildConfig.VERSION_NAME)
                    }
                    val body = JSONObject()
                        .put("mappings", JSONArray(mappings.export().map { it.toJson() }))
                        .put("settings", settings.export().toJson())
                    applyServerState(api.postSync(body))
                    ok()
                } catch (e: Exception) {
                    fail(e)
                }
            }
        }
    }

    /** Wywołać po wylogowaniu. Dane lokalne zostają na telefonie. */
    fun onSignedOut() { _state.value = SyncState.Off("Niezalogowany") }

    private suspend fun push(g: GestureType?) {
        if (g == null) { syncNow(); return }
        try {
            val r = mappings.record(g)
            api.putMapping(g.name, r.toJson().apply { remove("gesture") })
            ok()
        } catch (e: Exception) { fail(e) }
    }

    private suspend fun pushSettings() {
        try {
            api.putSettings(settings.export().toJson())
            ok()
        } catch (e: Exception) { fail(e) }
    }

    private fun ok() {
        val t = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_OK, t).apply()
        _state.value = SyncState.Ok(t)
    }

    private fun fail(e: Exception) {
        Log.w(TAG, "Synchronizacja nieudana", e)
        val msg = when {
            e is ApiException && e.code == 401 -> "Sesja wygasła – zaloguj się ponownie"
            e is ApiException && e.code == 503 -> "Serwer nie ma jeszcze konfiguracji Firebase"
            e is ApiException && e.code >= 500 -> "Serwer chwilowo nie odpowiada"
            else -> "Brak połączenia z serwerem"
        }
        _state.value = SyncState.Failed(msg, lastOk())
    }

    private fun applyServerState(json: JSONObject) {
        val list = json.optJSONArray("mappings") ?: JSONArray()
        val records = (0 until list.length()).mapNotNull { i -> list.getJSONObject(i).toMappingRecord() }
        mappings.applyRemote(records)
        json.optJSONObject("settings")?.let { s ->
            settings.applyRemote(
                SettingsRecord(
                    s.getInt("fps"), s.getBoolean("pause_on_screen_off"), s.getBoolean("doom_scroll"),
                    s.getInt("mouth_level"), s.getLong("updated_at"),
                )
            )
        }
    }

    private fun MappingRecord.toJson() = JSONObject()
        .put("gesture", gesture.name)
        .put("action", action.name)
        .put("app_package", appPackage ?: JSONObject.NULL)
        .put("app_label", appLabel ?: JSONObject.NULL)
        .put("updated_at", updatedAt)

    private fun SettingsRecord.toJson() = JSONObject()
        .put("fps", fps)
        .put("pause_on_screen_off", pauseOnScreenOff)
        .put("doom_scroll", doomScroll)
        .put("mouth_level", mouthLevel)
        .put("updated_at", updatedAt)

    private fun JSONObject.toMappingRecord(): MappingRecord? {
        val g = runCatching { GestureType.valueOf(getString("gesture")) }.getOrNull() ?: return null
        val a = runCatching { ActionType.valueOf(getString("action")) }.getOrNull() ?: return null
        return MappingRecord(
            g, a,
            optString("app_package").takeIf { !isNull("app_package") && it.isNotBlank() },
            optString("app_label").takeIf { !isNull("app_label") && it.isNotBlank() },
            getLong("updated_at"),
        )
    }

    companion object {
        private const val TAG = "GestySync"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_LAST_OK = "last_ok"
        @Volatile private var instance: SyncManager? = null
        fun get(context: Context): SyncManager =
            instance ?: synchronized(this) {
                instance ?: SyncManager(context.applicationContext).also { instance = it }
            }
    }
}
