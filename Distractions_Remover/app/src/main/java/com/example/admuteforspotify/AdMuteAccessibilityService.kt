package com.example.admuteforspotify

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AdMuteAccessibilityService — YouTube Ad Detection, Muting & Skipping
 *                             + Meta (Facebook / Instagram) Feed Ad Muting
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * This service monitors target apps via the Accessibility framework to detect,
 * mute, and auto-skip ads.
 *
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │  YouTube Ad Detection Strategy                                             │
 * ├─────────────────────────────────────────────────────────────────────────────┤
 * │  1. SEEK BAR HEURISTIC (Muting):                                          │
 * │     During normal video playback YouTube shows an enabled SeekBar.         │
 * │     During ads, the SeekBar is either completely absent from the view      │
 * │     tree OR present but disabled (isEnabled == false).                     │
 * │     → When seek bar is missing/disabled → MUTE media volume.              │
 * │     → When seek bar reappears enabled  → UNMUTE (restore volume).         │
 * │                                                                            │
 * │  2. SKIP BUTTON HEURISTIC (Skipping):                                     │
 * │     YouTube's skip button text ("Skip", "Skip Ad", "Skip ads") may        │
 * │     live in a non-clickable TextView inside a clickable container.         │
 * │     We DFS for any node matching these patterns (case-insensitive),        │
 * │     then either click it directly (if clickable) or traverse up the        │
 * │     parent chain to find the nearest clickable ancestor and click that.    │
 * ├─────────────────────────────────────────────────────────────────────────────┤
 * │  Meta (Facebook / Instagram) Ad Detection Strategy                         │
 * ├─────────────────────────────────────────────────────────────────────────────┤
 * │  SPONSORED TEXT HEURISTIC:                                                 │
 * │     Both Facebook and Instagram label in-feed ads with a "Sponsored"       │
 * │     text node. Because these apps auto-play videos in the feed:            │
 * │     → If "Sponsored" text is visible anywhere in the hierarchy → MUTE.    │
 * │     → When the user scrolls past and it disappears            → UNMUTE.   │
 * └─────────────────────────────────────────────────────────────────────────────┘
 *
 * Battery optimizations:
 *  1. XML config restricts event sources to only four target packages.
 *  2. XML config limits event types to window-level changes only.
 *  3. XML config sets a 500ms debounce via notificationTimeout.
 *  4. Early bailout: skip processing if screen is off or package doesn't match.
 *  5. State tracking: boolean flags prevent redundant mute/unmute system calls.
 *  6. Failsafe timer: auto-unmutes after 5 minutes to prevent stuck-muted state.
 */
class AdMuteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AdMuteA11yService"

        /** YouTube's package name. */
        private const val PKG_YOUTUBE = "com.google.android.youtube"

        // ── Meta app package names ──────────────────────────────────────
        private const val PKG_FACEBOOK  = "com.facebook.katana"
        private const val PKG_INSTAGRAM = "com.instagram.android"

        /** Spotify's package name. */
        private const val PKG_SPOTIFY = "com.spotify.music"

        /**
         * The exact set of packages we care about. Stored as a HashSet for O(1)
         * lookup in the hot path of onAccessibilityEvent.
         */
        private val TARGET_PACKAGES: Set<String> = hashSetOf(
            PKG_YOUTUBE,                    // YouTube
            "com.facebook.katana",          // Facebook
            "com.instagram.android",        // Instagram
            "com.spotify.music"             // Spotify
        )

        /**
         * Skip button text patterns (case-insensitive).
         * YouTube uses various labels across locales and ad types:
         *  - "Skip"      (generic)
         *  - "Skip Ad"   (single ad)
         *  - "Skip ads"  (ad pod / multiple ads)
         *  - "Skip Ads"  (capitalised variant)
         */
        private val SKIP_TEXT_PATTERNS: List<String> = listOf(
            "skip ads",
            "skip ad",
            "skip"
        )

        /**
         * Failsafe timeout: if we've been muted for this long, something went
         * wrong (e.g. YouTube UI changed, user left the app). Auto-unmute to
         * prevent the device from being stuck on silent.
         */
        private const val FAILSAFE_UNMUTE_MS = 5 * 60 * 1000L  // 5 minutes

        /** Lightweight static flag so other components can check service state. */
        @Volatile
        var isServiceConnected: Boolean = false
            private set
    }

    // ── System services ─────────────────────────────────────────────────────

    /** PowerManager used to check screen-on state for the early bailout. */
    private val powerManager: PowerManager by lazy {
        getSystemService(POWER_SERVICE) as PowerManager
    }

    /** AudioManager used to control media volume for muting/unmuting. */
    private lateinit var audioManager: AudioManager

    // ── State tracking ──────────────────────────────────────────────────────
    // These booleans prevent redundant system calls. Without them, every
    // accessibility event (fired every 500ms during an ad) would trigger a
    // setStreamVolume() call — wasteful and causes audible volume "pops".

    // ── YouTube state ────────────────────────────────────────────────────
    /** True when we believe a YouTube ad is currently playing. */
    private var isYouTubeAdPlaying: Boolean = false

    /** True when we have actively muted the media stream. */
    private var isCurrentlyMuted: Boolean = false

    /**
     * The user's media volume before we muted. Restored when the ad ends.
     * Defaults to a safe mid-range value in case we can't read it.
     */
    private var savedVolume: Int = -1

    // ── Meta (Facebook / Instagram) state ────────────────────────────────
    /**
     * True when we believe a Meta feed ad is currently visible ("Sponsored"
     * text found in the view hierarchy). Tracked independently from
     * [isYouTubeAdPlaying] so the two strategies don't interfere.
     */
    private var isMetaAdPlaying: Boolean = false

    // ── Spotify state ────────────────────────────────────────────────────
    /** True when we believe a Spotify ad is currently playing. */
    private var isSpotifyAdPlaying: Boolean = false

    private var currentAdStartTime: Long = 0L
    private var lastSeenPackage: String? = null

    // ── Timestamps for debouncing counters ──────────────────────────────────
    private var lastYouTubeMuteTime = 0L
    private var lastYouTubeSkipTime = 0L
    private var lastMetaMuteTime = 0L
    private var lastSpotifyMuteTime = 0L

    // ── Failsafe timer ──────────────────────────────────────────────────────
    // Ensures we never leave the device muted indefinitely if something goes
    // wrong (e.g. user force-closes YouTube while an ad is playing).

    private val handler = Handler(Looper.getMainLooper())
    private val failsafeRunnable = Runnable {
        Log.w(TAG, "Failsafe triggered — unmuting after ${FAILSAFE_UNMUTE_MS / 1000}s")
        unmuteMedia(reason = "failsafe")
    }

    // ── Lifecycle callbacks ─────────────────────────────────────────────────

    /**
     * Called when the system successfully binds to this service.
     *
     * We initialise the AudioManager here and programmatically confirm our
     * XML configuration as a safety net (guards against stale cached config
     * after OTA updates).
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected = true

        // Initialise AudioManager for volume control.
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Programmatic safety net: confirm the XML config was applied correctly.
        serviceInfo = serviceInfo.apply {
            eventTypes = (AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 500L
            // Flags: request view IDs and include non-important views so we
            // can find SeekBars and skip buttons deep in the hierarchy.
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }

        Log.i(TAG, "Service connected — monitoring ${TARGET_PACKAGES.size} target packages")
    }

    /**
     * Core event handler — the hottest path in this service.
     *
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │  Flow:                                                             │
     * │  1. Early bailout (screen off / wrong package)                     │
     * │  2. Route to app-specific handler based on package name            │
     * │  3. YouTube handler: scan hierarchy → mute/skip/unmute             │
     * └─────────────────────────────────────────────────────────────────────┘
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!powerManager.isInteractive) return

        val prefsHelper = PrefsHelper(applicationContext)
        if (!prefsHelper.isMasterAppEnabled()) {
            AdMuteService.instance?.updateNotification("Service Disabled")
            return
        }

        val pkg = event.packageName?.toString() ?: return

        // ── Fix App Closing / Notification Stuck Bug ─────────────
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg !in TARGET_PACKAGES) {
                lastSeenPackage = pkg
                isYouTubeAdPlaying = false
                isMetaAdPlaying = false
                isSpotifyAdPlaying = false
                isCurrentlyMuted = false
                unmuteMedia(reason = "app switched to non-target")
                AdMuteService.instance?.updateNotification("Sleeping to save battery")
                return
            }
        }

        if (pkg !in TARGET_PACKAGES) return

        // ── App-specific checks and toggles ─────────────
        when (pkg) {
            PKG_YOUTUBE -> {
                if (!prefsHelper.isAppEnabled(StatsManager.APP_YOUTUBE)) {
                    if (lastSeenPackage != "youtube_ignored") {
                        AdMuteService.instance?.updateNotification("YouTube ignored")
                        lastSeenPackage = "youtube_ignored"
                    }
                    return
                } else if (lastSeenPackage != PKG_YOUTUBE) {
                    AdMuteService.instance?.updateNotification("Monitoring YouTube")
                    lastSeenPackage = PKG_YOUTUBE
                }
                handleYouTubeEvent(event)
            }
            PKG_FACEBOOK, PKG_INSTAGRAM -> {
                val appKey = if (pkg == PKG_FACEBOOK) StatsManager.APP_FACEBOOK else StatsManager.APP_INSTAGRAM
                val appName = if (pkg == PKG_FACEBOOK) "Facebook" else "Instagram"
                if (!prefsHelper.isAppEnabled(appKey)) {
                    val ignoredKey = "${appName.lowercase()}_ignored"
                    if (lastSeenPackage != ignoredKey) {
                        AdMuteService.instance?.updateNotification("$appName ignored")
                        lastSeenPackage = ignoredKey
                    }
                    return
                } else if (lastSeenPackage != pkg) {
                    AdMuteService.instance?.updateNotification("Monitoring $appName")
                    lastSeenPackage = pkg
                }
                handleMetaFeedEvent(event, pkg)
            }
            PKG_SPOTIFY -> {
                if (!prefsHelper.isAppEnabled(StatsManager.APP_SPOTIFY)) {
                    if (lastSeenPackage != "spotify_ignored") {
                        AdMuteService.instance?.updateNotification("Spotify ignored")
                        lastSeenPackage = "spotify_ignored"
                    }
                    return
                } else if (lastSeenPackage != PKG_SPOTIFY) {
                    AdMuteService.instance?.updateNotification("Monitoring Spotify")
                    lastSeenPackage = PKG_SPOTIFY
                }
                handleSpotifyEvent(event)
            }
        }
    }

    /**
     * Called when the system wants to interrupt feedback from this service.
     * Since we don't produce auditory/haptic feedback ourselves, this is a
     * no-op. We log it for diagnostics.
     */
    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted by system")
    }

    /**
     * Called when the service is being shut down.
     * Clean up: cancel failsafe, unmute if needed, reset state.
     */
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(failsafeRunnable)
        // Safety: never leave the device muted when we die.
        unmuteMedia(reason = "service destroyed")
        isServiceConnected = false
        Log.i(TAG, "Service destroyed")
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  YouTube Ad Detection Logic
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Handles a single accessibility event from the YouTube app.
     *
     * Strategy:
     *  1. Get the root node of the active window.
     *  2. Scan for "Skip Ad" buttons → auto-click if found.
     *  3. Scan for a SeekBar to determine ad vs. normal playback.
     *     - SeekBar missing OR disabled → ad is playing → MUTE.
     *     - SeekBar present AND enabled → normal video  → UNMUTE.
     */
    private fun handleYouTubeEvent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return

        try {
            val didSkip = tryClickSkipButton(rootNode)
            if (didSkip) {
                Log.i(TAG, "YouTube: Auto-skipped ad")
                val now = System.currentTimeMillis()
                if (now - lastYouTubeSkipTime > 15000) {
                    StatsManager.incrementSkipped(applicationContext, StatsManager.APP_YOUTUBE)
                    lastYouTubeSkipTime = now
                }
                unmuteMedia(reason = "ad skipped")
                return
            }

            // Crucial Fix: Verify video player is active before checking SeekBar
            val videoPlayerActive = isVideoPlayerActive(rootNode)
            if (!videoPlayerActive) {
                // DO NOT mute if main player is not visible
                if (isYouTubeAdPlaying) {
                    isYouTubeAdPlaying = false
                    unmuteMedia(reason = "player not visible")
                }
                return
            }
            
            val seekBarState = findSeekBarState(rootNode)
            
            when (seekBarState) {
                SeekBarState.MISSING, SeekBarState.DISABLED -> {
                    if (!isYouTubeAdPlaying) {
                        Log.i(TAG, "YouTube: Ad detected (seekBar=$seekBarState)")
                        isYouTubeAdPlaying = true
                        muteMedia()
                        
                        val now = System.currentTimeMillis()
                        if (now - lastYouTubeMuteTime > 15000) {
                            StatsManager.incrementMuted(applicationContext, StatsManager.APP_YOUTUBE)
                            lastYouTubeMuteTime = now
                        }
                    }
                }
                SeekBarState.ENABLED -> {
                    if (isYouTubeAdPlaying) {
                        Log.i(TAG, "YouTube: Ad ended (seekBar re-enabled)")
                        isYouTubeAdPlaying = false
                        unmuteMedia(reason = "ad ended", appKey = StatsManager.APP_YOUTUBE)
                    }
                }
            }
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }
    }

    // ── YouTube Hybrid Heuristics ───────────────────────────────────────────

    private enum class SeekBarState { MISSING, DISABLED, ENABLED }

    private fun findSeekBarState(node: AccessibilityNodeInfo): SeekBarState {
        return findSeekBarStateRecursive(node) ?: SeekBarState.MISSING
    }

    private fun findSeekBarStateRecursive(node: AccessibilityNodeInfo): SeekBarState? {
        val className = node.className?.toString() ?: ""
        if (className == "android.widget.SeekBar") {
            return if (node.isEnabled) SeekBarState.ENABLED else SeekBarState.DISABLED
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val result = findSeekBarStateRecursive(child)
                if (result != null) return result
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return null
    }

    private fun isVideoPlayerActive(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (viewId.contains("player_view") || viewId.contains("video_player") || viewId.contains("player_fragment")) {
            return true
        }
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.contains("play video") || 
            desc.contains("pause video") || 
            desc.contains("fast forward") || 
            desc.contains("rewind")) {
            return true
        }
        val text = node.text?.toString()?.lowercase() ?: ""
        if (text.contains("0:00")) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (isVideoPlayerActive(child)) return true
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return false
    }

    // ── Skip button detection ───────────────────────────────────────────────

    /**
     * Scans the view tree for a "Skip Ad" button and clicks it.
     *
     * YouTube's skip button text (e.g. "Skip", "Skip Ad", "Skip ads") may
     * live in a non-clickable TextView nested inside a clickable container.
     * The old approach only checked nodes that were already clickable,
     * missing this common layout.
     *
     * New strategy:
     *  1. DFS for ANY node whose text/contentDescription matches a skip
     *     pattern (case-insensitive).
     *  2. If the matched node itself is clickable → click it directly.
     *  3. If NOT clickable → traverse UP the parent chain until we find
     *     a clickable ancestor, then click that.
     *  4. All intermediate AccessibilityNodeInfo objects obtained via
     *     getParent() are recycled to prevent memory leaks.
     *
     * @param rootNode The root of the view tree to search.
     * @return true if a skip button was found AND successfully clicked.
     */
    private fun tryClickSkipButton(rootNode: AccessibilityNodeInfo): Boolean {
        return findAndClickSkipButton(rootNode)
    }

    /**
     * Recursive DFS search for a node whose text matches a skip pattern.
     *
     * Unlike the previous version, this does NOT require the text node
     * itself to be clickable. When a text match is found, we delegate to
     * [clickNodeOrClickableParent] which handles the parent traversal.
     */
    private fun findAndClickSkipButton(node: AccessibilityNodeInfo): Boolean {
        // ── Check this node's text / contentDescription ─────────────────
        val nodeText = node.text?.toString()?.lowercase() ?: ""
        val nodeDesc = node.contentDescription?.toString()?.lowercase() ?: ""

        // Match against our known skip patterns.
        // Longest patterns are checked first ("skip ads") to avoid
        // false positives on generic "skip" text in unrelated UI.
        for (pattern in SKIP_TEXT_PATTERNS) {
            if (nodeText.contains(pattern) || nodeDesc.contains(pattern)) {
                Log.d(TAG, "YouTube: Found skip text — text='$nodeText' " +
                        "desc='$nodeDesc' clickable=${node.isClickable}")

                // Attempt to click: either this node or a clickable ancestor.
                if (clickNodeOrClickableParent(node)) {
                    return true
                }
            }
        }

        // ── Recurse into children ───────────────────────────────────────
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
     * Clicks [node] if it is clickable, otherwise walks up the parent
     * hierarchy to find the nearest clickable ancestor and clicks that.
     *
     * This handles YouTube's common layout where a "Skip" TextView sits
     * inside a non-clickable wrapper, which itself sits inside a clickable
     * FrameLayout or ViewGroup.
     *
     * Memory safety: every [AccessibilityNodeInfo] obtained via
     * [AccessibilityNodeInfo.getParent] is recycled after evaluation,
     * including the one we ultimately click (after the click is performed).
     *
     * @param node The starting node (the one containing the skip text).
     * @return true if a clickable node was found and ACTION_CLICK performed.
     */
    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo): Boolean {
        // ── Fast path: the text node itself is clickable ────────────────
        if (node.isClickable) {
            Log.d(TAG, "YouTube: Skip text node is clickable — clicking directly")
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }

        // ── Slow path: traverse up to find a clickable parent ───────────
        // We keep a reference to the "current" parent so we can recycle it
        // once we move to the next ancestor (or after clicking).
        Log.d(TAG, "YouTube: Skip text node is NOT clickable — searching parent chain")
        var currentParent: AccessibilityNodeInfo? = node.parent

        while (currentParent != null) {
            if (currentParent.isClickable) {
                Log.d(TAG, "YouTube: Found clickable parent — " +
                        "class=${currentParent.className} — clicking")
                currentParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                // Recycle the parent we just clicked; we're done with it.
                @Suppress("DEPRECATION")
                currentParent.recycle()
                return true
            }

            // Move one level up: get grandparent, then recycle the
            // current (non-clickable) parent to prevent leaks.
            val nextParent = currentParent.parent
            @Suppress("DEPRECATION")
            currentParent.recycle()
            currentParent = nextParent
        }

        // Reached the root without finding a clickable ancestor.
        Log.w(TAG, "YouTube: No clickable parent found for skip text node")
        return false
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Meta (Facebook / Instagram) Feed Ad Detection Logic
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Handles a single accessibility event from Facebook or Instagram.
     *
     * Strategy:
     *  1. Get the root node of the active window.
     *  2. Recursively scan the entire visible view hierarchy for any text
     *     node whose content matches "Sponsored" (case-insensitive).
     *  3. If found → an ad post is visible in the feed → MUTE.
     *  4. If not found → user scrolled past the ad → UNMUTE.
     *
     * Because both Meta apps auto-play video in the feed, muting the media
     * stream while "Sponsored" is on screen effectively silences the ad.
     *
     * @param event The accessibility event that triggered this handler.
     * @param pkg   The source package name (for logging clarity).
     */
    private fun handleMetaFeedEvent(event: AccessibilityEvent, pkg: String) {
        val rootNode = rootInActiveWindow ?: return

        try {
            val sponsoredFound = findSponsoredText(rootNode)

            if (sponsoredFound) {
                // "Sponsored" label is on screen → ad post is visible.
                if (!isMetaAdPlaying) {
                    val appLabel = if (pkg == PKG_FACEBOOK) "Facebook" else "Instagram"
                    val statsKey = if (pkg == PKG_FACEBOOK) StatsManager.APP_FACEBOOK
                                  else StatsManager.APP_INSTAGRAM
                    Log.i(TAG, "$appLabel: Ad detected (\"Sponsored\" text found in feed)")
                    val now = System.currentTimeMillis()
                    if (now - lastMetaMuteTime > 15000) {
                        StatsManager.incrementMuted(applicationContext, statsKey)
                        lastMetaMuteTime = now
                    }
                }
                isMetaAdPlaying = true
                muteMedia()
            } else {
                // No "Sponsored" text anywhere → user scrolled past the ad.
                if (isMetaAdPlaying) {
                    val appLabel = if (pkg == PKG_FACEBOOK) "Facebook" else "Instagram"
                    Log.i(TAG, "$appLabel: Ad ended (\"Sponsored\" text no longer visible)")
                }
                isMetaAdPlaying = false
                unmuteMedia(reason = "meta ad scrolled away")
            }
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }
    }

    // ── Sponsored text detection ────────────────────────────────────────────

    /**
     * Recursively scans the view hierarchy for a text node matching
     * "Sponsored" (case-insensitive, exact match).
     *
     * Both Facebook and Instagram use this label on in-feed ad posts.
     * The match is case-insensitive to handle any capitalisation
     * variations ("Sponsored", "SPONSORED", etc.).
     *
     * @param node The root of the subtree to search.
     * @return true if a "Sponsored" text node was found anywhere in the tree.
     */
    private fun findSponsoredText(node: AccessibilityNodeInfo): Boolean {
        // ── Check this node's text and content description ──────────────
        val nodeText = node.text?.toString() ?: ""
        val nodeDesc = node.contentDescription?.toString() ?: ""

        if (nodeText.equals("Sponsored", ignoreCase = true) ||
            nodeDesc.equals("Sponsored", ignoreCase = true)) {
            return true
        }

        // ── Recurse into children ───────────────────────────────────────
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (findSponsoredText(child)) return true  // Found — bubble up.
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }

        return false  // Not found in this subtree.
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Volume Control
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Mutes the media stream by saving the current volume and setting it to 0.
     *
     * The [isCurrentlyMuted] flag prevents redundant calls — without it, every
     * accessibility event during a 30-second ad would call setStreamVolume(),
     * which is wasteful and can cause audible "pops" on some devices.
     */
    private fun muteMedia() {
        if (isCurrentlyMuted) return  // Already muted — no-op.

        try {
            // Save the user's current volume so we can restore it exactly.
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            // Guard: if volume is already 0 (user manually muted), save a
            // sensible default so we don't "restore" to silence.
            savedVolume = if (currentVolume > 0) currentVolume else {
                // Use ~60% of max as a safe fallback.
                (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.6).toInt()
            }

            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            isCurrentlyMuted = true
            currentAdStartTime = System.currentTimeMillis()

            Log.d(TAG, "MUTED media — savedVolume=$savedVolume")

            // Schedule failsafe: auto-unmute after FAILSAFE_UNMUTE_MS to prevent
            // the device from being stuck muted if we miss the "ad ended" signal.
            handler.removeCallbacks(failsafeRunnable)
            handler.postDelayed(failsafeRunnable, FAILSAFE_UNMUTE_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mute media stream", e)
        }
    }

    /**
     * Restores the media stream to its previously saved volume.
     *
     * @param reason A short label for log clarity (e.g. "ad ended", "failsafe").
     * @param appKey The app that was muted (so we can save duration), if known.
     */
    private fun unmuteMedia(reason: String, appKey: String? = null) {
        if (!isCurrentlyMuted) return

        try {
            if (savedVolume > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
            }
            isCurrentlyMuted = false

            handler.removeCallbacks(failsafeRunnable)

            Log.d(TAG, "UNMUTED media — reason=$reason, restoredVolume=$savedVolume")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unmute media stream", e)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Spotify Ad Detection Logic
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Handles accessibility events for Spotify.
     * Searches for the text "Advertisement" / "Sponsored", or unclickable
     * Next/Previous track buttons (which indicates an ad is playing).
     */
    private fun handleSpotifyEvent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return

        try {
            val isAd = isSpotifyAdActive(rootNode)

            if (isAd) {
                if (!isSpotifyAdPlaying) {
                    Log.i(TAG, "Spotify: Ad detected")
                    val now = System.currentTimeMillis()
                    if (now - lastSpotifyMuteTime > 15000) {
                        StatsManager.incrementMuted(applicationContext, StatsManager.APP_SPOTIFY)
                        lastSpotifyMuteTime = now
                    }
                    isSpotifyAdPlaying = true
                    muteMedia()
                }
            } else {
                if (isSpotifyAdPlaying) {
                    Log.i(TAG, "Spotify: Ad ended")
                    isSpotifyAdPlaying = false
                    unmuteMedia(reason = "ad ended", appKey = StatsManager.APP_SPOTIFY)
                }
            }
        } finally {
            @Suppress("DEPRECATION")
            rootNode.recycle()
        }
    }

    private fun isSpotifyAdActive(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (text == "advertisement" || text == "sponsored" || desc == "advertisement" || desc == "sponsored") {
            return true
        }

        // Check for unclickable Next/Previous buttons
        if (desc.contains("next") || desc.contains("previous")) {
            if (!node.isClickable && !node.isEnabled) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (isSpotifyAdActive(child)) return true
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }

        return false
    }
}
