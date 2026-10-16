package com.example.functiongemmavoice.runtime.intent

import java.util.UUID

enum class IntentDomain {
    SETTINGS_NAVIGATION,
    SYSTEM_INSPECTION,
    STATE_MUTATION,
    DEVICE_CONNECTIVITY,
    APP_LAUNCH,
    IN_APP_OPERATION,
    INFORMATION_QUERY,
    MULTI_ACTION,
    COMMUNICATION,
    TIME_PRODUCTIVITY,
    UNKNOWN
}

enum class SemanticAction {
    OPEN_SETTINGS,
    GET_STATE,
    SET_STATE,
    CONNECT,
    DISCONNECT,
    LAUNCH_APP,
    EXECUTE_IN_APP,
    QUERY_INFO,
    SET_TIMER,
    SET_ALARM,
    CREATE_CALENDAR,
    COMPOSE,
    SEND,
    CLARIFY,
    REJECT_NEGATED,
    UNKNOWN
}

enum class DesiredState {
    ON,
    OFF,
    TOGGLE,
    OBSERVE,
    OPEN,
    COMPOSE,
    NONE
}

enum class ExecutionStrategy {
    FAST_PATH_DETERMINISTIC,
    MODEL_INFERENCE,
    SYSTEM_CONSENT_DIALOG,
    USER_CONFIRMATION_REQUIRED,
    CLARIFICATION_REQUIRED,
    UNSUPPORTED,
    NEGATED_REJECTED
}

enum class CapabilityStatus {
    AVAILABLE,
    AVAILABLE_WITH_PERMISSION,
    REQUIRES_SYSTEM_UI,
    UNAVAILABLE,
    NOT_IMPLEMENTED
}

data class NegationInfo(
    val hasNegation: Boolean,
    val negatedAction: SemanticAction? = null,
    val negatedTarget: String? = null,
    val rawNegationPhrase: String = "",
    val preservesPositiveAction: Boolean = false
)

data class StructuredIntent(
    val requestId: String = UUID.randomUUID().toString(),
    val originalUtterance: String,
    val normalizedUtterance: String,
    val intentDomain: IntentDomain,
    val action: SemanticAction,
    val target: String,
    val desiredState: DesiredState = DesiredState.NONE,
    val parameters: Map<String, Any?> = emptyMap(),
    val confidence: Float = 1.0f,
    val requiresConfirmation: Boolean = false,
    val executionStrategy: ExecutionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
    val capabilityStatus: CapabilityStatus = CapabilityStatus.AVAILABLE,
    val negation: NegationInfo? = null,
    val dependencies: List<String> = emptyList(),
    val clarificationPrompt: String? = null,
    val canonicalToolName: String? = null,
    val subIntents: List<StructuredIntent> = emptyList()
) {
    val isMultiAction: Boolean
        get() = intentDomain == IntentDomain.MULTI_ACTION && subIntents.isNotEmpty()

    val isAmbiguous: Boolean
        get() = executionStrategy == ExecutionStrategy.CLARIFICATION_REQUIRED || confidence < 0.70f
}
