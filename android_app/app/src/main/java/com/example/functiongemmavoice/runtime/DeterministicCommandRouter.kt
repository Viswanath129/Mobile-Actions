package com.example.functiongemmavoice.runtime

import org.json.JSONObject
import java.util.Locale

sealed class FastPathDecision {
    data class DirectAction(val actions: List<PreparedAction>) : FastPathDecision()
    data class Ambiguous(val clarificationQuestion: String) : FastPathDecision()
    data class Unsupported(val reason: String) : FastPathDecision()
    data class Negated(val explanation: String) : FastPathDecision()
    object FallbackToModel : FastPathDecision()
}

class DeterministicCommandRouter(private val actionRegistry: ActionRegistry) {

    private val negationPatterns = listOf(
        "don't", "dont", "do not", "never", "should not", "shouldn't", "must not",
        "stop", "cancel", "disallow", "వద్దు", "చేయవద్దు", "ఆపండి", "వేయొద్దు"
    )

    private val interrogativeHypotheticalPatterns = listOf(
        "why", "how to", "how do", "how can", "what if", "can you explain",
        "tell me about", "should i", "ఎందుకు", "ఎలా"
    )

    fun route(input: String): FastPathDecision {
        val rawTrimmed = input.trim()
        if (rawTrimmed.isBlank()) return FastPathDecision.FallbackToModel

        val lower = rawTrimmed.lowercase(Locale.ROOT)
            .replace("?", "")
            .replace("!", "")
            .replace(".", "")
            .trim()

        // 1. Check for compound commands separated by " and " or " మరియు "
        if (lower.contains(" and ") || lower.contains(" మరియు ")) {
            val parts = if (lower.contains(" and ")) lower.split(" and ") else lower.split(" మరియు ")
            if (parts.size == 2) {
                val dec1 = routeSingle(parts[0].trim())
                val dec2 = routeSingle(parts[1].trim())
                if (dec1 is FastPathDecision.DirectAction && dec2 is FastPathDecision.DirectAction) {
                    return FastPathDecision.DirectAction(dec1.actions + dec2.actions)
                }
            }
        }

        return routeSingle(lower)
    }

    private fun routeSingle(lower: String): FastPathDecision {
        // 1. Guard against Negation ("Do not turn on the flashlight")
        for (neg in negationPatterns) {
            val pattern = Regex("""\b$neg\b""", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(lower) || lower.startsWith("$neg ") || lower.contains(" $neg")) {
                if (lower.contains("flashlight") || lower.contains("torch") || lower.contains("లైట్")) {
                    return FastPathDecision.Negated("Action rejected: Request contains negation '$neg'. Flashlight was not turned on.")
                }
                if (lower.contains("youtube") || lower.contains("timer") || lower.contains("bluetooth") || lower.contains("wifi")) {
                    return FastPathDecision.Negated("Action rejected: Request contains negation '$neg'.")
                }
            }
        }

        // 2. Guard against Interrogative / Hypothetical queries ("Why should I turn on the flashlight?")
        for (q in interrogativeHypotheticalPatterns) {
            val pattern = Regex("""\b$q\b""", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(lower) || lower.startsWith("$q ") || lower.contains(" $q")) {
                // Route to FunctionGemma for reasoned natural language answer
                return FastPathDecision.FallbackToModel
            }
        }

        // 3. Guard against explicitly unsupported third-party intents (media playback controls or booking)
        if ((lower.contains("play") && lower.contains("spotify")) || lower.contains("netflix") || lower.contains("uber") || lower.contains("swiggy") || lower.contains("zomato")) {
            val service = when {
                lower.contains("spotify") -> "Spotify playback control is not supported directly. You can say 'Open Spotify' to launch the app."
                lower.contains("netflix") -> "Netflix integration is not currently supported on this device."
                lower.contains("uber") -> "Uber ride booking is not currently supported on this device."
                else -> "The requested third-party service is not currently supported."
            }
            return FastPathDecision.Unsupported(service)
        }

        // 4. Flashlight Turn ON
        if (isFlashlightOn(lower)) {
            val action = actionRegistry.prepare("turn_on_flashlight", JSONObject())
            return if (action != null) FastPathDecision.DirectAction(listOf(action)) else FastPathDecision.FallbackToModel
        }

        // 5. Flashlight Turn OFF
        if (isFlashlightOff(lower)) {
            val action = actionRegistry.prepare("turn_off_flashlight", JSONObject())
            return if (action != null) FastPathDecision.DirectAction(listOf(action)) else FastPathDecision.FallbackToModel
        }

        // 6. Battery Level
        if (isBatteryQuery(lower)) {
            val action = actionRegistry.prepare("get_battery_status", JSONObject())
            return if (action != null) FastPathDecision.DirectAction(listOf(action)) else FastPathDecision.FallbackToModel
        }

        // 7. Wi-Fi Settings
        if (isWifiQuery(lower)) {
            val action = actionRegistry.prepare("open_wifi_settings", JSONObject())
            return if (action != null) FastPathDecision.DirectAction(listOf(action)) else FastPathDecision.FallbackToModel
        }

        // 8. Bluetooth Settings
        if (isBluetoothQuery(lower)) {
            val action = actionRegistry.prepare("open_bluetooth_pairing", JSONObject())
            return if (action != null) FastPathDecision.DirectAction(listOf(action)) else FastPathDecision.FallbackToModel
        }

        // 9. Sound & Volume Settings
        if (lower.contains("sound settings") || lower.contains("volume settings") || lower == "open volume" || lower == "open sound") {
            val action = actionRegistry.prepare("open_sound_settings", JSONObject())
            return if (action != null) FastPathDecision.DirectAction(listOf(action)) else FastPathDecision.FallbackToModel
        }

        // 10. Display & Brightness Settings
        if (lower.contains("display settings") || lower.contains("brightness settings") || lower == "open display") {
            val action = actionRegistry.prepare("open_display_settings", JSONObject())
            return if (action != null) FastPathDecision.DirectAction(listOf(action)) else FastPathDecision.FallbackToModel
        }

        // 11. Location Settings
        if (lower.contains("location settings") || lower == "open location") {
            val action = actionRegistry.prepare("open_location_settings", JSONObject())
            return if (action != null) FastPathDecision.DirectAction(listOf(action)) else FastPathDecision.FallbackToModel
        }

        // 12. Airplane Mode Settings
        if (lower.contains("airplane mode") || lower.contains("aeroplane mode") || lower.contains("flight mode")) {
            val action = actionRegistry.prepare("open_airplane_mode_settings", JSONObject())
            return if (action != null) FastPathDecision.DirectAction(listOf(action)) else FastPathDecision.FallbackToModel
        }

        // 13. Do Not Disturb Settings
        if (lower.contains("do not disturb") || lower.contains("dnd settings") || lower == "open dnd") {
            val action = actionRegistry.prepare("open_dnd_settings", JSONObject())
            return if (action != null) FastPathDecision.DirectAction(listOf(action)) else FastPathDecision.FallbackToModel
        }

        // 14. System Settings
        if (isSettingsQuery(lower)) {
            val action = actionRegistry.prepare("open_system_settings", JSONObject())
            return if (action != null) FastPathDecision.DirectAction(listOf(action)) else FastPathDecision.FallbackToModel
        }

        // 15. Time & Date
        if (isTimeDateQuery(lower)) {
            val action = actionRegistry.prepare("check_current_time_date", JSONObject())
            return if (action != null) FastPathDecision.DirectAction(listOf(action)) else FastPathDecision.FallbackToModel
        }

        // 16. Calendar
        if (lower == "open calendar" || lower == "calendar" || lower == "launch calendar") {
            val action = actionRegistry.prepare("open_calendar", JSONObject())
            return if (action != null) FastPathDecision.DirectAction(listOf(action)) else FastPathDecision.FallbackToModel
        }

        // 17. Clock
        if (lower == "open clock" || lower == "clock" || lower == "open stopwatch" || lower == "stopwatch") {
            val action = actionRegistry.prepare("open_clock_stopwatch", JSONObject())
            return if (action != null) FastPathDecision.DirectAction(listOf(action)) else FastPathDecision.FallbackToModel
        }

        // 18. Share Text
        if (lower.startsWith("share ") || lower == "share text") {
            val textToShare = lower.removePrefix("share ").trim().ifBlank { "FunctionGemma Assistant" }
            val action = actionRegistry.prepare("share_text", JSONObject().put("text", textToShare))
            return if (action != null) FastPathDecision.DirectAction(listOf(action)) else FastPathDecision.FallbackToModel
        }

        // 19. Timer commands
        if (lower.contains("timer") || lower.contains("టైమర్")) {
            val durationSec = ActionRegistry.parseDurationToSeconds(lower)
            if (durationSec <= 0) {
                // Ambiguous timer request with missing duration
                return FastPathDecision.Ambiguous("Please specify how many minutes or seconds for the timer (e.g., 'Set a timer for 5 minutes').")
            }
            val args = JSONObject().put("duration_seconds", durationSec)
            val action = actionRegistry.prepare("create_timer", args)
            return if (action != null) FastPathDecision.DirectAction(listOf(action)) else FastPathDecision.FallbackToModel
        }

        // 20. App Launching (YouTube, Spotify, Chrome, Camera, Maps, Photos, Phone, Messages, Calculator)
        val appDecision = matchAppLaunch(lower)
        if (appDecision != null) {
            return appDecision
        }

        // Fallback to FunctionGemma for everything else
        return FastPathDecision.FallbackToModel
    }

    private fun isFlashlightOn(s: String): Boolean {
        val matchesEn = (s == "turn on flashlight" || s == "turn on the flashlight" || s == "turn the flashlight on" ||
                s == "flashlight on" || s == "turn on torch" || s == "torch on" || s == "enable flashlight" ||
                s == "start flashlight" || s == "turn flashlight on")
        val matchesTe = (s.contains("లైట్ ఆన్") || s.contains("టార్చ్ ఆన్"))
        return matchesEn || matchesTe
    }

    private fun isFlashlightOff(s: String): Boolean {
        val matchesEn = (s == "turn off flashlight" || s == "turn off the flashlight" || s == "turn the flashlight off" ||
                s == "flashlight off" || s == "turn off torch" || s == "torch off" || s == "disable flashlight" ||
                s == "stop flashlight" || s == "turn flashlight off")
        val matchesTe = (s.contains("లైట్ ఆఫ్") || s.contains("టార్చ్ ఆఫ్"))
        return matchesEn || matchesTe
    }

    private fun isBatteryQuery(s: String): Boolean {
        val matchesEn = (s == "what is my battery level" || s == "what's my battery level" || s == "what is the battery level" ||
                s == "what's the battery level" || s == "battery level" || s == "battery status" || s == "check battery" ||
                s == "check battery level" || s == "check my battery" || s == "how much battery is left" ||
                s == "battery percentage" || s == "show battery status")
        val matchesTe = (s.contains("బ్యాటరీ ఎంత") || s.contains("బ్యాటరీ స్థాయి") || s.contains("బ్యాటరీ చెక్"))
        return matchesEn || matchesTe
    }

    private fun isWifiQuery(s: String): Boolean {
        val matchesEn = (s == "open wifi settings" || s == "open wi-fi settings" || s == "wifi settings" ||
                s == "wi-fi settings" || s == "open wifi" || s == "open wi-fi" || s == "launch wifi settings")
        val matchesTe = (s.contains("వైఫై సెట్టింగ్స్") || s.contains("వైఫై ఓపెన్"))
        return matchesEn || matchesTe
    }

    private fun isBluetoothQuery(s: String): Boolean {
        val matchesEn = (s == "open bluetooth settings" || s == "open bluetooth" || s == "bluetooth settings" ||
                s == "launch bluetooth settings" || s == "bluetooth pairing")
        val matchesTe = (s.contains("బ్లూటూత్ సెట్టింగ్స్") || s.contains("బ్లూటూత్ ఓపెన్"))
        return matchesEn || matchesTe
    }

    private fun isSettingsQuery(s: String): Boolean {
        val matchesEn = (s == "open settings" || s == "open system settings" || s == "device settings" ||
                s == "open device settings" || s == "launch settings")
        val matchesTe = (s.contains("సెట్టింగ్స్ ఓపెన్") || s.contains("ఫోన్ సెట్టింగ్స్"))
        return matchesEn || matchesTe
    }

    private fun isTimeDateQuery(s: String): Boolean {
        val matchesEn = (s == "what time is it" || s == "what is the time" || s == "check current time" ||
                s == "current time" || s == "what's the time" || s == "time please" || s == "check time" ||
                s == "what is the date" || s == "today's date" || s == "current date and time")
        val matchesTe = (s.contains("సమయం ఎంత") || s.contains("తేదీ ఎంత"))
        return matchesEn || matchesTe
    }

    private fun matchAppLaunch(s: String): FastPathDecision? {
        val supportedApps = mapOf(
            "youtube" to "YouTube",
            "spotify" to "Spotify",
            "chrome" to "Chrome",
            "camera" to "Camera",
            "maps" to "Maps",
            "google maps" to "Maps",
            "photos" to "Photos",
            "gallery" to "Photos",
            "phone" to "Phone",
            "dialer" to "Phone",
            "messages" to "Messages",
            "sms" to "Messages",
            "clock" to "Clock",
            "calendar" to "Calendar",
            "calculator" to "Calculator"
        )

        for ((key, canonicalName) in supportedApps) {
            val enOpen = (s == "open $key" || s == "launch $key" || s == "start $key" || s == "open the $key app")
            val teOpen = (s.contains("$key ఓపెన్") || (key == "youtube" && s.contains("యూట్యూబ్ ఓపెన్")))
            if (enOpen || teOpen) {
                val action = actionRegistry.prepare("open_app", JSONObject().put("app_name", canonicalName))
                if (action != null) {
                    return FastPathDecision.DirectAction(listOf(action))
                }
            }
        }
        return null
    }
}
