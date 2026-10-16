package com.example.functiongemmavoice.runtime

import com.example.functiongemmavoice.runtime.intent.StructuredIntent
import java.util.UUID

object MonotonicClock {
    fun elapsedRealtimeMs(): Long {
        return try {
            android.os.SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.nanoTime() / 1_000_000L
        }
    }
}

enum class ErrorCategory {
    INPUT,
    MODEL,
    EXECUTION,
    CONCURRENCY,
    RESOURCE
}

enum class RequestState {
    RECEIVED,
    NORMALIZING,
    ROUTING,
    FAST_PATH,
    MODEL_RUNNING,
    VALIDATING,
    EXECUTING,
    VERIFYING,
    NEEDS_CONFIRMATION,
    COMPLETED,
    CANCELLED,
    TIMED_OUT,
    FAILED;

    fun isTerminal(): Boolean = this in setOf(COMPLETED, CANCELLED, TIMED_OUT, FAILED)
}

class IllegalStateTransitionException(from: RequestState, to: RequestState, requestId: String) :
    IllegalStateException("Illegal state transition from $from to $to for request $requestId")

sealed class AssistantError(
    val code: String,
    val userFacingMessage: String,
    val technicalDetails: String,
    val category: ErrorCategory = ErrorCategory.EXECUTION,
    val isRecoverable: Boolean = true
) {
    data class InvalidInput(val reason: String) : AssistantError(
        "INVALID_INPUT",
        "Invalid command: $reason",
        reason,
        ErrorCategory.INPUT
    )

    data class UnsupportedIntent(val reason: String) : AssistantError(
        "UNSUPPORTED_INTENT",
        "That capability is not currently supported: $reason",
        reason
    )

    data class UnknownTool(val toolName: String) : AssistantError(
        "UNKNOWN_TOOL",
        "Unsupported tool '$toolName' was blocked for safety.",
        "Tool '$toolName' not registered in ActionRegistry allowlist"
    )

    data class InvalidToolArguments(val toolName: String, val details: String) : AssistantError(
        "INVALID_TOOL_ARGUMENTS",
        "Invalid arguments for $toolName: $details",
        details
    )

    data class ModelInitializationFailed(val details: String) : AssistantError(
        "MODEL_INITIALIZATION_FAILED",
        "Failed to initialize FunctionGemma model: $details",
        details
    )

    data class ModelGenerationFailed(val details: String) : AssistantError(
        "MODEL_GENERATION_FAILED",
        "On-device inference failed. A fresh conversation session has been prepared.",
        details
    )

    data class ContextLimitExceeded(val limit: Int, val current: Int) : AssistantError(
        "CONTEXT_LIMIT_EXCEEDED",
        "Conversation context limit exceeded ($current >= $limit). Session was safely compacted.",
        "Tokens exceeded: $current >= $limit"
    )

    data class ToolExecutionFailed(val toolName: String, val details: String) : AssistantError(
        "TOOL_EXECUTION_FAILED",
        "Failed to execute $toolName: $details",
        details
    )

    data class PermissionDenied(val toolName: String, val permission: String) : AssistantError(
        "PERMISSION_DENIED",
        "Permission required for $toolName: $permission",
        "Missing permission: $permission"
    )

    data class FeatureUnavailable(val toolName: String, val feature: String) : AssistantError(
        "FEATURE_UNAVAILABLE",
        "Feature unavailable on this device: $feature",
        "Unavailable hardware/API: $feature"
    )

    data class ActionRequiresConfirmation(val action: PreparedAction) : AssistantError(
        "ACTION_REQUIRES_CONFIRMATION",
        "Action '${action.name}' requires user confirmation before proceeding.",
        "Confirmation pending for ${action.name}"
    )

    data class ActionCancelled(val requestId: String, val reason: String = "Operation was cancelled.") : AssistantError(
        "ACTION_CANCELLED",
        "Request cancelled.",
        "Request $requestId cancelled: $reason"
    )

    data class RequestTimeout(val requestId: String, val timeoutMs: Long) : AssistantError(
        "REQUEST_TIMEOUT",
        "Request timed out after ${timeoutMs}ms.",
        "Request $requestId exceeded deadline of ${timeoutMs}ms"
    )

    data class DuplicateRequest(val requestId: String) : AssistantError(
        "DUPLICATE_REQUEST",
        "A duplicate request is already in progress. Please wait.",
        "Duplicate request rejected for $requestId"
    )

    data class ResultCorrelationFailure(val expectedId: String, val actualId: String) : AssistantError(
        "RESULT_CORRELATION_FAILURE",
        "State correlation mismatch detected. Stale response was safely discarded.",
        "Correlation error: expected $expectedId, received $actualId"
    )

    data class ResourceExhausted(val details: String) : AssistantError(
        "RESOURCE_EXHAUSTED",
        "Device memory pressure detected. Cleaned temporary cache.",
        details
    )

    data class InternalError(val details: String) : AssistantError(
        "INTERNAL_ERROR",
        "An unexpected error occurred. Please try again.",
        details
    )
}

data class RequestTimings(
    var receivedMs: Long = 0L,
    var routingStartMs: Long = 0L,
    var routingEndMs: Long = 0L,
    var modelStartMs: Long = 0L,
    var modelEndMs: Long = 0L,
    var actionStartMs: Long = 0L,
    var actionEndMs: Long = 0L,
    var completedMs: Long = 0L,
    var isFastPath: Boolean = false
) {
    val totalDurationMs: Long
        get() = if (completedMs > 0 && receivedMs > 0) (completedMs - receivedMs).coerceAtLeast(1L) else 0L

    val routingDurationMs: Long
        get() = if (routingEndMs >= routingStartMs) (routingEndMs - routingStartMs) else 0L

    val modelDurationMs: Long
        get() = if (modelEndMs >= modelStartMs) (modelEndMs - modelStartMs) else 0L

    val actionDurationMs: Long
        get() = if (actionEndMs >= actionStartMs) (actionEndMs - actionStartMs) else 0L

    fun summary(): String {
        return if (isFastPath) {
            "FastPath: ${totalDurationMs}ms (Route: ${routingDurationMs}ms, Action: ${actionDurationMs}ms)"
        } else {
            "Model: ${totalDurationMs}ms (Inference: ${modelDurationMs}ms, Action: ${actionDurationMs}ms)"
        }
    }
}

class RequestContext(
    val requestId: String = UUID.randomUUID().toString(),
    val sessionId: String = UUID.randomUUID().toString(),
    val rawInput: String,
    val timeoutMs: Long = 30_000L
) {
    val startTimeRealtimeMs: Long = MonotonicClock.elapsedRealtimeMs()
    val deadlineRealtimeMs: Long = startTimeRealtimeMs + timeoutMs

    var normalizedInput: String = rawInput.trim()
    var state: RequestState = RequestState.RECEIVED
        private set

    val timings = RequestTimings(receivedMs = startTimeRealtimeMs)
    val parsedActions = mutableListOf<PreparedAction>()
    val executionResults = mutableListOf<Pair<String, ActionResult>>()
    var structuredIntent: StructuredIntent? = null
    var error: AssistantError? = null
    var finalResponseText: String? = null

    val isCancelled: Boolean
        get() = state == RequestState.CANCELLED

    val isTimedOut: Boolean
        get() = state == RequestState.TIMED_OUT || (state != RequestState.COMPLETED && MonotonicClock.elapsedRealtimeMs() > deadlineRealtimeMs)

    fun transitionTo(newState: RequestState) {
        if (state.isTerminal()) {
            throw IllegalStateTransitionException(state, newState, requestId)
        }

        // Validate legal transitions
        val valid = when (state) {
            RequestState.RECEIVED -> newState in setOf(RequestState.NORMALIZING, RequestState.FAILED, RequestState.CANCELLED)
            RequestState.NORMALIZING -> newState in setOf(RequestState.ROUTING, RequestState.FAILED, RequestState.CANCELLED)
            RequestState.ROUTING -> newState in setOf(RequestState.FAST_PATH, RequestState.MODEL_RUNNING, RequestState.COMPLETED, RequestState.FAILED, RequestState.CANCELLED)
            RequestState.FAST_PATH -> newState in setOf(RequestState.VALIDATING, RequestState.EXECUTING, RequestState.COMPLETED, RequestState.NEEDS_CONFIRMATION, RequestState.FAILED, RequestState.CANCELLED)
            RequestState.MODEL_RUNNING -> newState in setOf(RequestState.VALIDATING, RequestState.EXECUTING, RequestState.COMPLETED, RequestState.NEEDS_CONFIRMATION, RequestState.FAILED, RequestState.CANCELLED, RequestState.TIMED_OUT)
            RequestState.VALIDATING -> newState in setOf(RequestState.EXECUTING, RequestState.NEEDS_CONFIRMATION, RequestState.COMPLETED, RequestState.FAILED, RequestState.CANCELLED)
            RequestState.EXECUTING -> newState in setOf(RequestState.VERIFYING, RequestState.COMPLETED, RequestState.FAILED, RequestState.CANCELLED)
            RequestState.VERIFYING -> newState in setOf(RequestState.COMPLETED, RequestState.FAILED, RequestState.CANCELLED)
            RequestState.NEEDS_CONFIRMATION -> newState in setOf(RequestState.EXECUTING, RequestState.COMPLETED, RequestState.CANCELLED, RequestState.FAILED)
            else -> false
        }

        if (!valid) {
            throw IllegalStateTransitionException(state, newState, requestId)
        }

        state = newState
        val now = MonotonicClock.elapsedRealtimeMs()

        when (newState) {
            RequestState.ROUTING -> timings.routingStartMs = now
            RequestState.FAST_PATH -> {
                timings.routingEndMs = now
                timings.isFastPath = true
            }
            RequestState.MODEL_RUNNING -> {
                timings.routingEndMs = now
                timings.modelStartMs = now
                timings.isFastPath = false
            }
            RequestState.EXECUTING -> {
                if (timings.modelStartMs > 0 && timings.modelEndMs == 0L) {
                    timings.modelEndMs = now
                }
                timings.actionStartMs = now
            }
            RequestState.VERIFYING, RequestState.COMPLETED, RequestState.FAILED, RequestState.CANCELLED, RequestState.TIMED_OUT -> {
                if (timings.actionStartMs > 0 && timings.actionEndMs == 0L) {
                    timings.actionEndMs = now
                }
                if (newState.isTerminal()) {
                    timings.completedMs = now
                }
            }
            else -> {}
        }
    }

    fun cancel(reason: String = "User cancelled") {
        if (!state.isTerminal()) {
            error = AssistantError.ActionCancelled(requestId, reason)
            state = RequestState.CANCELLED
            timings.completedMs = MonotonicClock.elapsedRealtimeMs()
        }
    }

    fun markTimedOut() {
        if (!state.isTerminal()) {
            error = AssistantError.RequestTimeout(requestId, timeoutMs)
            state = RequestState.TIMED_OUT
            timings.completedMs = MonotonicClock.elapsedRealtimeMs()
        }
    }

    fun markFailed(err: AssistantError) {
        if (!state.isTerminal()) {
            error = err
            state = RequestState.FAILED
            timings.completedMs = MonotonicClock.elapsedRealtimeMs()
        }
    }

    fun markCompleted(reply: String? = null) {
        if (!state.isTerminal()) {
            if (reply != null) {
                finalResponseText = reply
            }
            transitionTo(RequestState.COMPLETED)
        }
    }
}
