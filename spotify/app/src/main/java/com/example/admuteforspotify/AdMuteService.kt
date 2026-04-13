package com.example.admuteforspotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

class AdMuteService : NotificationListenerService() {

    // ── Audio ──────────────────────────────────────────────────────────────────
    private lateinit var audioManager: AudioManager
    private var spotifyMediaController: MediaController? = null
    private var isMuted = false
    private var lastKnownVolume = 10
    private var muteStartMs: Long = 0L

    // ── Failsafe timer ─────────────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val failsafeRunnable = Runnable {
        Log.w(TAG, "Failsafe: unmuting after 4 minutes")
        unmuteVolume(fromFailsafe = true)
    }

    private val spotifyPackageName = "com.spotify.music"

    companion object {
        const val CHANNEL_ID = "AdMuteServiceChannel"
        private const val NOTIF_ID = 1
        private const val FAILSAFE_MS = 4 * 60 * 1000L  // 4 minutes
        private const val TAG = "AdMuteService"

        /** Lightweight static flag so MainActivity can read connection state. */
        @Volatile var isListenerConnected = false
    }

    // ── MediaController callback ───────────────────────────────────────────────

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            evaluateState(metadata, spotifyMediaController?.playbackState)
        }

        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
            super.onPlaybackStateChanged(state)
            evaluateState(spotifyMediaController?.metadata, state)
        }
    }

    /**
     * Central decision point — called whenever metadata OR playback state changes.
     * Keeps both signals in sync and avoids race conditions.
     */
    private fun evaluateState(
        metadata: MediaMetadata?,
        playbackState: android.media.session.PlaybackState?
    ) {
        if (metadata == null) {
            // No metadata at all — conservative: don't mute yet, wait for signal
            Log.d(TAG, "evaluateState: null metadata — holding current state")
            return
        }

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album  = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)  ?: ""
        val title  = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)  ?: ""

        Log.d(TAG, "evaluateState: title='$title'  artist='$artist'  album='$album'  " +
                "skipEnabled=${isSkipEnabled(playbackState)}")

        when {
            // ── Unmute condition: rich metadata with both artist AND album ──────
            artist.isNotBlank() && album.isNotBlank() -> {
                Log.d(TAG, "Rich metadata detected — this is a real track")
                unmuteVolume()
            }

            // ── Primary ad check: album empty AND title == "Spotify" ────────────
            isAdByMetadata(title, album) -> {
                Log.d(TAG, "Ad detected via metadata heuristic")
                muteVolume()
            }

            // ── Secondary ad check: skip-to-next action is disabled ─────────────
            // Spotify disables the skip button during ads.
            !isSkipEnabled(playbackState) -> {
                Log.d(TAG, "Ad detected via disabled skip action")
                muteVolume()
            }

            else -> {
                Log.d(TAG, "Indeterminate state — maintaining current mute state")
            }
        }
    }

    // ── Ad detection ──────────────────────────────────────────────────────────

    /**
     * PRIMARY heuristic: album metadata is strictly empty AND title equals "Spotify" (ignoring case).
     * Spotify populates album for every real track; ads leave it blank and set title to "Spotify".
     */
    private fun isAdByMetadata(title: String, album: String): Boolean {
        return album.isNullOrEmpty() && title.equals("Spotify", ignoreCase = true)
    }

    /**
     * SECONDARY heuristic: Spotify disables ACTION_SKIP_TO_NEXT during ads.
     * Returns false (i.e., ad is playing) when the skip action is absent or disabled.
     * Returns true (real track) when skip is available.
     * Returns true (no signal) when playbackState is null — we don't assume ad.
     */
    private fun isSkipEnabled(playbackState: android.media.session.PlaybackState?): Boolean {
        if (playbackState == null) return true  // no signal → don't assume ad
        // PlaybackState.ACTION_SKIP_TO_NEXT = 1L shl 9 = 512
        val skipAction = android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
        return (playbackState.actions and skipAction) != 0L
    }

    // ── NotificationListenerService hooks ─────────────────────────────────────

    override fun onListenerConnected() {
        super.onListenerConnected()
        isListenerConnected = true
        Log.d(TAG, "Listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isListenerConnected = false
        Log.d(TAG, "Listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn?.packageName != spotifyPackageName) return

        @Suppress("DEPRECATION")
        val token = sbn.notification.extras
            .getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)

        if (token != null && spotifyMediaController?.sessionToken != token) {
            spotifyMediaController?.unregisterCallback(mediaControllerCallback)
            spotifyMediaController = MediaController(applicationContext, token)
            spotifyMediaController?.registerCallback(mediaControllerCallback)
            Log.d(TAG, "Attached to new Spotify MediaSession via notification")
            // Immediately evaluate current state with both metadata and playback state
            evaluateState(
                spotifyMediaController?.metadata,
                spotifyMediaController?.playbackState
            )
        }
    }

    // ── Volume control ────────────────────────────────────────────────────────

    private fun muteVolume() {
        if (isMuted) return
        try {
            lastKnownVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            isMuted = true
            muteStartMs = System.currentTimeMillis()
            Log.d(TAG, "AD DETECTED — muting (saved volume=$lastKnownVolume)")
            // Schedule failsafe unmute
            handler.removeCallbacks(failsafeRunnable)
            handler.postDelayed(failsafeRunnable, FAILSAFE_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mute", e)
        }
    }

    private fun unmuteVolume(fromFailsafe: Boolean = false) {
        if (!isMuted) return
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, lastKnownVolume, 0)
            isMuted = false
            handler.removeCallbacks(failsafeRunnable)  // cancel failsafe if still pending
            val durationMs = System.currentTimeMillis() - muteStartMs
            Log.d(TAG, "Unmuted — duration=${durationMs}ms  failsafe=$fromFailsafe")
            PrefsHelper(applicationContext).saveMuteEvent(muteStartMs, durationMs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unmute", e)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Start foreground immediately in onCreate() to satisfy Android 12+ BGS restrictions.
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        Log.d(TAG, "Service created and moved to foreground")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground already promoted in onCreate(); nothing extra needed here.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(failsafeRunnable)
        unmuteVolume()
        spotifyMediaController?.unregisterCallback(mediaControllerCallback)
        isListenerConnected = false
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    // ── Notification helpers ───────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}