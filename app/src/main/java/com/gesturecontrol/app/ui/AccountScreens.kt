package com.gesturecontrol.app.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gesturecontrol.app.BuildConfig
import com.gesturecontrol.app.auth.AuthManager
import com.gesturecontrol.app.auth.AuthUser
import com.gesturecontrol.app.auth.SignInResult
import com.gesturecontrol.app.sync.SyncManager
import com.gesturecontrol.app.sync.SyncState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Pad = 20.dp

/** Wspólna logika przycisku „Kontynuuj z Google”: stan ładowania + komunikat błędu. */
@Composable
private fun rememberGoogleSignIn(onSuccess: () -> Unit = {}): Triple<Boolean, String?, () -> Unit> {
    val context = LocalContext.current
    val auth = remember { AuthManager.get(context) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val start: () -> Unit = {
        val activity = context as? Activity
        if (activity != null && !busy) {
            busy = true; error = null
            scope.launch {
                when (val r = auth.signIn(activity)) {
                    SignInResult.Success -> onSuccess()
                    SignInResult.Cancelled -> Unit
                    is SignInResult.Error -> error = r.message
                }
                busy = false
            }
        }
    }
    return Triple(busy, error, start)
}

@Composable
fun GoogleButton(text: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        contentPadding = PaddingValues(vertical = 16.dp, horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Spacer(Modifier.width(12.dp))
            Text("Logowanie…")
        } else {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("G") }
                },
                fontSize = 18.sp
            )
            Spacer(Modifier.width(12.dp))
            Text(text)
        }
    }
}

// ============================================================ Powitanie

@Composable
fun WelcomeScreen(onContinueWithoutLogin: () -> Unit) {
    val context = LocalContext.current
    val auth = remember { AuthManager.get(context) }
    val (busy, error, signIn) = rememberGoogleSignIn()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = Pad, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("👄 ✌️ 👍", fontSize = 40.sp, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Steruj telefonem\nbez dotykania",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Przewijaj Reelsy, zmieniaj piosenki i otwieraj aplikacje ruchem ust albo dłoni. Wystarczy przednia kamera.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Feature("🔒", "Obraz z kamery nigdy nie opuszcza telefonu")
                Feature("🔋", "Kamera zasypia, gdy gasisz ekran")
                Feature("☁️", "Po zalogowaniu Twoje gesty czekają na Ciebie nawet na nowym telefonie")
            }
        }
        Spacer(Modifier.height(4.dp))
        GoogleButton("Kontynuuj z Google", enabled = auth.isAvailable, busy = busy, onClick = signIn)
        auth.unavailableReason?.let { Hint(it) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        OutlinedButton(
            onClick = onContinueWithoutLogin,
            contentPadding = PaddingValues(vertical = 16.dp),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Kontynuuj bez logowania") }
        Hint("Bez konta wszystko działa, ale ustawienia znikną po odinstalowaniu apki. Zalogować się możesz w każdej chwili w profilu.")
    }
}

@Composable
private fun Feature(emoji: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 22.sp)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Hint(text: String, center: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth()
    )
}

// ============================================================ Avatar

@Composable
fun Avatar(user: AuthUser?, size: Int = 40) {
    val initials = user?.let {
        (it.name ?: it.email ?: "?").split(" ", ".", "@").filter { s -> s.isNotBlank() }
            .take(2).joinToString("") { s -> s.first().uppercase() }
    } ?: ""
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text(initials, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = (size * 0.38f).sp)
    }
}

// ============================================================ Profil

@Composable
fun ProfileScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val auth = remember { AuthManager.get(context) }
    val sync = remember { SyncManager.get(context) }
    val user by auth.user.collectAsState()
    val syncState by sync.state.collectAsState()
    val scope = rememberCoroutineScope()
    val (busy, error, signIn) = rememberGoogleSignIn()
    var dialog by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = WindowInsets.systemBars.asPaddingValues().let {
            PaddingValues(start = Pad, end = Pad, top = it.calculateTopPadding() + 16.dp, bottom = it.calculateBottomPadding() + 24.dp)
        },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wróć", modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Profil", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val u = user
                    if (u == null) {
                        Text("👤", fontSize = 40.sp)
                        Text("Nie jesteś zalogowany", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Zaloguj się, a Twoje gesty i ustawienia przeniosą się z Tobą na nowy telefon. Nic nie ustawiasz drugi raz.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        GoogleButton("Zaloguj się przez Google", enabled = auth.isAvailable, busy = busy, onClick = signIn)
                        auth.unavailableReason?.let { Hint(it) }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(u, 60)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(u.name ?: "Konto Google", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                u.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        SyncStatus(syncState)
                        if (syncState !is SyncState.Off) {
                            FilledTonalButton(
                                onClick = { sync.syncNow() },
                                enabled = syncState !is SyncState.Syncing,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Synchronizuj teraz") }
                        }
                    }
                }
            }
        }

        item { SectionTitle("Aplikacja") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    MenuRow("⚙️", "Ustawienia", "Czułość ust, tryb scrollowania, bateria", onOpenSettings)
                    HorizontalDivider()
                    MenuRow("💡", "Jak to działa", "Krótki poradnik z przykładami gestów") { dialog = "help" }
                    HorizontalDivider()
                    MenuRow("ℹ️", "O aplikacji", "Wersja ${BuildConfig.VERSION_NAME}") { dialog = "about" }
                }
            }
        }

        if (user != null) {
            item {
                OutlinedButton(
                    onClick = { scope.launch { auth.signOut(); sync.onSignedOut() } },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Wyloguj się") }
            }
            item { Hint("Po wylogowaniu Twoje gesty zostaną na tym telefonie.", center = true) }
            item {
                TextButton(onClick = { dialog = "delete" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Usuń konto i dane z serwera", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    when (dialog) {
        "help" -> InfoDialog("Jak to działa", HELP_TEXT) { dialog = null }
        "about" -> InfoDialog(
            "O aplikacji",
            "Gesty ${BuildConfig.VERSION_NAME}\n\nRozpoznawanie twarzy i dłoni działa w całości na telefonie (Google MediaPipe). " +
                "Na serwer trafiają tylko Twoje przypisania gestów i ustawienia – nigdy obraz z kamery."
        ) { dialog = null }
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Usunąć konto?") },
            text = { Text("Usuniemy Twoje gesty i ustawienia z serwera oraz konto w Gestach. Na tym telefonie wszystko zostanie.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    scope.launch {
                        runCatching { sync.api.deleteAccount() }
                        auth.signOut(); sync.onSignedOut()
                    }
                }) { Text("Usuń", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Anuluj") } }
        )
    }
}

private const val HELP_TEXT =
    "1. Zezwól na kamerę i włącz usługę dostępności na ekranie głównym.\n" +
        "2. Dotknij „Uruchom rozpoznawanie” – kamera działa w tle, możesz przejść do innej apki.\n" +
        "3. Dotknij dowolnego gestu, żeby zmienić, co robi.\n\n" +
        "Przykłady:\n👄 Otwórz usta – następny Reels\n✋ Pokaż dłoń – pauza muzyki\n👍 Kciuk w górę – polub\n\n" +
        "Trzymaj telefon tak, żeby twarz albo dłoń była dobrze widoczna, i unikaj ostrego światła za plecami."

@Composable
private fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

@Composable
private fun SyncStatus(state: SyncState) {
    val (icon, text, color) = when (state) {
        is SyncState.Ok -> Triple("☁️", "Gesty zapisane w chmurze · ${ago(state.at)}", MaterialTheme.colorScheme.primary)
        SyncState.Syncing -> Triple("🔄", "Synchronizuję…", MaterialTheme.colorScheme.onSurfaceVariant)
        is SyncState.Failed -> Triple("⚠️", state.message, MaterialTheme.colorScheme.error)
        is SyncState.Off -> Triple(
            "📱",
            if (state.reason.contains("Serwer")) "Serwer nie jest jeszcze podłączony – gesty są zapisane na tym telefonie."
            else "Gesty są zapisane na tym telefonie.",
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

private fun ago(t: Long): String {
    val s = (System.currentTimeMillis() - t) / 1000
    return when {
        s < 60 -> "przed chwilą"
        s < 3600 -> "${s / 60} min temu"
        s < 86400 -> "${s / 3600} godz. temu"
        else -> SimpleDateFormat("d MMM, HH:mm", Locale("pl")).format(Date(t))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun MenuRow(emoji: String, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Text(emoji, fontSize = 22.sp) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickableRow(onClick)
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit) = this.then(
    Modifier.clickable(onClick = onClick)
)
