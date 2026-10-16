package com.example.functiongemmavoice.runtime.orchestrator

import com.example.functiongemmavoice.runtime.ActionResult
import org.json.JSONObject
import java.util.UUID

enum class PlanStatus {
    PLANNED,
    EXECUTING,
    COMPLETED,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED
}

enum class StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
    CANCELLED
}

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

enum class ExecutionPolicy {
    SEQUENTIAL,
    DAG_CONCURRENT,
    STOP_ON_FAILURE
}

data class RetryPolicy(
    val maxRetries: Int = 0,
    val backoffMs: Long = 500L,
    val isRetrySafe: Boolean = false
)

data class StepPrecondition(
    val description: String,
    val check: () -> Boolean = { true }
)

data class TaskStep(
    val stepId: String = UUID.randomUUID().toString(),
    val actionId: String,
    val displayName: String = actionId,
    val validatedArguments: JSONObject = JSONObject(),
    val dependsOn: List<String> = emptyList(),
    val preconditions: List<StepPrecondition> = emptyList(),
    val successCriteria: String = "Execution returned non-error result",
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val timeoutMs: Long = 10_000L,
    val confirmationRequirement: Boolean = false,
    var status: StepStatus = StepStatus.PENDING,
    var executionResult: ActionResult? = null,
    var errorCode: String? = null
) {
    val isTerminal: Boolean
        get() = status in setOf(StepStatus.SUCCESS, StepStatus.FAILED, StepStatus.SKIPPED, StepStatus.CANCELLED)
}

data class TaskPlan(
    val planId: String = UUID.randomUUID().toString(),
    val requestId: String = UUID.randomUUID().toString(),
    val userGoal: String,
    val steps: List<TaskStep>,
    val executionPolicy: ExecutionPolicy = ExecutionPolicy.DAG_CONCURRENT,
    val confirmationRequirements: Boolean = steps.any { it.confirmationRequirement },
    val deadlineMs: Long = 30_000L,
    var overallStatus: PlanStatus = PlanStatus.PLANNED
) {
    val isComplete: Boolean
        get() = overallStatus in setOf(PlanStatus.COMPLETED, PlanStatus.PARTIAL_SUCCESS, PlanStatus.FAILED, PlanStatus.CANCELLED)

    val completedStepCount: Int
        get() = steps.count { it.status == StepStatus.SUCCESS }

    val failedStepCount: Int
        get() = steps.count { it.status == StepStatus.FAILED }
}
