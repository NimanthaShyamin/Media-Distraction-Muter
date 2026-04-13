package com.example.admuteforspotify

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Two-step setup wizard:
 *
 *   Step 1 (index 0) — Restricted Settings guide
 *     Educates the user that sideloaded apps must have "Allow restricted settings"
 *     enabled before standard permissions become accessible. Provides a direct
 *     shortcut to the App Info screen and a "I've Done This" gate button.
 *
 *   Step 2 (index 1) — Grant standard permissions
 *     Three tappable rows that each open the relevant system settings screen.
 *     "Finish Setup" only activates once ALL THREE are confirmed.
 *
 * Completion is persisted in SharedPreferences so MainActivity can skip the
 * wizard on subsequent launches.
 */
class SetupActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var flipper: ViewFlipper
    private lateinit var progressStep1: View
    private lateinit var progressStep2: View

    // Step 1
    private lateinit var btnOpenAppSettings: MaterialButton
    private lateinit var btnDoneStep1: MaterialButton

    // Step 2
    private lateinit var chipNotification: TextView
    private lateinit var chipBattery: TextView
    private lateinit var chipDnd: TextView
    private lateinit var btnContinue: MaterialButton

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        // Progress indicator bars
        progressStep1 = findViewById(R.id.progressStep1)
        progressStep2 = findViewById(R.id.progressStep2)

        // ViewFlipper
        flipper = findViewById(R.id.wizardFlipper)

        // ── Step 1 wiring ──────────────────────────────────────────────────────
        btnOpenAppSettings = findViewById(R.id.btnOpenAppSettings)
        btnDoneStep1       = findViewById(R.id.btnDoneStep1)

        /**
         * "Open App Settings" — takes the user directly to this app's App Info
         * screen where the 3-dot ⋮ menu contains "Allow restricted settings".
         */
        btnOpenAppSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        /** "I've Done This →" — slides the wizard forward to Step 2. */
        btnDoneStep1.setOnClickListener {
            showStep(1)
        }

        // ── Step 2 wiring ──────────────────────────────────────────────────────
        chipNotification = findViewById(R.id.chipNotification)
        chipBattery      = findViewById(R.id.chipBattery)
        chipDnd          = findViewById(R.id.chipDnd)
        btnContinue      = findViewById(R.id.btnContinue)

        /** Row 1 — Notification Listener: required to attach to Spotify's MediaSession. */
        findViewById<LinearLayout>(R.id.rowNotification).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        /** Row 2 — Battery optimisation whitelist: stops Android killing sideloaded services. */
        findViewById<LinearLayout>(R.id.rowBattery).setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                // Fallback for devices that don't expose the direct UI
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (_: Exception) { /* OEM variant not available */ }
            }
        }

        /** Row 3 — DND policy access: required to legally change media volume. */
        findViewById<LinearLayout>(R.id.rowDnd).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }

        /**
         * "Finish Setup" — only reachable when all three permissions are granted.
         * Persists completion flag and launches the main dashboard.
         */
        btnContinue.setOnClickListener {
            getSharedPreferences("admute_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("setup_complete", true)
                .apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        // Restore the correct step if the activity is recreated (e.g., rotation)
        val savedStep = savedInstanceState?.getInt("step", 0) ?: 0
        flipper.displayedChild = savedStep
        updateProgressBar(savedStep)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("step", flipper.displayedChild)
    }

    override fun onResume() {
        super.onResume()
        // Always re-check permission state so chips update when the user comes
        // back from a system settings screen.
        if (flipper.displayedChild == 1) {
            refreshPermissionState()
        }
    }

    /** Handle the system Back button — go back to Step 1 instead of exiting. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (flipper.displayedChild > 0) {
            showStep(flipper.displayedChild - 1)
        } else {
            super.onBackPressed()
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun showStep(index: Int) {
        flipper.displayedChild = index
        updateProgressBar(index)
        if (index == 1) refreshPermissionState()
    }

    private fun updateProgressBar(step: Int) {
        val activeColor  = Color.parseColor("#39FF14")
        val inactiveColor = Color.parseColor("#2A2A2A")
        progressStep1.setBackgroundColor(activeColor)  // Step 1 always active once app opens
        progressStep2.setBackgroundColor(if (step >= 1) activeColor else inactiveColor)
    }

    // ── Permission checks ─────────────────────────────────────────────────────

    /** True when this app's NotificationListenerService is registered in the system. */
    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val component = ComponentName(this, AdMuteService::class.java).flattenToString()
        return flat.contains(component)
    }

    /** True when Android has whitelisted this package from battery optimisation kills. */
    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** True when this app holds Do Not Disturb policy access. */
    private fun isDndGranted(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    // ── UI refresh ────────────────────────────────────────────────────────────

    private fun refreshPermissionState() {
        val notifOk   = isNotificationListenerEnabled()
        val batteryOk = isBatteryOptimizationIgnored()
        val dndOk     = isDndGranted()

        updateChip(chipNotification, notifOk)
        updateChip(chipBattery,      batteryOk)
        updateChip(chipDnd,          dndOk)

        val allGranted = notifOk && batteryOk && dndOk
        btnContinue.isEnabled = allGranted
        btnContinue.alpha     = if (allGranted) 1f else 0.4f
    }

    private fun updateChip(chip: TextView, granted: Boolean) {
        if (granted) {
            chip.text = getString(R.string.setup_status_granted)
            chip.setTextColor(Color.parseColor("#121212"))
            chip.setBackgroundColor(Color.parseColor("#39FF14"))
        } else {
            chip.text = getString(R.string.setup_status_required)
            chip.setTextColor(Color.parseColor("#121212"))
            chip.setBackgroundColor(Color.parseColor("#FF5252"))
        }
    }
}
