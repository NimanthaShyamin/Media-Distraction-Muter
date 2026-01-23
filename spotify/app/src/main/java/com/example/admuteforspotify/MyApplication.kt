package com.example.admuteforspotify

import android.app.Application
import android.content.Context
import android.content.Intent
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            handleUncaughtException(throwable)
        }
    }

    private fun handleUncaughtException(throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTraceString = sw.toString()

        val prefs = getSharedPreferences("AdMutePrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("crash_report", stackTraceString).commit()

        val intent = Intent(applicationContext, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)

        exitProcess(1)
    }
}