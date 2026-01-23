package com.example.admuteforspotify

import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var isServiceRunning = false
    private lateinit var mutedAdsCountTextView: TextView
    private lateinit var timelineListView: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkForCrashReport()

        val toggleButton: Button = findViewById(R.id.toggleServiceButton)
        val permissionButton: Button = findViewById(R.id.permissionButton)
        val statusText: TextView = findViewById(R.id.statusTextView)
        mutedAdsCountTextView = findViewById(R.id.mutedAdsCountTextView)
        timelineListView = findViewById(R.id.timelineListView)

        toggleButton.setOnClickListener {
            if (isServiceRunning) {
                stopService(Intent(this, AdMuteService::class.java))
                toggleButton.text = "Start AdMute Service"
                statusText.text = "Service is stopped."
            } else {
                startForegroundService(Intent(this, AdMuteService::class.java))
                toggleButton.text = "Stop AdMute Service"
                statusText.text = "Service is running..."
            }
            isServiceRunning = !isServiceRunning
        }

        permissionButton.setOnClickListener {
            val notificationListenerIntent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            startActivity(notificationListenerIntent)

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                val dndIntent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                startActivity(dndIntent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateCounter()
        updateTimeline()
    }

    private fun updateCounter() {
        val prefs = getSharedPreferences("AdMutePrefs", Context.MODE_PRIVATE)
        val muteCount = prefs.getInt("MuteCount", 0)
        mutedAdsCountTextView.text = muteCount.toString()
    }

    private fun updateTimeline() {
        val prefs = getSharedPreferences("AdMutePrefs", Context.MODE_PRIVATE)
        val historySet = prefs.getStringSet("MuteHistory", setOf()) ?: setOf()
        val historyList = historySet.toMutableList().sortedDescending()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, historyList)
        timelineListView.adapter = adapter
    }

    private fun checkForCrashReport() {
        val prefs = getSharedPreferences("AdMutePrefs", Context.MODE_PRIVATE)
        val crashReport = prefs.getString("crash_report", null)

        if (crashReport != null) {
            AlertDialog.Builder(this)
                .setTitle("App Error Detected")
                .setMessage(crashReport)
                .setPositiveButton("Copy Text") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Crash Report", crashReport)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Report copied to clipboard", Toast.LENGTH_LONG).show()
                    prefs.edit().remove("crash_report").apply()
                }
                .setNegativeButton("Close") { _, _ ->
                    prefs.edit().remove("crash_report").apply()
                }
                .setCancelable(false)
                .show()
        }
    }
}