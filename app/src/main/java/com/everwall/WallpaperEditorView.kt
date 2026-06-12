package com.everwall

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.text.SimpleDateFormat
import java.util.*

class WallpaperEditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, def: Int = 0
) : View(context, attrs, def) {

    enum class ActiveElement { NONE, CLOCK, DATE, SUBJECT }

    var clockX = 0.5f;  var clockY   = 0.28f; var clockSz  = 0.12f;  var clockRot = 0f
    var dateX  = 0.5f;  var dateY    = 0.42f; var dateSz   = 0.034f; var dateRot  = 0f
    var subjX  = 0.5f;  var subjY    = 0.68f; var subjSc   = 0.5f;   var subjRot  = 0f
    var bgRot  = 0f
    var use24hr = false; var showSeconds = false
    var clockColor = Color.WHITE

    // Dim: 0 = no dim, 1 = fully dimmed (brightness reduced, not transparency)
    var bgDim    = 0f
    var clockDim = 0f
    var subjDim  = 0f

    var activeElement = ActiveElement.NONE
        private set

    private var rawBg: Bitmap? = null
    private var rawFg: Bitmap? = null
    private var tf:    Typeface? = null

    private var pDownX = 0f; private var pDownY = 0f
    private var dLastX = 0f; private var dLastY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val f = d.scaleFactor
                when (activeElement) {
                    ActiveElement.CLOCK   -> clockSz = (clockSz * f).coerceIn(0.04f, 0.45f)
                    ActiveElement.DATE    -> dateSz  = (dateSz  * f).coerceIn(0.015f, 0.20f)
                    ActiveElement.SUBJECT -> subjSc  = (subjSc  * f).coerceIn(0.05f, 6f)
                    else -> {}
                }
                invalidate(); return true
            }
        })

    private val tPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dimPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val fgPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selGlow  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF; style = Paint.Style.STROKE; strokeWidth = 10f }
    private val selDash  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f) }

    private val f12   = SimpleDateFormat("h:mm",       Locale.getDefault())
    private val f12s  = SimpleDateFormat("h:mm:ss",    Locale.getDefault())
    private val f24   = SimpleDateFormat("HH:mm",      Locale.getDefault())
    private val f24s  = SimpleDateFormat("HH:mm:ss",   Locale.getDefault())
    private val fDate = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

    private val tick = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 1000L) }
    }

    fun setData(bg: Bitmap?, fg: Bitmap?, typeface: Typeface?) {
        rawBg = bg; rawFg = fg; tf = typeface
        applyTypeface()
        invalidate()
    }

    fun setFontOnly(typeface: Typeface?) {
        tf = typeface; applyTypeface(); invalidate()
    }

    private fun applyTypeface() {
        tPaint.typeface = tf ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        dPaint.typeface = tf ?: Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    // Reduce brightness by scaling RGB channels — keeps alpha intact
    private fun dimmedColor(color: Int, dim: Float): Int {
        val f = (1f - dim).coerceIn(0f, 1f)
        return Color.argb(
            Color.alpha(color),
            (Color.red(color)   * f).toInt().coerceIn(0, 255),
            (Color.green(color) * f).toInt().coerceIn(0, 255),
            (Color.blue(color)  * f).toInt().coerceIn(0, 255)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val W = width.toFloat(); val H = height.toFloat()
        if (W == 0f || H == 0f) return
        val dp = resources.displayMetrics.density

        // Background
        canvas.drawColor(Color.BLACK)
        rawBg?.let { bg ->
            val rotRad = Math.toRadians(bgRot.toDouble())
            val extra  = (Math.abs(Math.cos(rotRad)) + Math.abs(Math.sin(rotRad))).toFloat()
            val scale  = maxOf(W / bg.width, H / bg.height) * extra
            canvas.save()
            canvas.translate(W / 2f, H / 2f); canvas.rotate(bgRot); canvas.scale(scale, scale)
            canvas.drawBitmap(bg, -bg.width / 2f, -bg.height / 2f, null)
            canvas.restore()
        }
        // Dim background brightness via black overlay
        if (bgDim > 0f) {
            dimPaint.alpha = (bgDim * 255).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, W, H, dimPaint)
        }

        val now  = Date()
        val tStr = when { use24hr && showSeconds -> f24s.format(now); use24hr -> f24.format(now)
                          showSeconds -> f12s.format(now); else -> f12.format(now) }
        val dStr = fDate.format(now)

        // Clock — dim by reducing RGB brightness
        val clkPx = clockSz * H
        tPaint.color    = dimmedColor(clockColor, clockDim)
        tPaint.textSize = clkPx
        tPaint.typeface = tf ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.save(); canvas.translate(clockX * W, clockY * H); canvas.rotate(clockRot)
        canvas.drawText(tStr, 0f, -(tPaint.descent() + tPaint.ascent()) / 2f, tPaint)
        canvas.restore()
        if (activeElement == ActiveElement.CLOCK) {
            val tw = tPaint.measureText(tStr)
            canvas.save(); canvas.translate(clockX * W, clockY * H); canvas.rotate(clockRot)
            val r = RectF(-tw/2f-16f*dp, -clkPx/2f-12f*dp, tw/2f+16f*dp, clkPx/2f+12f*dp)
            canvas.drawRoundRect(r, 12f*dp, 12f*dp, selGlow)
            canvas.drawRoundRect(r, 12f*dp, 12f*dp, selDash)
            canvas.restore()
        }

        // Date — dim by reducing RGB brightness
        val datePx = dateSz * H
        val dateColor = dimmedColor(
            Color.argb((Color.alpha(clockColor) * 0.85f).toInt().coerceIn(0,255),
                Color.red(clockColor), Color.green(clockColor), Color.blue(clockColor)),
            clockDim)
        dPaint.color    = dateColor
        dPaint.textSize = datePx
        dPaint.typeface = tf ?: Typeface.create("sans-serif-light", Typeface.NORMAL)
        canvas.save(); canvas.translate(dateX * W, dateY * H); canvas.rotate(dateRot)
        canvas.drawText(dStr, 0f, -(dPaint.descent() + dPaint.ascent()) / 2f, dPaint)
        canvas.restore()
        if (activeElement == ActiveElement.DATE) {
            val dw = dPaint.measureText(dStr)
            canvas.save(); canvas.translate(dateX * W, dateY * H); canvas.rotate(dateRot)
            val r = RectF(-dw/2f-14f*dp, -datePx/2f-10f*dp, dw/2f+14f*dp, datePx/2f+10f*dp)
            canvas.drawRoundRect(r, 10f*dp, 10f*dp, selGlow)
            canvas.drawRoundRect(r, 10f*dp, 10f*dp, selDash)
            canvas.restore()
        }

        // Subject — dim brightness via ColorMatrix on RGB channels, alpha unchanged
        rawFg?.let { fg ->
            val base  = minOf(W / fg.width, H / fg.height)
            val total = base * subjSc
            val scale = (1f - subjDim).coerceIn(0f, 1f)
            val cm = ColorMatrix()
            cm.set(floatArrayOf(
                scale, 0f,    0f,    0f, 0f,
                0f,    scale, 0f,    0f, 0f,
                0f,    0f,    scale, 0f, 0f,
                0f,    0f,    0f,    1f, 0f   // alpha untouched
            ))
            fgPaint.colorFilter = ColorMatrixColorFilter(cm)
            fgPaint.alpha = 255
            canvas.save(); canvas.translate(subjX * W, subjY * H); canvas.rotate(subjRot)
            canvas.scale(total, total)
            canvas.drawBitmap(fg, -fg.width / 2f, -fg.height / 2f, fgPaint)
            canvas.restore()
            if (activeElement == ActiveElement.SUBJECT) {
                val hw = fg.width  * total / 2f + 14f*dp
                val hh = fg.height * total / 2f + 14f*dp
                canvas.save(); canvas.translate(subjX * W, subjY * H); canvas.rotate(subjRot)
                canvas.drawRoundRect(RectF(-hw, -hh, hw, hh), 16f, 16f, selGlow)
                canvas.drawRoundRect(RectF(-hw, -hh, hw, hh), 16f, 16f, selDash)
                canvas.restore()
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pDownX = e.x; pDownY = e.y; dLastX = e.x; dLastY = e.y; isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !scaleDetector.isInProgress) {
                    if (!isDragging && (Math.abs(e.x - pDownX) > 10f || Math.abs(e.y - pDownY) > 10f))
                        isDragging = true
                    if (isDragging) {
                        val dx = e.x - dLastX; val dy = e.y - dLastY
                        when (activeElement) {
                            ActiveElement.CLOCK   -> { clockX = (clockX + dx/width).coerceIn(0.02f,0.98f); clockY = (clockY + dy/height).coerceIn(0.02f,0.98f) }
                            ActiveElement.DATE    -> { dateX  = (dateX  + dx/width).coerceIn(0.02f,0.98f); dateY  = (dateY  + dy/height).coerceIn(0.02f,0.98f) }
                            ActiveElement.SUBJECT -> { subjX  = (subjX  + dx/width).coerceIn(0.02f,0.98f); subjY  = (subjY  + dy/height).coerceIn(0.02f,0.98f) }
                            else -> {}
                        }
                        invalidate()
                    }
                    dLastX = e.x; dLastY = e.y
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging && !scaleDetector.isInProgress) handleTap(e.x, e.y)
                isDragging = false; return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun handleTap(x: Float, y: Float) {
        val W = width.toFloat(); val H = height.toFloat()
        val dp = resources.displayMetrics.density
        tPaint.textSize = clockSz * H
        val tw  = tPaint.measureText(timeStr())
        val clockHit = hitTest(x, y, clockX*W, clockY*H, tw+40f*dp, clockSz*H+30f*dp, clockRot)
        dPaint.textSize = dateSz * H
        val dw  = dPaint.measureText(fDate.format(Date()))
        val dateHit = hitTest(x, y, dateX*W, dateY*H, dw+36f*dp, dateSz*H+24f*dp, dateRot)
        var subjHit = false
        rawFg?.let { fg ->
            val total = minOf(W/fg.width, H/fg.height) * subjSc
            subjHit = hitTest(x, y, subjX*W, subjY*H, fg.width*total+40f, fg.height*total+40f, subjRot)
        }
        val hits = listOf(
            ActiveElement.CLOCK   to (if (clockHit) dist(x,y,clockX*W,clockY*H) else Float.MAX_VALUE),
            ActiveElement.DATE    to (if (dateHit)  dist(x,y,dateX*W, dateY*H)  else Float.MAX_VALUE),
            ActiveElement.SUBJECT to (if (subjHit)  dist(x,y,subjX*W, subjY*H)  else Float.MAX_VALUE)
        ).filter { it.second < Float.MAX_VALUE }
        activeElement = if (hits.isEmpty()) ActiveElement.NONE
                        else hits.minByOrNull { it.second }!!.first
        invalidate()
    }

    private fun timeStr() = when {
        use24hr && showSeconds -> f24s.format(Date()); use24hr -> f24.format(Date())
        showSeconds -> f12s.format(Date()); else -> f12.format(Date())
    }
    private fun hitTest(tx:Float,ty:Float,cx:Float,cy:Float,w:Float,h:Float,rot:Float): Boolean {
        val rad = Math.toRadians(-rot.toDouble())
        val cos = Math.cos(rad).toFloat(); val sin = Math.sin(rad).toFloat()
        val dx = tx-cx; val dy = ty-cy
        return Math.abs(dx*cos - dy*sin) <= w/2f && Math.abs(dx*sin + dy*cos) <= h/2f
    }
    private fun dist(x1:Float,y1:Float,x2:Float,y2:Float): Float {
        val dx=x1-x2; val dy=y1-y2; return Math.sqrt((dx*dx+dy*dy).toDouble()).toFloat()
    }

    override fun onAttachedToWindow()   { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
}
