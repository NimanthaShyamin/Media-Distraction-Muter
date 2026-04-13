package com.example.admuteforspotify

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsHelper
    private lateinit var tvAdCount: TextView
    private lateinit var tvEmptyState: TextView
    private lateinit var recyclerHistory: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = PrefsHelper(this)

        // ── Permission gate ───────────────────────────────────────────────────
        // If either required permission is missing, redirect to SetupActivity.
        if (!isNotificationListenerEnabled() || !isDndGranted()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        checkForCrashReport()

        tvAdCount      = findViewById(R.id.tvAdCount)
        tvEmptyState   = findViewById(R.id.tvEmptyState)
        recyclerHistory = findViewById(R.id.recyclerHistory)

        recyclerHistory.layoutManager = LinearLayoutManager(this)

        // Settings gear
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()

        // Re-check permissions on every resume (e.g. user revoked them in bg)
        if (!isNotificationListenerEnabled() || !isDndGranted()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        refreshDashboard()
    }

    // ── Dashboard refresh ─────────────────────────────────────────────────────

    private fun refreshDashboard() {
        val count  = prefs.getMuteCount()
        val events = prefs.getMuteEvents()

        animateCounter(count)

        if (events.isEmpty()) {
            tvEmptyState.visibility  = android.view.View.VISIBLE
            recyclerHistory.visibility = android.view.View.GONE
        } else {
            tvEmptyState.visibility  = android.view.View.GONE
            recyclerHistory.visibility = android.view.View.VISIBLE
            recyclerHistory.adapter  = MuteHistoryAdapter(events)
        }
    }

    /**
     * Animates the counter text from 0 to [target] with a smooth ease-out curve.
     */
    private fun animateCounter(target: Int) {
        val animator = ValueAnimator.ofInt(0, target)
        animator.duration = 400L
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { tvAdCount.text = it.animatedValue.toString() }
        animator.start()
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val component = ComponentName(this, AdMuteService::class.java).flattenToString()
        return flat.contains(component)
    }

    private fun isDndGranted(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    // ── Crash report ──────────────────────────────────────────────────────────

    private fun checkForCrashReport() {
        // Uses the legacy prefs file where crash reports are stored by MyApplication
        val legacyPrefs = getSharedPreferences("AdMutePrefs", Context.MODE_PRIVATE)
        val crashReport = legacyPrefs.getString("crash_report", null) ?: return

        AlertDialog.Builder(this)
            .setTitle("App Error Detected")
            .setMessage(crashReport)
            .setPositiveButton("Copy & Dismiss") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Crash Report", crashReport))
                Toast.makeText(this, "Report copied", Toast.LENGTH_LONG).show()
                legacyPrefs.edit().remove("crash_report").apply()
            }
            .setNegativeButton("Dismiss") { _, _ ->
                legacyPrefs.edit().remove("crash_report").apply()
            }
            .setCancelable(false)
            .show()
    }
}