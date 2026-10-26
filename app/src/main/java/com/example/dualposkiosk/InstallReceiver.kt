package com.example.dualposkiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        Log.d("InstallReceiver", "PackageInstaller callback status: $status, message: $message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // User interaction required (e.g. prompt permission or confirm)
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.d("InstallReceiver", "App installed successfully. Restarting kiosk...")
                Toast.makeText(context, "Update installed! Restarting...", Toast.LENGTH_SHORT).show()
                // Relaunch the MainActivity
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                }
            }
            else -> {
                Log.e("InstallReceiver", "Installation failed ($status): $message")
                Toast.makeText(context, "Install failed ($status): $message", Toast.LENGTH_LONG).show()
            }
        }
    }
}
