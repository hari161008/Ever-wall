package com.everwall

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

class MusicArtListenerService : NotificationListenerService() {

    private var activeMediaKey: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)

    // Registered MediaController callbacks for instant metadata detection
    private val registeredControllers = mutableListOf<Pair<MediaController, MediaController.Callback>>()

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        unregisterAllControllerCallbacks()
        controllers?.forEach { registerControllerCallback(it) }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        registerSessionListener()
    }

    override fun onListenerDisconnected() {
        unregisterAllControllerCallbacks()
        super.onListenerDisconnected()
    }

    private fun registerSessionListener() {
        try {
            val mgr = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
            val cn = ComponentName(this, MusicArtListenerService::class.java)
            mgr.addOnActiveSessionsChangedListener(sessionListener, cn)
            // Register callbacks for currently active sessions immediately
            mgr.getActiveSessions(cn).forEach { registerControllerCallback(it) }
        } catch (_: Exception) {}
    }

    private fun registerControllerCallback(controller: MediaController) {
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                metadata ?: return
                if (!WallpaperPrefs.getMusicArtEnabled(applicationContext)) return
                handler.removeCallbacksAndMessages(null)
                val gen = generation.incrementAndGet()
                // Fetch immediately on metadata change — no notification delay
                fetchAndSave(metadata, gen)
                // Retry for quality upgrade
                val retryDelays = longArrayOf(400L, 1000L)
                for (delay in retryDelays) {
                    handler.postDelayed({
                        if (generation.get() != gen) return@postDelayed
                        val upgraded = extractFromMediaSession() ?: return@postDelayed
                        val existing = WallpaperPrefs.getMusicArtFile(applicationContext)
                        if (existing.exists()) {
                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(existing.absolutePath, opts)
                            if (upgraded.width * upgraded.height <= opts.outWidth * opts.outHeight) return@postDelayed
                        }
                        saveBitmapAndBroadcast(upgraded)
                    }, delay)
                }
            }
        }
        controller.registerCallback(callback, handler)
        registeredControllers.add(Pair(controller, callback))
    }

    private fun unregisterAllControllerCallbacks() {
        for ((controller, callback) in registeredControllers) {
            try { controller.unregisterCallback(callback) } catch (_: Exception) {}
        }
        registeredControllers.clear()
    }

    private fun fetchAndSave(metadata: MediaMetadata, gen: Int) {
        val bitmap = extractFromMetadata(metadata)
            ?: extractFromMediaSession()
            ?: return
        if (generation.get() != gen) return
        saveBitmapAndBroadcast(bitmap)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!WallpaperPrefs.getMusicArtEnabled(applicationContext)) return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val template = extras.getString(Notification.EXTRA_TEMPLATE)
        val isMedia = (template != null && template.contains("MediaStyle")) ||
                extras.get("android.mediaSession") != null
        if (!isMedia) return
        activeMediaKey = sbn.key
        // Notification path is fallback — MediaController.Callback fires first for track changes
        val bitmap = extractFromMediaSession() ?: extractFromNotification(notification) ?: return
        saveBitmapAndBroadcast(bitmap)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.key != activeMediaKey) return
        handler.removeCallbacksAndMessages(null)
        generation.incrementAndGet()
        activeMediaKey = null
        WallpaperPrefs.getMusicArtFile(applicationContext).delete()
        LocalBroadcastManager.getInstance(applicationContext)
            .sendBroadcast(Intent(WallpaperPrefs.ACTION_MUSIC_ART_CHANGED))
    }

    private fun saveBitmapAndBroadcast(bitmap: Bitmap) {
        try {
            FileOutputStream(WallpaperPrefs.getMusicArtFile(applicationContext)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            LocalBroadcastManager.getInstance(applicationContext)
                .sendBroadcast(Intent(WallpaperPrefs.ACTION_MUSIC_ART_CHANGED))
        } catch (_: Exception) {}
    }

    private fun extractFromMetadata(metadata: MediaMetadata): Bitmap? {
        val direct = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        if (direct != null) return direct
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
        return if (uri != null) loadBitmapFromUri(uri) else null
    }

    private fun extractFromMediaSession(): Bitmap? {
        return try {
            val mgr = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
            val controllers = mgr.getActiveSessions(ComponentName(this, MusicArtListenerService::class.java))
            for (controller in controllers) {
                val metadata = controller.metadata ?: continue
                val bmp = extractFromMetadata(metadata)
                if (bmp != null) return bmp
            }
            null
        } catch (_: Exception) { null }
    }

    private fun loadBitmapFromUri(uriStr: String): Bitmap? {
        return try {
            contentResolver.openInputStream(Uri.parse(uriStr))?.use { stream ->
                BitmapFactory.decodeStream(stream, null,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            }
        } catch (_: Exception) { null }
    }

    private fun extractFromNotification(notification: Notification): Bitmap? {
        val extras = notification.extras
        val picture = extras.get(NotificationCompat.EXTRA_PICTURE)
        if (picture is Bitmap) return picture
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val obj = extras.get("android.largeIcon")
            if (obj is Icon) {
                val d = obj.loadDrawable(applicationContext)
                if (d is BitmapDrawable) return d.bitmap
            }
        }
        @Suppress("DEPRECATION")
        return notification.largeIcon
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterAllControllerCallbacks()
        try {
            val mgr = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager
            mgr?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
