package com.example.admuteforspotify

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

/**
 * AdMuteAccessibilityService — YouTube Ad Detection, Muting & Skipping
 *                             + Meta (Facebook / Instagram) Feed Ad Muting
 *
 * NOTE: Spotify is NOT handled here. It is managed entirely in the background
 * by [AdMuteService] via MediaController callbacks on its notification.
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  ARCHITECTURE: Single Source of Truth State Manager                        │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │                                                                              │
 * │  [AppState] enum is the single source of truth for what app is active.     │
 * │  The Notification Engine ONLY reacts to AppState transitions — it is       │
 * │  completely decoupled from the scanner logic, eliminating all UI desyncs.  │
 * │                                                                              │
 * │  ┌──────────────────────────────────────────────────────────────────────┐   │
 * │  │  SystemUI Bypass Rule                                                │   │
 * │  │  If pkg == "com.android.systemui" (notification shade, volume HUD), │   │
 * │  │  the event is silently ignored. AppState is NEVER changed and the   │   │
 * │  │  notification NEVER flickers. The scanner stays locked on its       │   │
 * │  │  current target (e.g. YouTube).                                     │   │
 * │  └──────────────────────────────────────────────────────────────────────┘   │
 * │                                                                              │
 * │  ┌──────────────────────────────────────────────────────────────────────┐   │
 * │  │  Else → IDLE Rule                                                    │   │
 * │  │  If pkg is not SystemUI AND not a target app (home screen, browser, │   │
 * │  │  settings, etc.), AppState transitions to IDLE: scanner sleeps,     │   │
 * │  │  media unmutes, notification resets to "Ad Mute Active".           │   │
 * │  └──────────────────────────────────────────────────────────────────────┘   │
 * │                                                                              │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │  Hardware Audio Alarm (AudioPlaybackCallback)                               │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │  On API 26+, an AudioPlaybackCallback is registered with AudioManager.     │
 * │  The exact millisecond the speaker hardware activates, this alarm fires    │
 * │  and immediately triggers a full ad scan — no polling, no fragile timers.  │
 * │                                                                              │
 * │  Timer-Free Audio Gate: In onAccessibilityEvent, if audioManager            │
 * │  .isMusicActive is false, the screen event is instantly discarded.         │
 * │  The AudioPlaybackCallback wakes the scanner when audio starts.            │
 * │                                                                              │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │  YouTube Ad Detection Strategy (Root-Level Exclusion Scanner)              │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │  REPLACES the old findPlayerContainer() / player_view bounding-box logic. │
 * │  Scans from ROOT to support floating PiP windows and YouTube layout       │
 * │  changes. Uses a strict exclusion rule instead of a strict inclusion rule:  │
 * │                                                                              │
 * │  EXCLUSION KEYWORDS: If a node's viewIdResourceName, contentDescription,   │
 * │  or text contains "comment", "chat", "live_chat", "related_videos", or     │
 * │  "feed", that ENTIRE subtree is pruned instantly.                          │
 * │                                                                              │
 * │  TEXT TRIGGER (Muting) — exact-match, case-insensitive:                    │
 * │    text or contentDescription == "Ad"                                       │
 * │    text or contentDescription == "Ad ·"  (U+00B7 middle dot)              │
 * │    text or contentDescription == "Sponsored"                               │
 * │    text or contentDescription == "Visit advertiser"                        │
 * │    text or contentDescription startsWith "Ad 1 of "                        │
 * │                                                                              │
 * │  SELF-CORRECTION: If "Next video" / "Previous video" buttons are visible   │
 * │  alongside ad text → unmute (fade-out protection via ignoreCurrentTrigger) │
 * │                                                                              │
 * │  SKIP BUTTON: DFS for "Skip"/"Skip Ad"/"Skip ads" → click clickable       │
 * │  node or nearest clickable ancestor. 5-second debounce.                   │
 * │                                                                              │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │  Meta (Facebook / Instagram) Ad Detection Strategy                          │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │  SPONSORED TEXT HEURISTIC:                                                  │
 * │    "Sponsored" (text or contentDescription, case-insensitive) found in     │
 * │    feed → MUTE. When it disappears → UNMUTE.                               │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * Battery optimizations:
 *  1. AppState enum prevents redundant notification posts and scanner calls.
 *  2. SystemUI bypass: zero work done when notification shade is pulled.
 *  3. Screen-Off Sensor: ACTION_SCREEN_OFF BroadcastReceiver forces IDLE instantly,
 *     bypassing the com.android.systemui lock-screen trap.
 *  4. Audio Gate: events discarded instantly when isMusicActive is false.
 *  5. AudioPlaybackCallback (API 26+): replaces polling, reacts at hardware speed.
 *  6. Exclusion scanner prunes entire subtrees early, avoiding deep DFS waste.
 *  7. Failsafe timer: auto-unmutes after 5 minutes to prevent stuck-muted state.
 *  8. SharedPreferences listener: UI toggle changes are reflected instantly without
 *     waiting for the next accessibility event.
 *
 * Notification:
 *  - Uses Notification ID 2 exclusively. AdMuteService (Spotify) uses ID 1.
 *  - These two services NEVER modify each other's notifications.
 */
class AdMuteAccessibilityService : AccessibilityService() {

    // ═════════════════════════════════════════════════════════════════════════
    //  Companion Object — Constants & Static State
    // ═════════════════════════════════════════════════════════════════════════

    companion object {
        private const val TAG = "AdMuteA11yService"

        // ── Target package names ─────────────────────────────────────────────
        private const val PKG_YOUTUBE   = "com.google.android.youtube"
        private const val PKG_FACEBOOK  = "com.facebook.katana"
        private const val PKG_INSTAGRAM = "com.instagram.android"
        private const val PKG_SYSTEMUI  = "com.android.systemui"

        /**
         * All packages we actively scan. O(1) lookup in the hot path.
         * Spotify is intentionally excluded — handled by [AdMuteService].
         */
        private val TARGET_PACKAGES: Set<String> = hashSetOf(
            PKG_YOUTUBE,
            PKG_FACEBOOK,
            PKG_INSTAGRAM
        )

        /**
         * Skip button text patterns (longest first to prevent premature matches).
         * YouTube uses multiple labels across locales and ad formats.
         */
        private val SKIP_TEXT_PATTERNS: List<String> = listOf(
            "skip ads",
            "skip ad",
            "skip"
        )

        /**
         * Exclusion keywords for the YouTube root-level scanner.
         *
         * If ANY node's viewIdResourceName, contentDescription, or text contains
         * one of these strings (case-insensitive), that node's ENTIRE subtree is
         * pruned from the ad-text scan. This replaces the old findPlayerContainer()
         * approach and works correctly with PiP windows and YouTube layout changes.
         */
        private val SCAN_EXCLUSION_KEYWORDS: List<String> = listOf(
            "live_chat",
            "chat",
            "comment",
            "related_videos",
            "feed"
        )

        /** Auto-unmute failsafe: fires if ad state gets stuck for 5 minutes. */
        private const val FAILSAFE_UNMUTE_MS = 5 * 60 * 1000L

        /** Debounce skip-button clicks to prevent double-skipping. */
        private const val SKIP_DEBOUNCE_MS = 5000L

        /**
         * Notification ID for the Screen Engine (YouTube / Facebook / Instagram).
         * AdMuteService (Spotify) uses ID 1. These two IDs must never overlap.
         */
        private const val NOTIF_ID    = 2
        private const val CHANNEL_ID  = "AdMuteServiceChannel"

        /** Read by other components to check if the service is live. */
        @Volatile
        var isServiceConnected: Boolean = false
            private set
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Central State Manager
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * The Single Source of Truth for which app is currently in the foreground
     * and whether monitoring is active.
     *
     * Active scanning states:
     *  - [IDLE]               → No target app active; scanner asleep. "Ad Mute Active".
     *  - [YOUTUBE]            → YouTube in foreground; YouTube scanner running.
     *  - [FACEBOOK]           → Facebook in foreground; Meta scanner running.
     *  - [INSTAGRAM]          → Instagram in foreground; Meta scanner running.
     *
     * Contextual disabled states (target app on screen, toggle is OFF):
     *  - [MASTER_DISABLED]    → Master toggle is OFF. "All services disabled".
     *  - [YOUTUBE_DISABLED]   → YouTube on screen but its toggle is OFF.
     *                           "YouTube monitoring disabled".
     *  - [FACEBOOK_DISABLED]  → Facebook on screen but its toggle is OFF.
     *                           "Facebook monitoring disabled".
     *  - [INSTAGRAM_DISABLED] → Instagram on screen but its toggle is OFF.
     *                           "Instagram monitoring disabled".
     *
     * The Notification Engine listens ONLY to transitions of this state.
     * It is never updated by the scanner itself, eliminating all UI desyncs.
     */
    private enum class AppState {
        IDLE,
        YOUTUBE,
        FACEBOOK,
        INSTAGRAM,
        MASTER_DISABLED,
        YOUTUBE_DISABLED,
        FACEBOOK_DISABLED,
        INSTAGRAM_DISABLED
    }

    /** Current foreground state. Starts IDLE. Only [transitionTo] may mutate it. */
    private var activeAppState: AppState = AppState.IDLE

    /**
     * True if this state represents an active scanning state (scanner is running).
     * All disabled/idle states return false.
     */
    private val AppState.isScanningActive: Boolean
        get() = this == AppState.YOUTUBE ||
                this == AppState.FACEBOOK ||
                this == AppState.INSTAGRAM

    /**
     * True if this state is any dormant state (scanner is asleep).
     * Used by [transitionTo] to decide when to call [resetScannerState].
     */
    private val AppState.isDormant: Boolean
        get() = !isScanningActive

    /**
     * Transitions to a new [AppState] and updates the notification if the
     * state has actually changed. This is the ONLY place the notification
     * text is ever decided.
     *
     * When moving from an active scanning state to any dormant state, also
     * calls [resetScannerState] to clear flags and unmute.
     *
     * @param newState   The desired new state.
     * @param notifText  Notification text override, or null to derive from state.
     */
    private fun transitionTo(newState: AppState, notifText: String? = null) {
        if (activeAppState == newState && notifText == null) return

        val previousState = activeAppState
        activeAppState = newState

        val text = notifText ?: when (newState) {
            AppState.IDLE               -> "Ad Mute Active"
            AppState.YOUTUBE            -> "Monitoring YouTube"
            AppState.FACEBOOK           -> "Monitoring Facebook"
            AppState.INSTAGRAM          -> "Monitoring Instagram"
            AppState.MASTER_DISABLED    -> "All services disabled"
            AppState.YOUTUBE_DISABLED   -> "YouTube monitoring disabled"
            AppState.FACEBOOK_DISABLED  -> "Facebook monitoring disabled"
            AppState.INSTAGRAM_DISABLED -> "Instagram monitoring disabled"
        }

        // State Transition Hooks: Dynamic Package Filtering (Battery Shield)
        when (newState) {
            AppState.IDLE -> updateServiceFiltering(isIdle = true)
            AppState.YOUTUBE, AppState.FACEBOOK, AppState.INSTAGRAM -> updateServiceFiltering(isIdle = false)
            else -> {}
        }

        // Moving from an active scanner state into any dormant state:
        // reset all flags and unmute so we never leave the device stuck muted.
        if (previousState.isScanningActive && newState.isDormant) {
            resetScannerState()
        }

        updateSystemNotification(text)
        Log.d(TAG, "AppState: $previousState → $newState | Notification: \"$text\"")
    }

    /**
     * Dynamically updates accessibility service package filtering (Battery Shield).
     *
     * If [isIdle] == true: restricts [serviceInfo.packageNames] to [TARGET_PACKAGES]
     * to close the firehose and block OS event noise while dormant.
     * If [isIdle] == false: sets [serviceInfo.packageNames] to null (opens the
     * firehose temporarily) so we catch exact exit events when navigating away.
     */
    private fun updateServiceFiltering(isIdle: Boolean) {
        try {
            val info = serviceInfo ?: return
            info.packageNames = if (isIdle) TARGET_PACKAGES.toTypedArray() else null
            serviceInfo = info
            Log.d(TAG, "updateServiceFiltering: isIdle=$isIdle | packageNames=${if (isIdle) TARGET_PACKAGES.toString() else "ALL (null)"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update service filtering", e)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  System Services
    // ═════════════════════════════════════════════════════════════════════════

    private val powerManager: PowerManager by lazy {
        getSystemService(POWER_SERVICE) as PowerManager
    }

    private lateinit var audioManager: AudioManager

    // ═════════════════════════════════════════════════════════════════════════
    //  YouTube Scan State
    // ═════════════════════════════════════════════════════════════════════════

    /** True while a YouTube ad is confirmed active and we have muted. */
    private var isYouTubeAdPlaying: Boolean = false

    /** True when we have called setStreamVolume(0) on the media stream. */
    private var isCurrentlyMuted: Boolean = false

    /**
     * Override latch (fade-out protection).
     * Set to true when the self-correction logic fires (ad text present but
     * playback controls also visible). Stays true until ad text fully disappears,
     * preventing the mute from re-triggering as controls fade out.
     */
    private var ignoreCurrentTrigger: Boolean = false

    /**
     * Staging flag for the ad count database write.
     * Set true when mute triggers, committed to DB only when ad text disappears.
     * This prevents counting an ad that was incorrectly detected.
     */
    private var pendingMuteCount: Boolean = false

    /** Volume level saved before muting; restored on unmute. */
    private var savedVolume: Int = -1

    /** Timestamp of the mute start; used for time-saved calculation. */
    private var currentAdStartTime: Long = 0L

    // ═════════════════════════════════════════════════════════════════════════
    //  Meta (Facebook / Instagram) Scan State
    // ═════════════════════════════════════════════════════════════════════════

    /** True while a Meta feed ad (Sponsored text) is visible on screen. */
    private var isMetaAdPlaying: Boolean = false

    /** Debounce: prevents counting the same Meta ad multiple times in 15s. */
    private var lastMetaMuteTime = 0L

    // ═════════════════════════════════════════════════════════════════════════
    //  Debounce & Notification Dedup
    // ═════════════════════════════════════════════════════════════════════════

    /** Timestamp of the last successful Skip button click. */
    private var lastSkipClickTime = 0L

    /** Last notification text posted; prevents rebuilding an identical notification. */
    private var lastNotificationText: String? = null

    // ═════════════════════════════════════════════════════════════════════════
    //  Failsafe Timer
    // ═════════════════════════════════════════════════════════════════════════

    private val handler = Handler(Looper.getMainLooper())

    private val failsafeRunnable = Runnable {
        Log.w(TAG, "Failsafe triggered — force-unmuting after ${FAILSAFE_UNMUTE_MS / 1000}s of mute")
        unmuteMedia(reason = "failsafe")
    }

    /**
     * Delay before actually unmuting after ad text disappears (Fix 4 — Sticky Mute).
     *
     * When ad text vanishes, we do NOT unmute immediately. YouTube overlays can
     * briefly drop the "Ad" label while fading, which would cause a 1–2 frame
     * audio pop before the ad truly ends. This 2-second buffer absorbs that
     * flicker. If ad text reappears within the window, the runnable is cancelled
     * and the device stays muted. If a seek bar is detected instead, the delay
     * is cancelled and unmute happens immediately (seek bar proves real video).
     */
    private val STICKY_UNMUTE_MS = 2000L

    /**
     * True while the 2-second unmute delay is pending.
     * Prevents [finalizeAdLifecycle] from being called redundantly during the
     * delay window, and lets [handleYouTubeEvent] know it should check for
     * seek bars and ad-text reappearance.
     */
    private var isAwaitingUnmute: Boolean = false

    /**
     * The delayed unmute posted by [finalizeAdLifecycle].
     * Cancelled by [handleYouTubeEvent] if ad text reappears, or executed
     * early if a seek bar is detected.
     */
    private val stickyUnmuteRunnable = Runnable {
        if (isAwaitingUnmute) {
            Log.i(TAG, "YouTube: Sticky delay elapsed — executing unmute")
            isAwaitingUnmute = false
            commitAdLifecycleUnmute()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Screen-Off Sensor (Lock Screen Trap Fix)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Forces the State Manager to IDLE the instant the screen turns off.
     *
     * **Why this is necessary:**
     * When the screen locks, Android fires accessibility events from
     * `com.android.systemui` (the lock screen). Our SystemUI bypass silently
     * ignores those events, which means without this receiver the service would
     * stay locked on its last target app (e.g. YouTube) indefinitely while the
     * device is sitting on the lock screen — wasting battery and keeping the
     * notification in the wrong state.
     *
     * By listening to [Intent.ACTION_SCREEN_OFF] at the OS level, we react
     * immediately and unconditionally, bypassing the SystemUI package trap.
     *
     * Registered in [onServiceConnected], unregistered in [onDestroy].
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.d(TAG, "Screen-Off Sensor: Screen turned off — forcing IDLE")
                // Reset to clean IDLE unconditionally; the lock screen is not a
                // target app and we do not want the scanner running while locked.
                handler.post { transitionTo(AppState.IDLE) }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Real-Time Button Listener (UI Toggle → Instant State Update)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Reacts instantly when the user changes any toggle in the app UI.
     *
     * Without this listener, a toggle change only takes effect on the next
     * accessibility event. If the user turns off the YouTube toggle mid-ad,
     * the service keeps muting until the next screen update arrives.
     *
     * With this listener, any SharedPreferences write triggers an immediate
     * re-evaluation of the current state using the contextual disabled states.
     *
     * Registered in [onServiceConnected], unregistered in [onDestroy].
     */
    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        Log.d(TAG, "Prefs changed — re-evaluating service state")
        handler.post { reEvaluateFromPrefsChange() }
    }

    /**
     * Called on the main thread whenever a SharedPreferences key changes.
     * Reads the current master/app toggles and transitions to the correct
     * contextual state immediately — no waiting for the next screen event.
     *
     * Transition table:
     *  master OFF                        → MASTER_DISABLED
     *  master ON, YouTube on screen, YT toggle OFF  → YOUTUBE_DISABLED
     *  master ON, Facebook on screen, FB toggle OFF  → FACEBOOK_DISABLED
     *  master ON, Instagram on screen, IG toggle OFF → INSTAGRAM_DISABLED
     *  all relevant toggles ON           → refresh to current active/idle text
     */
    private fun reEvaluateFromPrefsChange() {
        val prefs = PrefsHelper(applicationContext)

        // ── Master toggle OFF → disable everything ───────────────────────────
        if (!prefs.isMasterAppEnabled()) {
            transitionTo(AppState.MASTER_DISABLED)
            return
        }

        // ── Per-app toggle check for the currently active app ────────────────
        // Map the current state to check if its corresponding toggle is now OFF
        val disabledState: AppState? = when (activeAppState) {
            AppState.YOUTUBE,
            AppState.YOUTUBE_DISABLED   ->
                if (!prefs.isAppEnabled(StatsManager.APP_YOUTUBE)) AppState.YOUTUBE_DISABLED else null
            AppState.FACEBOOK,
            AppState.FACEBOOK_DISABLED  ->
                if (!prefs.isAppEnabled(StatsManager.APP_FACEBOOK)) AppState.FACEBOOK_DISABLED else null
            AppState.INSTAGRAM,
            AppState.INSTAGRAM_DISABLED ->
                if (!prefs.isAppEnabled(StatsManager.APP_INSTAGRAM)) AppState.INSTAGRAM_DISABLED else null
            else -> null
        }
        if (disabledState != null) {
            transitionTo(disabledState)
            return
        }

        // ── All relevant toggles are ON — restore the correct active text ─────
        // Force notification refresh (clear dedup cache) in case text was stale.
        lastNotificationText = null
        val restoredState = when (activeAppState) {
            AppState.YOUTUBE,
            AppState.YOUTUBE_DISABLED   -> AppState.YOUTUBE
            AppState.FACEBOOK,
            AppState.FACEBOOK_DISABLED  -> AppState.FACEBOOK
            AppState.INSTAGRAM,
            AppState.INSTAGRAM_DISABLED -> AppState.INSTAGRAM
            AppState.MASTER_DISABLED    -> AppState.IDLE  // Master just turned back ON
            else                        -> activeAppState  // Already IDLE — keep as-is
        }
        transitionTo(restoredState)
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Hardware Audio Alarm (API 26+)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Fires the exact millisecond Android's audio hardware activates or stops.
     *
     * When the scanner discards a screen event because audio isn't playing yet
     * (buffering race condition), this callback rescues it: as soon as the
     * speaker kicks on, we immediately re-scan the current window.
     *
     * Registered in [onServiceConnected], unregistered in [onDestroy].
     * Only active on API 26+ (AudioPlaybackCallback requires Oreo).
     */
    @SuppressLint("NewApi")
    private val audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            // Only react if a MEDIA-usage stream just became active AND we are
            // in an actively scanning state (not disabled or idle)
            val mediaIsActive = configs.any {
                it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA
            }
            if (mediaIsActive && activeAppState.isScanningActive) {
                Log.d(TAG, "AudioAlarm: Hardware audio activated — triggering re-scan")
                handler.post { triggerScanFromAudioAlarm() }
            }
        }
    }

    /**
     * Called by [audioPlaybackCallback] on the main thread.
     * Performs a fresh accessibility scan of the current window.
     * Only runs when [activeAppState] is an active scanning state.
     */
    private fun triggerScanFromAudioAlarm() {
        if (!powerManager.isInteractive) return
        if (!activeAppState.isScanningActive) return
        val rootNode = rootInActiveWindow ?: return
        val pkg = rootNode.packageName?.toString() ?: run {
            @Suppress("DEPRECATION")
            rootNode.recycle()
            return
        }
        // Only dispatch if the window matches our current tracked app state
        val expectedPkg = when (activeAppState) {
            AppState.YOUTUBE   -> PKG_YOUTUBE
            AppState.FACEBOOK  -> PKG_FACEBOOK
            AppState.INSTAGRAM -> PKG_INSTAGRAM
            else               -> null
        }
        if (pkg != expectedPkg) {
            @Suppress("DEPRECATION")
            rootNode.recycle()
            return
        }
        Log.d(TAG, "AudioAlarm: Dispatching scan for $pkg")
        when (activeAppState) {
            AppState.YOUTUBE   -> handleYouTubeEvent(rootNode)
            AppState.FACEBOOK,
            AppState.INSTAGRAM -> handleMetaFeedEvent(rootNode, pkg)
            else               -> {
                @Suppress("DEPRECATION")
                rootNode.recycle()
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    override fun onServiceConnected() {
        super.onServiceConnected()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // ── Register the hardware audio alarm (API 26+) ──────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.registerAudioPlaybackCallback(
                audioPlaybackCallback,
                handler
            )
            Log.d(TAG, "AudioPlaybackCallback registered (API ${Build.VERSION.SDK_INT})")
        } else {
            Log.d(TAG, "AudioPlaybackCallback NOT registered (API ${Build.VERSION.SDK_INT} < 26)")
        }

        // ── Register the Screen-Off Sensor ───────────────────────────────────
        // Must use an explicit IntentFilter — ACTION_SCREEN_OFF cannot be
        // declared in the manifest; it requires a runtime-registered receiver.
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        Log.d(TAG, "Screen-off BroadcastReceiver registered")

        // ── Register the real-time prefs listener ────────────────────────────
        applicationContext
            .getSharedPreferences("AdMutePrefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsChangeListener)
        Log.d(TAG, "SharedPreferences listener registered")

        // ── Initialize dynamic package filtering in IDLE state ──────────────
        updateServiceFiltering(isIdle = true)

        isServiceConnected = true
        Log.i(TAG, "AdMuteAccessibilityService connected")
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted by system")
    }

    override fun onDestroy() {
        super.onDestroy()

        // ── Unregister the Screen-Off Sensor ─────────────────────────────────
        try {
            unregisterReceiver(screenOffReceiver)
            Log.d(TAG, "Screen-off BroadcastReceiver unregistered")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Screen-off receiver was not registered — skipping unregister")
        }

        // ── Unregister the real-time prefs listener ──────────────────────────
        applicationContext
            .getSharedPreferences("AdMutePrefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsChangeListener)
        Log.d(TAG, "SharedPreferences listener unregistered")

        // ── Unregister the hardware audio alarm ──────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback)
        }

        handler.removeCallbacks(failsafeRunnable)
        unmuteMedia(reason = "service destroyed")
        isServiceConnected = false
        Log.i(TAG, "AdMuteAccessibilityService destroyed")
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Decoupled Notification Engine
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Posts a notification update DIRECTLY via [NotificationManager].
     *
     * This is intentionally a low-level call that bypasses [AdMuteService.instance]
     * entirely — that reference can be null, causing stuck-text bugs. By going
     * directly to NotificationManager we guarantee delivery regardless of whether
     * the foreground service is alive.
     *
     * Deduplication: if [text] is identical to [lastNotificationText], returns
     * immediately to avoid a redundant system binder call.
     */
    private fun updateSystemNotification(text: String) {
        if (text == lastNotificationText) return
        lastNotificationText = text

        try {
            val contentIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setSilent(true)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Main Accessibility Event Entry Point
    // ═════════════════════════════════════════════════════════════════════════

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // ── Early bailout: screen is off, don't waste cycles ────────────────
        if (!powerManager.isInteractive) return

        val prefsHelper = PrefsHelper(applicationContext)

        // ────────────────────────────────────────────────────────────────────
        //  MASTER TOGGLE CHECK
        // ────────────────────────────────────────────────────────────────────
        //  If the master switch is off, show a contextual "All services disabled"
        //  notification and sleep. Uses MASTER_DISABLED state so the prefs
        //  listener can restore the correct state the moment it's turned back on.
        if (!prefsHelper.isMasterAppEnabled()) {
            transitionTo(AppState.MASTER_DISABLED)
            return
        }

        // ────────────────────────────────────────────────────────────────────
        //  THE TOP-LEVEL BOUNCER
        // ────────────────────────────────────────────────────────────────────
        val currentPkg = event.packageName?.toString()

        // 1. SystemUI event → return instantly (do nothing, preserve current state)
        if (currentPkg == PKG_SYSTEMUI) {
            Log.v(TAG, "Top-level bouncer: SystemUI event — holding current state")
            return
        }

        // 2. Non-target event package → Immediately transition to IDLE and return
        if (currentPkg !in TARGET_PACKAGES) {
            Log.v(TAG, "Top-level bouncer: currentPkg=$currentPkg is non-target — transitioning to IDLE")
            transitionTo(AppState.IDLE)
            return
        }

        // ────────────────────────────────────────────────────────────────────
        //  ROOT WINDOW HIJACK PROTECTION
        // ────────────────────────────────────────────────────────────────────
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.v(TAG, "Hijack protection: rootInActiveWindow=null — transitioning to IDLE")
            transitionTo(AppState.IDLE)
            return
        }

        val activePkg = rootNode.packageName?.toString()
        if (activePkg !in TARGET_PACKAGES) {
            @Suppress("DEPRECATION")
            rootNode.recycle()
            Log.v(TAG, "Hijack protection: activePkg=$activePkg is non-target — transitioning to IDLE")
            transitionTo(AppState.IDLE)
            return
        }

        val pkg = activePkg

        // ────────────────────────────────────────────────────────────────────
        //  PER-APP TOGGLE CHECK — Contextual "Disabled" Notifications
        // ────────────────────────────────────────────────────────────────────
        //  If a target app is on screen but its specific toggle is OFF, we
        //  set a DISABLED state rather than generic IDLE. This gives the user
        //  a contextual notification (e.g. "YouTube monitoring disabled") so
        //  they know why the service isn't muting, without running the scanner.
        val appToggleOn = when (pkg) {
            PKG_YOUTUBE   -> prefsHelper.isAppEnabled(StatsManager.APP_YOUTUBE)
            PKG_FACEBOOK  -> prefsHelper.isAppEnabled(StatsManager.APP_FACEBOOK)
            PKG_INSTAGRAM -> prefsHelper.isAppEnabled(StatsManager.APP_INSTAGRAM)
            else          -> false
        }
        if (!appToggleOn) {
            @Suppress("DEPRECATION")
            rootNode.recycle()
            val disabledState = when (pkg) {
                PKG_YOUTUBE   -> AppState.YOUTUBE_DISABLED
                PKG_FACEBOOK  -> AppState.FACEBOOK_DISABLED
                PKG_INSTAGRAM -> AppState.INSTAGRAM_DISABLED
                else          -> AppState.IDLE
            }
            transitionTo(disabledState)
            return
        }

        // ── State Manager: transition to the active scanning app if needed ───
        val newState = when (pkg) {
            PKG_YOUTUBE   -> AppState.YOUTUBE
            PKG_FACEBOOK  -> AppState.FACEBOOK
            PKG_INSTAGRAM -> AppState.INSTAGRAM
            else          -> AppState.IDLE
        }
        transitionTo(newState)

        // ────────────────────────────────────────────────────────────────────
        //  TIMER-FREE AUDIO GATE (with PiP/Mini-Player bypass)
        // ────────────────────────────────────────────────────────────────────
        //  If no media audio is currently playing, discard this screen event
        //  immediately. This saves maximum battery — we don't walk the
        //  accessibility tree at all when the speaker is silent.
        //
        //  PiP BYPASS: Floating PiP and in-app mini-player windows render in
        //  a separate surface layer. Their audio can take 3–4 seconds to reach
        //  the hardware mixer and trigger the AudioPlaybackCallback. To avoid
        //  this delay, we check for a mini-player/PiP node BEFORE discarding.
        //  If one is found, we skip the audio gate and proceed to the scan.
        //  This is safe because hasMiniPlayer() is a fast, shallow DFS that
        //  exits on the first match — it does not walk the whole tree.
        if (!audioManager.isMusicActive) {
            val inPipOrMiniPlayer = pkg == PKG_YOUTUBE && hasMiniPlayer(rootNode)
            if (!inPipOrMiniPlayer) {
                Log.v(TAG, "Audio gate: isMusicActive=false — discarding event for $pkg")
                @Suppress("DEPRECATION")
                rootNode.recycle()
                return
            }
            Log.d(TAG, "Audio gate bypassed: YouTube PiP/mini-player detected — proceeding with scan")
        }

        // ── Dispatch to the appropriate scanner ──────────────────────────────
        when (pkg) {
            PKG_YOUTUBE   -> handleYouTubeEvent(rootNode)
            PKG_FACEBOOK,
            PKG_INSTAGRAM -> handleMetaFeedEvent(rootNode, pkg)
            else          -> {
                @Suppress("DEPRECATION")
                rootNode.recycle()
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  State Transitions
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Resets all scanner state variables and unmutes the media stream.
     *
     * Called by [transitionTo] whenever we move from an active scanning state
     * (YOUTUBE/FACEBOOK/INSTAGRAM) to any dormant state. This ensures that a
     * mute started by the scanner is always cleaned up on any state exit,
     * regardless of which dormant state we're entering (IDLE, MASTER_DISABLED,
     * YOUTUBE_DISABLED, etc.).
     */
    private fun resetScannerState() {
        // Reset YouTube ad-tracking flags
        isYouTubeAdPlaying   = false
        ignoreCurrentTrigger = false
        pendingMuteCount     = false

        // Cancel any pending sticky-unmute delay
        isAwaitingUnmute = false
        handler.removeCallbacks(stickyUnmuteRunnable)

        // Reset Meta ad-tracking flags
        isMetaAdPlaying = false

        // Unmute if the scanner had muted the device
        unmuteMedia(reason = "scanner reset (${activeAppState})")
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  YouTube Ad Detection (Root-Level Exclusion Scanner)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Handles a YouTube accessibility event using a root-level exclusion scan.
     *
     * **Why root-level instead of player_view container?**
     * YouTube's `player_view` resource ID changes across app updates, and
     * Picture-in-Picture windows don't have the same node hierarchy. By scanning
     * from the root and using exclusion rules (pruning comment/chat/feed subtrees),
     * detection is layout-agnostic and PiP-compatible.
     *
     * Strategy:
     *  1. Skip button scan (full tree, 5s debounce).
     *  2. Ad-text scan from root, with exclusion keywords pruning irrelevant
     *     subtrees (comments, chat, related videos, feed).
     *  3. Self-correction: if ad text found AND media controls visible → unmute.
     *  4. Finalize lifecycle when ad text disappears.
     *
     * @param rootNode Root of the active YouTube window. This method recycles it.
     */
    private fun handleYouTubeEvent(rootNode: AccessibilityNodeInfo) {
        try {
            // ── 1. Skip Button Heuristic ─────────────────────────────────────
            val now = System.currentTimeMillis()
            if (now - lastSkipClickTime >= SKIP_DEBOUNCE_MS) {
                val didSkip = findAndClickSkipButton(rootNode)
                if (didSkip) {
                    Log.i(TAG, "YouTube: Auto-skipped ad")
                    lastSkipClickTime = now
                    // Cancel any pending sticky unmute; the skip itself unmutes
                    isAwaitingUnmute = false
                    handler.removeCallbacks(stickyUnmuteRunnable)
                    unmuteMedia(reason = "ad skipped")
                    return
                }
            }

            // ── 2. Mini-Player Context Detection ─────────────────────────────
            val miniPlayerActive = hasMiniPlayer(rootNode)
            if (miniPlayerActive) {
                Log.d(TAG, "YouTube: Mini-player detected — feed exclusion suspended")
            }

            // ── 3. Ad-text scan from root (exclusion-based) ──────────────────
            val hasAdText = hasAdTextTriggers(rootNode, skipFeedExclusion = miniPlayerActive)

            // ── 4. Mute / Unmute Decision (with Sticky Delay, Fix 4) ─────────
            if (hasAdText) {
                // Ad text is visible → cancel any pending unmute delay and stay muted
                if (isAwaitingUnmute) {
                    Log.d(TAG, "YouTube: Ad text reappeared during sticky delay — cancelling unmute")
                    isAwaitingUnmute = false
                    handler.removeCallbacks(stickyUnmuteRunnable)
                }
                if (!isYouTubeAdPlaying) {
                    Log.i(TAG, "YouTube: Ad detected — MUTING")
                    isYouTubeAdPlaying = true
                    pendingMuteCount   = true
                    muteMedia()
                }
            } else {
                // ── 5. No ad text — check sticky-unmute state ────────────────
                if (isYouTubeAdPlaying || pendingMuteCount) {
                    if (!isAwaitingUnmute) {
                        // First scan with no ad text → start the sticky delay
                        finalizeAdLifecycle(reason = "ad text disappeared from screen")
                    } else {
                        // Delay already posted — check for seek bar during the window
                        // A visible seek bar proves the real video has resumed,
                        // so we cancel the delay and unmute immediately.
                        if (hasSeekBar(rootNode)) {
                            Log.i(TAG, "YouTube: Seek bar detected during sticky delay — immediate unmute")
                            isAwaitingUnmute = false
                            handler.removeCallbacks(stickyUnmuteRunnable)
                            commitAdLifecycleUnmute()
                        }
                        // No seek bar yet → keep waiting for the delay to fire
                    }
                }
            }
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }
    }

    /**
     * Initiates the sticky-mute delay when ad text first disappears (Fix 4).
     *
     * Instead of unmuting immediately, this posts a 2-second delayed runnable.
     * The stats write (DB commit) happens now — the ad duration is already known
     * at this point. The actual [unmuteMedia] call is deferred inside
     * [stickyUnmuteRunnable] / [commitAdLifecycleUnmute].
     *
     * The delay can be cancelled by [handleYouTubeEvent] if:
     *  - Ad text reappears (overlay was still fading → stay muted)
     *  - A seek bar is detected (real video resumed → unmute immediately)
     */
    private fun finalizeAdLifecycle(reason: String) {
        Log.i(TAG, "YouTube: Ad text gone — reason=$reason, starting ${STICKY_UNMUTE_MS}ms sticky delay")

        if (pendingMuteCount) {
            StatsManager.incrementMuted(applicationContext, StatsManager.APP_YOUTUBE)

            if (currentAdStartTime > 0L) {
                val durationMs = System.currentTimeMillis() - currentAdStartTime
                if (durationMs > 0) {
                    StatsManager.addTimeSaved(
                        applicationContext,
                        StatsManager.APP_YOUTUBE,
                        durationMs
                    )
                }
            }
            pendingMuteCount = false
        }

        isYouTubeAdPlaying = false

        // Post the delayed unmute — stays pending until the delay fires or is cancelled
        isAwaitingUnmute = true
        handler.removeCallbacks(stickyUnmuteRunnable)   // clear any stale callback
        handler.postDelayed(stickyUnmuteRunnable, STICKY_UNMUTE_MS)
    }

    /**
     * Performs the actual unmute when the sticky delay expires or a seek bar
     * is detected. Separated from [finalizeAdLifecycle] so both the runnable
     * and the seek-bar fast-path share a single call site.
     */
    private fun commitAdLifecycleUnmute() {
        Log.i(TAG, "YouTube: Ad lifecycle committed — unmuting")
        unmuteMedia(reason = "ad ended", appKey = StatsManager.APP_YOUTUBE)
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Root-Level Exclusion Scanner — Ad Text Detection
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Returns true if [node] should be pruned from the ad-text scan.
     *
     * Pruning criteria: any of the [SCAN_EXCLUSION_KEYWORDS] found in:
     *  - [AccessibilityNodeInfo.getViewIdResourceName]
     *  - [AccessibilityNodeInfo.getContentDescription]
     *  - [AccessibilityNodeInfo.getText]
     *
     * All comparisons are case-insensitive substring matches.
     *
     * @param skipFeedExclusion If true, the `"feed"` keyword is temporarily
     *   suppressed from the exclusion list. This is set when a YouTube mini-player
     *   is detected on screen, allowing the scanner to reach the ad overlay
     *   inside the mini-player even though its ancestors may be labelled as feed.
     *
     * When this returns true, the caller must skip the node AND all its children,
     * achieving O(1) pruning of entire comment/chat/feed subtrees.
     */
    private fun isExcludedSubtree(
        node: AccessibilityNodeInfo,
        skipFeedExclusion: Boolean = false
    ): Boolean {
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val desc   = node.contentDescription?.toString()?.lowercase() ?: ""
        val text   = node.text?.toString()?.lowercase() ?: ""

        for (keyword in SCAN_EXCLUSION_KEYWORDS) {
            // When the mini-player is active, allow scanning inside feed subtrees
            // so we don't prune the node that contains the mini-player ad overlay.
            if (skipFeedExclusion && keyword == "feed") continue

            if (viewId.contains(keyword) || desc.contains(keyword) || text.contains(keyword)) {
                return true
            }
        }
        return false
    }

    /**
     * Checks whether a single node is an exact match for a known YouTube ad label.
     *
     * Uses a Regex pattern to match the base words ("ad", "sponsored") and
     * optionally accepts dynamic countdown timers attached to them, e.g.:
     *  - "Ad"               → matches
     *  - "Sponsored"        → matches
     *  - "Sponsored · 15s"  → matches (YouTube dynamic timer)
     *  - "Ad · 8s"          → matches
     *  - "load", "bad", "added" → do NOT match (anchored to ^ and $)
     *
     * Regex breakdown:
     *  ^(ad|sponsored)       — must start with exactly "ad" or "sponsored"
     *  (\s*\u00b7\s*\d*s?)?  — optionally followed by spaces, middle-dot,
     *                           spaces, any digits, and an optional trailing 's'
     *  $                     — nothing else allowed after
     */
    private fun isExactAdTextMatch(node: AccessibilityNodeInfo): Boolean {
        // Prefer .text; fall back to .contentDescription for hidden-text ads
        val rawText = node.text?.toString() ?: node.contentDescription?.toString() ?: return false
        val text    = rawText.trim().lowercase()
        if (text.isEmpty()) return false

        val dynamicTimerRegex = Regex("""^(ad|sponsored)(\s*\u00b7\s*\d*s?)?$""")

        return text.matches(dynamicTimerRegex) ||
               text == "visit advertiser" ||
               text.startsWith("ad 1 of ")
    }

    /**
     * Recursively scans [node] for ad text triggers, pruning excluded subtrees.
     *
     * Walk order:
     *  1. If the node matches [isExcludedSubtree] → prune the entire branch.
     *  2. If the node matches [isExactAdTextMatch] → return true immediately.
     *  3. Recurse into each child.
     *
     * @param skipFeedExclusion Forwarded to [isExcludedSubtree]. When true, the
     *   `"feed"` exclusion keyword is suppressed for this scan pass (mini-player
     *   context).
     * @return true if any non-excluded node in this subtree is an exact ad match.
     */
    private fun hasAdTextTriggers(
        node: AccessibilityNodeInfo,
        skipFeedExclusion: Boolean = false
    ): Boolean {
        // Prune entire excluded subtrees early (comments, chat, feed, etc.)
        if (isExcludedSubtree(node, skipFeedExclusion)) return false

        // Check this node for ad text
        if (isExactAdTextMatch(node)) return true

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (hasAdTextTriggers(child, skipFeedExclusion)) return true
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return false
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Mini-Player Detection
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Returns true if a YouTube in-app mini-player overlay is visible anywhere
     * in the current window.
     *
     * YouTube's mini-player is identified by node viewIdResourceNames or
     * contentDescriptions containing `"miniplayer"` or `"mini_player"`
     * (case-insensitive). This fast scan short-circuits on the first hit,
     * making it O(1) in the common case (no mini-player).
     *
     * @return true if at least one mini-player node is found in the subtree.
     */
    private fun hasMiniPlayer(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val desc   = node.contentDescription?.toString()?.lowercase() ?: ""

        if (viewId.contains("miniplayer") || viewId.contains("mini_player") ||
            desc.contains("miniplayer")   || desc.contains("mini_player")) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (hasMiniPlayer(child)) return true
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return false
    }

    /**
     * Returns true if a video seek bar (timeline scrubber) is visible anywhere
     * in the current window.
     *
     * YouTube renders its video timeline as an [android.widget.SeekBar] or as a
     * custom view whose viewIdResourceName contains `"time_bar"` or `"seek"`
     * (case-insensitive). Presence of a seek bar proves the real video content
     * is now rendered — ads do not expose a timeline scrubber.
     *
     * Used by the sticky-mute delay (Fix 4): if a seek bar is detected during
     * the 2-second grace window, the delay is cancelled and unmute fires
     * immediately rather than waiting for the timer to expire.
     *
     * @return true if at least one seek-bar node is found in the subtree.
     */
    private fun hasSeekBar(node: AccessibilityNodeInfo): Boolean {
        val viewId    = node.viewIdResourceName?.lowercase() ?: ""
        val className = node.className?.toString() ?: ""

        if (className == "android.widget.SeekBar" ||
            viewId.contains("time_bar") ||
            viewId.contains("seek")) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (hasSeekBar(child)) return true
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return false
    }

    // (hasNextOrPreviousButtons removed — see handleYouTubeEvent for rationale)

    // ═════════════════════════════════════════════════════════════════════════
    //  Skip Button Detection & Click
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Recursively searches the view tree for a Skip button and clicks it.
     *
     * YouTube's skip button may be a non-clickable TextView inside a clickable
     * container. Strategy:
     *  1. DFS for any node whose text or contentDescription matches a skip
     *     pattern (longest patterns checked first to avoid false positives).
     *  2. If the matched node is clickable → click it directly.
     *  3. If not clickable → walk up the parent chain to find a clickable
     *     ancestor and click that.
     *  4. All intermediate nodes retrieved via getParent() are recycled.
     *  5. Ad-skipped stat is committed only if ACTION_CLICK returns true.
     *
     * @return true if a Skip button was found AND clicked successfully.
     */
    private fun findAndClickSkipButton(node: AccessibilityNodeInfo): Boolean {
        val nodeText = node.text?.toString()?.lowercase() ?: ""
        val nodeDesc = node.contentDescription?.toString()?.lowercase() ?: ""

        for (pattern in SKIP_TEXT_PATTERNS) {
            if (nodeText.contains(pattern) || nodeDesc.contains(pattern)) {
                Log.d(TAG, "YouTube: Skip text found — text='$nodeText' desc='$nodeDesc'")
                if (clickNodeOrClickableParent(node)) return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (findAndClickSkipButton(child)) return true
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return false
    }

    /**
     * Attempts to perform ACTION_CLICK on [node]. If [node] is not clickable,
     * walks up its parent hierarchy until a clickable ancestor is found.
     *
     * Every [AccessibilityNodeInfo] retrieved via [AccessibilityNodeInfo.getParent]
     * is recycled after evaluation to prevent memory leaks.
     *
     * @return true if ACTION_CLICK was performed successfully on any node.
     */
    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo): Boolean {
        // Fast path: the text node itself is clickable
        if (node.isClickable) {
            Log.d(TAG, "YouTube: Skip node is clickable — clicking directly")
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) StatsManager.incrementSkipped(applicationContext, StatsManager.APP_YOUTUBE)
            return clicked
        }

        // Slow path: traverse up the parent chain
        Log.d(TAG, "YouTube: Skip node not clickable — searching parent chain")
        var current: AccessibilityNodeInfo? = node.parent

        while (current != null) {
            if (current.isClickable) {
                Log.d(TAG, "YouTube: Clickable parent found (${current.className}) — clicking")
                val clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) StatsManager.incrementSkipped(applicationContext, StatsManager.APP_YOUTUBE)
                @Suppress("DEPRECATION")
                current.recycle()
                return clicked
            }
            val next = current.parent
            @Suppress("DEPRECATION")
            current.recycle()
            current = next
        }

        Log.w(TAG, "YouTube: No clickable ancestor found for Skip text")
        return false
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Meta (Facebook / Instagram) Feed Ad Detection
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Handles a Meta (Facebook / Instagram) accessibility event.
     *
     * Both apps label in-feed ad posts with a "Sponsored" text node. Because
     * both apps auto-play video in the feed, muting the media stream while
     * "Sponsored" is on screen effectively silences the ad audio.
     *
     * Strategy:
     *  1. Scan the entire view hierarchy for a "Sponsored" text node.
     *  2. Found → MUTE (first detection triggers stats increment with 15s debounce).
     *  3. Not found → UNMUTE (user scrolled past the ad).
     *
     * @param rootNode Root of the active Meta app window. This method recycles it.
     * @param pkg      The foreground package name (for logging and stat keys).
     */
    private fun handleMetaFeedEvent(rootNode: AccessibilityNodeInfo, pkg: String) {
        try {
            val adFound = findMetaAdText(rootNode)

            if (adFound) {
                if (!isMetaAdPlaying) {
                    val appLabel = if (pkg == PKG_FACEBOOK) "Facebook" else "Instagram"
                    val statsKey = if (pkg == PKG_FACEBOOK) StatsManager.APP_FACEBOOK
                                  else StatsManager.APP_INSTAGRAM
                    Log.i(TAG, "$appLabel: Ad detected — MUTING")

                    val now = System.currentTimeMillis()
                    if (now - lastMetaMuteTime > 15_000L) {
                        StatsManager.incrementMuted(applicationContext, statsKey)
                        lastMetaMuteTime = now
                    }
                }
                isMetaAdPlaying = true
                muteMedia()
            } else {
                if (isMetaAdPlaying) {
                    val appLabel = if (pkg == PKG_FACEBOOK) "Facebook" else "Instagram"
                    Log.i(TAG, "$appLabel: Ad ended — UNMUTING")
                }
                isMetaAdPlaying = false
                unmuteMedia(reason = "meta ad scrolled away")
            }
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }
    }

    /**
     * Recursively scans the view hierarchy for Meta ad text triggers.
     *
     * Uses contains-based matching (not exact-match) to catch timestamped
     * ad label formats that Meta uses in Reels and in-feed videos, e.g.:
     *  - "0:02 · Ad"   → matched by `contains("\u00b7 Ad")`  (middle-dot U+00B7)
     *  - "Sponsored"   → matched by `contains("sponsored")`
     *  - "Reel continues after the ad" → matched by contains
     *
     * Checks both [AccessibilityNodeInfo.getText] and
     * [AccessibilityNodeInfo.getContentDescription], both case-insensitively.
     *
     * The `· Ad` pattern deliberately includes the middle-dot and a space so
     * that ordinary words containing "ad" ("add", "bad", "load") are never
     * matched, preventing false positives.
     *
     * @return true if a Meta ad label is found anywhere in the subtree.
     */
    private fun findMetaAdText(node: AccessibilityNodeInfo): Boolean {
        val rawText = node.text?.toString() ?: ""
        val rawDesc = node.contentDescription?.toString() ?: ""

        if (isMetaAdString(rawText) || isMetaAdString(rawDesc)) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (findMetaAdText(child)) return true
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return false
    }

    /**
     * Returns true if [s] contains any known Meta ad label pattern.
     *
     * All comparisons are case-insensitive. The `\u00b7 Ad` pattern (middle-dot
     * followed by space and "Ad") targets the timestamp-prefixed format used by
     * Meta Reels ads, e.g. "0:02 · Ad". The space after the dot ensures ordinary
     * text ("ready", "badly") is never matched.
     */
    private fun isMetaAdString(s: String): Boolean {
        if (s.isEmpty()) return false
        val lower = s.lowercase()
        return lower.contains("\u00b7 ad") ||
               lower.contains("sponsored") ||
               lower.contains("reel continues after the ad")
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Volume Control
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Mutes the media stream by setting STREAM_MUSIC volume to 0.
     *
     * The [isCurrentlyMuted] guard prevents redundant calls — without it,
     * every accessibility event during a 30-second ad would call
     * setStreamVolume(), causing wasteful binder calls and audible "pops".
     *
     * Side effects:
     *  - Saves the current volume to [savedVolume] for later restoration.
     *  - Records [currentAdStartTime] for time-saved calculation.
     *  - Schedules [failsafeRunnable] to auto-unmute after [FAILSAFE_UNMUTE_MS].
     */
    private fun muteMedia() {
        if (isCurrentlyMuted) return

        try {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            // If the user has already manually muted (volume == 0), store a
            // sensible default (~60% of max) so unmute doesn't restore silence.
            savedVolume = if (currentVolume > 0) currentVolume else {
                (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.6).toInt()
            }

            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            isCurrentlyMuted    = true
            currentAdStartTime  = System.currentTimeMillis()

            Log.d(TAG, "MUTED — savedVolume=$savedVolume")

            // Schedule the failsafe: auto-unmute if we somehow miss the ad-end signal
            handler.removeCallbacks(failsafeRunnable)
            handler.postDelayed(failsafeRunnable, FAILSAFE_UNMUTE_MS)
        } catch (e: Exception) {
            Log.e(TAG, "muteMedia failed", e)
        }
    }

    /**
     * Restores the media stream to [savedVolume].
     *
     * The [isCurrentlyMuted] guard prevents no-op calls from being expensive.
     * Cancels the failsafe timer on success.
     *
     * @param reason  Short label for logcat (e.g. "ad ended", "failsafe").
     * @param appKey  Optional: app key for future per-app stat hooks.
     */
    private fun unmuteMedia(reason: String, appKey: String? = null) {
        if (!isCurrentlyMuted) return

        try {
            if (savedVolume > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
            }
            isCurrentlyMuted = false
            handler.removeCallbacks(failsafeRunnable)

            Log.d(TAG, "UNMUTED — reason=$reason, restoredVolume=$savedVolume")
        } catch (e: Exception) {
            Log.e(TAG, "unmuteMedia failed", e)
        }
    }
}
