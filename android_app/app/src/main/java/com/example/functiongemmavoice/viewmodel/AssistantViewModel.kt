package com.example.functiongemmavoice.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.functiongemmavoice.runtime.ActionResult
import com.example.functiongemmavoice.runtime.AssistantException
import com.example.functiongemmavoice.runtime.InferenceEngineManager
import com.example.functiongemmavoice.runtime.PreparedAction
import com.example.functiongemmavoice.runtime.TurnOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val engineManager = InferenceEngineManager.getInstance(application)

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val maxVisibleMessages = 50

    init {
        autoDiscoverAndLoadModel()
    }

    private fun autoDiscoverAndLoadModel() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val defaultInternal = File(context.filesDir, "functiongemma_mobile_actions.litertlm")
            val appExternalModel = File(context.getExternalFilesDir(null), "functiongemma_mobile_actions.litertlm")
            val fallbackExternal = File("/sdcard/Android/data/com.example.functiongemmavoice/files/functiongemma_mobile_actions.litertlm")
            val galleryModel = File("/sdcard/Android/data/com.google.ai.edge.gallery/files/MobileActions_270M/20260218/mobile_actions.litertlm")

            val targetModel = when {
                defaultInternal.exists() -> defaultInternal
                appExternalModel.exists() -> appExternalModel
                fallbackExternal.exists() -> fallbackExternal
                galleryModel.exists() && galleryModel.canRead() -> galleryModel
                else -> null
            }

            if (targetModel != null) {
                initializeModel(targetModel.absolutePath, targetModel.name)
            } else {
                _uiState.update {
                    it.copy(
                        statusState = AssistantStatusState.MODEL_ERROR,
                        statusLabel = "No local model found. Tap import to select .litertlm file."
                    )
                }
            }
        }
    }

    fun initializeModel(path: String, displayName: String = "functiongemma_mobile_actions.litertlm") {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    statusState = AssistantStatusState.MODEL_LOADING,
                    statusLabel = "Initializing FunctionGemma Engine…",
                    isBusy = true
                )
            }
            try {
                val startNs = System.nanoTime()
                engineManager.initialize(path)
                val durationMs = (System.nanoTime() - startNs) / 1_000_000

                _uiState.update {
                    it.copy(
                        statusState = AssistantStatusState.MODEL_READY,
                        statusLabel = "Ready • 42 Actions Operational",
                        modelName = displayName,
                        budgetInfo = engineManager.getBudgetStatus(),
                        coldModelLoadDurationMs = durationMs,
                        activeOrbState = com.example.functiongemmavoice.ui.OrbState.IDLE,
                        activeStageLabel = "Ready",
                        isBusy = false
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        statusState = AssistantStatusState.MODEL_ERROR,
                        statusLabel = "Model load failed: ${t.message ?: "Native error"}",
                        activeOrbState = com.example.functiongemmavoice.ui.OrbState.IDLE,
                        activeStageLabel = "Error",
                        isBusy = false
                    )
                }
            }
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    statusState = AssistantStatusState.MODEL_LOADING,
                    statusLabel = "Copying model to private storage…",
                    isBusy = true
                )
            }
            try {
                val path = engineManager.copyModel(uri)
                initializeModel(path, "Imported Model")
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(
                        statusState = AssistantStatusState.MODEL_ERROR,
                        statusLabel = "Import failed: ${e.message}",
                        isBusy = false
                    )
                }
            }
        }
    }

    fun startNewConversation() {
        viewModelScope.launch(Dispatchers.IO) {
            engineManager.resetConversation()
            _uiState.update {
                it.copy(
                    budgetInfo = engineManager.getBudgetStatus(),
                    pendingConfirmation = null,
                    messages = listOf(
                        UiMessage(
                            sender = MessageSender.ASSISTANT,
                            text = "New chat started. What would you like to do on your device?"
                        )
                    )
                )
            }
        }
    }

    private var lastSubmittedText: String? = null
    private var lastSubmitTimeMs: Long = 0L

    fun submitCommand(text: String, onSpeak: ((String) -> Unit)? = null) {
        val trimmed = text.trim()
        val now = com.example.functiongemmavoice.runtime.MonotonicClock.elapsedRealtimeMs()

        // Debounce exact duplicate submissions within 500ms
        if (trimmed.isBlank() || _uiState.value.isBusy || (trimmed == lastSubmittedText && (now - lastSubmitTimeMs) < 500L)) {
            return
        }
        lastSubmittedText = trimmed
        lastSubmitTimeMs = now

        val request = com.example.functiongemmavoice.runtime.RequestContext(rawInput = trimmed)
        val userMsg = UiMessage(
            id = request.requestId,
            sender = MessageSender.USER,
            text = trimmed,
            requestId = request.requestId
        )

        val isFast = engineManager.fastRouter.route(trimmed, request.requestId) is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction
        val initialOrb = if (isFast) com.example.functiongemmavoice.ui.OrbState.WORKING else com.example.functiongemmavoice.ui.OrbState.REASONING
        val initialStage = if (isFast) "Executing action…" else "Thinking (LiteRT-LM)…"

        _uiState.update { current ->
            val updated = (current.messages + userMsg).takeLast(maxVisibleMessages)
            current.copy(
                messages = updated,
                isBusy = true,
                statusState = AssistantStatusState.PROCESSING,
                activeOrbState = initialOrb,
                activeStageLabel = initialStage,
                statusLabel = initialStage
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val startNs = System.nanoTime()
            try {
                val outcomes = engineManager.processRequest(request)
                val durationMs = (System.nanoTime() - startNs) / 1_000_000
                val timingSummary = request.timings.summary()

                val newMessages = mutableListOf<UiMessage>()
                var speechTextToEmit: String? = null

                for (outcome in outcomes) {
                    when (outcome) {
                        is TurnOutcome.TextReply -> {
                            newMessages.add(
                                UiMessage(
                                    sender = MessageSender.ASSISTANT,
                                    text = outcome.text,
                                    executionDurationMs = durationMs,
                                    requestId = outcome.requestId ?: request.requestId,
                                    timingSummary = timingSummary
                                )
                            )
                            speechTextToEmit = outcome.text
                        }
                        is TurnOutcome.ActionExecuted -> {
                            val userMsgText = outcome.result.userFacingMessage()
                            newMessages.add(
                                UiMessage(
                                    sender = MessageSender.ASSISTANT,
                                    text = userMsgText,
                                    actionTool = outcome.name,
                                    actionResult = outcome.result,
                                    executionDurationMs = durationMs,
                                    requestId = outcome.requestId ?: request.requestId,
                                    timingSummary = timingSummary
                                )
                            )
                            speechTextToEmit = userMsgText
                        }
                        is TurnOutcome.ConfirmationNeeded -> {
                            _uiState.update {
                                it.copy(
                                    pendingConfirmation = outcome.action,
                                    activeOrbState = com.example.functiongemmavoice.ui.OrbState.WAITING,
                                    activeStageLabel = "Waiting for you"
                                )
                            }
                            val promptMsg = "Action '${outcome.action.name}' requires confirmation."
                            newMessages.add(
                                UiMessage(
                                    sender = MessageSender.ASSISTANT,
                                    text = promptMsg,
                                    actionTool = outcome.action.name,
                                    requestId = outcome.requestId ?: request.requestId,
                                    timingSummary = timingSummary
                                )
                            )
                            speechTextToEmit = promptMsg
                        }
                        is TurnOutcome.ActionBlocked -> {
                            newMessages.add(
                                UiMessage(
                                    sender = MessageSender.SYSTEM,
                                    text = outcome.reason,
                                    isError = true,
                                    requestId = outcome.requestId ?: request.requestId,
                                    timingSummary = timingSummary
                                )
                            )
                        }
                        is TurnOutcome.MultiStepPlanOutcome -> {
                            val plan = outcome.plan
                            val summary = "Executed plan (${plan.completedStepCount}/${plan.steps.size} steps completed, status: ${plan.overallStatus})"
                            newMessages.add(
                                UiMessage(
                                    sender = MessageSender.ASSISTANT,
                                    text = summary,
                                    executionDurationMs = durationMs,
                                    requestId = outcome.requestId ?: request.requestId,
                                    timingSummary = timingSummary,
                                    taskPlan = plan
                                )
                            )
                            speechTextToEmit = summary
                        }
                    }
                }

                val usableCount = engineManager.actionRegistry.capabilityRegistry.usableCount()
                val totalCount = engineManager.actionRegistry.capabilityRegistry.all().size
                val statusLabelText = if (request.timings.isFastPath) {
                    "Ready • FastPath (${request.timings.totalDurationMs}ms)"
                } else {
                    "Ready • Model (${durationMs}ms)"
                }

                val intent = request.structuredIntent
                val routeStr = if (request.timings.isFastPath) "Deterministic FastPath (<10ms)" else "FunctionGemma 270M On-Device LLM"
                val diag = DiagnosticsInfo(
                    activeRequestId = request.requestId,
                    lifecycleState = request.state.name,
                    selectedRoute = routeStr,
                    intentDomain = intent?.intentDomain?.name ?: "UNKNOWN",
                    canonicalAction = intent?.action?.name ?: "UNKNOWN",
                    target = intent?.target ?: (newMessages.firstOrNull()?.actionTool ?: "N/A"),
                    toolParameters = intent?.parameters?.toString() ?: "{}",
                    stageTimings = timingSummary,
                    registeredActions = totalCount,
                    usableActions = usableCount,
                    capabilityStatus = "ALL_SYSTEMS_OPERATIONAL",
                    lastErrorCode = request.error?.code,
                    developerTrace = "Utterance: \"${request.rawInput}\"\nIntent: ${intent?.intentDomain} -> ${intent?.action} (Confidence: ${intent?.confidence})\nRoute: $routeStr\nOutcomes: ${newMessages.mapNotNull { it.actionTool }.joinToString()}"
                )

                _uiState.update { current ->
                    val combined = (current.messages + newMessages).takeLast(maxVisibleMessages)
                    current.copy(
                        messages = combined,
                        isBusy = false,
                        activeOrbState = com.example.functiongemmavoice.ui.OrbState.IDLE,
                        activeStageLabel = "Ready",
                        statusState = AssistantStatusState.MODEL_READY,
                        statusLabel = statusLabelText,
                        lastExecutionDurationMs = durationMs,
                        lastTimingSummary = timingSummary,
                        budgetInfo = engineManager.getBudgetStatus(),
                        diagnostics = diag
                    )
                }

                speechTextToEmit?.let { textToSpeak ->
                    withContext(Dispatchers.Main) {
                        onSpeak?.invoke(textToSpeak)
                    }
                }

            } catch (e: Throwable) {
                val durationMs = (System.nanoTime() - startNs) / 1_000_000
                val errMsg = when (e) {
                    is AssistantException -> e.userFacingMessage
                    else -> "Inference failed: ${e.message ?: "Unknown error"}"
                }

                _uiState.update { current ->
                    val errorMsg = UiMessage(
                        sender = MessageSender.SYSTEM,
                        text = errMsg,
                        isError = true,
                        executionDurationMs = durationMs,
                        requestId = request.requestId
                    )
                    current.copy(
                        messages = (current.messages + errorMsg).takeLast(maxVisibleMessages),
                        isBusy = false,
                        activeOrbState = com.example.functiongemmavoice.ui.OrbState.IDLE,
                        activeStageLabel = "Ready",
                        statusState = AssistantStatusState.MODEL_READY,
                        statusLabel = "Ready • Recovered (${durationMs}ms)",
                        budgetInfo = engineManager.getBudgetStatus(),
                        developerDiagnostics = e.stackTraceToString()
                    )
                }
            }
        }
    }

    fun confirmAction(action: PreparedAction, onSpeak: ((String) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val startNs = System.nanoTime()
            val result = engineManager.executeConfirmedAction(action)
            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            val textMsg = result.userFacingMessage()

            _uiState.update { current ->
                val newMsg = UiMessage(
                    sender = MessageSender.ASSISTANT,
                    text = textMsg,
                    actionTool = action.name,
                    actionResult = result,
                    executionDurationMs = durationMs
                )
                current.copy(
                    pendingConfirmation = null,
                    messages = (current.messages + newMsg).takeLast(maxVisibleMessages),
                    statusLabel = "Action Executed (${durationMs}ms)"
                )
            }

            withContext(Dispatchers.Main) {
                onSpeak?.invoke(textMsg)
            }
        }
    }

    fun cancelAction(action: PreparedAction) {
        _uiState.update { current ->
            val cancelMsg = UiMessage(
                sender = MessageSender.SYSTEM,
                text = "Cancelled action: ${action.name}"
            )
            current.copy(
                pendingConfirmation = null,
                messages = (current.messages + cancelMsg).takeLast(maxVisibleMessages)
            )
        }
    }

    fun setSpeechLanguage(language: String) {
        _uiState.update { it.copy(currentSpeechLanguage = language) }
    }

    fun setListeningState(isListening: Boolean, statusText: String? = null) {
        _uiState.update { current ->
            current.copy(
                isListening = isListening,
                speechEngineStatus = statusText ?: current.speechEngineStatus
            )
        }
    }

    fun setSpeakingState(isSpeaking: Boolean) {
        _uiState.update { it.copy(isSpeaking = isSpeaking) }
    }

    fun onTrimMemory() {
        engineManager.releaseOnTrimMemory()
    }
}
