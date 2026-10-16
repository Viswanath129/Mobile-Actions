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
}
