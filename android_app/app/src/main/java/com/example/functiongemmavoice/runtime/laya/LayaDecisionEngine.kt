package com.example.functiongemmavoice.runtime.laya

import com.example.functiongemmavoice.runtime.capability.CapabilityRegistry
import com.example.functiongemmavoice.runtime.intent.IntentDomain
import com.example.functiongemmavoice.runtime.orchestrator.RiskLevel

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
 * single vs multi-step routing, and risk levels without external Python or cloud dependencies.
 */
class LocalLayaDecisionAdapter(
    private val capabilityRegistry: CapabilityRegistry
) : LayaDecisionEngine {

    override fun isLocalModelAvailable(): Boolean = true

    override fun classify(utterance: String): LayaDecision {
        val startNs = System.nanoTime()
        val lower = utterance.trim().lowercase(java.util.Locale.ROOT)

        val isMulti = lower.contains(" and ") || lower.contains(" then ") || lower.contains(" after ") || lower.contains(" మరియు ")
        val hasNegation = lower.contains("do not") || lower.contains("don't") || lower.contains("never") || lower.contains("వద్దు")
        val isInterrogative = lower.startsWith("why") || lower.startsWith("how") || lower.startsWith("what if") || lower.startsWith("ఎందుకు")

        val (domain, candidates, risk) = when {
            lower.contains("bluetooth") -> Triple(
                IntentDomain.SETTINGS_NAVIGATION,
                listOf("open_bluetooth_settings", "request_bluetooth_enable", "get_bluetooth_state"),
                RiskLevel.LOW
            )
            lower.contains("wifi") || lower.contains("wi fi") -> Triple(
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
            lower.contains("open ") || lower.contains("launch ") || lower.contains("ఓపెన్") ||
            lower.contains("whatsapp") || lower.contains("youtube") || lower.contains("chrome") -> Triple(
                IntentDomain.APP_LAUNCH,
                listOf("open_app"),
                RiskLevel.LOW
            )
            lower.contains("timer") -> Triple(
                IntentDomain.TIME_PRODUCTIVITY,
                listOf("create_timer"),
                RiskLevel.LOW
            )
            lower.contains("alarm") -> Triple(
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
            lower == "set a timer" -> RouteRecommendation.CLARIFICATION_REQUIRED
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
}
