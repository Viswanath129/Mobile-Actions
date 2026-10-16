package com.example.functiongemmavoice.viewmodel

import com.example.functiongemmavoice.runtime.ActionResult
import com.example.functiongemmavoice.runtime.ContextBudgetStatus
import com.example.functiongemmavoice.runtime.PreparedAction

enum class AssistantStatusState {
    MODEL_LOADING,
    MODEL_READY,
    MODEL_ERROR,
    PROCESSING
}

enum class MessageSender {
    USER,
    ASSISTANT,
    SYSTEM
}

data class UiMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    val actionTool: String? = null,
    val actionResult: ActionResult? = null,
    val executionDurationMs: Long? = null,
    val isStreaming: Boolean = false,
    val requestId: String? = null,
    val timingSummary: String? = null,
    val taskPlan: com.example.functiongemmavoice.runtime.orchestrator.TaskPlan? = null
)

data class DiagnosticsInfo(
    val activeRequestId: String? = null,
    val lifecycleState: String = "IDLE",
    val selectedRoute: String = "N/A",
    val intentDomain: String = "N/A",
    val canonicalAction: String = "N/A",
    val target: String = "N/A",
    val toolParameters: String = "{}",
    val stageTimings: String = "N/A",
    val contextTokens: String = "2400 / 4096 tokens (Schema + Rolling History)",
    val registeredActions: Int = 42,
    val usableActions: Int = 42,
    val capabilityStatus: String = "ALL_SYSTEMS_OPERATIONAL",
    val lastErrorCode: String? = null,
    val developerTrace: String? = null
)

data class AssistantUiState(
    val statusState: AssistantStatusState = AssistantStatusState.MODEL_LOADING,
    val statusLabel: String = "Initializing FunctionGemma Engine…",
    val modelName: String = "functiongemma_mobile_actions.litertlm",
    val speechEngineStatus: String = "Engine: SpeechRecognizer (On-device preferred)",
    val isBusy: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val currentSpeechLanguage: String = "English + Telugu",
    val activeToolsCount: Int = 42,
    val budgetInfo: ContextBudgetStatus? = null,
    val pendingConfirmation: PreparedAction? = null,
    val lastExecutionDurationMs: Long? = null,
    val coldModelLoadDurationMs: Long? = null,
    val lastTimingSummary: String? = null,
    val activeOrbState: com.example.functiongemmavoice.ui.OrbState = com.example.functiongemmavoice.ui.OrbState.IDLE,
    val activeStageLabel: String = "Ready",
    val developerDiagnostics: String? = null,
    val diagnostics: DiagnosticsInfo = DiagnosticsInfo(),
    val messages: List<UiMessage> = listOf(
        UiMessage(
            sender = MessageSender.ASSISTANT,
            text = "Hello! I am your FunctionGemma Assistant. You can speak in English or Telugu or type commands to control your device."
        )
    )
)
