package com.example.functiongemmavoice.runtime.intent

/**
 * Canonical application intent types as requested by the architecture specification.
 * These represent intent domains/types, not guarantees that every action is available
 * without prerequisite permissions or system services.
 */
enum class CanonicalIntentId {
    OPEN_APP,
    NAVIGATE_BACK,
    GO_HOME,
    SHOW_RECENTS,
    LOCK_SCREEN,
    OPEN_PLAY_STORE_SEARCH,
    OPEN_PLAY_STORE_LISTING,
    OPEN_PLAY_STORE_UPDATES,
    REQUEST_APP_INSTALL,
    REQUEST_APP_UPDATE,
    OPEN_SPLIT_SCREEN,
    OPEN_FLOATING_WINDOW,
    OPEN_BLUETOOTH_SETTINGS,
    REQUEST_BLUETOOTH_ENABLE,
    GET_BATTERY_STATUS,
    TURN_ON_FLASHLIGHT,
    TURN_OFF_FLASHLIGHT,
    OPEN_WIFI_SETTINGS,
    GET_WIFI_STATE,
    CREATE_TIMER,
    CREATE_ALARM,
    GET_BRIGHTNESS,
    SET_BRIGHTNESS,
    ADJUST_BRIGHTNESS,
    GET_VOLUME,
    SET_VOLUME,
    ADJUST_VOLUME,
    MUTE_STREAM,
    UNMUTE_STREAM,
    SET_RINGER_MODE,
    SET_DND_MODE,
    UNKNOWN
}

/**
 * Result data class retaining the normalized request, canonical intent,
 * parameters, decision confidence, ambiguity information, and fallback reason.
 */
data class IntentClassificationResult(
    val normalizedRequest: String,
    val canonicalIntent: CanonicalIntentId,
    val canonicalToolName: String? = null,
    val parameters: Map<String, Any?> = emptyMap(),
    val confidence: Float = 1.0f,
    val isAmbiguous: Boolean = false,
    val clarificationPrompt: String? = null,
    val fallbackReason: String? = null,
    val latencyMs: Long = 0L
)

/**
 * Stable intent classification contract allowing deterministic routers,
 * local fast adapters, and optional model-based classifiers (e.g. Laya)
 * to be swapped and tested independently.
 */
interface IntentClassifier {
    fun classifyIntent(utterance: String): IntentClassificationResult
    fun isAvailable(): Boolean
}
