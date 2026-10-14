package com.titovpower.racegame

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

class MainActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var sound: EngineSound

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val controls = Controls()
        val telemetry = Telemetry()
        sound = EngineSound(telemetry)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(MultisampleConfigChooser())
            setRenderer(GameRenderer(controls, telemetry))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        val root = FrameLayout(this)
        root.addView(glView)
        root.addView(ControlOverlayView(this, controls, telemetry))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        sound.start()
        hideSystemBars()
    }

    override fun onPause() {
        glView.onPause()
        sound.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
    }
}

/** Prefers 4x MSAA with a 24-bit depth buffer, falling back to plainer configs. */
private class MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val attempts = listOf(
            config(24, 4),
            config(16, 4),
            config(24, 0),
            config(16, 0),
        )
        for (attribs in attempts) {
            val count = IntArray(1)
            val configs = arrayOfNulls<EGLConfig>(1)
            if (egl.eglChooseConfig(display, attribs, configs, 1, count) && count[0] > 0) {
                configs[0]?.let { return it }
            }
        }
        throw IllegalStateException("No suitable EGL config")
    }

    private fun config(depth: Int, samples: Int): IntArray {
        val base = mutableListOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, depth,
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        )
        if (samples > 0) base += listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples)
        base += EGL10.EGL_NONE
        return base.toIntArray()
    }

    companion object {
        private const val EGL_OPENGL_ES2_BIT = 4
    }
}
