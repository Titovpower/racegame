package com.titovpower.racegame

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Transparent overlay with the steering wheel at the bottom of the screen and the HUD.
 *
 * Touch the wheel and drag: left/right steers, up accelerates, down brakes.
 * Letting go re-centers the wheel and releases both pedals.
 */
class ControlOverlayView(
    context: Context,
    private val controls: Controls,
    private val telemetry: Telemetry,
) : View(context) {

    private val density = resources.displayMetrics.density
    private var wheelRadius = 0f
    private var cx = 0f
    private var cy = 0f

    private var activePointer = -1
    private var startX = 0f
    private var startY = 0f
    private var steer = 0f
    /** -1 (full brake) .. 1 (full throttle). */
    private var pedal = 0f

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 30, 30, 34)
    }
    private val rimHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 90, 90, 98)
    }
    private val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(230, 55, 55, 60)
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 40, 40, 44) }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(229, 57, 53)
    }
    private val emblemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }
    private val gasPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(76, 175, 80) }
    private val brakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        wheelRadius = min(h * 0.17f, w * 0.13f)
        cx = w / 2f
        cy = h - wheelRadius - 14f * density
        rimPaint.strokeWidth = wheelRadius * 0.16f
        rimHighlight.strokeWidth = wheelRadius * 0.04f
        spokePaint.strokeWidth = wheelRadius * 0.13f
        markPaint.strokeWidth = wheelRadius * 0.16f
        hintPaint.textSize = 12f * density
        speedPaint.textSize = 40f * density
        labelPaint.textSize = 15f * density
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activePointer == -1) {
                    val i = event.actionIndex
                    val x = event.getX(i)
                    val y = event.getY(i)
                    if (hypot(x - cx, y - cy) <= wheelRadius * 1.5f) {
                        activePointer = event.getPointerId(i)
                        startX = x
                        startY = y
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(activePointer)
                if (i >= 0) {
                    steer = clamp((event.getX(i) - startX) / (wheelRadius * 1.1f), -1f, 1f)
                    pedal = clamp(-(event.getY(i) - startY) / (wheelRadius * 0.8f), -1f, 1f)
                    publish()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointer) release()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    private fun release() {
        activePointer = -1
        steer = 0f
        pedal = 0f
        publish()
    }

    private fun publish() {
        controls.steer = steer
        controls.throttle = max(pedal, 0f)
        controls.brake = max(-pedal, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        drawHud(canvas)
        drawPedalGauge(canvas)
        drawWheel(canvas)
        postInvalidateOnAnimation()
    }

    private fun drawWheel(canvas: Canvas) {
        val r = wheelRadius
        // Hints around the wheel.
        canvas.drawText("▲ GAS", cx, cy - r * 1.28f, hintPaint)
        canvas.drawText("▼ BRAKE", cx, cy + r * 1.05f + hintPaint.textSize, hintPaint)
        canvas.drawText("◀", cx - r * 1.35f, cy + hintPaint.textSize * 0.4f, hintPaint)
        canvas.drawText("▶", cx + r * 1.35f, cy + hintPaint.textSize * 0.4f, hintPaint)

        canvas.save()
        // The wheel follows the finger a little vertically, and turns with the steering.
        canvas.translate(cx, cy - pedal * r * 0.18f)
        canvas.rotate(steer * 110f)
        val ringR = r * 0.88f
        canvas.drawCircle(0f, 0f, ringR, rimPaint)
        canvas.drawCircle(0f, 0f, ringR + rimPaint.strokeWidth * 0.35f, rimHighlight)
        // Three spokes: left, right, down.
        canvas.drawLine(-ringR, 0f, ringR, 0f, spokePaint)
        canvas.drawLine(0f, 0f, 0f, ringR, spokePaint)
        canvas.drawCircle(0f, 0f, r * 0.27f, hubPaint)
        canvas.drawCircle(0f, 0f, r * 0.1f, emblemPaint)
        // Red top-center marker shows how far the wheel is turned.
        rect.set(-ringR, -ringR, ringR, ringR)
        canvas.drawArc(rect, 260f, 20f, false, markPaint)
        canvas.restore()
    }

    private fun drawPedalGauge(canvas: Canvas) {
        val r = wheelRadius
        val w = 10f * density
        val left = cx + r * 1.6f
        val top = cy - r
        val bottom = cy + r
        rect.set(left, top, left + w, bottom)
        canvas.drawRoundRect(rect, w / 2, w / 2, trackPaint)
        val mid = (top + bottom) / 2f
        if (pedal > 0f) {
            rect.set(left, mid - pedal * (mid - top), left + w, mid)
            canvas.drawRoundRect(rect, w / 2, w / 2, gasPaint)
        } else if (pedal < 0f) {
            rect.set(left, mid, left + w, mid - pedal * (bottom - mid))
            canvas.drawRoundRect(rect, w / 2, w / 2, brakePaint)
        }
    }

    private fun drawHud(canvas: Canvas) {
        val pad = 16f * density
        val speedText = String.format(Locale.US, "%d", telemetry.speedKmh.toInt())
        canvas.drawText(speedText, pad, pad + speedPaint.textSize, speedPaint)
        val unitX = pad + speedPaint.measureText(speedText) + 6f * density
        canvas.drawText("km/h", unitX, pad + speedPaint.textSize, labelPaint)

        // Ground type chip.
        val onGrass = telemetry.surfaceName == "GRASS"
        chipPaint.color = if (onGrass) Color.argb(220, 56, 142, 60) else Color.argb(220, 66, 66, 72)
        val chipText = telemetry.surfaceName
        val chipTop = pad + speedPaint.textSize + 10f * density
        val chipW = labelPaint.measureText(chipText) + 20f * density
        rect.set(pad, chipTop, pad + chipW, chipTop + labelPaint.textSize + 12f * density)
        canvas.drawRoundRect(rect, 8f * density, 8f * density, chipPaint)
        canvas.drawText(chipText, pad + 10f * density, chipTop + labelPaint.textSize + 3f * density, labelPaint)

        // Lap times, top right.
        val right = width - pad
        labelPaint.textAlign = Paint.Align.RIGHT
        val line = labelPaint.textSize * 1.4f
        canvas.drawText("LAP  " + formatTime(telemetry.lapTime), right, pad + line, labelPaint)
        canvas.drawText("LAST " + formatTime(telemetry.lastLap), right, pad + line * 2, labelPaint)
        canvas.drawText("BEST " + formatTime(telemetry.bestLap), right, pad + line * 3, labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
    }

    private fun formatTime(seconds: Float): String {
        if (seconds <= 0f) return "--:--.--"
        val total = (seconds * 100).toInt()
        return String.format(Locale.US, "%d:%02d.%02d", total / 6000, (total / 100) % 60, total % 100)
    }
}
