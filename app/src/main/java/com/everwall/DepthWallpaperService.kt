package com.everwall

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
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

        // Auto-hide state
        private var isScreenOff    = false
        private var clockHidden    = false
        private var clockFadeAlpha = 1f

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

        private val FADE_STEP_MS     = 16L
        private val FADE_DURATION_MS = 280f
        private var fadeStartTimeMs  = 0L
        private val fadeInTick = object : Runnable {
            override fun run() {
                val t = ((android.os.SystemClock.elapsedRealtime() - fadeStartTimeMs) / FADE_DURATION_MS)
                    .coerceIn(0f, 1f)
                val inv = 1f - t
                clockFadeAlpha = 1f - inv * inv * inv
                drawFrame()
                if (t < 1f) handler.postDelayed(this, FADE_STEP_MS)
                else { clockFadeAlpha = 1f; clockHidden = false }
            }
        }

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOff = true
                        if (WallpaperPrefs.getAutoHide(this@DepthWallpaperService)) {
                            clockHidden    = true
                            clockFadeAlpha = 0f
                            handler.removeCallbacks(fadeInTick)
                            drawFrame()
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        isScreenOff = false
                        // Do not touch clockHidden here — draw() will evaluate keyguard
                        // state on the very next tick and start the fade-in if appropriate.
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        // Screen fully unlocked — start fade-in immediately if hidden
                        if (WallpaperPrefs.getAutoHide(this@DepthWallpaperService) && clockHidden) {
                            clockHidden = false
                            fadeStartTimeMs = android.os.SystemClock.elapsedRealtime()
                            handler.removeCallbacks(fadeInTick)
                            handler.post(fadeInTick)
                        }
                    }
                }
            }
        }

        private var lastSlot      = -1
        private var lastNightMode = -1

        private val configReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_CONFIGURATION_CHANGED) return
                if (WallpaperPrefs.getWallpaperMode(this@DepthWallpaperService) != WallpaperPrefs.MODE_SYSTEM_THEME) return
                val nightBit = resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                if (nightBit != lastNightMode) {
                    lastNightMode = nightBit
                    load()
                    if (visible) drawFrame()
                }
            }
        }

        override fun onCreate(h: SurfaceHolder) {
            super.onCreate(h)
            setTouchEventsEnabled(false)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(
                this@DepthWallpaperService, screenReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(
                this@DepthWallpaperService, configReceiver,
                IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            load()
        }

        private fun load() {
            lastNightMode = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val ftF = File(filesDir, WallpaperPrefs.FILE_FONT)
            tf    = if (ftF.exists()) try { Typeface.createFromFile(ftF) } catch (_: Exception) { null } else null
            tPaint.typeface = tf ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            dPaint.typeface = tf ?: Typeface.create("sans-serif-light", Typeface.NORMAL)

            // Resolve initial hide state from keyguard — avoids false-hiding on home screen
            // when the wallpaper becomes visible after switching apps.
            if (WallpaperPrefs.getAutoHide(this@DepthWallpaperService)) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val locked = isScreenOff || km?.isKeyguardLocked == true
                clockHidden    = locked
                clockFadeAlpha = if (locked) 0f else 1f
                handler.removeCallbacks(fadeInTick)
            } else {
                clockHidden    = false
                clockFadeAlpha = 1f
            }

            val slot = WallpaperPrefs.activeSlot(this@DepthWallpaperService)
            lastSlot = slot
            loadForSlot(slot)
        }

        private fun loadForSlot(slot: Int) {
            val bgF = WallpaperPrefs.getBgFileForSlot(this@DepthWallpaperService, slot)
            val fgF = WallpaperPrefs.getFgFileForSlot(this@DepthWallpaperService, slot)
            rawBg?.recycle(); rawBg = if (bgF.exists()) decode(bgF, 2048) else null
            rawFg?.recycle(); rawFg = if (fgF.exists()) decode(fgF, 2048) else null
            cachedBg?.recycle(); cachedBg = null
            cachedBgRot = Float.NaN; cachedW = 0; cachedH = 0
            if (surfW > 0 && surfH > 0) {
                val p = WallpaperPrefs.loadSlot(this@DepthWallpaperService, slot)
                buildBg(surfW, surfH, p.bgRot)
            }
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
            val slot = WallpaperPrefs.activeSlot(this@DepthWallpaperService)
            if (slot != lastSlot) { lastSlot = slot; loadForSlot(slot) }
            val p = WallpaperPrefs.loadSlot(this@DepthWallpaperService, slot)
            buildBg(w, hi, p.bgRot)
            drawFrame()
        }

        override fun onSurfaceDestroyed(h: SurfaceHolder) {
            visible = false; handler.removeCallbacks(tick); super.onSurfaceDestroyed(h)
        }

        override fun onDestroy() {
            handler.removeCallbacks(tick); handler.removeCallbacks(fadeInTick)
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
            try { unregisterReceiver(configReceiver) } catch (_: Exception) {}
            rawBg?.recycle(); rawFg?.recycle(); cachedBg?.recycle()
            super.onDestroy()
        }

        private fun buildBg(w: Int, h: Int, rot: Float) {
            if (w == cachedW && h == cachedH && rot == cachedBgRot && cachedBg != null) return
            cachedW = w; cachedH = h; cachedBgRot = rot
            val bg = rawBg ?: run { cachedBg?.recycle(); cachedBg = null; return }
            val rotRad = Math.toRadians(rot.toDouble())
            val extra  = (Math.abs(Math.cos(rotRad)) + Math.abs(Math.sin(rotRad))).toFloat()
            val scale  = maxOf(w.toFloat() / bg.width, h.toFloat() / bg.height) * extra
            val sw = (bg.width  * scale).toInt().coerceAtLeast(1)
            val sh = (bg.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bg, sw, sh, true)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out); c.drawColor(Color.BLACK)
            c.save(); c.translate(w / 2f, h / 2f); c.rotate(rot)
            c.drawBitmap(scaled, -sw / 2f, -sh / 2f, null); c.restore()
            cachedBg?.recycle()
            cachedBg = out
        }

        private fun drawFrame() {
            val holder = surfaceHolder; var canvas: Canvas? = null
            try { canvas = holder.lockCanvas() ?: return; draw(canvas) }
            finally { canvas?.let { holder.unlockCanvasAndPost(it) } }
        }

        private fun dimmedColor(color: Int, dim: Float, alphaScale: Float = 1f): Int {
            val f = (1f - dim).coerceIn(0f, 1f)
            return Color.argb(
                (Color.alpha(color) * alphaScale).toInt().coerceIn(0, 255),
                (Color.red(color)   * f).toInt().coerceIn(0, 255),
                (Color.green(color) * f).toInt().coerceIn(0, 255),
                (Color.blue(color)  * f).toInt().coerceIn(0, 255))
        }

        private fun draw(canvas: Canvas) {
            val ctx = this@DepthWallpaperService
            val W = canvas.width.toFloat(); val H = canvas.height.toFloat()

            val slot = WallpaperPrefs.activeSlot(ctx)
            if (slot != lastSlot) { lastSlot = slot; loadForSlot(slot) }

            val p     = WallpaperPrefs.loadSlot(ctx, slot)
            val color = if (p.color == WallpaperPrefs.NO_COLOR) Color.WHITE else p.color

            canvas.drawColor(Color.BLACK)
            if (cachedBg == null || p.bgRot != cachedBgRot) buildBg(W.toInt(), H.toInt(), p.bgRot)
            cachedBg?.let {
                val bgCm = ColorMatrix(); bgCm.setSaturation(p.bgSat)
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(bgCm) }
                canvas.drawBitmap(it, 0f, 0f, if (p.bgSat == 1f) null else bgPaint)
            }

            if (p.bgDim > 0f) {
                dimPaint.alpha = (p.bgDim * 255).toInt().coerceIn(0, 255)
                canvas.drawRect(0f, 0f, W, H, dimPaint)
            }

            val now  = Date()
            val tStr = when {
                p.use24 && p.secs -> f24s.format(now); p.use24 -> f24.format(now)
                p.secs            -> f12s.format(now); else     -> f12.format(now)
            }
            val dStr = fDate.format(now)

            // ── Auto-hide logic ───────────────────────────────────────────────
            // Only hides when the keyguard (lock screen) is active or screen is off.
            // Opening/closing apps does NOT trigger hiding because we ask KeyguardManager
            // directly on every tick rather than relying on screen on/off events.
            val autoHide = WallpaperPrefs.getAutoHide(ctx)
            if (autoHide) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val shouldHide = isScreenOff || km?.isKeyguardLocked == true

                when {
                    shouldHide && !clockHidden -> {
                        // Just entered lock screen — hide immediately
                        clockHidden = true; clockFadeAlpha = 0f
                        handler.removeCallbacks(fadeInTick)
                    }
                    !shouldHide && clockHidden && !handler.hasCallbacks(fadeInTick) -> {
                        // Just left lock screen — start fade-in
                        clockHidden = false
                        fadeStartTimeMs = android.os.SystemClock.elapsedRealtime()
                        handler.post(fadeInTick)
                    }
                }
            } else {
                clockFadeAlpha = 1f; clockHidden = false
            }

            val alpha = if (autoHide) clockFadeAlpha else 1f
            if (alpha > 0f && !isPreview()) {
                tPaint.color    = dimmedColor(color, p.clkDim, alpha)
                tPaint.textSize = p.clockSz * H
                tPaint.typeface = tf ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                canvas.save(); canvas.translate(p.clockX * W, p.clockY * H); canvas.rotate(p.clockRot)
                canvas.drawText(tStr, 0f, -(tPaint.descent() + tPaint.ascent()) / 2f, tPaint)
                canvas.restore()

                val dateBase = Color.argb(
                    (Color.alpha(color) * 0.85f).toInt().coerceIn(0, 255),
                    Color.red(color), Color.green(color), Color.blue(color))
                dPaint.color    = dimmedColor(dateBase, p.clkDim, alpha)
                dPaint.textSize = p.dateSz * H
                dPaint.typeface = tf ?: Typeface.create("sans-serif-light", Typeface.NORMAL)
                canvas.save(); canvas.translate(p.dateX * W, p.dateY * H); canvas.rotate(p.dateRot)
                canvas.drawText(dStr, 0f, -(dPaint.descent() + dPaint.ascent()) / 2f, dPaint)
                canvas.restore()
            }

            rawFg?.let { fg ->
                val base  = minOf(W / fg.width, H / fg.height); val total = base * p.subjSc
                val dim   = (1f - p.subjDim).coerceIn(0f, 1f)
                val dimCm = ColorMatrix(); dimCm.set(floatArrayOf(
                    dim, 0f, 0f, 0f, 0f,  0f, dim, 0f, 0f, 0f,  0f, 0f, dim, 0f, 0f,  0f, 0f, 0f, 1f, 0f))
                val satCm = ColorMatrix(); satCm.setSaturation(p.subjSat)
                val cm    = ColorMatrix(); cm.setConcat(dimCm, satCm)
                fgPaint.colorFilter = ColorMatrixColorFilter(cm)
                fgPaint.alpha = 255
                canvas.save(); canvas.translate(p.subjX * W, p.subjY * H); canvas.rotate(p.subjRot)
                canvas.scale(total, total)
                canvas.drawBitmap(fg, -fg.width / 2f, -fg.height / 2f, fgPaint)
                canvas.restore()
            }
        }

        private fun decode(f: File, max: Int): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var s = 1; while ((o.outWidth / s) > max || (o.outHeight / s) > max) s *= 2
            return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
        }
    }
}
