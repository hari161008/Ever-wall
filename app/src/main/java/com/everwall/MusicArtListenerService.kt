package com.everwall

import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.FileOutputStream

class MusicArtListenerService : NotificationListenerService() {

    private var activeMediaKey: String? = null

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!WallpaperPrefs.getMusicArtEnabled(applicationContext)) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val template = extras.getString(Notification.EXTRA_TEMPLATE)
        val isMedia = (template != null && template.contains("MediaStyle")) ||
                extras.get("android.mediaSession") != null

        if (!isMedia) return

        val artBitmap = extractAlbumArt(notification) ?: return

        try {
            val artFile = WallpaperPrefs.getMusicArtFile(applicationContext)
            FileOutputStream(artFile).use { out ->
                artBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            activeMediaKey = sbn.key
            LocalBroadcastManager.getInstance(applicationContext)
                .sendBroadcast(Intent(WallpaperPrefs.ACTION_MUSIC_ART_CHANGED))
        } catch (_: Exception) {}
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.key != activeMediaKey) return
        activeMediaKey = null
        // Delete art file so wallpaper service reverts to original
        WallpaperPrefs.getMusicArtFile(applicationContext).delete()
        LocalBroadcastManager.getInstance(applicationContext)
            .sendBroadcast(Intent(WallpaperPrefs.ACTION_MUSIC_ART_CHANGED))
    }

    private fun extractAlbumArt(notification: Notification): Bitmap? {
        val extras = notification.extras

        val picture = extras.get(NotificationCompat.EXTRA_PICTURE)
        if (picture is Bitmap) return picture

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val largeIconObj = extras.get("android.largeIcon")
            if (largeIconObj is Icon) {
                val drawable = largeIconObj.loadDrawable(applicationContext)
                if (drawable is BitmapDrawable) return drawable.bitmap
            }
        }

        @Suppress("DEPRECATION")
        return notification.largeIcon
    }
}
