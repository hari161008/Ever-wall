package com.everwall

import android.content.Context
import android.graphics.*
import android.os.Build
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
    var subjX  = 0.5f;  var subjY    = 0.5f; var subjSc   = 1.0f;   var subjRot  = 0f
    var bgRot  = 0f
    var use24hr = false; var showSeconds = false
    var clockColor = Color.WHITE
    var bgDim = 0f; var clockDim = 0f; var subjDim = 0f
    var bgSat = 1f;  var subjSat = 1f

    var activeElement = ActiveElement.NONE
        private set

    private var rawBg: Bitmap? = null
    private var rawFg: Bitmap? = null
    private var tf: Typeface? = null

    // Touch state
    private var pDownX = 0f; private var pDownY = 0f
    private var dLastX = 0f; private var dLastY = 0f
    private var isDragging = false
    private var scalingInProgress = false   // true from pinch-start until next ACTION_DOWN

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                scalingInProgress = true; isDragging = false; return true
            }
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
    private val selGlow  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF; style = Paint.Style.STROKE; strokeWidth = 10f }
    private val selDash  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }

    // Cached reference width for clock so the selection box never reflows on tick
    private var _refClkW   = 0f
    private var _refClkKey = ""

    private fun clockRefWidth(): Float {
        val key = "$use24hr/$showSeconds/${tPaint.textSize.toInt()}"
        if (key != _refClkKey) {
            val cal = Calendar.getInstance()
            var max = 0f
            for (h in 0..23) for (m in 0..59) {
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); cal.set(Calendar.SECOND, 0)
                max = maxOf(max, tPaint.measureText(timeStr(cal.time)))
            }
            _refClkW = max; _refClkKey = key
        }
        return _refClkW
    }

    private val f12   = SimpleDateFormat("h:mm",       Locale.getDefault())
    private val f12s  = SimpleDateFormat("h:mm:ss",    Locale.getDefault())
    private val f24   = SimpleDateFormat("HH:mm",      Locale.getDefault())
    private val f24s  = SimpleDateFormat("HH:mm:ss",   Locale.getDefault())
    private val fDate = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

    private val tick = object : Runnable {
        override fun run() {
            if (isAttachedToWindow) { invalidate(); postDelayed(this, 1000L) }
        }
    }

    fun setData(bg: Bitmap?, fg: Bitmap?, typeface: Typeface?) {
        rawBg = bg; rawFg = fg; tf = typeface; applyTypeface(); invalidate()
    }
    fun setBg(bg: Bitmap?) { rawBg = bg; invalidate() }
    fun setFg(fg: Bitmap?) { rawFg = fg; invalidate() }
    fun setFontOnly(typeface: Typeface?) { tf = typeface; applyTypeface(); invalidate() }

    private fun applyTypeface() {
        // Use monospace for clock so digit widths are stable across time changes
        val clockTf = tf ?: Typeface.MONOSPACE
        tPaint.typeface = clockTf
        // Also request tabular numbers on API 26+ for variable fonts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { tPaint.fontVariationSettings = "'tnum' 1" } catch (_: Exception) {}
        }
        dPaint.typeface = tf ?: Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    private fun dimmedColor(color: Int, dim: Float): Int {
        val f = (1f - dim).coerceIn(0f, 1f)
        return Color.argb(Color.alpha(color),
            (Color.red(color)   * f).toInt().coerceIn(0, 255),
            (Color.green(color) * f).toInt().coerceIn(0, 255),
            (Color.blue(color)  * f).toInt().coerceIn(0, 255))
    }

    override fun onDraw(canvas: Canvas) {
        val W = width.toFloat(); val H = height.toFloat()
        if (W == 0f || H == 0f) return
        val dp = resources.displayMetrics.density

        // ── Background ──────────────────────────────────────────────────────
        canvas.drawColor(Color.BLACK)
        rawBg?.let { bg ->
            val rotRad = Math.toRadians(bgRot.toDouble())
            val extra  = (Math.abs(Math.cos(rotRad)) + Math.abs(Math.sin(rotRad))).toFloat()
            val scale  = maxOf(W / bg.width, H / bg.height) * extra
            val bgCm = ColorMatrix(); bgCm.setSaturation(bgSat)
            val bgDrawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(bgCm) }
            canvas.save()
            canvas.translate(W / 2f, H / 2f); canvas.rotate(bgRot); canvas.scale(scale, scale)
            canvas.drawBitmap(bg, -bg.width / 2f, -bg.height / 2f, bgDrawPaint)
            canvas.restore()
        }
        if (bgDim > 0f) {
            dimPaint.alpha = (bgDim * 255).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, W, H, dimPaint)
        }

        val now  = Date()
        val tStr = timeStr(now); val dStr = fDate.format(now)

        // ── Clock ────────────────────────────────────────────────────────────
        val clkPx = clockSz * H
        tPaint.color    = dimmedColor(clockColor, clockDim)
        tPaint.textSize = clkPx
        canvas.save(); canvas.translate(clockX * W, clockY * H); canvas.rotate(clockRot)
        canvas.drawText(tStr, 0f, -(tPaint.descent() + tPaint.ascent()) / 2f, tPaint)
        canvas.restore()
        if (activeElement == ActiveElement.CLOCK) {
            val tw = clockRefWidth()
            canvas.save(); canvas.translate(clockX * W, clockY * H); canvas.rotate(clockRot)
            val r = RectF(-tw/2f-16f*dp, -clkPx/2f-12f*dp, tw/2f+16f*dp, clkPx/2f+12f*dp)
            canvas.drawRoundRect(r, 12f*dp, 12f*dp, selGlow)
            canvas.drawRoundRect(r, 12f*dp, 12f*dp, selDash)
            canvas.restore()
        }

        // ── Date ─────────────────────────────────────────────────────────────
        val datePx = dateSz * H
        val dateBaseColor = Color.argb((Color.alpha(clockColor)*0.85f).toInt().coerceIn(0,255),
            Color.red(clockColor), Color.green(clockColor), Color.blue(clockColor))
        dPaint.color    = dimmedColor(dateBaseColor, clockDim)
        dPaint.textSize = datePx
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

        // ── Subject ───────────────────────────────────────────────────────────
        rawFg?.let { fg ->
            val base  = minOf(W / fg.width, H / fg.height)
            val total = base * subjSc
            val dim   = (1f - subjDim).coerceIn(0f, 1f)
            val cm = ColorMatrix()
            val dimCm = ColorMatrix(); dimCm.set(floatArrayOf(
                dim,0f,0f,0f,0f,  0f,dim,0f,0f,0f,
                0f,0f,dim,0f,0f,  0f,0f,0f,1f,0f))
            val satCm = ColorMatrix(); satCm.setSaturation(subjSat)
            cm.setConcat(dimCm, satCm)
            fgPaint.colorFilter = ColorMatrixColorFilter(cm); fgPaint.alpha = 255
            canvas.save(); canvas.translate(subjX * W, subjY * H); canvas.rotate(subjRot)
            canvas.scale(total, total)
            canvas.drawBitmap(fg, -fg.width / 2f, -fg.height / 2f, fgPaint)
            canvas.restore()
            if (activeElement == ActiveElement.SUBJECT) {
                val hw = fg.width*total/2f+14f*dp; val hh = fg.height*total/2f+14f*dp
                canvas.save(); canvas.translate(subjX * W, subjY * H); canvas.rotate(subjRot)
                canvas.drawRoundRect(RectF(-hw,-hh,hw,hh), 16f, 16f, selGlow)
                canvas.drawRoundRect(RectF(-hw,-hh,hw,hh), 16f, 16f, selDash)
                canvas.restore()
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Fresh gesture — clear scale flag
                scalingInProgress = false
                pDownX = e.x; pDownY = e.y; dLastX = e.x; dLastY = e.y; isDragging = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger down — cancel any drag
                isDragging = false; return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // One finger lifts during pinch — sync drag origin to remaining finger
                val remainIdx = if (e.actionIndex == 0) 1 else 0
                dLastX = e.getX(remainIdx); dLastY = e.getY(remainIdx)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !scaleDetector.isInProgress) {
                    if (scalingInProgress) {
                        // First MOVE after scale ends — just sync origin, no jump
                        dLastX = e.x; dLastY = e.y
                    } else {
                        if (!isDragging &&
                            (Math.abs(e.x - pDownX) > 10f || Math.abs(e.y - pDownY) > 10f))
                            isDragging = true
                        if (isDragging) {
                            val dx = e.x - dLastX; val dy = e.y - dLastY
                            when (activeElement) {
                                ActiveElement.CLOCK   -> { clockX=(clockX+dx/width).coerceIn(0.02f,0.98f); clockY=(clockY+dy/height).coerceIn(0.02f,0.98f) }
                                ActiveElement.DATE    -> { dateX=(dateX+dx/width).coerceIn(0.02f,0.98f);  dateY=(dateY+dy/height).coerceIn(0.02f,0.98f) }
                                ActiveElement.SUBJECT -> { subjX=(subjX+dx/width).coerceIn(0.02f,0.98f); subjY=(subjY+dy/height).coerceIn(0.02f,0.98f) }
                                else -> {}
                            }
                            invalidate()
                        }
                        dLastX = e.x; dLastY = e.y
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasDragging = isDragging; val wasScale = scalingInProgress
                isDragging = false
                // Only fire tap if this was a true tap (no drag, no scale)
                if (!wasDragging && !wasScale && !scaleDetector.isInProgress)
                    handleTap(e.x, e.y)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun handleTap(x: Float, y: Float) {
        val W = width.toFloat(); val H = height.toFloat(); val dp = resources.displayMetrics.density
        tPaint.textSize = clockSz * H
        val tw = clockRefWidth()
        val clockHit = hitTest(x,y,clockX*W,clockY*H, tw+40f*dp, clockSz*H+30f*dp, clockRot)
        dPaint.textSize = dateSz * H
        val dw = dPaint.measureText(fDate.format(Date()))
        val dateHit  = hitTest(x,y, dateX*W, dateY*H, dw+36f*dp, dateSz*H+24f*dp, dateRot)
        var subjHit  = false
        rawFg?.let { fg ->
            val total = minOf(W/fg.width,H/fg.height)*subjSc
            subjHit = hitTest(x,y,subjX*W,subjY*H,fg.width*total+40f,fg.height*total+40f,subjRot)
        }
        val hits = listOf(
            ActiveElement.CLOCK   to (if (clockHit) dist(x,y,clockX*W,clockY*H) else Float.MAX_VALUE),
            ActiveElement.DATE    to (if (dateHit)  dist(x,y,dateX*W, dateY*H)  else Float.MAX_VALUE),
            ActiveElement.SUBJECT to (if (subjHit)  dist(x,y,subjX*W, subjY*H)  else Float.MAX_VALUE)
        ).filter { it.second < Float.MAX_VALUE }
        activeElement = if (hits.isEmpty()) ActiveElement.NONE else hits.minByOrNull { it.second }!!.first
        invalidate()
    }

    private fun timeStr(d: Date = Date()) = when {
        use24hr && showSeconds -> f24s.format(d); use24hr -> f24.format(d)
        showSeconds -> f12s.format(d); else -> f12.format(d)
    }
    private fun hitTest(tx:Float,ty:Float,cx:Float,cy:Float,w:Float,h:Float,rot:Float): Boolean {
        val rad=Math.toRadians(-rot.toDouble()); val cos=Math.cos(rad).toFloat(); val sin=Math.sin(rad).toFloat()
        val dx=tx-cx; val dy=ty-cy
        return Math.abs(dx*cos-dy*sin)<=w/2f && Math.abs(dx*sin+dy*cos)<=h/2f
    }
    private fun dist(x1:Float,y1:Float,x2:Float,y2:Float): Float { val dx=x1-x2; val dy=y1-y2; return Math.sqrt((dx*dx+dy*dy).toDouble()).toFloat() }

    override fun onAttachedToWindow()   { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
}
