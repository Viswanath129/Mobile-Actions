package com.example.functiongemmavoice.runtime.laya

import com.example.functiongemmavoice.runtime.capability.CapabilityRegistry
import com.example.functiongemmavoice.runtime.intent.CanonicalIntentId
import com.example.functiongemmavoice.runtime.intent.IntentClassifier
import com.example.functiongemmavoice.runtime.intent.IntentClassificationResult
import com.example.functiongemmavoice.runtime.intent.IntentDomain
import com.example.functiongemmavoice.runtime.orchestrator.RiskLevel
import java.util.Locale

enum class RouteRecommendation {
    DETERMINISTIC_FAST_PATH,
    FUNCTIONGEMMA_INFERENCE,
    CLARIFICATION_REQUIRED,
    REJECT_NEGATED
}

data class LayaDecision(
    val intentDomain: IntentDomain,
    val candidateActions: List<String>,
    val isMultiStep: Boolean,
    val riskLevel: RiskLevel,
    val isAmbiguous: Boolean,
    val confidence: Float,
    val recommendedRoute: RouteRecommendation,
    val latencyMs: Long,
    val justification: String
)

interface LayaDecisionEngine {
    fun classify(utterance: String): LayaDecision
    fun isLocalModelAvailable(): Boolean
}

/**
 * Local on-device decision adapter evaluating command domain, ambiguity,
 * canonical intent classification, single vs multi-step routing, and risk levels
 * without external Python or heavy cloud dependencies.
 *
 * Implements both LayaDecisionEngine and the stable IntentClassifier contract.
 */
class LocalLayaDecisionAdapter(
    val capabilityRegistry: CapabilityRegistry? = null
) : LayaDecisionEngine, IntentClassifier {

    override fun isLocalModelAvailable(): Boolean = true
    override fun isAvailable(): Boolean = true

    override fun classify(utterance: String): LayaDecision {
        val startNs = System.nanoTime()
        val lower = utterance.trim().lowercase(Locale.ROOT)

        val isMulti = lower.contains(" and ") || lower.contains(" then ") || lower.contains(" after ") || lower.contains(" మరియు ")
        val hasNegation = lower.contains("do not") || lower.contains("don't") || lower.contains("never") || lower.contains("వద్దు")
        val isInterrogative = lower.startsWith("why") || lower.startsWith("how") || lower.startsWith("what if") || lower.startsWith("ఎందుకు")

        val (domain, candidates, risk) = when {
            lower.contains("bluetooth") -> Triple(
                IntentDomain.SETTINGS_NAVIGATION,
                listOf("open_bluetooth_settings", "request_bluetooth_enable", "get_bluetooth_state"),
                RiskLevel.LOW
            )
            lower.contains("wifi") || lower.contains("wi fi") || lower.contains("వైఫై") -> Triple(
                IntentDomain.SETTINGS_NAVIGATION,
                listOf("open_wifi_settings", "get_wifi_state"),
                RiskLevel.LOW
            )
            lower.contains("flashlight") || lower.contains("torch") || lower.contains("లైట్") -> Triple(
                IntentDomain.STATE_MUTATION,
                listOf("turn_on_flashlight", "turn_off_flashlight"),
                RiskLevel.LOW
            )
            lower.contains("battery") || lower.contains("బ్యాటరీ") -> Triple(
                IntentDomain.SYSTEM_INSPECTION,
                listOf("get_battery_status"),
                RiskLevel.LOW
            )
            lower.contains("lock screen") || lower.contains("lock phone") || lower.contains("లాక్") -> Triple(
                IntentDomain.STATE_MUTATION,
                listOf("lock_screen"),
                RiskLevel.LOW
            )
            lower.contains("play store") || lower.contains("ప్లే స్టోర్") -> Triple(
                IntentDomain.APP_LAUNCH,
                listOf("open_play_store_search", "open_play_store_listing", "open_play_store_updates"),
                RiskLevel.LOW
            )
            lower.contains("split screen") || lower.contains("side by side") -> Triple(
                IntentDomain.APP_LAUNCH,
                listOf("open_split_screen"),
                RiskLevel.LOW
            )
            lower.contains("floating window") -> Triple(
                IntentDomain.APP_LAUNCH,
                listOf("open_floating_window"),
                RiskLevel.LOW
            )
            lower.contains("brightness") || lower.contains("బ్రైట్‌నెస్") -> Triple(
                if (lower.contains("settings")) IntentDomain.SETTINGS_NAVIGATION else IntentDomain.STATE_MUTATION,
                listOf("get_brightness", "set_brightness", "adjust_brightness"),
                RiskLevel.LOW
            )
            lower.contains("volume") || lower.contains("వాల్యూమ్") || lower.contains("mute") -> Triple(
                if (lower.contains("settings")) IntentDomain.SETTINGS_NAVIGATION else IntentDomain.STATE_MUTATION,
                listOf("get_volume", "set_volume", "adjust_volume", "mute_stream", "unmute_stream"),
                RiskLevel.LOW
            )
            lower.contains("dnd") || lower.contains("do not disturb") -> Triple(
                if (lower.contains("settings")) IntentDomain.SETTINGS_NAVIGATION else IntentDomain.STATE_MUTATION,
                listOf("set_dnd_mode"),
                RiskLevel.LOW
            )
            lower.contains("silent") || lower.contains("vibrate") -> Triple(
                IntentDomain.STATE_MUTATION,
                listOf("set_ringer_mode"),
                RiskLevel.LOW
            )
            lower.contains("open ") || lower.contains("launch ") || lower.contains("ఓపెన్") ||
            lower.contains("whatsapp") || lower.contains("youtube") || lower.contains("chrome") -> Triple(
                IntentDomain.APP_LAUNCH,
                listOf("open_app"),
                RiskLevel.LOW
            )
            lower.contains("timer") || lower.contains("టైమర్") -> Triple(
                IntentDomain.TIME_PRODUCTIVITY,
                listOf("create_timer"),
                RiskLevel.LOW
            )
            lower.contains("alarm") || lower.contains("అలారం") -> Triple(
                IntentDomain.TIME_PRODUCTIVITY,
                listOf("create_alarm"),
                RiskLevel.LOW
            )
            lower.contains("call") || lower.contains("dial") || lower.contains("email") || lower.contains("sms") -> Triple(
                IntentDomain.COMMUNICATION,
                listOf("dial_phone_number", "compose_sms", "compose_email"),
                RiskLevel.HIGH
            )
            else -> Triple(IntentDomain.UNKNOWN, emptyList(), RiskLevel.LOW)
        }

        val route = when {
            hasNegation && !lower.contains(";") && !lower.contains("but") -> RouteRecommendation.REJECT_NEGATED
            isInterrogative -> RouteRecommendation.FUNCTIONGEMMA_INFERENCE
            domain == IntentDomain.UNKNOWN -> RouteRecommendation.FUNCTIONGEMMA_INFERENCE
            lower == "set a timer" || lower == "timer పెట్టు" -> RouteRecommendation.CLARIFICATION_REQUIRED
            else -> RouteRecommendation.DETERMINISTIC_FAST_PATH
        }

        val latencyMs = (System.nanoTime() - startNs) / 1_000_000

        return LayaDecision(
            intentDomain = domain,
            candidateActions = candidates,
            isMultiStep = isMulti,
            riskLevel = risk,
            isAmbiguous = (route == RouteRecommendation.CLARIFICATION_REQUIRED),
            confidence = if (domain != IntentDomain.UNKNOWN) 0.95f else 0.50f,
            recommendedRoute = route,
            latencyMs = latencyMs.coerceAtLeast(1L),
            justification = "Evaluated via local Laya architecture contract: domain=$domain, multi=$isMulti, risk=$risk"
        )
    }

    override fun classifyIntent(utterance: String): IntentClassificationResult {
        val startNs = System.nanoTime()
        val normalized = utterance.trim().lowercase(Locale.ROOT)

        val result = when {
            // Lock screen
            normalized.contains("lock screen") || normalized.contains("lock my screen") ||
            normalized.contains("lock phone") || normalized.contains("lock my phone") ||
            normalized.contains("స్క్రీన్ లాక్") || normalized == "lock" -> {
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = CanonicalIntentId.LOCK_SCREEN,
                    canonicalToolName = "lock_screen",
                    confidence = 1.0f
                )
            }

            // Navigation
            normalized == "back" || normalized == "go back" || normalized.contains("navigate back") || normalized == "వెనక్కి" -> {
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = CanonicalIntentId.NAVIGATE_BACK,
                    canonicalToolName = "navigate_back",
                    confidence = 1.0f
                )
            }
            normalized == "home" || normalized == "go home" || normalized.contains("home screen") || normalized == "హోమ్" -> {
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = CanonicalIntentId.GO_HOME,
                    canonicalToolName = "go_home_screen",
                    confidence = 1.0f
                )
            }
            normalized == "recents" || normalized.contains("recent apps") || normalized.contains("show recents") -> {
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = CanonicalIntentId.SHOW_RECENTS,
                    canonicalToolName = "show_recents",
                    confidence = 1.0f
                )
            }

            // Play Store Updates
            normalized.contains("app updates") || normalized.contains("update all apps") ||
            normalized.contains("show updates") || normalized.contains("open play store updates") -> {
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = CanonicalIntentId.OPEN_PLAY_STORE_UPDATES,
                    canonicalToolName = "open_play_store_updates",
                    confidence = 1.0f
                )
            }

            // Play Store Search
            normalized.startsWith("search play store") || normalized.startsWith("find in play store") ||
            normalized.contains("in play store") || normalized.contains("on play store") -> {
                val q = normalized.replace("search play store for", "")
                    .replace("find in play store", "")
                    .replace("search in play store", "")
                    .replace("in play store", "")
                    .replace("on play store", "").trim()
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = CanonicalIntentId.OPEN_PLAY_STORE_SEARCH,
                    canonicalToolName = "open_play_store_search",
                    parameters = mapOf("query" to q),
                    confidence = 0.95f
                )
            }

            // App Install Request
            normalized.startsWith("install ") -> {
                val app = normalized.removePrefix("install ").removeSuffix(" app").trim()
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = CanonicalIntentId.REQUEST_APP_INSTALL,
                    canonicalToolName = "request_app_install",
                    parameters = mapOf("app_name" to app),
                    confidence = 0.95f
                )
            }

            // App Update Request
            normalized.startsWith("update ") && !normalized.contains("all") -> {
                val app = normalized.removePrefix("update ").removeSuffix(" app").trim()
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = CanonicalIntentId.REQUEST_APP_UPDATE,
                    canonicalToolName = "request_app_update",
                    parameters = mapOf("app_name" to app),
                    confidence = 0.95f
                )
            }

            // Split Screen
            normalized.contains("split screen") || normalized.contains("side by side") -> {
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = CanonicalIntentId.OPEN_SPLIT_SCREEN,
                    canonicalToolName = "open_split_screen",
                    confidence = 0.95f
                )
            }

            // Floating Window
            normalized.contains("floating window") || normalized.contains("floating mode") -> {
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = CanonicalIntentId.OPEN_FLOATING_WINDOW,
                    canonicalToolName = "open_floating_window",
                    confidence = 0.95f
                )
            }

            // Bluetooth
            normalized.contains("bluetooth") || normalized.contains("బ్లూటూత్") -> {
                when {
                    normalized.contains("on") || normalized.contains("enable") || normalized.contains("ఆన్") -> {
                        IntentClassificationResult(
                            normalizedRequest = normalized,
                            canonicalIntent = CanonicalIntentId.REQUEST_BLUETOOTH_ENABLE,
                            canonicalToolName = "request_bluetooth_enable",
                            confidence = 1.0f
                        )
                    }
                    normalized.contains("settings") || normalized.contains("open") -> {
                        IntentClassificationResult(
                            normalizedRequest = normalized,
                            canonicalIntent = CanonicalIntentId.OPEN_BLUETOOTH_SETTINGS,
                            canonicalToolName = "open_bluetooth_settings",
                            confidence = 1.0f
                        )
                    }
                    else -> {
                        IntentClassificationResult(
                            normalizedRequest = normalized,
                            canonicalIntent = CanonicalIntentId.OPEN_BLUETOOTH_SETTINGS,
                            canonicalToolName = "open_bluetooth_settings",
                            confidence = 0.90f
                        )
                    }
                }
            }

            // Battery
            normalized.contains("battery") || normalized.contains("బ్యాటరీ") -> {
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = CanonicalIntentId.GET_BATTERY_STATUS,
                    canonicalToolName = "get_battery_status",
                    confidence = 1.0f
                )
            }

            // Flashlight
            normalized.contains("flashlight") || normalized.contains("torch") || normalized.contains("లైట్") -> {
                val isOff = normalized.contains("off") || normalized.contains("ఆఫ్")
                val tool = if (isOff) "turn_off_flashlight" else "turn_on_flashlight"
                val intent = if (isOff) CanonicalIntentId.TURN_OFF_FLASHLIGHT else CanonicalIntentId.TURN_ON_FLASHLIGHT
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = intent,
                    canonicalToolName = tool,
                    confidence = 1.0f
                )
            }

            // Wi-Fi
            normalized.contains("wifi") || normalized.contains("వైఫై") -> {
                if (normalized.contains("status") || normalized.contains("is wifi")) {
                    IntentClassificationResult(
                        normalizedRequest = normalized,
                        canonicalIntent = CanonicalIntentId.GET_WIFI_STATE,
                        canonicalToolName = "get_wifi_state",
                        confidence = 1.0f
                    )
                } else {
                    IntentClassificationResult(
                        normalizedRequest = normalized,
                        canonicalIntent = CanonicalIntentId.OPEN_WIFI_SETTINGS,
                        canonicalToolName = "open_wifi_settings",
                        confidence = 1.0f
                    )
                }
            }

            // Brightness
            normalized.contains("brightness") || normalized.contains("బ్రైట్‌నెస్") -> {
                when {
                    normalized.contains("check") || normalized.contains("what is") || normalized.contains("status") || normalized.contains("get") || normalized == "brightness" -> {
                        IntentClassificationResult(
                            normalizedRequest = normalized,
                            canonicalIntent = CanonicalIntentId.GET_BRIGHTNESS,
                            canonicalToolName = "get_brightness",
                            confidence = 1.0f
                        )
                    }
                    normalized.contains("up") || normalized.contains("increase") || normalized.contains("raise") || normalized.contains("down") || normalized.contains("lower") || normalized.contains("decrease") -> {
                        val dir = if (normalized.contains("down") || normalized.contains("lower") || normalized.contains("decrease")) "down" else "up"
                        IntentClassificationResult(
                            normalizedRequest = normalized,
                            canonicalIntent = CanonicalIntentId.ADJUST_BRIGHTNESS,
                            canonicalToolName = "adjust_brightness",
                            parameters = mapOf("direction" to dir),
                            confidence = 1.0f
                        )
                    }
                    else -> {
                        val pctMatch = Regex("""(\d{1,3})""").find(normalized)
                        val pct = pctMatch?.groupValues?.get(1)?.toIntOrNull() ?: 50
                        IntentClassificationResult(
                            normalizedRequest = normalized,
                            canonicalIntent = CanonicalIntentId.SET_BRIGHTNESS,
                            canonicalToolName = "set_brightness",
                            parameters = mapOf("percentage" to pct),
                            confidence = 1.0f
                        )
                    }
                }
            }

            // Volume
            normalized.contains("volume") || normalized.contains("వాల్యూమ్") || normalized.contains("mute") -> {
                when {
                    normalized.contains("unmute") -> {
                        IntentClassificationResult(
                            normalizedRequest = normalized,
                            canonicalIntent = CanonicalIntentId.UNMUTE_STREAM,
                            canonicalToolName = "unmute_stream",
                            confidence = 1.0f
                        )
                    }
                    normalized.contains("mute") -> {
                        IntentClassificationResult(
                            normalizedRequest = normalized,
                            canonicalIntent = CanonicalIntentId.MUTE_STREAM,
                            canonicalToolName = "mute_stream",
                            confidence = 1.0f
                        )
                    }
                    normalized.contains("check") || normalized.contains("what is") || normalized.contains("status") || normalized.contains("get") -> {
                        IntentClassificationResult(
                            normalizedRequest = normalized,
                            canonicalIntent = CanonicalIntentId.GET_VOLUME,
                            canonicalToolName = "get_volume",
                            confidence = 1.0f
                        )
                    }
                    normalized.contains("up") || normalized.contains("increase") || normalized.contains("raise") || normalized.contains("down") || normalized.contains("lower") || normalized.contains("decrease") -> {
                        val dir = if (normalized.contains("down") || normalized.contains("lower") || normalized.contains("decrease")) "down" else "up"
                        IntentClassificationResult(
                            normalizedRequest = normalized,
                            canonicalIntent = CanonicalIntentId.ADJUST_VOLUME,
                            canonicalToolName = "adjust_volume",
                            parameters = mapOf("direction" to dir),
                            confidence = 1.0f
                        )
                    }
                    else -> {
                        val pctMatch = Regex("""(\d{1,3})""").find(normalized)
                        val pct = pctMatch?.groupValues?.get(1)?.toIntOrNull() ?: 50
                        IntentClassificationResult(
                            normalizedRequest = normalized,
                            canonicalIntent = CanonicalIntentId.SET_VOLUME,
                            canonicalToolName = "set_volume",
                            parameters = mapOf("percentage" to pct),
                            confidence = 1.0f
                        )
                    }
                }
            }

            // Ringer Mode
            normalized.contains("silent") || normalized.contains("vibrate") || normalized.contains("ringer") -> {
                val mode = when {
                    normalized.contains("silent") -> "silent"
                    normalized.contains("vibrate") -> "vibrate"
                    else -> "normal"
                }
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = CanonicalIntentId.SET_RINGER_MODE,
                    canonicalToolName = "set_ringer_mode",
                    parameters = mapOf("mode" to mode),
                    confidence = 1.0f
                )
            }

            // DND
            normalized.contains("dnd") || normalized.contains("do not disturb") -> {
                val enabled = !(normalized.contains("off") || normalized.contains("disable") || normalized.contains("stop"))
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = CanonicalIntentId.SET_DND_MODE,
                    canonicalToolName = "set_dnd_mode",
                    parameters = mapOf("enabled" to enabled),
                    confidence = 1.0f
                )
            }

            // App Launch
            normalized.startsWith("open ") || normalized.startsWith("launch ") || normalized.startsWith("start ") ||
            normalized.startsWith("ఓపెన్ ") -> {
                val app = normalized.replace("open the ", "")
                    .replace("launch the ", "")
                    .replace("open ", "")
                    .replace("launch ", "")
                    .replace("start ", "")
                    .replace("ఓపెన్ ", "")
                    .removeSuffix(" app").trim()
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = CanonicalIntentId.OPEN_APP,
                    canonicalToolName = "open_app",
                    parameters = mapOf("app_name" to app),
                    confidence = 0.95f
                )
            }

            // Ambiguity check
            normalized == "set a timer" || normalized == "timer పెట్టు" -> {
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = CanonicalIntentId.CREATE_TIMER,
                    isAmbiguous = true,
                    clarificationPrompt = "Please specify the duration for the timer (e.g., 5 minutes).",
                    confidence = 0.50f
                )
            }

            // Fallback
            else -> {
                IntentClassificationResult(
                    normalizedRequest = normalized,
                    canonicalIntent = CanonicalIntentId.UNKNOWN,
                    fallbackReason = "No high-confidence bounded classifier match; routed to FunctionGemma.",
                    confidence = 0.40f
                )
            }
        }

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        return result.copy(latencyMs = elapsedMs.coerceAtLeast(1L))
    }
}
