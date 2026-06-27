package com.everwall

import android.content.Context

object WallpaperPrefs {
    const val FILE_ORIGINAL   = "ew_original.png"
    const val FILE_FOREGROUND = "ew_foreground.png"
    const val FILE_FONT       = "ew_font.ttf"
    const val FILE_FONT_DATE  = "ew_font_date.ttf"
    const val NO_COLOR        = Int.MIN_VALUE

    private const val PREFS        = "everwall_prefs"
    private const val KEY_CLK_X    = "clock_x";  private const val KEY_CLK_Y    = "clock_y"
    private const val KEY_CLK_SZ   = "clock_sz"; private const val KEY_CLK_ROT  = "clock_rot"
    private const val KEY_DATE_X   = "date_x";   private const val KEY_DATE_Y   = "date_y"
    private const val KEY_DATE_SZ  = "date_sz";  private const val KEY_DATE_ROT  = "date_rot"
    private const val KEY_SUBJ_X   = "subj_x";  private const val KEY_SUBJ_Y   = "subj_y"
    private const val KEY_SUBJ_SC  = "subj_sc";  private const val KEY_SUBJ_ROT  = "subj_rot"
    private const val KEY_BG_ROT   = "bg_rot"
    private const val KEY_COLOR    = "clk_color"
    private const val KEY_DATE_COLOR = "date_color"
    private const val KEY_USE24    = "use_24hr";  private const val KEY_SECS     = "show_secs"
    private const val KEY_FONT     = "has_font"
    private const val KEY_DATE_FONT = "has_date_font"
    private const val KEY_SURF_W   = "surface_w"; private const val KEY_SURF_H  = "surface_h"
    private const val KEY_BG_DIM   = "bg_dim"
    private const val KEY_CLK_DIM  = "clk_dim"
    private const val KEY_SUBJ_DIM = "subj_dim"
    private const val KEY_BG_SAT   = "bg_sat"
    private const val KEY_SUBJ_SAT = "subj_sat"
    private const val KEY_AUTO_HIDE = "auto_hide_lock"
    private const val KEY_SHOW_TIME = "show_time"
    private const val KEY_SHOW_DATE = "show_date"
    private const val KEY_VERT_CLK  = "vert_clock"
    private const val KEY_ZERO_PAD  = "zero_pad_hour"

    // Default element positions / sizes
    const val DEF_CLK_X   = 0.5f;  const val DEF_CLK_Y   = 0.28f
    const val DEF_CLK_SZ  = 0.12f; const val DEF_CLK_ROT = 0f
    const val DEF_DATE_X  = 0.5f;  const val DEF_DATE_Y  = 0.42f
    const val DEF_DATE_SZ = 0.034f;const val DEF_DATE_ROT = 0f
    const val DEF_SUBJ_X  = 0.5f;  const val DEF_SUBJ_Y  = 0.5f
    const val DEF_SUBJ_SC = 1.0f;  const val DEF_SUBJ_ROT = 0f
    const val DEF_BG_ROT  = 0f

    /** Wipe all layout data so the editor starts fresh after new images are picked. */
    fun clearPositions(ctx: Context) = prefs(ctx).edit()
        .remove(KEY_CLK_X).remove(KEY_CLK_Y).remove(KEY_CLK_SZ).remove(KEY_CLK_ROT)
        .remove(KEY_DATE_X).remove(KEY_DATE_Y).remove(KEY_DATE_SZ).remove(KEY_DATE_ROT)
        .remove(KEY_SUBJ_X).remove(KEY_SUBJ_Y).remove(KEY_SUBJ_SC).remove(KEY_SUBJ_ROT)
        .remove(KEY_BG_ROT).remove(KEY_COLOR)
        .remove(KEY_BG_DIM).remove(KEY_CLK_DIM).remove(KEY_SUBJ_DIM)
        .apply()

    fun setSurfaceSize(ctx: Context, w: Int, h: Int) =
        prefs(ctx).edit().putInt(KEY_SURF_W, w).putInt(KEY_SURF_H, h).apply()
    fun setHasFont(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_FONT, v).apply()
    fun setHasDateFont(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_DATE_FONT, v).apply()
    fun hasDateFont(ctx: Context)  = prefs(ctx).getBoolean(KEY_DATE_FONT, false)

    fun getSurfaceW(ctx: Context)  = prefs(ctx).getInt(KEY_SURF_W, 0)
    fun getSurfaceH(ctx: Context)  = prefs(ctx).getInt(KEY_SURF_H, 0)
    fun getClkX(ctx: Context)      = prefs(ctx).getFloat(KEY_CLK_X,    DEF_CLK_X)
    fun getClkY(ctx: Context)      = prefs(ctx).getFloat(KEY_CLK_Y,    DEF_CLK_Y)
    fun getClkSz(ctx: Context)     = prefs(ctx).getFloat(KEY_CLK_SZ,   DEF_CLK_SZ)
    fun getClkRot(ctx: Context)    = prefs(ctx).getFloat(KEY_CLK_ROT,  DEF_CLK_ROT)
    fun getDateX(ctx: Context)     = prefs(ctx).getFloat(KEY_DATE_X,   DEF_DATE_X)
    fun getDateY(ctx: Context)     = prefs(ctx).getFloat(KEY_DATE_Y,   DEF_DATE_Y)
    fun getDateSz(ctx: Context)    = prefs(ctx).getFloat(KEY_DATE_SZ,  DEF_DATE_SZ)
    fun getDateRot(ctx: Context)   = prefs(ctx).getFloat(KEY_DATE_ROT, DEF_DATE_ROT)
    fun getSubjX(ctx: Context)     = prefs(ctx).getFloat(KEY_SUBJ_X,   DEF_SUBJ_X)
    fun getSubjY(ctx: Context)     = prefs(ctx).getFloat(KEY_SUBJ_Y,   DEF_SUBJ_Y)
    fun getSubjSc(ctx: Context)    = prefs(ctx).getFloat(KEY_SUBJ_SC,  DEF_SUBJ_SC)
    fun getSubjRot(ctx: Context)   = prefs(ctx).getFloat(KEY_SUBJ_ROT, DEF_SUBJ_ROT)
    fun getBgRot(ctx: Context)     = prefs(ctx).getFloat(KEY_BG_ROT,   DEF_BG_ROT)
    fun getColor(ctx: Context)     = prefs(ctx).getInt(KEY_COLOR,       NO_COLOR)
    fun getUse24(ctx: Context)     = prefs(ctx).getBoolean(KEY_USE24,  false)
    fun getSecs(ctx: Context)      = prefs(ctx).getBoolean(KEY_SECS,   false)
    fun hasFont(ctx: Context)      = prefs(ctx).getBoolean(KEY_FONT,   false)
    fun getBgDim(ctx: Context)     = prefs(ctx).getFloat(KEY_BG_DIM,   0f)
    fun getClkDim(ctx: Context)    = prefs(ctx).getFloat(KEY_CLK_DIM,  0f)
    fun getSubjDim(ctx: Context)   = prefs(ctx).getFloat(KEY_SUBJ_DIM, 0f)
    fun getAutoHide(ctx: Context)  = prefs(ctx).getBoolean(KEY_AUTO_HIDE, false)
    fun setAutoHide(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO_HIDE, v).apply()

    fun getBgBehindPreview(ctx: Context)         = metaPrefs(ctx).getBoolean("bg_behind_preview", false)
    fun setBgBehindPreview(ctx: Context, v: Boolean) = metaPrefs(ctx).edit().putBoolean("bg_behind_preview", v).apply()

    const val THEME_AUTO  = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK  = 2
    fun getAppTheme(ctx: Context)         = metaPrefs(ctx).getInt("app_theme", THEME_AUTO)
    fun setAppTheme(ctx: Context, v: Int) = metaPrefs(ctx).edit().putInt("app_theme", v).apply()

    // ── Mode / Day-Night slot system ──────────────────────────────────────────
    const val MODE_NONE         = 0
    const val MODE_DAY_NIGHT    = 1
    const val MODE_SYSTEM_THEME = 2
    const val EDIT_NONE      = 0
    const val EDIT_DAY       = 1
    const val EDIT_NIGHT     = 2

    private const val META_PREFS  = "ew_meta"
    private const val KEY_WMODE   = "wmode"
    private const val KEY_DAY_M   = "day_mins"
    private const val KEY_NIGHT_M = "night_mins"

    private fun metaPrefs(ctx: Context) = ctx.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)

    fun slotPrefs(ctx: Context, slot: Int) = ctx.getSharedPreferences(
        when(slot) { EDIT_DAY -> "ew_day"; EDIT_NIGHT -> "ew_night"; else -> PREFS },
        Context.MODE_PRIVATE)

    fun getWallpaperMode(ctx: Context)             = metaPrefs(ctx).getInt(KEY_WMODE, MODE_NONE)
    fun setWallpaperMode(ctx: Context, v: Int)     = metaPrefs(ctx).edit().putInt(KEY_WMODE, v).apply()
    fun getDayMins(ctx: Context)                   = metaPrefs(ctx).getInt(KEY_DAY_M, -1)
    fun setDayMins(ctx: Context, v: Int)           = metaPrefs(ctx).edit().putInt(KEY_DAY_M, v).apply()
    fun getNightMins(ctx: Context)                 = metaPrefs(ctx).getInt(KEY_NIGHT_M, -1)
    fun setNightMins(ctx: Context, v: Int)         = metaPrefs(ctx).edit().putInt(KEY_NIGHT_M, v).apply()

    fun getBgFileForSlot(ctx: Context, slot: Int) = java.io.File(ctx.filesDir,
        when(slot) { EDIT_DAY -> "ew_bg_day.png"; EDIT_NIGHT -> "ew_bg_night.png"; else -> FILE_ORIGINAL })
    fun getFgFileForSlot(ctx: Context, slot: Int) = java.io.File(ctx.filesDir,
        when(slot) { EDIT_DAY -> "ew_fg_day.png"; EDIT_NIGHT -> "ew_fg_night.png"; else -> FILE_FOREGROUND })

    fun activeSlot(ctx: Context): Int {
        return when (getWallpaperMode(ctx)) {
            MODE_DAY_NIGHT -> {
                val d = getDayMins(ctx); val n = getNightMins(ctx)
                if (d < 0 || n < 0) return EDIT_NONE
                val cal = java.util.Calendar.getInstance()
                val now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
                val isDay = if (d <= n) now in d until n else now >= d || now < n
                if (isDay) EDIT_DAY else EDIT_NIGHT
            }
            MODE_SYSTEM_THEME -> {
                val nightBit = ctx.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                if (nightBit == android.content.res.Configuration.UI_MODE_NIGHT_YES) EDIT_NIGHT else EDIT_DAY
            }
            else -> EDIT_NONE
        }
    }

    data class WallPrefs(
        val clockX: Float, val clockY: Float, val clockSz: Float, val clockRot: Float,
        val dateX: Float,  val dateY: Float,  val dateSz: Float,  val dateRot: Float,
        val subjX: Float,  val subjY: Float,  val subjSc: Float,  val subjRot: Float,
        val bgRot: Float,  val color: Int,    val use24: Boolean, val secs: Boolean,
        val bgDim: Float,  val clkDim: Float, val subjDim: Float,
        val bgSat: Float = 1f, val subjSat: Float = 1f,
        val showTime: Boolean = true, val showDate: Boolean = true,
        val verticalClock: Boolean = false, val zeroPad: Boolean = true,
        val dateColor: Int = NO_COLOR
    )

    fun loadSlot(ctx: Context, slot: Int): WallPrefs {
        val sp = slotPrefs(ctx, slot)
        return WallPrefs(
            sp.getFloat(KEY_CLK_X,    DEF_CLK_X),   sp.getFloat(KEY_CLK_Y,   DEF_CLK_Y),
            sp.getFloat(KEY_CLK_SZ,   DEF_CLK_SZ),  sp.getFloat(KEY_CLK_ROT, DEF_CLK_ROT),
            sp.getFloat(KEY_DATE_X,   DEF_DATE_X),   sp.getFloat(KEY_DATE_Y,  DEF_DATE_Y),
            sp.getFloat(KEY_DATE_SZ,  DEF_DATE_SZ),  sp.getFloat(KEY_DATE_ROT,DEF_DATE_ROT),
            sp.getFloat(KEY_SUBJ_X,   DEF_SUBJ_X),   sp.getFloat(KEY_SUBJ_Y,  DEF_SUBJ_Y),
            sp.getFloat(KEY_SUBJ_SC,  DEF_SUBJ_SC),  sp.getFloat(KEY_SUBJ_ROT,DEF_SUBJ_ROT),
            sp.getFloat(KEY_BG_ROT,   DEF_BG_ROT),
            sp.getInt(KEY_COLOR, NO_COLOR),
            sp.getBoolean(KEY_USE24, false), sp.getBoolean(KEY_SECS, false),
            sp.getFloat(KEY_BG_DIM, 0f), sp.getFloat(KEY_CLK_DIM, 0f), sp.getFloat(KEY_SUBJ_DIM, 0f),
            sp.getFloat(KEY_BG_SAT, 1f), sp.getFloat(KEY_SUBJ_SAT, 1f),
            sp.getBoolean(KEY_SHOW_TIME, true), sp.getBoolean(KEY_SHOW_DATE, true),
            sp.getBoolean(KEY_VERT_CLK, false), sp.getBoolean(KEY_ZERO_PAD, true),
            sp.getInt(KEY_DATE_COLOR, NO_COLOR)
        )
    }

    fun saveToSlot(ctx: Context, slot: Int, p: WallPrefs) =
        slotPrefs(ctx, slot).edit().also { e ->
            e.putFloat(KEY_CLK_X, p.clockX);  e.putFloat(KEY_CLK_Y,   p.clockY)
            e.putFloat(KEY_CLK_SZ, p.clockSz); e.putFloat(KEY_CLK_ROT, p.clockRot)
            e.putFloat(KEY_DATE_X, p.dateX);   e.putFloat(KEY_DATE_Y,  p.dateY)
            e.putFloat(KEY_DATE_SZ, p.dateSz); e.putFloat(KEY_DATE_ROT,p.dateRot)
            e.putFloat(KEY_SUBJ_X, p.subjX);   e.putFloat(KEY_SUBJ_Y,  p.subjY)
            e.putFloat(KEY_SUBJ_SC, p.subjSc); e.putFloat(KEY_SUBJ_ROT,p.subjRot)
            e.putFloat(KEY_BG_ROT, p.bgRot)
            e.putInt(KEY_COLOR, p.color)
            e.putBoolean(KEY_USE24, p.use24);  e.putBoolean(KEY_SECS, p.secs)
            e.putFloat(KEY_BG_DIM, p.bgDim);   e.putFloat(KEY_CLK_DIM, p.clkDim)
            e.putFloat(KEY_SUBJ_DIM, p.subjDim)
            e.putFloat(KEY_BG_SAT, p.bgSat);   e.putFloat(KEY_SUBJ_SAT, p.subjSat)
            e.putBoolean(KEY_SHOW_TIME, p.showTime); e.putBoolean(KEY_SHOW_DATE, p.showDate)
            e.putBoolean(KEY_VERT_CLK, p.verticalClock)
            e.putBoolean(KEY_ZERO_PAD, p.zeroPad)
            e.putInt(KEY_DATE_COLOR, p.dateColor)
        }.apply()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Music Art ─────────────────────────────────────────────────────────────
    const val FILE_MUSIC_ART = "ew_music_art.png"
    const val ACTION_MUSIC_ART_CHANGED = "com.everwall.MUSIC_ART_CHANGED"

    fun getAutoCheckUpdates(ctx: Context) = metaPrefs(ctx).getBoolean("auto_check_updates", true)
    fun setAutoCheckUpdates(ctx: Context, v: Boolean) = metaPrefs(ctx).edit().putBoolean("auto_check_updates", v).apply()

    fun getMusicArtEnabled(ctx: Context) = metaPrefs(ctx).getBoolean("music_art_enabled", false)
    fun setMusicArtEnabled(ctx: Context, v: Boolean) = metaPrefs(ctx).edit().putBoolean("music_art_enabled", v).apply()
    fun getMusicArtDim(ctx: Context) = metaPrefs(ctx).getFloat("music_art_dim", 0f)
    fun setMusicArtDim(ctx: Context, v: Float) = metaPrefs(ctx).edit().putFloat("music_art_dim", v).apply()
    fun getMusicArtFile(ctx: Context) = java.io.File(ctx.filesDir, FILE_MUSIC_ART)
}
