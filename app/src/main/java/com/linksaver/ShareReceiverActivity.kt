package com.linksaver

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val urlRegex = "(https?://[^ \\n]+)".toRegex()
            val url = urlRegex.find(text)?.value
            
            if (url != null) {
                val tag = text.replace(url, "").trim()
                val prefs = getSharedPreferences("linksaver_prefs", Context.MODE_PRIVATE)
                val dbPath = prefs.getString("db_path", null)
                val hasPermission = Environment.isExternalStorageManager()

                if (dbPath != null && hasPermission) {
                    try {
                        if (DatabaseManager.initDb(dbPath)) {
                            val isYoutube = url.contains("youtube.com", true) || url.contains("youtu.be", true)
                            DatabaseManager.saveLink(url, tag, isYoutube)
                            // THE CRITICAL FIX: DO NOT CLOSE THE DATABASE HERE.
                            // Let the OS manage the connection lifecycle.
                            Toast.makeText(this, "Link Saved", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Error accessing database file", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(this, "DB Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "LinkSaver setup incomplete. Open the app first.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "No valid link found in shared text", Toast.LENGTH_SHORT).show()
            }
        }
        
        finishAndRemoveTask()
    }
}