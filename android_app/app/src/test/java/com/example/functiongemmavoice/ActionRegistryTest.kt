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
}
