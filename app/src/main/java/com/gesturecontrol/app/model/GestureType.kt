package com.gesturecontrol.app.model

enum class GestureCategory(val title: String) { FACE("Twarz"), HAND("Dłoń") }

/** Wszystkie gesty, które aplikacja potrafi rozpoznać. */
enum class GestureType(val emoji: String, val label: String, val hint: String?, val category: GestureCategory) {
    // --- Twarz (MediaPipe Face Landmarker, blendshapes) ---
    MOUTH_OPEN_ONCE("👄", "Otwarcie ust 1×", "Reaguje ok. 0,7 s po zamknięciu ust", GestureCategory.FACE),
    MOUTH_OPEN_TWICE("👄", "Otwarcie ust 2×", null, GestureCategory.FACE),
    MOUTH_OPEN_THRICE("👄", "Otwarcie ust 3×", null, GestureCategory.FACE),
    BROWS_UP("🤨", "Uniesienie brwi", null, GestureCategory.FACE),
    SMILE("😁", "Szeroki uśmiech", null, GestureCategory.FACE),

    // --- Dłoń (MediaPipe Gesture Recognizer + własna klasyfikacja z punktów dłoni) ---
    THUMB_UP("👍", "Kciuk w górę", null, GestureCategory.HAND),
    THUMB_DOWN("👎", "Kciuk w dół", null, GestureCategory.HAND),
    VICTORY("✌️", "Znak V", null, GestureCategory.HAND),
    MIDDLE_FINGER("🖕", "Środkowy palec", null, GestureCategory.HAND),
    OPEN_PALM("✋", "Otwarta dłoń", null, GestureCategory.HAND),
    CLOSED_FIST("✊", "Pięść", null, GestureCategory.HAND),
    POINTING_UP("☝️", "Palec wskazujący w górę", null, GestureCategory.HAND),
    I_LOVE_YOU("🤟", "„I love you”", null, GestureCategory.HAND);

    val display: String get() = "$emoji $label"

    companion object {
        /** Nazwy kategorii zwracane przez model gesture_recognizer.task */
        fun fromMediaPipe(name: String): GestureType? = when (name) {
            "Thumb_Up" -> THUMB_UP
            "Thumb_Down" -> THUMB_DOWN
            "Victory" -> VICTORY
            "Open_Palm" -> OPEN_PALM
            "Closed_Fist" -> CLOSED_FIST
            "Pointing_Up" -> POINTING_UP
            "ILoveYou" -> I_LOVE_YOU
            else -> null // "None" lub nieznane
        }
    }
}
