package com.example.functiongemmavoice

import android.content.ContextWrapper
import android.media.AudioManager
import com.example.functiongemmavoice.runtime.ActionRegistry
import com.example.functiongemmavoice.runtime.ActionResult
import com.example.functiongemmavoice.runtime.AssistantFailureCategory
import com.example.functiongemmavoice.runtime.accessibility.AssistantAccessibilityService
import com.example.functiongemmavoice.runtime.intent.CanonicalIntentId
import com.example.functiongemmavoice.runtime.intent.IntentEngine
import com.example.functiongemmavoice.runtime.laya.LocalLayaDecisionAdapter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceControlsTest {

    private class MockContext : ContextWrapper(null)

    // =========================================================================
    // 1. Audio Stream Isolation Tests
    // =========================================================================

    @Test
    fun testAudioStreamIsolation_DistinctStreamConstants() {
        // AudioManager stream constants must never be conflated
        val media = ActionRegistry.resolveAudioStream("media")
        val music = ActionRegistry.resolveAudioStream("music")
        val ring = ActionRegistry.resolveAudioStream("ring")
        val notification = ActionRegistry.resolveAudioStream("notification")
        val alarm = ActionRegistry.resolveAudioStream("alarm")
        val call = ActionRegistry.resolveAudioStream("call")

        assertEquals(AudioManager.STREAM_MUSIC, media.first)
        assertEquals(AudioManager.STREAM_MUSIC, music.first)
        assertEquals(AudioManager.STREAM_RING, ring.first)
        assertEquals(AudioManager.STREAM_NOTIFICATION, notification.first)
        assertEquals(AudioManager.STREAM_ALARM, alarm.first)
        assertEquals(AudioManager.STREAM_VOICE_CALL, call.first)

        // Ensure streams are isolated from each other
        assertNotEquals("Media and Ring must not be the same stream", media.first, ring.first)
        assertNotEquals("Ring and Notification must not be the same stream", ring.first, notification.first)
        assertNotEquals("Media and Alarm must not be the same stream", media.first, alarm.first)
        assertNotEquals("Call and Media must not be the same stream", call.first, media.first)
    }

    // =========================================================================
    // 2. ActionRegistry Prepare & Validation Tests
    // =========================================================================

    @Test
    fun testBrightnessPrepare_ValidPercentage() {
        val registry = ActionRegistry(MockContext())
        val valid = registry.prepare("set_brightness", JSONObject("""{"percentage": 75}"""))
        assertNotNull(valid)
        assertEquals("set_brightness", valid?.name)
        assertEquals(75, valid?.args?.getInt("percentage"))
        assertFalse(valid!!.requiresConfirmation)
    }

    @Test
    fun testBrightnessPrepare_ClampedOrRejectedInvalid() {
        val registry = ActionRegistry(MockContext())
        val negative = registry.prepare("set_brightness", JSONObject("""{"percentage": -10}"""))
        assertNull("Negative brightness must be rejected", negative)

        val excessive = registry.prepare("set_brightness", JSONObject("""{"percentage": 150}"""))
        assertNull("Brightness > 100% must be rejected", excessive)
    }

    @Test
    fun testVolumePrepare_ValidStreams() {
        val registry = ActionRegistry(MockContext())
        val prepMedia = registry.prepare("set_volume", JSONObject("""{"stream":"media","percentage":80}"""))
        assertNotNull(prepMedia)
        assertEquals("media", prepMedia?.args?.getString("stream"))
        assertEquals(80, prepMedia?.args?.getInt("percentage"))

        val prepRing = registry.prepare("set_volume", JSONObject("""{"stream":"ring","level":5}"""))
        assertNotNull(prepRing)
        assertEquals("ring", prepRing?.args?.getString("stream"))
    }

    @Test
    fun testDndAndRingerPrepare() {
        val registry = ActionRegistry(MockContext())
        val dndOn = registry.prepare("set_dnd_mode", JSONObject("""{"enabled": true, "filter": "priority"}"""))
        assertNotNull(dndOn)
        assertTrue(dndOn?.args?.getBoolean("enabled") == true)

        val ringerSilent = registry.prepare("set_ringer_mode", JSONObject("""{"mode": "silent"}"""))
        assertNotNull(ringerSilent)
        assertEquals("silent", ringerSilent?.args?.getString("mode"))

        val ringerVibrate = registry.prepare("set_ringer_mode", JSONObject("""{"mode": "vibrate"}"""))
        assertNotNull(ringerVibrate)
        assertEquals("vibrate", ringerVibrate?.args?.getString("mode"))

        val ringerInvalid = registry.prepare("set_ringer_mode", JSONObject("""{"mode": "party_mode"}"""))
        assertNull("Invalid ringer mode must be rejected", ringerInvalid)
    }

    @Test
    fun testLockScreenAndSplitScreenPrepare() {
        val registry = ActionRegistry(MockContext())
        val lock = registry.prepare("lock_screen", JSONObject())
        assertNotNull(lock)
        assertEquals("lock_screen", lock?.name)

        val split = registry.prepare("open_split_screen", JSONObject("""{"primary_app": "WhatsApp", "secondary_app": "YouTube"}"""))
        assertNotNull(split)
        assertEquals("open_split_screen", split?.name)
        assertEquals("WhatsApp", split?.args?.getString("primary_app"))
        assertEquals("YouTube", split?.args?.getString("secondary_app"))

        val floating = registry.prepare("open_floating_window", JSONObject("""{"app_name": "Calculator"}"""))
        assertNotNull(floating)
        assertEquals("open_floating_window", floating?.name)
    }

    // =========================================================================
    // 3. Execution Truthfulness & Platform Error Types
    // =========================================================================

    @Test
    fun testFloatingWindow_ReturnsUnsupportedActionCategory() {
        val registry = ActionRegistry(MockContext())
        val result = registry.execute("open_floating_window", JSONObject("""{"app_name": "Calculator"}"""))
        assertTrue("Must be FloatingWindowUnsupported", result is ActionResult.FloatingWindowUnsupported)
        assertEquals(AssistantFailureCategory.FLOATING_WINDOW_UNSUPPORTED, result.failureCategory)
        assertTrue(result.userFacingMessage().contains("Realme UI", ignoreCase = true) ||
                   result.userFacingMessage().contains("not supported", ignoreCase = true))
    }

    @Test
    fun testLockScreenWithoutAccessibility_ReturnsAccessibilityNotEnabled() {
        // When service is not running
        AssistantAccessibilityService.isServiceRunningForTest = false
        val registry = ActionRegistry(MockContext())
        val result = registry.execute("lock_screen", JSONObject())
        assertTrue("Must return AccessibilityNotEnabled when service is off", result is ActionResult.AccessibilityNotEnabled)
        assertEquals(AssistantFailureCategory.ACCESSIBILITY_NOT_ENABLED, result.failureCategory)
    }

    @Test
    fun testSplitScreenWithoutAccessibility_ReturnsMultiWindowUnavailable() {
        AssistantAccessibilityService.isServiceRunningForTest = false
        val registry = ActionRegistry(MockContext())
        val result = registry.execute("open_split_screen", JSONObject("""{"primary_app":"WhatsApp","secondary_app":"YouTube"}"""))
        assertTrue("Must return MultiWindowUnavailable when service is off", result is ActionResult.MultiWindowUnavailable)
        assertEquals(AssistantFailureCategory.MULTI_WINDOW_UNAVAILABLE, result.failureCategory)
    }

    // =========================================================================
    // 4. Intent Classification & Fast Routing Tests
    // =========================================================================

    @Test
    fun testIntentEngine_BrightnessCommands() {
        val registry = ActionRegistry(MockContext())
        val engine = IntentEngine(registry.capabilityRegistry)

        val check = engine.parse("what is my screen brightness?")
        assertEquals("get_brightness", check.canonicalToolName)
        assertEquals(CanonicalIntentId.GET_BRIGHTNESS, check.canonicalIntentId)

        val set50 = engine.parse("set brightness to 50%")
        assertEquals("set_brightness", set50.canonicalToolName)
        assertEquals(50, set50.parameters["percentage"])
        assertEquals(CanonicalIntentId.SET_BRIGHTNESS, set50.canonicalIntentId)

        val dim = engine.parse("dim screen")
        assertEquals("set_brightness", dim.canonicalToolName)
        assertEquals(15, dim.parameters["percentage"])

        val max = engine.parse("set brightness to max")
        assertEquals("set_brightness", max.canonicalToolName)
        assertEquals(100, max.parameters["percentage"])

        val raise = engine.parse("increase brightness")
        assertEquals("adjust_brightness", raise.canonicalToolName)
        assertEquals("up", raise.parameters["direction"])
    }

    @Test
    fun testIntentEngine_VolumeCommands() {
        val registry = ActionRegistry(MockContext())
        val engine = IntentEngine(registry.capabilityRegistry)

        val check = engine.parse("check volume")
        assertEquals("get_volume", check.canonicalToolName)
        assertEquals(CanonicalIntentId.GET_VOLUME, check.canonicalIntentId)

        val setMedia = engine.parse("set media volume to 80%")
        assertEquals("set_volume", setMedia.canonicalToolName)
        assertEquals(80, setMedia.parameters["percentage"])
        assertEquals("media", setMedia.parameters["stream"])

        val volUp = engine.parse("volume up")
        assertEquals("adjust_volume", volUp.canonicalToolName)
        assertEquals("up", volUp.parameters["direction"])

        val mute = engine.parse("mute volume")
        assertEquals("mute_stream", mute.canonicalToolName)
        assertEquals(CanonicalIntentId.MUTE_STREAM, mute.canonicalIntentId)

        val unmute = engine.parse("unmute media")
        assertEquals("unmute_stream", unmute.canonicalToolName)
        assertEquals(CanonicalIntentId.UNMUTE_STREAM, unmute.canonicalIntentId)
    }

    @Test
    fun testIntentEngine_DndAndRinger() {
        val registry = ActionRegistry(MockContext())
        val engine = IntentEngine(registry.capabilityRegistry)

        val dndOn = engine.parse("turn on do not disturb")
        assertEquals("set_dnd_mode", dndOn.canonicalToolName)
        assertEquals(true, dndOn.parameters["enabled"])
        assertEquals(CanonicalIntentId.SET_DND_MODE, dndOn.canonicalIntentId)

        val dndOff = engine.parse("disable dnd")
        assertEquals("set_dnd_mode", dndOff.canonicalToolName)
        assertEquals(false, dndOff.parameters["enabled"])

        val silent = engine.parse("set phone to silent mode")
        assertEquals("set_ringer_mode", silent.canonicalToolName)
        assertEquals("silent", silent.parameters["mode"])
        assertEquals(CanonicalIntentId.SET_RINGER_MODE, silent.canonicalIntentId)

        val vibrate = engine.parse("put phone on vibrate")
        assertEquals("set_ringer_mode", vibrate.canonicalToolName)
        assertEquals("vibrate", vibrate.parameters["mode"])
    }

    @Test
    fun testLayaAdapter_FastDecisionAndClassification() {
        val adapter = LocalLayaDecisionAdapter()
        assertTrue(adapter.isAvailable())

        // Test LayaDecision
        val decision = adapter.classify("set brightness to 60%")
        assertNotNull(decision)
        assertTrue(decision.latencyMs < 10)

        // Test IntentClassificationResult
        val brightnessResult = adapter.classifyIntent("set brightness to 60%")
        assertEquals(CanonicalIntentId.SET_BRIGHTNESS, brightnessResult.canonicalIntent)
        assertEquals("set_brightness", brightnessResult.canonicalToolName)
        assertEquals(60, brightnessResult.parameters["percentage"])
        assertTrue("Classification should complete in <10ms", brightnessResult.latencyMs < 10)

        val dndResult = adapter.classifyIntent("enable dnd")
        assertEquals(CanonicalIntentId.SET_DND_MODE, dndResult.canonicalIntent)
        assertEquals(true, dndResult.parameters["enabled"])

        val ringerResult = adapter.classifyIntent("silent mode")
        assertEquals(CanonicalIntentId.SET_RINGER_MODE, ringerResult.canonicalIntent)
        assertEquals("silent", ringerResult.parameters["mode"])

        val muteResult = adapter.classifyIntent("mute media")
        assertEquals(CanonicalIntentId.MUTE_STREAM, muteResult.canonicalIntent)
    }
}
