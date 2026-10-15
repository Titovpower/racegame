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
    private val settings: Settings,
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
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 12, 14, 18) }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 200, 16, 46) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
        setShadowLayer(8f, 0f, 3f, Color.BLACK)
    }
    private val rect = RectF()
    private val revPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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
        rowPaint.textSize = 12f * density
        bigPaint.textSize = 72f * density
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (settingsTouch(event)) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activePointer == -1) {
                    val i = event.actionIndex
                    val x = event.getX(i)
                    val y = event.getY(i)
                    if (telemetry.picking) {
                        pickerTouch(x, y)
                    } else if (telemetry.finished && hypot(x - cx, y - cy) > wheelRadius * 1.5f) {
                        controls.restart = true
                    } else if (hypot(x - cx, y - cy) <= wheelRadius * 1.5f) {
                        activePointer = event.getPointerId(i)
                        startX = x
                        startY = y
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(activePointer)
                if (i >= 0) {
                    steer = clamp((event.getX(i) - startX) / (wheelRadius * 1.1f) * settings.steering, -1f, 1f)
                    val travel = -(event.getY(i) - startY) / (wheelRadius * 0.8f)
                    pedal = clamp(travel * if (travel > 0f) settings.throttle else settings.brake, -1f, 1f)
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

    /** Picker layout: arrows either side of the car name, START button below. */
    private val leftArrow = RectF()
    private val rightArrow = RectF()
    private val startButton = RectF()

    private fun pickerTouch(x: Float, y: Float) {
        when {
            leftArrow.contains(x, y) -> controls.pick.addAndGet(-1)
            rightArrow.contains(x, y) -> controls.pick.addAndGet(1)
            startButton.contains(x, y) -> controls.start = true
            safeBox.contains(x, y) -> {
                settings.safeDriving = !settings.safeDriving
                controls.safeDriving = settings.safeDriving
            }
        }
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
        val error = telemetry.error
        if (error != null || telemetry.loading) {
            drawStatus(canvas, error)
            postInvalidateOnAnimation()
            return
        }
        if (telemetry.picking) {
            drawPicker(canvas)
            postInvalidateOnAnimation()
            return
        }
        drawHud(canvas)
        drawStandings(canvas)
        drawRaceMessages(canvas)
        drawPedalGauge(canvas)
        drawWheel(canvas)
        drawSettings(canvas)
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

        // Rev counter: gear and a bar that turns red near the redline.
        val spec = telemetry.carSpec
        val barTop = rect.bottom + 10f * density
        val barW = 150f * density
        val barH = 8f * density
        val frac = clamp(telemetry.rpm / spec.redline, 0f, 1f)
        rect.set(pad, barTop, pad + barW, barTop + barH)
        canvas.drawRoundRect(rect, barH / 2, barH / 2, trackPaint)
        revPaint.color = when {
            frac > 0.92f -> Color.rgb(229, 57, 53)
            frac > 0.75f -> Color.rgb(255, 179, 0)
            else -> Color.rgb(250, 250, 250)
        }
        rect.set(pad, barTop, pad + barW * frac, barTop + barH)
        canvas.drawRoundRect(rect, barH / 2, barH / 2, revPaint)
        val gearText = if (telemetry.gear <= 0) "R" else "${telemetry.gear}"
        canvas.drawText(gearText, pad + barW + 8f * density, barTop + barH + 2f * density, labelPaint)
        canvas.drawText(
            String.format(Locale.US, "%d rpm", (telemetry.rpm / 10f).toInt() * 10),
            pad, barTop + barH + labelPaint.textSize + 4f * density, hintPaint.also { it.textAlign = Paint.Align.LEFT },
        )
        hintPaint.textAlign = Paint.Align.CENTER

        // Position, lap and lap times, top right.
        val right = width - pad
        speedPaint.textAlign = Paint.Align.RIGHT
        labelPaint.textAlign = Paint.Align.RIGHT
        val total = telemetry.standings.size
        canvas.drawText("P${telemetry.position}/$total", right, pad + speedPaint.textSize, speedPaint)
        val line = labelPaint.textSize * 1.4f
        val top = pad + speedPaint.textSize + 4f * density
        canvas.drawText("LAP ${telemetry.lap}/${telemetry.totalLaps}", right, top + line, labelPaint)
        canvas.drawText("TIME " + formatTime(telemetry.lapTime), right, top + line * 2, labelPaint)
        canvas.drawText("LAST " + formatTime(telemetry.lastLap), right, top + line * 3, labelPaint)
        canvas.drawText("BEST " + formatTime(telemetry.bestLap), right, top + line * 4, labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
        speedPaint.textAlign = Paint.Align.LEFT
    }

    /** Race ranking in the bottom-left corner, leader first. */
    private fun drawStandings(canvas: Canvas) {
        val rows = telemetry.standings
        if (rows.isEmpty()) return
        val pad = 12f * density
        val rowH = 17f * density
        val headerH = 20f * density
        val panelW = 196f * density
        val bottom = height - pad
        val top = bottom - headerH - rows.size * rowH - 6f * density
        rect.set(pad, top, pad + panelW, bottom)
        canvas.drawRoundRect(rect, 10f * density, 10f * density, panelPaint)
        rowPaint.color = Color.argb(200, 255, 255, 255)
        canvas.drawText("RANKING", pad + 10f * density, top + headerH - 5f * density, rowPaint)
        for ((k, row) in rows.withIndex()) {
            val y0 = top + headerH + k * rowH
            if (row.isPlayer) {
                rect.set(pad + 4f * density, y0, pad + panelW - 4f * density, y0 + rowH)
                canvas.drawRoundRect(rect, 6f * density, 6f * density, highlightPaint)
            }
            val baseline = y0 + rowH - 4.5f * density
            rowPaint.color = Color.WHITE
            rowPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${k + 1}", pad + 24f * density, baseline, rowPaint)
            rowPaint.textAlign = Paint.Align.LEFT
            dotPaint.color = Color.rgb((row.color shr 16) and 0xFF, (row.color shr 8) and 0xFF, row.color and 0xFF)
            canvas.drawCircle(pad + 36f * density, y0 + rowH / 2f, 5f * density, dotPaint)
            canvas.drawText(row.name, pad + 47f * density, baseline, rowPaint)
            rowPaint.color = Color.argb(if (row.isPlayer) 230 else 170, 255, 255, 255)
            canvas.drawText(row.car, pad + 112f * density, baseline, rowPaint)
            rowPaint.color = Color.WHITE
            if (row.finished) {
                rowPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText("\u2691", pad + panelW - 10f * density, baseline, rowPaint)
                rowPaint.textAlign = Paint.Align.LEFT
            }
        }
    }

    /** Start countdown and the finish banner. */
    private fun drawRaceMessages(canvas: Canvas) {
        val midY = height * 0.32f
        val cd = telemetry.countdown
        if (cd > 0f) {
            // 3.5 s countdown: "3", "2", "1" each for a second after a short pause.
            val n = kotlin.math.ceil(cd - 0.5f).toInt()
            if (n in 1..3) canvas.drawText("$n", width / 2f, midY, bigPaint)
        } else if (telemetry.raceTime in 0.001f..1.2f) {
            canvas.drawText("GO!", width / 2f, midY, bigPaint)
        }
        if (telemetry.finished) {
            val y = drawWin(canvas) + 30f * density
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "5 laps done · finished P${telemetry.position} in ${formatTime(telemetry.finishTime)}",
                width / 2f, y, labelPaint,
            )
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("Tap anywhere to race again", width / 2f, y + 22f * density, hintPaint)
        }
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 16, 46) }
    private val safeBox = RectF()
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val winPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 20, 30)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
    }
    private val winOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
    }

    /** Car selection before the race: brand and model, real figures, and START. */
    private fun drawPicker(canvas: Canvas) {
        val spec = telemetry.carSpec
        val d = density
        val panelW = min(width * 0.62f, 460f * d)
        val panelH = 186f * d
        val left = (width - panelW) / 2f
        val top = height - panelH - 16f * d
        rect.set(left, top, left + panelW, top + panelH)
        canvas.drawRoundRect(rect, 16f * d, 16f * d, panelPaint)

        labelPaint.textAlign = Paint.Align.CENTER
        speedPaint.textAlign = Paint.Align.CENTER
        hintPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("CHOOSE YOUR CAR", width / 2f, top + 22f * d, hintPaint)
        val oldSize = speedPaint.textSize
        speedPaint.textSize = 26f * d
        canvas.drawText(spec.brand.uppercase(Locale.US), width / 2f, top + 52f * d, speedPaint)
        speedPaint.textSize = oldSize
        canvas.drawText(spec.model, width / 2f, top + 72f * d, labelPaint)
        val stats = String.format(
            Locale.US, "%d hp   ·   0-100 km/h %.1f s   ·   %d km/h   ·   %s",
            spec.powerHp, spec.zeroTo100, spec.topSpeedKmh.toInt(), if (spec.awd) "AWD" else "RWD",
        )
        canvas.drawText(stats, width / 2f, top + 94f * d, hintPaint)

        // Arrows.
        arrowPaint.textSize = 34f * d
        leftArrow.set(left, top + 20f * d, left + 64f * d, top + 90f * d)
        rightArrow.set(left + panelW - 64f * d, top + 20f * d, left + panelW, top + 90f * d)
        canvas.drawText("◀", leftArrow.centerX(), leftArrow.centerY() + 12f * d, arrowPaint)
        canvas.drawText("▶", rightArrow.centerX(), rightArrow.centerY() + 12f * d, arrowPaint)

        // "Safe driving" checkbox: guardrails along the track.
        val box = 18f * d
        val label = "SAFE DRIVING  ·  fences along the track"
        labelPaint.textAlign = Paint.Align.LEFT
        val rowW = box + 10f * d + labelPaint.measureText(label)
        val bx = width / 2f - rowW / 2f
        val by = top + 112f * d
        safeBox.set(bx - 8f * d, by - 8f * d, bx + rowW + 8f * d, by + box + 8f * d)
        rect.set(bx, by, bx + box, by + box)
        canvas.drawRoundRect(rect, 4f * d, 4f * d, if (settings.safeDriving) startPaint else trackPaint)
        boxPaint.strokeWidth = 2f * d
        canvas.drawRoundRect(rect, 4f * d, 4f * d, boxPaint)
        if (settings.safeDriving) {
            canvas.drawLine(bx + box * 0.22f, by + box * 0.52f, bx + box * 0.43f, by + box * 0.74f, boxPaint)
            canvas.drawLine(bx + box * 0.43f, by + box * 0.74f, bx + box * 0.8f, by + box * 0.28f, boxPaint)
        }
        canvas.drawText(label, bx + box + 10f * d, by + box * 0.5f + labelPaint.textSize * 0.35f, labelPaint)
        labelPaint.textAlign = Paint.Align.CENTER

        // START button.
        val bw = 150f * d
        val bh = 34f * d
        startButton.set(width / 2f - bw / 2f, top + panelH - bh - 10f * d, width / 2f + bw / 2f, top + panelH - 10f * d)
        canvas.drawRoundRect(startButton, bh / 2f, bh / 2f, startPaint)
        canvas.drawText("START RACE", width / 2f, startButton.centerY() + labelPaint.textSize * 0.35f, labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
        speedPaint.textAlign = Paint.Align.LEFT
    }

    /** Red "YOU WIN" popping up above the player's car after the last lap. */
    private fun drawWin(canvas: Canvas): Float {
        val age = telemetry.raceTime - telemetry.finishTime
        // Pop: grow past full size, then settle.
        val t = clamp(age / 0.45f, 0f, 1f)
        val scale = if (t < 1f) {
            val e = 1f - (1f - t) * (1f - t)
            e * 1.25f
        } else {
            1f + 0.25f * kotlin.math.exp(-(age - 0.45f) * 8f)
        }
        val sx = telemetry.carScreenX
        val sy = telemetry.carScreenY
        val x = if (sx in 0f..1f) sx * width else width / 2f
        val y = maxOf(if (sy in 0f..1f) sy * height else height * 0.35f, 80f * density)
        val size = 64f * density * scale
        winPaint.textSize = size
        winOutline.textSize = size
        winOutline.strokeWidth = size * 0.06f
        winPaint.setShadowLayer(10f, 0f, 4f, Color.argb(160, 0, 0, 0))
        canvas.drawText("YOU WIN", x, y, winOutline)
        canvas.drawText("YOU WIN", x, y, winPaint)
        return y
    }

    // Settings: a gear button in the bottom-right corner opens a panel with two tabs: sliders for
    // the control sensitivities, and the OpenGL graphics options.
    private var settingsOpen = false
    /** 0 = controls, 1 = OpenGL graphics. */
    private var settingsTab = 0
    private val gearButton = RectF()
    private val settingsPanel = RectF()
    private val closeButton = RectF()
    private val tabButtons = Array(2) { RectF() }
    private val sliderTracks = Array(3) { RectF() }
    private val choiceButtons = GraphicsOption.values().map { o -> Array(o.choices.size) { RectF() } }
    private var draggingSlider = -1
    private val gearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 235, 235, 240) }
    private val gearHole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 30, 32, 38) }
    private val sliderFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val choicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
    private val choiceText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private fun sliderValue(k: Int) = when (k) { 0 -> settings.steering; 1 -> settings.throttle; else -> settings.brake }

    private fun setSlider(k: Int, x: Float) {
        val t = clamp((x - sliderTracks[k].left) / sliderTracks[k].width(), 0f, 1f)
        val v = Settings.MIN + t * (Settings.MAX - Settings.MIN)
        when (k) { 0 -> settings.steering = v; 1 -> settings.throttle = v; else -> settings.brake = v }
    }

    /** Handles touches on the gear button and the open settings panel; true if consumed. */
    private fun settingsTouch(event: MotionEvent): Boolean {
        val i = event.actionIndex
        val x = event.getX(i)
        val y = event.getY(i)
        if (!settingsOpen) {
            if (activePointer == -1 && !telemetry.picking && event.actionMasked == MotionEvent.ACTION_DOWN && gearButton.contains(x, y)) {
                settingsOpen = true
                release()
                return true
            }
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (closeButton.contains(x, y) || !settingsPanel.contains(x, y)) {
                    settingsOpen = false
                    draggingSlider = -1
                    return true
                }
                for (t in 0 until 2) if (tabButtons[t].contains(x, y)) settingsTab = t
                if (settingsTab == 0) {
                    for (k in 0 until 3) {
                        val tr = sliderTracks[k]
                        if (y in tr.top - 18f * density..tr.bottom + 18f * density && x in tr.left - 14f * density..tr.right + 14f * density) {
                            draggingSlider = k
                            setSlider(k, x)
                        }
                    }
                } else {
                    for ((o, buttons) in GraphicsOption.values().zip(choiceButtons)) {
                        for ((c, r) in buttons.withIndex()) if (r.contains(x, y)) settings.setGraphics(o, c)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (draggingSlider >= 0) setSlider(draggingSlider, event.getX(0))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> draggingSlider = -1
        }
        return true
    }

    private fun drawSettings(canvas: Canvas) {
        val d = density
        val gr = 22f * d
        val gx = width - gr - 18f * d
        val gy = height - gr - 18f * d
        gearButton.set(gx - gr * 1.4f, gy - gr * 1.4f, gx + gr * 1.4f, gy + gr * 1.4f)
        canvas.drawCircle(gx, gy, gr * 1.25f, panelPaint)
        // Gear: a disc with eight teeth and a hole.
        canvas.save()
        canvas.translate(gx, gy)
        for (k in 0 until 8) {
            canvas.rotate(45f)
            rect.set(-gr * 0.17f, -gr * 0.82f, gr * 0.17f, -gr * 0.45f)
            canvas.drawRoundRect(rect, gr * 0.05f, gr * 0.05f, gearPaint)
        }
        canvas.drawCircle(0f, 0f, gr * 0.58f, gearPaint)
        canvas.drawCircle(0f, 0f, gr * 0.25f, gearHole)
        canvas.restore()
        if (!settingsOpen) return

        val pw = min(width * 0.8f, 480f * d)
        val ph = min(height - 16f * d, 340f * d)
        val left = (width - pw) / 2f
        val top = (height - ph) / 2f
        settingsPanel.set(left, top, left + pw, top + ph)
        panelPaint.alpha = 230
        canvas.drawRoundRect(settingsPanel, 16f * d, 16f * d, panelPaint)
        panelPaint.alpha = 150

        // Tabs.
        val tabH = 30f * d
        val tabW = (pw - 40f * d) / 2f
        val tabNames = arrayOf("CONTROL SENSITIVITY", "OPENGL GRAPHICS")
        choiceText.textSize = 12f * d
        for (t in 0 until 2) {
            val r = tabButtons[t]
            r.set(left + 20f * d + t * tabW, top + 12f * d, left + 20f * d + (t + 1) * tabW, top + 12f * d + tabH)
            canvas.drawRoundRect(r, tabH / 2f, tabH / 2f, if (t == settingsTab) sliderFill else choicePaint)
            canvas.drawText(tabNames[t], r.centerX(), r.centerY() + choiceText.textSize * 0.35f, choiceText)
        }

        val bw = 110f * d
        val bh = 30f * d
        closeButton.set(width / 2f - bw / 2f, top + ph - bh - 10f * d, width / 2f + bw / 2f, top + ph - 10f * d)
        val bodyTop = top + 12f * d + tabH + 8f * d
        val bodyBottom = closeButton.top - 6f * d
        if (settingsTab == 0) drawSensitivity(canvas, left, pw, bodyTop, bodyBottom) else drawGraphics(canvas, left, pw, bodyTop, bodyBottom)

        canvas.drawRoundRect(closeButton, bh / 2f, bh / 2f, startPaint)
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("DONE", width / 2f, closeButton.centerY() + labelPaint.textSize * 0.35f, labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawSensitivity(canvas: Canvas, left: Float, pw: Float, top: Float, bottom: Float) {
        val d = density
        val names = arrayOf("Steering wheel", "Acceleration", "Brakes")
        val rowH = (bottom - top) / 3f
        labelPaint.textAlign = Paint.Align.LEFT
        for (k in 0 until 3) {
            val y = top + rowH * k + rowH * 0.42f
            canvas.drawText(names[k], left + 20f * d, y, labelPaint)
            val v = sliderValue(k)
            val pct = String.format(Locale.US, "%d%%", (v * 100).toInt())
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(pct, left + pw - 20f * d, y, labelPaint)
            labelPaint.textAlign = Paint.Align.LEFT
            val tr = sliderTracks[k]
            tr.set(left + 20f * d, y + 12f * d, left + pw - 20f * d, y + 18f * d)
            canvas.drawRoundRect(tr, 3f * d, 3f * d, trackPaint)
            val t = (v - Settings.MIN) / (Settings.MAX - Settings.MIN)
            val kx = tr.left + t * tr.width()
            rect.set(tr.left, tr.top, kx, tr.bottom)
            canvas.drawRoundRect(rect, 3f * d, 3f * d, sliderFill)
            canvas.drawCircle(kx, tr.centerY(), 10f * d, knobPaint)
        }
    }

    /** One row per OpenGL option: its name and a button per choice, the current one in red. */
    private fun drawGraphics(canvas: Canvas, left: Float, pw: Float, top: Float, bottom: Float) {
        val d = density
        val options = GraphicsOption.values()
        val rowH = (bottom - top) / options.size
        val bh = min(rowH - 6f * d, 26f * d)
        val cw = 58f * d
        val gap = 6f * d
        val saved = labelPaint.textSize
        labelPaint.textSize = min(saved, rowH * 0.5f)
        choiceText.textSize = min(12f * d, bh * 0.5f)
        labelPaint.textAlign = Paint.Align.LEFT
        for ((k, o) in options.withIndex()) {
            val cy = top + rowH * (k + 0.5f)
            canvas.drawText(o.label, left + 20f * d, cy + labelPaint.textSize * 0.35f, labelPaint)
            val selected = settings.graphics(o)
            var x = left + pw - 20f * d - o.choices.size * cw - (o.choices.size - 1) * gap
            for ((c, name) in o.choices.withIndex()) {
                val r = choiceButtons[k][c]
                r.set(x, cy - bh / 2f, x + cw, cy + bh / 2f)
                canvas.drawRoundRect(r, bh / 2f, bh / 2f, if (c == selected) sliderFill else choicePaint)
                canvas.drawText(name, r.centerX(), r.centerY() + choiceText.textSize * 0.35f, choiceText)
                x += cw + gap
            }
        }
        labelPaint.textSize = saved
    }

    /** "Loading" while the world is built, or the error if it could not be. */
    private fun drawStatus(canvas: Canvas, error: String?) {
        labelPaint.textAlign = Paint.Align.CENTER
        if (error == null) {
            canvas.drawText("LOADING THE TRACK…", width / 2f, height / 2f, labelPaint)
        } else {
            // Wrap the message over a few lines.
            val words = error.split(" ")
            val lines = ArrayList<String>()
            var line = ""
            for (w in words) {
                val next = if (line.isEmpty()) w else "$line $w"
                if (labelPaint.measureText(next) > width * 0.8f && line.isNotEmpty()) {
                    lines.add(line)
                    line = w
                } else {
                    line = next
                }
            }
            lines.add(line)
            var y = height / 2f - lines.size * labelPaint.textSize * 0.7f
            for (l in lines) {
                canvas.drawText(l, width / 2f, y, labelPaint)
                y += labelPaint.textSize * 1.4f
            }
        }
        labelPaint.textAlign = Paint.Align.LEFT
    }

    private fun formatTime(seconds: Float): String {
        if (seconds <= 0f) return "--:--.--"
        val total = (seconds * 100).toInt()
        return String.format(Locale.US, "%d:%02d.%02d", total / 6000, (total / 100) % 60, total % 100)
    }
}
