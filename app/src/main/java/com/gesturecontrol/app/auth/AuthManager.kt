package com.gesturecontrol.app.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.gesturecontrol.app.AppConfig
import com.gesturecontrol.app.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

data class AuthUser(val uid: String, val name: String?, val email: String?, val photoUrl: String?)

sealed interface SignInResult {
    data object Success : SignInResult
    data object Cancelled : SignInResult
    data class Error(val message: String) : SignInResult
}

/**
 * Logowanie wyłącznie przez Google: Credential Manager -> Google ID token -> Firebase Auth.
 * Bez google-services.json / Web client ID wszystko jest bezpiecznie wyłączone (isAvailable = false).
 */
class AuthManager private constructor(private val context: Context) {

    private val _user = MutableStateFlow<AuthUser?>(null)
    val user: StateFlow<AuthUser?> = _user

    private val auth: FirebaseAuth? = runCatching {
        if (BuildConfig.FIREBASE_CONFIGURED && FirebaseApp.getApps(context).isNotEmpty()) FirebaseAuth.getInstance()
        else null
    }.onFailure { Log.w(TAG, "Firebase niedostępny", it) }.getOrNull()

    /** Czy logowanie Google jest w ogóle możliwe w tej wersji (wklejone klucze). */
    val isAvailable: Boolean get() = auth != null && AppConfig.hasWebClientId

    /** Krótki powód dla ekranu, gdy logowanie jest wyłączone. */
    val unavailableReason: String?
        get() = when {
            auth == null -> "Logowanie Google pojawi się w kolejnej wersji. Do tego czasu wszystko działa bez konta."
            !AppConfig.hasWebClientId -> "Logowanie Google nie jest jeszcze skonfigurowane. Wszystko działa bez konta."
            else -> null
        }

    init {
        auth?.addAuthStateListener { fa -> _user.value = fa.currentUser?.toAuthUser() }
        _user.value = auth?.currentUser?.toAuthUser()
    }

    private fun com.google.firebase.auth.FirebaseUser.toAuthUser() =
        AuthUser(uid, displayName, email, photoUrl?.toString())

    suspend fun signIn(activity: Activity): SignInResult {
        val fa = auth ?: return SignInResult.Error(unavailableReason ?: "Logowanie niedostępne")
        if (!AppConfig.hasWebClientId) return SignInResult.Error(unavailableReason!!)
        return try {
            val option = GetSignInWithGoogleOption.Builder(AppConfig.webClientId).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) return SignInResult.Error("Nieoczekiwany typ danych logowania")
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            fa.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
            SignInResult.Success
        } catch (e: GetCredentialCancellationException) {
            SignInResult.Cancelled
        } catch (e: NoCredentialException) {
            SignInResult.Error("Nie znaleziono konta Google na tym telefonie. Dodaj je w Ustawieniach > Konta.")
        } catch (e: Exception) {
            Log.w(TAG, "Błąd logowania", e)
            SignInResult.Error("Nie udało się zalogować. Sprawdź internet i spróbuj ponownie.")
        }
    }

    suspend fun signOut() {
        auth?.signOut()
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    /** Firebase ID token do nagłówka Authorization. forceRefresh = true po odpowiedzi 401. */
    suspend fun idToken(forceRefresh: Boolean = false): String? =
        runCatching { auth?.currentUser?.getIdToken(forceRefresh)?.await()?.token }.getOrNull()

    companion object {
        private const val TAG = "GestyAuth"
        @Volatile private var instance: AuthManager? = null
        fun get(context: Context): AuthManager =
            instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
    }
}
