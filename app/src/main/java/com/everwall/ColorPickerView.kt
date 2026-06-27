package com.everwall

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class ColorPickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onColorChanged: ((Int) -> Unit)? = null
    var onColorCommitted: ((Int) -> Unit)? = null

    private val hsv = floatArrayOf(0f, 1f, 1f)

    var color: Int
        get() = Color.HSVToColor(hsv)
        set(value) {
            Color.colorToHSV(value, hsv)
            svBitmap = null
            invalidate()
        }

    private val svRect  = RectF()
    private val hueRect = RectF()
    private var svBitmap: Bitmap? = null

    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val thumbWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val thumbColorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44000000; style = Paint.Style.FILL
    }

    private val hueColors = intArrayOf(
        0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
        0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(),
        0xFFFF0000.toInt()
    )
    private val huePos = floatArrayOf(0f, 1f/6, 2f/6, 3f/6, 4f/6, 5f/6, 1f)

    private enum class Zone { NONE, SV, HUE }
    private var zone = Zone.NONE

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val dp = resources.displayMetrics.density
        val hueH = 28f * dp
        val gap  = 12f * dp
        svRect.set(0f, 0f, w.toFloat(), h - hueH - gap)
        hueRect.set(0f, h - hueH, w.toFloat(), h.toFloat())
        huePaint.shader = LinearGradient(
            hueRect.left, 0f, hueRect.right, 0f,
            hueColors, huePos, Shader.TileMode.CLAMP
        )
        svBitmap = null
        invalidate()
    }

    private fun buildSV() {
        val RW = 128; val RH = 128
        val px = IntArray(RW * RH)
        val tmp = FloatArray(3)
        tmp[0] = hsv[0]
        for (y in 0 until RH) for (x in 0 until RW) {
            tmp[1] = x.toFloat() / (RW - 1)
            tmp[2] = 1f - y.toFloat() / (RH - 1)
            px[y * RW + x] = Color.HSVToColor(tmp)
        }
        svBitmap = Bitmap.createBitmap(px, RW, RH, Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(canvas: Canvas) {
        if (svRect.isEmpty) return
        val dp = resources.displayMetrics.density
        val r  = 14f * dp

        // SV square
        if (svBitmap == null) buildSV()
        val svPath = Path().also { it.addRoundRect(svRect, r, r, Path.Direction.CW) }
        canvas.save(); canvas.clipPath(svPath)
        canvas.drawBitmap(svBitmap!!, null, svRect, bmpPaint)
        canvas.restore()

        // SV thumb
        val tx = svRect.left + hsv[1] * svRect.width()
        val ty = svRect.top  + (1f - hsv[2]) * svRect.height()
        val tr = 11f * dp
        canvas.drawCircle(tx, ty, tr + 4f, thumbShadowPaint)
        canvas.drawCircle(tx, ty, tr + 2.5f, thumbWhitePaint)
        thumbColorPaint.color = color
        canvas.drawCircle(tx, ty, tr, thumbColorPaint)

        // Hue bar
        val hueR = hueRect.height() / 2f
        val huePath = Path().also { it.addRoundRect(hueRect, hueR, hueR, Path.Direction.CW) }
        canvas.save(); canvas.clipPath(huePath)
        canvas.drawRect(hueRect, huePaint)
        canvas.restore()

        // Hue thumb
        val hx = hueRect.left + (hsv[0] / 360f) * hueRect.width()
        val hy = hueRect.centerY()
        val hr = hueR + 2f * dp
        canvas.drawCircle(hx, hy, hr + 3f, thumbShadowPaint)
        canvas.drawCircle(hx, hy, hr + 1.5f, thumbWhitePaint)
        thumbColorPaint.color = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        canvas.drawCircle(hx, hy, hr, thumbColorPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                zone = when {
                    svRect.contains(e.x, e.y)  -> Zone.SV
                    hueRect.contains(e.x, e.y) -> Zone.HUE
                    else -> Zone.NONE
                }
                if (zone != Zone.NONE) parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        if (zone == Zone.NONE) return false
        when (zone) {
            Zone.SV  -> {
                hsv[1] = ((e.x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
                hsv[2] = (1f - (e.y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
            }
            Zone.HUE -> {
                hsv[0] = ((e.x - hueRect.left) / hueRect.width()).coerceIn(0f, 1f) * 360f
                svBitmap = null
            }
            else -> {}
        }
        onColorChanged?.invoke(color)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            onColorCommitted?.invoke(color)
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        invalidate()
        return true
    }
}
