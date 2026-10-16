package com.example.functiongemmavoice

import android.content.ContextWrapper
import com.example.functiongemmavoice.runtime.ActionRegistry
import com.example.functiongemmavoice.runtime.ActionResult
import com.example.functiongemmavoice.runtime.capability.CapabilityRegistry
import com.example.functiongemmavoice.runtime.intent.IntentDomain
import com.example.functiongemmavoice.runtime.laya.LocalLayaDecisionAdapter
import com.example.functiongemmavoice.runtime.laya.RouteRecommendation
import com.example.functiongemmavoice.runtime.orchestrator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MultiStepOrchestrationTest {

    private class MockContext : ContextWrapper(null)

    // =========================================================================
    // SECTION J.1: PlanValidator Tests (DAG Cycles, Missing Deps, Capability Checks)
    // =========================================================================

    @Test
    fun testPlanValidator_AcyclicPlan_IsValid() {
        val registry = ActionRegistry(MockContext())
        val validator = PlanValidator(registry.capabilityRegistry, registry)

        val step1 = TaskStep(
            stepId = "step_1",
            actionId = "get_battery_status",
            displayName = "Check battery level"
        )
        val step2 = TaskStep(
            stepId = "step_2",
            actionId = "open_bluetooth_settings",
            displayName = "Open bluetooth settings",
            dependsOn = listOf("step_1")
        )

        val plan = TaskPlan(
            userGoal = "Check battery and open bluetooth settings",
            steps = listOf(step1, step2)
        )

        val result = validator.validate(plan)
        assertTrue("Acyclic plan must be valid", result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun testPlanValidator_CycleDetection_RejectsCircularDependency() {
        val registry = ActionRegistry(MockContext())
        val validator = PlanValidator(registry.capabilityRegistry, registry)

        // Step 1 depends on Step 2; Step 2 depends on Step 1
        val step1 = TaskStep(
            stepId = "step_1",
            actionId = "get_battery_status",
            displayName = "Check battery",
            dependsOn = listOf("step_2")
        )
        val step2 = TaskStep(
            stepId = "step_2",
            actionId = "open_bluetooth_settings",
            displayName = "Open BT",
            dependsOn = listOf("step_1")
        )

        val plan = TaskPlan(
            userGoal = "Circular plan",
            steps = listOf(step1, step2)
        )

        val result = validator.validate(plan)
        assertFalse("Circular dependency plan must be rejected", result.isValid)
        assertTrue("Error message must mention circular dependency",
            result.errors.any { it.contains("Circular dependency", ignoreCase = true) })
    }

    @Test
    fun testPlanValidator_MissingDependency_RejectsPlan() {
        val registry = ActionRegistry(MockContext())
        val validator = PlanValidator(registry.capabilityRegistry, registry)

        val step1 = TaskStep(
            stepId = "step_1",
            actionId = "get_battery_status",
            displayName = "Check battery",
            dependsOn = listOf("non_existent_step")
        )

        val plan = TaskPlan(
            userGoal = "Missing dependency plan",
            steps = listOf(step1)
        )

        val result = validator.validate(plan)
        assertFalse("Plan with missing dependency must be rejected", result.isValid)
        assertTrue(result.errors.any { it.contains("unknown step", ignoreCase = true) })
    }

    @Test
    fun testPlanValidator_UnknownCapability_RejectsPlan() {
        val registry = ActionRegistry(MockContext())
        val validator = PlanValidator(registry.capabilityRegistry, registry)

        val step1 = TaskStep(
            stepId = "step_1",
            actionId = "unregistered_malicious_action",
            displayName = "Exploit action"
        )

        val plan = TaskPlan(
            userGoal = "Invalid capability plan",
            steps = listOf(step1)
        )

        val result = validator.validate(plan)
        assertFalse("Plan with unregistered capability must be rejected", result.isValid)
        assertTrue(result.errors.any { it.contains("not registered", ignoreCase = true) })
    }

    @Test
    fun testPlanValidator_DuplicateStepId_RejectsPlan() {
        val registry = ActionRegistry(MockContext())
        val validator = PlanValidator(registry.capabilityRegistry, registry)

        val step1 = TaskStep(
            stepId = "duplicate_id",
            actionId = "get_battery_status",
            displayName = "First check"
        )
        val step2 = TaskStep(
            stepId = "duplicate_id",
            actionId = "open_bluetooth_settings",
            displayName = "Second check"
        )

        val plan = TaskPlan(
            userGoal = "Duplicate step IDs plan",
            steps = listOf(step1, step2)
        )

        val result = validator.validate(plan)
        assertFalse("Plan with duplicate step IDs must be rejected", result.isValid)
        assertTrue(result.errors.any { it.contains("Duplicate stepId", ignoreCase = true) })
    }

    // =========================================================================
    // SECTION J.2: TaskPlanScheduler Tests (Dependency-aware, Skips, Partial Success)
    // =========================================================================

    @Test
    fun testScheduler_LinearPlan_Execution() = runBlocking {
        val registry = ActionRegistry(MockContext())
        val validator = PlanValidator(registry.capabilityRegistry, registry)
        val scheduler = TaskPlanScheduler(registry, validator)

        // Mock battery will succeed in mock context or yield result
        val step1 = TaskStep(
            stepId = "step_1",
            actionId = "get_battery_status",
            displayName = "Check battery status"
        )
        val step2 = TaskStep(
            stepId = "step_2",
            actionId = "open_bluetooth_settings",
            displayName = "Open BT settings",
            dependsOn = listOf("step_1")
        )

        val plan = TaskPlan(
            userGoal = "Inspect battery then open BT",
            steps = listOf(step1, step2)
        )

        val finalStatus = scheduler.executePlan(plan)
        // In MockContext, intent opening might return Success or Error depending on startActivity mock
        // Either way, scheduler must run step 1 first, and evaluate step 2 based on step 1 status
        assertTrue(plan.steps[0].isTerminal)
        if (plan.steps[0].status == StepStatus.SUCCESS) {
            assertTrue("Step 2 should have executed because step 1 succeeded", plan.steps[1].isTerminal)
        } else {
            assertEquals("Step 2 should be skipped if step 1 failed", StepStatus.SKIPPED, plan.steps[1].status)
        }
    }

    @Test
    fun testScheduler_DependencyFailure_SkipsDependentStep() = runBlocking {
        val registry = ActionRegistry(MockContext())
        val validator = PlanValidator(registry.capabilityRegistry, registry)
        val scheduler = TaskPlanScheduler(registry, validator)

        val step1 = TaskStep(
            stepId = "step_1",
            actionId = "get_battery_status",
            displayName = "Initial step"
        )
        val step2 = TaskStep(
            stepId = "step_2",
            actionId = "open_bluetooth_settings",
            displayName = "Dependent step",
            dependsOn = listOf("step_1")
        )

        val plan = TaskPlan(
            userGoal = "Test dependency skip",
            steps = listOf(step1, step2)
        )

        // Pre-mark step 1 as FAILED to simulate execution failure
        step1.status = StepStatus.FAILED
        step1.errorCode = "DEVICE_HARDWARE_ERROR"

        scheduler.executePlan(plan)

        // Dependent step must be SKIPPED because step 1 failed
        assertEquals("Dependent step must be SKIPPED when prerequisite fails",
            StepStatus.SKIPPED, plan.steps[1].status)
        assertEquals("DEPENDENCY_FAILED", plan.steps[1].errorCode)
        assertEquals(PlanStatus.FAILED, plan.overallStatus)
    }

    @Test
    fun testAppLaunchRouting_AllAliasesAndPhrases() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val queries = listOf(
            "Open YouTube" to "YouTube",
            "open whatsapp" to "WhatsApp",
            "Open WhatsApp" to "WhatsApp",
            "launch WhatsApp" to "WhatsApp",
            "Can you open WhatsApp for me?" to "WhatsApp",
            "start Chrome" to "Chrome",
            "Open Maps" to "Google Maps",
            "వాట్సాప్ ఓపెన్ చెయ్" to "WhatsApp",
            "యూట్యూబ్ ఓపెన్ చెయ్" to "YouTube"
        )

        for ((input, expectedApp) in queries) {
            val decision = router.route(input)
            assertTrue("Expected DirectAction for '$input', got $decision", decision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
            val action = (decision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0]
            assertEquals("open_app", action.name)
            assertEquals("Expected $expectedApp for '$input'", expectedApp, action.args.getString("app_name"))
        }
    }

    // =========================================================================
    // SECTION J.3: Laya Decision Adapter Benchmarks & Routing
    // =========================================================================

    @Test
    fun testLocalLayaDecisionAdapter_LatencyBenchmark() {
        val registry = ActionRegistry(MockContext())
        val laya = LocalLayaDecisionAdapter(registry.capabilityRegistry)

        // Warm up
        repeat(5) {
            laya.classify("open bluetooth settings")
        }

        // Measure
        val start = System.nanoTime()
        val decision = laya.classify("turn on the flashlight and check my battery level")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        assertTrue("Laya decision latency must be < 10ms (actual: ${elapsedMs}ms)", elapsedMs < 10.0)
        assertTrue("Multi-action utterance must be detected as multi-step", decision.isMultiStep)
        assertEquals(IntentDomain.STATE_MUTATION, decision.intentDomain)
        assertEquals(RouteRecommendation.DETERMINISTIC_FAST_PATH, decision.recommendedRoute)
    }

    @Test
    fun testLocalLayaDecisionAdapter_ClassificationAccuracy() {
        val registry = ActionRegistry(MockContext())
        val laya = LocalLayaDecisionAdapter(registry.capabilityRegistry)

        // Bluetooth settings
        val btDecision = laya.classify("open bluetooth settings")
        assertEquals(IntentDomain.SETTINGS_NAVIGATION, btDecision.intentDomain)
        assertFalse(btDecision.isMultiStep)
        assertFalse(btDecision.isAmbiguous)
        assertEquals(RouteRecommendation.DETERMINISTIC_FAST_PATH, btDecision.recommendedRoute)

        // Negation detection
        val negDecision = laya.classify("do not turn on the flashlight")
        assertEquals(RouteRecommendation.REJECT_NEGATED, negDecision.recommendedRoute)

        // Interrogative / reasoning query
        val reasoningDecision = laya.classify("why is the sky blue?")
        assertEquals(RouteRecommendation.FUNCTIONGEMMA_INFERENCE, reasoningDecision.recommendedRoute)

        // Telugu device control
        val teluguDecision = laya.classify("లైట్ ఆన్ చెయ్")
        assertEquals(IntentDomain.STATE_MUTATION, teluguDecision.intentDomain)
        assertEquals(RouteRecommendation.DETERMINISTIC_FAST_PATH, teluguDecision.recommendedRoute)
    }
}
