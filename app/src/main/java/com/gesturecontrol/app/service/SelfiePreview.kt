package com.gesturecontrol.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView

/**
 * Podgląd przedniej kamery nad innymi aplikacjami.
 * Dzięki niemu selfie nie powstaje w ciemno, a gesty dalej korzystają z tej samej kamery.
 */
class SelfiePreview(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var previewView: PreviewView? = null
    private var flash: View? = null
    private var savedLabel: TextView? = null

    var onClosed: (() -> Unit)? = null

    val isShowing: Boolean get() = root != null

    val surfaceProvider: Preview.SurfaceProvider?
        get() = previewView?.surfaceProvider

    /** Zwraca true, gdy okno dopiero powstało i kamerę trzeba podpiąć od nowa. */
    fun show(): Boolean {
        if (root != null) return false
        if (!Settings.canDrawOverlays(context)) return false
        return try {
            showWindow()
        } catch (e: Exception) {
            Log.e(TAG, "Nie udało się pokazać podglądu", e)
            hide()
            false
        }
    }

    private fun showWindow(): Boolean {
        val density = context.resources.displayMetrics.density
        val width = (context.resources.displayMetrics.widthPixels * 0.78f).toInt()
        val previewHeight = (width * 4f / 3f).toInt()
        val captionHeight = (40 * density).toInt()

        val preview = PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleX = -1f
            layoutParams = FrameLayout.LayoutParams(width, previewHeight)
        }
        val white = View(context).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(width, previewHeight)
        }
        val saved = TextView(context).apply {
            text = "Zapisano"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(width, previewHeight)
        }
        val caption = TextView(context).apply {
            text = "Gest robi zdjęcie"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CC111111"))
            layoutParams = FrameLayout.LayoutParams(width, captionHeight, Gravity.BOTTOM)
        }
        val closeSize = (48 * density).toInt()
        val closePad = (10 * density).toInt()
        val close = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            contentDescription = "Zamknij podgląd"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6111111"))
                setStroke((2 * density).toInt(), Color.WHITE)
            }
            layoutParams = FrameLayout.LayoutParams(closeSize, closeSize, Gravity.END).apply {
                topMargin = closePad
                marginEnd = closePad
            }
            setOnClickListener { dismiss() }
        }
        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 24 * density
                setColor(Color.BLACK)
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            addView(preview)
            addView(white)
            addView(saved)
            addView(caption)
            addView(close)
        }
        val params = WindowManager.LayoutParams(
            width,
            previewHeight + captionHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (28 * density).toInt()
        }
        windowManager.addView(container, params)
        root = container
        previewView = preview
        flash = white
        savedLabel = saved
        return true
    }

    fun hide() {
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
        root = null
        previewView = null
        flash = null
        savedLabel = null
    }

    /** Zamyka podgląd tak samo jak krzyżyk. Zwraca true, gdy okno było otwarte. */
    fun dismiss(): Boolean {
        if (root == null) return false
        hide()
        onClosed?.invoke()
        return true
    }

    /** Krótki błysk, żeby było widać, że zdjęcie zostało zrobione. */
    fun flashSaved() {
        val white = flash ?: return
        val label = savedLabel ?: return
        white.animate().cancel()
        label.animate().cancel()
        white.alpha = 0.85f
        label.alpha = 1f
        white.animate().alpha(0f).setDuration(500).start()
        label.animate().alpha(0f).setStartDelay(400).setDuration(400).start()
    }

    private companion object {
        const val TAG = "SelfiePreview"
    }
}
