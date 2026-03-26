package com.roox.clawlauncher.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("ClawLauncher", "Boot completed — checking auto-start preference")

            val prefs = context.getSharedPreferences("claw_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_on_boot", false)
            val wasRunning = prefs.getBoolean("was_running", false)

            if (autoStart && wasRunning) {
                Log.i("ClawLauncher", "Auto-starting OpenClaw service after boot")
                val serviceIntent = Intent(context, OpenClawService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                // Also launch the main activity to trigger ProcessManager.start()
                val mainIntent = Intent(context, com.roox.clawlauncher.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("auto_start", true)
                }
                context.startActivity(mainIntent)
            }
        }
    }
}
