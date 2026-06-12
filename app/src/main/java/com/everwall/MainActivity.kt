package com.everwall

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.everwall.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var hasBg = false; private var hasSubj = false
    private enum class Target { NONE, BG, SUBJ, FONT }
    private var target = Target.NONE

    private val pickImg  = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> onImg(u) } }
    private val pickFont = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let { u -> onFont(u) } }
    private val perm     = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) launch() else toast("Permission required.") }

    override fun onCreate(s: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(s)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        val wic = WindowCompat.getInsetsController(window, b.root)
        wic.isAppearanceLightStatusBars = false // hero is dark → light icons

        // Apply dynamic system color gradient to hero
        applyHeroGradient()

        // Insets: push hero text below status bar
        ViewCompat.setOnApplyWindowInsetsListener(b.heroSection) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(0, top, 0, 0)
            insets
        }
        // Insets: pad scroll below nav bar
        ViewCompat.setOnApplyWindowInsetsListener(b.nestedScrollView) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(0, 0, 0, nav)
            insets
        }

        // Status bar color transitions as user scrolls past the hero
        b.nestedScrollView.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                val heroH = b.heroSection.height.takeIf { it > 0 } ?: 1
                val pastHero = scrollY > heroH * 0.55f
                if (pastHero) {
                    window.statusBarColor = MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorSurface, Color.WHITE)
                    wic.isAppearanceLightStatusBars = !isNightMode()
                } else {
                    window.statusBarColor = Color.TRANSPARENT
                    wic.isAppearanceLightStatusBars = false
                }
            })

        b.cardBackground.setOnClickListener {
            animateCardPress(b.cardBackground) { target = Target.BG; reqPerm() }
        }
        b.cardFont.setOnClickListener {
            animateCardPress(b.cardFont) { target = Target.FONT; pickFont.launch("*/*") }
        }
        b.cardSubject.setOnClickListener {
            animateCardPress(b.cardSubject) { target = Target.SUBJ; reqPerm() }
        }
        b.btnContinue.setOnClickListener {
            animateBtnPress(b.btnContinue) {
                startActivity(Intent(this, EditorActivity::class.java))
                overridePendingTransition(R.anim.slide_up_enter, R.anim.fade_out)
            }
        }

        restore()
        animateEntranceCards()
    }

    // ── Dynamic hero gradient using Material You / system color ───────────────
    private fun applyHeroGradient() {
        val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#6750A4"))
        val dark1 = darken(primary, 0.18f)
        val dark2 = darken(primary, 0.55f)
        val gd = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(dark1, dark2, primary))
        b.heroSection.background = gd
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = (Color.red(color)   * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val bl = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), r, g, bl)
    }

    private fun isNightMode() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // ── Entrance animations ───────────────────────────────────────────────────
    private fun animateEntranceCards() {
        b.heroSection.alpha = 0f
        b.heroSection.animate().alpha(1f).setDuration(360).setStartDelay(40).start()
        listOf<View>(b.cardBackground, b.cardFont, b.cardSubject, b.btnContinue)
            .forEachIndexed { i, v ->
                v.alpha = 0f; v.translationY = 50f
                v.animate().alpha(1f).translationY(0f)
                    .setStartDelay((120 + i * 90).toLong()).setDuration(440)
                    .setInterpolator(DecelerateInterpolator(2f)).start()
            }
    }

    private fun animateCardPress(v: View, action: () -> Unit) {
        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(180)
                .setInterpolator(OvershootInterpolator(2f)).withEndAction { action() }.start()
        }.start()
    }

    private fun animateBtnPress(v: View, action: () -> Unit) {
        v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(OvershootInterpolator(2.5f)).withEndAction { action() }.start()
        }.start()
    }

    // ── Restore state ─────────────────────────────────────────────────────────
    private fun restore() {
        hasBg   = File(filesDir, WallpaperPrefs.FILE_ORIGINAL).exists()
        hasSubj = File(filesDir, WallpaperPrefs.FILE_FOREGROUND).exists()
        val hasFt = File(filesDir, WallpaperPrefs.FILE_FONT).exists()
        if (hasBg) {
            BitmapFactory.decodeFile(File(filesDir, WallpaperPrefs.FILE_ORIGINAL).absolutePath)?.let {
                b.ivBgThumb.setImageBitmap(it); revealThumb(b.ivBgThumb, b.bgPlaceholder)
            }
            ok(b.tvBgStatus, "Background image ready")
        }
        if (hasSubj) {
            BitmapFactory.decodeFile(File(filesDir, WallpaperPrefs.FILE_FOREGROUND).absolutePath)?.let {
                b.ivSubjectThumb.setImageBitmap(it); revealThumb(b.ivSubjectThumb, b.subjectPlaceholder)
            }
            ok(b.tvSubjectStatus, "Cutout subject ready")
        }
        if (hasFt) {
            try { b.tvFontPreview.typeface = Typeface.createFromFile(File(filesDir, WallpaperPrefs.FILE_FONT)) } catch (_: Exception) {}
            b.tvFontPreview.visibility = View.VISIBLE
            b.ivFontIcon.visibility    = View.GONE
            ok(b.tvFontStatus, "Custom font applied")
        }
        updateBtn()
    }

    private fun revealThumb(thumb: View, placeholder: View) {
        thumb.alpha = 0f; thumb.visibility = View.VISIBLE
        placeholder.animate().alpha(0f).setDuration(200).withEndAction { placeholder.visibility = View.GONE }.start()
        thumb.animate().alpha(1f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
    }

    // ── Pick images ───────────────────────────────────────────────────────────
    private fun reqPerm() {
        val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) android.Manifest.permission.READ_MEDIA_IMAGES
                else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED) launch()
        else perm.launch(p)
    }

    private fun launch() { if (target == Target.BG || target == Target.SUBJ) pickImg.launch("image/*") }

    private fun onImg(uri: Uri) {
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: return toast("Failed to read image.")
        val fn  = if (target == Target.BG) WallpaperPrefs.FILE_ORIGINAL else WallpaperPrefs.FILE_FOREGROUND
        val sc  = scaled(bmp, 2048)
        FileOutputStream(File(filesDir, fn)).use { sc.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Clear saved positions so editor starts fresh with new image
        WallpaperPrefs.clearPositions(this)
        if (target == Target.BG) {
            hasBg = true
            b.ivBgThumb.setImageBitmap(sc); revealThumb(b.ivBgThumb, b.bgPlaceholder)
            ok(b.tvBgStatus, "Background image ready")
        } else {
            hasSubj = true
            b.ivSubjectThumb.setImageBitmap(sc); revealThumb(b.ivSubjectThumb, b.subjectPlaceholder)
            ok(b.tvSubjectStatus, "Cutout subject ready")
        }
        updateBtn()
    }

    private fun onFont(uri: Uri) {
        val name = displayName(uri) ?: uri.lastPathSegment ?: ""
        if (!name.endsWith(".ttf", ignoreCase = true)) return toast("Please select a .ttf file.")
        contentResolver.openInputStream(uri)?.use { i ->
            File(filesDir, WallpaperPrefs.FILE_FONT).outputStream().use { i.copyTo(it) }
        }
        WallpaperPrefs.setHasFont(this, true)
        try { b.tvFontPreview.typeface = Typeface.createFromFile(File(filesDir, WallpaperPrefs.FILE_FONT)) } catch (_: Exception) {}
        b.tvFontPreview.alpha = 0f; b.tvFontPreview.visibility = View.VISIBLE
        b.tvFontPreview.animate().alpha(1f).setDuration(300).start()
        b.ivFontIcon.animate().alpha(0f).setDuration(200).withEndAction { b.ivFontIcon.visibility = View.GONE }.start()
        ok(b.tvFontStatus, "Font: $name")
    }

    private fun ok(tv: android.widget.TextView, msg: String) {
        tv.text = msg
        tv.setTextColor(getColor(android.R.color.holo_green_dark))
        tv.alpha = 0f; tv.animate().alpha(1f).setDuration(300).start()
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0) return c.getString(i)
            }
        }; return null
    }

    private fun updateBtn() {
        val en = hasBg && hasSubj
        b.btnContinue.animate().alpha(if (en) 1f else 0.35f).setDuration(300).start()
        b.btnContinue.isEnabled = en
    }

    private fun scaled(bmp: Bitmap, max: Int): Bitmap {
        if (bmp.width <= max && bmp.height <= max) return bmp
        val r = minOf(max.toFloat() / bmp.width, max.toFloat() / bmp.height)
        return Bitmap.createScaledBitmap(bmp, (bmp.width * r).toInt(), (bmp.height * r).toInt(), true)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
