package com.example.admuteforspotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdMuteService : NotificationListenerService() {

    private lateinit var audioManager: AudioManager
    private var spotifyMediaController: MediaController? = null
    private var isMuted = false
    private var lastKnownVolume = 10
    private val spotifyPackageName = "com.spotify.music"

    companion object {
        const val CHANNEL_ID = "AdMuteServiceChannel"
    }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            if (metadata == null) return
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            Log.d("AdMuteService", "Track changed: $title - $artist")
            if (isAd(title, artist)) {
                muteVolume()
            } else if (title.isNotBlank() && artist.isNotBlank()) {
                unmuteVolume()
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn?.packageName != spotifyPackageName) {
            return
        }
        val token = sbn.notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        if (token != null && spotifyMediaController?.sessionToken != token) {
            spotifyMediaController?.unregisterCallback(mediaControllerCallback)
            spotifyMediaController = MediaController(applicationContext, token)
            spotifyMediaController?.registerCallback(mediaControllerCallback)
            Log.d("AdMuteService", "Attached to new Spotify session via notification.")
            mediaControllerCallback.onMetadataChanged(spotifyMediaController?.metadata)
        }
    }

    private fun isAd(title: String, artist: String): Boolean {
        return artist.equals("Advertisement", ignoreCase = true) || title.equals("Spotify", ignoreCase = true)
    }

    private fun muteVolume() {
        if (!isMuted) {
            try {
                lastKnownVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                isMuted = true
                Log.d("AdMuteService", "AD DETECTED: Muting volume.")
                saveMuteEvent()
            } catch (e: Exception) {
                Log.e("AdMuteService", "Failed to mute", e)
            }
        }
    }

    private fun saveMuteEvent() {
        val prefs = getSharedPreferences("AdMutePrefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val currentCount = prefs.getInt("MuteCount", 0)
        editor.putInt("MuteCount", currentCount + 1)
        val history = prefs.getStringSet("MuteHistory", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        val timestamp = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
        history.add("$timestamp: Ad muted")
        editor.putStringSet("MuteHistory", history)
        editor.apply()
    }

    private fun unmuteVolume() {
        if (isMuted) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, lastKnownVolume, 0)
                isMuted = false
                Log.d("AdMuteService", "SONG DETECTED: Restoring volume.")
            } catch (e: Exception) {
                Log.e("AdMuteService", "Failed to unmute", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AdMute Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = notificationBuilder
            .setContentTitle("AdMute is Active")
            .setContentText("Listening for Spotify ads.")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .build()
        startForeground(1, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unmuteVolume()
        spotifyMediaController?.unregisterCallback(mediaControllerCallback)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }
}