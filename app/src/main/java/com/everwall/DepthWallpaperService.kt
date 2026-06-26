package com.everwall

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class DepthWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = DepthEngine()

    inner class DepthEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())

        // Normal wallpaper layers
        private var rawBg: Bitmap? = null
        private var rawFg: Bitmap? = null
        private var cachedBg: Bitmap? = null
        private var cachedBgRot = Float.NaN; private var cachedW = 0; private var cachedH = 0

        // Music art layer (separate from normal bg)
        private var rawMusicArt: Bitmap? = null
        private var cachedMusicArt: Bitmap? = null
        private var cachedMusicArtW = 0; private var cachedMusicArtH = 0

        // Previous music art for cross-fade between tracks
        private var cachedMusicArtPrev: Bitmap? = null
        private var crossFadeAlpha = 1f   // 0=prev art, 1=new art
        private val CROSS_FADE_DURATION = 800f
        private var crossFadeStartMs = 0L
        private val crossFadeTick = object : Runnable {
            override fun run() {
                val t = ((SystemClock.elapsedRealtime() - crossFadeStartMs) / CROSS_FADE_DURATION).coerceIn(0f, 1f)
                crossFadeAlpha = (0.5f - 0.5f * Math.cos(Math.PI * t).toFloat())
                drawFrame()
                if (t < 1f) handler.postDelayed(this, 16L)
                else { crossFadeAlpha = 1f; cachedMusicArtPrev?.recycle(); cachedMusicArtPrev = null }
            }
        }

        // Music art fade transition
        private var musicArtAlpha  = 0f   // current blended alpha (0=normal, 1=music art)
        private var musicArtTarget = 0f   // destination alpha
        private val MUSIC_FADE_DURATION = 1200f
        private var musicFadeStartMs = 0L
        private val musicFadeTick = object : Runnable {
            override fun run() {
                val t = ((SystemClock.elapsedRealtime() - musicFadeStartMs) / MUSIC_FADE_DURATION).coerceIn(0f, 1f)
                // Smooth sine ease-in-out
                val ease = (0.5f - 0.5f * Math.cos(Math.PI * t).toFloat())
                musicArtAlpha = if (musicArtTarget >= 1f) ease else 1f - ease
                drawFrame()
                if (t < 1f) handler.postDelayed(this, 16L)
                else {
                    musicArtAlpha = musicArtTarget
                    // If faded out completely, release music art bitmap
                    if (musicArtTarget == 0f) { rawMusicArt?.recycle(); rawMusicArt = null; cachedMusicArt?.recycle(); cachedMusicArt = null }
                }
            }
        }

        private var surfW = 0; private var surfH = 0
        private var visible = false
        private var tf: Typeface? = null

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
        private val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        private val tick = object : Runnable {
            override fun run() { drawFrame(); if (visible) handler.postDelayed(this, 1000L) }
        }

        private val FADE_STEP_MS     = 16L
        private val FADE_DURATION_MS = 280f
        private var fadeStartTimeMs  = 0L
        private val fadeInTick = object : Runnable {
            override fun run() {
                val t = ((SystemClock.elapsedRealtime() - fadeStartTimeMs) / FADE_DURATION_MS).coerceIn(0f, 1f)
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
                            clockHidden = true; clockFadeAlpha = 0f
                            handler.removeCallbacks(fadeInTick); drawFrame()
                        }
                    }
                    Intent.ACTION_SCREEN_ON  -> { isScreenOff = false }
                    Intent.ACTION_USER_PRESENT -> {
                        if (WallpaperPrefs.getAutoHide(this@DepthWallpaperService) && clockHidden) {
                            clockHidden = false
                            fadeStartTimeMs = SystemClock.elapsedRealtime()
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
                val nightBit = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                if (nightBit != lastNightMode) { lastNightMode = nightBit; load(); if (visible) drawFrame() }
            }
        }

        private val musicArtReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != WallpaperPrefs.ACTION_MUSIC_ART_CHANGED) return
                if (!WallpaperPrefs.getMusicArtEnabled(this@DepthWallpaperService)) return

                val artFile = WallpaperPrefs.getMusicArtFile(this@DepthWallpaperService)
                if (artFile.exists()) {
                    val newArt = decode(artFile, 4096)
                    if (musicArtAlpha >= 1f && cachedMusicArt != null) {
                        // Already showing music art — cross-fade to new thumbnail
                        cachedMusicArtPrev?.recycle()
                        cachedMusicArtPrev = cachedMusicArt
                        cachedMusicArt = null
                        rawMusicArt?.recycle(); rawMusicArt = newArt
                        cachedMusicArtW = 0; cachedMusicArtH = 0
                        if (surfW > 0 && surfH > 0) buildMusicArtBg(surfW, surfH)
                        crossFadeAlpha = 0f
                        crossFadeStartMs = SystemClock.elapsedRealtime()
                        handler.removeCallbacks(crossFadeTick)
                        handler.post(crossFadeTick)
                    } else {
                        // First time showing — fade in from wallpaper
                        rawMusicArt?.recycle(); rawMusicArt = newArt
                        cachedMusicArt?.recycle(); cachedMusicArt = null
                        cachedMusicArtW = 0; cachedMusicArtH = 0
                        if (surfW > 0 && surfH > 0) buildMusicArtBg(surfW, surfH)
                        crossFadeAlpha = 1f
                        startMusicFade(target = 1f)
                    }
                } else {
                    handler.removeCallbacks(crossFadeTick)
                    cachedMusicArtPrev?.recycle(); cachedMusicArtPrev = null
                    crossFadeAlpha = 1f
                    startMusicFade(target = 0f)
                }
            }
        }

        private fun startMusicFade(target: Float) {
            if (musicArtAlpha == target) return
            musicArtTarget = target
            musicFadeStartMs = SystemClock.elapsedRealtime()
            handler.removeCallbacks(musicFadeTick)
            handler.post(musicFadeTick)
        }

        override fun onCreate(h: SurfaceHolder) {
            super.onCreate(h)
            setTouchEventsEnabled(false)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(this@DepthWallpaperService, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(this@DepthWallpaperService, configReceiver, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            LocalBroadcastManager.getInstance(this@DepthWallpaperService)
                .registerReceiver(musicArtReceiver, IntentFilter(WallpaperPrefs.ACTION_MUSIC_ART_CHANGED))
            load()
        }

        private fun load() {
            lastNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val ftF = File(filesDir, WallpaperPrefs.FILE_FONT)
            tf    = if (ftF.exists()) try { Typeface.createFromFile(ftF) } catch (_: Exception) { null } else null
            tPaint.typeface = tf ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            dPaint.typeface = tf ?: Typeface.create("sans-serif-light", Typeface.NORMAL)

            if (WallpaperPrefs.getAutoHide(this@DepthWallpaperService)) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val locked = isScreenOff || km?.isKeyguardLocked == true
                clockHidden = locked; clockFadeAlpha = if (locked) 0f else 1f
                handler.removeCallbacks(fadeInTick)
            } else { clockHidden = false; clockFadeAlpha = 1f }

            val slot = WallpaperPrefs.activeSlot(this@DepthWallpaperService)
            lastSlot = slot
            loadForSlot(slot)

            // Restore music art state if enabled and file exists
            if (WallpaperPrefs.getMusicArtEnabled(this@DepthWallpaperService)) {
                val artFile = WallpaperPrefs.getMusicArtFile(this@DepthWallpaperService)
                if (artFile.exists()) {
                    rawMusicArt?.recycle(); rawMusicArt = decode(artFile, 4096)
                    musicArtAlpha = 1f; musicArtTarget = 1f
                } else {
                    musicArtAlpha = 0f; musicArtTarget = 0f
                }
            } else {
                musicArtAlpha = 0f; musicArtTarget = 0f
            }
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
            else   { handler.removeCallbacks(tick) }
        }

        override fun onSurfaceChanged(h: SurfaceHolder, f: Int, w: Int, hi: Int) {
            super.onSurfaceChanged(h, f, w, hi)
            surfW = w; surfH = hi
            WallpaperPrefs.setSurfaceSize(this@DepthWallpaperService, w, hi)
            val slot = WallpaperPrefs.activeSlot(this@DepthWallpaperService)
            if (slot != lastSlot) { lastSlot = slot; loadForSlot(slot) }
            val p = WallpaperPrefs.loadSlot(this@DepthWallpaperService, slot)
            buildBg(w, hi, p.bgRot)
            if (rawMusicArt != null) buildMusicArtBg(w, hi)
            drawFrame()
        }

        override fun onSurfaceDestroyed(h: SurfaceHolder) {
            visible = false; handler.removeCallbacks(tick); super.onSurfaceDestroyed(h)
        }

        override fun onDestroy() {
            handler.removeCallbacks(tick); handler.removeCallbacks(fadeInTick); handler.removeCallbacks(musicFadeTick); handler.removeCallbacks(crossFadeTick)
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
            try { unregisterReceiver(configReceiver) } catch (_: Exception) {}
            try { LocalBroadcastManager.getInstance(this@DepthWallpaperService).unregisterReceiver(musicArtReceiver) } catch (_: Exception) {}
            rawBg?.recycle(); rawFg?.recycle(); cachedBg?.recycle()
            rawMusicArt?.recycle(); cachedMusicArt?.recycle(); cachedMusicArtPrev?.recycle()
            super.onDestroy()
        }

        private fun buildBg(w: Int, h: Int, rot: Float) {
            if (w == cachedW && h == cachedH && rot == cachedBgRot && cachedBg != null) return
            cachedW = w; cachedH = h; cachedBgRot = rot
            val bg = rawBg ?: run { cachedBg?.recycle(); cachedBg = null; return }
            val rotRad = Math.toRadians(rot.toDouble())
            val extra  = (Math.abs(Math.cos(rotRad)) + Math.abs(Math.sin(rotRad))).toFloat()
            val scale  = maxOf(w.toFloat() / bg.width, h.toFloat() / bg.height) * extra
            val sw = (bg.width * scale).toInt().coerceAtLeast(1)
            val sh = (bg.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bg, sw, sh, true)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out); c.drawColor(Color.BLACK)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            c.save(); c.translate(w / 2f, h / 2f); c.rotate(rot)
            c.drawBitmap(scaled, -sw / 2f, -sh / 2f, paint); c.restore()
            cachedBg?.recycle(); cachedBg = out
        }

        private fun buildMusicArtBg(w: Int, h: Int) {
            if (w == cachedMusicArtW && h == cachedMusicArtH && cachedMusicArt != null) return
            val art = rawMusicArt ?: return
            // Cover-fit: fill the surface, centre-crop
            val scale  = maxOf(w.toFloat() / art.width, h.toFloat() / art.height)
            val sw = (art.width  * scale).toInt().coerceAtLeast(1)
            val sh = (art.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(art, sw, sh, true)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out); c.drawColor(Color.BLACK)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            c.drawBitmap(scaled, (w - sw) / 2f, (h - sh) / 2f, paint)
            cachedMusicArt?.recycle(); cachedMusicArt = out
            cachedMusicArtW = w; cachedMusicArtH = h
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

            // ── Normal background ─────────────────────────────────────────────
            if (cachedBg == null || p.bgRot != cachedBgRot) buildBg(W.toInt(), H.toInt(), p.bgRot)
            cachedBg?.let {
                val bgCm = ColorMatrix(); bgCm.setSaturation(p.bgSat)
                val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                if (p.bgSat != 1f) bgPaint.colorFilter = ColorMatrixColorFilter(bgCm)
                // Fade out normal bg as music art fades in
                bgPaint.alpha = (255 * (1f - musicArtAlpha)).toInt().coerceIn(0, 255)
                if (bgPaint.alpha > 0) canvas.drawBitmap(it, 0f, 0f, bgPaint)
            }

            // ── Music art overlay ─────────────────────────────────────────────
            if (musicArtAlpha > 0f) {
                if (cachedMusicArt == null || cachedMusicArtW != W.toInt() || cachedMusicArtH != H.toInt()) {
                    buildMusicArtBg(W.toInt(), H.toInt())
                }
                val baseAlpha = (255 * musicArtAlpha).toInt().coerceIn(0, 255)
                // Draw previous thumbnail fading out during cross-fade
                if (cachedMusicArtPrev != null && crossFadeAlpha < 1f) {
                    overlayPaint.alpha = (baseAlpha * (1f - crossFadeAlpha)).toInt().coerceIn(0, 255)
                    canvas.drawBitmap(cachedMusicArtPrev!!, 0f, 0f, overlayPaint)
                }
                // Draw new thumbnail fading in
                cachedMusicArt?.let {
                    overlayPaint.alpha = (baseAlpha * crossFadeAlpha).toInt().coerceIn(0, 255)
                    canvas.drawBitmap(it, 0f, 0f, overlayPaint)
                }
                // Dim layer on top of music art
                val artDim = WallpaperPrefs.getMusicArtDim(ctx)
                if (artDim > 0f) {
                    dimPaint.alpha = (artDim * musicArtAlpha * 255).toInt().coerceIn(0, 255)
                    canvas.drawRect(0f, 0f, W, H, dimPaint)
                }
            }

            // ── Dim ───────────────────────────────────────────────────────────
            if (p.bgDim > 0f && musicArtAlpha < 1f) {
                dimPaint.alpha = (p.bgDim * 255 * (1f - musicArtAlpha)).toInt().coerceIn(0, 255)
                canvas.drawRect(0f, 0f, W, H, dimPaint)
            }

            // ── Auto-hide clock logic ─────────────────────────────────────────
            val autoHide = WallpaperPrefs.getAutoHide(ctx)
            if (autoHide) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val shouldHide = isScreenOff || km?.isKeyguardLocked == true
                when {
                    shouldHide && !clockHidden -> {
                        clockHidden = true; clockFadeAlpha = 0f
                        handler.removeCallbacks(fadeInTick)
                    }
                    !shouldHide && clockHidden && !handler.hasCallbacks(fadeInTick) -> {
                        clockHidden = false
                        fadeStartTimeMs = SystemClock.elapsedRealtime()
                        handler.post(fadeInTick)
                    }
                }
            } else { clockFadeAlpha = 1f; clockHidden = false }

            // Hide clock/date entirely when music art is showing
            val clockAlpha = if (autoHide) clockFadeAlpha else 1f
            val effectiveClockAlpha = clockAlpha * (1f - musicArtAlpha)

            if (effectiveClockAlpha > 0f && !isPreview()) {
                if (p.showTime) {
                    tPaint.color    = dimmedColor(color, p.clkDim, effectiveClockAlpha)
                    tPaint.textSize = p.clockSz * H
                    tPaint.typeface = tf ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    canvas.save(); canvas.translate(p.clockX * W, p.clockY * H); canvas.rotate(p.clockRot)
                    canvas.drawText(tStr(p, Date()), 0f, -(tPaint.descent() + tPaint.ascent()) / 2f, tPaint)
                    canvas.restore()
                }
                if (p.showDate) {
                    val dateBase = Color.argb(
                        (Color.alpha(color) * 0.85f).toInt().coerceIn(0, 255),
                        Color.red(color), Color.green(color), Color.blue(color))
                    dPaint.color    = dimmedColor(dateBase, p.clkDim, effectiveClockAlpha)
                    dPaint.textSize = p.dateSz * H
                    dPaint.typeface = tf ?: Typeface.create("sans-serif-light", Typeface.NORMAL)
                    canvas.save(); canvas.translate(p.dateX * W, p.dateY * H); canvas.rotate(p.dateRot)
                    canvas.drawText(fDate.format(Date()), 0f, -(dPaint.descent() + dPaint.ascent()) / 2f, dPaint)
                    canvas.restore()
                }
            }

            // ── Subject — fade out as music art fades in ──────────────────────
            rawFg?.let { fg ->
                val subjAlpha = (1f - musicArtAlpha).coerceIn(0f, 1f)
                if (subjAlpha == 0f) return@let
                val base  = minOf(W / fg.width, H / fg.height); val total = base * p.subjSc
                val dim   = (1f - p.subjDim).coerceIn(0f, 1f)
                val dimCm = ColorMatrix(); dimCm.set(floatArrayOf(
                    dim, 0f, 0f, 0f, 0f,  0f, dim, 0f, 0f, 0f,  0f, 0f, dim, 0f, 0f,  0f, 0f, 0f, 1f, 0f))
                val satCm = ColorMatrix(); satCm.setSaturation(p.subjSat)
                val cm    = ColorMatrix(); cm.setConcat(dimCm, satCm)
                fgPaint.colorFilter = ColorMatrixColorFilter(cm)
                fgPaint.alpha = (subjAlpha * 255).toInt().coerceIn(0, 255)
                canvas.save(); canvas.translate(p.subjX * W, p.subjY * H); canvas.rotate(p.subjRot)
                canvas.scale(total, total)
                canvas.drawBitmap(fg, -fg.width / 2f, -fg.height / 2f, fgPaint)
                canvas.restore()
            }
        }

        private fun tStr(p: WallpaperPrefs.WallPrefs, now: Date) = when {
            p.use24 && p.secs -> f24s.format(now); p.use24 -> f24.format(now)
            p.secs            -> f12s.format(now); else     -> f12.format(now)
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
