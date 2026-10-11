package com.example.serverdashboard.ui.util

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager

/**
 * Real frosted-glass helper supporting Android 12-16 RenderEffect and Window blurBehind,
 * with zero-overhead fallbacks for API 26-30.
 */
object GlassEffectHelper {

    fun applyWindowBlur(window: Window?, blurRadiusDp: Int = 36) {
        if (window == null) return
        val context = window.context
        val density = context.resources.displayMetrics.density
        val radiusPx = (blurRadiusDp * density).toInt()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes.blurBehindRadius = radiusPx
                window.setDimAmount(0.45f)
            } catch (e: Throwable) {
                window.setDimAmount(0.6f)
            }
        } else {
            window.setDimAmount(0.6f)
        }
    }

    fun applyFrostedEffect(view: View, radiusDp: Float = 28f) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val density = view.resources.displayMetrics.density
                val radiusPx = radiusDp * density
                val blurEffect = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
                view.setRenderEffect(blurEffect)
            } catch (e: Throwable) {
                // Graceful fallback: view retains its translucent background
            }
        }
    }

    fun clearFrostedEffect(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                view.setRenderEffect(null)
            } catch (e: Throwable) {
                // ignore
            }
        }
    }
}
