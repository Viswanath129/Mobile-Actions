package com.example.functiongemmavoice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.functiongemmavoice.runtime.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private val PrimaryBlue = Color(0xFF1E88E5)
private val DarkBlue = Color(0xFF1565C0)
private val LightBlue = Color(0xFFE3F2FD)
private val CardBg = Color(0xFFFFFFFF)
private val DarkInk = Color(0xFF0D47A1)
private val TextDark = Color(0xFF212121)
private val SubtitleGray = Color(0xFF5F6368)
private val BackgroundGray = Color(0xFFF8F9FA)
private val ErrorLight = Color(0xFFFFEBEE)
private val ErrorRed = Color(0xFFC62828)

data class ExamplePrompt(
    val category: String,
    val prompt: String,
    val targetTool: String
)

private val VerifiedExamples = listOf(
    ExamplePrompt("Apps", "open YouTube", "open_app"),
    ExamplePrompt("Flashlight", "Turn on the flashlight", "turn_on_flashlight"),
    ExamplePrompt("Flashlight", "Turn off the flashlight", "turn_off_flashlight"),
    ExamplePrompt("Settings", "Open Wi-Fi settings", "open_wifi_settings"),
    ExamplePrompt("Settings", "Open Bluetooth settings", "open_bluetooth_pairing"),
    ExamplePrompt("Settings", "Open Battery Saver settings", "open_battery_saver_settings"),
    ExamplePrompt("Maps", "Show location on map query Hyderabad", "show_location_on_map"),
    ExamplePrompt("Maps", "Navigate to Charminar", "navigate_to_place"),
    ExamplePrompt("Search", "Search the web for LiteRT-LM Android", "search_web"),
    ExamplePrompt("Clock", "Set a timer for 5 minutes", "create_timer"),
    ExamplePrompt("Clock", "Set an alarm for 7:00 AM", "create_alarm"),
    ExamplePrompt("Info", "What is my battery level?", "get_battery_status"),
    ExamplePrompt("Info", "What is the date and time?", "check_current_time_date"),
    ExamplePrompt("Contacts", "Create contact Alice phone +919876543210", "create_contact"),
    ExamplePrompt("Email", "Compose email to test@example.com subject Hello", "compose_email")
)

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initTts(Locale.ENGLISH)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = PrimaryBlue,
                    background = BackgroundGray,
                    surface = Color.White
                )
            ) {
                AssistantScreen(
                    speak = { text, lang -> speakReply(text, lang) },
                    stopSpeaking = { tts?.stop() }
                )
            }
        }
    }

    private fun initTts(locale: Locale) {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = locale
            }
        }
    }

    private fun speakReply(text: String, languageChoice: String) {
        val targetLocale = when (languageChoice) {
            "Telugu (India)" -> Locale("te", "IN")
            "English + Telugu" -> {
                if (text.any { it.code in 0x0C00..0x0C7F }) Locale("te", "IN") else Locale("en", "IN")
            }
            else -> Locale("en", "IN")
        }
        val result = tts?.setLanguage(targetLocale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.language = Locale.ENGLISH
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_reply")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun AssistantScreen(
    speak: (String, String) -> Unit,
    stopSpeaking: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtime = remember { AssistantRuntime(context.applicationContext) }

    var status by remember { mutableStateOf("Ready • Load .litertlm model") }
    var speechStatus by remember { mutableStateOf("Engine: SpeechRecognizer (On-device preferred)") }
    var busy by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var speechLanguage by remember { mutableStateOf("English + Telugu") }
    var pendingConfirmation by remember { mutableStateOf<PreparedAction?>(null) }
    var isSpeaking by remember { mutableStateOf(false) }
    var budgetInfo by remember { mutableStateOf<ContextBudgetStatus?>(null) }

    val lines = remember {
        mutableStateListOf(
            ChatLine("assistant", "Hello! I am your FunctionGemma Voice Assistant. Model and action catalog are auto-configured for 38 native Android tools.")
        )
    }

    // Auto-check if model already exists in filesDir or app directory
    LaunchedEffect(Unit) {
        val defaultInternal = File(context.filesDir, "functiongemma_mobile_actions.litertlm")
        val appExternalModel = File(context.getExternalFilesDir(null), "functiongemma_mobile_actions.litertlm")
        val fallbackExternal = File("/sdcard/Android/data/com.example.functiongemmavoice/files/functiongemma_mobile_actions.litertlm")
        val galleryModel = File("/sdcard/Android/data/com.google.ai.edge.gallery/files/MobileActions_270M/20260218/mobile_actions.litertlm")

        val targetModel = when {
            defaultInternal.exists() -> defaultInternal
            appExternalModel.exists() -> appExternalModel
            fallbackExternal.exists() -> fallbackExternal
            galleryModel.exists() && galleryModel.canRead() -> galleryModel
            else -> null
        }

        if (targetModel != null) {
            busy = true
            status = "Initializing local FunctionGemma model…"
            try {
                runtime.initialize(targetModel.absolutePath)
                status = "Model active • 38 Executable Tools (2.4K token budget)"
                budgetInfo = runtime.getBudgetStatus()
                lines.add(ChatLine("system", "Auto-loaded model: ${targetModel.name}"))
            } catch (t: Throwable) {
                status = "Auto-load failed: ${t.message}"
            } finally {
                busy = false
            }
        }
    }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            status = "Copying model to private storage…"
            try {
                val path = runtime.copyModel(uri)
                status = "Initializing LiteRT-LM runtime…"
                runtime.initialize(path)
                status = "Model active • 38 Executable Tools Ready"
                budgetInfo = runtime.getBudgetStatus()
                lines.add(ChatLine("assistant", "Model loaded successfully! Try asking: “Turn on flashlight”, “Open Wi-Fi settings”, or “నా ఫోన్ బ్యాటరీ ఎంత?”. "))
            } catch (e: Throwable) {
                status = "Model load failed"
                lines.add(ChatLine("system", e.message ?: "Unknown model initialization error", isError = true))
            } finally {
                busy = false
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            listening = true
            startSpeech(
                activity = context as Activity,
                languageChoice = speechLanguage,
                onText = { recognizedText ->
                    input = recognizedText
                    listening = false
                    speechStatus = "Speech recognized (${speechLanguage})"
                },
                onError = { err ->
                    lines.add(ChatLine("system", err, isError = true))
                    listening = false
                    speechStatus = "Recognition error"
                },
                onStatus = { s -> speechStatus = s }
            )
        } else {
            lines.add(ChatLine("system", "Microphone permission was denied.", isError = true))
        }
    }

    fun submit(text: String) {
        if (text.isBlank() || busy) return
        lines.add(ChatLine("user", text))
        input = ""
        busy = true
        status = "Thinking…"
        val startNs = System.nanoTime()
        scope.launch {
            try {
                status = "Validating & Executing…"
                val outcomes = runtime.handlePrompt(text)
                budgetInfo = runtime.getBudgetStatus()
                for (outcome in outcomes) {
                    when (outcome) {
                        is TurnOutcome.TextReply -> {
                            lines.add(ChatLine("assistant", outcome.text))
                            speak(outcome.text, speechLanguage)
                            isSpeaking = true
                        }
                        is TurnOutcome.ActionExecuted -> {
                            val msg = outcome.result.userFacingMessage()
                            lines.add(ChatLine("assistant", msg))
                            speak(msg, speechLanguage)
                            isSpeaking = true
                        }
                        is TurnOutcome.ConfirmationNeeded -> {
                            pendingConfirmation = outcome.action
                        }
                        is TurnOutcome.ActionBlocked -> {
                            lines.add(ChatLine("system", outcome.reason, isError = true))
                            speak(outcome.reason, speechLanguage)
                        }
                    }
                }
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                status = "Completed (${elapsedMs}ms) • 38 Tools Ready"
            } catch (e: AssistantException) {
                status = "Recovered session"
                if (lines.lastOrNull()?.text != e.userFacingMessage) {
                    lines.add(ChatLine("system", e.userFacingMessage, isError = true, technicalDetails = e.technicalDetails))
                }
            } catch (e: Throwable) {
                status = "Error encountered"
                lines.add(ChatLine("system", "Inference error: ${e.message}", isError = true, technicalDetails = e.stackTraceToString()))
            } finally {
                busy = false
            }
        }
    }

    // Confirmation Dialog for Sensitive Actions
    if (pendingConfirmation != null) {
        val action = pendingConfirmation!!
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = PrimaryBlue) },
            title = { Text("Confirm Sensitive Action", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("The assistant requested to perform an external or sensitive action:")
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = LightBlue,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "${action.name}\n${action.args.toString(2)}",
                            modifier = Modifier.padding(10.dp),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = DarkInk
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Do you want to proceed and open the system confirmation?", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val act = action
                        pendingConfirmation = null
                        val result = runtime.executeConfirmedAction(act)
                        val msg = result.userFacingMessage()
                        lines.add(ChatLine("assistant", msg))
                        speak(msg, speechLanguage)
                    }
                ) { Text("Confirm & Open") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    pendingConfirmation = null
                    lines.add(ChatLine("system", "Action “${action.name}” cancelled by user."))
                }) { Text("Cancel") }
            }
        )
    }

    // Main UI Layout
    Column(
        Modifier
            .fillMaxSize()
            .background(BackgroundGray)
            .padding(horizontal = 16.dp)
    ) {
        // App Top Bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .background(LightBlue, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("FG", color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("FunctionGemma Voice", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = DarkInk)
                Text("ON-DEVICE 270M • 4096 CONTEXT BUDGET", style = MaterialTheme.typography.labelSmall, color = PrimaryBlue, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = {
                scope.launch {
                    runtime.resetConversation()
                    if (lines.lastOrNull()?.text != "Conversation session reset.") {
                        lines.add(ChatLine("system", "Conversation session reset."))
                    }
                }
            }) {
                Icon(Icons.Default.Refresh, contentDescription = "New Session", tint = PrimaryBlue)
            }
            IconButton(onClick = { modelPicker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Choose model", tint = PrimaryBlue)
            }
        }

        // Status Card with Token Budget Indicator
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = CardBg,
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(if (status.contains("active")) Color(0xFF4CAF50) else PrimaryBlue, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(status, color = TextDark, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(speechStatus, style = MaterialTheme.typography.labelSmall, color = SubtitleGray)
                    if (budgetInfo != null) {
                        Text(
                            "Rem: ~${budgetInfo!!.remainingBudget} tokens",
                            style = MaterialTheme.typography.labelSmall,
                            color = PrimaryBlue,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Language & Controls Row
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Lang: $speechLanguage", fontSize = 12.sp)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf("English + Telugu", "English (India)", "Telugu (India)").forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice) },
                            onClick = {
                                speechLanguage = choice
                                expanded = false
                            }
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isSpeaking) {
                IconButton(onClick = {
                    stopSpeaking()
                    isSpeaking = false
                }) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop audio", tint = Color.Red)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Functional "Try asking" Chips Row
        Column(Modifier.fillMaxWidth()) {
            Text(
                "TRY ASKING (VERIFIED TOOLS)",
                style = MaterialTheme.typography.labelSmall,
                color = SubtitleGray,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 6.dp)
            ) {
                items(VerifiedExamples) { example ->
                    SuggestionChip(
                        onClick = {
                            input = example.prompt
                        },
                        label = {
                            Text(
                                example.prompt,
                                fontSize = 12.sp,
                                maxLines = 1,
                                color = DarkInk
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = LightBlue
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = PrimaryBlue.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Conversation History
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(lines) { line ->
                val isUser = line.role == "user"
                val isSystem = line.role == "system"

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = when {
                        isUser -> Arrangement.End
                        isSystem -> Arrangement.Center
                        else -> Arrangement.Start
                    }
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = when {
                            line.isError -> ErrorLight
                            isUser -> PrimaryBlue
                            isSystem -> Color(0xFFECEFF1)
                            else -> CardBg
                        },
                        shadowElevation = if (isSystem || line.isError) 0.dp else 1.dp,
                        modifier = Modifier.widthIn(max = 330.dp)
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                text = line.text,
                                color = when {
                                    line.isError -> ErrorRed
                                    isUser -> Color.White
                                    isSystem -> Color(0xFF455A64)
                                    else -> TextDark
                                },
                                fontSize = 14.sp
                            )
                            if (line.technicalDetails != null) {
                                var showTech by remember { mutableStateOf(false) }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = if (showTech) "Hide Technical Log" else "Show Technical Log",
                                    fontSize = 11.sp,
                                    color = PrimaryBlue,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { showTech = !showTech }
                                )
                                if (showTech) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = line.technicalDetails,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = SubtitleGray
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (busy) {
                item {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = PrimaryBlue,
                            trackColor = LightBlue
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("On-device reasoning…", fontSize = 11.sp, color = SubtitleGray)
                    }
                }
            }
        }

        // Microphone Pulse Animation
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (listening) 1.25f else 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )

        // Bottom Input Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 16.dp, top = 6.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Speak or type command…") },
                maxLines = 3,
                shape = RoundedCornerShape(24.dp)
            )

            // Mic Button
            FilledIconButton(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        listening = true
                        startSpeech(
                            activity = context as Activity,
                            languageChoice = speechLanguage,
                            onText = { recognizedText ->
                                input = recognizedText
                                listening = false
                                speechStatus = "Recognized: $recognizedText"
                            },
                            onError = { err ->
                                lines.add(ChatLine("system", err, isError = true))
                                listening = false
                                speechStatus = "Speech failed"
                            },
                            onStatus = { s -> speechStatus = s }
                        )
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !busy,
                modifier = Modifier
                    .size(52.dp)
                    .scale(if (listening) pulseScale else 1f),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (listening) Color(0xFFE53935) else PrimaryBlue
                )
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = if (listening) "Listening…" else "Tap to talk",
                    tint = Color.White
                )
            }

            // Send Button
            FilledIconButton(
                onClick = { submit(input) },
                enabled = !busy && input.isNotBlank(),
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = PrimaryBlue)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
            }
        }
    }
}

private fun startSpeech(
    activity: Activity,
    languageChoice: String,
    onText: (String) -> Unit,
    onError: (String) -> Unit,
    onStatus: (String) -> Unit
) {
    val preferredLocale = when (languageChoice) {
        "English (India)" -> "en-IN"
        "Telugu (India)" -> "te-IN"
        else -> null
    }

    fun launchRecognizer(onDevice: Boolean) {
        val mode = if (onDevice) "on-device" else "installed speech service"
        if (onDevice && Build.VERSION.SDK_INT >= 31 && !SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
            launchRecognizer(false)
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            onError("Android speech recognition service is not available on this device.")
            return
        }

        onStatus("Listening via $mode ($languageChoice)…")

        val recognizer = try {
            if (onDevice && Build.VERSION.SDK_INT >= 31) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
            } else {
                SpeechRecognizer.createSpeechRecognizer(activity)
            }
        } catch (_: Throwable) {
            if (onDevice) {
                launchRecognizer(false)
            } else {
                onError("Failed to initialize speech recognizer.")
            }
            return
        }

        var completed = false
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                recognizer.destroy()
                if (completed) return
                completed = true
                if (onDevice) {
                    launchRecognizer(false)
                } else {
                    onError("Speech recognition error ($error). Check microphone permission, language pack, or connectivity.")
                }
            }

            override fun onResults(results: Bundle?) {
                recognizer.destroy()
                if (completed) return
                completed = true
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull()
                if (text.isNullOrBlank()) {
                    if (onDevice) launchRecognizer(false) else onError("No speech detected.")
                } else {
                    onStatus("Recognized with $mode")
                    onText(text)
                }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (preferredLocale != null) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, preferredLocale)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, preferredLocale)
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            recognizer.startListening(intent)
        } catch (_: Throwable) {
            recognizer.destroy()
            if (onDevice) launchRecognizer(false) else onError("Failed to start speech listener.")
        }
    }

    launchRecognizer(Build.VERSION.SDK_INT >= 31)
}
