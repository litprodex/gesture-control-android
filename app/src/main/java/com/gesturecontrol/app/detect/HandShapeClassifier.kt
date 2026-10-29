package com.gesturecontrol.app.detect

import com.gesturecontrol.app.model.GestureType
import com.google.mediapipe.tasks.components.containers.Landmark
import kotlin.math.sqrt

/**
 * Dodatkowe gesty liczone geometrycznie z 21 punktów dłoni (world landmarks, w metrach),
 * które Gesture Recognizer już zwraca – bez dodatkowego modelu.
 *
 * Indeksy: 0 nadgarstek | kciuk 1-4 | wskazujący 5-8 | środkowy 9-12 | serdeczny 13-16 | mały 17-20
 */
object HandShapeClassifier {

    fun classify(lm: List<Landmark>): GestureType? {
        if (lm.size < 21) return null

        fun d(a: Int, b: Int): Float {
            val p = lm[a]; val q = lm[b]
            val dx = p.x() - q.x(); val dy = p.y() - q.y(); val dz = p.z() - q.z()
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        /** Palec wyprostowany = prawie prosty i czubek dalej od nadgarstka niż staw PIP. */
        fun extended(mcp: Int): Boolean {
            val pip = mcp + 1; val dip = mcp + 2; val tip = mcp + 3
            val bone = d(mcp, pip) + d(pip, dip) + d(dip, tip)
            val straightness = if (bone > 0f) d(mcp, tip) / bone else 0f
            return straightness > 0.80f && d(0, tip) > d(0, pip)
        }

        val index = extended(5)
        val middle = extended(9)
        val ring = extended(13)
        val pinky = extended(17)

        // 🖕 tylko środkowy wyprostowany
        if (middle && !index && !ring && !pinky) return GestureType.MIDDLE_FINGER

        return null
    }
}
