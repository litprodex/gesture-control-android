package com.gesturecontrol.app.net

import com.gesturecontrol.app.AppConfig
import com.gesturecontrol.app.auth.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)

/** Klient REST. Do KAŻDEGO zapytania dokleja „Authorization: Bearer <Firebase ID token>”. */
class ApiClient(private val auth: AuthManager, private val deviceId: String) {

    private val json = "application/json; charset=utf-8".toMediaType()

    /** Dokleja token; przy 401 odświeża token i ponawia raz. */
    private val authInterceptor = Interceptor { chain ->
        fun withToken(force: Boolean): Request {
            val token = runBlocking { auth.idToken(force) }
            return chain.request().newBuilder()
                .header("X-Device-Id", deviceId)
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
        }
        var response: Response = chain.proceed(withToken(false))
        if (response.code == 401) {
            response.close()
            response = chain.proceed(withToken(true))
        }
        response
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .build()

    val isConfigured: Boolean get() = AppConfig.hasApi

    private suspend fun call(method: String, path: String, body: JSONObject? = null): String =
        withContext(Dispatchers.IO) {
            if (!isConfigured) throw ApiException(0, "Adres serwera nie jest skonfigurowany")
            val req = Request.Builder()
                .url(AppConfig.apiBaseUrl + path)
                .method(method, body?.toString()?.toRequestBody(json) ?: if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(json))
                .build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiException(r.code, "HTTP ${r.code}: $text")
                text
            }
        }

    suspend fun health(): JSONObject = JSONObject(call("GET", "/health"))
    suspend fun me(): JSONObject = JSONObject(call("GET", "/me"))
    suspend fun getSync(): JSONObject = JSONObject(call("GET", "/sync"))
    suspend fun postSync(body: JSONObject): JSONObject = JSONObject(call("POST", "/sync", body))
    suspend fun putMapping(gesture: String, body: JSONObject): JSONObject = JSONObject(call("PUT", "/mappings/$gesture", body))
    suspend fun putSettings(body: JSONObject): JSONObject = JSONObject(call("PUT", "/settings", body))
    suspend fun registerDevice(name: String, appVersion: String): JSONObject =
        JSONObject(call("PUT", "/devices/$deviceId", JSONObject().put("name", name).put("app_version", appVersion)))
    suspend fun devices(): JSONArray = JSONArray(call("GET", "/devices"))
    suspend fun deleteAccount() { call("DELETE", "/me") }
}
