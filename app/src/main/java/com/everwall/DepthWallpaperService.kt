package com.everwall

import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class DepthWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = DepthEngine()

    inner class DepthEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var rawBg: Bitmap? = null; private var rawFg: Bitmap? = null
        private var cachedBg: Bitmap? = null
        private var cachedBgRot = Float.NaN; private var cachedW = 0; private var cachedH = 0
        private var surfW = 0; private var surfH = 0
        private var visible = false
        private var tf: Typeface? = null

        private val f12   = SimpleDateFormat("h:mm",       Locale.getDefault())
        private val f12s  = SimpleDateFormat("h:mm:ss",    Locale.getDefault())
        private val f24   = SimpleDateFormat("HH:mm",      Locale.getDefault())
        private val f24s  = SimpleDateFormat("HH:mm:ss",   Locale.getDefault())
        private val fDate = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

        private val tPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val dPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val dimPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        private val fgPaint  = Paint(Paint.ANTI_ALIAS_FLAG)

        private val tick = object : Runnable {
            override fun run() { drawFrame(); if (visible) handler.postDelayed(this, 1000L) }
        }

        override fun onCreate(h: SurfaceHolder) { super.onCreate(h); setTouchEventsEnabled(false); load() }

        private fun load() {
            val bgF = File(filesDir, WallpaperPrefs.FILE_ORIGINAL)
            val fgF = File(filesDir, WallpaperPrefs.FILE_FOREGROUND)
            val ftF = File(filesDir, WallpaperPrefs.FILE_FONT)
            rawBg = if (bgF.exists()) decode(bgF, 2048) else null
            rawFg = if (fgF.exists()) decode(fgF, 2048) else null
            tf    = if (ftF.exists()) try { Typeface.createFromFile(ftF) } catch(_:Exception) { null } else null
            tPaint.typeface = tf ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            dPaint.typeface = tf ?: Typeface.create("sans-serif-light", Typeface.NORMAL)
            cachedBg = null; cachedBgRot = Float.NaN; cachedW = 0; cachedH = 0
            if (surfW > 0 && surfH > 0) buildBg(surfW, surfH, WallpaperPrefs.getBgRot(this@DepthWallpaperService))
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) { load(); handler.removeCallbacks(tick); handler.post(tick) }
            else handler.removeCallbacks(tick)
        }

        override fun onSurfaceChanged(h: SurfaceHolder, f: Int, w: Int, hi: Int) {
            super.onSurfaceChanged(h, f, w, hi)
            surfW = w; surfH = hi
            WallpaperPrefs.setSurfaceSize(this@DepthWallpaperService, w, hi)
            buildBg(w, hi, WallpaperPrefs.getBgRot(this@DepthWallpaperService))
            drawFrame()
        }

        override fun onSurfaceDestroyed(h: SurfaceHolder) { visible=false; handler.removeCallbacks(tick); super.onSurfaceDestroyed(h) }
        override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }

        private fun buildBg(w: Int, h: Int, rot: Float) {
            if (w==cachedW && h==cachedH && rot==cachedBgRot && cachedBg!=null) return
            cachedW=w; cachedH=h; cachedBgRot=rot
            val bg = rawBg ?: run { cachedBg = null; return }
            val rotRad = Math.toRadians(rot.toDouble())
            val extra  = (Math.abs(Math.cos(rotRad)) + Math.abs(Math.sin(rotRad))).toFloat()
            val scale  = maxOf(w.toFloat()/bg.width, h.toFloat()/bg.height) * extra
            val sw = (bg.width*scale).toInt(); val sh = (bg.height*scale).toInt()
            val scaled = Bitmap.createScaledBitmap(bg, sw.coerceAtLeast(1), sh.coerceAtLeast(1), true)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out); c.drawColor(Color.BLACK)
            c.save(); c.translate(w/2f, h/2f); c.rotate(rot)
            c.drawBitmap(scaled, -sw/2f, -sh/2f, null); c.restore()
            cachedBg = out
        }

        private fun drawFrame() {
            val holder = surfaceHolder; var canvas: Canvas? = null
            try { canvas = holder.lockCanvas() ?: return; draw(canvas) }
            finally { canvas?.let { holder.unlockCanvasAndPost(it) } }
        }

        private fun dimmedColor(color: Int, dim: Float): Int {
            val f = (1f - dim).coerceIn(0f, 1f)
            return Color.argb(
                Color.alpha(color),
                (Color.red(color)   * f).toInt().coerceIn(0, 255),
                (Color.green(color) * f).toInt().coerceIn(0, 255),
                (Color.blue(color)  * f).toInt().coerceIn(0, 255)
            )
        }

        private fun draw(canvas: Canvas) {
            val ctx = this@DepthWallpaperService
            val W = canvas.width.toFloat(); val H = canvas.height.toFloat()

            val bgRot    = WallpaperPrefs.getBgRot(ctx)
            val clkX     = WallpaperPrefs.getClkX(ctx);   val clkY     = WallpaperPrefs.getClkY(ctx)
            val clkSz    = WallpaperPrefs.getClkSz(ctx);  val clkRot   = WallpaperPrefs.getClkRot(ctx)
            val dateX    = WallpaperPrefs.getDateX(ctx);  val dateY    = WallpaperPrefs.getDateY(ctx)
            val dateSz   = WallpaperPrefs.getDateSz(ctx); val dateRot  = WallpaperPrefs.getDateRot(ctx)
            val subjX    = WallpaperPrefs.getSubjX(ctx);  val subjY    = WallpaperPrefs.getSubjY(ctx)
            val subjSc   = WallpaperPrefs.getSubjSc(ctx); val subjRot  = WallpaperPrefs.getSubjRot(ctx)
            val use24    = WallpaperPrefs.getUse24(ctx);  val secs     = WallpaperPrefs.getSecs(ctx)
            val rawColor = WallpaperPrefs.getColor(ctx)
            val color    = if (rawColor == WallpaperPrefs.NO_COLOR) Color.WHITE else rawColor
            val bgDim    = WallpaperPrefs.getBgDim(ctx)
            val clkDim   = WallpaperPrefs.getClkDim(ctx)
            val subjDim  = WallpaperPrefs.getSubjDim(ctx)

            canvas.drawColor(Color.BLACK)
            if (cachedBg == null || bgRot != cachedBgRot) buildBg(W.toInt(), H.toInt(), bgRot)
            cachedBg?.let { canvas.drawBitmap(it, 0f, 0f, null) }

            // Background dim (black overlay = brightness reduction)
            if (bgDim > 0f) {
                dimPaint.alpha = (bgDim * 255).toInt().coerceIn(0, 255)
                canvas.drawRect(0f, 0f, W, H, dimPaint)
            }

            val now = Date()
            val tStr = when { use24&&secs -> f24s.format(now); use24 -> f24.format(now); secs -> f12s.format(now); else -> f12.format(now) }
            val dStr = fDate.format(now)

            // Clock — brightness dim via RGB scaling
            tPaint.color    = dimmedColor(color, clkDim)
            tPaint.textSize = clkSz * H
            tPaint.typeface = tf ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.save(); canvas.translate(clkX*W, clkY*H); canvas.rotate(clkRot)
            canvas.drawText(tStr, 0f, -(tPaint.descent()+tPaint.ascent())/2f, tPaint)
            canvas.restore()

            // Date — brightness dim
            val dateBaseColor = Color.argb(
                (Color.alpha(color)*0.85f).toInt().coerceIn(0,255),
                Color.red(color), Color.green(color), Color.blue(color))
            dPaint.color    = dimmedColor(dateBaseColor, clkDim)
            dPaint.textSize = dateSz * H
            dPaint.typeface = tf ?: Typeface.create("sans-serif-light", Typeface.NORMAL)
            canvas.save(); canvas.translate(dateX*W, dateY*H); canvas.rotate(dateRot)
            canvas.drawText(dStr, 0f, -(dPaint.descent()+dPaint.ascent())/2f, dPaint)
            canvas.restore()

            // Subject — brightness dim via ColorMatrix (RGB scale, alpha unchanged)
            rawFg?.let { fg ->
                val base = minOf(W/fg.width, H/fg.height); val total = base * subjSc
                val scale = (1f - subjDim).coerceIn(0f, 1f)
                val cm = ColorMatrix()
                cm.set(floatArrayOf(
                    scale, 0f,    0f,    0f, 0f,
                    0f,    scale, 0f,    0f, 0f,
                    0f,    0f,    scale, 0f, 0f,
                    0f,    0f,    0f,    1f, 0f
                ))
                fgPaint.colorFilter = ColorMatrixColorFilter(cm)
                fgPaint.alpha = 255
                canvas.save(); canvas.translate(subjX*W, subjY*H); canvas.rotate(subjRot)
                canvas.scale(total, total)
                canvas.drawBitmap(fg, -fg.width/2f, -fg.height/2f, fgPaint)
                canvas.restore()
            }
        }

        private fun decode(f: File, max: Int): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            var s = 1; while ((o.outWidth/s) > max || (o.outHeight/s) > max) s *= 2
            return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
        }
    }
}
