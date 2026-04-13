package com.example.admuteforspotify

/**
 * Represents a single ad-mute event recorded by AdMuteService.
 *
 * @param timestampMs Unix timestamp (ms) when the mute started — enables reliable
 *                    chronological sorting and date-group calculations in the UI.
 * @param durationMs  How long the device was muted in milliseconds.
 *                    May be 0 if the failsafe timer fired before a real unmute was detected.
 */
data class MuteEvent(
    val timestampMs: Long,
    val durationMs: Long
)
