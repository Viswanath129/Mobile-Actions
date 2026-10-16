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
import org.json.JSONObject
import java.io.File

class InferenceEngineManager private constructor(private val appContext: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val lifecycleMutex = Mutex()
    private var activeModelPath: String? = null
    private val historyTurns = mutableListOf<Pair<String, String>>()
    private val maxHistoryTurns = 3

    val actionRegistry = ActionRegistry(appContext)

    companion object {
        @Volatile
        private var instance: InferenceEngineManager? = null

        fun getInstance(context: Context): InferenceEngineManager {
            val appCtx = try { context.applicationContext ?: context } catch (_: Throwable) { context }
            return instance ?: synchronized(this) {
                instance ?: InferenceEngineManager(appCtx).also { instance = it }
            }
        }
    }

    val isInitialized: Boolean
        get() = engine != null && conversation != null

    val currentModelPath: String?
        get() = activeModelPath

    suspend fun copyModel(uri: Uri): String = withContext(Dispatchers.IO) {
        val target = File(appContext.filesDir, "functiongemma_mobile_actions.litertlm")
        appContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot read selected file." }.use { source ->
                target.outputStream().use { output -> source.copyTo(output) }
            }
        }
        require(target.length() > 100_000_000L) { "Selected model is unexpectedly small." }
        target.absolutePath
    }

    suspend fun initialize(path: String) = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (engine != null && activeModelPath == path && conversation != null) {
                // Already initialized with this model - reuse existing engine
                return@withContext
            }
            try {
                conversation?.close()
                conversation = null
                engine?.close()
                engine = null

                val newEngine = Engine(
                    EngineConfig(
                        modelPath = path,
                        backend = Backend.CPU(),
                        cacheDir = appContext.cacheDir.absolutePath
                    )
                )
                newEngine.initialize()
                engine = newEngine
                activeModelPath = path
                historyTurns.clear()
                createNewConversationInternal()
            } catch (t: Throwable) {
                conversation?.close()
                conversation = null
                engine?.close()
                engine = null
                activeModelPath = null
                throw t
            }
        }
    }

    private fun createNewConversationInternal() {
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
                tools = actionRegistry.schemas(),
                automaticToolCalling = false
            )
        )
    }

    suspend fun resetConversation() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (engine != null) {
                historyTurns.clear()
                createNewConversationInternal()
            }
        }
    }

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

    /**
     * Executes prompt inference and dispatches validated tools.
     */
    suspend fun handlePrompt(prompt: String): List<TurnOutcome> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            val eng = engine ?: throw AssistantException.ModelNotLoaded()
            var conv = conversation ?: run {
                createNewConversationInternal()
                conversation!!
            }

            val budget = getBudgetStatus()
            val promptTokens = estimateTokens(prompt)
            if (promptTokens > budget.remainingBudget && historyTurns.isNotEmpty()) {
                historyTurns.clear()
                createNewConversationInternal()
                conv = conversation!!
            }

            try {
                val message = conv.sendMessage(prompt)
                val outcomes = mutableListOf<TurnOutcome>()

                if (message.toolCalls.isEmpty()) {
                    val rawText = message.contents.contents.filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                        .joinToString("\n") { it.text }
                    var cleanText = if (rawText.isNotBlank()) rawText else message.toString()

                    // Check if model emitted tool call in text form: <start_function_call>call:turn_on_flashlight{}<end_function_call>
                    val callRegex = Regex("""(?:<start_function_call>|<start_of_tool_call>)?(?:call:)?([a-zA-Z0-9_]+)\s*(?:\{([^}]*)\})?(?:<end_function_call>|<end_of_tool_call>)?""")
                    val hasToolSyntax = cleanText.contains("call:") || cleanText.contains("function_call") || cleanText.contains("tool_call")
                    if (hasToolSyntax) {
                        val callMatch = callRegex.find(cleanText)
                        if (callMatch != null) {
                            val toolName = callMatch.groupValues[1]
                            val rawArgs = if (callMatch.groupValues.size > 2 && callMatch.groupValues[2].isNotBlank()) {
                                "{" + callMatch.groupValues[2] + "}"
                            } else "{}"
                            val argsJson = actionRegistry.parseArgs(rawArgs)
                            val prepared = actionRegistry.prepare(toolName, argsJson)
                            if (prepared != null) {
                                if (prepared.requiresConfirmation) {
                                    outcomes.add(TurnOutcome.ConfirmationNeeded(prepared))
                                } else {
                                    val execResult = actionRegistry.execute(prepared.name, prepared.args)
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
                        createNewConversationInternal()

                        val normalized = prompt.trim().lowercase(java.util.Locale.ROOT)
                        val fallbackAction: PreparedAction? = when {
                            normalized.contains("timer") -> {
                                val sec = ActionRegistry.parseDurationToSeconds(normalized)
                                val finalSec = if (sec > 0) sec else 300
                                actionRegistry.prepare("create_timer", JSONObject().put("duration_seconds", finalSec))
                            }
                            normalized.contains("flashlight") && (normalized.contains("on") || normalized.contains("turn on")) -> {
                                actionRegistry.prepare("turn_on_flashlight", JSONObject())
                            }
                            normalized.contains("flashlight") && (normalized.contains("off") || normalized.contains("turn off")) -> {
                                actionRegistry.prepare("turn_off_flashlight", JSONObject())
                            }
                            normalized.contains("battery") -> {
                                actionRegistry.prepare("get_battery_status", JSONObject())
                            }
                            normalized.contains("bluetooth") -> {
                                actionRegistry.prepare("open_bluetooth_pairing", JSONObject())
                            }
                            normalized.contains("wifi") || normalized.contains("wi-fi") -> {
                                actionRegistry.prepare("open_wifi_settings", JSONObject())
                            }
                            normalized.contains("youtube") -> {
                                actionRegistry.prepare("open_app", JSONObject().put("app_name", "YouTube"))
                            }
                            else -> null
                        }

                        if (fallbackAction != null) {
                            if (fallbackAction.requiresConfirmation) {
                                outcomes.add(TurnOutcome.ConfirmationNeeded(fallbackAction))
                            } else {
                                val execResult = actionRegistry.execute(fallbackAction.name, fallbackAction.args)
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
                    val args = actionRegistry.parseArgs(rawArgs)
                    val prepared = actionRegistry.prepare(call.name, args)

                    if (prepared == null) {
                        val reason = "Blocked unsupported or invalid tool: ${call.name}"
                        outcomes.add(TurnOutcome.ActionBlocked(reason))
                        actionDescriptions.add(reason)
                    } else if (prepared.requiresConfirmation) {
                        outcomes.add(TurnOutcome.ConfirmationNeeded(prepared))
                        actionDescriptions.add("Requested confirmation for ${prepared.name}")
                    } else {
                        val execResult = actionRegistry.execute(prepared.name, prepared.args)
                        outcomes.add(TurnOutcome.ActionExecuted(prepared.name, execResult))
                        actionDescriptions.add("[${prepared.name}] ${execResult.userFacingMessage()}")
                    }
                }

                // Clean conversation history state so previous tool call is never replayed
                createNewConversationInternal()
                recordTurn(prompt, actionDescriptions.joinToString("; "))
                outcomes
            } catch (t: Throwable) {
                val rawMsg = t.message ?: t.toString()
                createNewConversationInternal()
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
        return actionRegistry.execute(action.name, action.args)
    }

    fun releaseOnTrimMemory() {
        // Safe memory trimming without closing active engine: clear history and purge conversations
        historyTurns.clear()
        conversation?.close()
        conversation = null
    }

    fun shutdown() {
        try {
            conversation?.close()
            conversation = null
            engine?.close()
            engine = null
        } catch (_: Throwable) {}
    }
}
