package com.everwall

import android.graphics.*
import android.graphics.drawable.Drawable

class BannerConcaveDrawable(fillColor: Int, private val cornerRadius: Float) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    private val path = Path()

    fun setFillColor(color: Int) {
        paint.color = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val r = cornerRadius

        path.reset()
        path.moveTo(0f, 0f)                           // top-left (sharp — phone handles these)
        path.lineTo(w, 0f)                            // top edge
        path.lineTo(w, h - r)                         // right edge
        // Bottom-right convex rounded corner
        path.arcTo(RectF(w - r * 2f, h - r * 2f, w, h), 0f, 90f)
        path.lineTo(r, h)                             // bottom edge
        // Bottom-left convex rounded corner
        path.arcTo(RectF(0f, h - r * 2f, r * 2f, h), 90f, 90f)
        path.lineTo(0f, 0f)                           // left edge
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
