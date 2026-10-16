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
import java.io.InputStreamReader

data class PreparedAction(
    val name: String,
    val args: JSONObject,
    val summary: String,
    val requiresConfirmation: Boolean
)

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

    fun parseArgs(raw: String): JSONObject = try { JSONObject(raw) } catch (_: Throwable) { JSONObject() }

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
            "open_mobile_data_settings", "open_nfc_settings", "open_accessibility_settings" -> {
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
                val url = args.optString("url").trim()
                // Strict URL validation: HTTPS ONLY to prevent file:, intent:, javascript: injection
                if (!url.startsWith("https://", ignoreCase = true) || url.length > 500) return null
                clean.put("url", url)
                sensitive = false
            }

            // App Navigation
            "open_app" -> {
                val app = args.optString("app_name").trim()
                if (app.isBlank() || app.length > 100) return null
                clean.put("app_name", app)
                sensitive = false
            }
            "go_home_screen" -> {
                sensitive = false
            }

            // Clock, Alarm, Timer
            "create_alarm" -> {
                val hour = args.optInt("hour", -1)
                val minutes = args.optInt("minutes", 0)
                if (hour !in 0..23 || minutes !in 0..59) return null
                clean.put("hour", hour)
                clean.put("minutes", minutes)
                val msg = args.optString("message").trim()
                if (msg.isNotBlank()) clean.put("message", msg.take(100))
                sensitive = false
            }
            "create_timer" -> {
                val sec = args.optInt("duration_seconds", args.optInt("seconds", -1))
                if (sec <= 0 || sec > 86400) return null
                clean.put("duration_seconds", sec)
                val msg = args.optString("message").trim()
                if (msg.isNotBlank()) clean.put("message", msg.take(100))
                sensitive = false
            }
            "open_clock_stopwatch" -> {
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
                val s = args.optString("start_iso")
                val e = args.optString("end_iso")
                if (title.isBlank() || title.length > 120) return null
                val sm = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull() ?: return null
                val em = runCatching { java.time.OffsetDateTime.parse(e).toInstant().toEpochMilli() }.getOrNull() ?: return null
                if (em <= sm || em - sm > 604800000L) return null
                clean.put("title", title)
                clean.put("start_iso", s)
                clean.put("end_iso", e)
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

            // Clipboard
            "copy_to_clipboard" -> {
                val text = args.optString("text").trim()
                if (text.isBlank() || text.length > 5000) return null
                clean.put("text", text)
                sensitive = false
            }

            // Camera / Media capture
            "take_photo", "open_video_camera" -> {
                sensitive = false
            }
            "open_photo_gallery" -> {
                sensitive = false
            }

            // Notes
            "create_note" -> {
                val text = args.optString("text").ifBlank { args.optString("content") }.trim()
                if (text.isBlank() || text.length > 5000) return null
                clean.put("text", text)
                clean.put("title", args.optString("title").take(100))
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
    fun execute(name: String, a: JSONObject): String = try {
        when (name) {
            "turn_on_flashlight" -> setFlashlight(true)
            "turn_off_flashlight" -> setFlashlight(false)
            "toggle_flashlight" -> toggleFlashlight()

            "open_wifi_settings" -> {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened Wi-Fi settings."
            }
            "open_bluetooth_pairing" -> {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened Bluetooth settings."
            }
            "open_system_settings" -> {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened System Settings."
            }
            "open_battery_saver_settings" -> {
                context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened Battery Saver settings."
            }
            "open_storage_settings" -> {
                context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened Storage settings."
            }
            "open_security_settings" -> {
                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened Security settings."
            }
            "open_date_time_settings" -> {
                context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened Date & Time settings."
            }
            "open_developer_options" -> {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened Developer Options."
            }
            "open_airplane_mode_settings" -> {
                context.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened Airplane Mode settings."
            }
            "open_mobile_data_settings" -> {
                context.startActivity(Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened Mobile Data settings."
            }
            "open_nfc_settings" -> {
                context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened NFC settings."
            }
            "open_accessibility_settings" -> {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened Accessibility settings."
            }

            "show_location_on_map", "show_map" -> {
                val q = a.getString("query")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(q)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Opened map search for $q."
            }
            "navigate_to_place" -> {
                val dest = a.getString("destination")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Started navigation to $dest."
            }

            "search_web" -> {
                val q = a.getString("query")
                val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(SearchManager.QUERY, q)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opened web search for “$q”."
            }
            "open_url" -> {
                val url = a.getString("url")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Opened URL: $url"
            }

            "open_app" -> {
                val appName = a.getString("app_name")
                val pm = context.packageManager
                val intent = pm.getLaunchIntentForPackage(appName) ?: run {
                    // Try to match by app name in installed applications
                    val packages = pm.getInstalledApplications(0)
                    val matched = packages.firstOrNull { pm.getApplicationLabel(it).toString().equals(appName, ignoreCase = true) }
                    matched?.let { pm.getLaunchIntentForPackage(it.packageName) }
                }
                if (intent != null) {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Opened app: $appName"
                } else {
                    "App “$appName” is not installed or cannot be launched."
                }
            }
            "go_home_screen" -> {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                "Navigated to Home Screen."
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
                "Opened alarm clock to set alarm for ${a.getInt("hour")}:${String.format("%02d", a.getInt("minutes"))}."
            }
            "create_timer" -> {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("duration_seconds"))
                    if (a.has("message")) putExtra(AlarmClock.EXTRA_MESSAGE, a.getString("message"))
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Started timer for ${a.getInt("duration_seconds")} seconds."
            }
            "open_clock_stopwatch" -> {
                val intent = Intent(AlarmClock.ACTION_SHOW_TIMERS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Opened Clock / Timers."
            }

            "create_contact" -> {
                val name = listOf(a.optString("first_name"), a.optString("last_name")).filter { it.isNotBlank() }.joinToString(" ")
                val i = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                i.putExtra(ContactsContract.Intents.Insert.NAME, name)
                if (a.has("phone_number")) i.putExtra(ContactsContract.Intents.Insert.PHONE, a.getString("phone_number"))
                if (a.has("email")) i.putExtra(ContactsContract.Intents.Insert.EMAIL, a.getString("email"))
                context.startActivity(i)
                "Opened contact form for “$name”; review and save manually."
            }

            "dial_phone_number" -> {
                val phone = a.getString("phone_number")
                val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                "Opened phone dialer for $phone."
            }

            "create_calendar_event" -> {
                val s = java.time.OffsetDateTime.parse(a.getString("start_iso")).toInstant().toEpochMilli()
                val e = java.time.OffsetDateTime.parse(a.getString("end_iso")).toInstant().toEpochMilli()
                val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                i.putExtra(CalendarContract.Events.TITLE, a.getString("title"))
                i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, s)
                i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e)
                if (a.has("description")) i.putExtra(CalendarContract.Events.DESCRIPTION, a.getString("description"))
                context.startActivity(i)
                "Opened calendar form for “${a.getString("title")}”; review and save manually."
            }

            "send_email", "compose_email" -> {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(a.getString("to"))}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                i.putExtra(Intent.EXTRA_SUBJECT, a.optString("subject"))
                i.putExtra(Intent.EXTRA_TEXT, a.optString("body"))
                context.startActivity(i)
                "Opened email composer. Message was not sent silently."
            }

            "compose_sms" -> {
                val phone = a.getString("phone_number")
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phone)}")).apply {
                    putExtra("sms_body", a.optString("message"))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(i)
                "Opened SMS composer for $phone."
            }

            "copy_to_clipboard" -> {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Assistant", a.getString("text"))
                cm.setPrimaryClip(clip)
                "Copied text to clipboard."
            }

            "take_photo" -> {
                val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                "Opened Camera to take photo."
            }
            "open_video_camera" -> {
                val i = Intent(MediaStore.ACTION_VIDEO_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                "Opened Camera to record video."
            }
            "open_photo_gallery" -> {
                val i = Intent(Intent.ACTION_VIEW).apply {
                    type = "image/*"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(i)
                "Opened Photo Gallery."
            }

            "create_note" -> {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, a.getString("text"))
                    if (a.has("title")) putExtra(Intent.EXTRA_SUBJECT, a.getString("title"))
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(Intent.createChooser(i, "Save Note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened note composer."
            }

            "get_battery_status" -> {
                val ifilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val b = context.registerReceiver(null, ifilter)
                val level = b?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = b?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                "Battery level is currently $pct%."
            }
            "get_device_info" -> {
                "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})."
            }
            "check_current_time_date" -> {
                "Current date and time: ${java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy h:mm a"))}"
            }

            else -> "Blocked unsupported action: $name"
        }
    } catch (t: Throwable) {
        "Action failed: ${t.message ?: t.javaClass.simpleName}"
    }

    private var flashlightOn = false
    private fun setFlashlight(state: Boolean): String {
        val id = flashCameraId ?: return "Flashlight hardware is unavailable on this device."
        return try {
            cameraManager?.setTorchMode(id, state)
            flashlightOn = state
            if (state) "Turned on flashlight." else "Turned off flashlight."
        } catch (e: Throwable) {
            "Failed to toggle flashlight: ${e.message}"
        }
    }

    private fun toggleFlashlight(): String {
        return setFlashlight(!flashlightOn)
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
