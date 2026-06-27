package com.everwall

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

object UpdateChecker {

    private const val GITHUB_API_URL =
        "https://api.github.com/repos/hari161008/Ever-wall/releases/latest"

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val apkUrl: String,
        val apkFileName: String
    )

    /** Returns ReleaseInfo if a newer version exists, null otherwise. */
    suspend fun checkForUpdate(context: Context): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(GITHUB_API_URL).openConnection()
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val json = conn.getInputStream().bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val tagName = obj.getString("tag_name")
            val remoteVersion = tagName.trimStart('v', 'V')

            val assets = obj.getJSONArray("assets")
            var apkUrl = ""
            var apkFileName = "everwall-$remoteVersion.apk"
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.getString("browser_download_url")
                    apkFileName = name
                    break
                }
            }

            if (apkUrl.isEmpty()) return@withContext null

            val currentVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: APP_VERSION
            } catch (_: Exception) { APP_VERSION }

            if (!isNewerVersion(remoteVersion, currentVersion)) return@withContext null

            ReleaseInfo(tagName, remoteVersion, apkUrl, apkFileName)
        } catch (_: Exception) {
            null
        }
    }

    /** Returns the downloaded APK file if it already exists in Downloads. */
    fun getDownloadedApk(apkFileName: String): File? {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            apkFileName
        )
        return if (file.exists() && file.length() > 0) file else null
    }

    /** Enqueues the APK download via DownloadManager and calls onComplete when done. */
    fun downloadApk(
        context: Context,
        apkUrl: String,
        apkFileName: String,
        onProgress: (String) -> Unit,
        onComplete: (File?) -> Unit
    ) {
        val dest = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            apkFileName
        )
        if (dest.exists()) dest.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Ever Wall Update")
            .setDescription("Downloading v$apkFileName...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, apkFileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)
        onProgress("Downloading update...")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = dm.query(query)
                    var success = false
                    if (cursor != null && cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (statusIdx >= 0 && cursor.getInt(statusIdx) == DownloadManager.STATUS_SUCCESSFUL) {
                            success = true
                        }
                        cursor.close()
                    }
                    onComplete(if (success) dest else null)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    /** Fires the system installer for the given APK file. */
    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Returns true if remote version string is strictly newer than current. */
    fun isNewerVersion(remote: String, current: String): Boolean {
        return try {
            val r = remote.split(".").map { it.trim().toIntOrNull() ?: 0 }
            val c = current.split(".").map { it.trim().toIntOrNull() ?: 0 }
            val maxLen = maxOf(r.size, c.size)
            for (i in 0 until maxLen) {
                val rv = r.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (rv > cv) return true
                if (rv < cv) return false
            }
            false
        } catch (_: Exception) { false }
    }

    /** Single source of truth for current app version. */
    const val APP_VERSION = "2.0.0"
}
