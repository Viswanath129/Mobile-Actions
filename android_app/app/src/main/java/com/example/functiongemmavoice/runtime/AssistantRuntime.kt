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
    data class ActionExecuted(val name: String, val result: String) : TurnOutcome()
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
                    "You are a helpful on-device voice assistant for Android. " +
                    "Use available action tools when the user asks you to perform an action. " +
                    "Support English and Telugu inquiries. " +
                    "Never hallucinate missing parameters or execute destructive actions without confirmation."
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
                    val text = message.contents.contents.filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                        .joinToString("\n") { it.text }
                    val cleanText = if (text.isNotBlank()) text else message.toString()
                    outcomes.add(TurnOutcome.TextReply(cleanText))
                    recordTurn(prompt, cleanText)
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
                        actionDescriptions.add("[${prepared.name}] $execResult")
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
        historyTurns.add(userPrompt to assistantReply)
        while (historyTurns.size > maxHistoryTurns) {
            historyTurns.removeAt(0)
        }
    }

    fun executeConfirmedAction(action: PreparedAction): String {
        return actions.execute(action.name, action.args)
    }

    fun isReady(): Boolean = engine != null && conversation != null
}
