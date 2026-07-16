package com.example.admuteforspotify

import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * A minimal settings screen with a single toggle for the boot receiver preference.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = PrefsHelper(this)

        // Back button
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Boot switch
        val switchBoot = findViewById<MaterialSwitch>(R.id.switchBoot)
        switchBoot.isChecked = prefs.isBootReceiverEnabled()
        switchBoot.setOnCheckedChangeListener { _, isChecked ->
            prefs.setBootReceiverEnabled(isChecked)
        }
    }
}
