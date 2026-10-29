package com.gesturecontrol.app.detect

import com.gesturecontrol.app.model.GestureType

/**
 * Gest dłoni musi być stabilny przez [holdMs], wtedy odpala się raz.
 * Kolejne odpalenie dopiero po zmianie/opuszczeniu dłoni.
 */
class HandGestureLogic(private val onGesture: (GestureType) -> Unit) {

    var holdMs = 400L
    private val lostToleranceMs = 250L // pojedyncze "zgubione" klatki nie przerywają gestu

    private var current: GestureType? = null
    private var since = 0L
    private var lastSeen = 0L
    private var firedCurrent = false

    fun update(g: GestureType?, t: Long) {
        if (g == null) {
            if (current != null && t - lastSeen > lostToleranceMs) {
                current = null
                firedCurrent = false
            }
            return
        }
        lastSeen = t
        if (g != current) {
            current = g
            since = t
            firedCurrent = false
            return
        }
        if (!firedCurrent && t - since >= holdMs) {
            firedCurrent = true
            onGesture(g)
        }
    }
}
