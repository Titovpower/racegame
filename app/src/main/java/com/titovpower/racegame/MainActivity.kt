package com.titovpower.racegame

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.opengl.GLSurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout

class MainActivity : Activity() {
    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: GlRenderer
    private lateinit var sound: EngineSound

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val controls = Controls()
        val telemetry = Telemetry()
        sound = EngineSound(telemetry)

        surface = GLSurfaceView(this)
        GlRenderer.setUp(surface)
        renderer = GlRenderer(this, controls, telemetry)
        surface.setRenderer(renderer)
        val root = FrameLayout(this)
        root.addView(surface)
        root.addView(ControlOverlayView(this, controls, telemetry))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        surface.onResume()
        renderer.onResume()
        sound.start()
        hideSystemBars()
    }

    override fun onPause() {
        surface.onPause()
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
