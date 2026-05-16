package com.linksaver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ScriptAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            PythonRunner.syncAlarms(context)
            return
        }

        val scriptName = intent.getStringExtra("SCRIPT_NAME") ?: return
        val runAsServer = intent.getBooleanExtra("RUN_AS_SERVER", false)

        val serviceIntent = if (runAsServer) {
            Intent(context, ServerService::class.java)
        } else {
            Intent(context, ScriptService::class.java)
        }
        
        serviceIntent.putExtra("SCRIPT_NAME", scriptName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        PythonRunner.syncAlarms(context)
    }
}