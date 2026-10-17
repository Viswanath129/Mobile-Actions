package com.example.functiongemmavoice

import android.content.ContextWrapper
import com.example.functiongemmavoice.runtime.ActionRegistry
import com.example.functiongemmavoice.runtime.ActionResult
import com.example.functiongemmavoice.runtime.AssistantFailureCategory
import com.example.functiongemmavoice.runtime.accessibility.AssistantAccessibilityService
import com.example.functiongemmavoice.runtime.intent.IntentEngine
import com.example.functiongemmavoice.runtime.orchestrator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MandatoryTasksOrchestrationTest {

    private class MockContext : ContextWrapper(null)

    private fun createScheduler(mockExecutor: suspend (String, JSONObject) -> ActionResult): TaskPlanScheduler {
        val registry = ActionRegistry(MockContext())
        val validator = PlanValidator(registry.capabilityRegistry, registry)
        return TaskPlanScheduler(
            actionRegistry = registry,
            validator = validator,
            customActionExecutor = mockExecutor
        )
    }

    // =========================================================================
    // TASK A: Study Mode
    // DND enabled, ringer set to silent/vibrate, brightness dimmed, timer set (45m)
    // =========================================================================
    @Test
    fun testTaskA_StudyMode_ExecutesAllStepsDeterministically() = runBlocking {
        val executedActions = mutableListOf<String>()

        val scheduler = createScheduler { actionId, _ ->
            executedActions.add(actionId)
            when (actionId) {
                "set_dnd_mode" -> ActionResult.Success(actionId, "Do Not Disturb turned ON.")
                "set_ringer_mode" -> ActionResult.Success(actionId, "Ringer mode set to SILENT.")
                "set_brightness" -> ActionResult.Success(actionId, "Set screen brightness to 20%.")
                "create_timer" -> ActionResult.Success(actionId, "Timer set for 2700 seconds (45 minutes).")
                else -> ActionResult.UnsupportedAction(actionId)
            }
        }

        val plan = TaskPlan(
            userGoal = "Start study mode: Turn on DND, set ringer to silent, dim screen, and set a 45-minute timer",
            steps = listOf(
                TaskStep("s1", "set_dnd_mode", "Enable DND", JSONObject("""{"enabled":true}""")),
                TaskStep("s2", "set_ringer_mode", "Set ringer silent", JSONObject("""{"mode":"silent"}"""), dependsOn = listOf("s1")),
                TaskStep("s3", "set_brightness", "Dim brightness", JSONObject("""{"percentage":20}"""), dependsOn = listOf("s2")),
                TaskStep("s4", "create_timer", "Set 45-min timer", JSONObject("""{"duration_seconds":2700}"""), dependsOn = listOf("s3"))
            )
        )

        val status = scheduler.executePlan(plan)
        assertEquals(PlanStatus.COMPLETED, status)
        assertEquals(PlanStatus.COMPLETED, plan.overallStatus)
        assertEquals(4, executedActions.size)
        assertEquals(listOf("set_dnd_mode", "set_ringer_mode", "set_brightness", "create_timer"), executedActions)
        assertTrue(plan.generateSummary().contains("4 actions", ignoreCase = true) ||
                   plan.generateSummary().contains("Successfully", ignoreCase = true))
    }

    // =========================================================================
    // TASK B: Night Mode
    // Flashlight off, screen brightness to reading/min, alarms checked, DND enabled
    // =========================================================================
    @Test
    fun testTaskB_NightMode_AcyclicWorkflowWithAlarmsAndDnd() = runBlocking {
        val executedActions = mutableListOf<String>()

        val scheduler = createScheduler { actionId, _ ->
            executedActions.add(actionId)
            when (actionId) {
                "turn_off_flashlight" -> ActionResult.Success(actionId, "Flashlight is off.")
                "set_brightness" -> ActionResult.Success(actionId, "Brightness set to 15%.")
                "create_alarm" -> ActionResult.Success(actionId, "Alarm set for 7:00 AM.")
                "set_dnd_mode" -> ActionResult.Success(actionId, "DND turned ON.")
                else -> ActionResult.UnsupportedAction(actionId)
            }
        }

        val plan = TaskPlan(
            userGoal = "Prepare night mode: turn off flashlight, dim screen, set alarm, enable DND",
            steps = listOf(
                TaskStep("b1", "turn_off_flashlight", "Turn off flashlight"),
                TaskStep("b2", "set_brightness", "Set reading brightness", JSONObject("""{"percentage":15}""")),
                TaskStep("b3", "create_alarm", "Set wake-up alarm", JSONObject("""{"hour":7,"minutes":0}""")),
                TaskStep("b4", "set_dnd_mode", "Enable DND", JSONObject("""{"enabled":true}"""), dependsOn = listOf("b1", "b2", "b3"))
            )
        )

        val status = scheduler.executePlan(plan)
        assertEquals(PlanStatus.COMPLETED, status)
        assertTrue(executedActions.contains("turn_off_flashlight"))
        assertTrue(executedActions.contains("set_brightness"))
        assertTrue(executedActions.contains("create_alarm"))
        assertTrue(executedActions.contains("set_dnd_mode"))
    }

    // =========================================================================
    // TASK C: Morning Routine
    // Battery queried, time/date checked, calendar opened, volume set to 60%
    // =========================================================================
    @Test
    fun testTaskC_MorningRoutine_StatusQueriesAndVolume() = runBlocking {
        val executedActions = mutableListOf<String>()

        val scheduler = createScheduler { actionId, _ ->
            executedActions.add(actionId)
            when (actionId) {
                "get_battery_status" -> ActionResult.Success(actionId, "Battery level is currently 85%.")
                "check_current_time_date" -> ActionResult.Success(actionId, "Current time: Saturday, 8:00 AM.")
                "open_calendar" -> ActionResult.Success(actionId, "Opened Calendar.")
                "set_volume" -> ActionResult.Success(actionId, "Set Media volume to 60%.")
                else -> ActionResult.UnsupportedAction(actionId)
            }
        }

        val plan = TaskPlan(
            userGoal = "Good morning routine: Check battery and time, open calendar, and set volume to 60%",
            steps = listOf(
                TaskStep("c1", "get_battery_status", "Check battery"),
                TaskStep("c2", "check_current_time_date", "Check time"),
                TaskStep("c3", "open_calendar", "Open calendar", dependsOn = listOf("c1", "c2")),
                TaskStep("c4", "set_volume", "Set volume", JSONObject("""{"stream":"media","percentage":60}"""))
            )
        )

        val status = scheduler.executePlan(plan)
        assertEquals(PlanStatus.COMPLETED, status)
        assertEquals(4, plan.steps.count { it.status == StepStatus.SUCCESS })
    }

    // =========================================================================
    // TASK D: App Install Workflow
    // Play Store search, listing, installation requested with truthful limitations
    // =========================================================================
    @Test
    fun testTaskD_AppInstallWorkflow_TruthfulReporting() = runBlocking {
        val executedActions = mutableListOf<String>()

        val scheduler = createScheduler { actionId, _ ->
            executedActions.add(actionId)
            when (actionId) {
                "open_play_store_search" -> ActionResult.Success(actionId, "Opened Google Play Store search for “Spotify”.")
                "request_app_install" -> ActionResult.Success(actionId, "Opened Google Play Store to install “Spotify”. Please confirm installation in the Play Store.")
                else -> ActionResult.UnsupportedAction(actionId)
            }
        }

        val plan = TaskPlan(
            userGoal = "Install Spotify from Google Play Store",
            steps = listOf(
                TaskStep("d1", "open_play_store_search", "Search Spotify in Play Store", JSONObject("""{"query":"Spotify"}""")),
                TaskStep("d2", "request_app_install", "Request Spotify install", JSONObject("""{"app_name":"Spotify"}"""), dependsOn = listOf("d1"))
            )
        )

        val status = scheduler.executePlan(plan)
        assertEquals(PlanStatus.COMPLETED, status)
        assertEquals(2, executedActions.size)
        // Verify truthful reporting message
        val step2Result = plan.steps.first { it.stepId == "d2" }.executionResult?.userFacingMessage().orEmpty()
        assertTrue("Must truthfully mention user confirmation in Play Store UI",
            step2Result.contains("Play Store", ignoreCase = true))
    }

    // =========================================================================
    // TASK E: Dual-App Split Screen
    // Primary app launched, split-screen toggled, secondary app adjacent
    // =========================================================================
    @Test
    fun testTaskE_DualAppSplitScreen_WithAccessibility() = runBlocking {
        AssistantAccessibilityService.isServiceRunningForTest = true

        val executedActions = mutableListOf<String>()
        val scheduler = createScheduler { actionId, _ ->
            executedActions.add(actionId)
            when (actionId) {
                "open_split_screen" -> ActionResult.Success(actionId, "Opened WhatsApp and YouTube in split screen mode.")
                else -> ActionResult.UnsupportedAction(actionId)
            }
        }

        val plan = TaskPlan(
            userGoal = "Open WhatsApp and YouTube in split screen",
            steps = listOf(
                TaskStep("e1", "open_split_screen", "Open split screen", JSONObject("""{"primary_app":"WhatsApp","secondary_app":"YouTube"}"""))
            )
        )

        val status = scheduler.executePlan(plan)
        assertEquals(PlanStatus.COMPLETED, status)
        assertEquals(listOf("open_split_screen"), executedActions)
    }

    @Test
    fun testTaskE_DualAppSplitScreen_WithoutAccessibility_GracefulError() = runBlocking {
        AssistantAccessibilityService.isServiceRunningForTest = false

        val registry = ActionRegistry(MockContext())
        val result = registry.execute("open_split_screen", JSONObject("""{"primary_app":"WhatsApp","secondary_app":"YouTube"}"""))
        assertTrue("Must return MultiWindowUnavailable when accessibility is off", result is ActionResult.MultiWindowUnavailable)
        assertEquals(AssistantFailureCategory.MULTI_WINDOW_UNAVAILABLE, result.failureCategory)
    }

    // =========================================================================
    // TASK F: Lock Phone Request
    // Screen locked via AssistantAccessibilityService.lockScreen()
    // =========================================================================
    @Test
    fun testTaskF_LockPhone_WithAccessibilityService() = runBlocking {
        AssistantAccessibilityService.isServiceRunningForTest = true

        val executedActions = mutableListOf<String>()
        val scheduler = createScheduler { actionId, _ ->
            executedActions.add(actionId)
            when (actionId) {
                "lock_screen" -> ActionResult.Success(actionId, "Device screen locked.")
                else -> ActionResult.UnsupportedAction(actionId)
            }
        }

        val plan = TaskPlan(
            userGoal = "Lock phone screen",
            steps = listOf(
                TaskStep("f1", "lock_screen", "Lock screen")
            )
        )

        val status = scheduler.executePlan(plan)
        assertEquals(PlanStatus.COMPLETED, status)
        assertEquals(listOf("lock_screen"), executedActions)
    }

    @Test
    fun testTaskF_LockPhone_WithoutAccessibility_ReturnsTypedError() = runBlocking {
        AssistantAccessibilityService.isServiceRunningForTest = false

        val registry = ActionRegistry(MockContext())
        val result = registry.execute("lock_screen", JSONObject())
        assertTrue("Must be AccessibilityNotEnabled", result is ActionResult.AccessibilityNotEnabled)
        assertEquals(AssistantFailureCategory.ACCESSIBILITY_NOT_ENABLED, result.failureCategory)
    }

    // =========================================================================
    // TASK G: Bluetooth App Launch
    // Bluetooth state checked, enabled via system request if off, app launched
    // =========================================================================
    @Test
    fun testTaskG_BluetoothAppLaunch_StateCheckAndLaunch() = runBlocking {
        val executedActions = mutableListOf<String>()

        val scheduler = createScheduler { actionId, _ ->
            executedActions.add(actionId)
            when (actionId) {
                "get_bluetooth_state" -> ActionResult.Success(actionId, "Bluetooth is currently disabled (OFF).")
                "request_bluetooth_enable" -> ActionResult.Success(actionId, "Requested Bluetooth activation. Please allow the system prompt.")
                "open_app" -> ActionResult.Success(actionId, "Opened Spotify.")
                else -> ActionResult.UnsupportedAction(actionId)
            }
        }

        val plan = TaskPlan(
            userGoal = "Check bluetooth, turn it on, and open Spotify",
            steps = listOf(
                TaskStep("g1", "get_bluetooth_state", "Check Bluetooth"),
                TaskStep("g2", "request_bluetooth_enable", "Enable Bluetooth", dependsOn = listOf("g1")),
                TaskStep("g3", "open_app", "Launch Spotify", JSONObject("""{"app_name":"Spotify"}"""), dependsOn = listOf("g2"))
            )
        )

        val status = scheduler.executePlan(plan)
        assertEquals(PlanStatus.COMPLETED, status)
        assertEquals(listOf("get_bluetooth_state", "request_bluetooth_enable", "open_app"), executedActions)
    }

    // =========================================================================
    // TASK H: Communication Review (Confirmation Gates)
    // Sensitive action requires user confirmation before execution
    // =========================================================================
    @Test
    fun testTaskH_CommunicationReview_RequiresConfirmation() {
        val registry = ActionRegistry(MockContext())

        // Create contact is sensitive
        val contact = registry.prepare("create_contact", JSONObject("""{"first_name":"Alice","phone_number":"+919876543210"}"""))
        assertNotNull(contact)
        assertTrue("create_contact must require confirmation", contact!!.requiresConfirmation)

        // Send email is sensitive
        val email = registry.prepare("send_email", JSONObject("""{"to":"test@example.com","subject":"Hi","body":"Hello"}"""))
        assertNotNull(email)
        assertTrue("send_email must require confirmation", email!!.requiresConfirmation)

        // Compose SMS is sensitive
        val sms = registry.prepare("compose_sms", JSONObject("""{"phone_number":"+919876543210","message":"Test message"}"""))
        assertNotNull(sms)
        assertTrue("compose_sms must require confirmation", sms!!.requiresConfirmation)

        // Dial phone number is sensitive
        val dial = registry.prepare("dial_phone_number", JSONObject("""{"phone_number":"+919876543210"}"""))
        assertNotNull(dial)
        assertTrue("dial_phone_number must require confirmation", dial!!.requiresConfirmation)
    }

    // =========================================================================
    // TASK I: Context-Aware Follow-up (Antecedent Resolution & Clean Isolation)
    // Multi-turn without cross-request contamination
    // =========================================================================
    @Test
    fun testTaskI_ContextAwareFollowUp_NoCrossRequestContamination() {
        val registry = ActionRegistry(MockContext())
        val engine = IntentEngine(registry.capabilityRegistry)

        // Turn 1: User says "do not turn on bluetooth ; open its settings"
        val turn1 = engine.parse("do not turn on bluetooth ; open its settings", "req-1")
        assertEquals("open_bluetooth_settings", turn1.canonicalToolName)
        assertNotNull(turn1.negation)
        assertTrue(turn1.negation != null && turn1.negation!!.hasNegation)
        assertEquals("bluetooth", turn1.negation!!.negatedTarget)

        // Turn 2: User says "what is my battery level?"
        // Should have zero contamination from turn 1's bluetooth negation!
        val turn2 = engine.parse("what is my battery level?", "req-2")
        assertEquals("get_battery_status", turn2.canonicalToolName)
        assertNull("Turn 2 must not retain turn 1 negation", turn2.negation)
        assertEquals(1.0f, turn2.confidence, 0.01f)
    }

    // =========================================================================
    // TASK J: Recovery After Failure
    // Intentional failure triggers typed failure category, logs error, recovers
    // =========================================================================
    @Test
    fun testTaskJ_RecoveryAfterFailure_RollbackAndAlternativeStep() = runBlocking {
        val scheduler = createScheduler { actionId, args ->
            when (actionId) {
                "open_app" -> {
                    val app = args.optString("app_name")
                    if (app == "NonExistentApp") {
                        ActionResult.AppNotFound(app)
                    } else {
                        ActionResult.Success(actionId, "Opened $app.")
                    }
                }
                "open_play_store_search" -> ActionResult.Success(actionId, "Opened Play Store to search for missing app.")
                else -> ActionResult.UnsupportedAction(actionId)
            }
        }

        // Plan with a step that will fail, followed by a dependent step
        val step1 = TaskStep("j1", "open_app", "Launch missing app", JSONObject("""{"app_name":"NonExistentApp"}"""))
        val stepFallback = TaskStep(
            "j2",
            "open_play_store_search",
            "Search missing app in Play Store",
            JSONObject("""{"query":"NonExistentApp"}"""),
            dependsOn = listOf("j1")
        )

        val plan = TaskPlan(
            userGoal = "Try launching app, or find it in Play Store if missing",
            steps = listOf(step1, stepFallback)
        )

        val status = scheduler.executePlan(plan)
        // Step 1 fails with APP_NOT_INSTALLED
        assertEquals(StepStatus.FAILED, plan.steps.first { it.stepId == "j1" }.status)
        // Step 2 is skipped because dependent step 1 failed
        assertEquals(StepStatus.SKIPPED, plan.steps.first { it.stepId == "j2" }.status)
        assertEquals(PlanStatus.FAILED, status)

        // Verify truthful reporting of failure reason
        val failureStep = plan.steps.first { it.stepId == "j1" }
        val reason = failureStep.executionResult?.userFacingMessage().orEmpty()
        assertTrue("Failure reason must mention App is not installed",
            reason.contains("not installed", ignoreCase = true) || reason.contains("cannot be launched", ignoreCase = true))
    }
}
