package com.example.admuteforspotify

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * StatsManager — Centralised ad-mute statistics tracking.
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * Stores per-app counters (muted / skipped) in a dedicated SharedPreferences
 * file ("ad_mute_stats"), separate from the existing "AdMutePrefs" used by
 * [PrefsHelper] for settings and mute history.
 *
 * Designed for two consumers:
 *  1. **Services** call [incrementMuted] / [incrementSkipped] from any thread.
 *  2. **Compose UI** collects [statsFlow] to get live counter updates via a
 *     [SharedPreferences.OnSharedPreferenceChangeListener].
 *
 * ┌──────────────────────────────────┬──────────────────────────────────────────┐
 * │  Key pattern                     │  Description                             │
 * ├──────────────────────────────────┼──────────────────────────────────────────┤
 * │  {appKey}_muted_count            │  How many times ads were muted           │
 * │  {appKey}_skipped_count          │  How many times ads were auto-skipped    │
 * └──────────────────────────────────┴──────────────────────────────────────────┘
 *
 * Valid appKeys: "youtube", "facebook", "instagram", "spotify"
 */
object StatsManager {

    private const val PREFS_NAME = "ad_mute_stats"

    // ── Key suffixes ────────────────────────────────────────────────────────
    private const val SUFFIX_MUTED   = "_muted_count"
    private const val SUFFIX_SKIPPED = "_skipped_count"
    private const val SUFFIX_TIME_SAVED = "_time_saved"

    // ── All tracked app keys ────────────────────────────────────────────────
    // Kept as constants so callers don't need to hard-code strings.
    const val APP_YOUTUBE   = "youtube"
    const val APP_FACEBOOK  = "facebook"
    const val APP_INSTAGRAM = "instagram"
    const val APP_SPOTIFY   = "spotify"

    /** All known counter keys — used to build the full stats snapshot. */
    private val ALL_KEYS: List<String> = listOf(
        APP_YOUTUBE, APP_FACEBOOK, APP_INSTAGRAM, APP_SPOTIFY
    ).flatMap { app ->
        listOf("$app$SUFFIX_MUTED", "$app$SUFFIX_SKIPPED", "$app$SUFFIX_TIME_SAVED")
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun mutedKey(appKey: String): String   = "$appKey$SUFFIX_MUTED"
    private fun skippedKey(appKey: String): String  = "$appKey$SUFFIX_SKIPPED"
    private fun timeSavedKey(appKey: String): String = "$appKey$SUFFIX_TIME_SAVED"

    // ── Write API (called from services) ────────────────────────────────────

    /**
     * Atomically increments the "muted" counter for [appKey].
     * Safe to call from any thread — [SharedPreferences.Editor.apply] is async.
     */
    fun incrementMuted(context: Context, appKey: String) {
        val p = prefs(context)
        val key = mutedKey(appKey)
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
    }

    /**
     * Atomically increments the "skipped" counter for [appKey].
     * Safe to call from any thread.
     */
    fun incrementSkipped(context: Context, appKey: String) {
        val p = prefs(context)
        val key = skippedKey(appKey)
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
    }

    /**
     * Adds the ad duration (converted to seconds) to the "time_saved" counter.
     */
    fun addTimeSaved(context: Context, appKey: String, durationMs: Long) {
        val durationSeconds = (durationMs / 1000).toInt()
        if (durationSeconds <= 0) return
        
        val p = prefs(context)
        val key = timeSavedKey(appKey)
        p.edit().putInt(key, p.getInt(key, 0) + durationSeconds).apply()
    }

    // ── Read API (called from UI) ───────────────────────────────────────────

    /** Returns the current muted count for [appKey]. */
    fun getMutedCount(context: Context, appKey: String): Int =
        prefs(context).getInt(mutedKey(appKey), 0)

    /** Returns the current skipped count for [appKey]. */
    fun getSkippedCount(context: Context, appKey: String): Int =
        prefs(context).getInt(skippedKey(appKey), 0)

    /**
     * Returns a snapshot of ALL counters as a `Map<String, Int>`.
     * Keys are the full preference keys (e.g. "youtube_muted_count").
     */
    private fun snapshot(context: Context): Map<String, Int> {
        val p = prefs(context)
        return ALL_KEYS.associateWith { key -> p.getInt(key, 0) }
    }

    // ── Reactive API (Compose integration) ──────────────────────────────────

    /**
     * A cold [Flow] that emits the full stats map every time any counter
     * changes in SharedPreferences.
     *
     * Usage in Compose:
     * ```
     * val stats by StatsManager.statsFlow(context).collectAsStateWithLifecycle(
     *     initialValue = emptyMap()
     * )
     * ```
     *
     * The flow registers a [SharedPreferences.OnSharedPreferenceChangeListener]
     * while collected, and unregisters it on cancellation (lifecycle-aware).
     */
    fun statsFlow(context: Context): Flow<Map<String, Int>> = callbackFlow {
        // Emit the current snapshot immediately so the UI has data on first frame.
        trySend(snapshot(context))

        val prefs = prefs(context)

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            // Any key changed → re-emit the full snapshot.
            // This is cheap (8 getInt calls) and keeps the API simple.
            trySend(snapshot(context))
        }

        prefs.registerOnSharedPreferenceChangeListener(listener)

        // When the Flow collector is cancelled (e.g. composable leaves
        // composition, or lifecycle drops below STARTED), unregister.
        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
}
