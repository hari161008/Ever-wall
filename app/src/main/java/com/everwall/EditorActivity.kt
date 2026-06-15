package com.everwall

import android.Manifest
import android.animation.ValueAnimator
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
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
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.everwall.databinding.ActivityEditorBinding
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class EditorActivity : AppCompatActivity() {

    private lateinit var b: ActivityEditorBinding
    private enum class Panel { BACKGROUND, TIME, SUBJECT }
    private var panel = Panel.TIME
    private var clockColor = Color.WHITE
    private var loadedBgBmp: Bitmap? = null
    private lateinit var bsb: LockableBottomSheetBehavior<android.widget.LinearLayout>
    private var sheetLocked = false

    // Mode / Day-Night
    private var wallpaperMode = WallpaperPrefs.MODE_NONE
    private var editSlot      = WallpaperPrefs.EDIT_NONE

    private val pickBg   = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> replaceImg(u, true) } }
    private val pickFg   = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> replaceImg(u, false) } }
    private val pickFont = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> onFont(u) } }
    private val perm     = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (!it) toast("Storage permission required.") }

    companion object {
        private const val PEEK_HEIGHT_DP = 368f
        private const val BOTTOM_GAP_DP  = 32f
    }

    override fun onCreate(s: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(s)
        b = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(b.root)

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
            bsb.locked = sheetLocked
            b.ivLock.setImageResource(if (sheetLocked) R.drawable.ic_lock else R.drawable.ic_lock_open)

            // Tint circle: highlighted when locked
            val circleTint = if (sheetLocked)
                attr(com.google.android.material.R.attr.colorSecondaryContainer)
            else 0x1AFFFFFF.toInt()
            b.btnLock.background = android.graphics.drawable.GradientDrawable().also { gd ->
                gd.shape = android.graphics.drawable.GradientDrawable.OVAL
                gd.setColor(circleTint)
            }
            val iconTint = if (sheetLocked)
                attr(com.google.android.material.R.attr.colorOnSecondaryContainer)
            else attr(com.google.android.material.R.attr.colorOnSurfaceVariant)
            b.ivLock.setColorFilter(iconTint)

            // Constrain NestedScrollView height to the visible peek area when locked
            // so the user can scroll to the absolute bottom of all content
            val lp = b.panelsScroll.layoutParams as android.widget.LinearLayout.LayoutParams
            if (sheetLocked) {
                val dp = resources.displayMetrics.density
                val visH = ((PEEK_HEIGHT_DP - 52f) * dp).toInt()  // peek minus drag handle
                lp.height  = visH
                lp.weight  = 0f
            } else {
                lp.height  = 0
                lp.weight  = 1f
            }
            b.panelsScroll.layoutParams = lp
        }

        val dp = resources.displayMetrics.density
        b.controlsRoot.post {
            b.controlsRoot.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    val r = 28f * dp
                    o.setRoundRect(0, 0, v.width, (v.height + r).toInt(), r)
                }
            }
            b.controlsRoot.clipToOutline = true
        }
        animateEntrance()
    }

    override fun onPause() { save(); super.onPause() }

    // ── Mode / Day-Night ──────────────────────────────────────────────────────
    private fun setupModeButton() {
        b.btnMode.text = modeLabel()
        b.btnMode.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Wallpaper Mode")
                .setSingleChoiceItems(arrayOf("None", "Day and Night"),
                    if (wallpaperMode == WallpaperPrefs.MODE_NONE) 0 else 1) { dialog, which ->
                    val newMode = if (which == 0) WallpaperPrefs.MODE_NONE else WallpaperPrefs.MODE_DAY_NIGHT
                    if (newMode != wallpaperMode) {
                        saveCurrentSlot()
                        applyWallpaperMode(newMode)
                    }
                    dialog.dismiss()
                }.show()
        }

        b.btnDay.setOnClickListener {
            if (editSlot != WallpaperPrefs.EDIT_DAY) {
                saveCurrentSlot()
                editSlot = WallpaperPrefs.EDIT_DAY
                loadSlotIntoEditor(WallpaperPrefs.EDIT_DAY)
                pillDayNight(WallpaperPrefs.EDIT_DAY)
            }
        }
        b.btnNight.setOnClickListener {
            if (editSlot != WallpaperPrefs.EDIT_NIGHT) {
                saveCurrentSlot()
                editSlot = WallpaperPrefs.EDIT_NIGHT
                loadSlotIntoEditor(WallpaperPrefs.EDIT_NIGHT)
                pillDayNight(WallpaperPrefs.EDIT_NIGHT)
            }
        }
        b.btnSetDayTime.setOnClickListener   { showTimePicker(isDay = true) }
        b.btnSetNightTime.setOnClickListener { showTimePicker(isDay = false) }

        // Restore mode UI if already in Day/Night mode
        if (wallpaperMode == WallpaperPrefs.MODE_DAY_NIGHT) {
            showDayNightUI(true)
            pillDayNight(editSlot)
        }
        updateDayNightTimeLabels()
        updateSetLiveButton()
    }

    private fun applyWallpaperMode(mode: Int) {
        wallpaperMode = mode
        WallpaperPrefs.setWallpaperMode(this, mode)
        if (mode == WallpaperPrefs.MODE_DAY_NIGHT) {
            editSlot = WallpaperPrefs.EDIT_DAY
            showDayNightUI(true)
            loadSlotIntoEditor(WallpaperPrefs.EDIT_DAY)
            pillDayNight(WallpaperPrefs.EDIT_DAY)
        } else {
            editSlot = WallpaperPrefs.EDIT_NONE
            showDayNightUI(false)
            loadSlotIntoEditor(WallpaperPrefs.EDIT_NONE)
        }
        b.btnMode.text = modeLabel()
        updateSetLiveButton()
    }

    private fun showDayNightUI(show: Boolean) {
        b.dayNightOverlay.visibility = if (show) View.VISIBLE else View.GONE
        // Adjust peek height to accommodate the day/night bar
        val dp = resources.displayMetrics.density
        val barH = if (show) (120 * dp).toInt() else 0
        bsb.peekHeight = (PEEK_HEIGHT_DP * dp).toInt() + barH
    }

    private fun pillDayNight(slot: Int) {
        val dayActive  = slot == WallpaperPrefs.EDIT_DAY
        val activeTint = attr(com.google.android.material.R.attr.colorPrimaryContainer)
        val inactTint  = attr(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        b.btnDay.backgroundTintList   = android.content.res.ColorStateList.valueOf(if (dayActive) activeTint else inactTint)
        b.btnNight.backgroundTintList = android.content.res.ColorStateList.valueOf(if (!dayActive) activeTint else inactTint)
    }

    private fun modeLabel() = if (wallpaperMode == WallpaperPrefs.MODE_NONE) "Mode: None" else "Mode: Day & Night"

    private fun showTimePicker(isDay: Boolean) {
        val savedMins = if (isDay) WallpaperPrefs.getDayMins(this) else WallpaperPrefs.getNightMins(this)
        val h = if (savedMins >= 0) savedMins / 60 else if (isDay) 6 else 20
        val m = if (savedMins >= 0) savedMins % 60 else 0
        TimePickerDialog(this, { _, hour, minute ->
            val mins = hour * 60 + minute
            if (isDay) WallpaperPrefs.setDayMins(this, mins)
            else       WallpaperPrefs.setNightMins(this, mins)
            updateDayNightTimeLabels()
            updateSetLiveButton()
        }, h, m, DateFormat.is24HourFormat(this)).show()
    }

    private fun updateDayNightTimeLabels() {
        val d = WallpaperPrefs.getDayMins(this)
        val n = WallpaperPrefs.getNightMins(this)
        b.btnSetDayTime.text   = if (d >= 0) "Day: ${fmtMins(d)}"   else "Set Day"
        b.btnSetNightTime.text = if (n >= 0) "Night: ${fmtMins(n)}" else "Set Night"
    }

    private fun fmtMins(mins: Int): String {
        val h = mins / 60; val m = mins % 60
        return if (DateFormat.is24HourFormat(this)) "%02d:%02d".format(h, m)
        else { val ampm = if (h < 12) "AM" else "PM"; val h12 = if (h % 12 == 0) 12 else h % 12
               "%d:%02d %s".format(h12, m, ampm) }
    }

    private fun updateSetLiveButton() {
        if (wallpaperMode == WallpaperPrefs.MODE_DAY_NIGHT) {
            val daySet   = WallpaperPrefs.getDayMins(this) >= 0
            val nightSet = WallpaperPrefs.getNightMins(this) >= 0
            val both     = daySet && nightSet
            b.btnSetWallpaper.isEnabled = both
            b.btnSetWallpaper.text = when {
                !daySet && !nightSet -> "Set Day & Night"
                !daySet   -> "Set Day Time"
                !nightSet -> "Set Night Time"
                else -> "Set Live"
            }
        } else {
            b.btnSetWallpaper.isEnabled = true
            b.btnSetWallpaper.text = "Set Live"
        }
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
        with(b.editorView) {
            clockX=p.clockX; clockY=p.clockY; clockSz=p.clockSz; clockRot=p.clockRot
            dateX=p.dateX;   dateY=p.dateY;   dateSz=p.dateSz;   dateRot=p.dateRot
            subjX=p.subjX;   subjY=p.subjY;   subjSc=p.subjSc;   subjRot=p.subjRot
            bgRot=p.bgRot;   use24hr=p.use24; showSeconds=p.secs
            bgDim=p.bgDim;   clockDim=p.clkDim; subjDim=p.subjDim
            bgSat=p.bgSat;   subjSat=p.subjSat
            this.clockColor = this@EditorActivity.clockColor
            setBg(bgBmp); setFg(fgBmp)
        }
        syncSlidersFromEditor()
    }

    private fun syncSlidersFromEditor() {
        with(b.editorView) {
            b.sliderBgRot.value   = bgRot.coerceIn(-180f, 180f)
            b.tvBgRotVal.text     = "${bgRot.toInt()}°"
            b.sliderBgDim.value   = (bgDim * 100f).coerceIn(0f, 100f)
            b.tvBgDimVal.text     = "${(bgDim * 100).toInt()}%"
            b.sliderClkRot.value  = clockRot.coerceIn(-180f, 180f)
            b.tvClkRotVal.text    = "${clockRot.toInt()}°"
            b.sliderDateRot.value = dateRot.coerceIn(-180f, 180f)
            b.tvDateRotVal.text   = "${dateRot.toInt()}°"
            b.sliderTimeDim.value = (clockDim * 100f).coerceIn(0f, 100f)
            b.tvTimeDimVal.text   = "${(clockDim * 100).toInt()}%"
            b.sliderSubjRot.value = subjRot.coerceIn(-180f, 180f)
            b.tvSubjRotVal.text   = "${subjRot.toInt()}°"
            b.sliderSubjDim.value = (subjDim * 100f).coerceIn(0f, 100f)
            b.tvSubjDimVal.text   = "${(subjDim * 100).toInt()}%"
            b.sliderBgSat.value   = (bgSat   * 100f).coerceIn(0f, 200f)
            b.tvBgSatVal.text     = "${(bgSat   * 100).toInt()}%"
            b.sliderSubjSat.value = (subjSat * 100f).coerceIn(0f, 200f)
            b.tvSubjSatVal.text   = "${(subjSat * 100).toInt()}%"
            b.switch24hr.isChecked    = use24hr
            b.switchSeconds.isChecked = showSeconds
        }
        b.colorPicker.color = clockColor
        updateSwatch(clockColor)
    }

    private fun currentWallPrefs() = with(b.editorView) {
        WallpaperPrefs.WallPrefs(
            clockX, clockY, clockSz, clockRot,
            dateX,  dateY,  dateSz,  dateRot,
            subjX,  subjY,  subjSc,  subjRot,
            bgRot, clockColor,
            b.switch24hr.isChecked, b.switchSeconds.isChecked,
            bgDim, clockDim, subjDim,
            bgSat, subjSat
        )
    }

    private fun saveCurrentSlot() {
        WallpaperPrefs.saveToSlot(this, editSlot, currentWallPrefs())
        WallpaperPrefs.setAutoHide(this, b.switchAutoHide.isChecked)
    }

    // ── Reset dialog ──────────────────────────────────────────────────────────
    private fun showResetDialog() {
        val items = arrayOf("Reset Background", "Reset Clock & Date", "Reset Subject")
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset Element")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { resetBackground(); toast("Background reset") }
                    1 -> { resetClockDate();  toast("Clock & date reset") }
                    2 -> { resetSubject();    toast("Subject reset") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetBackground() {
        b.editorView.bgRot = WallpaperPrefs.DEF_BG_ROT
        b.editorView.bgDim = 0f
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
            clockDim=0f
        }
        b.sliderClkRot.value=0f; b.tvClkRotVal.text="0°"
        b.sliderDateRot.value=0f; b.tvDateRotVal.text="0°"
        b.sliderTimeDim.value=0f; b.tvTimeDimVal.text="0%"
        loadedBgBmp?.let { bmp ->
            clockColor = smartColor(bmp); b.editorView.clockColor = clockColor
            updateSwatch(clockColor); b.colorPicker.color = clockColor
        }
        b.editorView.invalidate()
    }

    private fun resetSubject() {
        with(b.editorView) {
            subjX=WallpaperPrefs.DEF_SUBJ_X; subjY=WallpaperPrefs.DEF_SUBJ_Y
            subjSc=WallpaperPrefs.DEF_SUBJ_SC; subjRot=WallpaperPrefs.DEF_SUBJ_ROT
            subjDim=0f
        }
        b.sliderSubjRot.value=0f; b.tvSubjRotVal.text="0°"
        b.sliderSubjDim.value=0f; b.tvSubjDimVal.text="0%"
        b.editorView.invalidate()
    }

    // ── Bottom sheet ──────────────────────────────────────────────────────────
    private fun setupBottomSheet() {
        val dp = resources.displayMetrics.density
        @Suppress("UNCHECKED_CAST")
        bsb = BottomSheetBehavior.from(b.controlsRoot) as LockableBottomSheetBehavior<android.widget.LinearLayout>
        bsb.peekHeight      = (PEEK_HEIGHT_DP * dp).toInt()
        bsb.state           = BottomSheetBehavior.STATE_COLLAPSED
        bsb.isDraggable     = true
        bsb.skipCollapsed   = false
        bsb.isHideable      = false
        bsb.isFitToContents = true
    }

    private fun applyExactSurfaceAspectRatio() {
        val surfW = WallpaperPrefs.getSurfaceW(this).takeIf { it > 0 } ?: getWinW()
        val surfH = WallpaperPrefs.getSurfaceH(this).takeIf { it > 0 } ?: getWinH()
        b.previewContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val contH = b.previewContainer.height
                    val contW = b.previewContainer.width
                    if (contH <= 100 || contW <= 0) return
                    b.previewContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val dp       = resources.displayMetrics.density
                    val peekH    = (PEEK_HEIGHT_DP * dp).toInt()
                    val gapH     = (BOTTOM_GAP_DP  * dp).toInt()
                    val toolbarH = b.toolbarContainer.height.takeIf { it > 0 }
                        ?: TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 56f, resources.displayMetrics).toInt()
                    val padH     = (14 * dp).toInt()
                    val avW = (contW - padH * 2).coerceAtLeast(1)
                    val avH = (contH - peekH - gapH - toolbarH - padH).coerceAtLeast((80 * dp).toInt())
                    val hFromW = (avW.toLong() * surfH / surfW).toInt()
                    val finalW: Int; val finalH: Int
                    if (hFromW <= avH) { finalW = avW; finalH = hFromW }
                    else               { finalH = avH; finalW = (avH.toLong() * surfW / surfH).toInt() }
                    val lp = b.previewCard.layoutParams as FrameLayout.LayoutParams
                    lp.width = finalW; lp.height = finalH
                    lp.gravity   = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.TOP
                    lp.topMargin = toolbarH + padH
                    b.previewCard.layoutParams = lp
                }
            })
    }

    private fun getWinW() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        windowManager.currentWindowMetrics.bounds.width()
    else { @Suppress("DEPRECATION") val d = android.util.DisplayMetrics(); @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(d); d.widthPixels }

    private fun getWinH() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        windowManager.currentWindowMetrics.bounds.height()
    else { @Suppress("DEPRECATION") val d = android.util.DisplayMetrics(); @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(d); d.heightPixels }

    private fun animateEntrance() {
        b.previewCard.alpha = 0f; b.previewCard.translationY = 40f
        b.previewCard.animate().alpha(1f).translationY(0f).setDuration(420)
            .setStartDelay(60).setInterpolator(DecelerateInterpolator(2.2f)).start()
        b.controlsRoot.alpha = 0f
        b.controlsRoot.animate().alpha(1f).setDuration(360).setStartDelay(120)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    private fun animateBtnPress(v: View, action: () -> Unit) {
        v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(70).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(OvershootInterpolator(2.5f)).withEndAction { action() }.start()
        }.start()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { save(); @Suppress("DEPRECATION") super.onBackPressed() }

    // ── Load ──────────────────────────────────────────────────────────────────
    private fun loadData() {
        wallpaperMode = WallpaperPrefs.getWallpaperMode(this)
        editSlot = if (wallpaperMode == WallpaperPrefs.MODE_DAY_NIGHT) WallpaperPrefs.EDIT_DAY
                   else WallpaperPrefs.EDIT_NONE

        val p   = WallpaperPrefs.loadSlot(this, editSlot)
        val bgF = WallpaperPrefs.getBgFileForSlot(this, editSlot)
        val fgF = WallpaperPrefs.getFgFileForSlot(this, editSlot)
        val ftF = File(filesDir, WallpaperPrefs.FILE_FONT)
        val bgBmp = if (bgF.exists()) BitmapFactory.decodeFile(bgF.absolutePath) else null
        val fgBmp = if (fgF.exists()) BitmapFactory.decodeFile(fgF.absolutePath) else null
        val tf    = if (ftF.exists()) try { Typeface.createFromFile(ftF) } catch (_: Exception) { null } else null
        loadedBgBmp = bgBmp
        clockColor = if (p.color != WallpaperPrefs.NO_COLOR) p.color else Color.WHITE
        with(b.editorView) {
            clockX=p.clockX; clockY=p.clockY; clockSz=p.clockSz; clockRot=p.clockRot
            dateX=p.dateX;   dateY=p.dateY;   dateSz=p.dateSz;   dateRot=p.dateRot
            subjX=p.subjX;   subjY=p.subjY;   subjSc=p.subjSc;   subjRot=p.subjRot
            bgRot=p.bgRot;   use24hr=p.use24; showSeconds=p.secs
            bgDim=p.bgDim;   clockDim=p.clkDim; subjDim=p.subjDim
            this.clockColor = this@EditorActivity.clockColor
            setData(bgBmp, fgBmp, tf)
        }
    }

    // ── Pills ─────────────────────────────────────────────────────────────────
    private fun setupPills() {
        b.btnPillBg.setOnClickListener      { switchPanel(Panel.BACKGROUND) }
        b.btnPillTime.setOnClickListener    { switchPanel(Panel.TIME) }
        b.btnPillSubject.setOnClickListener { switchPanel(Panel.SUBJECT) }
        b.btnChangeBg.setOnClickListener      { reqPerm(true) }
        b.btnChangeSubject.setOnClickListener { reqPerm(false) }
        b.btnPickFont.setOnClickListener      { pickFont.launch("*/*") }
        showPanel(Panel.TIME)
    }

    private fun switchPanel(p: Panel) {
        if (p == panel) return
        val out = panelView(panel)
        out.animate().alpha(0f).translationX(-24f).setDuration(140)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { out.visibility = View.GONE; out.translationX = 0f; showPanel(p) }.start()
    }

    private fun showPanel(p: Panel) {
        panel = p
        val inP = panelView(p)
        inP.alpha = 0f; inP.translationX = 24f; inP.visibility = View.VISIBLE
        inP.animate().alpha(1f).translationX(0f).setDuration(200)
            .setInterpolator(DecelerateInterpolator(1.5f)).start()
        b.panelBg.visibility      = if (p == Panel.BACKGROUND) View.VISIBLE else View.GONE
        b.panelTime.visibility    = if (p == Panel.TIME)       View.VISIBLE else View.GONE
        b.panelSubject.visibility = if (p == Panel.SUBJECT)    View.VISIBLE else View.GONE
        pill(b.btnPillBg,      p == Panel.BACKGROUND)
        pill(b.btnPillTime,    p == Panel.TIME)
        pill(b.btnPillSubject, p == Panel.SUBJECT)
    }

    private fun panelView(p: Panel): View = when (p) {
        Panel.BACKGROUND -> b.panelBg; Panel.TIME -> b.panelTime; Panel.SUBJECT -> b.panelSubject
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
        // Set initial values before wiring listeners to avoid spurious callbacks
        b.switchAutoHide.isChecked = WallpaperPrefs.getAutoHide(this)

        b.sliderBgRot.addOnChangeListener  { _, v, _ -> b.tvBgRotVal.text  = "${v.toInt()}°"; b.editorView.bgRot    = v }
        b.sliderBgDim.addOnChangeListener  { _, v, _ -> b.tvBgDimVal.text  = "${v.toInt()}%"; b.editorView.bgDim    = v / 100f }
        b.sliderClkRot.addOnChangeListener  { _, v, _ -> b.tvClkRotVal.text  = "${v.toInt()}°"; b.editorView.clockRot  = v }
        b.sliderDateRot.addOnChangeListener { _, v, _ -> b.tvDateRotVal.text = "${v.toInt()}°"; b.editorView.dateRot   = v }
        b.sliderTimeDim.addOnChangeListener { _, v, _ -> b.tvTimeDimVal.text = "${v.toInt()}%"; b.editorView.clockDim  = v / 100f }
        b.sliderSubjRot.addOnChangeListener { _, v, _ -> b.tvSubjRotVal.text = "${v.toInt()}°"; b.editorView.subjRot   = v }
        b.sliderSubjDim.addOnChangeListener { _, v, _ -> b.tvSubjDimVal.text = "${v.toInt()}%"; b.editorView.subjDim   = v / 100f }
        b.sliderBgSat.addOnChangeListener   { _, v, _ -> b.tvBgSatVal.text   = "${v.toInt()}%"; b.editorView.bgSat     = v / 100f }
        b.sliderSubjSat.addOnChangeListener { _, v, _ -> b.tvSubjSatVal.text = "${v.toInt()}%"; b.editorView.subjSat   = v / 100f }
        b.switch24hr.setOnCheckedChangeListener    { _, c -> b.editorView.use24hr     = c }
        b.switchSeconds.setOnCheckedChangeListener { _, c -> b.editorView.showSeconds = c }
        b.switchAutoHide.setOnCheckedChangeListener { _, v -> WallpaperPrefs.setAutoHide(this, v) }

        b.chipPickColor.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                b.colorPicker.visibility = View.VISIBLE
                b.colorPicker.animate().alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator(1.5f)).start()
            } else {
                b.colorPicker.animate().alpha(0f).setDuration(160)
                    .withEndAction { b.colorPicker.visibility = View.GONE }.start()
            }
        }
        b.colorPicker.onColorChanged = { c -> clockColor = c; b.editorView.clockColor = c; updateSwatch(c) }

        val ftF = File(filesDir, WallpaperPrefs.FILE_FONT)
        b.tvFontStatus.text = if (ftF.exists()) ftF.name else "System default"

        b.colorPicker.visibility = View.GONE; b.colorPicker.alpha = 0f
        b.chipPickColor.isChecked = false
        syncSlidersFromEditor()
    }

    private fun updateSwatch(c: Int) {
        val dp = resources.displayMetrics.density
        val gd = GradientDrawable(); gd.setColor(c); gd.cornerRadius=8f*dp
        gd.setStroke((1.5f*dp).toInt(), 0x33000000); b.colorSwatch.background = gd
        b.colorSwatch.animate().scaleX(1.18f).scaleY(1.18f).setDuration(80).withEndAction {
            b.colorSwatch.animate().scaleX(1f).scaleY(1f).setDuration(180)
                .setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
    }

    // ── Font ─────────────────────────────────────────────────────────────────
    private fun onFont(uri: Uri) {
        val name = displayName(uri) ?: uri.lastPathSegment ?: ""
        if (!name.endsWith(".ttf", ignoreCase = true)) { toast("Please select a .ttf file."); return }
        contentResolver.openInputStream(uri)?.use { inp ->
            File(filesDir, WallpaperPrefs.FILE_FONT).outputStream().use { inp.copyTo(it) }
        }
        WallpaperPrefs.setHasFont(this, true)
        val tf = try { Typeface.createFromFile(File(filesDir, WallpaperPrefs.FILE_FONT)) } catch (_: Exception) { null }
        b.editorView.setFontOnly(tf); b.tvFontStatus.text = name; toast("Font applied")
    }

    // ── Image replacement ─────────────────────────────────────────────────────
    private fun reqPerm(bg: Boolean) {
        val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
                else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED) launch(bg)
        else perm.launch(p)
    }

    private fun launch(bg: Boolean) { if (bg) pickBg.launch("image/*") else pickFg.launch("image/*") }

    private fun replaceImg(uri: Uri, isBg: Boolean) {
        val targetFile = if (isBg) WallpaperPrefs.getBgFileForSlot(this, editSlot)
                         else      WallpaperPrefs.getFgFileForSlot(this, editSlot)
        val isFirstTime = !targetFile.exists()
        try {
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: return toast("Failed to read image.")
            val r  = minOf(2048f / bmp.width, 2048f / bmp.height)
            val sc = if (r < 1f) Bitmap.createScaledBitmap(bmp,(bmp.width*r).toInt(),(bmp.height*r).toInt(),true) else bmp
            FileOutputStream(targetFile).use { sc.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (isFirstTime) WallpaperPrefs.saveToSlot(this, editSlot,
                WallpaperPrefs.loadSlot(this, editSlot).copy(
                    clockX=WallpaperPrefs.DEF_CLK_X, clockY=WallpaperPrefs.DEF_CLK_Y,
                    subjX=WallpaperPrefs.DEF_SUBJ_X, subjY=WallpaperPrefs.DEF_SUBJ_Y))
            if (isBg) loadedBgBmp = sc
            b.previewCard.animate().alpha(0.3f).setDuration(100).withEndAction {
                loadSlotIntoEditor(editSlot)
                b.previewCard.animate().alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
            }.start()
        } catch (e: Exception) { toast("Error: ${e.message}") }
    }

    // ── Save & set wallpaper ──────────────────────────────────────────────────
    private fun save() {
        saveCurrentSlot()
        WallpaperPrefs.setWallpaperMode(this, wallpaperMode)
    }

    private fun saveAndLaunch() {
        save()
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@EditorActivity, DepthWallpaperService::class.java))
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
}
