package com.example.admuteforspotify

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralised SharedPreferences helper.
 *
 * Storage schema (key → type):
 *   mute_count_v2     → Int   — total number of mute events (increment-only counter)
 *   mute_history_v2   → Set<String> — serialised MuteEvent entries: "$timestampMs:$durationMs"
 *   boot_enabled      → Boolean — whether BootReceiver should auto-start the service
 *
 * The legacy "MuteCount" and "MuteHistory" keys (formatted strings) are intentionally ignored.
 */
class PrefsHelper(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Mute Events ────────────────────────────────────────────────────────────

    /**
     * Persist a new mute event.  Call from any thread — edit().apply() is thread-safe.
     */
    fun saveMuteEvent(startMs: Long, durationMs: Long) {
        val current = prefs.getStringSet(KEY_HISTORY, mutableSetOf())?.toMutableSet()
            ?: mutableSetOf()
        current.add("$startMs:$durationMs")
        prefs.edit()
            .putStringSet(KEY_HISTORY, current)
            .putInt(KEY_COUNT, prefs.getInt(KEY_COUNT, 0) + 1)
            .apply()
    }

    /**
     * Returns all mute events sorted newest-first.
     */
    fun getMuteEvents(): List<MuteEvent> {
        val raw = prefs.getStringSet(KEY_HISTORY, emptySet()) ?: emptySet()
        return raw.mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                val ts = parts[0].toLongOrNull()
                val dur = parts[1].toLongOrNull()
                if (ts != null && dur != null) MuteEvent(ts, dur) else null
            } else null
        }.sortedByDescending { it.timestampMs }
    }

    fun getMuteCount(): Int = prefs.getInt(KEY_COUNT, 0)

    // ── Settings ───────────────────────────────────────────────────────────────

    fun isBootReceiverEnabled(): Boolean = prefs.getBoolean(KEY_BOOT, true)

    fun setBootReceiverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BOOT, enabled).apply()
    }

    fun isMasterAppEnabled(): Boolean = prefs.getBoolean(KEY_MASTER_ENABLE, true)

    fun setMasterAppEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MASTER_ENABLE, enabled).apply()
    }

    fun isAppEnabled(appKey: String): Boolean = prefs.getBoolean("${KEY_APP_ENABLE_PREFIX}$appKey", true)

    fun setAppEnabled(appKey: String, enabled: Boolean) {
        prefs.edit().putBoolean("${KEY_APP_ENABLE_PREFIX}$appKey", enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "AdMutePrefs"
        private const val KEY_HISTORY = "mute_history_v2"
        private const val KEY_COUNT = "mute_count_v2"
        private const val KEY_BOOT = "boot_enabled"
        private const val KEY_MASTER_ENABLE = "master_app_enable"
        private const val KEY_APP_ENABLE_PREFIX = "app_enable_"
    }
}
