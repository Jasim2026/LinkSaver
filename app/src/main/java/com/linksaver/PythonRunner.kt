package com.linksaver

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import org.json.JSONArray
import java.io.File
import java.util.*

object OutputLogger {
    val sessionLogs = mutableStateMapOf<String, MutableList<String>>()

    fun log(scriptName: String, text: String) {
        if (!sessionLogs.containsKey(scriptName)) {
            sessionLogs[scriptName] = mutableStateListOf()
        }
        sessionLogs[scriptName]?.add(text)
    }
}

interface PythonLogger {
    fun log(msg: String)
}

object PythonRunner {

    fun executeSingleScript(context: Context, fileName: String): Boolean {
        val prefs = context.getSharedPreferences("linksaver_prefs", Context.MODE_PRIVATE)
        val scriptsDirPath = prefs.getString("script_folder_path", null)
        val dbPath = prefs.getString("db_path", null)
        if (scriptsDirPath == null || dbPath == null) return false

        val scriptFile = File(scriptsDirPath, fileName)
        if (!scriptFile.exists()) {
            DatabaseManager.addLog("FAILURE", "Script not found: $fileName")
            return false
        }
        
        val success = runSingleFile(context, dbPath, scriptFile)
        
        if (success) {
            val schedule = DatabaseManager.getSchedule(fileName)
            if (schedule != null && schedule.triggerScript.isNotBlank() && schedule.triggerScript != "None") {
                val triggerSchedule = DatabaseManager.getSchedule(schedule.triggerScript)
                if (triggerSchedule?.runAsServer == true) {
                    val sIntent = Intent(context, ServerService::class.java)
                    sIntent.putExtra("SCRIPT_NAME", schedule.triggerScript)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(sIntent)
                    } else {
                        context.startService(sIntent)
                    }
                } else {
                    val intent = Intent(context, ScriptService::class.java)
                    intent.putExtra("SCRIPT_NAME", schedule.triggerScript)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }
            }
        }
        return success
    }

    private fun runSingleFile(context: Context, dbPath: String, scriptFile: File): Boolean {
        val py = Python.getInstance()
        val mainModule = py.getModule("__main__")
        
        mainModule.put("DB_PATH", "$dbPath/linksaver.db")
        mainModule.put("DB_DIR", dbPath)

        val loggerObj = object : PythonLogger {
            override fun log(msg: String) {
                OutputLogger.log(scriptFile.name, msg)
            }
        }
        mainModule.put("android_logger", loggerObj)

        val mainGlobals = mainModule.get("__dict__")
        val setupCode = """
import sys
class AndroidWriter:
    def __init__(self, logger):
        self.logger = logger
    def write(self, msg):
        if msg.strip():
            self.logger.log(msg.strip())
    def flush(self):
        pass

sys.stdout = AndroidWriter(android_logger)
sys.stderr = AndroidWriter(android_logger)
"""
        py.getModule("builtins").callAttr("exec", setupCode, mainGlobals)

        return try {
            val scriptCode = scriptFile.readText()
            py.getModule("builtins").callAttr("exec", scriptCode, mainGlobals)
            DatabaseManager.addLog("SUCCESS", "Executed ${scriptFile.name} successfully.")
            true
        } catch (e: Exception) {
            val errorMsg = e.stackTraceToString()
            DatabaseManager.addLog("FAILURE", "Error in ${scriptFile.name}:\n${e.message}")

            val isNetwork = errorMsg.contains("Timeout", true) || 
                            errorMsg.contains("ConnectionError", true) || 
                            errorMsg.contains("404")
            if (!isNetwork) {
                sendNotification(context, "Script Error", "Error in ${scriptFile.name}. Check Logs.")
            }
            false
        }
    }

    fun syncAlarms(context: Context) {
        val prefs = context.getSharedPreferences("linksaver_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("script_enabled", false)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val schedules = DatabaseManager.getAllSchedules()
        
        for (schedule in schedules) {
            val intent = Intent(context, ScriptAlarmReceiver::class.java).apply {
                action = "RUN_SCHEDULED_SCRIPT"
                putExtra("SCRIPT_NAME", schedule.scriptName)
                putExtra("RUN_AS_SERVER", schedule.runAsServer)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 
                schedule.scriptName.hashCode(), 
                intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            alarmManager.cancel(pendingIntent)
            
            if (!enabled) continue

            val timesArr = JSONArray(schedule.timesJson)
            val times = (0 until timesArr.length()).map { timesArr.getString(it) }
            if (times.isEmpty()) continue

            val now = Calendar.getInstance()
            var nextRun: Calendar? = null

            val parsedTimes = times.map { 
                val parts = it.split(":")
                parts[0].toInt() to parts[1].toInt()
            }.sortedBy { it.first * 60 + it.second }

            for ((h, m) in parsedTimes) {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (cal.after(now)) {
                    nextRun = cal
                    break
                }
            }

            if (nextRun == null) {
                val first = parsedTimes.first()
                nextRun = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, first.first)
                    set(Calendar.MINUTE, first.second)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, nextRun.timeInMillis, pendingIntent)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextRun.timeInMillis, pendingIntent)
                }
            } catch (e: SecurityException) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, nextRun.timeInMillis, pendingIntent)
            }
        }
    }

    private fun sendNotification(context: Context, title: String, message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "script_fatal_errors"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Script Errors", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)
        }
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}