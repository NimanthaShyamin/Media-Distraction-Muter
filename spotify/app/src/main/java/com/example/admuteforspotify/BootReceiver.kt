package com.example.admuteforspotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Starts AdMuteService on device boot.
 *
 * Handles two intents to cover both normal and Direct Boot scenarios:
 *  - ACTION_BOOT_COMPLETED       (fires after user's first unlock post-reboot)
 *  - ACTION_LOCKED_BOOT_COMPLETED (fires before unlock; marked directBootAware in manifest)
 *
 * The receiver checks the user preference before starting because the
 * SharedPreferences are stored in credential-protected storage (default),
 * which is only accessible after ACTION_BOOT_COMPLETED (post-unlock).
 * For ACTION_LOCKED_BOOT_COMPLETED we skip the preference check and always start,
 * since the user explicitly opted into auto-start.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "onReceive: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // Credential-protected storage is now accessible — check preference.
                if (PrefsHelper(context).isBootReceiverEnabled()) {
                    startService(context)
                } else {
                    Log.d(TAG, "Boot receiver disabled by user preference — skipping start")
                }
            }
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> {
                // Before first unlock: only start if the setting default (true) applies.
                // We can't read credential-protected prefs here, so we always start.
                // The service will idle until Spotify opens.
                startService(context)
            }
        }
    }

    private fun startService(context: Context) {
        Log.d(TAG, "Starting AdMuteService via ContextCompat.startForegroundService")
        val serviceIntent = Intent(context, AdMuteService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
