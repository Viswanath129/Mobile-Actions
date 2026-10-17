package com.example.functiongemmavoice.runtime

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import org.json.JSONArray
import org.json.JSONObject
import android.Manifest
import android.content.pm.PackageManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.example.functiongemmavoice.runtime.capability.CapabilityRegistry
import java.io.InputStreamReader

import com.example.functiongemmavoice.runtime.accessibility.AssistantAccessibilityService
import android.content.ActivityNotFoundException
import android.media.AudioManager
import android.app.NotificationManager
import kotlin.math.roundToInt

enum class AssistantFailureCategory {
    NONE,
    AMBIGUOUS_INTENT,
    UNKNOWN_APPLICATION,
    APP_NOT_INSTALLED,
    ACTIVITY_NOT_FOUND,
    PERMISSION_DENIED,
    ACCESSIBILITY_NOT_ENABLED,
    DEVICE_ADMIN_UNAVAILABLE,
    SYSTEM_ACTION_UNSUPPORTED,
    PLAY_STORE_UNAVAILABLE,
    USER_CONFIRMATION_REQUIRED,
    MULTI_WINDOW_UNAVAILABLE,
    FLOATING_WINDOW_UNSUPPORTED,
    ACTION_TIMEOUT,
    TOOL_EXECUTION_FAILED,
    RESULT_NOT_VERIFIED,
    PARTIAL_SUCCESS,
    CANCELLED,
    INTERNAL_ERROR
}

data class PreparedAction(
    val name: String,
    val args: JSONObject,
    val summary: String,
    val requiresConfirmation: Boolean
)

sealed class ActionResult(open val failureCategory: AssistantFailureCategory = AssistantFailureCategory.NONE) {
    data class Success(val tool: String, val message: String) : ActionResult(AssistantFailureCategory.NONE)
    data class NeedsConfirmation(val action: PreparedAction) : ActionResult(AssistantFailureCategory.USER_CONFIRMATION_REQUIRED)
    data class InvalidArguments(val tool: String, val reason: String) : ActionResult(AssistantFailureCategory.TOOL_EXECUTION_FAILED)
    data class PermissionDenied(val tool: String, val permission: String) : ActionResult(AssistantFailureCategory.PERMISSION_DENIED)
    data class AccessibilityNotEnabled(val tool: String, val message: String = "Accessibility Service is not enabled. Please enable it in Settings > Accessibility to perform this action.") : ActionResult(AssistantFailureCategory.ACCESSIBILITY_NOT_ENABLED)
    data class AppNotFound(val appName: String, val reason: String = "App “$appName” is not installed or cannot be launched.", override val failureCategory: AssistantFailureCategory = AssistantFailureCategory.APP_NOT_INSTALLED) : ActionResult(failureCategory)
    data class PlayStoreUnavailable(val reason: String) : ActionResult(AssistantFailureCategory.PLAY_STORE_UNAVAILABLE)
    data class MultiWindowUnavailable(val reason: String) : ActionResult(AssistantFailureCategory.MULTI_WINDOW_UNAVAILABLE)
    data class FloatingWindowUnsupported(val reason: String = "Floating window mode is not supported via standard Android public APIs.") : ActionResult(AssistantFailureCategory.FLOATING_WINDOW_UNSUPPORTED)
    data class UnsupportedAction(val tool: String, val reason: String = "Action '$tool' is not supported.", override val failureCategory: AssistantFailureCategory = AssistantFailureCategory.SYSTEM_ACTION_UNSUPPORTED) : ActionResult(failureCategory)
    data class ExecutionFailed(val tool: String, val error: String, override val failureCategory: AssistantFailureCategory = AssistantFailureCategory.TOOL_EXECUTION_FAILED) : ActionResult(failureCategory)
    data class Cancelled(val tool: String) : ActionResult(AssistantFailureCategory.CANCELLED)
    data class PartialSuccess(val summary: String, val stepResults: Map<String, ActionResult> = emptyMap()) : ActionResult(AssistantFailureCategory.PARTIAL_SUCCESS)

    fun userFacingMessage(): String = when (this) {
        is Success -> message
        is NeedsConfirmation -> "Action '${action.name}' requires confirmation."
        is InvalidArguments -> "Invalid arguments for $tool: $reason"
        is PermissionDenied -> "Permission required: $permission"
        is AccessibilityNotEnabled -> message
        is AppNotFound -> reason
        is PlayStoreUnavailable -> "Google Play Store unavailable: $reason"
        is MultiWindowUnavailable -> reason
        is FloatingWindowUnsupported -> reason
        is UnsupportedAction -> reason
        is ExecutionFailed -> "Failed to execute $tool: $error"
        is Cancelled -> "Action '$tool' was cancelled."
        is PartialSuccess -> summary
    }
}

class ActionRegistry(val context: Context) {

    val capabilityRegistry = CapabilityRegistry(context)

    private var flashCameraId: String? = null
    private val emailRegex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\$")
    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    }
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    }

    init {
        try {
            cameraManager?.let { cm ->
                for (id in cm.cameraIdList) {
                    val chars = cm.getCameraCharacteristics(id)
                    val flashAvailable = chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    val lensFacing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    if (flashAvailable && lensFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                        flashCameraId = id
                        break
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Loads active executable tools (38 tools) from assets/tool_catalog_active.json
     * (or fallback to tool_catalog.json) and wraps them as LiteRT-LM OpenAPI tools.
     * This keeps the prompt well within the 4,096 token limit (~2,400 tokens schema).
     */
    fun schemas(): List<ToolProvider> {
        val tools = mutableListOf<ToolProvider>()
        val catalogName = "tool_catalog_active.json"
        try {
            val assetStream = try {
                context.assets.open(catalogName)
            } catch (_: Throwable) {
                context.assets.open("tool_catalog.json")
            }
            assetStream.use { stream ->
                val text = InputStreamReader(stream).readText()
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.getString("name")
                    val desc = obj.optString("description", "")
                    val params = obj.optJSONObject("parameters")?.toString() ?: """{"type":"object","properties":{}}"""
                    tools.add(tool(SchemaTool(name, desc, params)))
                }
            }
        } catch (e: Throwable) {
            // Fallback to essential core tools if asset fails to read
            tools.addAll(listOf(
                tool(SchemaTool("open_wifi_settings", "Open Android Wi-Fi settings.", """{"type":"object","properties":{},"required":[]}""")),
                tool(SchemaTool("turn_on_flashlight", "Turn on the device flashlight.", """{"type":"object","properties":{},"required":[]}""")),
                tool(SchemaTool("turn_off_flashlight", "Turn off the device flashlight.", """{"type":"object","properties":{},"required":[]}""")),
                tool(SchemaTool("show_location_on_map", "Open a map search for a destination.", """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""")),
                tool(SchemaTool("create_contact", "Open contact creation form for explicitly supplied details.", """{"type":"object","properties":{"first_name":{"type":"string"},"last_name":{"type":"string"},"phone_number":{"type":"string"},"email":{"type":"string"}},"required":["first_name"]}""")),
                tool(SchemaTool("create_calendar_event", "Open a calendar event form.", """{"type":"object","properties":{"title":{"type":"string"},"start_iso":{"type":"string"},"end_iso":{"type":"string"},"description":{"type":"string"}},"required":["title","start_iso","end_iso"]}""")),
                tool(SchemaTool("send_email", "Open email composer; never send silently.", """{"type":"object","properties":{"to":{"type":"string"},"subject":{"type":"string"},"body":{"type":"string"}},"required":["to","subject","body"]}"""))
            ))
        }
        return tools
    }

    fun parseArgs(raw: String): JSONObject {
        val cleaned = raw
            .replace("<escape>", "")
            .replace("</escape>", "")
            .trim()
        try {
            return JSONObject(cleaned)
        } catch (_: Throwable) {
            val obj = JSONObject()
            val inner = cleaned.removePrefix("{").removeSuffix("}").trim()
            if (inner.isNotEmpty()) {
                val pairs = inner.split(",")
                for (p in pairs) {
                    val kv = p.split(":", limit = 2)
                    if (kv.size == 2) {
                        val k = kv[0].trim().trim('"', '\'')
                        val v = kv[1].trim().trim('"', '\'')
                        if (k.isNotEmpty()) {
                            val intVal = v.toIntOrNull()
                            if (intVal != null) obj.put(k, intVal)
                            else obj.put(k, v)
                        }
                    }
                }
            }
            return obj
        }
    }

    /**
     * Strict validation for tools and arguments.
     * Rejects unknown tools, malformed arguments, non-https URLs, invalid phone/email, etc.
     */
    fun prepare(name: String, args: JSONObject): PreparedAction? {
        val canonicalName: String
        val canonicalArgs: JSONObject

        if (name in specificAppTools) {
            canonicalName = "open_app"
            val appFromTool = specificAppTools[name] ?: "App"
            canonicalArgs = JSONObject(args.toString()).apply {
                if (!has("app_name") || optString("app_name").isBlank()) {
                    put("app_name", appFromTool)
                }
            }
        } else {
            canonicalName = name
            canonicalArgs = args
        }

        val clean = JSONObject()
        val sensitive: Boolean

        when (canonicalName) {
            // Flashlight
            "turn_on_flashlight", "turn_off_flashlight", "toggle_flashlight" -> {
                sensitive = false
            }

            // Settings & Connectivity
            "open_wifi_settings", "open_bluetooth_settings", "open_bluetooth_pairing",
            "request_bluetooth_enable", "request_bluetooth_disable", "get_bluetooth_state", "get_wifi_state",
            "open_system_settings",
            "open_battery_saver_settings", "open_storage_settings", "open_security_settings",
            "open_date_time_settings", "open_developer_options", "open_airplane_mode_settings",
            "open_mobile_data_settings", "open_nfc_settings", "open_accessibility_settings",
            "open_sound_settings", "open_volume_settings", "open_display_settings",
            "open_location_settings", "open_dnd_settings", "open_calendar",
            "open_wallpaper_settings", "open_font_settings", "open_screen_timeout_settings",
            "open_battery_settings", "open_app_permissions_settings", "open_hotspot_settings",
            "open_network_settings", "open_vpn_settings", "open_language_settings",
            "open_privacy_settings", "open_wifi_panel" -> {
                sensitive = false
            }

            // Display & Brightness Controls
            "get_brightness" -> {
                sensitive = false
            }
            "set_brightness" -> {
                val pct = args.optInt("percentage", -1).let { if (it == -1) args.optInt("level", -1) else it }
                if (pct !in 0..100) return null
                clean.put("percentage", pct)
                sensitive = false
            }
            "adjust_brightness" -> {
                val delta = args.optInt("delta_percent", 0).let { if (it == 0) args.optInt("delta", 0) else it }
                clean.put("delta_percent", delta)
                sensitive = false
            }

            // Audio & Volume Controls
            "get_volume" -> {
                val stream = args.optString("stream").ifBlank { "media" }.lowercase()
                clean.put("stream", stream)
                sensitive = false
            }
            "set_volume" -> {
                val stream = args.optString("stream").ifBlank { "media" }.lowercase()
                val pct = args.optInt("percentage", -1).let { if (it == -1) args.optInt("level", -1) else it }
                if (pct !in 0..100) return null
                clean.put("stream", stream)
                clean.put("percentage", pct)
                sensitive = false
            }
            "adjust_volume" -> {
                val stream = args.optString("stream").ifBlank { "media" }.lowercase()
                val delta = args.optInt("delta_percent", 0)
                clean.put("stream", stream)
                clean.put("delta_percent", delta)
                sensitive = false
            }
            "mute_stream", "unmute_stream" -> {
                val stream = args.optString("stream").ifBlank { "media" }.lowercase()
                clean.put("stream", stream)
                sensitive = false
            }
            "set_ringer_mode" -> {
                val mode = args.optString("mode").lowercase()
                if (mode !in listOf("silent", "vibrate", "normal")) return null
                clean.put("mode", mode)
                sensitive = false
            }
            "set_dnd_mode" -> {
                val enabled = args.optBoolean("enabled", true)
                clean.put("enabled", enabled)
                if (args.has("duration_minutes")) clean.put("duration_minutes", args.getInt("duration_minutes"))
                sensitive = false
            }
            "open_app_notification_settings", "open_app_details_settings" -> {
                val pkg = args.optString("package_name").ifBlank { args.optString("app_name") }.trim()
                if (pkg.isBlank()) return null
                clean.put("package_name", pkg)
                sensitive = false
            }

            // Maps & Navigation
            "show_location_on_map", "show_map" -> {
                val q = args.optString("query").ifBlank { args.optString("location") }.trim()
                if (q.isBlank() || q.length > 200) return null
                clean.put("query", q)
                sensitive = false
            }
            "navigate_to_place" -> {
                val dest = args.optString("destination").ifBlank { args.optString("query") }.trim()
                if (dest.isBlank() || dest.length > 200) return null
                clean.put("destination", dest)
                sensitive = false
            }

            // Web Search & URL opening
            "search_web" -> {
                val q = args.optString("query").trim()
                if (q.isBlank() || q.length > 300) return null
                clean.put("query", q)
                sensitive = false
            }
            "open_url" -> {
                var url = args.optString("url").trim()
                if (url.isBlank() || url.length > 500) return null
                val lower = url.lowercase(java.util.Locale.ROOT)
                // Strictly block insecure HTTP, dangerous, file, or script schemes
                if (lower.startsWith("http://") || lower.startsWith("file:") ||
                    lower.startsWith("javascript:") || lower.startsWith("content:") ||
                    lower.startsWith("intent:") || lower.startsWith("data:") ||
                    lower.startsWith("about:")) {
                    return null
                }
                if (!lower.startsWith("https://")) {
                    if (lower.contains("://")) return null
                    url = "https://$url"
                }
                if (!url.startsWith("https://", ignoreCase = true)) return null
                clean.put("url", url)
                sensitive = false
            }

            // App Navigation
            "open_app" -> {
                val app = args.optString("app_name")
                    .replace("<escape>", "")
                    .replace("</escape>", "")
                    .trim()
                if (app.isBlank() || app.length > 100) return null
                clean.put("app_name", app)
                sensitive = false
            }
            "go_home_screen", "lock_screen", "navigate_back", "show_recents" -> {
                sensitive = false
            }
            "close_or_minimize_app" -> {
                val app = args.optString("app_name").trim()
                if (app.isNotBlank()) clean.put("app_name", app)
                sensitive = false
            }
            "open_play_store_search" -> {
                val q = args.optString("query").ifBlank { args.optString("app_name") }.trim()
                if (q.isBlank()) return null
                clean.put("query", q)
                sensitive = false
            }
            "open_play_store_listing" -> {
                val app = args.optString("app_name").ifBlank { args.optString("package_name") }.trim()
                if (app.isBlank()) return null
                clean.put("app_name", app)
                if (args.has("package_name")) clean.put("package_name", args.getString("package_name"))
                sensitive = false
            }
            "open_play_store_updates" -> {
                sensitive = false
            }
            "request_app_install" -> {
                val app = args.optString("app_name").trim()
                if (app.isBlank()) return null
                clean.put("app_name", app)
                sensitive = false
            }
            "request_app_update" -> {
                val app = args.optString("app_name").trim()
                clean.put("app_name", app)
                sensitive = false
            }
            "open_split_screen" -> {
                val p = args.optString("primary_app").ifBlank { args.optString("app_one") }.trim()
                val s = args.optString("secondary_app").ifBlank { args.optString("app_two") }.trim()
                if (p.isNotBlank()) clean.put("primary_app", p)
                if (s.isNotBlank()) clean.put("secondary_app", s)
                sensitive = false
            }
            "open_floating_window" -> {
                val app = args.optString("app_name").trim()
                if (app.isNotBlank()) clean.put("app_name", app)
                sensitive = false
            }

            // Clock, Alarm, Timer
            "create_alarm" -> {
                var hour = args.optInt("hour", -1)
                var minutes = args.optInt("minutes", -1)
                val timeStr = args.optString("time").trim()
                if (hour == -1 && timeStr.isNotBlank()) {
                    val parsed = parseTimeStringToHourMinute(timeStr)
                    if (parsed != null) {
                        hour = parsed.first
                        minutes = parsed.second
                    }
                }
                if (minutes == -1) minutes = 0
                if (hour !in 0..23 || minutes !in 0..59) return null
                clean.put("hour", hour)
                clean.put("minutes", minutes)
                val msg = args.optString("message").ifBlank { args.optString("label") }.trim()
                if (msg.isNotBlank()) clean.put("message", msg.take(100))
                sensitive = false
            }
            "create_timer" -> {
                var sec = args.optInt("duration_seconds", -1)
                if (sec <= 0) sec = args.optInt("seconds", -1)
                if (sec <= 0 && args.has("minutes")) {
                    sec = args.optInt("minutes", 0) * 60
                }
                if (sec <= 0 && args.has("duration")) {
                    val dStr = args.optString("duration").trim()
                    sec = parseDurationToSeconds(dStr)
                }
                if (sec <= 0 && args.has("duration_seconds")) {
                    val dStr = args.optString("duration_seconds").trim()
                    sec = parseDurationToSeconds(dStr)
                }
                if (sec <= 0) sec = 300 // default 5 minutes if invoked without duration
                sec = sec.coerceIn(1, 86400)
                clean.put("duration_seconds", sec)
                val msg = args.optString("message").ifBlank { args.optString("label") }.trim()
                if (msg.isNotBlank()) clean.put("message", msg.take(100))
                sensitive = false
            }
            "open_clock_stopwatch" -> {
                val cmd = args.optString("command").ifBlank { "start" }
                clean.put("command", cmd)
                sensitive = false
            }

            // Contacts (Sensitive: requires confirmation)
            "create_contact" -> {
                val first = args.optString("first_name").ifBlank { args.optString("name") }.trim()
                if (first.isBlank() || first.length > 80) return null
                clean.put("first_name", first)
                val last = args.optString("last_name").trim()
                if (last.isNotBlank() && last.length <= 80) clean.put("last_name", last)
                val phone = args.optString("phone_number").trim()
                if (phone.isNotBlank()) {
                    if (!phone.matches(Regex("[+0-9() .-]{3,25}"))) return null
                    clean.put("phone_number", phone)
                }
                val email = args.optString("email").trim()
                if (email.isNotBlank()) {
                    if (!emailRegex.matches(email)) return null
                    clean.put("email", email)
                }
                sensitive = true
            }

            // Phone Dialing (Sensitive)
            "dial_phone_number" -> {
                val phone = args.optString("phone_number").ifBlank { args.optString("number") }.trim()
                if (!phone.matches(Regex("[+0-9*#() .-]{3,30}"))) return null
                clean.put("phone_number", phone)
                sensitive = true
            }

            // Calendar (Sensitive: requires confirmation)
            "create_calendar_event" -> {
                val title = args.optString("title").trim()
                if (title.isBlank() || title.length > 120) return null
                val s = args.optString("start_datetime").ifBlank { args.optString("start_iso") }.ifBlank { args.optString("start_time") }
                val startMs = if (s.isNotBlank()) {
                    runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
                        .getOrNull() ?: runCatching { java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
                        .getOrNull() ?: (System.currentTimeMillis() + 3600000L)
                } else {
                    System.currentTimeMillis() + 3600000L
                }
                val e = args.optString("end_datetime").ifBlank { args.optString("end_iso") }
                val endMs = if (e.isNotBlank()) {
                    runCatching { java.time.OffsetDateTime.parse(e).toInstant().toEpochMilli() }
                        .getOrNull() ?: runCatching { java.time.LocalDateTime.parse(e).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
                        .getOrNull() ?: (startMs + 3600000L)
                } else {
                    startMs + 3600000L
                }
                clean.put("title", title)
                clean.put("start_millis", startMs)
                clean.put("end_millis", endMs)
                if (args.has("description")) clean.put("description", args.optString("description").take(1000))
                sensitive = true
            }

            // Email & Messaging (Sensitive: opens composer only)
            "send_email", "compose_email" -> {
                val to = args.optString("to").ifBlank { args.optString("email") }.trim()
                if (!emailRegex.matches(to)) return null
                clean.put("to", to)
                clean.put("subject", args.optString("subject").take(200))
                clean.put("body", args.optString("body").take(5000))
                sensitive = true
            }
            "compose_sms" -> {
                val phone = args.optString("phone_number").ifBlank { args.optString("recipient") }.trim()
                if (!phone.matches(Regex("[+0-9() .-]{3,25}"))) return null
                clean.put("phone_number", phone)
                clean.put("message", args.optString("message").take(2000))
                sensitive = true
            }

            // Clipboard & Share
            "copy_to_clipboard" -> {
                val text = args.optString("text").trim()
                if (text.isBlank() || text.length > 5000) return null
                clean.put("text", text)
                sensitive = false
            }
            "share_text" -> {
                val text = args.optString("text").ifBlank { args.optString("content") }.trim()
                clean.put("text", if (text.isNotBlank()) text else "FunctionGemma Voice Assistant")
                sensitive = false
            }

            // Camera / Media capture
            "take_photo", "open_video_camera" -> {
                val cam = args.optString("camera").ifBlank { "rear" }
                clean.put("camera", cam)
                sensitive = false
            }
            "open_photo_gallery" -> {
                sensitive = false
            }

            // Notes
            "create_note" -> {
                val text = args.optString("body").ifBlank { args.optString("text") }.ifBlank { args.optString("content") }.trim()
                if (text.isBlank() || text.length > 5000) return null
                clean.put("text", text)
                val title = args.optString("title").trim()
                if (title.isNotBlank()) clean.put("title", title.take(100))
                sensitive = false
            }

            // Device Info
            "get_battery_status", "get_device_info", "check_current_time_date" -> {
                sensitive = false
            }

            else -> {
                // If it's a catalog tool without an implementation handler yet, reject safely
                return null
            }
        }

        return PreparedAction(canonicalName, clean, "$canonicalName $clean", sensitive)
    }

    /**
     * Executes approved action via Android APIs or Intents.
     * Returns structured ActionResult with truthful execution status.
     */
    fun execute(name: String, a: JSONObject): ActionResult = try {
        val canonicalName = if (name in specificAppTools) "open_app" else name
        val canonicalArgs = if (name in specificAppTools && (!a.has("app_name") || a.optString("app_name").isBlank())) {
            JSONObject(a.toString()).apply { put("app_name", specificAppTools[name] ?: "App") }
        } else {
            a
        }

        when (canonicalName) {
            "turn_on_flashlight" -> setFlashlight(true)
            "turn_off_flashlight" -> setFlashlight(false)
            "toggle_flashlight" -> toggleFlashlight()

            "open_wifi_settings" -> {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Wi-Fi settings.")
            }
            "get_wifi_state" -> {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNetwork = cm?.activeNetwork
                val caps = cm?.getNetworkCapabilities(activeNetwork)
                val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                val status = if (hasWifi) "connected to Wi-Fi" else "not connected to Wi-Fi"
                ActionResult.Success(name, "Wi-Fi is currently $status.")
            }
            "open_bluetooth_settings", "open_bluetooth_pairing" -> {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Bluetooth settings.")
            }
            "request_bluetooth_enable" -> {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bm?.adapter
                if (adapter == null) {
                    ActionResult.ExecutionFailed(name, "Bluetooth hardware is not available on this device.")
                } else if (adapter.isEnabled) {
                    ActionResult.Success(name, "Bluetooth is already enabled.")
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        ActionResult.PermissionDenied(name, Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        try {
                            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(enableIntent)
                            ActionResult.Success(name, "Requested Bluetooth activation. Please allow the system prompt on your screen.")
                        } catch (_: Throwable) {
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            ActionResult.Success(name, "Opened Bluetooth settings to enable Bluetooth.")
                        }
                    }
                }
            }
            "request_bluetooth_disable" -> {
                // On Android 13+ (API 33+), direct disable is blocked for 3rd-party apps.
                // Truthfully explain limitation and launch Bluetooth settings.
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Android 13+ restricts third-party apps from turning off Bluetooth directly. Opened Bluetooth settings so you can toggle it off.")
            }
            "get_bluetooth_state" -> {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bm?.adapter
                if (adapter == null) {
                    ActionResult.ExecutionFailed(name, "Bluetooth hardware is not available on this device.")
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    ActionResult.PermissionDenied(name, Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    val isEnabled = try { adapter.isEnabled } catch (_: Throwable) { false }
                    val status = if (isEnabled) "enabled (ON)" else "disabled (OFF)"
                    ActionResult.Success(name, "Bluetooth is currently $status.")
                }
            }
            "open_system_settings" -> {
                val section = a.optString("section").lowercase(java.util.Locale.ROOT)
                val intent = when (section) {
                    "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
                    "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
                    "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
                    "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
                    "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    "notifications" -> Intent(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS)
                    "privacy" -> Intent(Settings.ACTION_PRIVACY_SETTINGS)
                    "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    else -> Intent(Settings.ACTION_SETTINGS)
                }
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened System Settings.")
            }
            "open_battery_saver_settings" -> {
                context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Battery Saver settings.")
            }
            "open_storage_settings" -> {
                context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Storage settings.")
            }
            "open_security_settings" -> {
                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Security settings.")
            }
            "open_date_time_settings" -> {
                context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Date & Time settings.")
            }
            "open_developer_options" -> {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Developer Options.")
            }
            "open_airplane_mode_settings" -> {
                context.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Airplane Mode settings.")
            }
            "open_mobile_data_settings" -> {
                context.startActivity(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Mobile Data settings.")
            }
            "open_nfc_settings" -> {
                context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened NFC settings.")
            }
            "open_accessibility_settings" -> {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Accessibility settings.")
            }
            "open_sound_settings", "open_volume_settings" -> {
                context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Sound & Volume settings.")
            }
            "open_display_settings" -> {
                context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Display & Brightness settings.")
            }
            "open_location_settings" -> {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Location settings.")
            }
            "open_dnd_settings" -> {
                context.startActivity(Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Do Not Disturb settings.")
            }
            "open_wallpaper_settings" -> {
                val intent = Intent(Intent.ACTION_SET_WALLPAPER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                    ActionResult.Success(name, "Opened Wallpaper settings.")
                } catch (_: Throwable) {
                    context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ActionResult.Success(name, "Opened Display settings.")
                }
            }
            "open_font_settings", "open_screen_timeout_settings" -> {
                context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Display settings.")
            }
            "open_battery_settings" -> {
                val intent = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                    ActionResult.Success(name, "Opened Battery usage settings.")
                } catch (_: Throwable) {
                    context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ActionResult.Success(name, "Opened Battery Saver settings.")
                }
            }
            "open_app_permissions_settings" -> {
                val intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionResult.Success(name, "Opened Application settings.")
            }
            "open_app_notification_settings" -> {
                val pkg = a.optString("package_name").ifBlank { context.packageName }
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$pkg")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(intent)
                ActionResult.Success(name, "Opened notification settings for $pkg.")
            }
            "open_app_details_settings" -> {
                val pkg = a.optString("package_name").ifBlank { context.packageName }
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$pkg")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult.Success(name, "Opened App Info settings for $pkg.")
            }
            "open_hotspot_settings" -> {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    setClassName("com.android.settings", "com.android.settings.TetherSettings")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                    ActionResult.Success(name, "Opened Hotspot & Tethering settings.")
                } catch (_: Throwable) {
                    context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ActionResult.Success(name, "Opened Network settings.")
                }
            }
            "open_network_settings" -> {
                context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Wireless & Network settings.")
            }
            "open_vpn_settings" -> {
                context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened VPN settings.")
            }
            "open_language_settings" -> {
                context.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Language & Region settings.")
            }
            "open_privacy_settings" -> {
                context.startActivity(Intent(Settings.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Privacy settings.")
            }
            "open_wifi_panel" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(panelIntent)
                        ActionResult.Success(name, "Opened Internet connectivity panel.")
                    } catch (_: Throwable) {
                        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        ActionResult.Success(name, "Opened Wi-Fi settings.")
                    }
                } else {
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ActionResult.Success(name, "Opened Wi-Fi settings.")
                }
            }

            // Brightness Control
            "get_brightness" -> {
                val cur = try {
                    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                } catch (_: Throwable) { 128 }
                val pct = (cur * 100 / 255.0).roundToInt().coerceIn(0, 100)
                ActionResult.Success(name, "Current screen brightness is $pct% (level $cur of 255).")
            }
            "set_brightness" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:" + context.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try { context.startActivity(intent) } catch (_: Throwable) {}
                    ActionResult.PermissionDenied(name, Manifest.permission.WRITE_SETTINGS)
                } else {
                    val pct = a.optInt("percentage", a.optInt("level", 50)).coerceIn(0, 100)
                    val brightnessVal = (pct * 255 / 100.0).roundToInt().coerceIn(1, 255)
                    try {
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightnessVal)
                        ActionResult.Success(name, "Set screen brightness to $pct%.")
                    } catch (e: Throwable) {
                        ActionResult.ExecutionFailed(name, "Could not set screen brightness: ${e.message}")
                    }
                }
            }
            "adjust_brightness" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:" + context.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try { context.startActivity(intent) } catch (_: Throwable) {}
                    ActionResult.PermissionDenied(name, Manifest.permission.WRITE_SETTINGS)
                } else {
                    val cur = try {
                        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                    } catch (_: Throwable) { 128 }
                    val curPct = (cur * 100 / 255.0).roundToInt().coerceIn(0, 100)
                    val direction = a.optString("direction").lowercase()
                    val delta = a.optInt("delta", if (direction == "down") -20 else 20)
                    val newPct = (curPct + delta).coerceIn(5, 100)
                    val brightnessVal = (newPct * 255 / 100.0).roundToInt().coerceIn(1, 255)
                    try {
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightnessVal)
                        ActionResult.Success(name, "Adjusted screen brightness to $newPct% (was $curPct%).")
                    } catch (e: Throwable) {
                        ActionResult.ExecutionFailed(name, "Could not adjust screen brightness: ${e.message}")
                    }
                }
            }

            // Volume Streams & Sound Modes
            "get_volume" -> {
                val am = audioManager
                if (am == null) {
                    ActionResult.ExecutionFailed(name, "AudioManager unavailable.")
                } else {
                    val (stream, streamTitle) = resolveAudioStream(a.optString("stream"))
                    val current = am.getStreamVolume(stream)
                    val max = am.getStreamMaxVolume(stream)
                    val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) am.getStreamMinVolume(stream) else 0
                    val pct = if (max > min) ((current - min) * 100 / (max - min)) else (current * 100 / max.coerceAtLeast(1))
                    ActionResult.Success(name, "$streamTitle volume is at $pct% (level $current of $max).")
                }
            }
            "set_volume" -> {
                val am = audioManager
                if (am == null) {
                    ActionResult.ExecutionFailed(name, "AudioManager unavailable.")
                } else if (am.isVolumeFixed) {
                    ActionResult.UnsupportedAction(name, "Device audio volume is fixed by hardware.")
                } else {
                    val (stream, streamTitle) = resolveAudioStream(a.optString("stream"))
                    val max = am.getStreamMaxVolume(stream)
                    val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) am.getStreamMinVolume(stream) else 0
                    val targetLevel = if (a.has("percentage")) {
                        val pct = a.getInt("percentage").coerceIn(0, 100)
                        min + ((max - min) * pct / 100.0).roundToInt()
                    } else if (a.has("level")) {
                        a.getInt("level").coerceIn(min, max)
                    } else {
                        (max * 0.5).roundToInt()
                    }
                    am.setStreamVolume(stream, targetLevel, AudioManager.FLAG_SHOW_UI)
                    ActionResult.Success(name, "Set $streamTitle volume to $targetLevel (of $max).")
                }
            }
            "adjust_volume" -> {
                val am = audioManager
                if (am == null) {
                    ActionResult.ExecutionFailed(name, "AudioManager unavailable.")
                } else if (am.isVolumeFixed) {
                    ActionResult.UnsupportedAction(name, "Device audio volume is fixed by hardware.")
                } else {
                    val (stream, streamTitle) = resolveAudioStream(a.optString("stream"))
                    val direction = a.optString("direction").lowercase()
                    val delta = a.optInt("delta", 0)
                    val adjustDir = when {
                        direction == "up" || delta > 0 -> AudioManager.ADJUST_RAISE
                        direction == "down" || delta < 0 -> AudioManager.ADJUST_LOWER
                        else -> AudioManager.ADJUST_SAME
                    }
                    am.adjustStreamVolume(stream, adjustDir, AudioManager.FLAG_SHOW_UI)
                    val current = am.getStreamVolume(stream)
                    val max = am.getStreamMaxVolume(stream)
                    ActionResult.Success(name, "Adjusted $streamTitle volume to $current (of $max).")
                }
            }
            "mute_stream" -> {
                val am = audioManager
                if (am == null) {
                    ActionResult.ExecutionFailed(name, "AudioManager unavailable.")
                } else if (am.isVolumeFixed) {
                    ActionResult.UnsupportedAction(name, "Device audio volume is fixed by hardware.")
                } else {
                    val (stream, streamTitle) = resolveAudioStream(a.optString("stream"))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    } else {
                        @Suppress("DEPRECATION")
                        am.setStreamMute(stream, true)
                    }
                    ActionResult.Success(name, "Muted $streamTitle stream.")
                }
            }
            "unmute_stream" -> {
                val am = audioManager
                if (am == null) {
                    ActionResult.ExecutionFailed(name, "AudioManager unavailable.")
                } else if (am.isVolumeFixed) {
                    ActionResult.UnsupportedAction(name, "Device audio volume is fixed by hardware.")
                } else {
                    val (stream, streamTitle) = resolveAudioStream(a.optString("stream"))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    } else {
                        @Suppress("DEPRECATION")
                        am.setStreamMute(stream, false)
                    }
                    ActionResult.Success(name, "Unmuted $streamTitle stream.")
                }
            }
            "set_ringer_mode" -> {
                val am = audioManager
                val nm = notificationManager
                if (am == null) {
                    ActionResult.ExecutionFailed(name, "AudioManager unavailable.")
                } else {
                    val modeStr = a.optString("mode").lowercase()
                    val targetMode = when (modeStr) {
                        "silent" -> AudioManager.RINGER_MODE_SILENT
                        "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                        else -> AudioManager.RINGER_MODE_NORMAL
                    }
                    if (targetMode == AudioManager.RINGER_MODE_SILENT && nm?.isNotificationPolicyAccessGranted == false) {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { context.startActivity(intent) } catch (_: Throwable) {}
                        ActionResult.PermissionDenied(name, "android.permission.ACCESS_NOTIFICATION_POLICY")
                    } else {
                        try {
                            am.ringerMode = targetMode
                            ActionResult.Success(name, "Ringer mode set to ${modeStr.uppercase()}.")
                        } catch (e: SecurityException) {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try { context.startActivity(intent) } catch (_: Throwable) {}
                            ActionResult.PermissionDenied(name, "android.permission.ACCESS_NOTIFICATION_POLICY")
                        }
                    }
                }
            }
            "set_dnd_mode" -> {
                val nm = notificationManager
                if (nm == null) {
                    ActionResult.ExecutionFailed(name, "NotificationManager unavailable.")
                } else if (!nm.isNotificationPolicyAccessGranted) {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(intent) } catch (_: Throwable) {}
                    ActionResult.PermissionDenied(name, "android.permission.ACCESS_NOTIFICATION_POLICY")
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val enabled = a.optBoolean("enabled", true)
                        val filter = if (!enabled) {
                            NotificationManager.INTERRUPTION_FILTER_ALL
                        } else {
                            when (a.optString("filter").lowercase()) {
                                "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                                "none", "total_silence" -> NotificationManager.INTERRUPTION_FILTER_NONE
                                else -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                            }
                        }
                        nm.setInterruptionFilter(filter)
                        val statusStr = if (!enabled) "turned OFF" else "turned ON"
                        ActionResult.Success(name, "Do Not Disturb $statusStr.")
                    } else {
                        ActionResult.UnsupportedAction(name, "DND filter API requires Android M+")
                    }
                }
            }
            "open_calendar" -> {
                val intent = Intent(Intent.ACTION_VIEW).setData(Uri.parse("content://com.android.calendar/time/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionResult.Success(name, "Opened Calendar.")
            }

            "show_location_on_map", "show_map" -> {
                val q = a.getString("query")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(q)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionResult.Success(name, "Opened map search for $q.")
            }
            "navigate_to_place" -> {
                val dest = a.getString("destination")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionResult.Success(name, "Started navigation to $dest.")
            }

            "search_web" -> {
                val q = a.getString("query")
                val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(SearchManager.QUERY, q)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult.Success(name, "Opened web search for “$q”.")
            }
            "open_url" -> {
                val url = a.getString("url")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionResult.Success(name, "Opened URL: $url")
            }

            "open_app" -> {
                val appName = a.getString("app_name")
                    .replace("<escape>", "")
                    .replace("</escape>", "")
                    .trim()
                val pm = context.packageManager
                val normalized = appName.lowercase(java.util.Locale.ROOT)
                val targetPkg = when {
                    normalized.contains("youtube") -> "com.google.android.youtube"
                    normalized.contains("spotify") -> "com.spotify.music"
                    normalized.contains("chrome") -> "com.android.chrome"
                    normalized.contains("map") -> "com.google.android.apps.maps"
                    normalized.contains("whatsapp") -> "com.whatsapp"
                    normalized.contains("gmail") -> "com.google.android.gm"
                    normalized.contains("clock") -> "com.google.android.deskclock"
                    normalized.contains("camera") -> "com.android.camera2"
                    normalized.contains("setting") -> "com.android.settings"
                    normalized.contains("photo") || normalized.contains("gallery") -> "com.google.android.apps.photos"
                    normalized.contains("phone") || normalized.contains("dialer") -> "com.google.android.dialer"
                    normalized.contains("message") || normalized.contains("sms") -> "com.google.android.apps.messaging"
                    normalized.contains("calculator") -> "com.google.android.calculator"
                    else -> null
                }
                var intent = (if (targetPkg != null) pm.getLaunchIntentForPackage(targetPkg) else null)
                    ?: pm.getLaunchIntentForPackage(appName)
                    ?: run {
                        val packages = pm.getInstalledApplications(0)
                        val matched = packages.firstOrNull {
                            pm.getApplicationLabel(it).toString().equals(appName, ignoreCase = true)
                        } ?: packages.firstOrNull {
                            pm.getApplicationLabel(it).toString().contains(appName, ignoreCase = true)
                        }
                        matched?.let { pm.getLaunchIntentForPackage(it.packageName) }
                    }

                // Fallback to standard platform intents for core device apps
                if (intent == null) {
                    intent = when {
                        normalized.contains("camera") -> Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        normalized.contains("phone") || normalized.contains("dialer") -> Intent(Intent.ACTION_DIAL)
                        normalized.contains("photo") || normalized.contains("gallery") -> Intent(Intent.ACTION_VIEW).apply { type = "image/*" }
                        normalized.contains("message") || normalized.contains("sms") -> Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_MESSAGING) }
                        normalized.contains("clock") -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
                        else -> null
                    }
                }

                if (intent != null) {
                    try {
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        ActionResult.Success(name, "Opened app: $appName")
                    } catch (e: ActivityNotFoundException) {
                        ActionResult.AppNotFound(appName, "Cannot open $appName: Activity not found.")
                    } catch (e: SecurityException) {
                        ActionResult.PermissionDenied(name, e.message ?: "Permission denied launching $appName")
                    }
                } else {
                    ActionResult.AppNotFound(appName)
                }
            }
            "go_home_screen" -> {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success(name, "Navigated to Home Screen.")
            }
            "lock_screen" -> {
                if (AssistantAccessibilityService.isServiceRunning) {
                    val locked = AssistantAccessibilityService.lockScreen()
                    if (locked) {
                        ActionResult.Success(name, "Screen locked.")
                    } else {
                        ActionResult.ExecutionFailed(name, "Failed to lock screen via Accessibility Service.")
                    }
                } else {
                    ActionResult.AccessibilityNotEnabled(
                        name,
                        "Locking screen requires the Assistant Accessibility Service. Please enable FunctionGemma in Settings > Accessibility."
                    )
                }
            }
            "navigate_back" -> {
                if (AssistantAccessibilityService.isServiceRunning) {
                    AssistantAccessibilityService.navigateBack()
                    ActionResult.Success(name, "Navigated back.")
                } else {
                    ActionResult.AccessibilityNotEnabled(
                        name,
                        "Back navigation requires the Assistant Accessibility Service. Please enable it in Settings > Accessibility."
                    )
                }
            }
            "show_recents" -> {
                if (AssistantAccessibilityService.isServiceRunning) {
                    AssistantAccessibilityService.showRecents()
                    ActionResult.Success(name, "Opened Recent Apps overview.")
                } else {
                    ActionResult.AccessibilityNotEnabled(
                        name,
                        "Opening Recent Apps requires the Assistant Accessibility Service. Please enable it in Settings > Accessibility."
                    )
                }
            }
            "close_or_minimize_app" -> {
                val app = a.optString("app_name").ifBlank { "the application" }
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                ActionResult.Success(
                    name,
                    "Navigated to Home screen. Note: On Android 14+, third-party apps cannot force-stop other applications. Swipe $app away from the Recent Apps list to close it."
                )
            }
            "open_play_store_search" -> {
                val q = a.optString("query")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(q)}")).apply {
                    setPackage("com.android.vending")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                    ActionResult.Success(name, "Opened Google Play Store search for “$q”.")
                } catch (_: ActivityNotFoundException) {
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=${Uri.encode(q)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(webIntent)
                    ActionResult.Success(name, "Opened web Play Store search for “$q”.")
                }
            }
            "open_play_store_listing" -> {
                val app = a.optString("app_name")
                val pkg = a.optString("package_name").ifBlank {
                    when (app.lowercase()) {
                        "whatsapp" -> "com.whatsapp"
                        "youtube" -> "com.google.android.youtube"
                        "spotify" -> "com.spotify.music"
                        "chrome" -> "com.android.chrome"
                        else -> null
                    }
                }
                val intent = if (pkg != null) {
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                        setPackage("com.android.vending")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                } else {
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(app)}")).apply {
                        setPackage("com.android.vending")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                try {
                    context.startActivity(intent)
                    ActionResult.Success(name, "Opened Play Store listing for “$app”. Please review and tap Install.")
                } catch (_: ActivityNotFoundException) {
                    val webUrl = if (pkg != null) "https://play.google.com/store/apps/details?id=$pkg" else "https://play.google.com/store/search?q=${Uri.encode(app)}"
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ActionResult.Success(name, "Opened web Play Store for “$app”. Please review and tap Install.")
                }
            }
            "open_play_store_updates" -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps?category=UPDATES")).apply {
                    setPackage("com.android.vending")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                    ActionResult.Success(name, "Opened Google Play Store updates screen. Review and update your apps in the Play Store.")
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps?category=UPDATES")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ActionResult.Success(name, "Opened web Play Store updates.")
                }
            }
            "request_app_install" -> {
                val app = a.optString("app_name")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(app)}")).apply {
                    setPackage("com.android.vending")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                    ActionResult.Success(name, "Opened Google Play Store to install “$app”. Please confirm installation in the Play Store.")
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=${Uri.encode(app)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ActionResult.Success(name, "Opened web Play Store to install “$app”.")
                }
            }
            "request_app_update" -> {
                val app = a.optString("app_name")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(app)}")).apply {
                    setPackage("com.android.vending")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                    ActionResult.Success(name, "Opened Google Play Store to update “$app”. Please confirm update in the Play Store.")
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=${Uri.encode(app)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ActionResult.Success(name, "Opened web Play Store to update “$app”.")
                }
            }
            "open_split_screen" -> {
                val primary = a.optString("primary_app").ifBlank { "WhatsApp" }
                val secondary = a.optString("secondary_app").ifBlank { "YouTube" }
                if (AssistantAccessibilityService.isServiceRunning) {
                    execute("open_app", JSONObject().put("app_name", primary))
                    AssistantAccessibilityService.toggleSplitScreen()
                    val secPkg = when (secondary.lowercase()) {
                        "youtube" -> "com.google.android.youtube"
                        "chrome" -> "com.android.chrome"
                        "calculator" -> "com.google.android.calculator"
                        "maps" -> "com.google.android.apps.maps"
                        else -> null
                    }
                    val secIntent = secPkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
                    if (secIntent != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        secIntent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(secIntent)
                    } else {
                        execute("open_app", JSONObject().put("app_name", secondary))
                    }
                    ActionResult.Success(name, "Opened $primary and $secondary in split screen mode.")
                } else {
                    ActionResult.MultiWindowUnavailable(
                        "Split-screen automation requires the Assistant Accessibility Service. Please enable it in Settings > Accessibility."
                    )
                }
            }
            "open_floating_window" -> {
                ActionResult.FloatingWindowUnsupported(
                    "Floating window / Freeform mode is an OEM-specific proprietary feature on Realme UI / Android without standard public 3rd-party intent APIs. Please open the app in split-screen or full-screen."
                )
            }

            "create_alarm" -> {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour"))
                    putExtra(AlarmClock.EXTRA_MINUTES, a.getInt("minutes"))
                    if (a.has("message")) putExtra(AlarmClock.EXTRA_MESSAGE, a.getString("message"))
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult.Success(name, "Opened alarm clock for ${a.getInt("hour")}:${String.format("%02d", a.getInt("minutes"))}.")
            }
            "create_timer" -> {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("duration_seconds"))
                    if (a.has("message")) putExtra(AlarmClock.EXTRA_MESSAGE, a.getString("message"))
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult.Success(name, "Started timer for ${a.getInt("duration_seconds")} seconds.")
            }
            "open_clock_stopwatch" -> {
                val intent = Intent(AlarmClock.ACTION_SHOW_TIMERS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionResult.Success(name, "Opened Clock / Timers.")
            }

            "create_contact" -> {
                val nameStr = listOf(a.optString("first_name"), a.optString("last_name")).filter { it.isNotBlank() }.joinToString(" ")
                val i = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                i.putExtra(ContactsContract.Intents.Insert.NAME, nameStr)
                if (a.has("phone_number")) i.putExtra(ContactsContract.Intents.Insert.PHONE, a.getString("phone_number"))
                if (a.has("email")) i.putExtra(ContactsContract.Intents.Insert.EMAIL, a.getString("email"))
                context.startActivity(i)
                ActionResult.Success(name, "Opened contact form for “$nameStr”.")
            }

            "dial_phone_number" -> {
                val phone = a.getString("phone_number")
                val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                ActionResult.Success(name, "Opened phone dialer for $phone.")
            }

            "create_calendar_event" -> {
                val s = a.optLong("start_millis", System.currentTimeMillis() + 3600000L)
                val e = a.optLong("end_millis", s + 3600000L)
                val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                i.putExtra(CalendarContract.Events.TITLE, a.getString("title"))
                i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, s)
                i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e)
                if (a.has("description")) i.putExtra(CalendarContract.Events.DESCRIPTION, a.getString("description"))
                context.startActivity(i)
                ActionResult.Success(name, "Opened calendar event for “${a.getString("title")}”.")
            }

            "send_email", "compose_email" -> {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(a.getString("to"))}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                i.putExtra(Intent.EXTRA_SUBJECT, a.optString("subject"))
                i.putExtra(Intent.EXTRA_TEXT, a.optString("body"))
                context.startActivity(i)
                ActionResult.Success(name, "Opened email composer for ${a.getString("to")}.")
            }

            "compose_sms" -> {
                val phone = a.getString("phone_number")
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phone)}")).apply {
                    putExtra("sms_body", a.optString("message"))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(i)
                ActionResult.Success(name, "Opened SMS composer for $phone.")
            }

            "copy_to_clipboard" -> {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Assistant", a.getString("text"))
                cm.setPrimaryClip(clip)
                ActionResult.Success(name, "Copied text to clipboard.")
            }
            "share_text" -> {
                val text = a.optString("text").ifBlank { "FunctionGemma Voice Assistant" }
                val i = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, text)
                    type = "text/plain"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(i, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Share sheet.")
            }

            "take_photo" -> {
                val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                ActionResult.Success(name, "Opened Camera.")
            }
            "open_video_camera" -> {
                val i = Intent(MediaStore.ACTION_VIDEO_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                ActionResult.Success(name, "Opened Video Camera.")
            }
            "open_photo_gallery" -> {
                val i = Intent(Intent.ACTION_VIEW).apply {
                    type = "image/*"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(i)
                ActionResult.Success(name, "Opened Photo Gallery.")
            }

            "create_note" -> {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, a.getString("text"))
                    if (a.has("title")) putExtra(Intent.EXTRA_SUBJECT, a.getString("title"))
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(Intent.createChooser(i, "Save Note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened note composer.")
            }

            "get_battery_status" -> {
                val ifilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val b = context.registerReceiver(null, ifilter)
                val level = b?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = b?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                ActionResult.Success(name, "Battery level is currently $pct%.")
            }
            "get_device_info" -> {
                ActionResult.Success(name, "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}.")
            }
            "check_current_time_date" -> {
                val nowStr = java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy h:mm a"))
                ActionResult.Success(name, "Current date and time: $nowStr")
            }

            else -> ActionResult.UnsupportedAction(name)
        }
    } catch (t: Throwable) {
        ActionResult.ExecutionFailed(name, t.message ?: t.javaClass.simpleName)
    }

    private var flashlightOn = false
    private var torchCallbackRegistered = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            super.onTorchModeChanged(cameraId, enabled)
            if (cameraId == flashCameraId) {
                flashlightOn = enabled
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            super.onTorchModeUnavailable(cameraId)
            if (cameraId == flashCameraId) {
                flashlightOn = false
            }
        }
    }

    private fun registerTorchListener() {
        if (!torchCallbackRegistered) {
            try {
                cameraManager?.registerTorchCallback(torchCallback, null)
                torchCallbackRegistered = true
            } catch (_: Throwable) {}
        }
    }

    private fun getOrFindFlashCameraId(): String? {
        if (flashCameraId != null) return flashCameraId
        val cm = cameraManager ?: return null
        return try {
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val flashAvailable = chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                if (flashAvailable) {
                    flashCameraId = id
                    break
                }
            }
            flashCameraId
        } catch (_: Throwable) {
            null
        }
    }

    private fun setFlashlight(state: Boolean): ActionResult {
        val toolName = if (state) "turn_on_flashlight" else "turn_off_flashlight"
        val cm = cameraManager
        if (cm == null) {
            return ActionResult.ExecutionFailed(toolName, "CameraManager system service unavailable.")
        }
        val id = getOrFindFlashCameraId()
            ?: return ActionResult.ExecutionFailed(toolName, "Flashlight hardware is unavailable on this device.")

        registerTorchListener()
        return try {
            cm.setTorchMode(id, state)
            flashlightOn = state
            val msg = if (state) "Flashlight is on." else "Flashlight is off."
            ActionResult.Success(toolName, msg)
        } catch (e: android.hardware.camera2.CameraAccessException) {
            ActionResult.ExecutionFailed(toolName, "Camera hardware error or in use: ${e.message}")
        } catch (e: SecurityException) {
            ActionResult.PermissionDenied(toolName, Manifest.permission.CAMERA)
        } catch (e: Throwable) {
            ActionResult.ExecutionFailed(toolName, "Failed to toggle flashlight: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun toggleFlashlight(): ActionResult {
        return setFlashlight(!flashlightOn)
    }

    companion object {
        val specificAppTools = mapOf(
            "open_whatsapp" to "WhatsApp",
            "open_youtube" to "YouTube",
            "open_chrome" to "Chrome",
            "open_spotify" to "Spotify",
            "open_camera" to "Camera",
            "open_maps" to "Google Maps",
            "open_photos" to "Photos",
            "open_calculator" to "Calculator",
            "open_gmail" to "Gmail",
            "open_messages" to "Messages",
            "open_phone" to "Phone"
        )

        fun parseTimeStringToHourMinute(str: String): Pair<Int, Int>? {
            val clean = str.trim()
            val colonMatch = Regex("(\\d{1,2}):(\\d{2})\\s*(AM|PM)?", RegexOption.IGNORE_CASE).find(clean)
            if (colonMatch != null) {
                var h = colonMatch.groupValues[1].toIntOrNull() ?: return null
                val m = colonMatch.groupValues[2].toIntOrNull() ?: return null
                val ampm = colonMatch.groupValues[3].uppercase()
                if (ampm == "PM" && h < 12) h += 12
                if (ampm == "AM" && h == 12) h = 0
                return if (h in 0..23 && m in 0..59) Pair(h, m) else null
            }
            val ampmMatch = Regex("(\\d{1,2})\\s*(AM|PM)", RegexOption.IGNORE_CASE).find(clean)
            if (ampmMatch != null) {
                var h = ampmMatch.groupValues[1].toIntOrNull() ?: return null
                val ampm = ampmMatch.groupValues[2].uppercase()
                if (ampm == "PM" && h < 12) h += 12
                if (ampm == "AM" && h == 12) h = 0
                return if (h in 0..23) Pair(h, 0) else null
            }
            return null
        }

        fun parseDurationToSeconds(str: String): Int {
            if (str.isBlank()) return -1
            str.toIntOrNull()?.let { return it }
            val minMatch = Regex("(\\d+)\\s*(m|min|minute|minutes)", RegexOption.IGNORE_CASE).find(str)
            if (minMatch != null) {
                val mins = minMatch.groupValues[1].toIntOrNull() ?: 0
                return mins * 60
            }
            val secMatch = Regex("(\\d+)\\s*(s|sec|second|seconds)", RegexOption.IGNORE_CASE).find(str)
            if (secMatch != null) {
                return secMatch.groupValues[1].toIntOrNull() ?: 0
            }
            val hourMatch = Regex("(\\d+)\\s*(h|hr|hour|hours)", RegexOption.IGNORE_CASE).find(str)
            if (hourMatch != null) {
                val hours = hourMatch.groupValues[1].toIntOrNull() ?: 0
                return hours * 3600
            }
            return -1
        }

        fun resolveAudioStream(streamName: String?): Pair<Int, String> {
            return when (streamName?.lowercase(java.util.Locale.ROOT)?.trim()) {
                "ring", "ringer", "call_ring" -> Pair(AudioManager.STREAM_RING, "Ring")
                "notification", "notifications" -> Pair(AudioManager.STREAM_NOTIFICATION, "Notification")
                "alarm", "alarms" -> Pair(AudioManager.STREAM_ALARM, "Alarm")
                "call", "voice_call", "voice", "in_call" -> Pair(AudioManager.STREAM_VOICE_CALL, "Voice Call")
                "system" -> Pair(AudioManager.STREAM_SYSTEM, "System")
                "media", "music", null, "" -> Pair(AudioManager.STREAM_MUSIC, "Media")
                else -> Pair(AudioManager.STREAM_MUSIC, "Media")
            }
        }
    }

    fun resolveAudioStream(streamName: String?): Pair<Int, String> = Companion.resolveAudioStream(streamName)
}

private class SchemaTool(
    private val name: String,
    private val description: String,
    private val params: String
) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String =
        """{"name":"$name","description":"$description","parameters":$params}"""

    override fun execute(paramsJsonString: String): String =
        """{"status":"manual_dispatch_required","tool":"$name"}"""
}
