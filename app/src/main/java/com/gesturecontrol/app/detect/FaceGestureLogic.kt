package com.gesturecontrol.app.detect

import com.gesturecontrol.app.model.GestureType

/**
 * Zamienia ciągły strumień blendshapes (0..1) z MediaPipe na dyskretne gesty.
 * Czysta logika – bez zależności od Androida, łatwa do testowania i strojenia.
 */
class FaceGestureLogic(private val onGesture: (GestureType) -> Unit) {

    // ---- Progi (dostosuj pod siebie) ----
    var mouthOpenThreshold = 0.40f      // jawOpen powyżej = usta otwarte
    var mouthCloseThreshold = 0.18f     // jawOpen poniżej = usta zamknięte (histereza)

    /** Tryb doom scrolling: tylko 1× otwarcie, akcja od razu przy otwarciu, minimalny próg. */
    var doomMode = false
        set(value) { if (field != value) { field = value; reset() } }

    /** Poziom wymaganego otwarcia: 0 = lekko, 1 = średnio, 2 = mocno. */
    fun setMouthLevel(level: Int) {
        val (open, close) = when (level) {
            0 -> 0.22f to 0.10f
            2 -> 0.60f to 0.28f
            else -> 0.40f to 0.18f
        }
        mouthOpenThreshold = open
        mouthCloseThreshold = close
    }

    private val doomOpenThreshold = 0.12f   // ledwo uchylone usta
    private val doomCloseThreshold = 0.06f
    private var doomArmed = true            // musi się zamknąć przed kolejnym strzałem
    private var doomAboveFrames = 0
    private val minOpenMs = 120L        // krótsze otwarcia (mówienie) są ignorowane
    private val maxOpenMs = 1500L       // dłuższe (ziewanie) zerują sekwencję
    private val sequenceGapMs = 650L    // cisza po ostatnim zamknięciu = koniec sekwencji

    private var mouthOpen = false
    private var mouthOpenedAt = 0L
    private var lastMouthClose = 0L
    private var mouthCount = 0

    private val holdStart = mutableMapOf<GestureType, Long>()
    private val fired = mutableSetOf<GestureType>()

    fun update(s: Map<String, Float>, t: Long) {
        val jaw = s["jawOpen"] ?: 0f

        if (doomMode) {
            // Strzał po 2 kolejnych klatkach powyżej progu (~70–130 ms), potem czekamy na zamknięcie
            if (jaw > doomOpenThreshold) {
                doomAboveFrames++
                if (doomArmed && doomAboveFrames >= 2) {
                    doomArmed = false
                    onGesture(GestureType.MOUTH_OPEN_ONCE)
                }
            } else {
                doomAboveFrames = 0
                if (jaw < doomCloseThreshold) doomArmed = true
            }
            mouthCount = 0
        } else {
        // --- sekwencja otwarć ust ---
        if (!mouthOpen && jaw > mouthOpenThreshold) {
            mouthOpen = true
            mouthOpenedAt = t
        } else if (mouthOpen && jaw < mouthCloseThreshold) {
            mouthOpen = false
            val dur = t - mouthOpenedAt
            if (dur in minOpenMs..maxOpenMs) {
                mouthCount++
                lastMouthClose = t
            } else {
                mouthCount = 0
            }
        }
        if (mouthOpen && t - mouthOpenedAt > maxOpenMs) mouthCount = 0
        if (!mouthOpen && mouthCount > 0 && t - lastMouthClose > sequenceGapMs) {
            when {
                mouthCount == 1 -> onGesture(GestureType.MOUTH_OPEN_ONCE)
                mouthCount == 2 -> onGesture(GestureType.MOUTH_OPEN_TWICE)
                mouthCount >= 3 -> onGesture(GestureType.MOUTH_OPEN_THRICE)
            }
            mouthCount = 0
        }
        }

        // --- gesty "przytrzymaj" ---
        hold(GestureType.BROWS_UP, (s["browInnerUp"] ?: 0f) > 0.70f, t, 500)

        val smile = ((s["mouthSmileLeft"] ?: 0f) + (s["mouthSmileRight"] ?: 0f)) / 2f
        hold(GestureType.SMILE, smile > 0.75f && jaw < 0.30f, t, 600)
    }

    /** Twarz zniknęła z kadru. */
    fun reset() {
        mouthOpen = false
        mouthCount = 0
        doomArmed = true
        doomAboveFrames = 0
        holdStart.clear()
        fired.clear()
    }

    private fun hold(g: GestureType, active: Boolean, t: Long, ms: Long) {
        if (!active) {
            holdStart.remove(g); fired.remove(g); return
        }
        val start = holdStart.getOrPut(g) { t }
        if (g !in fired && t - start >= ms) {
            fired += g
            onGesture(g)
        }
    }
}
