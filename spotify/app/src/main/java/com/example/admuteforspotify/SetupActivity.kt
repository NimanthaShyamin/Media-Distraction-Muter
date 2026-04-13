package com.example.admuteforspotify

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * One-time onboarding screen shown whenever a required permission is missing.
 * Validates three production-grade permissions before enabling "Finish Setup":
 *   1. Notification Listener access — required to detect Spotify media session changes.
 *   2. Unrestricted Battery (ignore battery optimizations) — prevents Android from
 *      killing the background service on sideloaded/non-Play builds.
 *   3. Do Not Disturb access — required to legally change device volume.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var chipNotification: TextView
    private lateinit var chipBattery: TextView
    private lateinit var chipDnd: TextView
    private lateinit var btnContinue: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        chipNotification = findViewById(R.id.chipNotification)
        chipBattery      = findViewById(R.id.chipBattery)
        chipDnd          = findViewById(R.id.chipDnd)
        btnContinue      = findViewById(R.id.btnContinue)

        // ── 1. Notification Listener Access ───────────────────────────────────
        // Required to read Spotify's MediaSession from the notification shade.
        findViewById<LinearLayout>(R.id.rowNotification).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // ── 2. Unrestricted Battery ────────────────────────────────────────────
        // Requests that Android add this package to the battery-optimization whitelist.
        // Without this, the OS will kill the service on sideloaded builds within minutes.
        findViewById<LinearLayout>(R.id.rowBattery).setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback: open the general battery optimization list
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) { /* not available on all OEMs */ }
            }
        }

        // ── 3. Do Not Disturb Access ───────────────────────────────────────────
        // Required to change the ringer / media volume without user interaction.
        findViewById<LinearLayout>(R.id.rowDnd).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }

        // ── Finish Setup button ────────────────────────────────────────────────
        btnContinue.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    // ── Permission checks ─────────────────────────────────────────────────────

    /** True when this app's NotificationListenerService is enabled in system settings. */
    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val component = ComponentName(this, AdMuteService::class.java).flattenToString()
        return flat.contains(component)
    }

    /** True when Android will NOT kill this app due to battery optimizations (whitelist check). */
    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** True when this app has been granted Do Not Disturb policy access. */
    private fun isDndGranted(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    // ── UI refresh ────────────────────────────────────────────────────────────

    private fun refreshPermissionState() {
        val notifGranted   = isNotificationListenerEnabled()
        val batteryGranted = isBatteryOptimizationIgnored()
        val dndGranted     = isDndGranted()

        updateChip(chipNotification, notifGranted,   required = true)
        updateChip(chipBattery,      batteryGranted, required = true)
        updateChip(chipDnd,          dndGranted,     required = true)

        // All three must be granted before allowing the user to proceed.
        val allRequired = notifGranted && batteryGranted && dndGranted
        btnContinue.isEnabled = allRequired
        btnContinue.alpha = if (allRequired) 1f else 0.4f
    }

    private fun updateChip(chip: TextView, granted: Boolean, required: Boolean) {
        if (granted) {
            chip.text = getString(R.string.setup_status_granted)
            chip.setTextColor(Color.parseColor("#121212"))
            chip.setBackgroundColor(Color.parseColor("#39FF14"))
        } else {
            chip.text = if (required) getString(R.string.setup_status_required)
                        else getString(R.string.setup_status_optional)
            chip.setTextColor(Color.parseColor("#121212"))
            chip.setBackgroundColor(Color.parseColor("#FF5252"))
        }
    }
}
