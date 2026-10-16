package com.example.functiongemmavoice.runtime

import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ChatLine(
    val role: String,
    val text: String,
    val isError: Boolean = false,
    val technicalDetails: String? = null
)

sealed class TurnOutcome {
    data class TextReply(val text: String) : TurnOutcome()
    data class ActionExecuted(val name: String, val result: ActionResult) : TurnOutcome()
    data class ConfirmationNeeded(val action: PreparedAction) : TurnOutcome()
    data class ActionBlocked(val reason: String) : TurnOutcome()
}

sealed class AssistantException(
    val userFacingMessage: String,
    val technicalDetails: String,
    cause: Throwable? = null
) : Exception(userFacingMessage, cause) {
    class ContextBudgetExceeded(limit: Int, current: Int, cause: Throwable? = null) : AssistantException(
        "Conversation reached the context limit. A fresh session was started automatically.",
        "Input tokens ($current) exceeded model context window limit ($limit). Resetting conversation.",
        cause
    )
    class NativeEngineError(errorCode: Int, message: String, cause: Throwable? = null) : AssistantException(
        "The on-device inference engine encountered a temporary glitch. Resetting conversation session.",
        "Native LiteRT-LM task failed with state $errorCode: $message",
        cause
    )
    class ModelNotLoaded(message: String = "Please load or initialize the .litertlm model first.") : AssistantException(
        message,
        "Engine or conversation is null. Model path not loaded."
    )
    class UnknownInferenceError(message: String, cause: Throwable? = null) : AssistantException(
        "Inference failed. Please try again.",
        message,
        cause
    )
}

data class ContextBudgetStatus(
    val maxTokens: Int = 4096,
    val estimatedSystemTokens: Int = 120,
    val estimatedSchemaTokens: Int = 2400,
    val estimatedHistoryTokens: Int = 0,
    val safetyBuffer: Int = 500
) {
    val remainingBudget: Int
        get() = maxTokens - estimatedSystemTokens - estimatedSchemaTokens - estimatedHistoryTokens - safetyBuffer
}

class AssistantRuntime(private val context: Context) {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val inferenceMutex = Mutex()
    private var loadedModelPath: String? = null
    private val historyTurns = mutableListOf<Pair<String, String>>() // (User, Assistant)
    private val maxHistoryTurns = 3 // Bounded history: keep only last 3 turns

    val actions = ActionRegistry(context)

    suspend fun copyModel(uri: Uri): String = withContext(Dispatchers.IO) {
        val target = File(context.filesDir, "functiongemma_mobile_actions.litertlm")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot read selected file." }.use { source ->
                target.outputStream().use { output -> source.copyTo(output) }
            }
        }
        require(target.length() > 100_000_000L) { "Selected model is unexpectedly small." }
        target.absolutePath
    }

    suspend fun initialize(path: String) = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            conversation?.close()
            engine?.close()
            val newEngine = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = context.cacheDir.absolutePath))
            newEngine.initialize()
            engine = newEngine
            loadedModelPath = path
            historyTurns.clear()
            createNewConversation()
        }
    }

    private fun createNewConversation() {
        val eng = requireNotNull(engine) { "Engine not initialized." }
        conversation?.close()
        conversation = eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(
                    "You are FunctionGemma, a fast on-device Android voice assistant. " +
                    "When the user asks to perform an action, you must immediately call the corresponding action tool. " +
                    "Support English and Telugu inquiries concisely. " +
                    "Never output raw HTML tags or echo error messages."
                ),
                tools = actions.schemas(),
                automaticToolCalling = false
            )
        )
    }

    /**
     * Resets conversation without reloading the 435MB model into RAM.
     */
    suspend fun resetConversation() = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            if (engine != null) {
                historyTurns.clear()
                createNewConversation()
            }
        }
    }

    /**
     * Fast estimation of tokens (~3.5 characters per token for English/code, ~2 for Telugu).
     */
    fun estimateTokens(text: String): Int {
        var teluguCount = 0
        var otherCount = 0
        for (ch in text) {
            if (ch.code in 0x0C00..0x0C7F) {
                teluguCount++
            } else {
                otherCount++
            }
        }
        return (otherCount / 3.5 + teluguCount / 1.5).toInt().coerceAtLeast(1)
    }

    fun getBudgetStatus(): ContextBudgetStatus {
        val historyTokens = historyTurns.sumOf { (u, a) -> estimateTokens(u) + estimateTokens(a) }
        return ContextBudgetStatus(estimatedHistoryTokens = historyTokens)
    }

    suspend fun handlePrompt(prompt: String): List<TurnOutcome> = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            val eng = engine ?: throw AssistantException.ModelNotLoaded()
            var conv = conversation ?: run {
                createNewConversation()
                conversation!!
            }

            // Estimate prompt token length
            val promptTokens = estimateTokens(prompt)
            val budget = getBudgetStatus()

            // Pre-execution budget check: if prompt would exceed available budget, trim history or reset conversation
            if (promptTokens > budget.remainingBudget && historyTurns.isNotEmpty()) {
                historyTurns.clear()
                createNewConversation()
                conv = conversation!!
            }

            try {
                val message = conv.sendMessage(prompt)
                val outcomes = mutableListOf<TurnOutcome>()

                if (message.toolCalls.isEmpty()) {
                    val rawText = message.contents.contents.filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                        .joinToString("\n") { it.text }
                    var cleanText = if (rawText.isNotBlank()) rawText else message.toString()

                    // Check if model emitted tool call in text form
                    val callRegex = Regex("""(?:<start_function_call>|<start_of_tool_call>)?(?:call:)?([a-zA-Z0-9_]+)\s*(?:\{([^}]*)\})?(?:<end_function_call>|<end_of_tool_call>)?""")
                    val hasToolSyntax = cleanText.contains("call:") || cleanText.contains("function_call") || cleanText.contains("tool_call")
                    if (hasToolSyntax) {
                        val callMatch = callRegex.find(cleanText)
                        if (callMatch != null) {
                            val toolName = callMatch.groupValues[1]
                            val rawArgs = if (callMatch.groupValues.size > 2 && callMatch.groupValues[2].isNotBlank()) {
                                "{" + callMatch.groupValues[2] + "}"
                            } else "{}"
                            val argsJson = actions.parseArgs(rawArgs)
                            val prepared = actions.prepare(toolName, argsJson)
                            if (prepared != null) {
                                if (prepared.requiresConfirmation) {
                                    outcomes.add(TurnOutcome.ConfirmationNeeded(prepared))
                                } else {
                                    val execResult = actions.execute(prepared.name, prepared.args)
                                    outcomes.add(TurnOutcome.ActionExecuted(prepared.name, execResult))
                                }
                                recordTurn(prompt, "[${prepared.name}]")
                                return@withContext outcomes
                            }
                        }
                    }

                    // Check for hallucination / table token poisoning
                    val isHallucination = cleanText.contains("<tr>", ignoreCase = true) ||
                        cleanText.contains("Invalid argument:", ignoreCase = true) ||
                        cleanText.contains("<start_of_turn>", ignoreCase = true) ||
                        cleanText.contains("<end_of_turn>", ignoreCase = true) ||
                        hasToolSyntax

                    if (isHallucination) {
                        // Immediately reset poisoned conversation session so subsequent turns are clean
                        createNewConversation()

                        // Check fallback action for common voice assistant commands
                        val normalized = prompt.trim().lowercase(java.util.Locale.ROOT)
                        val fallbackAction: PreparedAction? = when {
                            normalized.contains("timer") -> {
                                val sec = ActionRegistry.parseDurationToSeconds(normalized)
                                val finalSec = if (sec > 0) sec else 300
                                actions.prepare("create_timer", org.json.JSONObject().put("duration_seconds", finalSec))
                            }
                            normalized.contains("flashlight") && (normalized.contains("on") || normalized.contains("turn on")) -> {
                                actions.prepare("turn_on_flashlight", org.json.JSONObject())
                            }
                            normalized.contains("flashlight") && (normalized.contains("off") || normalized.contains("turn off")) -> {
                                actions.prepare("turn_off_flashlight", org.json.JSONObject())
                            }
                            normalized.contains("battery") -> {
                                actions.prepare("get_battery_status", org.json.JSONObject())
                            }
                            normalized.contains("wifi") || normalized.contains("wi-fi") -> {
                                actions.prepare("open_wifi_settings", org.json.JSONObject())
                            }
                            normalized.contains("youtube") -> {
                                actions.prepare("open_app", org.json.JSONObject().put("app_name", "YouTube"))
                            }
                            else -> null
                        }

                        if (fallbackAction != null) {
                            if (fallbackAction.requiresConfirmation) {
                                outcomes.add(TurnOutcome.ConfirmationNeeded(fallbackAction))
                            } else {
                                val execResult = actions.execute(fallbackAction.name, fallbackAction.args)
                                outcomes.add(TurnOutcome.ActionExecuted(fallbackAction.name, execResult))
                            }
                            return@withContext outcomes
                        }

                        cleanText = cleanText
                            .replace("<start_function_call>", "")
                            .replace("<end_function_call>", "")
                            .replace("<start_of_tool_call>", "")
                            .replace("<end_of_tool_call>", "")
                            .replace("<start_of_turn>", "")
                            .replace("<end_of_turn>", "")
                            .replace(Regex("<[^>]*>"), "")
                            .trim()
                        if (cleanText.isBlank()) {
                            cleanText = "I could not complete that request. Please try again."
                        }
                    }

                    outcomes.add(TurnOutcome.TextReply(cleanText))
                    if (!isHallucination) {
                        recordTurn(prompt, cleanText)
                    }
                    return@withContext outcomes
                }

                val actionDescriptions = mutableListOf<String>()
                for (call in message.toolCalls) {
                    val rawArgs = call.arguments.toString()
                    val args = actions.parseArgs(rawArgs)
                    val prepared = actions.prepare(call.name, args)

                    if (prepared == null) {
                        val reason = "Blocked unsupported or invalid tool: ${call.name}"
                        outcomes.add(TurnOutcome.ActionBlocked(reason))
                        actionDescriptions.add(reason)
                    } else if (prepared.requiresConfirmation) {
                        outcomes.add(TurnOutcome.ConfirmationNeeded(prepared))
                        actionDescriptions.add("Requested confirmation for ${prepared.name}")
                    } else {
                        val execResult = actions.execute(prepared.name, prepared.args)
                        outcomes.add(TurnOutcome.ActionExecuted(prepared.name, execResult))
                        actionDescriptions.add("[${prepared.name}] ${execResult.userFacingMessage()}")
                    }
                }

                recordTurn(prompt, actionDescriptions.joinToString("; "))
                outcomes
            } catch (t: Throwable) {
                val rawMsg = t.message ?: t.toString()

                // Recover native engine session: LiteRT-LM marks conversation broken after State 7 or INVALID_ARGUMENT
                createNewConversation()
                historyTurns.clear()

                if (rawMsg.contains("5906 >= 4096", ignoreCase = true) || rawMsg.contains("Exceeding the maximum number of tokens", ignoreCase = true)) {
                    throw AssistantException.ContextBudgetExceeded(4096, 5906, t)
                } else if (rawMsg.contains("state: 7", ignoreCase = true) || rawMsg.contains("Task failed with state", ignoreCase = true)) {
                    throw AssistantException.NativeEngineError(7, rawMsg, t)
                } else {
                    throw AssistantException.UnknownInferenceError(rawMsg, t)
                }
            }
        }
    }

    private fun recordTurn(userPrompt: String, assistantReply: String) {
        if (assistantReply.contains("<tr>", ignoreCase = true) ||
            assistantReply.contains("Invalid argument:", ignoreCase = true)) {
            return
        }
        historyTurns.add(userPrompt to assistantReply)
        while (historyTurns.size > maxHistoryTurns) {
            historyTurns.removeAt(0)
        }
    }

    fun executeConfirmedAction(action: PreparedAction): ActionResult {
        return actions.execute(action.name, action.args)
    }

    fun isReady(): Boolean = engine != null && conversation != null
}
