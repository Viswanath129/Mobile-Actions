package com.example.functiongemmavoice.runtime

import com.example.functiongemmavoice.runtime.intent.ExecutionStrategy
import com.example.functiongemmavoice.runtime.intent.IntentEngine
import com.example.functiongemmavoice.runtime.intent.StructuredIntent
import org.json.JSONObject

sealed class FastPathDecision {
    data class DirectAction(val actions: List<PreparedAction>, val intent: StructuredIntent? = null) : FastPathDecision()
    data class Ambiguous(val clarificationQuestion: String, val intent: StructuredIntent? = null) : FastPathDecision()
    data class Unsupported(val reason: String, val intent: StructuredIntent? = null) : FastPathDecision()
    data class Negated(val explanation: String, val intent: StructuredIntent? = null) : FastPathDecision()
    data class FallbackToModel(val intent: StructuredIntent? = null) : FastPathDecision()
}

class DeterministicCommandRouter(private val actionRegistry: ActionRegistry) {

    val intentEngine = IntentEngine(actionRegistry.capabilityRegistry)

    fun route(input: String, requestId: String = java.util.UUID.randomUUID().toString()): FastPathDecision {
        val rawTrimmed = input.trim()
        if (rawTrimmed.isBlank()) return FastPathDecision.FallbackToModel()

        // Analyze through the structured Intent Engine
        val intent = intentEngine.parse(rawTrimmed, requestId)

        // 1. Multi-action requests ("Turn on Bluetooth and open YouTube")
        if (intent.isMultiAction) {
            val preparedActions = mutableListOf<PreparedAction>()
            for (sub in intent.subIntents) {
                val toolName = sub.canonicalToolName ?: return FastPathDecision.FallbackToModel(intent)
                val args = JSONObject(sub.parameters)
                val prep = actionRegistry.prepare(toolName, args) ?: return FastPathDecision.FallbackToModel(intent)
                preparedActions.add(prep)
            }
            return FastPathDecision.DirectAction(preparedActions, intent)
        }

        // 2. Pure Negation Rejection
        if (intent.executionStrategy == ExecutionStrategy.NEGATED_REJECTED) {
            val msg = intent.clarificationPrompt ?: "Action rejected: Request contains explicit negation. No state was changed."
            return FastPathDecision.Negated(msg, intent)
        }

        // 3. Unsupported Intents (e.g. In-App Spotify playback controls)
        if (intent.executionStrategy == ExecutionStrategy.UNSUPPORTED) {
            val msg = intent.clarificationPrompt ?: "The requested in-app operation is not currently supported."
            return FastPathDecision.Unsupported(msg, intent)
        }

        // 4. Ambiguity requiring clarification
        if (intent.executionStrategy == ExecutionStrategy.CLARIFICATION_REQUIRED) {
            val prompt = intent.clarificationPrompt ?: "Could you please clarify your request?"
            return FastPathDecision.Ambiguous(prompt, intent)
        }

        // 5. Direct Execution (Deterministic Fast-Path or User Confirmation)
        if (intent.canonicalToolName != null) {
            val args = JSONObject(intent.parameters)
            val prep = actionRegistry.prepare(intent.canonicalToolName, args)
            if (prep != null) {
                return FastPathDecision.DirectAction(listOf(prep), intent)
            }
        }

        // 6. Fallback to FunctionGemma on-device LLM
        return FastPathDecision.FallbackToModel(intent)
    }
}
