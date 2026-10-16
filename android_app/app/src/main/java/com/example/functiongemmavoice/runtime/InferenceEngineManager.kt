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
import com.example.functiongemmavoice.runtime.orchestrator.TaskPlan
import com.example.functiongemmavoice.runtime.orchestrator.TaskStep
import com.example.functiongemmavoice.runtime.orchestrator.TaskPlanScheduler
import com.example.functiongemmavoice.runtime.orchestrator.PlanValidator
import com.example.functiongemmavoice.runtime.laya.LocalLayaDecisionAdapter

class InferenceEngineManager private constructor(private val appContext: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val lifecycleMutex = Mutex()
    private var activeModelPath: String? = null
    private val historyTurns = mutableListOf<Pair<String, String>>()
    private val maxHistoryTurns = 3

    val actionRegistry = ActionRegistry(appContext)
    val fastRouter = DeterministicCommandRouter(actionRegistry)
    val planValidator = PlanValidator(actionRegistry.capabilityRegistry, actionRegistry)
    val planScheduler = TaskPlanScheduler(actionRegistry, planValidator)
    val layaEngine = LocalLayaDecisionAdapter(actionRegistry.capabilityRegistry)

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
     * Executes prompt inference with full request context tracking, deterministic fast routing,
     * and strict stage timing isolation.
     */
    suspend fun processRequest(context: RequestContext): List<TurnOutcome> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (context.isCancelled) {
                return@withContext emptyList()
            }

            // Stage 1: Input Normalization
            context.transitionTo(RequestState.NORMALIZING)
            val normalized = context.rawInput.trim()
            if (normalized.isBlank()) {
                context.markFailed(AssistantError.InvalidInput("Empty input command"))
                return@withContext listOf(
                    TurnOutcome.TextReply("Please say or type a command.", context.requestId, context.timings)
                )
            }
            context.normalizedInput = normalized

            // Stage 2: Routing via Deterministic Fast Path & Structured Intent
            context.transitionTo(RequestState.ROUTING)
            val decision = fastRouter.route(normalized, context.requestId)
            context.structuredIntent = when (decision) {
                is FastPathDecision.DirectAction -> decision.intent
                is FastPathDecision.Negated -> decision.intent
                is FastPathDecision.Ambiguous -> decision.intent
                is FastPathDecision.Unsupported -> decision.intent
                is FastPathDecision.FallbackToModel -> decision.intent
            }

            when (decision) {
                is FastPathDecision.DirectAction -> {
                    context.transitionTo(RequestState.FAST_PATH)
                    context.transitionTo(RequestState.VALIDATING)

                    val outcomes = mutableListOf<TurnOutcome>()
                    // Check confirmation requirements
                    val needsConfirmation = decision.actions.firstOrNull { it.requiresConfirmation }
                    if (needsConfirmation != null) {
                        context.transitionTo(RequestState.NEEDS_CONFIRMATION)
                        outcomes.add(TurnOutcome.ConfirmationNeeded(needsConfirmation, context.requestId, context.timings))
                        return@withContext outcomes
                    }

                    if (decision.actions.size > 1) {
                        val steps = decision.actions.mapIndexed { idx, act ->
                            TaskStep(
                                stepId = "step-$idx",
                                actionId = act.name,
                                displayName = act.summary,
                                validatedArguments = act.args,
                                dependsOn = if (idx > 0 && decision.intent?.dependencies?.isNotEmpty() == true) listOf("step-${idx - 1}") else emptyList()
                            )
                        }
                        val plan = TaskPlan(
                            requestId = context.requestId,
                            userGoal = context.rawInput,
                            steps = steps
                        )
                        context.transitionTo(RequestState.EXECUTING)
                        planScheduler.executePlan(plan)
                        for (st in plan.steps) {
                            st.executionResult?.let { res ->
                                context.executionResults.add(st.actionId to res)
                            }
                        }
                        context.transitionTo(RequestState.VERIFYING)
                        context.markCompleted()
                        outcomes.add(TurnOutcome.MultiStepPlanOutcome(plan, context.requestId, context.timings))
                        return@withContext outcomes
                    }

                    context.transitionTo(RequestState.EXECUTING)
                    for (action in decision.actions) {
                        val execResult = actionRegistry.execute(action.name, action.args)
                        context.executionResults.add(action.name to execResult)
                        outcomes.add(TurnOutcome.ActionExecuted(action.name, execResult, context.requestId, context.timings))
                    }

                    context.transitionTo(RequestState.VERIFYING)
                    context.markCompleted()
                    return@withContext outcomes
                }

                is FastPathDecision.Negated -> {
                    context.transitionTo(RequestState.FAST_PATH)
                    context.markCompleted(decision.explanation)
                    return@withContext listOf(
                        TurnOutcome.TextReply(decision.explanation, context.requestId, context.timings)
                    )
                }

                is FastPathDecision.Ambiguous -> {
                    context.transitionTo(RequestState.FAST_PATH)
                    context.markCompleted(decision.clarificationQuestion)
                    return@withContext listOf(
                        TurnOutcome.TextReply(decision.clarificationQuestion, context.requestId, context.timings)
                    )
                }

                is FastPathDecision.Unsupported -> {
                    context.transitionTo(RequestState.FAST_PATH)
                    context.markCompleted(decision.reason)
                    return@withContext listOf(
                        TurnOutcome.ActionBlocked(decision.reason, context.requestId, context.timings)
                    )
                }

                is FastPathDecision.FallbackToModel -> {
                    // Proceed to FunctionGemma on-device model inference
                    context.transitionTo(RequestState.MODEL_RUNNING)
                    val eng = engine ?: throw AssistantException.ModelNotLoaded()
                    var conv = conversation ?: run {
                        createNewConversationInternal()
                        conversation!!
                    }

                    val budget = getBudgetStatus()
                    val promptTokens = estimateTokens(normalized)
                    if (promptTokens > budget.remainingBudget && historyTurns.isNotEmpty()) {
                        historyTurns.clear()
                        createNewConversationInternal()
                        conv = conversation!!
                    }

                    try {
                        val message = conv.sendMessage(normalized)
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

                                    context.transitionTo(RequestState.VALIDATING)
                                    val prepared = actionRegistry.prepare(toolName, argsJson)
                                    if (prepared != null) {
                                        if (prepared.requiresConfirmation) {
                                            context.transitionTo(RequestState.NEEDS_CONFIRMATION)
                                            outcomes.add(TurnOutcome.ConfirmationNeeded(prepared, context.requestId, context.timings))
                                        } else {
                                            context.transitionTo(RequestState.EXECUTING)
                                            val execResult = actionRegistry.execute(prepared.name, prepared.args)
                                            context.executionResults.add(prepared.name to execResult)
                                            context.transitionTo(RequestState.VERIFYING)
                                            outcomes.add(TurnOutcome.ActionExecuted(prepared.name, execResult, context.requestId, context.timings))
                                            context.markCompleted()
                                        }
                                        // Reset conversation to prevent KV cache contamination across turns
                                        createNewConversationInternal()
                                        recordTurn(normalized, "[${prepared.name}]")
                                        return@withContext outcomes
                                    }
                                }
                            }

                            // Check for hallucination / table token poisoning or unexecuted tool call syntax
                            val isHallucination = cleanText.contains("<tr>", ignoreCase = true) ||
                                cleanText.contains("Invalid argument:", ignoreCase = true) ||
                                cleanText.contains("<start_of_turn>", ignoreCase = true) ||
                                cleanText.contains("<end_of_turn>", ignoreCase = true) ||
                                hasToolSyntax

                            if (isHallucination) {
                                createNewConversationInternal()

                                // Attempt controlled recovery via deterministic fast-path before failing
                                val recovery = fastRouter.route(normalized, context.requestId)
                                if (recovery is FastPathDecision.DirectAction) {
                                    for (act in recovery.actions) {
                                        val execResult = actionRegistry.execute(act.name, act.args)
                                        context.executionResults.add(act.name to execResult)
                                        outcomes.add(TurnOutcome.ActionExecuted(act.name, execResult, context.requestId, context.timings))
                                    }
                                    context.transitionTo(RequestState.VERIFYING)
                                    context.markCompleted()
                                    return@withContext outcomes
                                }

                                cleanText = cleanText
                                    .replace("<start_function_call>", "")
                                    .replace("<end_function_call>", "")
                                    .replace("<start_of_tool_call>", "")
                                    .replace("<end_of_tool_call>", "")
                                    .replace("<start_of_turn>", "")
                                    .replace("<end_of_turn>", "")
                                    .replace(Regex("""call:[a-zA-Z0-9_]+(\{[^}]*\})?"""), "")
                                    .replace(Regex("<[^>]*>"), "")
                                    .trim()
                                if (cleanText.isBlank() || cleanText.contains("call:")) {
                                    cleanText = "I could not complete that request. Please try again."
                                }
                            }

                            context.markCompleted(cleanText)
                            outcomes.add(TurnOutcome.TextReply(cleanText, context.requestId, context.timings))
                            if (!isHallucination) {
                                recordTurn(normalized, cleanText)
                            }
                            return@withContext outcomes
                        }

                        val actionDescriptions = mutableListOf<String>()
                        context.transitionTo(RequestState.VALIDATING)

                        for (call in message.toolCalls) {
                            val rawArgs = call.arguments.toString()
                            val args = actionRegistry.parseArgs(rawArgs)
                            val prepared = actionRegistry.prepare(call.name, args)

                            if (prepared == null) {
                                val reason = "Blocked unsupported or invalid tool: ${call.name}"
                                outcomes.add(TurnOutcome.ActionBlocked(reason, context.requestId, context.timings))
                                actionDescriptions.add(reason)
                            } else if (prepared.requiresConfirmation) {
                                context.transitionTo(RequestState.NEEDS_CONFIRMATION)
                                outcomes.add(TurnOutcome.ConfirmationNeeded(prepared, context.requestId, context.timings))
                                actionDescriptions.add("Requested confirmation for ${prepared.name}")
                            } else {
                                if (context.state != RequestState.EXECUTING) {
                                    context.transitionTo(RequestState.EXECUTING)
                                }
                                val execResult = actionRegistry.execute(prepared.name, prepared.args)
                                context.executionResults.add(prepared.name to execResult)
                                outcomes.add(TurnOutcome.ActionExecuted(prepared.name, execResult, context.requestId, context.timings))
                                actionDescriptions.add("[${prepared.name}] ${execResult.userFacingMessage()}")
                            }
                        }

                        if (context.state == RequestState.EXECUTING) {
                            context.transitionTo(RequestState.VERIFYING)
                        }
                        if (!context.state.isTerminal() && context.state != RequestState.NEEDS_CONFIRMATION) {
                            context.markCompleted()
                        }

                        // Clean conversation state so previous tool call is never replayed in KV cache
                        createNewConversationInternal()
                        recordTurn(normalized, actionDescriptions.joinToString("; "))
                        outcomes
                    } catch (t: Throwable) {
                        val rawMsg = t.message ?: t.toString()
                        createNewConversationInternal()
                        historyTurns.clear()

                        val error = when {
                            rawMsg.contains("5906 >= 4096", ignoreCase = true) || rawMsg.contains("Exceeding the maximum number of tokens", ignoreCase = true) ->
                                AssistantError.ContextLimitExceeded(4096, 5906)
                            rawMsg.contains("state: 7", ignoreCase = true) || rawMsg.contains("Task failed with state", ignoreCase = true) ->
                                AssistantError.ModelGenerationFailed("LiteRT-LM native task failed with state 7: $rawMsg")
                            else ->
                                AssistantError.InternalError(rawMsg)
                        }
                        context.markFailed(error)

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
        }
    }

    /**
     * Backward-compatible handlePrompt delegating to request lifecycle processor.
     */
    suspend fun handlePrompt(prompt: String): List<TurnOutcome> {
        val context = RequestContext(rawInput = prompt)
        return processRequest(context)
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
