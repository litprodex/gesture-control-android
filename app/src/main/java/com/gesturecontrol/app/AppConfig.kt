package com.gesturecontrol.app

/** Konfiguracja z config.properties (przez BuildConfig). Placeholdery = funkcja wyłączona. */
object AppConfig {
    val webClientId: String = BuildConfig.WEB_CLIENT_ID
    val apiBaseUrl: String = BuildConfig.API_BASE_URL

    val hasWebClientId: Boolean
        get() = webClientId.endsWith(".apps.googleusercontent.com") && !webClientId.startsWith("WKLEJ")

    val hasApi: Boolean
        get() = apiBaseUrl.startsWith("http") && !apiBaseUrl.contains("WKLEJ")
}
