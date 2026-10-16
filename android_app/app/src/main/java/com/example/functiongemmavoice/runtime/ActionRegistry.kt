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
import java.io.InputStreamReader

data class PreparedAction(
    val name: String,
    val args: JSONObject,
    val summary: String,
    val requiresConfirmation: Boolean
)

sealed class ActionResult {
    data class Success(val tool: String, val message: String) : ActionResult()
    data class NeedsConfirmation(val action: PreparedAction) : ActionResult()
    data class InvalidArguments(val tool: String, val reason: String) : ActionResult()
    data class PermissionDenied(val tool: String, val permission: String) : ActionResult()
    data class UnsupportedAction(val tool: String) : ActionResult()
    data class ExecutionFailed(val tool: String, val error: String) : ActionResult()
    data class Cancelled(val tool: String) : ActionResult()

    fun userFacingMessage(): String = when (this) {
        is Success -> message
        is NeedsConfirmation -> "Action '${action.name}' requires confirmation."
        is InvalidArguments -> "Invalid arguments for $tool: $reason"
        is PermissionDenied -> "Permission required: $permission"
        is UnsupportedAction -> "Action '$tool' is not supported."
        is ExecutionFailed -> "Failed to execute $tool: $error"
        is Cancelled -> "Action '$tool' was cancelled."
    }
}

class ActionRegistry(private val context: Context) {

    private var flashCameraId: String? = null
    private val emailRegex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\$")
    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
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
        val clean = JSONObject()
        val sensitive: Boolean

        when (name) {
            // Flashlight
            "turn_on_flashlight", "turn_off_flashlight", "toggle_flashlight" -> {
                sensitive = false
            }

            // Settings
            "open_wifi_settings", "open_bluetooth_pairing", "open_system_settings",
            "open_battery_saver_settings", "open_storage_settings", "open_security_settings",
            "open_date_time_settings", "open_developer_options", "open_airplane_mode_settings",
            "open_mobile_data_settings", "open_nfc_settings", "open_accessibility_settings",
            "open_sound_settings", "open_volume_settings", "open_display_settings",
            "open_location_settings", "open_dnd_settings", "open_calendar" -> {
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
            "go_home_screen" -> {
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

        return PreparedAction(name, clean, "$name $clean", sensitive)
    }

    /**
     * Executes approved action via Android APIs or Intents.
     */
    /**
     * Executes approved action via Android APIs or Intents.
     * Returns structured ActionResult with truthful execution status.
     */
    fun execute(name: String, a: JSONObject): ActionResult = try {
        when (name) {
            "turn_on_flashlight" -> setFlashlight(true)
            "turn_off_flashlight" -> setFlashlight(false)
            "toggle_flashlight" -> toggleFlashlight()

            "open_wifi_settings" -> {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Wi-Fi settings.")
            }
            "open_bluetooth_pairing" -> {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionResult.Success(name, "Opened Bluetooth settings.")
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
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ActionResult.Success(name, "Opened app: $appName")
                } else {
                    ActionResult.ExecutionFailed(name, "App “$appName” is not installed or cannot be launched.")
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
    }
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
