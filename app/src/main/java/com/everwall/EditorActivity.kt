package com.everwall

import android.Manifest
import android.animation.ValueAnimator
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.app.TimePickerDialog
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.everwall.databinding.ActivityEditorBinding
import com.everwall.databinding.DialogAboutBinding
import com.everwall.databinding.DialogMusicArtBinding
import com.everwall.databinding.DialogWelcomeBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import android.view.LayoutInflater

class EditorActivity : AppCompatActivity() {

    private lateinit var b: ActivityEditorBinding
    private lateinit var bannerDrawable: BannerConcaveDrawable
    private enum class Panel { BACKGROUND, TIME, DATE, SUBJECT }
    private var panel = Panel.TIME
    private var clockColor = Color.WHITE
    private var dateColor = Color.WHITE
    private var colorAtPickOpen = Color.WHITE
    private var dateColorAtPickOpen = Color.WHITE
    private var loadedBgBmp: Bitmap? = null
    private lateinit var bsb: LockableBottomSheetBehavior<android.widget.LinearLayout>
    private var sheetLocked = true
    /** True only when controls_root is an actual CoordinatorLayout bottom sheet (portrait).
     *  In the landscape dual-pane layout, controls_root is a plain, always-visible side
     *  panel with no behavior attached, so every drag/peek/lock concept is skipped. */
    private val hasBottomSheet: Boolean by lazy {
        (b.controlsRoot.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)?.behavior != null
    }

    private var wallpaperMode = WallpaperPrefs.MODE_NONE
    private var editSlot      = WallpaperPrefs.EDIT_NONE
    private var fontPickIsDate = false

    private val pickBg   = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> replaceImg(u, true) } }
    private val pickFg   = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> replaceImg(u, false) } }
    private val pickFont = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> onFont(u, fontPickIsDate) } }
    private val perm     = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (!it) toast("Storage permission required.") }

    companion object {
        private const val PEEK_HEIGHT_DP = 368f
        private const val BOTTOM_GAP_DP  = 32f
    }

    /** A full, fresh restart of the editor — not Activity.recreate().
     *  recreate() saves the current view hierarchy state (Switch/Slider checked
     *  values, etc.) and automatically restores it onto the newly created views,
     *  which would silently carry the OLD preset's time/date switches and slider
     *  positions into the NEW preset. Launching a brand-new Activity instance with
     *  no saved-instance Bundle guarantees every control is populated purely from
     *  WallpaperPrefs.loadSlot() for the now-active preset, so presets never leak
     *  state into one another. */
    private fun restartEditorFresh() {
        val i = Intent(this, EditorActivity::class.java)
        startActivity(i)
        finish()
        overridePendingTransition(0, 0)
    }

    /** Never let the framework auto-save/restore the editor's view hierarchy
     *  (Switch checked state, Slider positions, etc.). All of that is always
     *  re-derived from WallpaperPrefs.loadSlot() for whichever preset is
     *  currently active, so accepting a stale Bundle here would just reintroduce
     *  the "settings leak between presets" bug. */
    override fun onSaveInstanceState(outState: Bundle) {
        // Intentionally do not call super.onSaveInstanceState(outState) and
        // leave outState empty — nothing is saved, so nothing stale can be restored.
    }

    override fun onCreate(s: Bundle?) {
        applyThemeMode()
        DynamicColors.applyToActivityIfAvailable(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(s)
        b = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(b.root)

        val concaveR = 32f * resources.displayMetrics.density
        bannerDrawable = BannerConcaveDrawable(
            attr(com.google.android.material.R.attr.colorPrimaryContainer), concaveR)
        b.toolbarContainer.background = bannerDrawable
        b.toolbarContainer.clipToOutline = true
        b.toolbarContainer.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                val r = concaveR
                val w = view.width.toFloat(); val h = view.height.toFloat()
                val p = android.graphics.Path().apply {
                    moveTo(0f, 0f); lineTo(w, 0f); lineTo(w, h - r)
                    arcTo(android.graphics.RectF(w - r * 2f, h - r * 2f, w, h), 0f, 90f)
                    lineTo(r, h)
                    arcTo(android.graphics.RectF(0f, h - r * 2f, r * 2f, h), 90f, 90f)
                    lineTo(0f, 0f); close()
                }
                @Suppress("DEPRECATION") outline.setConvexPath(p)
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars    = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val dp      = resources.displayMetrics.density
            val isLand  = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val base    = ((if (isLand) 56 else 80) * dp).toInt()
            val bannerH = base + bars.top
            // Pad only the title/icon row below the status bar — the banner
            // FrameLayout itself stays unpadded so its decorative shapes are
            // free to extend all the way to its true top edge (y=0) and
            // bleed behind the status bar instead of stopping short of it.
            b.toolbarContentRow.setPadding(
                b.toolbarContentRow.paddingLeft, bars.top,
                b.toolbarContentRow.paddingRight, 0)
            val lp = b.toolbarContainer.layoutParams; lp.height = bannerH; b.toolbarContainer.layoutParams = lp
            // Only the portrait layout nests preview_container directly in the
            // CoordinatorLayout — in the landscape dual-pane layout it's nested
            // inside a plain LinearLayout, so this cast must stay safe.
            (b.previewContainer.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)?.let {
                it.topMargin = 0; b.previewContainer.layoutParams = it
            }
            b.previewContainer.setPadding(bars.left, 0, 0, 0)
            b.sidebarScroll.setPadding(b.sidebarScroll.paddingLeft, bannerH + (8 * dp).toInt(),
                b.sidebarScroll.paddingRight, (24 * dp).toInt())
            b.controlsRoot.setPadding(b.controlsRoot.paddingLeft, b.controlsRoot.paddingTop,
                bars.right, bars.bottom)
            val isNight = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !isNight
            WindowInsetsCompat.CONSUMED
        }

        setupBottomSheet()
        applyExactSurfaceAspectRatio()
        loadData()
        setupPills()
        setupPanels()
        setupModeButton()

        b.btnSetWallpaper.setOnClickListener { animateBtnPress(b.btnSetWallpaper) { saveAndLaunch() } }
        b.btnReset.setOnClickListener        { showResetDialog() }
        b.btnLock.setOnClickListener {
            sheetLocked = !sheetLocked
            applyLockState()
        }

        val dp = resources.displayMetrics.density
        b.controlsRoot.post {
            if (hasBottomSheet) {
                b.controlsRoot.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) {
                        val r = 28f * dp; o.setRoundRect(0, 0, v.width, (v.height + r).toInt(), r)
                    }
                }
                b.controlsRoot.clipToOutline = true
            }
            // Apply default locked state once views are laid out
            applyLockState()
        }
        animateEntrance()
        b.btnSettings.setOnClickListener { showAboutDialog() }
        b.btnMusicArt.setOnClickListener { showMusicArtDialog() }
        showWelcomeIfFirstLaunch()
        autoCheckForUpdates()
    }

    /** Guards against the onPause() autosave firing AFTER WallpaperPrefs.switchToPreset()
     *  has already flipped the active preset. Without this, starting the replacement
     *  Activity (in restartEditorFresh) triggers this Activity's onPause(), which would
     *  call save() with the OLD in-memory UI state but — since the active-preset pointer
     *  has already moved — write it straight into the NEW preset's storage, stomping every
     *  field (background, subject, time, date) of the preset being switched to. */
    private var skipPauseSave = false

    override fun onPause() { if (!skipPauseSave) save(); super.onPause() }

    // ── Mode / Day-Night ──────────────────────────────────────────────────────
    private fun setupModeButton() {
        b.btnMode.text = modeLabel()
        b.btnPresets.setOnClickListener { showPresetsDialog() }
        b.btnMode.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Wallpaper Mode")
                .setSingleChoiceItems(arrayOf("None", "Day and Night", "System Theme"),
                    when (wallpaperMode) {
                        WallpaperPrefs.MODE_DAY_NIGHT    -> 1
                        WallpaperPrefs.MODE_SYSTEM_THEME -> 2
                        else -> 0
                    }) { dialog, which ->
                    val newMode = when (which) { 1 -> WallpaperPrefs.MODE_DAY_NIGHT; 2 -> WallpaperPrefs.MODE_SYSTEM_THEME; else -> WallpaperPrefs.MODE_NONE }
                    if (newMode != wallpaperMode) { saveCurrentSlot(); applyWallpaperMode(newMode) }
                    dialog.dismiss()
                }.show()
        }
        b.btnDay.setOnClickListener {
            if (editSlot != WallpaperPrefs.EDIT_DAY) { saveCurrentSlot(); editSlot = WallpaperPrefs.EDIT_DAY; loadSlotIntoEditor(WallpaperPrefs.EDIT_DAY); pillDayNight(WallpaperPrefs.EDIT_DAY) }
        }
        b.btnNight.setOnClickListener {
            if (editSlot != WallpaperPrefs.EDIT_NIGHT) { saveCurrentSlot(); editSlot = WallpaperPrefs.EDIT_NIGHT; loadSlotIntoEditor(WallpaperPrefs.EDIT_NIGHT); pillDayNight(WallpaperPrefs.EDIT_NIGHT) }
        }
        b.btnSetDayTime.setOnClickListener   { showTimePicker(isDay = true) }
        b.btnSetNightTime.setOnClickListener { showTimePicker(isDay = false) }
        if (wallpaperMode == WallpaperPrefs.MODE_DAY_NIGHT || wallpaperMode == WallpaperPrefs.MODE_SYSTEM_THEME) {
            showDayNightUI(true)
            b.setTimeContainer.visibility = if (wallpaperMode == WallpaperPrefs.MODE_SYSTEM_THEME) View.GONE else View.VISIBLE
            pillDayNight(editSlot)
        }
        updateDayNightTimeLabels()
        updateSetLiveButton()
        updateMusicArtButtonTint(WallpaperPrefs.getMusicArtEnabled(this))
    }

    private fun applyWallpaperMode(mode: Int) {
        wallpaperMode = mode; WallpaperPrefs.setWallpaperMode(this, mode)
        when (mode) {
            WallpaperPrefs.MODE_DAY_NIGHT, WallpaperPrefs.MODE_SYSTEM_THEME -> {
                editSlot = WallpaperPrefs.EDIT_DAY; showDayNightUI(true)
                b.setTimeContainer.visibility = if (mode == WallpaperPrefs.MODE_SYSTEM_THEME) View.GONE else View.VISIBLE
                loadSlotIntoEditor(WallpaperPrefs.EDIT_DAY); pillDayNight(WallpaperPrefs.EDIT_DAY)
            }
            else -> { editSlot = WallpaperPrefs.EDIT_NONE; showDayNightUI(false); loadSlotIntoEditor(WallpaperPrefs.EDIT_NONE) }
        }
        b.btnMode.text = modeLabel(); updateSetLiveButton()
    }

    private fun showDayNightUI(show: Boolean) { b.dayNightOverlay.visibility = if (show) View.VISIBLE else View.GONE }

    private fun pillDayNight(slot: Int) {
        val dayActive  = slot == WallpaperPrefs.EDIT_DAY
        val bgEnabled  = WallpaperPrefs.getBgBehindPreview(this)
        val activeTint = if (bgEnabled) attr(com.google.android.material.R.attr.colorPrimary) else attr(com.google.android.material.R.attr.colorPrimaryContainer)
        val activeText = if (bgEnabled) attr(com.google.android.material.R.attr.colorOnPrimary) else attr(com.google.android.material.R.attr.colorOnPrimaryContainer)
        val inactTint  = attr(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val inactText  = attr(com.google.android.material.R.attr.colorOnSurface)
        val (dayTint, dayText)   = if (dayActive)  Pair(activeTint, activeText) else Pair(inactTint, inactText)
        val (nightTint, nightText) = if (!dayActive) Pair(activeTint, activeText) else Pair(inactTint, inactText)
        b.btnDay.backgroundTintList   = android.content.res.ColorStateList.valueOf(dayTint);   b.btnDay.setTextColor(dayText);   b.btnDay.iconTint   = android.content.res.ColorStateList.valueOf(dayText)
        b.btnNight.backgroundTintList = android.content.res.ColorStateList.valueOf(nightTint); b.btnNight.setTextColor(nightText); b.btnNight.iconTint = android.content.res.ColorStateList.valueOf(nightText)
    }

    private fun modeLabel() = when (wallpaperMode) { WallpaperPrefs.MODE_DAY_NIGHT -> "Mode: Day & Night"; WallpaperPrefs.MODE_SYSTEM_THEME -> "Mode: System Theme"; else -> "Mode: None" }

    private fun showTimePicker(isDay: Boolean) {
        val savedMins = if (isDay) WallpaperPrefs.getDayMins(this) else WallpaperPrefs.getNightMins(this)
        val h = if (savedMins >= 0) savedMins / 60 else if (isDay) 6 else 20
        val m = if (savedMins >= 0) savedMins % 60 else 0
        TimePickerDialog(this, { _, hour, minute ->
            val mins = hour * 60 + minute
            if (isDay) WallpaperPrefs.setDayMins(this, mins) else WallpaperPrefs.setNightMins(this, mins)
            updateDayNightTimeLabels(); updateSetLiveButton()
        }, h, m, DateFormat.is24HourFormat(this)).show()
    }

    private fun updateDayNightTimeLabels() {
        val d = WallpaperPrefs.getDayMins(this); val n = WallpaperPrefs.getNightMins(this)
        b.tvDayTime.text   = if (d >= 0) fmtMins(d) else "--:--"
        b.tvNightTime.text = if (n >= 0) fmtMins(n) else "--:--"
    }

    private fun fmtMins(mins: Int): String {
        val h = mins / 60; val m = mins % 60
        return if (DateFormat.is24HourFormat(this)) "%02d:%02d".format(h, m)
        else { val ampm = if (h < 12) "AM" else "PM"; val h12 = if (h % 12 == 0) 12 else h % 12; "%d:%02d %s".format(h12, m, ampm) }
    }

    private fun updateSetLiveButton() {
        if (wallpaperMode == WallpaperPrefs.MODE_DAY_NIGHT) {
            val daySet = WallpaperPrefs.getDayMins(this) >= 0; val nightSet = WallpaperPrefs.getNightMins(this) >= 0; val both = daySet && nightSet
            b.btnSetWallpaper.isEnabled = both
            b.btnSetWallpaper.text = when { !daySet && !nightSet -> "Set Live"; !daySet -> "Set Day Time"; !nightSet -> "Set Night Time"; else -> "Set Live" }
        } else { b.btnSetWallpaper.isEnabled = true; b.btnSetWallpaper.text = "Set Live" }
    }

    // ── Slot load / save ──────────────────────────────────────────────────────
    private fun loadSlotIntoEditor(slot: Int) {
        val p   = WallpaperPrefs.loadSlot(this, slot)
        val bgF = WallpaperPrefs.getBgFileForSlot(this, slot)
        val fgF = WallpaperPrefs.getFgFileForSlot(this, slot)
        val bgBmp = if (bgF.exists()) BitmapFactory.decodeFile(bgF.absolutePath) else null
        val fgBmp = if (fgF.exists()) BitmapFactory.decodeFile(fgF.absolutePath) else null
        loadedBgBmp = bgBmp
        clockColor = if (p.color != WallpaperPrefs.NO_COLOR) p.color else Color.WHITE
        dateColor  = if (p.dateColor != WallpaperPrefs.NO_COLOR) p.dateColor else clockColor
        with(b.editorView) {
            clockX=p.clockX; clockY=p.clockY; clockSz=p.clockSz; clockRot=p.clockRot
            dateX=p.dateX;   dateY=p.dateY;   dateSz=p.dateSz;   dateRot=p.dateRot
            subjX=p.subjX;   subjY=p.subjY;   subjSc=p.subjSc;   subjRot=p.subjRot
            bgRot=p.bgRot;   use24hr=p.use24; showSeconds=p.secs
            bgDim=p.bgDim;   clockDim=p.clkDim; subjDim=p.subjDim; dateDim=p.dateDim
            bgSat=p.bgSat;   subjSat=p.subjSat
            showTime=p.showTime; showDate=p.showDate; verticalClock=p.verticalClock
            zeroPad=p.zeroPad
            this.clockColor = this@EditorActivity.clockColor
            this.dateColor  = this@EditorActivity.dateColor
            setBg(bgBmp); setFg(fgBmp)
        }
        syncSlidersFromEditor()
    }

    private fun syncSlidersFromEditor() {
        with(b.editorView) {
            b.sliderBgRot.value   = bgRot.coerceIn(-180f, 180f);   b.tvBgRotVal.text  = "${bgRot.toInt()}°"
            b.sliderBgDim.value   = (bgDim * 100f).coerceIn(0f, 100f); b.tvBgDimVal.text  = "${(bgDim * 100).toInt()}%"
            b.sliderClkRot.value  = clockRot.coerceIn(-180f, 180f); b.tvClkRotVal.text = "${clockRot.toInt()}°"
            b.sliderDateRot.value = dateRot.coerceIn(-180f, 180f);  b.tvDateRotVal.text= "${dateRot.toInt()}°"
            b.sliderTimeDim.value = (clockDim * 100f).coerceIn(0f, 100f); b.tvTimeDimVal.text= "${(clockDim * 100).toInt()}%"
            b.sliderDateDim.value = (dateDim * 100f).coerceIn(0f, 100f); b.tvDateDimVal.text= "${(dateDim * 100).toInt()}%"
            b.sliderSubjRot.value = subjRot.coerceIn(-180f, 180f);  b.tvSubjRotVal.text= "${subjRot.toInt()}°"
            b.sliderSubjDim.value = (subjDim * 100f).coerceIn(0f, 100f); b.tvSubjDimVal.text= "${(subjDim * 100).toInt()}%"
            b.sliderBgSat.value   = (bgSat   * 100f).coerceIn(0f, 200f); b.tvBgSatVal.text  = "${(bgSat   * 100).toInt()}%"
            b.sliderSubjSat.value = (subjSat * 100f).coerceIn(0f, 200f); b.tvSubjSatVal.text= "${(subjSat * 100).toInt()}%"
            b.switch24hr.isChecked    = use24hr
            b.switchSeconds.isChecked = showSeconds
            b.switchShowTime.isChecked = showTime
            b.switchShowDate.isChecked = showDate
            b.switchVerticalClock.isChecked = verticalClock
            b.switchZeroPad.isChecked = zeroPad
            b.rowZeroPad.visibility = if (use24hr) View.GONE else View.VISIBLE
        }
        b.colorPicker.color = clockColor; updateSwatch(clockColor)
        b.colorPickerDate.color = dateColor; updateDateSwatch(dateColor)
    }

    private fun currentWallPrefs() = with(b.editorView) {
        WallpaperPrefs.WallPrefs(
            clockX, clockY, clockSz, clockRot, dateX, dateY, dateSz, dateRot,
            subjX, subjY, subjSc, subjRot, bgRot, clockColor,
            b.switch24hr.isChecked, b.switchSeconds.isChecked,
            bgDim, clockDim, subjDim, bgSat, subjSat,
            b.switchShowTime.isChecked, b.switchShowDate.isChecked,
            b.switchVerticalClock.isChecked, b.switchZeroPad.isChecked,
            dateColor, dateDim
        )
    }

    private fun saveCurrentSlot() {
        WallpaperPrefs.saveToSlot(this, editSlot, currentWallPrefs())
        WallpaperPrefs.setAutoHide(this, b.switchAutoHide.isChecked)
    }

    // ── Reset dialog ──────────────────────────────────────────────────────────
    private fun showResetDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset Element")
            .setItems(arrayOf("Reset Background", "Reset Clock & Date", "Reset Subject")) { _, which ->
                when (which) {
                    0 -> { resetBackground(); toast("Background reset") }
                    1 -> { resetClockDate();  toast("Clock & date reset") }
                    2 -> { resetSubject();    toast("Subject reset") }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun resetBackground() {
        b.editorView.bgRot = WallpaperPrefs.DEF_BG_ROT; b.editorView.bgDim = 0f
        b.sliderBgRot.value = 0f; b.tvBgRotVal.text = "0°"
        b.sliderBgDim.value = 0f; b.tvBgDimVal.text = "0%"
        b.editorView.invalidate()
    }

    private fun resetClockDate() {
        with(b.editorView) {
            clockX=WallpaperPrefs.DEF_CLK_X; clockY=WallpaperPrefs.DEF_CLK_Y
            clockSz=WallpaperPrefs.DEF_CLK_SZ; clockRot=WallpaperPrefs.DEF_CLK_ROT
            dateX=WallpaperPrefs.DEF_DATE_X; dateY=WallpaperPrefs.DEF_DATE_Y
            dateSz=WallpaperPrefs.DEF_DATE_SZ; dateRot=WallpaperPrefs.DEF_DATE_ROT
            clockDim=0f; dateDim=0f
        }
        b.sliderClkRot.value=0f; b.tvClkRotVal.text="0°"
        b.sliderDateRot.value=0f; b.tvDateRotVal.text="0°"
        b.sliderTimeDim.value=0f; b.tvTimeDimVal.text="0%"
        b.sliderDateDim.value=0f; b.tvDateDimVal.text="0%"
        loadedBgBmp?.let { bmp -> clockColor = smartColor(bmp); b.editorView.clockColor = clockColor; updateSwatch(clockColor); b.colorPicker.color = clockColor }
        b.editorView.invalidate()
    }

    private fun resetSubject() {
        with(b.editorView) {
            subjX=WallpaperPrefs.DEF_SUBJ_X; subjY=WallpaperPrefs.DEF_SUBJ_Y
            subjSc=WallpaperPrefs.DEF_SUBJ_SC; subjRot=WallpaperPrefs.DEF_SUBJ_ROT; subjDim=0f
        }
        b.sliderSubjRot.value=0f; b.tvSubjRotVal.text="0°"
        b.sliderSubjDim.value=0f; b.tvSubjDimVal.text="0%"
        b.editorView.invalidate()
    }

    // ── Bottom sheet ──────────────────────────────────────────────────────────
    private fun setupBottomSheet() {
        if (!hasBottomSheet) return // landscape dual-pane: controls panel is always fully visible
        val dp = resources.displayMetrics.density
        @Suppress("UNCHECKED_CAST")
        bsb = BottomSheetBehavior.from(b.controlsRoot) as LockableBottomSheetBehavior<android.widget.LinearLayout>
        bsb.peekHeight = (PEEK_HEIGHT_DP * dp).toInt(); bsb.state = BottomSheetBehavior.STATE_COLLAPSED
        bsb.isDraggable = true; bsb.skipCollapsed = false; bsb.isHideable = false; bsb.isFitToContents = true
    }

    private fun applyExactSurfaceAspectRatio() {
        val surfW = WallpaperPrefs.getSurfaceW(this).takeIf { it > 0 } ?: getWinW()
        val surfH = WallpaperPrefs.getSurfaceH(this).takeIf { it > 0 } ?: getWinH()
        b.previewContainer.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val contH = b.previewContainer.height; val contW = b.previewArea.width
                if (contH <= 100 || contW <= 0) return
                b.previewContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val dp      = resources.displayMetrics.density
                val padH    = (14 * dp).toInt(); val sidebarR = (88 * dp).toInt()
                val toolbarH = b.toolbarContainer.height.takeIf { it > 0 } ?: ((72 * dp).toInt())
                val avW = (contW - padH * 2 - sidebarR).coerceAtLeast(1)
                val gapH = (BOTTOM_GAP_DP * dp).toInt()
                val avH = if (hasBottomSheet) {
                    val peekH = (PEEK_HEIGHT_DP * dp).toInt()
                    (contH - peekH - gapH - toolbarH - padH).coerceAtLeast((80 * dp).toInt())
                } else {
                    // Landscape dual-pane: the controls panel is a separate side pane,
                    // not an overlapping sheet, so the preview gets the full pane height.
                    (contH - toolbarH - padH - gapH).coerceAtLeast((80 * dp).toInt())
                }
                val hFromW = (avW.toLong() * surfH / surfW).toInt()
                val finalW: Int; val finalH: Int
                if (hFromW <= avH) { finalW = avW; finalH = hFromW } else { finalH = avH; finalW = (avH.toLong() * surfW / surfH).toInt() }
                val lp = b.previewCard.layoutParams as FrameLayout.LayoutParams
                lp.width = finalW; lp.height = finalH
                lp.gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.TOP
                lp.topMargin = toolbarH + padH; b.previewCard.layoutParams = lp
            }
        })
    }

    private fun getWinW() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowManager.currentWindowMetrics.bounds.width()
        else { @Suppress("DEPRECATION") val d = android.util.DisplayMetrics(); @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(d); d.widthPixels }

    private fun getWinH() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowManager.currentWindowMetrics.bounds.height()
        else { @Suppress("DEPRECATION") val d = android.util.DisplayMetrics(); @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(d); d.heightPixels }

    private fun animateEntrance() {
        b.previewCard.alpha = 0f; b.previewCard.translationY = 40f
        b.previewCard.animate().alpha(1f).translationY(0f).setDuration(420).setStartDelay(60).setInterpolator(DecelerateInterpolator(2.2f)).start()
        b.controlsRoot.alpha = 0f
        b.controlsRoot.animate().alpha(1f).setDuration(360).setStartDelay(120).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun animateBtnPress(v: View, action: () -> Unit) {
        v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(70).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(OvershootInterpolator(2.5f)).withEndAction { action() }.start()
        }.start()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { save(); @Suppress("DEPRECATION") super.onBackPressed() }

    // ── Load ──────────────────────────────────────────────────────────────────
    private fun loadData() {
        wallpaperMode = WallpaperPrefs.getWallpaperMode(this)
        editSlot = if (wallpaperMode == WallpaperPrefs.MODE_DAY_NIGHT || wallpaperMode == WallpaperPrefs.MODE_SYSTEM_THEME) WallpaperPrefs.EDIT_DAY else WallpaperPrefs.EDIT_NONE
        val p   = WallpaperPrefs.loadSlot(this, editSlot)
        val bgF = WallpaperPrefs.getBgFileForSlot(this, editSlot)
        val fgF = WallpaperPrefs.getFgFileForSlot(this, editSlot)
        val ftF = WallpaperPrefs.getFontFile(this)
        val ftFDate = WallpaperPrefs.getDateFontFile(this)
        val bgBmp = if (bgF.exists()) BitmapFactory.decodeFile(bgF.absolutePath) else null
        val fgBmp = if (fgF.exists()) BitmapFactory.decodeFile(fgF.absolutePath) else null
        val tf    = if (ftF.exists()) try { Typeface.createFromFile(ftF) } catch (_: Exception) { null } else null
        val dateTf = if (ftFDate.exists()) try { Typeface.createFromFile(ftFDate) } catch (_: Exception) { null } else tf
        loadedBgBmp = bgBmp
        clockColor = if (p.color != WallpaperPrefs.NO_COLOR) p.color else Color.WHITE
        dateColor  = if (p.dateColor != WallpaperPrefs.NO_COLOR) p.dateColor else clockColor
        with(b.editorView) {
            clockX=p.clockX; clockY=p.clockY; clockSz=p.clockSz; clockRot=p.clockRot
            dateX=p.dateX;   dateY=p.dateY;   dateSz=p.dateSz;   dateRot=p.dateRot
            subjX=p.subjX;   subjY=p.subjY;   subjSc=p.subjSc;   subjRot=p.subjRot
            bgRot=p.bgRot;   use24hr=p.use24; showSeconds=p.secs
            bgDim=p.bgDim;   clockDim=p.clkDim; subjDim=p.subjDim; dateDim=p.dateDim
            this.clockColor = this@EditorActivity.clockColor
            this.dateColor  = this@EditorActivity.dateColor
            showTime=p.showTime; showDate=p.showDate; verticalClock=p.verticalClock
            zeroPad=p.zeroPad
            setData(bgBmp, fgBmp, tf, dateTf)
        }
        applyPreviewBg()
    }

    // ── Pills ─────────────────────────────────────────────────────────────────
    private fun applyLockState() {
        if (!hasBottomSheet) return // nothing to lock/unlock; the panel is always fully expanded
        bsb.locked = sheetLocked
        b.ivLock.setImageResource(if (sheetLocked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        val circleTint = if (sheetLocked) attr(com.google.android.material.R.attr.colorSecondaryContainer) else 0x1AFFFFFF.toInt()
        b.btnLock.background = android.graphics.drawable.GradientDrawable().also { gd ->
            gd.shape = android.graphics.drawable.GradientDrawable.OVAL; gd.setColor(circleTint)
        }
        val iconTint = if (sheetLocked) attr(com.google.android.material.R.attr.colorOnSecondaryContainer)
                       else attr(com.google.android.material.R.attr.colorOnSurfaceVariant)
        b.ivLock.setColorFilter(iconTint)
        val lp = b.panelsScroll.layoutParams as android.widget.LinearLayout.LayoutParams
        if (sheetLocked) {
            val dp = resources.displayMetrics.density
            val visH = ((PEEK_HEIGHT_DP - 52f) * dp).toInt()
            lp.height = visH; lp.weight = 0f
        } else { lp.height = 0; lp.weight = 1f }
        b.panelsScroll.layoutParams = lp
    }

    private fun setupPills() {
        b.btnPillBg.setOnClickListener      { switchPanel(Panel.BACKGROUND) }
        b.btnPillTime.setOnClickListener    { showTimeDateMenu() }
        b.btnPillSubject.setOnClickListener { switchPanel(Panel.SUBJECT) }
        b.btnChangeBg.setOnClickListener      { reqPerm(true) }
        b.btnChangeSubject.setOnClickListener { reqPerm(false) }
        b.btnRemoveSubject.setOnClickListener {
            val fgFile = WallpaperPrefs.getFgFileForSlot(this, editSlot)
            fgFile.delete()
            b.editorView.setFg(null)
            b.btnRemoveSubject.visibility = View.GONE
            toast("Subject removed")
        }
        val fgExists = WallpaperPrefs.getFgFileForSlot(this, editSlot).exists()
        b.btnRemoveSubject.visibility = if (fgExists) View.VISIBLE else View.GONE
        b.btnPickFont.setOnClickListener { fontPickIsDate = false; pickFont.launch("*/*") }
        showPanel(Panel.TIME)
    }

    private fun showTimeDateMenu() {
        val themedCtx = android.view.ContextThemeWrapper(this, R.style.EverWallPopupMenuOverlay)
        val popup = android.widget.PopupMenu(themedCtx, b.btnPillTime)
        val iconTint = attr(com.google.android.material.R.attr.colorPrimary)
        val iconTime = ContextCompat.getDrawable(this, R.drawable.ic_clock)?.mutate()?.also {
            it.setTint(iconTint)
        }
        val iconDate = ContextCompat.getDrawable(this, R.drawable.ic_calendar)?.mutate()?.also {
            it.setTint(iconTint)
        }
        popup.menu.add(0, 1, 0, "Time").setIcon(iconTime)
        popup.menu.add(0, 2, 1, "Date").setIcon(iconDate)
        try {
            val mPopup = popup.javaClass.getDeclaredField("mPopup")
            mPopup.isAccessible = true
            val menuHelper = mPopup.get(popup)
            menuHelper.javaClass
                .getDeclaredMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
                .invoke(menuHelper, true)
        } catch (_: Exception) { /* icons simply won't show on the rare device where this fails */ }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> switchPanel(Panel.TIME)
                2 -> switchPanel(Panel.DATE)
            }
            true
        }
        popup.show()
    }

    private fun switchPanel(p: Panel) {
        if (p == panel) return
        val out = panelView(panel)
        out.animate().alpha(0f).translationX(-24f).setDuration(140).setInterpolator(DecelerateInterpolator())
            .withEndAction { out.visibility = View.GONE; out.translationX = 0f; showPanel(p) }.start()
    }

    private fun showPanel(p: Panel) {
        panel = p
        val inP = panelView(p)
        inP.alpha = 0f; inP.translationX = 24f; inP.visibility = View.VISIBLE
        inP.animate().alpha(1f).translationX(0f).setDuration(200).setInterpolator(DecelerateInterpolator(1.5f)).start()
        b.panelBg.visibility      = if (p == Panel.BACKGROUND) View.VISIBLE else View.GONE
        b.panelTime.visibility    = if (p == Panel.TIME)       View.VISIBLE else View.GONE
        b.panelDate.visibility    = if (p == Panel.DATE)       View.VISIBLE else View.GONE
        b.panelSubject.visibility = if (p == Panel.SUBJECT)    View.VISIBLE else View.GONE
        pill(b.btnPillBg,      p == Panel.BACKGROUND)
        pill(b.btnPillTime,    p == Panel.TIME || p == Panel.DATE)
        pill(b.btnPillSubject, p == Panel.SUBJECT)
    }

    private fun panelView(p: Panel): View = when (p) {
        Panel.BACKGROUND -> b.panelBg; Panel.TIME -> b.panelTime
        Panel.DATE -> b.panelDate;     Panel.SUBJECT -> b.panelSubject
    }

    private fun pill(btn: MaterialButton, active: Boolean) {
        val targetBg  = if (active) attr(com.google.android.material.R.attr.colorPrimaryContainer) else Color.TRANSPARENT
        val targetTxt = if (active) attr(com.google.android.material.R.attr.colorOnPrimaryContainer)
                        else attr(com.google.android.material.R.attr.colorOnSurfaceVariant)
        btn.setTextColor(targetTxt)
        val from = btn.backgroundTintList?.defaultColor ?: Color.TRANSPARENT
        ValueAnimator.ofArgb(from, targetBg).apply {
            duration = 200; interpolator = DecelerateInterpolator()
            addUpdateListener { btn.backgroundTintList = android.content.res.ColorStateList.valueOf(it.animatedValue as Int) }
        }.start()
        btn.strokeWidth = if (active) 0 else resources.getDimensionPixelSize(R.dimen.pill_stroke)
    }

    private fun attr(a: Int): Int { val tv = TypedValue(); theme.resolveAttribute(a, tv, true); return tv.data }

    // ── Panels ────────────────────────────────────────────────────────────────
    private fun setupPanels() {
        b.switchAutoHide.isChecked  = WallpaperPrefs.getAutoHide(this)
        b.switchAutoHideDate.isChecked = WallpaperPrefs.getAutoHide(this)
        b.switchShowTime.isChecked  = WallpaperPrefs.loadSlot(this, editSlot).showTime
        b.switchShowDate.isChecked  = WallpaperPrefs.loadSlot(this, editSlot).showDate
        b.switchVerticalClock.isChecked = WallpaperPrefs.loadSlot(this, editSlot).verticalClock
        b.switchZeroPad.isChecked = WallpaperPrefs.loadSlot(this, editSlot).zeroPad
        b.rowZeroPad.visibility = if (b.switch24hr.isChecked) View.GONE else View.VISIBLE

        b.sliderBgRot.addOnChangeListener  { _, v, _ -> b.tvBgRotVal.text  = "${v.toInt()}°"; b.editorView.bgRot    = v }
        b.sliderBgDim.addOnChangeListener  { _, v, _ -> b.tvBgDimVal.text  = "${v.toInt()}%"; b.editorView.bgDim    = v / 100f }
        b.sliderClkRot.addOnChangeListener  { _, v, _ -> b.tvClkRotVal.text  = "${v.toInt()}°"; b.editorView.clockRot  = v }
        b.sliderDateRot.addOnChangeListener { _, v, _ -> b.tvDateRotVal.text = "${v.toInt()}°"; b.editorView.dateRot   = v }
        b.sliderTimeDim.addOnChangeListener { _, v, _ -> b.tvTimeDimVal.text = "${v.toInt()}%"; b.editorView.clockDim  = v / 100f }
        b.sliderSubjRot.addOnChangeListener { _, v, _ -> b.tvSubjRotVal.text = "${v.toInt()}°"; b.editorView.subjRot   = v }
        b.sliderSubjDim.addOnChangeListener { _, v, _ -> b.tvSubjDimVal.text = "${v.toInt()}%"; b.editorView.subjDim   = v / 100f }
        b.sliderBgSat.addOnChangeListener   { _, v, _ -> b.tvBgSatVal.text   = "${v.toInt()}%"; b.editorView.bgSat     = v / 100f }
        b.sliderSubjSat.addOnChangeListener { _, v, _ -> b.tvSubjSatVal.text = "${v.toInt()}%"; b.editorView.subjSat   = v / 100f }
        b.sliderDateDim.addOnChangeListener { _, v, _ -> b.tvDateDimVal.text = "${v.toInt()}%"; b.editorView.dateDim  = v / 100f }
        b.switch24hr.setOnCheckedChangeListener    { _, c ->
            b.editorView.use24hr = c
            b.rowZeroPad.visibility = if (c) View.GONE else View.VISIBLE
        }
        b.switchSeconds.setOnCheckedChangeListener { _, c -> b.editorView.showSeconds = c }
        b.switchAutoHide.setOnCheckedChangeListener { _, v ->
            WallpaperPrefs.setAutoHide(this, v)
            b.switchAutoHideDate.isChecked = v
        }
        b.switchAutoHideDate.setOnCheckedChangeListener { _, v ->
            WallpaperPrefs.setAutoHide(this, v)
            b.switchAutoHide.isChecked = v
        }
        b.switchShowTime.setOnCheckedChangeListener { _, c -> b.editorView.showTime = c; b.editorView.invalidate() }
        b.switchShowDate.setOnCheckedChangeListener { _, c -> b.editorView.showDate = c; b.editorView.invalidate() }
        b.switchVerticalClock.setOnCheckedChangeListener { _, c -> b.editorView.verticalClock = c; b.editorView.invalidate() }
        b.switchZeroPad.setOnCheckedChangeListener { _, c -> b.editorView.zeroPad = c; b.editorView.invalidate() }

        b.chipPickColor.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                colorAtPickOpen = clockColor
                b.colorPicker.visibility = View.VISIBLE
                b.colorPicker.animate().alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator(1.5f)).start()
            } else {
                b.colorPicker.animate().alpha(0f).setDuration(160).withEndAction { b.colorPicker.visibility = View.GONE }.start()
                if (clockColor != colorAtPickOpen) {
                    val finalColor = clockColor
                    askApplyToOther(changedIsTime = true, kindLabel = "color") {
                        dateColor = finalColor; b.editorView.dateColor = finalColor; updateDateSwatch(finalColor)
                        b.colorPickerDate.color = finalColor
                    }
                }
            }
        }
        b.colorPicker.onColorChanged = { c -> clockColor = c; b.editorView.clockColor = c; updateSwatch(c) }

        // Date panel color picker — independent from time, offers to sync once picking is done
        b.chipPickColorDate.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                dateColorAtPickOpen = dateColor
                b.colorPickerDate.visibility = View.VISIBLE
                b.colorPickerDate.animate().alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator(1.5f)).start()
            } else {
                b.colorPickerDate.animate().alpha(0f).setDuration(160).withEndAction { b.colorPickerDate.visibility = View.GONE }.start()
                if (dateColor != dateColorAtPickOpen) {
                    val finalColor = dateColor
                    askApplyToOther(changedIsTime = false, kindLabel = "color") {
                        clockColor = finalColor; b.editorView.clockColor = finalColor; updateSwatch(finalColor)
                        b.colorPicker.color = finalColor
                    }
                }
            }
        }
        b.colorPickerDate.onColorChanged = { c -> dateColor = c; b.editorView.dateColor = c; updateDateSwatch(c) }
        b.colorPickerDate.color = dateColor

        b.btnPickFontDate.setOnClickListener { fontPickIsDate = true; pickFont.launch("*/*") }

        val ftF = WallpaperPrefs.getFontFile(this)
        val ftFDate = WallpaperPrefs.getDateFontFile(this)
        b.tvFontStatus.text = if (ftF.exists()) ftF.name else "System default"
        b.tvDateFontStatus.text = if (ftFDate.exists()) ftFDate.name else if (ftF.exists()) ftF.name else "System default"

        b.colorPicker.visibility = View.GONE; b.colorPicker.alpha = 0f
        b.chipPickColor.isChecked = false
        b.colorPickerDate.visibility = View.GONE; b.colorPickerDate.alpha = 0f
        b.chipPickColorDate.isChecked = false
        syncSlidersFromEditor()
    }

    /** After a font or color change on one of Time/Date, offer to mirror it on the other. */
    private fun askApplyToOther(changedIsTime: Boolean, kindLabel: String, onApplyToOther: () -> Unit) {
        val thisLabel  = if (changedIsTime) "Time"  else "Date"
        val otherLabel = if (changedIsTime) "Date"  else "Time"
        MaterialAlertDialogBuilder(this)
            .setTitle("Use the same $kindLabel?")
            .setMessage("You changed the $kindLabel for $thisLabel. Apply it to $otherLabel as well, or keep them separate?")
            .setPositiveButton("Apply to $otherLabel") { _, _ -> onApplyToOther() }
            .setNegativeButton("Keep Separate", null)
            .show()
    }

    private fun updateSwatch(c: Int) {
        val dp = resources.displayMetrics.density
        val gd = GradientDrawable(); gd.setColor(c); gd.cornerRadius = 8f * dp
        gd.setStroke((1.5f * dp).toInt(), 0x33000000); b.colorSwatch.background = gd
        b.colorSwatch.animate().scaleX(1.18f).scaleY(1.18f).setDuration(80).withEndAction {
            b.colorSwatch.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
    }

    private fun updateDateSwatch(c: Int) {
        val dp = resources.displayMetrics.density
        val gd = GradientDrawable(); gd.setColor(c); gd.cornerRadius = 8f * dp
        gd.setStroke((1.5f * dp).toInt(), 0x33000000); b.colorSwatchDate.background = gd
        b.colorSwatchDate.animate().scaleX(1.18f).scaleY(1.18f).setDuration(80).withEndAction {
            b.colorSwatchDate.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
    }

    // ── Font ─────────────────────────────────────────────────────────────────
    private fun onFont(uri: Uri, isDate: Boolean) {
        val name = displayName(uri) ?: uri.lastPathSegment ?: ""
        if (!name.endsWith(".ttf", ignoreCase = true)) { toast("Please select a .ttf file."); return }
        val destFile = if (isDate) WallpaperPrefs.getDateFontFile(this) else WallpaperPrefs.getFontFile(this)
        contentResolver.openInputStream(uri)?.use { inp -> destFile.outputStream().use { inp.copyTo(it) } }
        val tf = try { Typeface.createFromFile(destFile) } catch (_: Exception) { null }
        if (isDate) {
            WallpaperPrefs.setHasDateFont(this, true)
            b.editorView.setDateFontOnly(tf); b.tvDateFontStatus.text = name
        } else {
            WallpaperPrefs.setHasFont(this, true)
            b.editorView.setFontOnly(tf); b.tvFontStatus.text = name
        }
        toast("Font applied")
        askApplyToOther(changedIsTime = !isDate, kindLabel = "font") {
            val otherFile = if (isDate) WallpaperPrefs.getFontFile(this) else WallpaperPrefs.getDateFontFile(this)
            destFile.copyTo(otherFile, overwrite = true)
            val otherTf = try { Typeface.createFromFile(otherFile) } catch (_: Exception) { null }
            if (isDate) {
                WallpaperPrefs.setHasFont(this, true)
                b.editorView.setFontOnly(otherTf); b.tvFontStatus.text = name
            } else {
                WallpaperPrefs.setHasDateFont(this, true)
                b.editorView.setDateFontOnly(otherTf); b.tvDateFontStatus.text = name
            }
        }
    }

    // ── Image replacement ─────────────────────────────────────────────────────
    private fun reqPerm(bg: Boolean) {
        val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
                else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED) launch(bg)
        else perm.launch(p)
    }

    private fun launch(bg: Boolean) { if (bg) pickBg.launch(arrayOf("image/*")) else pickFg.launch(arrayOf("image/*")) }

    private fun replaceImg(uri: Uri, isBg: Boolean) {
        val targetFile = if (isBg) WallpaperPrefs.getBgFileForSlot(this, editSlot) else WallpaperPrefs.getFgFileForSlot(this, editSlot)
        val isFirstTime = !targetFile.exists()
        try {
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return toast("Failed to read image.")
            val r = minOf(2048f / bmp.width, 2048f / bmp.height)
            val sc = if (r < 1f) Bitmap.createScaledBitmap(bmp,(bmp.width*r).toInt(),(bmp.height*r).toInt(),true) else bmp
            FileOutputStream(targetFile).use { sc.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (isFirstTime) WallpaperPrefs.saveToSlot(this, editSlot,
                WallpaperPrefs.loadSlot(this, editSlot).copy(clockX=WallpaperPrefs.DEF_CLK_X, clockY=WallpaperPrefs.DEF_CLK_Y, subjX=WallpaperPrefs.DEF_SUBJ_X, subjY=WallpaperPrefs.DEF_SUBJ_Y))
            if (isBg) loadedBgBmp = sc
            if (!isBg) b.btnRemoveSubject.visibility = View.VISIBLE
            b.previewCard.animate().alpha(0.3f).setDuration(100).withEndAction {
                loadSlotIntoEditor(editSlot)
                b.previewCard.animate().alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
            }.start()
        } catch (e: Exception) { toast("Error: ${e.message}") }
    }

    // ── Save & set wallpaper ──────────────────────────────────────────────────
    private fun save() { saveCurrentSlot(); WallpaperPrefs.setWallpaperMode(this, wallpaperMode) }

    private fun saveAndLaunch() {
        save()
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this@EditorActivity, DepthWallpaperService::class.java))
            })
        } catch (e: Exception) { toast("Cannot open wallpaper picker: ${e.message}") }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri,null,null,null,null)?.use { c ->
            if (c.moveToFirst()) { val i=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i>=0) return c.getString(i) }
        }; return null
    }

    private fun smartColor(bmp: Bitmap): Int {
        val cx=(0.5f*bmp.width).toInt(); val cy=(0.28f*bmp.height).toInt()
        val rad=(bmp.width*0.12f).toInt().coerceAtLeast(8); val step=maxOf(1,rad/6)
        var total=0L; var n=0
        for (dx in -rad..rad step step) for (dy in -rad..rad step step) {
            val px=(cx+dx).coerceIn(0,bmp.width-1); val py=(cy+dy).coerceIn(0,bmp.height-1)
            val c=bmp.getPixel(px,py)
            total+=(0.299*Color.red(c)+0.587*Color.green(c)+0.114*Color.blue(c)).toLong(); n++
        }
        return if (n>0 && total/n>128) Color.BLACK else Color.WHITE
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ── About / Settings ──────────────────────────────────────────────────────
    private fun showAboutDialog() {
        val dialog = BottomSheetDialog(this)
        val db = DialogAboutBinding.inflate(layoutInflater)

        // Version — always from packageInfo (single source: build.gradle)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            db.tvVersion.text = "Version ${pInfo.versionName}"
        } catch (_: Exception) {
            db.tvVersion.text = "Version ${UpdateChecker.APP_VERSION}"
        }

        // Theme toggle
        db.toggleTheme.check(when (WallpaperPrefs.getAppTheme(this)) {
            WallpaperPrefs.THEME_LIGHT -> R.id.btn_theme_light
            WallpaperPrefs.THEME_DARK  -> R.id.btn_theme_dark
            else                       -> R.id.btn_theme_auto
        })
        db.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val theme = when (checkedId) {
                    R.id.btn_theme_light -> WallpaperPrefs.THEME_LIGHT
                    R.id.btn_theme_dark  -> WallpaperPrefs.THEME_DARK
                    else                 -> WallpaperPrefs.THEME_AUTO
                }
                WallpaperPrefs.setAppTheme(this, theme); applyThemeMode()
            }
        }

        // Background behind preview toggle
        db.swBgBehindPreview.isChecked = WallpaperPrefs.getBgBehindPreview(this)
        db.swBgBehindPreview.setOnCheckedChangeListener { _, v ->
            WallpaperPrefs.setBgBehindPreview(this, v); applyPreviewBg()
            if (wallpaperMode != WallpaperPrefs.MODE_NONE) pillDayNight(editSlot)
        }

        // Auto check updates toggle
        db.swAutoCheckUpdates.isChecked = WallpaperPrefs.getAutoCheckUpdates(this)
        db.swAutoCheckUpdates.setOnCheckedChangeListener { _, v ->
            WallpaperPrefs.setAutoCheckUpdates(this, v)
        }

        // Check for updates button
        db.cardCheckUpdates.setOnClickListener {
            performUpdateCheck(
                onStatusChange  = { msg -> db.tvUpdateStatus.text = msg },
                onLoadingChange = { loading ->
                    db.progressUpdate.visibility = if (loading) View.VISIBLE else View.GONE
                    db.ivUpdateArrow.visibility  = if (loading) View.GONE else View.VISIBLE
                    db.cardCheckUpdates.isClickable = !loading
                }
            )
        }

        db.cardSupportGroup.setOnClickListener { openUrl("https://t.me/EverlastingAndroidTweak") }
        db.cardAppChannel.setOnClickListener   { openUrl("https://t.me/CoolAppStore") }
        dialog.setContentView(db.root); dialog.show()
    }

    /**
     * Core update-check routine shared between the manual button and the auto-startup check.
     * [onStatusChange] and [onLoadingChange] are UI callbacks invoked on the main thread.
     * [onUpdateFound] is called (on main thread) when a release is found and before any
     * download/install action so the caller can show its own prompt; if null the default
     * behaviour (toast + download) is used.
     */
    private fun performUpdateCheck(
        onStatusChange:  (String) -> Unit = {},
        onLoadingChange: (Boolean) -> Unit = {},
        onUpdateFound:   ((UpdateChecker.ReleaseInfo) -> Unit)? = null
    ) {
        lifecycleScope.launch {
            onLoadingChange(true)
            onStatusChange("Checking for updates…")

            val release = withContext(Dispatchers.IO) {
                UpdateChecker.checkForUpdate(this@EditorActivity)
            }

            if (release == null) {
                onStatusChange("You're up to date")
                onLoadingChange(false)
                return@launch
            }

            // Delegate to custom handler (e.g. startup popup) if provided
            if (onUpdateFound != null) {
                onLoadingChange(false)
                onUpdateFound(release)
                return@launch
            }

            // Default: check if already downloaded, else start download
            val existing = UpdateChecker.getDownloadedApk(release.apkFileName)
            if (existing != null) {
                onStatusChange("Update ready — launching installer…")
                onLoadingChange(false)
                UpdateChecker.installApk(this@EditorActivity, existing)
            } else {
                onStatusChange("Downloading v${release.versionName}…")
                UpdateChecker.downloadApk(
                    context     = this@EditorActivity,
                    apkUrl      = release.apkUrl,
                    apkFileName = release.apkFileName,
                    onProgress  = { msg -> runOnUiThread { onStatusChange(msg) } },
                    onComplete  = { file ->
                        runOnUiThread {
                            onLoadingChange(false)
                            if (file != null) {
                                onStatusChange("Download complete — launching installer…")
                                UpdateChecker.installApk(this@EditorActivity, file)
                            } else {
                                onStatusChange("Download failed. Please try again.")
                            }
                        }
                    }
                )
            }
        }
    }

    /** Auto-checks for updates at app startup and shows a floating popup if one is found. */
    private fun autoCheckForUpdates() {
        if (!WallpaperPrefs.getAutoCheckUpdates(this)) return
        lifecycleScope.launch {
            // Small delay so the UI settles first
            kotlinx.coroutines.delay(1500)
            performUpdateCheck(
                onUpdateFound = { release -> showUpdatePopup(release) }
            )
        }
    }

    /** Shows a modern bottom-sheet popup asking the user to download the available update. */
    private fun showUpdatePopup(release: UpdateChecker.ReleaseInfo) {
        if (isFinishing || isDestroyed) return
        val dialog = BottomSheetDialog(this)
        val dp = resources.displayMetrics.density
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (32 * dp).toInt())
        }

        // Icon + title row
        val header = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val iconFrame = FrameLayout(this).apply {
            val sz = (44 * dp).toInt()
            layoutParams = android.widget.LinearLayout.LayoutParams(sz, sz).also {
                it.marginEnd = (14 * dp).toInt()
            }
            background = ContextCompat.getDrawable(this@EditorActivity, R.drawable.bg_icon_circle_primary)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                attr(com.google.android.material.R.attr.colorPrimaryContainer))
        }
        val icon = android.widget.ImageView(this).apply {
            val sz = (22 * dp).toInt()
            layoutParams = FrameLayout.LayoutParams(sz, sz, android.view.Gravity.CENTER)
            setImageResource(R.drawable.ic_cloud)
            imageTintList = android.content.res.ColorStateList.valueOf(
                attr(com.google.android.material.R.attr.colorOnPrimaryContainer))
        }
        iconFrame.addView(icon)
        header.addView(iconFrame)

        val titleBlock = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTitle = android.widget.TextView(this).apply {
            text = "Update Available"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurface))
        }
        val tvSub = android.widget.TextView(this).apply {
            text = "Ever Wall ${release.versionName} is ready to install"
            textSize = 13f
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, (2 * dp).toInt(), 0, 0)
        }
        titleBlock.addView(tvTitle); titleBlock.addView(tvSub)
        header.addView(titleBlock)
        root.addView(header)

        // Status message
        val tvStatus = android.widget.TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, (10 * dp).toInt(), 0, 0)
            visibility = View.GONE
        }
        root.addView(tvStatus)

        // Progress bar
        val progressBar = android.widget.ProgressBar(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (4 * dp).toInt()).also {
                it.topMargin = (12 * dp).toInt()
            }
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progressBar)

        // Buttons row
        val btnRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = (20 * dp).toInt()
            }
        }

        val btnCancel = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Not Now"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.marginEnd = (8 * dp).toInt()
            }
            setOnClickListener { dialog.dismiss() }
        }

        val btnDownload = com.google.android.material.button.MaterialButton(this).apply {
            text = "Download & Install"
            setOnClickListener {
                isEnabled = false; btnCancel.isEnabled = false
                tvStatus.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE

                val existing = UpdateChecker.getDownloadedApk(release.apkFileName)
                if (existing != null) {
                    tvStatus.text = "Launching installer…"
                    progressBar.visibility = View.GONE
                    UpdateChecker.installApk(this@EditorActivity, existing)
                    dialog.dismiss()
                } else {
                    tvStatus.text = "Downloading v${release.versionName}…"
                    UpdateChecker.downloadApk(
                        context     = this@EditorActivity,
                        apkUrl      = release.apkUrl,
                        apkFileName = release.apkFileName,
                        onProgress  = { msg -> runOnUiThread { tvStatus.text = msg } },
                        onComplete  = { file ->
                            runOnUiThread {
                                progressBar.visibility = View.GONE
                                if (file != null) {
                                    tvStatus.text = "Download complete — launching installer…"
                                    UpdateChecker.installApk(this@EditorActivity, file)
                                    dialog.dismiss()
                                } else {
                                    tvStatus.text = "Download failed. Please try again."
                                    isEnabled = true; btnCancel.isEnabled = true
                                }
                            }
                        }
                    )
                }
            }
        }

        btnRow.addView(btnCancel); btnRow.addView(btnDownload)
        root.addView(btnRow)

        // Animate popup in
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(280)
            .setInterpolator(DecelerateInterpolator(2f)).start()

        dialog.setContentView(root)
        dialog.show()
    }

    private fun applyThemeMode() {
        AppCompatDelegate.setDefaultNightMode(when (WallpaperPrefs.getAppTheme(this)) {
            WallpaperPrefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            WallpaperPrefs.THEME_DARK  -> AppCompatDelegate.MODE_NIGHT_YES
            else                       -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
    }

    private fun applyPreviewBg() {
        val enabled       = WallpaperPrefs.getBgBehindPreview(this)
        val isNight       = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val controller    = WindowInsetsControllerCompat(window, window.decorView)
        val primaryCont   = attr(com.google.android.material.R.attr.colorPrimaryContainer)
        val onPrimaryCont = attr(com.google.android.material.R.attr.colorOnPrimaryContainer)
        val primary       = attr(com.google.android.material.R.attr.colorPrimary)
        val onPrimary     = attr(com.google.android.material.R.attr.colorOnPrimary)
        bannerDrawable.setFillColor(primaryCont)
        b.tvAppTitle.setTextColor(onPrimaryCont)
        b.tvAppSubtitle.setTextColor(onPrimaryCont)
        b.btnSettings.backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
        b.ivSettingsIcon.imageTintList   = android.content.res.ColorStateList.valueOf(onPrimary)
        controller.isAppearanceLightStatusBars = !isNight
        for (i in 0 until b.toolbarContainer.childCount) {
            val child = b.toolbarContainer.getChildAt(i)
            if ("toolbar_deco" == child.tag) child.backgroundTintList = android.content.res.ColorStateList.valueOf(onPrimaryCont)
        }
        if (enabled) {
            b.previewContainer.setBackgroundColor(primaryCont)
            b.previewBgFrame.setBackgroundColor(primaryCont); b.previewBgFrame.visibility = android.view.View.VISIBLE
            b.ivSun.visibility  = if (!isNight) android.view.View.VISIBLE else android.view.View.GONE
            b.ivMoon.visibility = if (isNight)  android.view.View.VISIBLE else android.view.View.GONE
            val darkCard = ContextCompat.getDrawable(this, R.drawable.bg_set_time_card)
            b.btnSetDayTime.background = darkCard; b.btnSetNightTime.background = darkCard?.constantState?.newDrawable()
            applySetTimeTextColor(attr(com.google.android.material.R.attr.colorOnSurface))
        } else {
            b.previewContainer.setBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))
            b.previewBgFrame.visibility = android.view.View.GONE
            b.ivSun.visibility = android.view.View.GONE; b.ivMoon.visibility = android.view.View.GONE
            val lightCard = ContextCompat.getDrawable(this, R.drawable.bg_set_time_card_light)
            b.btnSetDayTime.background = lightCard; b.btnSetNightTime.background = lightCard?.constantState?.newDrawable()
            applySetTimeTextColor(android.graphics.Color.WHITE)
        }
    }

    private fun applySetTimeTextColor(color: Int) {
        val tint = android.content.res.ColorStateList.valueOf(color)
        fun traverse(v: android.view.View) {
            when (v) {
                is android.widget.TextView  -> v.setTextColor(color)
                is android.widget.ImageView -> v.imageTintList = tint
                is android.view.ViewGroup   -> repeat(v.childCount) { traverse(v.getChildAt(it)) }
            }
        }
        traverse(b.setTimeContainer)
    }

    private fun showWelcomeIfFirstLaunch() {
        val prefs = getSharedPreferences("everwall_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("welcome_shown", false)) return
        prefs.edit().putBoolean("welcome_shown", true).apply()
        val dialog = BottomSheetDialog(this); dialog.setCancelable(false)
        val wb = DialogWelcomeBinding.inflate(layoutInflater)
        wb.btnWelcomeContinue.setOnClickListener { dialog.dismiss() }
        wb.btnWelcomeJoin.setOnClickListener { openUrl("https://t.me/EverlastingAndroidTweak"); dialog.dismiss() }
        dialog.setContentView(wb.root); dialog.show()
    }

    private fun isNotificationListenerGranted(): Boolean {
        val flat = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { it.trim().startsWith(packageName) }
    }

    private fun showMusicArtDialog() {
        val dialog = BottomSheetDialog(this)
        val mb = DialogMusicArtBinding.inflate(layoutInflater)

        val granted = isNotificationListenerGranted()

        mb.switchMusicArt.isChecked = WallpaperPrefs.getMusicArtEnabled(this)
        mb.switchMusicArt.isEnabled = granted
        mb.switchMusicArt.setOnCheckedChangeListener { _, checked ->
            WallpaperPrefs.setMusicArtEnabled(this, checked)
            updateMusicArtButtonTint(checked)
            if (!checked) {
                WallpaperPrefs.getMusicArtFile(this).delete()
                LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(Intent(WallpaperPrefs.ACTION_MUSIC_ART_CHANGED))
            }
        }

        val savedDim = (WallpaperPrefs.getMusicArtDim(this) * 100f).toInt()
        mb.sliderMusicArtDim.value = savedDim.toFloat()
        mb.tvMusicArtDimVal.text = "$savedDim%"
        mb.sliderMusicArtDim.addOnChangeListener { _, v, _ ->
            mb.tvMusicArtDimVal.text = "${v.toInt()}%"
            WallpaperPrefs.setMusicArtDim(this, v / 100f)
        }

        val savedBlur = (WallpaperPrefs.getMusicArtBlur(this) * 100f).toInt()
        mb.sliderMusicArtBlur.value = savedBlur.toFloat()
        mb.tvMusicArtBlurVal.text = "$savedBlur%"
        mb.sliderMusicArtBlur.addOnChangeListener { _, v, _ ->
            mb.tvMusicArtBlurVal.text = "${v.toInt()}%"
            WallpaperPrefs.setMusicArtBlur(this, v / 100f)
        }

        if (granted) {
            mb.btnGrantNotifAccess.visibility = View.GONE
        } else {
            mb.btnGrantNotifAccess.visibility = View.VISIBLE
            mb.btnGrantNotifAccess.setOnClickListener {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) { toast("Cannot open settings") }
                dialog.dismiss()
            }
        }

        dialog.setContentView(mb.root); dialog.show()
    }

    private fun updateMusicArtButtonTint(enabled: Boolean) {
        val tint = if (enabled) attr(com.google.android.material.R.attr.colorPrimaryContainer) else Color.TRANSPARENT
        val textTint = if (enabled) attr(com.google.android.material.R.attr.colorOnPrimaryContainer) else attr(com.google.android.material.R.attr.colorOnSurfaceVariant)
        b.btnMusicArt.backgroundTintList = android.content.res.ColorStateList.valueOf(tint)
        b.btnMusicArt.setTextColor(textTint)
        b.btnMusicArt.iconTint = android.content.res.ColorStateList.valueOf(textTint)
        b.btnMusicArt.strokeWidth = if (enabled) 0 else resources.getDimensionPixelSize(R.dimen.pill_stroke)
    }

    // ── Presets ──────────────────────────────────────────────────────────────
    /** A floating popup listing every saved preset; tapping one swaps its full
     *  customisation (background, subject, time, date, mode) into the editor
     *  without touching any other preset's saved state. */
    private fun showPresetsDialog() {
        val dialog = BottomSheetDialog(this)
        val dp = resources.displayMetrics.density

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (28 * dp).toInt())
        }

        val handle = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams((36 * dp).toInt(), (4 * dp).toInt()).also {
                it.gravity = android.view.Gravity.CENTER_HORIZONTAL
                it.topMargin = (6 * dp).toInt(); it.bottomMargin = (10 * dp).toInt()
            }
            background = ContextCompat.getDrawable(this@EditorActivity, R.drawable.bg_drag_handle)
        }
        root.addView(handle)

        val header = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val headerIcon = android.widget.ImageView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt()).also {
                it.marginEnd = (10 * dp).toInt()
            }
            setImageResource(R.drawable.ic_presets)
            imageTintList = android.content.res.ColorStateList.valueOf(attr(com.google.android.material.R.attr.colorPrimary))
        }
        val headerTitle = android.widget.TextView(this).apply {
            text = "Presets"; textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurface))
        }
        header.addView(headerIcon); header.addView(headerTitle)
        root.addView(header)

        val subtitle = android.widget.TextView(this).apply {
            text = "Switch between saved wallpaper customisations. Each preset keeps its own background, subject, time and date setup."
            textSize = 12.5f
            setTextColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, (4 * dp).toInt(), 0, (16 * dp).toInt())
            setLineSpacing((2 * dp), 1f)
        }
        root.addView(subtitle)

        val listContainer = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        root.addView(listContainer)

        val activeId = WallpaperPrefs.getActivePreset(this)
        val count    = WallpaperPrefs.getPresetCount(this)

        for (index in 0 until count) {
            val active = index == activeId
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (52 * dp).toInt()
                ).also { it.bottomMargin = (10 * dp).toInt() }
                radius = 14f * dp
                cardElevation = 0f
                strokeWidth = if (active) 0 else (1.2f * dp).toInt()
                strokeColor = attr(com.google.android.material.R.attr.colorOutlineVariant)
                setCardBackgroundColor(if (active) attr(com.google.android.material.R.attr.colorPrimaryContainer)
                                       else attr(com.google.android.material.R.attr.colorSurfaceContainerHigh))
                isClickable = true; isFocusable = true
            }
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            }
            val label = android.widget.TextView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "Preset ${index + 1}"
                textSize = 14.5f
                setTypeface(typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(if (active) attr(com.google.android.material.R.attr.colorOnPrimaryContainer)
                             else attr(com.google.android.material.R.attr.colorOnSurface))
            }
            row.addView(label)
            if (active) {
                row.addView(android.widget.TextView(this).apply {
                    text = "ACTIVE"; textSize = 10.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(attr(com.google.android.material.R.attr.colorOnPrimaryContainer))
                    letterSpacing = 0.08f
                })
            }
            card.addView(row)
            card.setOnClickListener {
                if (index != activeId) {
                    save()
                    skipPauseSave = true
                    WallpaperPrefs.switchToPreset(this, index)
                    dialog.dismiss()
                    restartEditorFresh()
                } else dialog.dismiss()
            }
            listContainer.addView(card)
        }

        val addBtn = com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "+ Add Preset"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = (2 * dp).toInt() }
        }
        addBtn.setOnClickListener {
            save()
            skipPauseSave = true
            WallpaperPrefs.addPreset(this)
            dialog.dismiss()
            restartEditorFresh()
        }
        root.addView(addBtn)

        root.alpha = 0f
        root.animate().alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator(2f)).start()

        dialog.setContentView(root)
        dialog.show()
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { toast("Cannot open link") }
    }
}
