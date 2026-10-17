package com.example.functiongemmavoice.runtime.capability

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.functiongemmavoice.runtime.intent.CapabilityStatus
import com.example.functiongemmavoice.runtime.intent.IntentDomain
import org.json.JSONArray
import org.json.JSONObject

enum class VerificationMethod {
    INTENT_RESOLVED,
    CAMERA_TORCH_CALLBACK,
    SYSTEM_STATE_QUERY,
    USER_CONFIRMATION,
    EXTERNAL_SYSTEM_DIALOG,
    CLIPBOARD_VERIFIED,
    TIME_QUERY_VERIFIED
}

data class CapabilityDefinition(
    val canonicalId: String,
    val aliases: List<String> = emptyList(),
    val domain: IntentDomain,
    val description: String,
    val requiredParameters: List<String> = emptyList(),
    val optionalParameters: List<String> = emptyList(),
    val requiredPermissions: List<String> = emptyList(),
    val minSdk: Int = 26,
    val targetSdkConstraint: String? = null,
    val requiresSystemUi: Boolean = false,
    val requiresConfirmation: Boolean = false,
    val isRetrySafe: Boolean = false,
    val verificationMethod: VerificationMethod = VerificationMethod.INTENT_RESOLVED,
    val recoveryHint: String = "Please try again or open settings manually."
) {
    fun checkStatus(context: Context): CapabilityStatus {
        if (Build.VERSION.SDK_INT in 1 until minSdk) {
            return CapabilityStatus.UNAVAILABLE
        }
        for (perm in requiredPermissions) {
            if (context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                return CapabilityStatus.AVAILABLE_WITH_PERMISSION
            }
        }
        if (requiresSystemUi) {
            return CapabilityStatus.REQUIRES_SYSTEM_UI
        }
        return CapabilityStatus.AVAILABLE
    }
}

data class ConsistencyReport(
    val registeredToolCount: Int,
    val catalogToolCount: Int,
    val missingFromRegistry: List<String>,
    val missingFromCatalog: List<String>,
    val warnings: List<String>
) {
    val isConsistent: Boolean
        get() = missingFromRegistry.isEmpty() && warnings.isEmpty()
}

class CapabilityRegistry(private val context: Context) {

    private val capabilities = LinkedHashMap<String, CapabilityDefinition>()
    private val aliasMap = HashMap<String, String>()

    init {
        registerAllCapabilities()
    }

    private fun register(cap: CapabilityDefinition) {
        capabilities[cap.canonicalId] = cap
        for (alias in cap.aliases) {
            aliasMap[alias.lowercase()] = cap.canonicalId
        }
        aliasMap[cap.canonicalId.lowercase()] = cap.canonicalId
    }

    private fun registerAllCapabilities() {
        // Bluetooth
        register(
            CapabilityDefinition(
                canonicalId = "open_bluetooth_settings",
                aliases = listOf("open_bluetooth_pairing"),
                domain = IntentDomain.SETTINGS_NAVIGATION,
                description = "Opens the device Bluetooth settings screen.",
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED,
                recoveryHint = "Open device Settings and navigate to Bluetooth."
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "request_bluetooth_enable",
                aliases = listOf("turn_on_bluetooth"),
                domain = IntentDomain.STATE_MUTATION,
                description = "Requests Bluetooth activation through supported Android dialog or adapter.",
                requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    listOf(Manifest.permission.BLUETOOTH_CONNECT)
                } else emptyList(),
                requiresSystemUi = true,
                isRetrySafe = false,
                verificationMethod = VerificationMethod.EXTERNAL_SYSTEM_DIALOG,
                recoveryHint = "Allow Bluetooth permission in App Info or enable in Quick Settings."
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "request_bluetooth_disable",
                aliases = listOf("turn_off_bluetooth"),
                domain = IntentDomain.STATE_MUTATION,
                description = "Directs user to Bluetooth settings because Android 13+ restricts 3rd party apps from disabling Bluetooth.",
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED,
                recoveryHint = "Toggle Bluetooth off in Quick Settings or Bluetooth settings."
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "get_bluetooth_state",
                aliases = listOf("check_bluetooth_state"),
                domain = IntentDomain.SYSTEM_INSPECTION,
                description = "Queries current Bluetooth adapter state (ON / OFF / TURNING_ON).",
                requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    listOf(Manifest.permission.BLUETOOTH_CONNECT)
                } else emptyList(),
                isRetrySafe = true,
                verificationMethod = VerificationMethod.SYSTEM_STATE_QUERY,
                recoveryHint = "Grant Nearby Devices / Bluetooth permission."
            )
        )

        // Wi-Fi
        register(
            CapabilityDefinition(
                canonicalId = "open_wifi_settings",
                domain = IntentDomain.SETTINGS_NAVIGATION,
                description = "Opens Wi-Fi settings.",
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "get_wifi_state",
                domain = IntentDomain.SYSTEM_INSPECTION,
                description = "Queries Wi-Fi connection and active network status.",
                requiredPermissions = listOf(Manifest.permission.ACCESS_NETWORK_STATE),
                isRetrySafe = true,
                verificationMethod = VerificationMethod.SYSTEM_STATE_QUERY
            )
        )

        // Flashlight
        register(
            CapabilityDefinition(
                canonicalId = "turn_on_flashlight",
                domain = IntentDomain.STATE_MUTATION,
                description = "Turns on the back camera flashlight.",
                requiredPermissions = listOf(Manifest.permission.CAMERA),
                isRetrySafe = true,
                verificationMethod = VerificationMethod.CAMERA_TORCH_CALLBACK
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "turn_off_flashlight",
                domain = IntentDomain.STATE_MUTATION,
                description = "Turns off the flashlight.",
                isRetrySafe = true,
                verificationMethod = VerificationMethod.CAMERA_TORCH_CALLBACK
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "toggle_flashlight",
                domain = IntentDomain.STATE_MUTATION,
                description = "Toggles flashlight torch state.",
                requiredPermissions = listOf(Manifest.permission.CAMERA),
                isRetrySafe = false,
                verificationMethod = VerificationMethod.CAMERA_TORCH_CALLBACK
            )
        )

        // Battery & Device Info
        register(
            CapabilityDefinition(
                canonicalId = "get_battery_status",
                domain = IntentDomain.SYSTEM_INSPECTION,
                description = "Returns current battery percentage and charging status.",
                isRetrySafe = true,
                verificationMethod = VerificationMethod.SYSTEM_STATE_QUERY
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "get_device_info",
                domain = IntentDomain.SYSTEM_INSPECTION,
                description = "Returns device model, manufacturer, and Android OS version.",
                isRetrySafe = true,
                verificationMethod = VerificationMethod.SYSTEM_STATE_QUERY
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "check_current_time_date",
                domain = IntentDomain.INFORMATION_QUERY,
                description = "Returns formatted current time and date.",
                isRetrySafe = true,
                verificationMethod = VerificationMethod.TIME_QUERY_VERIFIED
            )
        )

        // Settings Navigation
        val settingsList = listOf(
            "open_sound_settings", "open_volume_settings", "open_display_settings",
            "open_location_settings", "open_airplane_mode_settings", "open_dnd_settings",
            "open_system_settings", "open_battery_saver_settings", "open_storage_settings",
            "open_security_settings", "open_date_time_settings", "open_developer_options",
            "open_mobile_data_settings", "open_nfc_settings", "open_accessibility_settings",
            "open_wallpaper_settings", "open_font_settings", "open_screen_timeout_settings",
            "open_battery_settings", "open_app_permissions_settings", "open_app_notification_settings",
            "open_app_details_settings", "open_hotspot_settings", "open_network_settings",
            "open_vpn_settings", "open_language_settings", "open_privacy_settings", "open_wifi_panel"
        )
        for (s in settingsList) {
            register(
                CapabilityDefinition(
                    canonicalId = s,
                    domain = IntentDomain.SETTINGS_NAVIGATION,
                    description = "Opens corresponding device setting: $s",
                    requiresSystemUi = true,
                    isRetrySafe = true,
                    verificationMethod = VerificationMethod.INTENT_RESOLVED
                )
            )
        }

        // Brightness & Display Control
        register(
            CapabilityDefinition(
                canonicalId = "get_brightness",
                domain = IntentDomain.SYSTEM_INSPECTION,
                description = "Queries current screen brightness percentage and raw value.",
                isRetrySafe = true,
                verificationMethod = VerificationMethod.SYSTEM_STATE_QUERY
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "set_brightness",
                domain = IntentDomain.STATE_MUTATION,
                description = "Sets screen brightness level or percentage (requires WRITE_SETTINGS).",
                requiredParameters = listOf("percentage"),
                requiredPermissions = listOf(Manifest.permission.WRITE_SETTINGS),
                isRetrySafe = true,
                verificationMethod = VerificationMethod.SYSTEM_STATE_QUERY
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "adjust_brightness",
                domain = IntentDomain.STATE_MUTATION,
                description = "Adjusts screen brightness up or down by percentage.",
                optionalParameters = listOf("direction", "delta"),
                requiredPermissions = listOf(Manifest.permission.WRITE_SETTINGS),
                isRetrySafe = false,
                verificationMethod = VerificationMethod.SYSTEM_STATE_QUERY
            )
        )

        // Volume Streams & Sound Modes
        register(
            CapabilityDefinition(
                canonicalId = "get_volume",
                domain = IntentDomain.SYSTEM_INSPECTION,
                description = "Gets current volume for specified stream (media, ring, notification, alarm, call).",
                optionalParameters = listOf("stream"),
                isRetrySafe = true,
                verificationMethod = VerificationMethod.SYSTEM_STATE_QUERY
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "set_volume",
                domain = IntentDomain.STATE_MUTATION,
                description = "Sets volume for specified stream (media, ring, notification, alarm, call).",
                optionalParameters = listOf("stream", "percentage", "level"),
                isRetrySafe = true,
                verificationMethod = VerificationMethod.SYSTEM_STATE_QUERY
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "adjust_volume",
                domain = IntentDomain.STATE_MUTATION,
                description = "Raises or lowers volume for specified stream.",
                optionalParameters = listOf("stream", "direction", "delta"),
                isRetrySafe = false,
                verificationMethod = VerificationMethod.SYSTEM_STATE_QUERY
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "mute_stream",
                domain = IntentDomain.STATE_MUTATION,
                description = "Mutes the specified audio stream.",
                optionalParameters = listOf("stream"),
                isRetrySafe = true,
                verificationMethod = VerificationMethod.SYSTEM_STATE_QUERY
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "unmute_stream",
                domain = IntentDomain.STATE_MUTATION,
                description = "Unmutes the specified audio stream.",
                optionalParameters = listOf("stream"),
                isRetrySafe = true,
                verificationMethod = VerificationMethod.SYSTEM_STATE_QUERY
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "set_ringer_mode",
                domain = IntentDomain.STATE_MUTATION,
                description = "Sets device ringer mode to normal, vibrate, or silent.",
                requiredParameters = listOf("mode"),
                requiredPermissions = listOf(Manifest.permission.ACCESS_NOTIFICATION_POLICY),
                isRetrySafe = true,
                verificationMethod = VerificationMethod.SYSTEM_STATE_QUERY
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "set_dnd_mode",
                domain = IntentDomain.STATE_MUTATION,
                description = "Enables or disables Do Not Disturb mode (requires ACCESS_NOTIFICATION_POLICY).",
                optionalParameters = listOf("enabled", "filter"),
                requiredPermissions = listOf(Manifest.permission.ACCESS_NOTIFICATION_POLICY),
                isRetrySafe = true,
                verificationMethod = VerificationMethod.SYSTEM_STATE_QUERY
            )
        )

        // App Launch & Web
        register(
            CapabilityDefinition(
                canonicalId = "open_app",
                domain = IntentDomain.APP_LAUNCH,
                description = "Launches an installed app by package or name.",
                requiredParameters = listOf("app_name"),
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "open_url",
                domain = IntentDomain.APP_LAUNCH,
                description = "Opens a web link in browser.",
                requiredParameters = listOf("url"),
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "search_web",
                domain = IntentDomain.INFORMATION_QUERY,
                description = "Performs a web search.",
                requiredParameters = listOf("query"),
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "show_map",
                domain = IntentDomain.APP_LAUNCH,
                description = "Shows location on map.",
                requiredParameters = listOf("query"),
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "navigate_to_place",
                domain = IntentDomain.APP_LAUNCH,
                description = "Starts turn-by-turn navigation.",
                requiredParameters = listOf("destination"),
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "go_home_screen",
                domain = IntentDomain.SYSTEM_INSPECTION,
                description = "Navigates to home screen.",
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "lock_screen",
                domain = IntentDomain.STATE_MUTATION,
                description = "Locks the device screen via Assistant Accessibility Service.",
                requiresSystemUi = true,
                isRetrySafe = false,
                verificationMethod = VerificationMethod.SYSTEM_STATE_QUERY
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "navigate_back",
                domain = IntentDomain.SYSTEM_INSPECTION,
                description = "Issues system back action via Accessibility Service.",
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "show_recents",
                domain = IntentDomain.SYSTEM_INSPECTION,
                description = "Opens Recent Apps overview via Accessibility Service.",
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "close_or_minimize_app",
                domain = IntentDomain.APP_LAUNCH,
                description = "Returns to Home and guides user to dismiss app from Recents.",
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "open_play_store_search",
                domain = IntentDomain.APP_LAUNCH,
                description = "Opens Google Play Store search for the given query.",
                requiredParameters = listOf("query"),
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "open_play_store_listing",
                domain = IntentDomain.APP_LAUNCH,
                description = "Opens Google Play Store listing for application installation or review.",
                requiredParameters = listOf("app_name"),
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "open_play_store_updates",
                domain = IntentDomain.APP_LAUNCH,
                description = "Opens Google Play Store pending updates screen.",
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "request_app_install",
                domain = IntentDomain.APP_LAUNCH,
                description = "Opens Google Play Store to install an application with user confirmation.",
                requiredParameters = listOf("app_name"),
                requiresSystemUi = true,
                isRetrySafe = false,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "request_app_update",
                domain = IntentDomain.APP_LAUNCH,
                description = "Opens Google Play Store to update an application with user confirmation.",
                requiredParameters = listOf("app_name"),
                requiresSystemUi = true,
                isRetrySafe = false,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "open_split_screen",
                domain = IntentDomain.APP_LAUNCH,
                description = "Automates split-screen multitasking with two apps.",
                requiresSystemUi = true,
                isRetrySafe = false,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "open_floating_window",
                domain = IntentDomain.APP_LAUNCH,
                description = "Requests floating window / freeform mode (reports OEM unsupported status).",
                requiresSystemUi = true,
                isRetrySafe = false,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )

        // Time & Productivity
        register(
            CapabilityDefinition(
                canonicalId = "create_timer",
                aliases = listOf("set_timer"),
                domain = IntentDomain.TIME_PRODUCTIVITY,
                description = "Sets a countdown timer in seconds.",
                requiredParameters = listOf("duration_seconds"),
                optionalParameters = listOf("message"),
                requiresSystemUi = true,
                isRetrySafe = false,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "create_alarm",
                aliases = listOf("set_alarm"),
                domain = IntentDomain.TIME_PRODUCTIVITY,
                description = "Creates an alarm for given hour and minute.",
                requiredParameters = listOf("hour", "minute"),
                optionalParameters = listOf("message"),
                requiredPermissions = listOf(Manifest.permission.SET_ALARM),
                requiresSystemUi = true,
                isRetrySafe = false,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "open_clock_stopwatch",
                aliases = listOf("open_clock"),
                domain = IntentDomain.TIME_PRODUCTIVITY,
                description = "Opens clock stopwatch tab.",
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "open_calendar",
                domain = IntentDomain.TIME_PRODUCTIVITY,
                description = "Opens the device calendar.",
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "create_calendar_event",
                domain = IntentDomain.TIME_PRODUCTIVITY,
                description = "Schedules a calendar event.",
                requiredParameters = listOf("title"),
                requiresConfirmation = true,
                requiresSystemUi = true,
                isRetrySafe = false,
                verificationMethod = VerificationMethod.USER_CONFIRMATION
            )
        )

        // Communication (Safe & Confirmation Gates)
        register(
            CapabilityDefinition(
                canonicalId = "dial_phone_number",
                domain = IntentDomain.COMMUNICATION,
                description = "Opens dialer with phone number.",
                requiredParameters = listOf("phone_number"),
                requiresConfirmation = true,
                requiresSystemUi = true,
                isRetrySafe = false,
                verificationMethod = VerificationMethod.USER_CONFIRMATION
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "compose_sms",
                domain = IntentDomain.COMMUNICATION,
                description = "Opens messaging app with recipient and text.",
                requiredParameters = listOf("recipient", "message"),
                requiresConfirmation = true,
                requiresSystemUi = true,
                isRetrySafe = false,
                verificationMethod = VerificationMethod.USER_CONFIRMATION
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "compose_email",
                domain = IntentDomain.COMMUNICATION,
                description = "Opens email composer with draft.",
                requiredParameters = listOf("recipient", "subject", "body"),
                requiresConfirmation = true,
                requiresSystemUi = true,
                isRetrySafe = false,
                verificationMethod = VerificationMethod.USER_CONFIRMATION
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "send_email",
                domain = IntentDomain.COMMUNICATION,
                description = "Direct email compose launcher.",
                requiredParameters = listOf("recipient", "subject", "body"),
                requiresConfirmation = true,
                requiresSystemUi = true,
                isRetrySafe = false,
                verificationMethod = VerificationMethod.USER_CONFIRMATION
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "create_contact",
                domain = IntentDomain.COMMUNICATION,
                description = "Adds a contact.",
                requiredParameters = listOf("name", "phone"),
                requiresConfirmation = true,
                requiresSystemUi = true,
                isRetrySafe = false,
                verificationMethod = VerificationMethod.USER_CONFIRMATION
            )
        )

        // Media & Utilities
        register(
            CapabilityDefinition(
                canonicalId = "copy_to_clipboard",
                domain = IntentDomain.IN_APP_OPERATION,
                description = "Copies text to system clipboard.",
                requiredParameters = listOf("text"),
                isRetrySafe = true,
                verificationMethod = VerificationMethod.CLIPBOARD_VERIFIED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "share_text",
                domain = IntentDomain.COMMUNICATION,
                description = "Shares text via Android system chooser.",
                requiredParameters = listOf("text"),
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "take_photo",
                domain = IntentDomain.APP_LAUNCH,
                description = "Launches camera for photo capture.",
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "open_video_camera",
                domain = IntentDomain.APP_LAUNCH,
                description = "Launches camera in video mode.",
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "open_photo_gallery",
                domain = IntentDomain.APP_LAUNCH,
                description = "Opens photo gallery.",
                requiresSystemUi = true,
                isRetrySafe = true,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
        register(
            CapabilityDefinition(
                canonicalId = "create_note",
                domain = IntentDomain.TIME_PRODUCTIVITY,
                description = "Creates a text note.",
                requiredParameters = listOf("content"),
                requiresSystemUi = true,
                isRetrySafe = false,
                verificationMethod = VerificationMethod.INTENT_RESOLVED
            )
        )
    }

    fun get(toolOrAlias: String): CapabilityDefinition? {
        val canonical = aliasMap[toolOrAlias.lowercase()] ?: toolOrAlias.lowercase()
        return capabilities[canonical]
    }

    fun resolveCanonicalName(toolOrAlias: String): String {
        return aliasMap[toolOrAlias.lowercase()] ?: toolOrAlias.lowercase()
    }

    fun all(): List<CapabilityDefinition> = capabilities.values.toList()

    fun usableCount(): Int = capabilities.values.count { it.checkStatus(context) != CapabilityStatus.UNAVAILABLE }

    fun validateConsistency(catalogJson: String): ConsistencyReport {
        val catalogTools = mutableListOf<String>()
        try {
            val array = JSONArray(catalogJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                catalogTools.add(obj.getString("name").lowercase())
            }
        } catch (_: Throwable) {}

        val registered = capabilities.keys.map { it.lowercase() }.toSet()
        val catalogSet = catalogTools.toSet()

        val missingFromRegistry = catalogSet.filter { it !in registered && aliasMap[it] == null }
        val missingFromCatalog = registered.filter { it !in catalogSet && it !in aliasMap.keys }

        val warnings = mutableListOf<String>()
        if (missingFromRegistry.isNotEmpty()) {
            warnings.add("Active catalog tools missing executable handlers in CapabilityRegistry: $missingFromRegistry")
        }

        return ConsistencyReport(
            registeredToolCount = capabilities.size,
            catalogToolCount = catalogTools.size,
            missingFromRegistry = missingFromRegistry,
            missingFromCatalog = missingFromCatalog,
            warnings = warnings
        )
    }
}
