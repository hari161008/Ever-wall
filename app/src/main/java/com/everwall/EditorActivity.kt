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
import android.util.TypedValue
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

class EditorActivity : AppCompatActivity() {

    private lateinit var b: ActivityEditorBinding
    private enum class Panel { BACKGROUND, TIME, SUBJECT }
    private var panel = Panel.TIME
    private var clockColor = Color.WHITE
    private var loadedBgBmp: Bitmap? = null

    private val pickBg   = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> replaceImg(u, WallpaperPrefs.FILE_ORIGINAL) } }
    private val pickFg   = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> replaceImg(u, WallpaperPrefs.FILE_FOREGROUND) } }
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

        // Use the hidden toolbar only for system back-stack compatibility
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        setupBottomSheet()
        applyExactSurfaceAspectRatio()
        loadData()
        setupPills()
        setupPanels()

        b.btnSetWallpaper.setOnClickListener { animateBtnPress(b.btnSetWallpaper) { saveAndLaunch() } }
        b.btnReset.setOnClickListener        { showResetDialog() }

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
        val bsb = BottomSheetBehavior.from(b.controlsRoot)
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
                    b.previewContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val dp       = resources.displayMetrics.density
                    val peekH    = (PEEK_HEIGHT_DP * dp).toInt()
                    val gapH     = (BOTTOM_GAP_DP  * dp).toInt()
                    val toolbarH = b.toolbarContainer.height.takeIf { it > 0 }
                        ?: TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 56f, resources.displayMetrics).toInt()
                    val padH     = (14 * dp).toInt()
                    val avW = (b.previewContainer.width - padH * 2).coerceAtLeast(1)
                    val avH = (b.previewContainer.height - peekH - gapH - toolbarH - padH).coerceAtLeast(1)
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

    // ── Load ─────────────────────────────────────────────────────────────────
    private fun loadData() {
        val bgF = File(filesDir, WallpaperPrefs.FILE_ORIGINAL)
        val fgF = File(filesDir, WallpaperPrefs.FILE_FOREGROUND)
        val ftF = File(filesDir, WallpaperPrefs.FILE_FONT)
        val bgBmp = if (bgF.exists()) BitmapFactory.decodeFile(bgF.absolutePath) else null
        val fgBmp = if (fgF.exists()) BitmapFactory.decodeFile(fgF.absolutePath) else null
        val tf    = if (ftF.exists()) try { Typeface.createFromFile(ftF) } catch (_: Exception) { null } else null
        loadedBgBmp = bgBmp
        val saved = WallpaperPrefs.getColor(this)
        clockColor = if (saved != WallpaperPrefs.NO_COLOR) saved else Color.WHITE
        with(b.editorView) {
            clockX=WallpaperPrefs.getClkX(this@EditorActivity); clockY=WallpaperPrefs.getClkY(this@EditorActivity)
            clockSz=WallpaperPrefs.getClkSz(this@EditorActivity); clockRot=WallpaperPrefs.getClkRot(this@EditorActivity)
            dateX=WallpaperPrefs.getDateX(this@EditorActivity); dateY=WallpaperPrefs.getDateY(this@EditorActivity)
            dateSz=WallpaperPrefs.getDateSz(this@EditorActivity); dateRot=WallpaperPrefs.getDateRot(this@EditorActivity)
            subjX=WallpaperPrefs.getSubjX(this@EditorActivity); subjY=WallpaperPrefs.getSubjY(this@EditorActivity)
            subjSc=WallpaperPrefs.getSubjSc(this@EditorActivity); subjRot=WallpaperPrefs.getSubjRot(this@EditorActivity)
            bgRot=WallpaperPrefs.getBgRot(this@EditorActivity)
            use24hr=WallpaperPrefs.getUse24(this@EditorActivity); showSeconds=WallpaperPrefs.getSecs(this@EditorActivity)
            bgDim=WallpaperPrefs.getBgDim(this@EditorActivity); clockDim=WallpaperPrefs.getClkDim(this@EditorActivity)
            subjDim=WallpaperPrefs.getSubjDim(this@EditorActivity)
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
        b.sliderBgRot.value = WallpaperPrefs.getBgRot(this).coerceIn(-180f,180f)
        b.tvBgRotVal.text   = "${b.sliderBgRot.value.toInt()}°"
        b.sliderBgRot.addOnChangeListener { _, v, _ -> b.tvBgRotVal.text="${v.toInt()}°"; b.editorView.bgRot=v }

        b.sliderBgDim.value = (WallpaperPrefs.getBgDim(this)*100f).coerceIn(0f,100f)
        b.tvBgDimVal.text   = "${b.sliderBgDim.value.toInt()}%"
        b.sliderBgDim.addOnChangeListener { _, v, _ -> b.tvBgDimVal.text="${v.toInt()}%"; b.editorView.bgDim=v/100f }

        b.switch24hr.isChecked    = WallpaperPrefs.getUse24(this)
        b.switchSeconds.isChecked = WallpaperPrefs.getSecs(this)
        b.switch24hr.setOnCheckedChangeListener    { _, c -> b.editorView.use24hr     = c }
        b.switchSeconds.setOnCheckedChangeListener { _, c -> b.editorView.showSeconds = c }

        b.sliderClkRot.value = WallpaperPrefs.getClkRot(this).coerceIn(-180f,180f)
        b.tvClkRotVal.text   = "${b.sliderClkRot.value.toInt()}°"
        b.sliderClkRot.addOnChangeListener { _, v, _ -> b.tvClkRotVal.text="${v.toInt()}°"; b.editorView.clockRot=v }

        b.sliderDateRot.value = WallpaperPrefs.getDateRot(this).coerceIn(-180f,180f)
        b.tvDateRotVal.text   = "${b.sliderDateRot.value.toInt()}°"
        b.sliderDateRot.addOnChangeListener { _, v, _ -> b.tvDateRotVal.text="${v.toInt()}°"; b.editorView.dateRot=v }

        b.sliderTimeDim.value = (WallpaperPrefs.getClkDim(this)*100f).coerceIn(0f,100f)
        b.tvTimeDimVal.text   = "${b.sliderTimeDim.value.toInt()}%"
        b.sliderTimeDim.addOnChangeListener { _, v, _ -> b.tvTimeDimVal.text="${v.toInt()}%"; b.editorView.clockDim=v/100f }

        val ftF = File(filesDir, WallpaperPrefs.FILE_FONT)
        b.tvFontStatus.text = if (ftF.exists()) ftF.name else "System default"

        updateSwatch(clockColor)
        b.colorPicker.color   = clockColor
        b.colorPicker.visibility = View.GONE; b.colorPicker.alpha = 0f
        b.chipPickColor.isChecked = false
        b.chipPickColor.setOnCheckedChangeListener { _, checked ->
            if (checked) { b.colorPicker.visibility=View.VISIBLE; b.colorPicker.animate().alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator(1.5f)).start() }
            else { b.colorPicker.animate().alpha(0f).setDuration(160).withEndAction { b.colorPicker.visibility=View.GONE }.start() }
        }
        b.colorPicker.onColorChanged = { c -> clockColor=c; b.editorView.clockColor=c; updateSwatch(c) }

        b.sliderSubjRot.value = WallpaperPrefs.getSubjRot(this).coerceIn(-180f,180f)
        b.tvSubjRotVal.text   = "${b.sliderSubjRot.value.toInt()}°"
        b.sliderSubjRot.addOnChangeListener { _, v, _ -> b.tvSubjRotVal.text="${v.toInt()}°"; b.editorView.subjRot=v }

        b.sliderSubjDim.value = (WallpaperPrefs.getSubjDim(this)*100f).coerceIn(0f,100f)
        b.tvSubjDimVal.text   = "${b.sliderSubjDim.value.toInt()}%"
        b.sliderSubjDim.addOnChangeListener { _, v, _ -> b.tvSubjDimVal.text="${v.toInt()}%"; b.editorView.subjDim=v/100f }
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

    private fun replaceImg(uri: Uri, file: String) {
        // Only reset positions on first-ever pick; preserve layout on replacement
        val isFirstTime = !File(filesDir, file).exists()
        try {
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: return toast("Failed to read image.")
            val r  = minOf(2048f / bmp.width, 2048f / bmp.height)
            val sc = if (r < 1f) Bitmap.createScaledBitmap(bmp,(bmp.width*r).toInt(),(bmp.height*r).toInt(),true) else bmp
            FileOutputStream(File(filesDir, file)).use { sc.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (isFirstTime) WallpaperPrefs.clearPositions(this)
            if (file == WallpaperPrefs.FILE_ORIGINAL) loadedBgBmp = sc
            b.previewCard.animate().alpha(0.3f).setDuration(100).withEndAction {
                loadData(); setupPanels()
                b.previewCard.animate().alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
            }.start()
        } catch (e: Exception) { toast("Error: ${e.message}") }
    }

    // ── Save & set wallpaper ──────────────────────────────────────────────────
    private fun save() {
        with(b.editorView) {
            WallpaperPrefs.saveAll(this@EditorActivity,
                clockX,clockY,clockSz,clockRot,dateX,dateY,dateSz,dateRot,
                subjX,subjY,subjSc,subjRot,bgRot,clockColor,
                b.switch24hr.isChecked,b.switchSeconds.isChecked,
                bgDim,clockDim,subjDim)
        }
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
