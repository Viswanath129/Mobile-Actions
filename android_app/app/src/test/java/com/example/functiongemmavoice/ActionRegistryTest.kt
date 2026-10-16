package com.example.functiongemmavoice

import com.example.functiongemmavoice.runtime.ActionRegistry
import com.example.functiongemmavoice.runtime.AssistantException
import com.example.functiongemmavoice.runtime.AssistantRuntime
import com.example.functiongemmavoice.runtime.ContextBudgetStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActionRegistryTest {

    private class MockContext : android.content.ContextWrapper(null)

    @Test
    fun testValidOpenUrlHttps() {
        val registry = ActionRegistry(MockContext())
        val args = JSONObject("""{"url":"https://developer.android.com"}""")
        val prepared = registry.prepare("open_url", args)
        assertNotNull(prepared)
        assertEquals("open_url", prepared?.name)
        assertFalse(prepared!!.requiresConfirmation)
    }

    @Test
    fun testRejectInsecureOrMaliciousUrl() {
        val registry = ActionRegistry(MockContext())
        val fileUrl = registry.prepare("open_url", JSONObject("""{"url":"file:///sdcard/private.txt"}"""))
        assertNull(fileUrl)

        val jsUrl = registry.prepare("open_url", JSONObject("""{"url":"javascript:alert(1)"}"""))
        assertNull(jsUrl)

        val httpUrl = registry.prepare("open_url", JSONObject("""{"url":"http://insecure.site"}"""))
        assertNull(httpUrl)
    }

    @Test
    fun testSensitiveActionsRequireConfirmation() {
        val registry = ActionRegistry(MockContext())
        val contactArgs = JSONObject("""{"first_name":"Alice","phone_number":"+919876543210"}""")
        val preparedContact = registry.prepare("create_contact", contactArgs)
        assertNotNull(preparedContact)
        assertTrue(preparedContact!!.requiresConfirmation)

        val emailArgs = JSONObject("""{"to":"alice@example.com","subject":"Test","body":"Hello"}""")
        val preparedEmail = registry.prepare("send_email", emailArgs)
        assertNotNull(preparedEmail)
        assertTrue(preparedEmail!!.requiresConfirmation)
    }

    @Test
    fun testInvalidPhoneNumberRejection() {
        val registry = ActionRegistry(MockContext())
        val dialArgs = JSONObject("""{"phone_number":"invalid-letters-not-phone"}""")
        val prepared = registry.prepare("dial_phone_number", dialArgs)
        assertNull(prepared)
    }

    @Test
    fun testUnknownToolRejection() {
        val registry = ActionRegistry(MockContext())
        val prepared = registry.prepare("non_existent_dangerous_tool", JSONObject())
        assertNull(prepared)
    }

    @Test
    fun testTokenEstimationAccuracy() {
        val runtime = AssistantRuntime(MockContext())
        val englishPrompt = "Turn on the flashlight"
        val estimatedEnglish = runtime.estimateTokens(englishPrompt)
        assertTrue("English token estimate should be reasonable", estimatedEnglish in 4..12)

        val teluguPrompt = "నా ఫోన్ బ్యాటరీ ఎంత?"
        val estimatedTelugu = runtime.estimateTokens(teluguPrompt)
        assertTrue("Telugu token estimate should be positive", estimatedTelugu in 5..20)
    }

    @Test
    fun testContextBudgetCalculation() {
        val budget = ContextBudgetStatus(
            maxTokens = 4096,
            estimatedSystemTokens = 120,
            estimatedSchemaTokens = 2400,
            estimatedHistoryTokens = 300,
            safetyBuffer = 500
        )
        assertEquals(776, budget.remainingBudget)
        assertTrue(budget.remainingBudget > 0)
    }

    @Test
    fun testAssistantExceptionFormatting() {
        val ex = AssistantException.ContextBudgetExceeded(4096, 5906)
        assertTrue(ex.userFacingMessage.contains("context limit"))
        assertTrue(ex.technicalDetails.contains("5906"))
    }

    @Test
    fun testCreateTimerDurationNormalization() {
        val registry = ActionRegistry(MockContext())

        // 1. Integer duration_seconds
        val args1 = JSONObject("""{"duration_seconds":300}""")
        val p1 = registry.prepare("create_timer", args1)
        assertNotNull(p1)
        assertEquals(300, p1!!.args.getInt("duration_seconds"))

        // 2. Integer minutes
        val args2 = JSONObject("""{"minutes":5}""")
        val p2 = registry.prepare("create_timer", args2)
        assertNotNull(p2)
        assertEquals(300, p2!!.args.getInt("duration_seconds"))

        // 3. String duration "5 minutes"
        val args3 = JSONObject("""{"duration":"5 minutes"}""")
        val p3 = registry.prepare("create_timer", args3)
        assertNotNull(p3)
        assertEquals(300, p3!!.args.getInt("duration_seconds"))

        // 4. Default fallback when no duration specified
        val args4 = JSONObject()
        val p4 = registry.prepare("create_timer", args4)
        assertNotNull(p4)
        assertEquals(300, p4!!.args.getInt("duration_seconds"))

        // 5. Clamping check
        val args5 = JSONObject("""{"duration_seconds":999999}""")
        val p5 = registry.prepare("create_timer", args5)
        assertNotNull(p5)
        assertEquals(86400, p5!!.args.getInt("duration_seconds"))
    }

    @Test
    fun testCreateAlarmTimeParsing() {
        val registry = ActionRegistry(MockContext())

        // 1. 24-hr format "07:30"
        val args1 = JSONObject("""{"time":"07:30"}""")
        val p1 = registry.prepare("create_alarm", args1)
        assertNotNull(p1)
        assertEquals(7, p1!!.args.getInt("hour"))
        assertEquals(30, p1.args.getInt("minutes"))

        // 2. 12-hr format "7:00 AM"
        val args2 = JSONObject("""{"time":"7:00 AM"}""")
        val p2 = registry.prepare("create_alarm", args2)
        assertNotNull(p2)
        assertEquals(7, p2!!.args.getInt("hour"))
        assertEquals(0, p2.args.getInt("minutes"))

        // 3. 12-hr format "7:30 PM"
        val args3 = JSONObject("""{"time":"7:30 PM"}""")
        val p3 = registry.prepare("create_alarm", args3)
        assertNotNull(p3)
        assertEquals(19, p3!!.args.getInt("hour"))
        assertEquals(30, p3.args.getInt("minutes"))

        // 4. 12-hr short format "7 PM"
        val args4 = JSONObject("""{"time":"7 PM"}""")
        val p4 = registry.prepare("create_alarm", args4)
        assertNotNull(p4)
        assertEquals(19, p4!!.args.getInt("hour"))
        assertEquals(0, p4.args.getInt("minutes"))

        // 5. Explicit hour and minutes
        val args5 = JSONObject("""{"hour":6,"minutes":45}""")
        val p5 = registry.prepare("create_alarm", args5)
        assertNotNull(p5)
        assertEquals(6, p5!!.args.getInt("hour"))
        assertEquals(45, p5.args.getInt("minutes"))

        // 6. Invalid time
        val args6 = JSONObject("""{"time":"invalid"}""")
        val p6 = registry.prepare("create_alarm", args6)
        assertNull(p6)
    }

    @Test
    fun testOpenAppAndUrlNormalization() {
        val registry = ActionRegistry(MockContext())

        // Open app
        val appArgs = JSONObject("""{"app_name":"YouTube"}""")
        val appAction = registry.prepare("open_app", appArgs)
        assertNotNull(appAction)
        assertEquals("YouTube", appAction!!.args.getString("app_name"))

        // URL auto-prefixing
        val urlArgs = JSONObject("""{"url":"youtube.com"}""")
        val urlAction = registry.prepare("open_url", urlArgs)
        assertNotNull(urlAction)
        assertEquals("https://youtube.com", urlAction!!.args.getString("url"))

        // Explicit HTTPS URL
        val httpsArgs = JSONObject("""{"url":"https://youtube.com"}""")
        val httpsAction = registry.prepare("open_url", httpsArgs)
        assertNotNull(httpsAction)
        assertEquals("https://youtube.com", httpsAction!!.args.getString("url"))
    }

    @Test
    fun testCoreSensorsAndSettingsTools() {
        val registry = ActionRegistry(MockContext())

        assertNotNull(registry.prepare("turn_on_flashlight", JSONObject()))
        assertNotNull(registry.prepare("turn_off_flashlight", JSONObject()))
        assertNotNull(registry.prepare("get_battery_status", JSONObject()))
        assertNotNull(registry.prepare("open_wifi_settings", JSONObject()))
        assertNotNull(registry.prepare("check_current_time_date", JSONObject()))
    }

    @Test
    fun testActionResultMessages() {
        val success = com.example.functiongemmavoice.runtime.ActionResult.Success("turn_on_flashlight", "Flashlight is on.")
        assertEquals("Flashlight is on.", success.userFacingMessage())

        val failed = com.example.functiongemmavoice.runtime.ActionResult.ExecutionFailed("turn_on_flashlight", "Hardware unavailable")
        assertTrue(failed.userFacingMessage().contains("Hardware unavailable"))

        val denied = com.example.functiongemmavoice.runtime.ActionResult.PermissionDenied("turn_on_flashlight", "android.permission.CAMERA")
        assertTrue(denied.userFacingMessage().contains("android.permission.CAMERA"))
    }

    @Test
    fun testToolIndependenceFromTimerValidator() {
        val registry = ActionRegistry(MockContext())

        // Ensure battery tool does NOT require duration_seconds or time
        val batteryAction = registry.prepare("get_battery_status", JSONObject())
        assertNotNull(batteryAction)
        assertFalse(batteryAction!!.args.has("duration_seconds"))
        assertFalse(batteryAction.args.has("time"))

        // Ensure flashlight tool does NOT require duration_seconds or time
        val flashAction = registry.prepare("turn_on_flashlight", JSONObject())
        assertNotNull(flashAction)
        assertFalse(flashAction!!.args.has("duration_seconds"))
        assertFalse(flashAction.args.has("time"))

        // Ensure Wi-Fi tool does NOT require duration_seconds or time
        val wifiAction = registry.prepare("open_wifi_settings", JSONObject())
        assertNotNull(wifiAction)
        assertFalse(wifiAction!!.args.has("duration_seconds"))
        assertFalse(wifiAction.args.has("time"))
    }

    @Test
    fun testParseArgsWithUnquotedAndEscapeTags() {
        val registry = ActionRegistry(MockContext())
        val parsed = registry.parseArgs("{app_name:<escape>YouTube<escape>}")
        assertEquals("YouTube", parsed.optString("app_name"))

        val parsedWithNumbers = registry.parseArgs("{duration_seconds:300}")
        assertEquals(300, parsedWithNumbers.optInt("duration_seconds"))
    }

    @Test
    fun testBluetoothPairingToolValidation() {
        val registry = ActionRegistry(MockContext())
        val action = registry.prepare("open_bluetooth_pairing", JSONObject())
        assertNotNull(action)
        assertEquals("open_bluetooth_pairing", action?.name)
        assertFalse(action!!.requiresConfirmation)
    }

    @Test
    fun testTokenEstimationMultilingual() {
        val manager = com.example.functiongemmavoice.runtime.InferenceEngineManager.getInstance(MockContext())
        val englishTokens = manager.estimateTokens("Turn on the flashlight")
        assertTrue(englishTokens in 4..12)

        val teluguTokens = manager.estimateTokens("నా ఫోన్ బ్యాటరీ ఎంత?")
        assertTrue(teluguTokens in 5..20)
    }

    @Test
    fun testDeterministicFastPathBatteryNeverReturnsFlashlight() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val queries = listOf(
            "What is my battery level?",
            "battery status",
            "check battery",
            "how much battery is left",
            "బ్యాటరీ ఎంత ఉంది"
        )
        for (q in queries) {
            val decision = router.route(q)
            assertTrue("Query '$q' should resolve to DirectAction", decision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
            val actions = (decision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions
            assertEquals(1, actions.size)
            assertEquals("get_battery_status", actions[0].name)
            assertNotEquals("turn_off_flashlight", actions[0].name)
            assertNotEquals("turn_on_flashlight", actions[0].name)
        }
    }

    @Test
    fun testDeterministicFastPathFlashlightNeverTriggersTimer() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val decision = router.route("Turn on the flashlight")
        assertTrue(decision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        val actions = (decision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions
        assertEquals(1, actions.size)
        assertEquals("turn_on_flashlight", actions[0].name)
        assertFalse(actions[0].args.has("duration_seconds"))
    }

    @Test
    fun testDeterministicFastPathNegationRejection() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val negatedFlashlight = router.route("Do not turn on the flashlight")
        assertTrue(negatedFlashlight is com.example.functiongemmavoice.runtime.FastPathDecision.Negated)

        val dontFlash = router.route("Don't turn on flashlight")
        assertTrue(dontFlash is com.example.functiongemmavoice.runtime.FastPathDecision.Negated)

        val teluguNegated = router.route("లైట్ ఆన్ చేయవద్దు")
        assertTrue(teluguNegated is com.example.functiongemmavoice.runtime.FastPathDecision.Negated)
    }

    @Test
    fun testDeterministicFastPathInterrogativeFallback() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val whyQuery = router.route("Why should I turn on the flashlight?")
        assertTrue("Interrogative query should fall back to model", whyQuery is com.example.functiongemmavoice.runtime.FastPathDecision.FallbackToModel)

        val howQuery = router.route("How to turn on flashlight")
        assertTrue("How-to query should fall back to model", howQuery is com.example.functiongemmavoice.runtime.FastPathDecision.FallbackToModel)
    }

    @Test
    fun testDeterministicFastPathAmbiguousTimer() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val ambiguous = router.route("Set a timer")
        assertTrue("Timer without duration should be ambiguous", ambiguous is com.example.functiongemmavoice.runtime.FastPathDecision.Ambiguous)
        val msg = (ambiguous as com.example.functiongemmavoice.runtime.FastPathDecision.Ambiguous).clarificationQuestion
        assertTrue(msg.contains("specify"))

        val specific = router.route("Set a timer for 5 minutes")
        assertTrue(specific is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        val action = (specific as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0]
        assertEquals("create_timer", action.name)
        assertEquals(300, action.args.getInt("duration_seconds"))
    }

    @Test
    fun testDeterministicFastPathUnsupportedService() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val spotify = router.route("Play Taylor Swift on Spotify")
        assertTrue(spotify is com.example.functiongemmavoice.runtime.FastPathDecision.Unsupported)
        assertTrue((spotify as com.example.functiongemmavoice.runtime.FastPathDecision.Unsupported).reason.contains("Spotify"))
    }

    @Test
    fun testDeterministicFastPathCompoundCommand() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val compound = router.route("Turn on the flashlight and open YouTube")
        assertTrue(compound is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        val actions = (compound as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions
        assertEquals(2, actions.size)
        assertEquals("turn_on_flashlight", actions[0].name)
        assertEquals("open_app", actions[1].name)
        assertEquals("YouTube", actions[1].args.getString("app_name"))
    }

    @Test
    fun testRequestContextLegalTransitions() {
        val req = com.example.functiongemmavoice.runtime.RequestContext(rawInput = "Turn on the flashlight")
        assertEquals(com.example.functiongemmavoice.runtime.RequestState.RECEIVED, req.state)

        req.transitionTo(com.example.functiongemmavoice.runtime.RequestState.NORMALIZING)
        assertEquals(com.example.functiongemmavoice.runtime.RequestState.NORMALIZING, req.state)

        req.transitionTo(com.example.functiongemmavoice.runtime.RequestState.ROUTING)
        assertEquals(com.example.functiongemmavoice.runtime.RequestState.ROUTING, req.state)

        req.transitionTo(com.example.functiongemmavoice.runtime.RequestState.FAST_PATH)
        assertEquals(com.example.functiongemmavoice.runtime.RequestState.FAST_PATH, req.state)
        assertTrue(req.timings.isFastPath)

        req.transitionTo(com.example.functiongemmavoice.runtime.RequestState.VALIDATING)
        req.transitionTo(com.example.functiongemmavoice.runtime.RequestState.EXECUTING)
        req.transitionTo(com.example.functiongemmavoice.runtime.RequestState.VERIFYING)
        req.markCompleted()

        assertEquals(com.example.functiongemmavoice.runtime.RequestState.COMPLETED, req.state)
        assertTrue(req.state.isTerminal())
    }

    @Test(expected = com.example.functiongemmavoice.runtime.IllegalStateTransitionException::class)
    fun testRequestContextIllegalTransitionThrows() {
        val req = com.example.functiongemmavoice.runtime.RequestContext(rawInput = "Turn on flashlight")
        req.transitionTo(com.example.functiongemmavoice.runtime.RequestState.NORMALIZING)
        // Direct transition from NORMALIZING to EXECUTING is illegal and must throw
        req.transitionTo(com.example.functiongemmavoice.runtime.RequestState.EXECUTING)
    }

    @Test
    fun testRequestContextCancellationIsolation() {
        val reqA = com.example.functiongemmavoice.runtime.RequestContext(rawInput = "Query A")
        val reqB = com.example.functiongemmavoice.runtime.RequestContext(rawInput = "Query B")

        assertNotEquals(reqA.requestId, reqB.requestId)

        reqA.cancel("User aborted")
        assertTrue(reqA.isCancelled)
        assertEquals(com.example.functiongemmavoice.runtime.RequestState.CANCELLED, reqA.state)
        assertFalse(reqB.isCancelled)
        assertEquals(com.example.functiongemmavoice.runtime.RequestState.RECEIVED, reqB.state)
    }

    @Test
    fun testAssistantErrorTaxonomy() {
        val errors = listOf(
            com.example.functiongemmavoice.runtime.AssistantError.InvalidInput("bad input"),
            com.example.functiongemmavoice.runtime.AssistantError.UnsupportedIntent("music"),
            com.example.functiongemmavoice.runtime.AssistantError.UnknownTool("fake_tool"),
            com.example.functiongemmavoice.runtime.AssistantError.PermissionDenied("camera", "android.permission.CAMERA"),
            com.example.functiongemmavoice.runtime.AssistantError.ContextLimitExceeded(4096, 5000),
            com.example.functiongemmavoice.runtime.AssistantError.ResultCorrelationFailure("id1", "id2")
        )

        for (err in errors) {
            assertNotNull(err.code)
            assertTrue(err.userFacingMessage.isNotBlank())
            assertTrue(err.technicalDetails.isNotBlank())
        }
    }

    @Test
    fun testNewSettingsToolsPreparation() {
        val registry = ActionRegistry(MockContext())

        val soundAction = registry.prepare("open_sound_settings", JSONObject())
        assertNotNull(soundAction)
        assertEquals("open_sound_settings", soundAction?.name)
        assertFalse(soundAction!!.requiresConfirmation)

        val displayAction = registry.prepare("open_display_settings", JSONObject())
        assertNotNull(displayAction)
        assertEquals("open_display_settings", displayAction?.name)

        val locationAction = registry.prepare("open_location_settings", JSONObject())
        assertNotNull(locationAction)
        assertEquals("open_location_settings", locationAction?.name)

        val dndAction = registry.prepare("open_dnd_settings", JSONObject())
        assertNotNull(dndAction)
        assertEquals("open_dnd_settings", dndAction?.name)

        val calendarAction = registry.prepare("open_calendar", JSONObject())
        assertNotNull(calendarAction)
        assertEquals("open_calendar", calendarAction?.name)

        val shareAction = registry.prepare("share_text", JSONObject("""{"text":"Hello from FunctionGemma"}"""))
        assertNotNull(shareAction)
        assertEquals("Hello from FunctionGemma", shareAction!!.args.getString("text"))
    }

    @Test
    fun testDeterministicRoutingForNewQuickActions() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val soundDecision = router.route("open sound settings")
        assertTrue(soundDecision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("open_sound_settings", (soundDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)

        val displayDecision = router.route("open display settings")
        assertTrue(displayDecision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("open_display_settings", (displayDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)

        val locationDecision = router.route("open location settings")
        assertTrue(locationDecision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("open_location_settings", (locationDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)

        val dndDecision = router.route("open do not disturb settings")
        assertTrue(dndDecision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("open_dnd_settings", (dndDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)

        val calendarDecision = router.route("open calendar")
        assertTrue(calendarDecision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("open_calendar", (calendarDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)
    }

    @Test
    fun testSpotifyAppLaunchVsPlaybackGuard() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        // "open spotify" should route to open_app(app_name="Spotify")
        val openSpotify = router.route("open spotify")
        assertTrue(openSpotify is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        val action = (openSpotify as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0]
        assertEquals("open_app", action.name)
        assertEquals("Spotify", action.args.getString("app_name"))

        // "play Taylor Swift on spotify" must be caught by unsupported playback guard
        val playMusic = router.route("play songs on spotify")
        assertTrue(playMusic is com.example.functiongemmavoice.runtime.FastPathDecision.Unsupported)
        assertTrue((playMusic as com.example.functiongemmavoice.runtime.FastPathDecision.Unsupported).reason.contains("playback"))
    }

    // --- MANDATORY TEST MATRIX (OBJECTIVE 11) ---

    @Test
    fun testReq1_OpenBluetoothRoutesToSettingsNavigation() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val enDecision = router.route("open bluetooth")
        assertTrue(enDecision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("open_bluetooth_settings", (enDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)

        val teDecision = router.route("బ్లూటూత్ ఓపెన్ చెయ్")
        assertTrue(teDecision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("open_bluetooth_settings", (teDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)
    }

    @Test
    fun testReq2_TurnOnBluetoothRoutesToActivationRequest() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val enDecision = router.route("turn on bluetooth")
        assertTrue(enDecision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("request_bluetooth_enable", (enDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)

        val switchOnDecision = router.route("switch bluetooth on")
        assertTrue(switchOnDecision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("request_bluetooth_enable", (switchOnDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)

        val teDecision = router.route("బ్లూటూత్ ఆన్ చెయ్")
        assertTrue(teDecision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("request_bluetooth_enable", (teDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)
    }

    @Test
    fun testReq3_TurnOffBluetoothExplainsRestrictionAndOpensSettings() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val enDecision = router.route("turn bluetooth off")
        assertTrue(enDecision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("request_bluetooth_disable", (enDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)

        // Verify execution never falsely claims Bluetooth was turned off directly
        val execResult = registry.execute("request_bluetooth_disable", JSONObject())
        assertTrue(execResult is com.example.functiongemmavoice.runtime.ActionResult.Success)
        assertTrue(execResult.userFacingMessage().contains("Android 13+ restricts"))
    }

    @Test
    fun testReq4_BluetoothStatusCheckDoesNotInvokeMutation() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val decision = router.route("is bluetooth enabled?")
        assertTrue(decision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        val action = (decision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0]
        assertEquals("get_bluetooth_state", action.name)
        assertNotEquals("request_bluetooth_enable", action.name)
        assertNotEquals("request_bluetooth_disable", action.name)
    }

    @Test
    fun testReq5_BluetoothSettingsNavigationDoesNotInvokeActivation() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val decision = router.route("take me to bluetooth settings")
        assertTrue(decision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("open_bluetooth_settings", (decision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)
    }

    @Test
    fun testReq6_ExplicitDoNotTurnOnInstructionIsRespected() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        // "Do not turn on Bluetooth; open its settings"
        val decision = router.route("do not turn on bluetooth ; open its settings")
        assertTrue(decision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        val action = (decision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0]
        assertEquals("open_bluetooth_settings", action.name)
        assertNotEquals("request_bluetooth_enable", action.name)

        // "Open bluetooth settings, but do not change anything"
        val noChange = router.route("open bluetooth settings ; do not change anything")
        assertTrue(noChange is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("open_bluetooth_settings", (noChange as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)
    }

    @Test
    fun testReq7_BatteryQueryCannotInvokeFlashlight() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val batteryDecision = router.route("what is my battery level?")
        assertTrue(batteryDecision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        val action = (batteryDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0]
        assertEquals("get_battery_status", action.name)
        assertNotEquals("turn_on_flashlight", action.name)
        assertNotEquals("toggle_flashlight", action.name)
    }

    @Test
    fun testReq8_FlashlightCommandCannotInvokeTimer() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val flashDecision = router.route("turn on the flashlight")
        assertTrue(flashDecision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        val action = (flashDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0]
        assertEquals("turn_on_flashlight", action.name)
        assertNotEquals("create_timer", action.name)
        assertFalse(action.args.has("duration_seconds"))
    }

    @Test
    fun testReq9_WifiAndBluetoothDoNotExchangeResults() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val wifiDecision = router.route("open wifi settings")
        assertTrue(wifiDecision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("open_wifi_settings", (wifiDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)

        val btDecision = router.route("open bluetooth settings")
        assertTrue(btDecision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("open_bluetooth_settings", (btDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)
        assertNotEquals((wifiDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name,
            (btDecision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)
    }

    @Test
    fun testReq10_MultiActionIndependentTracking() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        // "turn on bluetooth and open youtube"
        val decision = router.route("turn on bluetooth and open youtube")
        assertTrue(decision is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        val actions = (decision as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions
        assertEquals(2, actions.size)
        assertEquals("request_bluetooth_enable", actions[0].name)
        assertEquals("open_app", actions[1].name)
        assertEquals("YouTube", actions[1].args.getString("app_name"))
    }

    @Test
    fun testReq11_CapabilityRegistryConsistencyCheck() {
        val registry = ActionRegistry(MockContext())
        val caps = registry.capabilityRegistry.all()
        assertTrue("At least 30 core tools registered", caps.size >= 30)

        // Verify Bluetooth definitions exist
        assertNotNull(registry.capabilityRegistry.get("open_bluetooth_settings"))
        assertNotNull(registry.capabilityRegistry.get("request_bluetooth_enable"))
        assertNotNull(registry.capabilityRegistry.get("request_bluetooth_disable"))
        assertNotNull(registry.capabilityRegistry.get("get_bluetooth_state"))
    }

    @Test
    fun testReq12_PureNegationFlashlightRejection() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val decision = router.route("do not turn on the flashlight")
        assertTrue(decision is com.example.functiongemmavoice.runtime.FastPathDecision.Negated)
    }

    @Test
    fun testReq13_TeluguVoiceVariantsForDeviceControls() {
        val registry = ActionRegistry(MockContext())
        val router = com.example.functiongemmavoice.runtime.DeterministicCommandRouter(registry)

        val flashTe = router.route("లైట్ ఆన్ చెయ్")
        assertTrue(flashTe is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("turn_on_flashlight", (flashTe as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)

        val flashOffTe = router.route("లైట్ ఆఫ్ చెయ్")
        assertTrue(flashOffTe is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("turn_off_flashlight", (flashOffTe as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)

        val batteryTe = router.route("నా ఫోన్ బ్యాటరీ ఎంత ఉంది")
        assertTrue(batteryTe is com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction)
        assertEquals("get_battery_status", (batteryTe as com.example.functiongemmavoice.runtime.FastPathDecision.DirectAction).actions[0].name)
    }
}

