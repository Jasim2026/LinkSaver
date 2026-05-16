package com.linksaver

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linksaver.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.Calendar
import android.os.storage.StorageManager
import android.os.Environment
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val prefs = context.getSharedPreferences("linksaver_prefs", Context.MODE_PRIVATE)
    var hasNotificationPermission by remember { 
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> hasNotificationPermission = isGranted }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var dbPath by remember { mutableStateOf(prefs.getString("db_path", null)) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(hasPermission, dbPath) {
        if (hasPermission && dbPath != null) {
            if (DatabaseManager.initDb(dbPath!!)) {
                refreshTrigger++
            }
        }
    }

    if (!hasPermission) {
        PermissionScreen { hasPermission = Environment.isExternalStorageManager() }
    } else if (dbPath == null) {
        SetupScreen { path -> prefs.edit().putString("db_path", path).apply(); dbPath = path }
    } else {
        AppNavigation(dbPath!!, refreshTrigger)
    }
}

@Composable
fun AppNavigation(dbPath: String, refreshTrigger: Int) {
    var selectedTab by remember { mutableStateOf(0) }
    var scheduledScript by remember { mutableStateOf<String?>(null) }
    
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("linksaver_prefs", Context.MODE_PRIVATE)
    val scriptPath = prefs.getString("script_folder_path", "") ?: ""
    val availableScripts = remember(scriptPath) {
        val dir = File(scriptPath)
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles { file -> file.extension == "py" }?.map { it.name }?.sorted() ?: emptyList()
        } else emptyList()
    }

    if (scheduledScript != null) {
        ScriptScheduleScreen(
            scriptName = scheduledScript!!,
            availableScripts = availableScripts,
            onBack = { scheduledScript = null }
        )
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(icon = { Text("🏠") }, label = { Text("Home") }, selected = selectedTab == 0, onClick = { selectedTab = 0 })
                    NavigationBarItem(icon = { Text("📚") }, label = { Text("DB") }, selected = selectedTab == 1, onClick = { selectedTab = 1 })
                    NavigationBarItem(icon = { Text("📝") }, label = { Text("Logs") }, selected = selectedTab == 2, onClick = { selectedTab = 2 })
                    NavigationBarItem(icon = { Text("💻") }, label = { Text("Term") }, selected = selectedTab == 3, onClick = { selectedTab = 3 })
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                when (selectedTab) {
                    0 -> DashboardScreen(dbPath, refreshTrigger, availableScripts) { scheduledScript = it }
                    1 -> LibraryScreen()
                    2 -> LogsScreen()
                    3 -> TerminalScreen()
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(dbPath: String, refreshTrigger: Int, availableScripts: List<String>, onScheduleClick: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("linksaver_prefs", Context.MODE_PRIVATE)
    
    var ytCount by remember { mutableStateOf(0) }
    var webCount by remember { mutableStateOf(0) }
    var scriptEnabled by remember { mutableStateOf(prefs.getBoolean("script_enabled", false)) }
    var scriptPath by remember { mutableStateOf(prefs.getString("script_folder_path", "No Folder Selected")) }
    
    LaunchedEffect(refreshTrigger) {
        ytCount = DatabaseManager.getYoutubeLinksCount()
        webCount = DatabaseManager.getWebLinksCount()
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            val path = getPathFromTreeUri(it)
            prefs.edit().putString("script_folder_path", path).apply()
            scriptPath = path
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Database Stats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("YouTube Links: $ytCount", modifier = Modifier.padding(top = 8.dp))
                Text("Web Links: $webCount")
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top=16.dp)) {
                    Button(onClick = { Toast.makeText(context, DatabaseManager.exportData(dbPath, "csv"), Toast.LENGTH_LONG).show() }) { Text("CSV") }
                    Button(onClick = { Toast.makeText(context, DatabaseManager.exportData(dbPath, "json"), Toast.LENGTH_LONG).show() }) { Text("JSON") }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Automation Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("Enable Global Automations:", modifier = Modifier.weight(1f))
                    Switch(
                        checked = scriptEnabled,
                        onCheckedChange = { isChecked ->
                            scriptEnabled = isChecked
                            prefs.edit().putBoolean("script_enabled", isChecked).apply()
                            PythonRunner.syncAlarms(context)
                        }
                    )
                }

                Text("Folder: $scriptPath", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { folderLauncher.launch(null) }, modifier = Modifier.padding(top=8.dp)) {
                    Text("Select Script Folder")
                }

                if (availableScripts.isNotEmpty()) {
                    Text("Available Scripts", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                    availableScripts.forEach { scriptName ->
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(scriptName, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    
                                    var runExpanded by remember { mutableStateOf(false) }
                                    Box(modifier = Modifier.weight(1f)) {
                                        Button(onClick = { runExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                            Text("Run")
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                        }
                                        DropdownMenu(expanded = runExpanded, onDismissRequest = { runExpanded = false }) {
                                            DropdownMenuItem(text = { Text("Instant Run") }, onClick = {
                                                runExpanded = false
                                                Thread { PythonRunner.executeSingleScript(context, scriptName) }.start()
                                                Toast.makeText(context, "Running $scriptName", Toast.LENGTH_SHORT).show()
                                            })
                                            DropdownMenuItem(text = { Text("Schedule Run") }, onClick = {
                                                runExpanded = false
                                                onScheduleClick(scriptName)
                                            })
                                        }
                                    }

                                    var serverExpanded by remember { mutableStateOf(false) }
                                    Box(modifier = Modifier.weight(1f)) {
                                        OutlinedButton(onClick = { serverExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                            Text("Server")
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                        }
                                        DropdownMenu(expanded = serverExpanded, onDismissRequest = { serverExpanded = false }) {
                                            DropdownMenuItem(text = { Text("Instant Server") }, onClick = {
                                                serverExpanded = false
                                                val sIntent = Intent(context, ServerService::class.java)
                                                sIntent.putExtra("SCRIPT_NAME", scriptName)
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    context.startForegroundService(sIntent)
                                                } else {
                                                    context.startService(sIntent)
                                                }
                                                Toast.makeText(context, "Server Started", Toast.LENGTH_SHORT).show()
                                            })
                                            DropdownMenuItem(text = { Text("Schedule Server") }, onClick = {
                                                serverExpanded = false
                                                onScheduleClick(scriptName)
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text("No Python scripts found in the selected folder.", modifier = Modifier.padding(top=8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptScheduleScreen(scriptName: String, availableScripts: List<String>, onBack: () -> Unit) {
    val context = LocalContext.current
    var times by remember { mutableStateOf(mutableStateListOf<String>()) }
    var runAsServer by remember { mutableStateOf(false) }
    var triggerScript by remember { mutableStateOf("None") }

    LaunchedEffect(Unit) {
        val schedule = DatabaseManager.getSchedule(scriptName)
        if (schedule != null) {
            runAsServer = schedule.runAsServer
            triggerScript = if (schedule.triggerScript.isBlank()) "None" else schedule.triggerScript
            val arr = JSONArray(schedule.timesJson)
            for (i in 0 until arr.length()) {
                times.add(arr.getString(i))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schedule: $scriptName") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            
            Text("Execution Times", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            times.forEachIndexed { index, time ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(time, modifier = Modifier.weight(1f), fontSize = 18.sp)
                    IconButton(onClick = { times.removeAt(index) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove Time")
                    }
                }
            }

            Button(onClick = {
                val cal = Calendar.getInstance()
                TimePickerDialog(context, { _, h, m ->
                    val formatted = String.format("%02d:%02d", h, m)
                    if (!times.contains(formatted)) times.add(formatted)
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
            }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Add Time")
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Run as Persistent Server:", modifier = Modifier.weight(1f))
                Switch(checked = runAsServer, onCheckedChange = { runAsServer = it })
            }
            Text("Enable this if the script runs an infinite loop (like Flask).", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

            Spacer(modifier = Modifier.height(24.dp))

            Text("Trigger Next Script (On Success)", fontWeight = FontWeight.Bold)
            var expandedTrigger by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedButton(onClick = { expandedTrigger = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(triggerScript)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = expandedTrigger, onDismissRequest = { expandedTrigger = false }) {
                    DropdownMenuItem(text = { Text("None") }, onClick = { triggerScript = "None"; expandedTrigger = false })
                    availableScripts.filter { it != scriptName }.forEach { script ->
                        DropdownMenuItem(text = { Text(script) }, onClick = {
                            triggerScript = script
                            expandedTrigger = false
                        })
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val arr = JSONArray()
                    times.forEach { arr.put(it) }
                    DatabaseManager.saveSchedule(scriptName, arr.toString(), runAsServer, if (triggerScript == "None") "" else triggerScript)
                    PythonRunner.syncAlarms(context)
                    Toast.makeText(context, "Schedule Saved", Toast.LENGTH_SHORT).show()
                    onBack()
                }
            ) {
                Text("Save Configuration")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    var searchQuery by remember { mutableStateOf("") }
    var links by remember { mutableStateOf(listOf<LinkItem>()) }
    var editingLink by remember { mutableStateOf<LinkItem?>(null) }

    LaunchedEffect(searchQuery, editingLink) {
        links = DatabaseManager.searchLinks(searchQuery)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search URLs or Tags") },
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        )

        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(links, key = { "${it.isYoutube}_${it.id}" }) { link ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = {
                        if (it == SwipeToDismissBoxValue.EndToStart) {
                            DatabaseManager.deleteLink(link.id, link.isYoutube)
                            true
                        } else false
                    }
                )

                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(8.dp), contentAlignment = Alignment.CenterEnd) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White)
                        }
                    }
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { editingLink = link },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(if (link.isYoutube) "YouTube" else "Web", style = MaterialTheme.typography.labelSmall)
                            Text(link.tag.ifBlank { "No Tag" }, fontWeight = FontWeight.Bold)
                            Text(link.url, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (editingLink != null) {
        var details by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        LaunchedEffect(editingLink) {
            details = DatabaseManager.getFullLinkDetails(editingLink!!.id, editingLink!!.isYoutube)
        }

        AlertDialog(
            onDismissRequest = { editingLink = null },
            title = { Text("Link Details") },
            text = {
                LazyColumn {
                    items(details.entries.toList()) { entry ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(entry.key.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(entry.value.ifBlank { "N/A" }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editingLink = null }) { Text("Close") } }
        )
    }
}

@Composable
fun LogsScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    var logs by remember { mutableStateOf(listOf<AppLog>()) }
    var stats by remember { mutableStateOf(Pair(0, 0)) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(selectedTab, refreshTrigger) {
        stats = DatabaseManager.getLogStats()
        logs = DatabaseManager.getLogs(if (selectedTab == 0) "SUCCESS" else "FAILURE")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Review", fontWeight = FontWeight.Bold)
                    Text("Success: ${stats.first} | Failed: ${stats.second}")
                }
                IconButton(onClick = {
                    DatabaseManager.clearLogs(if (selectedTab == 0) "SUCCESS" else "FAILURE")
                    refreshTrigger++
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear Logs")
                }
            }
        }
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Success") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Failures") })
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            items(logs) { log ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(log.datetime, style = MaterialTheme.typography.labelSmall)
                        Text(log.message)
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalScreen() {
    val sessionLogs = OutputLogger.sessionLogs
    val keys = sessionLogs.keys.toList()
    var selectedTabIndex by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (keys.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No terminal output yet.")
            }
        } else {
            ScrollableTabRow(selectedTabIndex = selectedTabIndex.coerceIn(0, maxOf(0, keys.size - 1))) {
                keys.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }
            val selectedKey = keys.getOrNull(selectedTabIndex)
            if (selectedKey != null) {
                val logs = sessionLogs[selectedKey] ?: emptyList()
                LazyColumn(modifier = Modifier.fillMaxSize().background(Color.Black).padding(8.dp)) {
                    items(logs) { log ->
                        Text(log, color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SetupScreen(onPathSelected: (String) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { onPathSelected(getPathFromTreeUri(it)) }
    }
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Initial Setup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Button(onClick = { launcher.launch(null) }, modifier = Modifier.padding(top=32.dp)) { Text("Select Folder") }
    }
}

@Composable
fun PermissionScreen(onPermissionGranted: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onPermissionGranted() }
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Storage Access", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Button(onClick = {
            launcher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${context.packageName}") })
        }, modifier = Modifier.padding(top=32.dp)) { Text("Grant Permission") }
    }
}


fun getPathFromTreeUri(context: Context, uri: Uri): String {
    val path = uri.path ?: return ""
    val match = Regex("/tree/([^:]+):(.*)").find(path)
    if (match == null) return Environment.getExternalStorageDirectory().absolutePath
    val volumeId = match.groupValues[1]
    val relativePath = match.groupValues[2]
    if (volumeId.equals("primary", true)) {
        return "${Environment.getExternalStorageDirectory().absolutePath}/$relativePath"
    }
    val externalDirs = context.getExternalFilesDirs(null)
    for (dir in externalDirs) {
        if (dir != null && !dir.absolutePath.contains("emulated/0")) {
            val rootPath = dir.absolutePath.split("/Android/data")[0]
            if (rootPath.contains(volumeId)) {
                return "$rootPath/$relativePath"
            }
        }
    }

    // Ultimate Fallback: The standard Linux mount point
    return "/storage/43BC-100D/$relativePath"
}