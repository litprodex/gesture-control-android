# Gesty – sterowanie telefonem gestami twarzy i dłoni

Natywna aplikacja Android (Kotlin + Jetpack Compose). Przednia kamera + MediaPipe rozpoznają gesty
twarzy i dłoni całkowicie lokalnie, urzytkownik przypisujesz każdemu gestowi akcję (muzyka, swipe, tap, akcje systemowe, otwarcie wybranej aplikacji).
Opcjonalnie: logowanie przez Google i synchronizacja gestów między telefonami przez własny serwer.

## 1. Jak to działa

```
Telefon                                   Serwer (VPS)
───────                                   ────────────
Credential Manager → Google ID token
        ↓
Firebase Auth (signInWithCredential)
        ↓  Firebase ID token (1 h, odświeżany sam)
OkHttp: Authorization: Bearer <token>  →  Caddy (HTTPS) → uvicorn/FastAPI
                                           firebase-admin.verify_id_token → uid
                                           SQLite: dane per uid
```

- **Dane lokalne są główne.** Apka zawsze działa na SharedPreferences; serwer to wspólna kopia dla telefonów.
- **Synchronizacja „ostatni zapis wygrywa”**: każdy gest i ustawienia mają znacznik czasu zmiany.
  Po zalogowaniu i przy starcie apki telefon wysyła cały stan (`POST /sync`), serwer zostawia nowsze wartości
  i odsyła wynik. Każda zmiana gestu/ustawienia leci od razu (`PUT`). Bez internetu nic się nie psuje.
- **Wylogowanie** zostawia gesty na telefonie. „Usuń konto” kasuje dane z serwera i konto w Firebase.
- Obraz z kamery nigdy nie opuszcza telefonu.

### Ekrany
Powitanie (tylko przy pierwszym uruchomieniu; zalogowany od razu trafia dalej) → Ekran główny (ikona profilu
w prawym górnym rogu) → Profil (stan logowania, synchronizacja, logowanie/wylogowanie) → Ustawienia.

## 2. Pierwsze uruchomienie

1. Rozpakuj ZIP.
2. Android Studio → **File → Open** → wskaż folder `GestureControl` (ten z plikiem `settings.gradle.kts`).
3. Poczekaj na **Gradle Sync** (pierwszy raz kilka minut). Jeśli zapyta o SDK / licencje – *Install*.
4. Na telefonie włącz **Opcje programisty → Debugowanie USB**.
5. Podłącz telefon, wybierz go na górnym pasku i kliknij **Run ▶**.
6. W aplikacji: zezwól na kamerę → włącz usługę dostępności „Gesty” → **Uruchom rozpoznawanie**.

### Build z terminala
```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew installDebug           # od razu na podłączony telefon
```

## 3. Struktura projektu

```
config.properties                   ← WEB_CLIENT_ID i API_BASE_URL 
app/
├── google-services.json            ← z Firebase, nie ma go w repo
├── gesty-debug.keystore            klucz podpisu (debug i release)
└── src/main/java/com/gesturecontrol/app/
    ├── AppConfig.kt                odczyt konfiguracji, wykrywanie placeholderów
    ├── auth/AuthManager.kt         Credential Manager + Firebase Auth, token do API
    ├── net/ApiClient.kt            OkHttp, dokleja „Bearer <token>” do każdego zapytania
    ├── sync/SyncManager.kt         synchronizacja przypisań i ustawień
    ├── ui/
    │   ├── MainActivity.kt         nawigacja, ekran główny, ustawienia, wybór akcji i aplikacji
    │   └── AccountScreens.kt       powitanie, profil, przycisk Google, avatar
    ├── service/                    kamera w tle, usługa dostępności, wykonywanie akcji
    ├── detect/                     logika gestów twarzy i dłoni
    ├── model/                      GestureType, ActionType
    └── data/                       przypisania i ustawienia (SharedPreferences + znaczniki czasu)
server/ (TODO)
├── app.py                          FastAPI: weryfikacja tokenu, endpointy, SQLite
├── schema.sql                      tabele: users, devices, gesture_mappings, user_settings
├── requirements.txt / -dev.txt
├── tests/test_api.py               testy (pytest)
└── deploy/                         gesty.service (systemd), Caddyfile, backup.sh
```

## 4. Stack

- Kotlin 2.0.20, JVM 17, Gradle 8.9, AGP 8.5.2, minSdk 26, target/compileSdk 35
- Jetpack Compose (BOM 2024.09.03), Material 3, CameraX 1.3.4, MediaPipe Tasks Vision 0.10.14
- Firebase Auth (BOM 33.7.0), Credential Manager 1.3.0 + Google ID 1.1.1, OkHttp 4.12
- Serwer: Python, FastAPI, uvicorn, firebase-admin, SQLite; Caddy (HTTPS), systemd

## 5. Jak dodać nowy gest

1. Dodaj wpis w `model/GestureType.kt` (emoji, nazwa, kategoria FACE/HAND).
2. Wykryj go:
   - twarz → `FaceGestureLogic.update()` (mapa blendshapes, np. `jawOpen`, `eyeBlinkLeft`),
   - dłoń → `HandShapeClassifier` (geometria punktów) albo mapowanie nazwy z MediaPipe w `GestureCameraService`.
3. Wywołaj `onGesture(GestureType.TWOJ_GEST)`. UI i wybór akcji pojawią się automatycznie.

Nowa akcja: wpis w `model/ActionType.kt` + obsługa w `ActionExecutor.kt`.

## 6. Otwieranie aplikacji

Akcja „Otwórz aplikację…” zapisuje nazwę pakietu w `MappingRepository` (klucz `<GEST>__app`).
Android 10+ blokuje start aktywności z tła, więc `ActionExecutor` uruchamia apkę przez
kontekst `GestureAccessibilityService` (aktywna usługa dostępności jest wyjątkiem od blokady).
Lista apek wymaga `<queries>` w manifeście (Android 11+).

## 7. Strojenie

- Progi ust: `FaceGestureLogic.setMouthLevel()` (Lekko/Średnio/Mocno) i `doomOpenThreshold`.
- Czas między akcjami: `ACTION_COOLDOWN_MS` i `DOOM_COOLDOWN_MS` w `GestureCameraService`.
- Dłonie: progi w `HandShapeClassifier.kt`.

Podgląd logów: Android Studio → **Logcat**, filtr `GestureCameraService`.

## 8. Wydawanie nowej wersji

1. Podbij `versionCode` (i `versionName`) w `app/build.gradle.kts`.
2. `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` 
3. **Nie usuwać `app/gesty-debug.keystore`** – inaczej aktualizacja nie zainstaluje się
   na starą wersję (trzeba będzie odinstalować i stracić ustawienia).

## 9. Ograniczenia

- Android 14+: rozpoznawanie trzeba uruchomić z otwartej aplikacji (wymóg dla kamery w tle) – potem działa w tle.
- Gdy inna apka używa przedniej kamery (np. rozmowa wideo), rozpoznawanie się wstrzymuje.
- Google Play wymaga dodatkowej weryfikacji aplikacji z AccessibilityService – do użytku prywatnego (APK) bez problemu.
