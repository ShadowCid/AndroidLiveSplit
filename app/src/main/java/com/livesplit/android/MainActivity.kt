package com.livesplit.android

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize SharedPreferences
        prefs = getSharedPreferences("LiveSplitPrefs", Context.MODE_PRIVATE)

        // 1. Load saved settings (or defaults if none exist)
        TimerState.overlayOpacity.value = prefs.getFloat("opacity", 0.85f)
        TimerState.timerFontSize.value = prefs.getInt("fontSize", 32)
        TimerState.useDigitalFont.value = prefs.getBoolean("useDigital", true)
        TimerState.splitKey.value = prefs.getInt("splitKey", KeyEvent.KEYCODE_VOLUME_UP)
        TimerState.undoKey.value = prefs.getInt("undoKey", KeyEvent.KEYCODE_VOLUME_DOWN)
        TimerState.skipKey.value = prefs.getInt("skipKey", -1)

        // 2. Setup listeners to automatically save settings the instant they change
        lifecycleScope.launch { TimerState.overlayOpacity.collect { prefs.edit().putFloat("opacity", it).apply() } }
        lifecycleScope.launch { TimerState.timerFontSize.collect { prefs.edit().putInt("fontSize", it).apply() } }
        lifecycleScope.launch { TimerState.useDigitalFont.collect { prefs.edit().putBoolean("useDigital", it).apply() } }
        lifecycleScope.launch { TimerState.splitKey.collect { prefs.edit().putInt("splitKey", it).apply() } }
        lifecycleScope.launch { TimerState.undoKey.collect { prefs.edit().putInt("undoKey", it).apply() } }
        lifecycleScope.launch { TimerState.skipKey.collect { prefs.edit().putInt("skipKey", it).apply() } }

        setContent {
            MaterialTheme {
                var currentScreen by remember { mutableStateOf("DASHBOARD") }

                when (currentScreen) {
                    "DASHBOARD" -> MainSettingsScreen(
                        context = this,
                        onEditSplits = { currentScreen = "EDIT_SPLITS" },
                        onSettings = { currentScreen = "SETTINGS" }
                    )
                    "EDIT_SPLITS" -> EditSplitsScreen(onBack = { currentScreen = "DASHBOARD" })
                    "SETTINGS" -> SettingsScreen(onBack = { currentScreen = "DASHBOARD" })
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (TimerState.listeningForKeybind.value != 0) {
            TimerState.assignKeybind(keyCode)
            return true
        }

        when (keyCode) {
            TimerState.splitKey.value -> {
                TimerState.startOrSplit()
                return true
            }
            TimerState.undoKey.value -> {
                TimerState.undoSplit()
                return true
            }
            TimerState.skipKey.value -> {
                TimerState.skipSegment()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun MainSettingsScreen(context: Context, onEditSplits: () -> Unit, onSettings: () -> Unit) {
    var showNewRunDialog by remember { mutableStateOf(false) }
    val autosaveFile = File(context.filesDir, "autosave.lss")

    // Check for autosave on startup and load it automatically
    LaunchedEffect(Unit) {
        if (autosaveFile.exists()) {
            try {
                autosaveFile.inputStream().use { stream ->
                    val run = LssParser.parse(stream)
                    if (run != null) {
                        TimerState.loadRunData(run)
                        Toast.makeText(context, "Run Restored", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error loading autosave", Toast.LENGTH_SHORT).show()
            }
            autosaveFile.delete() // Clean up after restoring
        }
    }

    val loadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    val run = LssParser.parse(stream)
                    if (run != null) {
                        TimerState.loadRunData(run)
                    }
                }
            } catch (e: Exception) { Toast.makeText(context, "Error loading", Toast.LENGTH_SHORT).show() }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        uri?.let {
            val xmlData = LssExporter.export(TimerState.gameName.value, TimerState.categoryName.value, TimerState.attemptCount.value, TimerState.segments.value)
            context.contentResolver.openOutputStream(it)?.use { stream -> stream.write(xmlData.toByteArray()) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Title Header
        Text(
            text = "LiveSplit\nfor Android",
            color = Color.Green,
            fontSize = 36.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp, bottom = 32.dp)
        )

        // Overlay Controls Block
        Button(
            onClick = { if (Settings.canDrawOverlays(context)) context.startService(Intent(context, FloatingTimerService::class.java)) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)) // Greenish
        ) {
            Text("START OVERLAY", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { context.startService(Intent(context, FloatingTimerService::class.java).apply { action = FloatingTimerService.ACTION_STOP_SERVICE }) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)) // Redish
        ) {
            Text("STOP OVERLAY", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Run Management Block
        Button(
            onClick = { showNewRunDialog = true },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("CREATE NEW RUN") }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onEditSplits,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("EDIT SPLITS") }

        Spacer(modifier = Modifier.height(32.dp))

        // File Management Block
        Button(
            onClick = { loadLauncher.launch("*/*") },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("LOAD .LSS") }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { saveLauncher.launch("${TimerState.gameName.value.replace(" ", "_")}.lss") },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("SAVE .LSS") }

        Spacer(modifier = Modifier.height(48.dp))

        // Settings Block
        Button(
            onClick = onSettings,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
        ) { Text("SETTINGS & KEYBINDS") }
    }

    if (showNewRunDialog) NewRunDialog(onDismiss = { showNewRunDialog = false }, onConfirm = { g, c, s -> TimerState.createNewRun(g, c, s.split(",").map { it.trim() }); showNewRunDialog = false })
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val splitKey by TimerState.splitKey.collectAsState()
    val undoKey by TimerState.undoKey.collectAsState()
    val skipKey by TimerState.skipKey.collectAsState()
    val listeningFor by TimerState.listeningForKeybind.collectAsState()

    val opacity by TimerState.overlayOpacity.collectAsState()
    val fontSize by TimerState.timerFontSize.collectAsState()
    val useDigital by TimerState.useDigitalFont.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(16.dp).verticalScroll(rememberScrollState())) {
        Button(onClick = onBack) { Text("BACK") }
        Spacer(modifier = Modifier.height(24.dp))

        Text("KEYBINDS", color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))

        KeybindRow("Start / Split", splitKey, listeningFor == 1) { TimerState.listenForKeybind(1) }
        KeybindRow("Undo", undoKey, listeningFor == 2) { TimerState.listenForKeybind(2) }
        KeybindRow("Skip", skipKey, listeningFor == 3) { TimerState.listenForKeybind(3) }

        Spacer(modifier = Modifier.height(32.dp))

        Text("OVERLAY APPEARANCE", color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))

        // Opacity Control
        Text("Background Opacity: ${(opacity * 100).toInt()}%", color = Color.White, modifier = Modifier.padding(top = 8.dp))
        Slider(
            value = opacity,
            onValueChange = { TimerState.overlayOpacity.value = it },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = Color.Green, activeTrackColor = Color.Green)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Font Size Control
        Text("Timer Font Size: $fontSize", color = Color.White)
        Slider(
            value = fontSize.toFloat(),
            onValueChange = { TimerState.timerFontSize.value = it.toInt() },
            valueRange = 20f..60f,
            steps = 40,
            colors = SliderDefaults.colors(thumbColor = Color.Green, activeTrackColor = Color.Green)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Font Type Control
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Use Digital Monospace Font", color = Color.White)
            Switch(
                checked = useDigital,
                onCheckedChange = { TimerState.useDigitalFont.value = it },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Green, checkedTrackColor = Color(0xFF1B5E20))
            )
        }
    }
}

@Composable
fun KeybindRow(label: String, currentKeyCode: Int, isListening: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 16.sp)
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = if (isListening) Color.Green else Color.DarkGray)) {
            Text(if (isListening) "PRESS KEY..." else if (currentKeyCode == -1) "NONE" else KeyEvent.keyCodeToString(currentKeyCode).replace("KEYCODE_", ""))
        }
    }
}

@Composable
fun EditSplitsScreen(onBack: () -> Unit) {
    val segments by TimerState.segments.collectAsState()
    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = Color.Green,
        unfocusedBorderColor = Color.Gray,
        cursorColor = Color.Green
    )

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
        Button(onClick = onBack) { Text("BACK") }
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            // Using the new segment.id as a unique key prevents data jumping when removing segments!
            itemsIndexed(segments, key = { _, seg -> seg.id }) { index, segment ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF222222))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {

                        // Name and Delete Row
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = segment.name,
                                onValueChange = { TimerState.updateSegmentName(index, it) },
                                label = { Text("Segment Name", color = Color.Gray) },
                                modifier = Modifier.weight(1f),
                                colors = textFieldColors
                            )
                            Spacer(modifier = Modifier.width(8.dp))

                            // Subsplit Toggle
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = segment.isSubsplit,
                                    onCheckedChange = { TimerState.updateSegmentSubsplit(index, it) },
                                    colors = CheckboxDefaults.colors(checkedColor = Color.Green, uncheckedColor = Color.Gray)
                                )
                                Text("Sub", color = Color.White, fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { TimerState.removeSegment(index) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                            ) {
                                Text("X", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Times Row
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Local state keeps text raw while typing (e.g., allows trailing ".")
                            var pbText by remember {
                                mutableStateOf(if (segment.personalBestTimeMs == 0L) "" else TimerState.formatTime(segment.personalBestTimeMs))
                            }
                            var bestText by remember {
                                mutableStateOf(if (segment.bestSegmentTimeMs == 0L) "" else TimerState.formatTime(segment.bestSegmentTimeMs))
                            }

                            OutlinedTextField(
                                value = pbText,
                                onValueChange = {
                                    pbText = it
                                    TimerState.updateSegmentPB(index, TimerState.parseTimeStringToMs(it))
                                },
                                label = { Text("PB Time", color = Color.Gray) },
                                modifier = Modifier.weight(1f),
                                colors = textFieldColors
                            )
                            OutlinedTextField(
                                value = bestText,
                                onValueChange = {
                                    bestText = it
                                    TimerState.updateSegmentBest(index, TimerState.parseTimeStringToMs(it))
                                },
                                label = { Text("Best Segment", color = Color.Gray) },
                                modifier = Modifier.weight(1f),
                                colors = textFieldColors
                            )
                        }
                    }
                }
            }

            // Add Split Button appended to bottom of list
            item {
                Button(
                    onClick = { TimerState.addSegment("New Split") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("ADD SPLIT", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun NewRunDialog(onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var game by remember { mutableStateOf("") }; var cat by remember { mutableStateOf("") }; var segs by remember { mutableStateOf("") }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = Color.Green,
        unfocusedBorderColor = Color.Gray,
        focusedLabelColor = Color.Green,
        unfocusedLabelColor = Color.LightGray,
        cursorColor = Color.Green
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)) // Dark gray card background
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(value = game, onValueChange = { game = it }, label = { Text("Game") }, colors = textFieldColors, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = cat, onValueChange = { cat = it }, label = { Text("Category") }, colors = textFieldColors, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = segs, onValueChange = { segs = it }, label = { Text("Segments (comma sep)") }, colors = textFieldColors, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { onConfirm(game, cat, segs) }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Create") }
            }
        }
    }
}