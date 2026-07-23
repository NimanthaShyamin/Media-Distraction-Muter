package com.example.admuteforspotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
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
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

class AdMuteService : NotificationListenerService() {

    // ── Audio ──────────────────────────────────────────────────────────────────
    private lateinit var audioManager: AudioManager
    private var spotifyMediaController: MediaController? = null
    private var isMuted = false
    private var lastKnownVolume = 10
    private var muteStartMs: Long = 0L

    // For Requirement 2: Navigation App detection
    private var isNavigationActive = false
    private var isTemporaryUnmutedForNav = false
    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null

    // ── Failsafe timer ─────────────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val failsafeRunnable = Runnable {
        Log.w(TAG, "Failsafe: unmuting after 4 minutes")
        unmuteVolume(fromFailsafe = true)
    }

    private val spotifyPackageName = "com.spotify.music"

    companion object {
        const val CHANNEL_ID = "AdMuteServiceChannel"
        /**
         * Notification ID for the Spotify Engine.
         * AdMuteAccessibilityService (screen apps) uses ID 2. These two IDs must never overlap.
         */
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
        try {
            // ── Requirement 1: Spotify Tie-in / Traffic Cop ──
            val prefs = PrefsHelper(applicationContext)
            if (!prefs.isMasterAppEnabled() || !prefs.isAppEnabled(StatsManager.APP_SPOTIFY)) {
                Log.d(TAG, "evaluateState: Master or Spotify toggle is OFF — unmuting")
                unmuteVolume()
                updateNotification("Spotify Monitoring Disabled")
                return
            }

            val stateVal = playbackState?.state
            Log.d(TAG, "evaluateState: stateVal=$stateVal, isNavigationActive=$isNavigationActive")

            // ── Requirement 1: Handle Paused/Stopped Playback State ──
            if (stateVal == android.media.session.PlaybackState.STATE_PAUSED ||
                stateVal == android.media.session.PlaybackState.STATE_STOPPED ||
                stateVal == android.media.session.PlaybackState.STATE_NONE) {
                Log.d(TAG, "Playback state is PAUSED, STOPPED or NONE — immediately unmuting")
                unmuteVolume()
                return
            }

            // ── Requirement 2: Skip muting if navigation is active ──
            if (isNavigationActive) {
                Log.d(TAG, "evaluateState: Navigation is active — postponing mute if needed")
                if (metadata != null) {
                    val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                    val album  = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)  ?: ""
                    val title  = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)  ?: ""
                    val isAd = isAdByMetadata(title, album) || !isSkipEnabled(playbackState)
                    
                    if (isAd) {
                        isTemporaryUnmutedForNav = true
                    } else {
                        isTemporaryUnmutedForNav = false
                    }
                }
                unmuteVolume()
                return
            }

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
        } catch (e: Exception) {
            Log.e(TAG, "Crash during evaluateState", e)
            updateNotification("Spotify Connection Error")
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

        try {
            @Suppress("DEPRECATION")
            val token = sbn.notification.extras
                .getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)

            if (token != null && spotifyMediaController?.sessionToken != token) {
                spotifyMediaController?.unregisterCallback(mediaControllerCallback)
                spotifyMediaController = MediaController(applicationContext, token)
                spotifyMediaController?.registerCallback(mediaControllerCallback)
                updateNotification("Monitoring Spotify")
                Log.d(TAG, "Attached to new Spotify MediaSession via notification")
                // Immediately evaluate current state with both metadata and playback state
                evaluateState(
                    spotifyMediaController?.metadata,
                    spotifyMediaController?.playbackState
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaController crash in onNotificationPosted", e)
            updateNotification("Spotify Connection Error")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName != spotifyPackageName) return

        try {
            spotifyMediaController?.unregisterCallback(mediaControllerCallback)
            spotifyMediaController = null
            unmuteVolume()
            updateNotification("Monitoring Paused (Spotify Closed)")
            Log.d(TAG, "Spotify notification dismissed — media controller released and service idle")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up onNotificationRemoved", e)
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
            
            if (durationMs > 0) {
                StatsManager.addTimeSaved(applicationContext, StatsManager.APP_SPOTIFY, durationMs)
                StatsManager.incrementMuted(applicationContext, StatsManager.APP_SPOTIFY)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unmute", e)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Reacts instantly when the user flips the Master toggle or the Spotify toggle.
     *
     * Without this listener, a toggle change only takes effect on the next
     * MediaController callback (which may never come if Spotify is paused).
     * With this listener, any SharedPreferences write immediately re-evaluates state:
     *  - Master or Spotify toggle OFF → unmute, update notification to "Spotify Monitoring Disabled"
     *  - Toggle back ON → notification updates to active monitoring text
     *
     * Registered in [onCreate], unregistered in [onDestroy].
     */
    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        Log.d(TAG, "Prefs changed — re-evaluating Spotify service state")
        handler.post { reEvaluateFromPrefsChange() }
    }

    /**
     * Called on the main thread whenever a SharedPreferences key changes.
     * Reads current toggles and transitions state accordingly.
     */
    private fun reEvaluateFromPrefsChange() {
        val prefs = PrefsHelper(applicationContext)
        val masterOn = prefs.isMasterAppEnabled()
        val spotifyOn = prefs.isAppEnabled(StatsManager.APP_SPOTIFY)

        if (!masterOn || !spotifyOn) {
            Log.d(TAG, "reEvaluateFromPrefsChange: toggled OFF — unmuting")
            unmuteVolume()
            updateNotification("Spotify Monitoring Disabled")
        } else {
            // Toggles are ON — re-evaluate with whatever state Spotify is in
            Log.d(TAG, "reEvaluateFromPrefsChange: toggled ON — re-evaluating")
            updateNotification("Monitoring Spotify")
            evaluateState(
                spotifyMediaController?.metadata,
                spotifyMediaController?.playbackState
            )
        }
    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupAudioPlaybackCallback() {
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                super.onPlaybackConfigChanged(configs)
                val navActive = configs.any { config ->
                    val usage = config.audioAttributes.usage
                    usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE ||
                            usage == AudioAttributes.USAGE_ASSISTANT
                }
                
                if (navActive != isNavigationActive) {
                    isNavigationActive = navActive
                    Log.d(TAG, "onPlaybackConfigChanged: isNavigationActive changed to $isNavigationActive")
                    if (isNavigationActive) {
                        // If we are currently muted due to an ad, temporarily unmute for the navigation voice
                        if (isMuted) {
                            isTemporaryUnmutedForNav = true
                            Log.d(TAG, "onPlaybackConfigChanged: Temporarily unmuting for navigation voice")
                            unmuteVolume()
                        }
                    } else {
                        // Once navigation stops speaking, resume previous mute state if it was temporarily unmuted
                        if (isTemporaryUnmutedForNav) {
                            isTemporaryUnmutedForNav = false
                            Log.d(TAG, "onPlaybackConfigChanged: Navigation finished, re-evaluating media state")
                            evaluateState(
                                spotifyMediaController?.metadata,
                                spotifyMediaController?.playbackState
                            )
                        }
                    }
                }
            }
        }
        audioPlaybackCallback = callback
        audioManager.registerAudioPlaybackCallback(callback, handler)
        Log.d(TAG, "AudioPlaybackCallback registered successfully")
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setupAudioPlaybackCallback()
        }
        // Start foreground immediately in onCreate() to satisfy Android 12+ BGS restrictions.
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        // Register the real-time prefs listener so toggle changes are instant
        applicationContext
            .getSharedPreferences("AdMutePrefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsChangeListener)
        Log.d(TAG, "SharedPreferences listener registered")

        Log.d(TAG, "Service created and moved to foreground")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground already promoted in onCreate(); nothing extra needed here.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(failsafeRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioPlaybackCallback != null) {
            audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback!!)
            Log.d(TAG, "AudioPlaybackCallback unregistered")
        }
        // Unregister the real-time prefs listener
        applicationContext
            .getSharedPreferences("AdMutePrefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsChangeListener)
        Log.d(TAG, "SharedPreferences listener unregistered")
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

    private fun buildNotification(contentText: String? = null): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(contentText ?: getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
    
    fun updateNotification(contentText: String) {
        val notification = buildNotification(contentText)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, notification)
    }
}