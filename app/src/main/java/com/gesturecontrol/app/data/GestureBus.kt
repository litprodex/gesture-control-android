package com.gesturecontrol.app.data

import kotlinx.coroutines.flow.MutableStateFlow

/** Prosty, współdzielony stan między usługą a UI. */
object GestureBus {
    val serviceRunning = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    val lastEvent = MutableStateFlow<String?>(null)
}
