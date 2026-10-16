package com.example.functiongemmavoice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.functiongemmavoice.runtime.ActionResult
import com.example.functiongemmavoice.runtime.PreparedAction
import com.example.functiongemmavoice.viewmodel.AssistantStatusState
import com.example.functiongemmavoice.viewmodel.AssistantUiState
import com.example.functiongemmavoice.viewmodel.MessageSender
import com.example.functiongemmavoice.viewmodel.UiMessage
import kotlinx.coroutines.launch

// ChatGPT Minimalist Light Palette
private val ChatBg = Color(0xFFF9F9F9)
private val UserBubbleBg = Color(0xFFE9E9EB)
private val AssistantBubbleBg = Color(0xFFFFFFFF)
private val BorderSubtle = Color(0xFFE5E5E5)
private val PrimaryAccent = Color(0xFF10A37F) // OpenAI Emerald Accent
private val BrandBlue = Color(0xFF2563EB)
private val TextMain = Color(0xFF1E1E1E)
private val TextSecondary = Color(0xFF6B7280)
private val TextMuted = Color(0xFF9CA3AF)
private val SuccessBg = Color(0xFFEBF7F2)
private val SuccessText = Color(0xFF0D684C)
private val ErrorBg = Color(0xFFFEF2F2)
private val ErrorText = Color(0xFFB91C1C)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatGPTAssistantScreen(
    uiState: AssistantUiState,
    onSendCommand: (String) -> Unit,
    onMicClick: () -> Unit,
    onNewChatClick: () -> Unit,
    onImportModelClick: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onConfirmAction: (PreparedAction) -> Unit,
    onCancelAction: (PreparedAction) -> Unit,
    onRetryCommand: (String) -> Unit,
    listState: LazyListState
) {
    val coroutineScope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showMoreActionsSheet by remember { mutableStateOf(false) }

    // Auto-scroll to bottom on new messages
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        containerColor = ChatBg,
        topBar = {
            ChatGPTHeader(
                uiState = uiState,
                onNewChat = onNewChatClick,
                onImportModel = onImportModelClick,
                onLanguageClick = { showLanguageDialog = true },
                onDiagnosticsClick = { showDiagnosticsDialog = true }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding()
            ) {
                // Pending Action Confirmation Card
                uiState.pendingConfirmation?.let { pending ->
                    ActionConfirmationBanner(
                        action = pending,
                        onConfirm = { onConfirmAction(pending) },
                        onCancel = { onCancelAction(pending) }
                    )
                }

                // Interactive Quick Action Bar
                QuickActionBar(
                    isBusy = uiState.isBusy,
                    onSelectAction = { command ->
                        onSendCommand(command)
                    },
                    onOpenMore = {
                        showMoreActionsSheet = true
                    }
                )

                // Fixed ChatGPT Bottom Input Bar
                ChatGPTComposer(
                    text = inputText,
                    onTextChanged = { inputText = it },
                    isBusy = uiState.isBusy,
                    isListening = uiState.isListening,
                    onSend = {
                        if (inputText.isNotBlank()) {
                            val promptToSend = inputText
                            inputText = ""
                            onSendCommand(promptToSend)
                        }
                    },
                    onMic = onMicClick
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp)
            ) {
                items(
                    items = uiState.messages,
                    key = { it.id }
                ) { message ->
                    ChatMessageItem(
                        message = message,
                        onRetry = {
                            if (message.sender == MessageSender.USER) {
                                onRetryCommand(message.text)
                            }
                        }
                    )
                }
            }

            // Jump to latest control if not at bottom
            val isAtBottom by remember {
                derivedStateOf {
                    val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisibleItem >= (uiState.messages.size - 2).coerceAtLeast(0)
                }
            }

            if (!isAtBottom && uiState.messages.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            listState.animateScrollToItem(uiState.messages.size - 1)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .size(40.dp),
                    shape = CircleShape,
                    containerColor = Color.White,
                    contentColor = TextMain,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Scroll to bottom",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            current = uiState.currentSpeechLanguage,
            onSelect = {
                onLanguageChange(it)
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showDiagnosticsDialog) {
        DiagnosticsDialog(
            uiState = uiState,
            onDismiss = { showDiagnosticsDialog = false }
        )
    }

    if (showMoreActionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMoreActionsSheet = false },
            containerColor = Color.White,
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFFD1D5DB)) },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            ExpandedActionsPanel(
                isBusy = uiState.isBusy,
                onSelectAction = { command ->
                    showMoreActionsSheet = false
                    onSendCommand(command)
                },
                onDismiss = { showMoreActionsSheet = false }
            )
        }
    }
}

@Composable
private fun ChatGPTHeader(
    uiState: AssistantUiState,
    onNewChat: () -> Unit,
    onImportModel: () -> Unit,
    onLanguageClick: () -> Unit,
    onDiagnosticsClick: () -> Unit
) {
    Surface(
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
        shadowElevation = 0.5.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Identity pill
                Surface(
                    shape = CircleShape,
                    color = BrandBlue.copy(alpha = 0.1f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "FG",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = BrandBlue
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "FunctionGemma 270M",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextMain
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    when (uiState.statusState) {
                                        AssistantStatusState.MODEL_READY -> PrimaryAccent
                                        AssistantStatusState.PROCESSING -> BrandBlue
                                        AssistantStatusState.MODEL_LOADING -> Color(0xFFF59E0B)
                                        AssistantStatusState.MODEL_ERROR -> Color(0xFFEF4444)
                                    }
                                )
                        )
                    }

                    Text(
                        text = uiState.statusLabel,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Actions: Language badge
                TextButton(
                    onClick = onLanguageClick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = when (uiState.currentSpeechLanguage) {
                            "Telugu (India)" -> "తెలుగు"
                            "English (India)" -> "EN"
                            else -> "EN+TE"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary
                    )
                }

                // New chat button
                IconButton(
                    onClick = onNewChat,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "New chat",
                        tint = TextMain,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Overflow / diagnostics
                IconButton(
                    onClick = onDiagnosticsClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Diagnostics",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Divider(color = BorderSubtle, thickness = 0.5.dp)
        }
    }
}

@Composable
private fun ChatMessageItem(
    message: UiMessage,
    onRetry: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = when (message.sender) {
            MessageSender.USER -> Alignment.End
            else -> Alignment.Start
        }
    ) {
        when (message.sender) {
            MessageSender.USER -> {
                Surface(
                    shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                    color = UserBubbleBg,
                    modifier = Modifier.widthIn(max = 310.dp)
                ) {
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        fontSize = 15.sp,
                        color = TextMain,
                        lineHeight = 21.sp
                    )
                }
            }

            MessageSender.ASSISTANT -> {
                Surface(
                    shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
                    color = AssistantBubbleBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                    shadowElevation = 0.5.dp,
                    modifier = Modifier.widthIn(max = 330.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        // Tool execution pill if an action executed
                        if (message.actionTool != null) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = SuccessBg,
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = SuccessText,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = message.actionTool,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = FontFamily.Monospace,
                                        color = SuccessText
                                    )
                                }
                            }
                        }

                        // Message body
                        Text(
                            text = message.text,
                            fontSize = 15.sp,
                            color = TextMain,
                            lineHeight = 22.sp
                        )

                        // Message footer actions (Copy, latency tag)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (message.timingSummary != null || message.executionDurationMs != null) {
                                val label = message.timingSummary ?: "${message.executionDurationMs}ms"
                                Text(
                                    text = label,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (label.contains("FastPath")) Color(0xFF10B981) else TextMuted
                                )
                            } else {
                                Spacer(modifier = Modifier.width(1.dp))
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (copied) "Copied" else "Copy",
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    modifier = Modifier
                                        .clickable {
                                            clipboard.setText(AnnotatedString(message.text))
                                            copied = true
                                        }
                                        .padding(4.dp)
                                )
                            }
                        }
                    }
                }
            }

            MessageSender.SYSTEM -> {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (message.isError) ErrorBg else UserBubbleBg,
                    modifier = Modifier.widthIn(max = 310.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.isError) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = ErrorText,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = message.text,
                            fontSize = 12.sp,
                            color = if (message.isError) ErrorText else TextSecondary,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatGPTComposer(
    text: String,
    onTextChanged: (String) -> Unit,
    isBusy: Boolean,
    isListening: Boolean,
    onSend: () -> Unit,
    onMic: () -> Unit
) {
    Surface(
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = text,
                onValueChange = onTextChanged,
                placeholder = {
                    Text(
                        text = if (isListening) "Listening…" else "Ask FunctionGemma…",
                        color = TextMuted,
                        fontSize = 15.sp
                    )
                },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                singleLine = false,
                maxLines = 4,
                enabled = !isBusy
            )

            // Voice Mic Button
            IconButton(
                onClick = onMic,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (isListening) ErrorBg else Color.Transparent)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Voice input",
                    tint = if (isListening) Color(0xFFEF4444) else TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Send Button
            val sendEnabled = text.isNotBlank() && !isBusy
            IconButton(
                onClick = onSend,
                enabled = sendEnabled,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (sendEnabled) TextMain else Color(0xFFE5E5E5)
                    )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (sendEnabled) Color.White else Color(0xFF9CA3AF),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ActionConfirmationBanner(
    action: PreparedAction,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        color = Color(0xFFFFFBEB),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFDE68A)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Confirmation Required",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color(0xFF92400E)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = action.summary,
                fontSize = 13.sp,
                color = Color(0xFF78350F)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = TextSecondary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Confirm & Execute")
                }
            }
        }
    }
}

enum class QuickActionCategory(val label: String) {
    ALL("All"),
    DEVICE("Device"),
    APPS("Apps"),
    TIME("Time"),
    COMMUNICATION("Comm"),
    INFO("Info")
}

data class QuickActionDefinition(
    val id: String,
    val title: String,
    val command: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val category: QuickActionCategory,
    val subtitle: String,
    val isPrimary: Boolean = false,
    val requiresConfirmation: Boolean = false
)

private val QuickActionCatalog = listOf(
    // Device Controls
    QuickActionDefinition("flash_on", "Flashlight On", "Turn on the flashlight", Icons.Default.FlashlightOn, QuickActionCategory.DEVICE, "Turn on torch LED", isPrimary = true),
    QuickActionDefinition("flash_off", "Flashlight Off", "Turn off the flashlight", Icons.Default.FlashlightOff, QuickActionCategory.DEVICE, "Turn off torch LED"),
    QuickActionDefinition("battery", "Battery Level", "What is my battery level?", Icons.Default.BatteryChargingFull, QuickActionCategory.DEVICE, "Current battery level & status", isPrimary = true),
    QuickActionDefinition("wifi", "Wi-Fi Settings", "Open Wi-Fi settings", Icons.Default.Wifi, QuickActionCategory.DEVICE, "Manage Wi-Fi networks", isPrimary = true),
    QuickActionDefinition("bluetooth", "Bluetooth", "Open Bluetooth settings", Icons.Default.Bluetooth, QuickActionCategory.DEVICE, "Pair & connect devices", isPrimary = true),
    QuickActionDefinition("sound", "Sound & Volume", "Open sound settings", Icons.Default.VolumeUp, QuickActionCategory.DEVICE, "Volume & audio settings"),
    QuickActionDefinition("display", "Display & Brightness", "Open display settings", Icons.Default.BrightnessMedium, QuickActionCategory.DEVICE, "Brightness & screen timeout"),
    QuickActionDefinition("location", "Location Settings", "Open location settings", Icons.Default.LocationOn, QuickActionCategory.DEVICE, "GPS & location permissions"),
    QuickActionDefinition("airplane", "Airplane Mode", "Open airplane mode settings", Icons.Default.AirplanemodeActive, QuickActionCategory.DEVICE, "Network & radio controls"),
    QuickActionDefinition("dnd", "Do Not Disturb", "Open Do Not Disturb settings", Icons.Default.DoNotDisturb, QuickActionCategory.DEVICE, "Silence notifications"),
    QuickActionDefinition("sys_settings", "Device Settings", "Open settings", Icons.Default.Settings, QuickActionCategory.DEVICE, "Main Android system settings"),

    // Apps & Navigation
    QuickActionDefinition("youtube", "YouTube", "Open YouTube", Icons.Default.PlayArrow, QuickActionCategory.APPS, "Launch YouTube video app", isPrimary = true),
    QuickActionDefinition("spotify", "Spotify", "Open Spotify", Icons.Default.MusicNote, QuickActionCategory.APPS, "Launch Spotify music app"),
    QuickActionDefinition("chrome", "Chrome", "Open Chrome", Icons.Default.Public, QuickActionCategory.APPS, "Browse the web"),
    QuickActionDefinition("maps", "Google Maps", "Open Maps", Icons.Default.Navigation, QuickActionCategory.APPS, "Search places & navigation"),
    QuickActionDefinition("camera", "Camera", "Open Camera", Icons.Default.CameraAlt, QuickActionCategory.APPS, "Launch camera capture"),
    QuickActionDefinition("photos", "Photos", "Open Photos", Icons.Default.PhotoLibrary, QuickActionCategory.APPS, "Browse photos & media"),
    QuickActionDefinition("phone", "Phone", "Open Phone", Icons.Default.Phone, QuickActionCategory.APPS, "Open dialer keypad"),
    QuickActionDefinition("messages", "Messages", "Open Messages", Icons.Default.Sms, QuickActionCategory.APPS, "Open SMS messages"),

    // Time & Productivity
    QuickActionDefinition("timer_5m", "5m Timer", "Set a timer for 5 minutes", Icons.Default.Timer, QuickActionCategory.TIME, "Quick 5-minute countdown", isPrimary = true),
    QuickActionDefinition("timer_15m", "15m Timer", "Set a timer for 15 minutes", Icons.Default.Timer, QuickActionCategory.TIME, "15-minute countdown"),
    QuickActionDefinition("alarm_7am", "7:00 AM Alarm", "Create an alarm for 7:00 AM", Icons.Default.Alarm, QuickActionCategory.TIME, "Set morning wake-up alarm"),
    QuickActionDefinition("time_date", "Time & Date", "Check current time and date", Icons.Default.Schedule, QuickActionCategory.TIME, "Current time, day & date", isPrimary = true),
    QuickActionDefinition("clock", "Clock & Timers", "Open clock", Icons.Default.AccessTime, QuickActionCategory.TIME, "View alarms & stopwatches"),
    QuickActionDefinition("calendar", "Calendar", "Open calendar", Icons.Default.CalendarMonth, QuickActionCategory.TIME, "View schedule & agenda"),
    QuickActionDefinition("new_event", "Calendar Event", "Create a calendar event", Icons.Default.Event, QuickActionCategory.TIME, "Add new appointment (confirms first)", requiresConfirmation = true),

    // Communication
    QuickActionDefinition("call_contact", "Call Contact", "Call a contact", Icons.Default.Call, QuickActionCategory.COMMUNICATION, "Open dialer for contact (confirms first)", requiresConfirmation = true),
    QuickActionDefinition("compose_sms", "Compose SMS", "Compose an SMS", Icons.Default.Sms, QuickActionCategory.COMMUNICATION, "Draft text message (confirms first)", requiresConfirmation = true),
    QuickActionDefinition("send_email", "Send Email", "Send an email", Icons.Default.Email, QuickActionCategory.COMMUNICATION, "Draft email message (confirms first)", requiresConfirmation = true),
    QuickActionDefinition("share_text", "Share Text", "Share text", Icons.Default.Share, QuickActionCategory.COMMUNICATION, "Share text to other apps"),

    // Information
    QuickActionDefinition("device_info", "Device Info", "Get device info", Icons.Default.Info, QuickActionCategory.INFO, "Model, OEM & Android version")
)

@Composable
private fun QuickActionBar(
    isBusy: Boolean,
    onSelectAction: (String) -> Unit,
    onOpenMore: () -> Unit
) {
    val primaryActions = QuickActionCatalog.filter { it.isPrimary }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        primaryActions.forEach { action ->
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (isBusy) Color(0xFFF3F4F6) else Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.clickable(enabled = !isBusy) { onSelectAction(action.command) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = null,
                        tint = if (isBusy) TextMuted else BrandBlue,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = action.title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isBusy) TextMuted else TextMain
                    )
                }
            }
        }

        // "More Tools" Action Chip
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (isBusy) Color(0xFFF3F4F6) else BrandBlue.copy(alpha = 0.08f),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isBusy) BorderSubtle else BrandBlue.copy(alpha = 0.35f)),
            modifier = Modifier.clickable(enabled = !isBusy) { onOpenMore() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Apps,
                    contentDescription = "More tools",
                    tint = if (isBusy) TextMuted else BrandBlue,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "More Tools…",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isBusy) TextMuted else BrandBlue
                )
            }
        }
    }
}

@Composable
private fun ExpandedActionsPanel(
    isBusy: Boolean,
    onSelectAction: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf(QuickActionCategory.ALL) }

    val filteredActions = if (selectedCategory == QuickActionCategory.ALL) {
        QuickActionCatalog
    } else {
        QuickActionCatalog.filter { it.category == selectedCategory }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Quick Actions & Tools",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain
                )
                Text(
                    text = "Tap any action to execute immediately on device",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Category Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickActionCategory.values().forEach { cat ->
                val isSelected = (cat == selectedCategory)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) BrandBlue else Color(0xFFF3F4F6),
                    modifier = Modifier.clickable { selectedCategory = cat }
                ) {
                    Text(
                        text = cat.label,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) Color.White else TextSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // List of Actions
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredActions) { item ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isBusy) { onSelectAction(item.command) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BrandBlue.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = BrandBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = item.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextMain
                                )
                                if (item.requiresConfirmation) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = Color(0xFFFEF3C7),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "Requires confirmation",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF92400E),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = item.subtitle,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }

                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun LanguageSelectionDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val languages = listOf("English + Telugu", "English (India)", "Telugu (India)")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice Recognition Language") },
        text = {
            Column {
                languages.forEach { lang ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(lang) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (lang == current),
                            onClick = { onSelect(lang) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(lang, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun DiagnosticsDialog(
    uiState: AssistantUiState,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Runtime Diagnostics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Model: ${uiState.modelName}", fontSize = 12.sp)
                Text("Context Budget: 4096 tokens", fontSize = 12.sp)
                uiState.budgetInfo?.let { b ->
                    Text("Remaining Budget: ~${b.remainingBudget} tokens", fontSize = 12.sp)
                    Text("Schemas: ${b.estimatedSchemaTokens} tokens (38 tools)", fontSize = 12.sp)
                }
                Text("Speech Engine: ${uiState.speechEngineStatus}", fontSize = 12.sp)
                if (uiState.lastExecutionDurationMs != null) {
                    Text("Last Latency: ${uiState.lastExecutionDurationMs}ms", fontSize = 12.sp)
                }
                if (uiState.developerDiagnostics != null) {
                    Divider()
                    Text("Error Trace: ${uiState.developerDiagnostics.take(200)}…", fontSize = 10.sp, color = ErrorText)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
