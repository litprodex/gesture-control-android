package com.gesturecontrol.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.media.MediaActionSound
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.gesturecontrol.app.data.GestureBus
import com.gesturecontrol.app.data.MappingRepository
import com.gesturecontrol.app.data.SettingsRepository
import com.gesturecontrol.app.detect.FaceGestureLogic
import com.gesturecontrol.app.detect.HandGestureLogic
import com.gesturecontrol.app.detect.HandShapeClassifier
import com.gesturecontrol.app.model.ActionType
import com.gesturecontrol.app.data.AppList
import com.gesturecontrol.app.model.GestureCategory
import com.gesturecontrol.app.model.GestureType
import com.gesturecontrol.app.ui.MainActivity
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Usługa pierwszoplanowa: przednia kamera -> MediaPipe (twarz + dłoń) -> gest -> akcja.
 * Działa także gdy jesteś w innej aplikacji (Instagram, Spotify...).
 */
class GestureCameraService : LifecycleService() {

    private lateinit var analysisExecutor: ExecutorService
    private lateinit var repo: MappingRepository
    private lateinit var actions: ActionExecutor
    private lateinit var faceLogic: FaceGestureLogic
    private lateinit var handLogic: HandGestureLogic

    private var faceLandmarker: FaceLandmarker? = null
    private var gestureRecognizer: GestureRecognizer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var selfieOverlay: SelfiePreview
    /** Podgląd pojawia się dopiero przy geście selfie, nie przy starcie rozpoznawania. */
    private var previewDismissed = true
    private var previewBroken = false
    private var previousMappingChange: ((GestureType?) -> Unit)? = null
    private lateinit var settings: SettingsRepository
    private val shutter = MediaActionSound()
    private val captureInFlight = AtomicBoolean(false)
    /** Fałsz tylko gdy sami zgasiliśmy kamerę przy wyłączonym ekranie. */
    private var cameraWanted = true
    private var lastAnalyzeAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> if (settings.pauseOnScreenOff.value) {
                    cameraWanted = false
                    selfieOverlay.hide()
                    unbindCamera()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    cameraWanted = true
                    refreshPreview()
                }
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastFrameAt = 0L
    private var lastActionAt = 0L
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val photoNameFmt = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    override fun onCreate() {
        super.onCreate()
        repo = MappingRepository.get(this)
        settings = SettingsRepository.get(this)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        actions = ActionExecutor(this)
        faceLogic = FaceGestureLogic(::onGesture)
        handLogic = HandGestureLogic(::onGesture)
        analysisExecutor = Executors.newSingleThreadExecutor()
        selfieOverlay = SelfiePreview(this)
        shutter.load(MediaActionSound.SHUTTER_CLICK)
        selfieOverlay.onClosed = {
            previewDismissed = true
            cameraProvider?.unbindAll()
            bindCamera()
        }
        previousMappingChange = repo.onLocalChange
        repo.onLocalChange = { gesture ->
            previousMappingChange?.invoke(gesture)
            main.post { if (instance === this) refreshPreview() }
        }
        instance = this
        main.post(cameraWatchdog)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (faceLandmarker == null && gestureRecognizer == null) {
            setupModels()
            startCamera()
        }
        GestureBus.serviceRunning.value = true
        return START_NOT_STICKY
    }

    // ---------- Powiadomienie / foreground ----------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sterowanie gestami", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, GestureCameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Sterowanie gestami aktywne")
            .setContentText("Przednia kamera rozpoznaje gesty twarzy i dłoni")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Zatrzymaj", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    // ---------- MediaPipe ----------

    private fun setupModels() {
        try {
            faceLandmarker = FaceLandmarker.createFromOptions(
                this,
                FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(true)
                    .setMinFaceDetectionConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener { result, _ -> onFaceResult(result) }
                    .setErrorListener { e -> Log.e(TAG, "FaceLandmarker", e) }
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Nie udało się załadować face_landmarker.task", e)
        }
        try {
            gestureRecognizer = GestureRecognizer.createFromOptions(
                this,
                GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener { result, _ -> onHandResult(result) }
                    .setErrorListener { e -> Log.e(TAG, "GestureRecognizer", e) }
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Nie udało się załadować gesture_recognizer.task", e)
        }
    }

    private fun onFaceResult(result: FaceLandmarkerResult) {
        val shapes = result.faceBlendshapes()
        if (!shapes.isPresent || shapes.get().isEmpty()) {
            faceLogic.reset(); return
        }
        val scores = shapes.get()[0].associate { it.categoryName() to it.score() }
        faceLogic.doomMode = settings.doomScroll.value
        faceLogic.setMouthLevel(settings.mouthLevel.value)
        faceLogic.update(scores, result.timestampMs())
    }

    private fun onHandResult(result: GestureRecognizerResult) {
        // Najpierw własne kształty (🖕) z punktów dłoni, potem gesty wbudowane w model
        val custom = result.worldLandmarks().firstOrNull()?.let { HandShapeClassifier.classify(it) }
        val builtIn = result.gestures().firstOrNull()?.firstOrNull()
            ?.takeIf { it.score() >= MIN_HAND_SCORE }
            ?.let { GestureType.fromMediaPipe(it.categoryName()) }
        handLogic.update(custom ?: builtIn, result.timestampMs())
    }

    // ---------- Kamera ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                            ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyze) }
                preview = Preview.Builder().build()
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1920, 1080),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            ).build()
                    )
                    .build()
                bindCamera()
                refreshPreview()
            } catch (e: Exception) {
                Log.e(TAG, "Nie udało się uruchomić kamery", e)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Włącza kamerę (np. po odblokowaniu ekranu). Podgląd, analiza i zdjęcie dzielą jedną sesję. */
    private fun bindCamera() {
        if (!cameraWanted) return
        val provider = cameraProvider ?: return
        val useCase = analysis ?: return
        val livePreview = preview?.takeIf { selfieOverlay.isShowing }
        val capture = imageCapture
        livePreview?.setSurfaceProvider(selfieOverlay.surfaceProvider)
        val wanted = listOfNotNull(livePreview, useCase, capture)
        if (wanted.all { provider.isBound(it) }) return
        try {
            provider.unbindAll()
            try {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, *wanted.toTypedArray())
            } catch (e: Exception) {
                Log.w(TAG, "Podgląd albo zdjęcie niedostępne, zostaje samo rozpoznawanie", e)
                imageCapture = null
                preview = null
                previewBroken = true
                selfieOverlay.hide()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, useCase)
            }
            lastAnalyzeAt = SystemClock.uptimeMillis()
            GestureBus.paused.value = false
        } catch (e: Exception) {
            Log.e(TAG, "bindCamera", e)
        }
    }

    private fun selfieWanted(): Boolean = repo.mappings.value.values.any { it == ActionType.TAKE_SELFIE }

    /** Pokazuje kadr, gdy selfie jest przypisane i system pozwala rysować nad innymi aplikacjami. */
    private fun refreshPreview() {
        val show = cameraWanted && !previewDismissed && !previewBroken &&
            selfieWanted() && Settings.canDrawOverlays(this)
        if (show) {
            if (selfieOverlay.show()) {
                cameraProvider?.unbindAll()
                bindCamera()
            }
        } else if (selfieOverlay.isShowing) {
            selfieOverlay.hide()
            cameraProvider?.unbindAll()
            bindCamera()
        }
    }

    /** Wyłącza kamerę całkowicie – zero zużycia, gdy ekran jest zgaszony. */
    private fun unbindCamera() {
        cameraProvider?.unbindAll()
        faceLogic.reset()
        GestureBus.paused.value = true
    }

    private fun analyze(image: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        lastAnalyzeAt = now
        val frameInterval = 1000L / settings.fps.value
        if (now - lastFrameAt < frameInterval) {
            image.close(); return
        }
        lastFrameAt = now

        val rotation = image.imageInfo.rotationDegrees
        val raw = image.toBitmap()
        image.close()
        val bitmap = if (rotation == 0) raw else Bitmap.createBitmap(
            raw, 0, 0, raw.width, raw.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, false
        )

        // Model, który nie ma żadnego przypisania, w ogóle nie jest uruchamiany
        val used = repo.mappings.value.filterValues { it != ActionType.NONE }.keys.map { it.category }.toSet()
        if (GestureCategory.FACE in used) faceLandmarker?.detectAsync(BitmapImageBuilder(bitmap).build(), now)
        if (GestureCategory.HAND in used) gestureRecognizer?.recognizeAsync(BitmapImageBuilder(bitmap).build(), now)
    }

    // ---------- Gest -> akcja ----------

    /** Wołane z wątków MediaPipe; całą obsługę przenosimy na main. */
    private fun onGesture(gesture: GestureType) {
        main.post {
            val action = repo.actionFor(gesture)
            val appPkg = repo.appFor(gesture)
            val actionText = if (action == ActionType.OPEN_APP)
                "📱 ${AppList.label(this, appPkg) ?: "Otwórz aplikację"}" else action.display
            GestureBus.lastEvent.value =
                "${timeFmt.format(Date())}  ${gesture.display}  →  $actionText"
            if (action == ActionType.NONE) return@post
            val now = SystemClock.uptimeMillis()
            val cooldown = if (gesture == GestureType.MOUTH_OPEN_ONCE && settings.doomScroll.value)
                DOOM_COOLDOWN_MS else ACTION_COOLDOWN_MS
            if (now - lastActionAt < cooldown) return@post
            lastActionAt = now
            actions.execute(action, appPkg)
        }
    }

    /**
     * Zapisuje selfie do galerii tą samą przednią kamerą, która rozpoznaje gesty.
     * Nie otwieramy aplikacji Aparat, bo ona zabiera kamerę i skanowanie samo nie wraca.
     */
    private fun captureSelfie(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Zezwól na podgląd nad innymi aplikacjami, żeby widzieć zdjęcie",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        if (!selfieOverlay.isShowing) {
            previewDismissed = false
            refreshPreview()
            Toast.makeText(this, "Ustaw kadr w podglądzie i powtórz gest", Toast.LENGTH_SHORT).show()
            return false
        }
        val capture = imageCapture
        if (capture == null || !cameraWanted) {
            Toast.makeText(this, "Uruchom rozpoznawanie, żeby zrobić zdjęcie", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!captureInFlight.compareAndSet(false, true)) return true
        capture.targetRotation = displayRotation()
        val name = "Gesty_${photoNameFmt.format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Gesty")
            }
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()
        shutter.play(MediaActionSound.SHUTTER_CLICK)
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    captureInFlight.set(false)
                    selfieOverlay.flashSaved()
                    Log.i(TAG, "Selfie zapisane: ${output.savedUri}")
                }

                override fun onError(exception: ImageCaptureException) {
                    captureInFlight.set(false)
                    Log.e(TAG, "Nie udało się zapisać selfie", exception)
                    Toast.makeText(
                        this@GestureCameraService,
                        "Nie udało się zapisać zdjęcia",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
        return true
    }

    private fun displayRotation(): Int {
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        return display?.rotation ?: Surface.ROTATION_0
    }

    /** Gdy inna aplikacja zabierze kamerę, klatki milkną. Po zwolnieniu podpinamy ją z powrotem. */
    private val cameraWatchdog = object : Runnable {
        override fun run() {
            val silentFor = SystemClock.uptimeMillis() - lastAnalyzeAt
            if (cameraWanted && lastAnalyzeAt != 0L && silentFor > CAMERA_STALL_MS) {
                Log.w(TAG, "Kamera milczy, podpinam ponownie")
                cameraProvider?.unbindAll()
                bindCamera()
            }
            refreshPreview()
            main.postDelayed(this, CAMERA_WATCH_MS)
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (repo.onLocalChange != null) repo.onLocalChange = previousMappingChange
        GestureBus.serviceRunning.value = false
        GestureBus.paused.value = false
        main.removeCallbacks(cameraWatchdog)
        selfieOverlay.hide()
        runCatching { unregisterReceiver(screenReceiver) }
        cameraProvider?.unbindAll()
        shutter.release()
        analysisExecutor.shutdown()
        faceLandmarker?.close()
        gestureRecognizer?.close()
        faceLandmarker = null
        gestureRecognizer = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.gesturecontrol.app.STOP"
        private const val TAG = "GestureCameraService"
        private const val CHANNEL_ID = "gesture_control"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_COOLDOWN_MS = 900L    // minimalny odstęp między akcjami
        private const val DOOM_COOLDOWN_MS = 350L      // w trybie doom scrolling
        private const val MIN_HAND_SCORE = 0.65f
        private const val CAMERA_STALL_MS = 3000L
        private const val CAMERA_WATCH_MS = 2000L

        @Volatile private var instance: GestureCameraService? = null

        fun takeSelfie(context: Context): Boolean {
            val svc = instance
            if (svc == null) {
                Toast.makeText(context, "Uruchom rozpoznawanie, żeby zrobić zdjęcie", Toast.LENGTH_SHORT).show()
                return false
            }
            return svc.captureSelfie()
        }

        fun dismissSelfiePreview(): Boolean = instance?.selfieOverlay?.dismiss() == true
    }
}
