package com.example.functiongemmavoice.runtime.orchestrator

import com.example.functiongemmavoice.runtime.ActionRegistry
import com.example.functiongemmavoice.runtime.capability.CapabilityRegistry
import com.example.functiongemmavoice.runtime.intent.CapabilityStatus

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

class PlanValidator(
    private val capabilityRegistry: CapabilityRegistry,
    private val actionRegistry: ActionRegistry
) {

    fun validate(plan: TaskPlan): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (plan.steps.isEmpty()) {
            return ValidationResult(isValid = false, errors = listOf("Plan has no steps to execute."))
        }

        // 1. Check all actions exist in CapabilityRegistry
        val stepIds = mutableSetOf<String>()
        for (step in plan.steps) {
            if (step.stepId in stepIds) {
                errors.add("Duplicate stepId detected: '${step.stepId}'")
            }
            stepIds.add(step.stepId)

            val cap = capabilityRegistry.get(step.actionId)
            if (cap == null) {
                errors.add("Action '${step.actionId}' is not registered in the capability registry.")
                continue
            }

            // 2. Argument validation via ActionRegistry
            val prep = actionRegistry.prepare(cap.canonicalId, step.validatedArguments)
            if (prep == null) {
                errors.add("Arguments validation failed for step '${step.stepId}' (action '${step.actionId}').")
            }

            // 3. Permission and API constraints check
            val status = cap.checkStatus(actionRegistry.context)
            if (status == CapabilityStatus.UNAVAILABLE) {
                errors.add("Capability '${step.actionId}' is not supported on this device/API level.")
            } else if (status == CapabilityStatus.AVAILABLE_WITH_PERMISSION) {
                warnings.add("Step '${step.stepId}' requires runtime permission: ${cap.requiredPermissions}")
            }
        }

        // 4. DAG Cycle Detection
        val graph = mutableMapOf<String, MutableList<String>>()
        val inDegree = mutableMapOf<String, Int>()
        for (step in plan.steps) {
            graph[step.stepId] = mutableListOf()
            inDegree[step.stepId] = 0
        }

        for (step in plan.steps) {
            for (dep in step.dependsOn) {
                if (dep !in graph) {
                    errors.add("Step '${step.stepId}' depends on unknown step '$dep'.")
                } else {
                    graph[dep]?.add(step.stepId)
                    inDegree[step.stepId] = (inDegree[step.stepId] ?: 0) + 1
                }
            }
        }

        // Kahn's algorithm for topological sorting
        val queue = ArrayDeque<String>()
        for ((id, deg) in inDegree) {
            if (deg == 0) queue.add(id)
        }

        var visitedCount = 0
        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            visitedCount++
            for (neighbor in graph[curr].orEmpty()) {
                val newDeg = (inDegree[neighbor] ?: 1) - 1
                inDegree[neighbor] = newDeg
                if (newDeg == 0) {
                    queue.add(neighbor)
                }
            }
        }

        if (visitedCount < plan.steps.size) {
            errors.add("Circular dependency detected in plan graph.")
        }

        // 5. Total plan timeout budget check
        val totalEstimatedTimeout = plan.steps.sumOf { it.timeoutMs }
        if (totalEstimatedTimeout > plan.deadlineMs * 2) {
            warnings.add("Cumulative step timeouts ($totalEstimatedTimeout ms) exceed plan deadline (${plan.deadlineMs} ms).")
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }
}
