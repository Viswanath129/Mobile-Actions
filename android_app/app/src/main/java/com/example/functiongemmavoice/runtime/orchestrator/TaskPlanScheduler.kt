package com.example.functiongemmavoice.runtime.orchestrator

import com.example.functiongemmavoice.runtime.ActionRegistry
import com.example.functiongemmavoice.runtime.ActionResult
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class TaskPlanScheduler(
    private val actionRegistry: ActionRegistry,
    private val validator: PlanValidator,
    private val maxConcurrency: Int = 2,
    private val customActionExecutor: (suspend (String, JSONObject) -> ActionResult)? = null
) {

    private val semaphore = Semaphore(maxConcurrency)

    suspend fun executePlan(
        plan: TaskPlan,
        onStepProgress: ((TaskStep) -> Unit)? = null
    ): PlanStatus = withContext(Dispatchers.IO) {
        val validation = validator.validate(plan)
        if (!validation.isValid) {
            plan.overallStatus = PlanStatus.FAILED
            for (step in plan.steps) {
                step.status = StepStatus.FAILED
                step.errorCode = "PLAN_VALIDATION_FAILED: ${validation.errors.firstOrNull()}"
            }
            return@withContext PlanStatus.FAILED
        }

        plan.overallStatus = PlanStatus.EXECUTING
        val stepMap = plan.steps.associateBy { it.stepId }
        val remainingSteps = plan.steps.toMutableList()

        while (remainingSteps.any { !it.isTerminal }) {
            if (plan.overallStatus == PlanStatus.CANCELLED) {
                for (rem in remainingSteps.filter { !it.isTerminal }) {
                    rem.status = StepStatus.CANCELLED
                    rem.errorCode = "ACTION_CANCELLED"
                    onStepProgress?.invoke(rem)
                }
                return@withContext PlanStatus.CANCELLED
            }
            // Find steps whose dependencies are all satisfied with SUCCESS
            val readySteps = remainingSteps.filter { step ->
                step.status == StepStatus.PENDING && step.dependsOn.all { depId ->
                    stepMap[depId]?.status == StepStatus.SUCCESS
                }
            }

            // Check for failed dependencies
            for (step in remainingSteps.filter { it.status == StepStatus.PENDING }) {
                val hasFailedDep = step.dependsOn.any { depId ->
                    val dep = stepMap[depId]
                    dep?.status in setOf(StepStatus.FAILED, StepStatus.SKIPPED, StepStatus.CANCELLED)
                }
                if (hasFailedDep) {
                    step.status = StepStatus.SKIPPED
                    step.errorCode = "DEPENDENCY_FAILED"
                    onStepProgress?.invoke(step)
                }
            }

            if (readySteps.isEmpty()) {
                // No ready steps remaining
                break
            }

            // Execute ready steps (sequential if policy is SEQUENTIAL, else bounded concurrency)
            for (step in readySteps) {
                executeStepWithRetry(step)
                onStepProgress?.invoke(step)
                if (plan.executionPolicy == ExecutionPolicy.STOP_ON_FAILURE && step.status == StepStatus.FAILED) {
                    // Mark remaining as cancelled
                    for (rem in remainingSteps.filter { !it.isTerminal }) {
                        rem.status = StepStatus.CANCELLED
                        onStepProgress?.invoke(rem)
                    }
                    break
                }
            }
        }

        val successCount = plan.steps.count { it.status == StepStatus.SUCCESS }
        val failedCount = plan.steps.count { it.status == StepStatus.FAILED }
        val skippedCount = plan.steps.count { it.status in setOf(StepStatus.SKIPPED, StepStatus.CANCELLED) }

        plan.overallStatus = when {
            successCount == plan.steps.size -> PlanStatus.COMPLETED
            successCount > 0 && (failedCount > 0 || skippedCount > 0) -> PlanStatus.PARTIAL_SUCCESS
            plan.steps.any { it.status == StepStatus.CANCELLED } -> PlanStatus.CANCELLED
            else -> PlanStatus.FAILED
        }

        plan.overallStatus
    }

    private suspend fun executeStepWithRetry(step: TaskStep) {
        step.status = StepStatus.RUNNING
        var attempts = 0
        val maxAttempts = if (step.retryPolicy.isRetrySafe) (step.retryPolicy.maxRetries + 1) else 1

        while (attempts < maxAttempts) {
            attempts++
            try {
                semaphore.withPermit {
                    val cap = actionRegistry.capabilityRegistry.get(step.actionId)
                    val canonicalName = cap?.canonicalId ?: step.actionId
                    val result = customActionExecutor?.invoke(canonicalName, step.validatedArguments)
                        ?: actionRegistry.execute(canonicalName, step.validatedArguments)
                    step.executionResult = result

                    when (result) {
                        is ActionResult.Success -> {
                            step.status = StepStatus.SUCCESS
                            return
                        }
                        is ActionResult.NeedsConfirmation -> {
                            step.status = StepStatus.PENDING
                            return
                        }
                        is ActionResult.Cancelled -> {
                            step.status = StepStatus.CANCELLED
                            return
                        }
                        else -> {
                            step.errorCode = result.userFacingMessage()
                            if (attempts < maxAttempts && step.retryPolicy.isRetrySafe) {
                                delay(step.retryPolicy.backoffMs * attempts)
                            } else {
                                step.status = StepStatus.FAILED
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                step.errorCode = t.message ?: "Execution exception"
                if (attempts < maxAttempts && step.retryPolicy.isRetrySafe) {
                    delay(step.retryPolicy.backoffMs * attempts)
                } else {
                    step.status = StepStatus.FAILED
                }
            }
        }
    }

    fun cancelPlan(plan: TaskPlan) {
        plan.overallStatus = PlanStatus.CANCELLED
        for (step in plan.steps) {
            if (!step.isTerminal) {
                step.status = StepStatus.CANCELLED
                step.errorCode = "ACTION_CANCELLED"
            }
        }
    }
}
