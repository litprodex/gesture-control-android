package com.gesturecontrol.app.ui

import androidx.compose.ui.text.style.TextAlign

import androidx.compose.ui.draw.alpha

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Search
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.gesturecontrol.app.data.AppList
import com.gesturecontrol.app.data.LaunchableApp
import com.gesturecontrol.app.data.GestureBus
import com.gesturecontrol.app.data.MappingRepository
import com.gesturecontrol.app.data.SettingsRepository
import kotlin.math.roundToInt
import com.gesturecontrol.app.model.ActionGroup
import com.gesturecontrol.app.model.ActionType
import com.gesturecontrol.app.model.GestureCategory
import com.gesturecontrol.app.model.GestureType
import com.gesturecontrol.app.service.GestureAccessibilityService
import com.gesturecontrol.app.service.GestureCameraService
import com.gesturecontrol.app.auth.AuthManager
import com.gesturecontrol.app.sync.SyncManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { GestyApp() }
            }
        }
    }
}

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private val ScreenPadding = 20.dp

private enum class Screen { WELCOME, HOME, PROFILE, SETTINGS }

/**
 * Nawigacja: Powitanie (tylko przy pierwszym uruchomieniu) -> Ekran główny -> Profil -> Ustawienia.
 * Zalogowany użytkownik od razu trafia na ekran główny.
 */
@Composable
fun GestyApp() {
    val context = LocalContext.current
    val auth = remember { AuthManager.get(context) }
    val sync = remember { SyncManager.get(context) }
    val prefs = remember { context.getSharedPreferences("app", Context.MODE_PRIVATE) }
    val user by auth.user.collectAsState()

    var screen by rememberSaveable {
        mutableStateOf(if (user != null || prefs.getBoolean("onboarding_done", false)) Screen.HOME else Screen.WELCOME)
    }

    // Po zalogowaniu: pomijamy powitanie i synchronizujemy gesty
    LaunchedEffect(user?.uid) {
        if (user != null) {
            prefs.edit().putBoolean("onboarding_done", true).apply()
            if (screen == Screen.WELCOME) screen = Screen.HOME
            sync.syncNow()
        }
    }

    when (screen) {
        Screen.WELCOME -> WelcomeScreen(onContinueWithoutLogin = {
            prefs.edit().putBoolean("onboarding_done", true).apply()
            screen = Screen.HOME
        })
        Screen.HOME -> HomeScreen(onOpenProfile = { screen = Screen.PROFILE })
        Screen.PROFILE -> {
            BackHandler { screen = Screen.HOME }
            ProfileScreen(onBack = { screen = Screen.HOME }, onOpenSettings = { screen = Screen.SETTINGS })
        }
        Screen.SETTINGS -> {
            BackHandler { screen = Screen.PROFILE }
            SettingsRoute(onBack = { screen = Screen.PROFILE })
        }
    }
}

@Composable
private fun SettingsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { MappingRepository.get(context) }
    val mappings by repo.mappings.collectAsState()
    val settings = remember { SettingsRepository.get(context) }
    val fps by settings.fps.collectAsState()
    val pauseOnScreenOff by settings.pauseOnScreenOff.collectAsState()
    val doomScroll by settings.doomScroll.collectAsState()
    val mouthLevel by settings.mouthLevel.collectAsState()
    val usedCategories = mappings.filterValues { it != ActionType.NONE }.keys.map { it.category }.toSet()
    SettingsScreen(
        onBack = onBack,
        doomScroll = doomScroll, onDoom = settings::setDoomScroll,
        mouthLevel = mouthLevel, onMouthLevel = settings::setMouthLevel,
        fps = fps, onFps = settings::setFps,
        pauseOnScreenOff = pauseOnScreenOff, onPause = settings::setPauseOnScreenOff,
        faceActive = GestureCategory.FACE in usedCategories,
        handActive = GestureCategory.HAND in usedCategories,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenProfile: () -> Unit) {
    val context = LocalContext.current
    val user by remember { AuthManager.get(context) }.user.collectAsState()
    val repo = remember { MappingRepository.get(context) }
    val mappings by repo.mappings.collectAsState()
    val apps by repo.apps.collectAsState()
    val running by GestureBus.serviceRunning.collectAsState()
    val lastEvent by GestureBus.lastEvent.collectAsState()
    val paused by GestureBus.paused.collectAsState()
    val doomScroll by remember { SettingsRepository.get(context) }.doomScroll.collectAsState()

    var hasCamera by remember { mutableStateOf(context.has(Manifest.permission.CAMERA)) }
    var a11y by remember { mutableStateOf(GestureAccessibilityService.isEnabled(context)) }
    var editing by remember { mutableStateOf<GestureType?>(null) }
    var pickingApp by remember { mutableStateOf<GestureType?>(null) }

    LifecycleResumeEffect(Unit) {
        hasCamera = context.has(Manifest.permission.CAMERA)
        a11y = GestureAccessibilityService.isEnabled(context)
        onPauseOrDispose { }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasCamera = context.has(Manifest.permission.CAMERA) }

    fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = WindowInsets.systemBars.asPaddingValues().let {
            PaddingValues(
                start = ScreenPadding, end = ScreenPadding,
                top = it.calculateTopPadding() + 16.dp,
                bottom = it.calculateBottomPadding() + 24.dp
            )
        },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            ) {
                Text(
                    "Sterowanie gestami",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onOpenProfile, modifier = Modifier.size(52.dp)) {
                    if (user != null) Avatar(user, 36)
                    else Icon(Icons.Filled.AccountCircle, contentDescription = "Profil", modifier = Modifier.size(34.dp))
                }
            }
        }

        // ---- Konfiguracja ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SetupStep("Dostęp do kamery", hasCamera, "Zezwól") { requestPerms() }
                    SetupStep("Usługa dostępności", a11y, "Włącz") {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    Button(
                        onClick = {
                            val intent = Intent(context, GestureCameraService::class.java)
                            if (running) context.stopService(intent)
                            else ContextCompat.startForegroundService(context, intent)
                        },
                        enabled = hasCamera,
                        colors = if (running) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ) else ButtonDefaults.buttonColors(),
                        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (running) "Zatrzymaj rozpoznawanie" else "Uruchom rozpoznawanie") }
                }
            }
        }

        // ---- Ostatni gest ----
        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Ostatni gest", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when {
                            running && paused -> "Wstrzymane – ekran zgaszony"
                            lastEvent != null -> lastEvent!!
                            running -> "Czekam na gest…"
                            else -> "Uruchom rozpoznawanie, żeby zobaczyć gesty"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // ---- Przypisania ----
        GestureCategory.entries.forEach { category ->
            item(key = "h_${category.name}") { SectionHeader(category.title) }
            items(GestureType.entries.filter { it.category == category }, key = { it.name }) { g ->
                val disabled = doomScroll &&
                    (g == GestureType.MOUTH_OPEN_TWICE || g == GestureType.MOUTH_OPEN_THRICE)
                val action = mappings[g] ?: ActionType.NONE
                val actionText = if (action == ActionType.OPEN_APP)
                    "📱 Otwórz: " + (AppList.label(context, apps[g])
                        ?: repo.appLabelFor(g)?.let { "$it (nie ma na tym telefonie)" } ?: "aplikacja odinstalowana")
                else null
                GestureCard(g, action, a11y, disabled, actionText) { editing = g }
            }
        }

        item {
            OutlinedButton(
                onClick = { repo.resetToDefaults() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Przywróć domyślne przypisania") }
        }
    }

    editing?.let { gesture ->
        ActionPickerSheet(
            gesture = gesture,
            selected = mappings[gesture] ?: ActionType.NONE,
            a11yEnabled = a11y,
            currentApp = AppList.label(context, apps[gesture]),
            onSelect = {
                if (it == ActionType.OPEN_APP) pickingApp = gesture else repo.set(gesture, it)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    pickingApp?.let { gesture ->
        AppPickerSheet(
            gesture = gesture,
            selectedPackage = apps[gesture],
            onSelect = { repo.setApp(gesture, it.packageName); pickingApp = null },
            onDismiss = { pickingApp = null }
        )
    }
}

/** Lista aplikacji z telefonu z wyszukiwarką – wybór celu dla „Otwórz aplikację”. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    gesture: GestureType,
    selectedPackage: String?,
    onSelect: (LaunchableApp) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val allApps by produceState<List<LaunchableApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { AppList.load(context) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(allApps, query) {
        val q = query.trim().lowercase()
        allApps.orEmpty().filter {
            q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                Text("${gesture.emoji}  Którą aplikację otworzyć?", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Szukaj aplikacji…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            when {
                allApps == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                filtered.isEmpty() -> Text(
                    "Brak aplikacji pasujących do „$query”",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(ScreenPadding)
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        val icon = remember(app.packageName) {
                            runCatching { app.icon.toBitmap(128, 128).asImageBitmap() }.getOrNull()
                        }
                        val selected = app.packageName == selectedPackage
                        ListItem(
                            leadingContent = {
                                if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(44.dp))
                                else Text("📱", fontSize = 28.sp)
                            },
                            headlineContent = { Text(app.label) },
                            trailingContent = if (selected) {
                                { Text("✓", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary) }
                            } else null,
                            colors = ListItemDefaults.colors(
                                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                            ),
                            modifier = Modifier.clickable { onSelect(app) }.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupStep(title: String, done: Boolean, buttonLabel: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(if (done) "✅" else "⚠️", fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!done) {
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onClick) { Text(buttonLabel) }
        }
    }
}

/** Ekran ustawień, otwierany z profilu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    doomScroll: Boolean, onDoom: (Boolean) -> Unit,
    mouthLevel: Int, onMouthLevel: (Int) -> Unit,
    fps: Int, onFps: (Int) -> Unit,
    pauseOnScreenOff: Boolean, onPause: (Boolean) -> Unit,
    faceActive: Boolean, handActive: Boolean,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = WindowInsets.systemBars.asPaddingValues().let {
            PaddingValues(
                start = ScreenPadding, end = ScreenPadding,
                top = it.calculateTopPadding() + 16.dp,
                bottom = it.calculateBottomPadding() + 24.dp
            )
        },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wróć", modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Ustawienia",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item { MouthCard(doomScroll, onDoom, mouthLevel, onMouthLevel) }
        item { BatteryCard(fps, onFps, pauseOnScreenOff, onPause, faceActive, handActive) }
    }
}

@Composable
private fun MouthCard(
    doomScroll: Boolean,
    onDoom: (Boolean) -> Unit,
    level: Int,
    onLevel: (Int) -> Unit,
) {
    var sliderValue by remember(level) { mutableFloatStateOf(level.toFloat()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("👄 Gesty ustami", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tryb scrollowania", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Wystarczy lekko uchylić usta i od razu lecisz do następnego filmiku. Idealne na Reelsy i TikToka. Podwójne i potrójne otwarcie na ten czas śpią.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = doomScroll, onCheckedChange = onDoom)
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            val enabled = !doomScroll
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Jak mocno otwierać usta", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (enabled) SettingsRepository.MOUTH_LEVEL_NAMES[sliderValue.roundToInt()] else "Minimalnie",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onLevel(sliderValue.roundToInt()) },
                valueRange = 0f..2f,
                steps = 1,
                enabled = enabled
            )
            Row(Modifier.fillMaxWidth()) {
                SettingsRepository.MOUTH_LEVEL_NAMES.forEachIndexed { i, name ->
                    Text(
                        name,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = when (i) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End }
                    )
                }
            }
            Text(
                if (enabled) "Przy „Lekko” wystarczy mały ruch, ale apka może czasem zareagować, kiedy po prostu mówisz."
                else "W trybie scrollowania wystarczy zawsze minimalny ruch.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BatteryCard(
    fps: Int,
    onFps: (Int) -> Unit,
    pauseOnScreenOff: Boolean,
    onPause: (Boolean) -> Unit,
    faceActive: Boolean,
    handActive: Boolean,
) {
    var sliderValue by remember(fps) { mutableFloatStateOf(fps.toFloat()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🔋 Bateria", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Płynność rozpoznawania", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(12.dp))
                Text("${sliderValue.roundToInt()} kl./s", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onFps(sliderValue.roundToInt()) },
                valueRange = SettingsRepository.MIN_FPS.toFloat()..SettingsRepository.MAX_FPS.toFloat(),
                steps = SettingsRepository.MAX_FPS - SettingsRepository.MIN_FPS - 1
            )
            Text(
                "Mniej klatek to dłużej na baterii, ale gest łapie się ułamek sekundy później. 10–15 to złoty środek.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Usypiaj kamerę przy zgaszonym ekranie", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Kamera wyłącza się, kiedy blokujesz telefon, i sama wraca po odblokowaniu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = pauseOnScreenOff, onCheckedChange = onPause)
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Text(
                "Teraz rozpoznaję: " + when {
                    faceActive && handActive -> "twarz i dłonie"
                    faceActive -> "tylko twarz"
                    handActive -> "tylko dłonie"
                    else -> "nic (żaden gest nie ma akcji)"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Jeśli żaden gest twarzy albo dłoni nie ma przypisanej akcji, w ogóle ich nie szukam. Bateria Ci podziękuje.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
    )
}

/** Gest w jednej karcie: duże emoji, nazwa, a pod nią przypisana akcja. Cała karta jest klikalna. */
@Composable
private fun GestureCard(
    gesture: GestureType,
    action: ActionType,
    a11yEnabled: Boolean,
    disabled: Boolean = false,
    actionText: String? = null,
    onClick: () -> Unit
) {
    val warn = action.needsAccessibility && !a11yEnabled && !disabled
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().alpha(if (disabled) 0.45f else 1f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(gesture.emoji, fontSize = 28.sp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(gesture.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    actionText ?: if (action == ActionType.NONE) "Brak akcji" else action.display,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        warn -> MaterialTheme.colorScheme.error
                        action == ActionType.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                if (disabled) {
                    Text(
                        "Śpi w trybie scrollowania",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (warn) {
                    Text(
                        "Wymaga usługi dostępności",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text("›", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Pełnoekranowy, przewijany wybór akcji – działa na każdej wielkości ekranu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionPickerSheet(
    gesture: GestureType,
    selected: ActionType,
    a11yEnabled: Boolean,
    currentApp: String?,
    onSelect: (ActionType) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                Column(Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                    Text("${gesture.emoji}  ${gesture.label}", style = MaterialTheme.typography.titleLarge)
                    gesture.hint?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            ActionGroup.entries.forEach { group ->
                val actions = ActionType.entries.filter { it.group == group }
                if (group != ActionGroup.NONE) {
                    item(key = "g_${group.name}") {
                        Text(
                            group.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 16.dp, bottom = 4.dp)
                        )
                    }
                }
                items(actions, key = { it.name }) { a ->
                    ListItem(
                        leadingContent = { Text(a.emoji, fontSize = 22.sp) },
                        headlineContent = { Text(a.label) },
                        supportingContent = when {
                            a.needsAccessibility && !a11yEnabled -> {
                                { Text("Wymaga usługi dostępności", color = MaterialTheme.colorScheme.error) }
                            }
                            a == ActionType.OPEN_APP && a == selected && currentApp != null -> {
                                { Text("Teraz: $currentApp – dotknij, żeby zmienić") }
                            }
                            a == ActionType.OPEN_APP -> {
                                { Text("Wybierzesz z listy aplikacji na telefonie") }
                            }
                            else -> null
                        },
                        trailingContent = if (a == selected) {
                            { Text("✓", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary) }
                        } else null,
                        colors = ListItemDefaults.colors(
                            containerColor = if (a == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                        ),
                        modifier = Modifier
                            .clickable { onSelect(a) }
                            .padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}
