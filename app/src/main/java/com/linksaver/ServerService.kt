package com.linksaver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ServerService : Service() {

    companion object {
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        val scriptName = intent?.getStringExtra("SCRIPT_NAME") ?: return START_NOT_STICKY
        
        val channelId = "server_running_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Server Tasks", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, ServerService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 
            0, 
            stopIntent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("LinkSaver Server")
            .setContentText("Running script: $scriptName")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kill Server", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(2, notification)
        }

        Thread {
            PythonRunner.executeSingleScript(this, scriptName)
            stopForeground(true)
            stopSelf()
        }.start()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}