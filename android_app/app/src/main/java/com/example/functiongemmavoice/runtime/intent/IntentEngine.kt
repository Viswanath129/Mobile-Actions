package com.example.functiongemmavoice.runtime.intent

import com.example.functiongemmavoice.runtime.capability.CapabilityRegistry
import java.util.Locale
import java.util.UUID

object TextNormalizer {
    private val contractions = mapOf(
        "don't" to "do not",
        "dont" to "do not",
        "can't" to "cannot",
        "cant" to "cannot",
        "won't" to "will not",
        "wont" to "will not",
        "i'm" to "i am",
        "im" to "i am",
        "it's" to "it is",
        "didn't" to "did not",
        "didnt" to "did not",
        "doesn't" to "does not",
        "doesnt" to "does not",
        "shouldn't" to "should not",
        "shouldnt" to "should not"
    )

    fun normalize(raw: String): String {
        var text = raw.trim().lowercase(Locale.ROOT)
        // Normalize common speech recognition punctuation
        text = text.replace("?", " ")
            .replace("!", " ")
            .replace(",", " ")
            .replace(";", " ; ")
            .replace(":", " ")
            .replace("\"", " ")
            .replace("'", "")

        // Normalize spaces
        text = text.replace(Regex("\\s+"), " ").trim()

        // Replace contractions
        val tokens = text.split(" ").map { contractions[it] ?: it }
        return tokens.joinToString(" ")
    }

    private val conversationalLeadIns = listOf(
        "can you please ", "could you please ", "would you please ", "will you please ",
        "can you ", "could you ", "would you ", "will you ",
        "please ", "kindly ", "help me ", "i want to ", "want to ",
        "i need to ", "need to ", "just ", "go ahead and ",
        "దయచేసి ", "ఒకసారి ", "నాకు "
    )

    private val conversationalTrailers = listOf(
        " for me", " on my phone", " right now", " please", " app",
        " నాకు", " దయచేసి", " యాప్"
    )

    fun stripConversationalNoise(text: String): String {
        var s = text.trim()
        for (prefix in conversationalLeadIns) {
            if (s.startsWith(prefix, ignoreCase = true)) {
                s = s.substring(prefix.length).trim()
                break
            }
        }
        for (suffix in conversationalTrailers) {
            if (s.endsWith(suffix, ignoreCase = true)) {
                s = s.substring(0, s.length - suffix.length).trim()
                break
            }
        }
        return s
    }
}

class IntentEngine(private val capabilityRegistry: CapabilityRegistry) {

    private val teluguOpenVerbs = listOf("ఓపెన్", "ఓపెన్ చెయ్", "ఓపెన్ చెయ్యి", "చూపించు", "తీయి", "తీయండి")
    private val teluguOnVerbs = listOf("ఆన్", "ఆన్ చెయ్", "ఆన్ చెయ్యి", "వెలిగించు", "స్టార్ట్", "మొదలుపెట్టు")
    private val teluguOffVerbs = listOf("ఆఫ్", "ఆఫ్ చెయ్", "ఆఫ్ చెయ్యి", "ఆపు", "ఆపండి", "కట్టు")
    private val teluguStatusVerbs = listOf("ఎంత", "ఎలా", "ఎంత ఉంది", "స్థితి", "చూడు", "చెప్పు")
    private val teluguNegations = listOf("వద్దు", "చేయవద్దు", "ఆపండి", "వేయొద్దు", "చేయొద్దు")

    fun parse(rawInput: String, requestId: String = UUID.randomUUID().toString()): StructuredIntent {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return StructuredIntent(
                requestId = requestId,
                originalUtterance = rawInput,
                normalizedUtterance = "",
                intentDomain = IntentDomain.UNKNOWN,
                action = SemanticAction.UNKNOWN,
                target = "",
                confidence = 0.0f,
                executionStrategy = ExecutionStrategy.CLARIFICATION_REQUIRED,
                clarificationPrompt = "Please say or type a command."
            )
        }

        val normalized = TextNormalizer.normalize(trimmed)

        // Interrogative / Hypothetical query check ("Why should I turn on the flashlight?")
        val interrogativePatterns = listOf(
            "why ", "how to ", "how do ", "how can ", "what if ", "can you explain ",
            "tell me about ", "should i ", "ఎందుకు ", "ఎలా "
        )
        if (interrogativePatterns.any { normalized.startsWith(it) || normalized.contains(" $it") }) {
            return StructuredIntent(
                requestId = requestId,
                originalUtterance = rawInput,
                normalizedUtterance = normalized,
                intentDomain = IntentDomain.INFORMATION_QUERY,
                action = SemanticAction.QUERY_INFO,
                target = "reasoning",
                confidence = 0.60f,
                executionStrategy = ExecutionStrategy.MODEL_INFERENCE
            )
        }

        // 1. Compound / Multi-Action detection (" and ", " మరియు ", " after ", " then ")
        val splitDelim = when {
            normalized.contains(" and ") -> " and "
            normalized.contains(" మరియు ") -> " మరియు "
            normalized.contains(" then ") -> " then "
            normalized.contains(" after ") -> " after "
            else -> null
        }

        if (splitDelim != null) {
            val parts = normalized.split(splitDelim).map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size >= 2) {
                val subIntents = parts.mapIndexed { idx, part ->
                    parseSingle(part, rawInput, "$requestId-sub$idx")
                }
                // Check if all sub-intents are unambiguous
                val allResolvable = subIntents.all {
                    it.executionStrategy == ExecutionStrategy.FAST_PATH_DETERMINISTIC && it.canonicalToolName != null
                }
                if (allResolvable) {
                    val deps = if (splitDelim == " after " && subIntents.size >= 2) {
                        listOf(subIntents[1].requestId)
                    } else emptyList()

                    return StructuredIntent(
                        requestId = requestId,
                        originalUtterance = rawInput,
                        normalizedUtterance = normalized,
                        intentDomain = IntentDomain.MULTI_ACTION,
                        action = SemanticAction.UNKNOWN,
                        target = "multi_action",
                        confidence = 0.95f,
                        executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                        subIntents = subIntents,
                        dependencies = deps
                    )
                }
            }
        }

        return parseSingle(normalized, rawInput, requestId)
    }

    private fun parseSingle(norm: String, rawOriginal: String, reqId: String): StructuredIntent {
        // 2. Complex Negation with Positive Action Check
        // e.g., "do not turn on bluetooth ; open its settings", "open bluetooth settings, but do not change anything"
        val hasClauseSplit = norm.contains(" ; ") || norm.contains(" but ") || norm.contains(" instead ")
        if (hasClauseSplit) {
            val clauses = norm.split(Regex("""\s*;\s*|\s+but\s+|\s+instead\s+""")).map { it.trim() }.filter { it.isNotBlank() }
            var positiveIntent: StructuredIntent? = null
            var detectedNegation: NegationInfo? = null

            // First pass: extract any negation clause to know negated target
            for (clause in clauses) {
                if (isPurelyNegated(clause)) {
                    detectedNegation = NegationInfo(
                        hasNegation = true,
                        negatedAction = extractActionFromClause(clause),
                        negatedTarget = extractTargetFromClause(clause),
                        rawNegationPhrase = clause,
                        preservesPositiveAction = true
                    )
                    break
                }
            }

            // Second pass: resolve positive intent, resolving anaphoric 'its settings'
            for (clause in clauses) {
                if (!isPurelyNegated(clause)) {
                    var resolvedClause = clause
                    if ((clause.contains("its settings") || clause.contains("their settings") || clause == "open settings") &&
                        detectedNegation?.negatedTarget != null) {
                        resolvedClause = clause.replace("its settings", "${detectedNegation.negatedTarget} settings")
                            .replace("their settings", "${detectedNegation.negatedTarget} settings")
                    }
                    val parsedClause = parseCoreIntent(resolvedClause, rawOriginal, reqId)
                    if (parsedClause.canonicalToolName != null) {
                        positiveIntent = parsedClause
                    }
                }
            }

            if (positiveIntent != null && detectedNegation != null) {
                return positiveIntent.copy(
                    negation = detectedNegation,
                    confidence = 0.98f
                )
            }
        }

        // 3. Check for pure negation (e.g. "do not turn on flashlight", "don't open bluetooth")
        if (isPurelyNegated(norm)) {
            val target = extractTargetFromClause(norm) ?: "action"
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.STATE_MUTATION,
                action = SemanticAction.REJECT_NEGATED,
                target = target,
                desiredState = DesiredState.NONE,
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.NEGATED_REJECTED,
                negation = NegationInfo(hasNegation = true, rawNegationPhrase = norm),
                clarificationPrompt = "Action cancelled: Request contains explicit negation. No state was changed."
            )
        }

        // 4. Core Intent Extraction
        val direct = parseCoreIntent(norm, rawOriginal, reqId)
        if (direct.executionStrategy == ExecutionStrategy.FAST_PATH_DETERMINISTIC) {
            return direct
        }

        // Try stripping conversational noise ("can you open whatsapp for me" -> "open whatsapp")
        val stripped = TextNormalizer.stripConversationalNoise(norm)
        if (stripped != norm) {
            val strippedIntent = parseCoreIntent(stripped, rawOriginal, reqId)
            if (strippedIntent.executionStrategy == ExecutionStrategy.FAST_PATH_DETERMINISTIC) {
                return strippedIntent
            }
        }

        return direct
    }

    private fun parseCoreIntent(norm: String, rawOriginal: String, reqId: String): StructuredIntent {
        // 4A. BLUETOOTH REFERENCE IMPLEMENTATION (Objective 2)
        if (isBluetoothRelated(norm)) {
            return resolveBluetoothIntent(norm, rawOriginal, reqId)
        }

        // 4B. WI-FI
        if (isWifiRelated(norm)) {
            return resolveWifiIntent(norm, rawOriginal, reqId)
        }

        // 4C. FLASHLIGHT / TORCH
        if (isFlashlightRelated(norm)) {
            return resolveFlashlightIntent(norm, rawOriginal, reqId)
        }

        // 4C1. BRIGHTNESS
        if (isBrightnessRelated(norm)) {
            return resolveBrightnessIntent(norm, rawOriginal, reqId)
        }

        // 4C2. DND (Do Not Disturb)
        if (isDndRelated(norm)) {
            return resolveDndIntent(norm, rawOriginal, reqId)
        }

        // 4C3. RINGER MODE (Silent / Vibrate / Normal)
        if (isRingerRelated(norm)) {
            return resolveRingerIntent(norm, rawOriginal, reqId)
        }

        // 4C4. VOLUME
        if (isVolumeRelated(norm)) {
            return resolveVolumeIntent(norm, rawOriginal, reqId)
        }

        // 4D. BATTERY STATUS
        if (isBatteryRelated(norm)) {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.SYSTEM_INSPECTION,
                action = SemanticAction.GET_STATE,
                target = "battery",
                desiredState = DesiredState.OBSERVE,
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "get_battery_status"
            )
        }

        // 4E. TIME / DATE / CLOCK
        if (isTimeOrDateRelated(norm)) {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.INFORMATION_QUERY,
                action = SemanticAction.QUERY_INFO,
                target = "time_date",
                desiredState = DesiredState.OBSERVE,
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "check_current_time_date"
            )
        }

        // 4F. TIMER
        if (norm == "set a timer" || norm == "create a timer" || norm == "start a timer" || norm == "timer పెట్టు" || norm == "open timer") {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.TIME_PRODUCTIVITY,
                action = SemanticAction.SET_TIMER,
                target = "timer",
                confidence = 0.5f,
                executionStrategy = ExecutionStrategy.CLARIFICATION_REQUIRED,
                clarificationPrompt = "Please specify the duration for the timer (e.g., 5 minutes)."
            )
        }
        val timerMatch = matchTimerCommand(norm)
        if (timerMatch != null) {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.TIME_PRODUCTIVITY,
                action = SemanticAction.SET_TIMER,
                target = "timer",
                desiredState = DesiredState.ON,
                parameters = mapOf("duration_seconds" to timerMatch),
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "create_timer"
            )
        }

        // 4G. ALARM
        val alarmMatch = matchAlarmCommand(norm)
        if (alarmMatch != null) {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.TIME_PRODUCTIVITY,
                action = SemanticAction.SET_ALARM,
                target = "alarm",
                desiredState = DesiredState.ON,
                parameters = alarmMatch,
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "create_alarm"
            )
        }

        // 4H. THIRD-PARTY MEDIA PLAYBACK GUARD (Spotify, Netflix, etc.)
        if (norm.contains("spotify") || norm.contains("netflix") || norm.contains("youtube music")) {
            if (norm.startsWith("play") || norm.contains("play song") || norm.contains("play music") || norm.contains("పాటలు")) {
                val service = if (norm.contains("spotify")) "Spotify" else "the requested app"
                return StructuredIntent(
                    requestId = reqId,
                    originalUtterance = rawOriginal,
                    normalizedUtterance = norm,
                    intentDomain = IntentDomain.IN_APP_OPERATION,
                    action = SemanticAction.EXECUTE_IN_APP,
                    target = "media_playback",
                    confidence = 0.95f,
                    executionStrategy = ExecutionStrategy.UNSUPPORTED,
                    clarificationPrompt = "$service background playback control is not supported directly. You can say 'Open $service' to launch the app."
                )
            }
        }

        // 4I. CALENDAR (Dedicated open_calendar tool)
        if (norm == "open calendar" || norm == "calendar" || norm == "క్యాలెండర్" || norm == "క్యాలెండర్ ఓపెన్ చెయ్") {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.TIME_PRODUCTIVITY,
                action = SemanticAction.LAUNCH_APP,
                target = "calendar",
                desiredState = DesiredState.OPEN,
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "open_calendar"
            )
        }

        // 4IA. LOCK SCREEN
        if (norm.contains("lock screen") || norm.contains("lock my screen") ||
            norm.contains("lock phone") || norm.contains("lock my phone") ||
            norm.contains("స్క్రీన్ లాక్") || norm.contains("ఫోన్ లాక్") || norm == "lock") {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.STATE_MUTATION,
                action = SemanticAction.SET_STATE,
                target = "lock_screen",
                desiredState = DesiredState.ON,
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "lock_screen",
                canonicalIntentId = CanonicalIntentId.LOCK_SCREEN
            )
        }

        // 4IB. NAVIGATION & APP CLOSING
        if (norm == "back" || norm == "go back" || norm == "navigate back" || norm == "వెనక్కి" || norm == "వెనక్కి వెళ్ళు") {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.SYSTEM_INSPECTION,
                action = SemanticAction.OPEN_SETTINGS,
                target = "navigate_back",
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "navigate_back",
                canonicalIntentId = CanonicalIntentId.NAVIGATE_BACK
            )
        }
        if (norm == "home" || norm == "go home" || norm == "home screen" || norm == "హోమ్" || norm == "హోమ్ కి వెళ్ళు") {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.SYSTEM_INSPECTION,
                action = SemanticAction.OPEN_SETTINGS,
                target = "go_home_screen",
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "go_home_screen",
                canonicalIntentId = CanonicalIntentId.GO_HOME
            )
        }
        if (norm == "recents" || norm == "show recents" || norm == "recent apps" || norm == "రీసెంట్స్") {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.SYSTEM_INSPECTION,
                action = SemanticAction.OPEN_SETTINGS,
                target = "show_recents",
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "show_recents",
                canonicalIntentId = CanonicalIntentId.SHOW_RECENTS
            )
        }
        if (norm.startsWith("close ") || norm.startsWith("exit ") || norm.startsWith("kill ") || norm.endsWith(" క్లోజ్ చెయ్") || norm == "close app") {
            val app = norm.removePrefix("close ").removePrefix("exit ").removePrefix("kill ").removeSuffix(" క్లోజ్ చెయ్").removeSuffix(" app").trim()
            val resolvedApp = if (app.isNotBlank()) (appAliases[app] ?: app.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }) else "the application"
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.APP_LAUNCH,
                action = SemanticAction.LAUNCH_APP,
                target = "close_app",
                parameters = mapOf("app_name" to resolvedApp),
                confidence = 0.95f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "close_or_minimize_app",
                canonicalIntentId = CanonicalIntentId.GO_HOME
            )
        }

        // 4IC. PLAY STORE ACTIONS
        if (norm.contains("play store") || norm.contains("ప్లే స్టోర్") || norm.startsWith("install ") ||
            (norm.startsWith("update ") && !norm.contains("bluetooth") && !norm.contains("wifi"))) {
            if (norm.contains("update all") || norm.contains("show updates") || norm.contains("show my updates") || norm.contains("app updates") || norm == "open play store updates" || norm.contains("అప్‌డేట్లు")) {
                return StructuredIntent(
                    requestId = reqId,
                    originalUtterance = rawOriginal,
                    normalizedUtterance = norm,
                    intentDomain = IntentDomain.APP_LAUNCH,
                    action = SemanticAction.LAUNCH_APP,
                    target = "play_store_updates",
                    confidence = 1.0f,
                    executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                    canonicalToolName = "open_play_store_updates",
                    canonicalIntentId = CanonicalIntentId.OPEN_PLAY_STORE_UPDATES
                )
            }
            if (norm.startsWith("install ")) {
                val app = norm.removePrefix("install ").removeSuffix(" app").trim()
                val resolved = appAliases[app] ?: app.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                return StructuredIntent(
                    requestId = reqId,
                    originalUtterance = rawOriginal,
                    normalizedUtterance = norm,
                    intentDomain = IntentDomain.APP_LAUNCH,
                    action = SemanticAction.LAUNCH_APP,
                    target = resolved,
                    parameters = mapOf("app_name" to resolved),
                    confidence = 0.95f,
                    executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                    canonicalToolName = "request_app_install",
                    canonicalIntentId = CanonicalIntentId.REQUEST_APP_INSTALL
                )
            }
            if (norm.startsWith("update ") && !norm.contains("all")) {
                val app = norm.removePrefix("update ").removeSuffix(" app").trim()
                val resolved = appAliases[app] ?: app.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                return StructuredIntent(
                    requestId = reqId,
                    originalUtterance = rawOriginal,
                    normalizedUtterance = norm,
                    intentDomain = IntentDomain.APP_LAUNCH,
                    action = SemanticAction.LAUNCH_APP,
                    target = resolved,
                    parameters = mapOf("app_name" to resolved),
                    confidence = 0.95f,
                    executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                    canonicalToolName = "request_app_update",
                    canonicalIntentId = CanonicalIntentId.REQUEST_APP_UPDATE
                )
            }
            if (norm.startsWith("search play store") || norm.startsWith("find in play store") || norm.contains("in play store") || norm.contains("on play store") || norm.contains("ప్లే స్టోర్ లో")) {
                val q = norm.replace("search play store for", "")
                    .replace("find in play store", "")
                    .replace("search in play store", "")
                    .replace("in play store", "")
                    .replace("on play store", "")
                    .replace("open play store search", "")
                    .replace("ప్లే స్టోర్ లో", "").trim()
                val finalQ = if (q.isNotBlank()) q else "top apps"
                return StructuredIntent(
                    requestId = reqId,
                    originalUtterance = rawOriginal,
                    normalizedUtterance = norm,
                    intentDomain = IntentDomain.APP_LAUNCH,
                    action = SemanticAction.LAUNCH_APP,
                    target = finalQ,
                    parameters = mapOf("query" to finalQ),
                    confidence = 0.95f,
                    executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                    canonicalToolName = "open_play_store_search",
                    canonicalIntentId = CanonicalIntentId.OPEN_PLAY_STORE_SEARCH
                )
            }
            if (norm.startsWith("open ") && (norm.contains("in play store") || norm.contains("on play store"))) {
                val app = norm.replace("open ", "").replace("in play store", "").replace("on play store", "").removeSuffix(" app").trim()
                val resolved = appAliases[app] ?: app.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                return StructuredIntent(
                    requestId = reqId,
                    originalUtterance = rawOriginal,
                    normalizedUtterance = norm,
                    intentDomain = IntentDomain.APP_LAUNCH,
                    action = SemanticAction.LAUNCH_APP,
                    target = resolved,
                    parameters = mapOf("app_name" to resolved),
                    confidence = 0.95f,
                    executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                    canonicalToolName = "open_play_store_listing",
                    canonicalIntentId = CanonicalIntentId.OPEN_PLAY_STORE_LISTING
                )
            }
        }

        // 4ID. MULTI-WINDOW & SPLIT SCREEN
        if (norm.contains("split screen") || norm.contains("side by side") || (norm.contains(" next to ") && (norm.startsWith("open ") || norm.startsWith("show ")))) {
            val apps = extractSplitScreenApps(norm)
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.APP_LAUNCH,
                action = SemanticAction.LAUNCH_APP,
                target = "split_screen",
                parameters = mapOf("primary_app" to apps.first, "secondary_app" to apps.second),
                confidence = 0.95f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "open_split_screen",
                canonicalIntentId = CanonicalIntentId.OPEN_SPLIT_SCREEN
            )
        }

        // 4IE. FLOATING WINDOW
        if (norm.contains("floating window") || norm.contains("floating mode")) {
            val app = norm.replace("open ", "").replace("in floating window", "").replace("floating window", "").replace("floating mode", "").trim()
            val finalApp = if (app.isNotBlank()) (appAliases[app] ?: app.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }) else "App"
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.APP_LAUNCH,
                action = SemanticAction.LAUNCH_APP,
                target = finalApp,
                parameters = mapOf("app_name" to finalApp),
                confidence = 0.95f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "open_floating_window",
                canonicalIntentId = CanonicalIntentId.OPEN_FLOATING_WINDOW
            )
        }

        // 4J. APP LAUNCH (YouTube, Spotify, Camera, Settings, etc.)
        val appName = extractAppLaunchName(norm)
        if (appName != null) {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.APP_LAUNCH,
                action = SemanticAction.LAUNCH_APP,
                target = appName,
                desiredState = DesiredState.OPEN,
                parameters = mapOf("app_name" to appName),
                confidence = 0.95f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "open_app"
            )
        }

        // 4J. SETTINGS NAVIGATION (General Settings, Sound, Display, Location, Airplane, DND)
        val settingsTool = matchSettingsNavigation(norm)
        if (settingsTool != null) {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.SETTINGS_NAVIGATION,
                action = SemanticAction.OPEN_SETTINGS,
                target = settingsTool,
                desiredState = DesiredState.OPEN,
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = settingsTool
            )
        }

        // 4K. COMMUNICATION WITH CONFIRMATION GATES (Call, SMS, Email, Contact)
        val commIntent = matchCommunicationCommand(norm, rawOriginal, reqId)
        if (commIntent != null) {
            return commIntent
        }

        // Fallback: Complex or open-ended natural language to FunctionGemma
        return StructuredIntent(
            requestId = reqId,
            originalUtterance = rawOriginal,
            normalizedUtterance = norm,
            intentDomain = IntentDomain.UNKNOWN,
            action = SemanticAction.UNKNOWN,
            target = "natural_language",
            confidence = 0.60f,
            executionStrategy = ExecutionStrategy.MODEL_INFERENCE
        )
    }

    // --- BLUETOOTH INTENT DISAMBIGUATION (Objective 2) ---
    private fun resolveBluetoothIntent(norm: String, rawOriginal: String, reqId: String): StructuredIntent {
        // Query status: "is bluetooth on?", "bluetooth status", "check bluetooth state"
        if (norm.startsWith("is bluetooth") || norm.contains("check bluetooth") || norm.contains("bluetooth status") ||
            norm.contains("bluetooth state") || norm.contains("బ్లూటూత్ స్థితి") || norm.contains("బ్లూటూత్ ఆన్ లో ఉందా")) {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.SYSTEM_INSPECTION,
                action = SemanticAction.GET_STATE,
                target = "bluetooth",
                desiredState = DesiredState.OBSERVE,
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "get_bluetooth_state"
            )
        }

        // State mutation: Turn ON / Switch ON
        val isOnMutation = (norm.contains("turn on") || norm.contains("switch on") || norm.contains("enable") ||
                norm.endsWith(" on") || norm.contains("ఆన్ చెయ్") || norm.contains("ఆన్ చెయ్యి")) &&
                !norm.contains("settings") && !norm.contains("open")

        if (isOnMutation) {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.STATE_MUTATION,
                action = SemanticAction.SET_STATE,
                target = "bluetooth",
                desiredState = DesiredState.ON,
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "request_bluetooth_enable"
            )
        }

        // State mutation: Turn OFF / Switch OFF
        val isOffMutation = (norm.contains("turn off") || norm.contains("switch off") || norm.contains("disable") ||
                norm.endsWith(" off") || norm.contains("ఆఫ్ చెయ్") || norm.contains("ఆఫ్ చెయ్యి")) &&
                !norm.contains("settings")

        if (isOffMutation) {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.STATE_MUTATION,
                action = SemanticAction.SET_STATE,
                target = "bluetooth",
                desiredState = DesiredState.OFF,
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "request_bluetooth_disable"
            )
        }

        // Settings Navigation: "open bluetooth", "bluetooth settings", "take me to bluetooth settings", "bluetooth open chey"
        return StructuredIntent(
            requestId = reqId,
            originalUtterance = rawOriginal,
            normalizedUtterance = norm,
            intentDomain = IntentDomain.SETTINGS_NAVIGATION,
            action = SemanticAction.OPEN_SETTINGS,
            target = "bluetooth",
            desiredState = DesiredState.OPEN,
            confidence = 0.98f,
            executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
            canonicalToolName = "open_bluetooth_settings"
        )
    }

    // --- WI-FI INTENT DISAMBIGUATION ---
    private fun resolveWifiIntent(norm: String, rawOriginal: String, reqId: String): StructuredIntent {
        if (norm.startsWith("is wifi") || norm.contains("wifi status") || norm.contains("check wifi") || norm.contains("వైఫై స్థితి")) {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.SYSTEM_INSPECTION,
                action = SemanticAction.GET_STATE,
                target = "wifi",
                desiredState = DesiredState.OBSERVE,
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "get_wifi_state"
            )
        }
        return StructuredIntent(
            requestId = reqId,
            originalUtterance = rawOriginal,
            normalizedUtterance = norm,
            intentDomain = IntentDomain.SETTINGS_NAVIGATION,
            action = SemanticAction.OPEN_SETTINGS,
            target = "wifi",
            desiredState = DesiredState.OPEN,
            confidence = 1.0f,
            executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
            canonicalToolName = "open_wifi_settings"
        )
    }

    // --- FLASHLIGHT INTENT DISAMBIGUATION ---
    private fun resolveFlashlightIntent(norm: String, rawOriginal: String, reqId: String): StructuredIntent {
        val isOff = norm.contains("off") || norm.contains("ఆఫ్") || norm.contains("ఆపు")
        val toolName = if (isOff) "turn_off_flashlight" else "turn_on_flashlight"
        val state = if (isOff) DesiredState.OFF else DesiredState.ON

        return StructuredIntent(
            requestId = reqId,
            originalUtterance = rawOriginal,
            normalizedUtterance = norm,
            intentDomain = IntentDomain.STATE_MUTATION,
            action = SemanticAction.SET_STATE,
            target = "flashlight",
            desiredState = state,
            confidence = 1.0f,
            executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
            canonicalToolName = toolName
        )
    }

    // --- BRIGHTNESS INTENT DISAMBIGUATION ---
    private fun resolveBrightnessIntent(norm: String, rawOriginal: String, reqId: String): StructuredIntent {
        if (norm.startsWith("is brightness") || norm.contains("what is") || norm.contains("check") || norm.contains("get") || norm.contains("ఎంత") || norm == "brightness") {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.SYSTEM_INSPECTION,
                action = SemanticAction.GET_STATE,
                target = "brightness",
                desiredState = DesiredState.OBSERVE,
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "get_brightness",
                canonicalIntentId = CanonicalIntentId.GET_BRIGHTNESS
            )
        }
        if (norm.contains("dim") || norm.contains("minimum") || norm.contains("min") || norm.contains("low")) {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.STATE_MUTATION,
                action = SemanticAction.SET_STATE,
                target = "brightness",
                desiredState = DesiredState.ON,
                parameters = mapOf("percentage" to 15),
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "set_brightness",
                canonicalIntentId = CanonicalIntentId.SET_BRIGHTNESS
            )
        }
        if (norm.contains("max") || norm.contains("100")) {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.STATE_MUTATION,
                action = SemanticAction.SET_STATE,
                target = "brightness",
                desiredState = DesiredState.ON,
                parameters = mapOf("percentage" to 100),
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "set_brightness",
                canonicalIntentId = CanonicalIntentId.SET_BRIGHTNESS
            )
        }
        val pctMatch = Regex("""(\d{1,3})\s*%?""").find(norm)
        if (pctMatch != null) {
            val pct = pctMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: 50
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.STATE_MUTATION,
                action = SemanticAction.SET_STATE,
                target = "brightness",
                desiredState = DesiredState.ON,
                parameters = mapOf("percentage" to pct),
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "set_brightness",
                canonicalIntentId = CanonicalIntentId.SET_BRIGHTNESS
            )
        }
        val isLower = norm.contains("down") || norm.contains("lower") || norm.contains("decrease") || norm.contains("reduce") || norm.contains("తగ్గించు")
        val dir = if (isLower) "down" else "up"
        val delta = if (isLower) -20 else 20
        return StructuredIntent(
            requestId = reqId,
            originalUtterance = rawOriginal,
            normalizedUtterance = norm,
            intentDomain = IntentDomain.STATE_MUTATION,
            action = SemanticAction.SET_STATE,
            target = "brightness",
            desiredState = DesiredState.ON,
            parameters = mapOf("direction" to dir, "delta" to delta),
            confidence = 1.0f,
            executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
            canonicalToolName = "adjust_brightness",
            canonicalIntentId = CanonicalIntentId.ADJUST_BRIGHTNESS
        )
    }

    // --- DND INTENT DISAMBIGUATION ---
    private fun resolveDndIntent(norm: String, rawOriginal: String, reqId: String): StructuredIntent {
        val isOff = norm.contains("off") || norm.contains("disable") || norm.contains("stop") || norm.contains("ఆఫ్")
        return StructuredIntent(
            requestId = reqId,
            originalUtterance = rawOriginal,
            normalizedUtterance = norm,
            intentDomain = IntentDomain.STATE_MUTATION,
            action = SemanticAction.SET_STATE,
            target = "dnd",
            desiredState = if (isOff) DesiredState.OFF else DesiredState.ON,
            parameters = mapOf("enabled" to !isOff),
            confidence = 1.0f,
            executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
            canonicalToolName = "set_dnd_mode",
            canonicalIntentId = CanonicalIntentId.SET_DND_MODE
        )
    }

    // --- RINGER INTENT DISAMBIGUATION ---
    private fun resolveRingerIntent(norm: String, rawOriginal: String, reqId: String): StructuredIntent {
        val mode = when {
            norm.contains("silent") || norm.contains("mute phone") || norm.contains("సైలెంట్") -> "silent"
            norm.contains("vibrate") || norm.contains("vibration") || norm.contains("వైబ్రేట్") -> "vibrate"
            else -> "normal"
        }
        return StructuredIntent(
            requestId = reqId,
            originalUtterance = rawOriginal,
            normalizedUtterance = norm,
            intentDomain = IntentDomain.STATE_MUTATION,
            action = SemanticAction.SET_STATE,
            target = "ringer",
            desiredState = DesiredState.ON,
            parameters = mapOf("mode" to mode),
            confidence = 1.0f,
            executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
            canonicalToolName = "set_ringer_mode",
            canonicalIntentId = CanonicalIntentId.SET_RINGER_MODE
        )
    }

    // --- VOLUME INTENT DISAMBIGUATION ---
    private fun resolveVolumeIntent(norm: String, rawOriginal: String, reqId: String): StructuredIntent {
        val stream = when {
            norm.contains("media") || norm.contains("music") -> "media"
            norm.contains("ring") || norm.contains("call") -> "ring"
            norm.contains("notification") -> "notification"
            norm.contains("alarm") -> "alarm"
            else -> "media"
        }
        if (norm.contains("unmute")) {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.STATE_MUTATION,
                action = SemanticAction.SET_STATE,
                target = "volume",
                parameters = mapOf("stream" to stream),
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "unmute_stream",
                canonicalIntentId = CanonicalIntentId.UNMUTE_STREAM
            )
        }
        if (norm.contains("mute")) {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.STATE_MUTATION,
                action = SemanticAction.SET_STATE,
                target = "volume",
                parameters = mapOf("stream" to stream),
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "mute_stream",
                canonicalIntentId = CanonicalIntentId.MUTE_STREAM
            )
        }
        if (norm.contains("what is") || norm.contains("check") || norm.contains("get") || norm.contains("status") || norm.contains("level") || norm.contains("ఎంత") || norm == "volume") {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.SYSTEM_INSPECTION,
                action = SemanticAction.GET_STATE,
                target = "volume",
                parameters = mapOf("stream" to stream),
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "get_volume",
                canonicalIntentId = CanonicalIntentId.GET_VOLUME
            )
        }
        val pctMatch = Regex("""(\d{1,3})\s*%?""").find(norm)
        if (pctMatch != null) {
            val pct = pctMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: 50
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = norm,
                intentDomain = IntentDomain.STATE_MUTATION,
                action = SemanticAction.SET_STATE,
                target = "volume",
                parameters = mapOf("stream" to stream, "percentage" to pct),
                confidence = 1.0f,
                executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
                canonicalToolName = "set_volume",
                canonicalIntentId = CanonicalIntentId.SET_VOLUME
            )
        }
        val isLower = norm.contains("down") || norm.contains("lower") || norm.contains("decrease") || norm.contains("reduce") || norm.contains("quieter") || norm.contains("తగ్గించు")
        val dir = if (isLower) "down" else "up"
        val delta = if (isLower) -1 else 1
        return StructuredIntent(
            requestId = reqId,
            originalUtterance = rawOriginal,
            normalizedUtterance = norm,
            intentDomain = IntentDomain.STATE_MUTATION,
            action = SemanticAction.SET_STATE,
            target = "volume",
            parameters = mapOf("stream" to stream, "direction" to dir, "delta" to delta),
            confidence = 1.0f,
            executionStrategy = ExecutionStrategy.FAST_PATH_DETERMINISTIC,
            canonicalToolName = "adjust_volume",
            canonicalIntentId = CanonicalIntentId.ADJUST_VOLUME
        )
    }

    private fun isBrightnessRelated(s: String): Boolean {
        if (s.contains("settings")) return false
        return s.contains("brightness") || s.contains("screen light") || s.contains("dim screen") ||
               s.contains("make screen brighter") || s.contains("బ్రైట్‌నెస్") || s.contains("బ్రైట్నెస్")
    }

    private fun isDndRelated(s: String): Boolean {
        if (s.contains("settings")) return false
        return s.contains("dnd") || s.contains("do not disturb")
    }

    private fun isRingerRelated(s: String): Boolean {
        return s.contains("silent mode") || s.contains("vibrate mode") || s.contains("vibration mode") ||
               s.contains("normal mode") || s == "silent" || s == "vibrate" || s.contains("put phone on vibrate") ||
               s.contains("put phone on silent") || s.contains("mute phone") || s.contains("సైలెంట్") || s.contains("వైబ్రేట్")
    }

    private fun isVolumeRelated(s: String): Boolean {
        if (s.contains("settings")) return false
        return s.contains("volume") || s.contains("mute") || s.contains("unmute") ||
               s.contains("sound level") || s.contains("వాల్యూమ్")
    }

    private fun isBluetoothRelated(s: String): Boolean {
        return s.contains("bluetooth") || s.contains("బ్లూటూత్") || s.contains("blutooth")
    }

    private fun isWifiRelated(s: String): Boolean {
        return s.contains("wifi") || s.contains("wi fi") || s.contains("వైఫై")
    }

    private fun isFlashlightRelated(s: String): Boolean {
        return s.contains("flashlight") || s.contains("torch") || s.contains("ఫ్లాష్‌లైట్") || s.contains("టార్చ్") || s.contains("లైట్")
    }

    private fun isBatteryRelated(s: String): Boolean {
        return s.contains("battery") || s.contains("బ్యాటరీ") || s.contains("charging") || s.contains("ఛార్జింగ్")
    }

    private fun isTimeOrDateRelated(s: String): Boolean {
        return s.contains("what time") || s.contains("time is it") || s.contains("current time") ||
                s.contains("today's date") || s.contains("todays date") || s.contains("what day") ||
                s.contains("సమయం ఎంత") || s.contains("తేదీ")
    }

    private fun isPurelyNegated(s: String): Boolean {
        if (s.contains("do not disturb") || s.contains("dnd")) return false
        val negTokens = listOf("do not", "dont", "never", "must not", "stop", "cancel", "disallow") + teluguNegations
        return negTokens.any { s.startsWith("$it ") || s == it || s.contains(" $it") }
    }

    private fun extractActionFromClause(clause: String): SemanticAction {
        return when {
            clause.contains("open") -> SemanticAction.OPEN_SETTINGS
            clause.contains("on") -> SemanticAction.SET_STATE
            clause.contains("off") -> SemanticAction.SET_STATE
            else -> SemanticAction.UNKNOWN
        }
    }

    private fun extractTargetFromClause(clause: String): String? {
        return when {
            clause.contains("bluetooth") -> "bluetooth"
            clause.contains("wifi") -> "wifi"
            clause.contains("flashlight") || clause.contains("torch") -> "flashlight"
            clause.contains("youtube") -> "YouTube"
            else -> null
        }
    }

    private fun matchTimerCommand(s: String): Int? {
        if (!s.contains("timer") && !s.contains("టైమర్")) return null
        val digitMatch = Regex("""(\d+)\s*(minute|min|m|second|sec|s|hour|hr|h)""").find(s)
        if (digitMatch != null) {
            val num = digitMatch.groupValues[1].toIntOrNull() ?: 1
            val unit = digitMatch.groupValues[2]
            return when {
                unit.startsWith("s") -> num
                unit.startsWith("h") -> num * 3600
                else -> num * 60
            }
        }
        val wordMatches = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30
        )
        for ((word, count) in wordMatches) {
            if (s.contains("$word minute") || s.contains("$word min")) {
                return count * 60
            }
        }
        if (s == "set a timer" || s == "timer పెట్టు" || s == "open timer") {
            return 300 // default 5m
        }
        return null
    }

    private fun matchAlarmCommand(s: String): Map<String, Any>? {
        if (!s.contains("alarm") && !s.contains("అలారం")) return null
        val timeRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""")
        val match = timeRegex.find(s)
        if (match != null) {
            var hour = match.groupValues[1].toIntOrNull() ?: 7
            val min = match.groupValues[2].toIntOrNull() ?: 0
            val ampm = match.groupValues[3].lowercase()
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            return mapOf("hour" to hour, "minute" to min)
        }
        return mapOf("hour" to 7, "minute" to 0)
    }

    private val appAliases = mapOf(
        "whatsapp" to "WhatsApp", "wa" to "WhatsApp", "వాట్సాప్" to "WhatsApp",
        "youtube" to "YouTube", "yt" to "YouTube", "యూట్యూబ్" to "YouTube",
        "spotify" to "Spotify", "music" to "Spotify", "స్పాటిఫై" to "Spotify",
        "chrome" to "Chrome", "google chrome" to "Chrome", "browser" to "Chrome", "క్రోమ్" to "Chrome",
        "camera" to "Camera", "cam" to "Camera", "కెమెరా" to "Camera",
        "photos" to "Photos", "gallery" to "Photos", "గ్యాలరీ" to "Photos",
        "calculator" to "Calculator", "calc" to "Calculator", "కాలిక్యులేటర్" to "Calculator",
        "calendar" to "Calendar", "క్యాలెండర్" to "Calendar",
        "clock" to "Clock", "గడియారం" to "Clock",
        "maps" to "Google Maps", "google maps" to "Google Maps", "మ్యాప్స్" to "Google Maps",
        "gmail" to "Gmail", "email" to "Gmail",
        "messages" to "Messages", "sms" to "Messages",
        "phone" to "Phone", "dialer" to "Phone",
        "contacts" to "Contacts",
        "files" to "Files", "file manager" to "Files",
        "instagram" to "Instagram", "insta" to "Instagram",
        "telegram" to "Telegram",
        "twitter" to "Twitter", "x" to "Twitter",
        "play store" to "Play Store", "google play" to "Play Store"
    )

    private fun extractAppLaunchName(s: String): String? {
        val trimmed = s.trim()
        if (trimmed in appAliases) return appAliases[trimmed]

        val launchPrefixes = listOf(
            "open the ", "launch the ", "start the ", "run the ",
            "open app ", "launch app ", "start app ",
            "open ", "launch ", "start ", "run ", "ఓపెన్ ", "స్టార్ట్ "
        )
        val launchSuffixes = listOf(
            " open chey", " open cheyyi", " open", " ఓపెన్ చెయ్", " ఓపెన్ చెయ్యి", " ఓపెన్"
        )

        var matched = false
        var clean = trimmed

        for (prefix in launchPrefixes) {
            if (clean.startsWith(prefix)) {
                clean = clean.substring(prefix.length).trim()
                matched = true
                break
            }
        }

        for (suffix in launchSuffixes) {
            if (clean.endsWith(suffix)) {
                clean = clean.substring(0, clean.length - suffix.length).trim()
                matched = true
                break
            }
        }

        if (clean.endsWith(" app")) {
            clean = clean.removeSuffix(" app").trim()
            matched = true
        }

        if (!matched && trimmed !in appAliases) return null

        clean = clean.trim()
        if (clean in appAliases) return appAliases[clean]

        val nonAppKeywords = listOf("settings", "bluetooth", "wifi", "wi fi", "flashlight", "torch", "timer", "alarm", "battery", "camera2")
        if (clean.length in 2..30 && nonAppKeywords.none { clean.contains(it) }) {
            return clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }

        return null
    }

    private fun matchSettingsNavigation(s: String): String? {
        return when {
            s.contains("sound settings") || s.contains("volume settings") || s == "open volume" || s == "open sound" -> "open_sound_settings"
            s.contains("display settings") || s.contains("brightness settings") || s == "open brightness" || s == "open display" -> "open_display_settings"
            s.contains("location settings") || s.contains("gps settings") || s == "open location" -> "open_location_settings"
            s.contains("airplane mode settings") || s.contains("flight mode") || s == "open airplane mode" -> "open_airplane_mode_settings"
            s.contains("do not disturb") || s.contains("dnd settings") || s == "open dnd" -> "open_dnd_settings"
            s.contains("system settings") || s == "open settings" || s == "device settings" || s == "సెట్టింగ్స్" -> "open_system_settings"
            s.contains("battery saver") -> "open_battery_saver_settings"
            s.contains("storage settings") -> "open_storage_settings"
            s.contains("security settings") -> "open_security_settings"
            s.contains("date and time settings") || s.contains("date time settings") -> "open_date_time_settings"
            s.contains("developer options") -> "open_developer_options"
            s.contains("mobile data settings") -> "open_mobile_data_settings"
            s.contains("nfc settings") -> "open_nfc_settings"
            s.contains("accessibility settings") -> "open_accessibility_settings"
            s.contains("wallpaper") -> "open_wallpaper_settings"
            s.contains("font") -> "open_font_settings"
            s.contains("screen timeout") -> "open_screen_timeout_settings"
            s.contains("battery settings") || s.contains("power usage") || s == "open battery" -> "open_battery_settings"
            s.contains("app permissions") || s.contains("permission settings") -> "open_app_permissions_settings"
            s.contains("app notifications") || s.contains("notification settings") -> "open_app_notification_settings"
            s.contains("app details") || s.contains("app info") -> "open_app_details_settings"
            s.contains("hotspot") || s.contains("tethering") -> "open_hotspot_settings"
            s.contains("network settings") -> "open_network_settings"
            s.contains("vpn") -> "open_vpn_settings"
            s.contains("language settings") || s.contains("locale") -> "open_language_settings"
            s.contains("privacy settings") -> "open_privacy_settings"
            s.contains("wifi panel") || s.contains("internet panel") || s.contains("connectivity panel") -> "open_wifi_panel"
            else -> null
        }
    }

    private fun matchCommunicationCommand(s: String, rawOriginal: String, reqId: String): StructuredIntent? {
        if (s.startsWith("call ") || s.startsWith("dial ")) {
            val phone = s.replace("call ", "").replace("dial ", "").trim()
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = s,
                intentDomain = IntentDomain.COMMUNICATION,
                action = SemanticAction.SEND,
                target = "phone",
                parameters = mapOf("phone_number" to phone),
                requiresConfirmation = true,
                confidence = 0.95f,
                executionStrategy = ExecutionStrategy.USER_CONFIRMATION_REQUIRED,
                canonicalToolName = "dial_phone_number"
            )
        }
        if (s.startsWith("send sms ") || s.startsWith("send message ") || s.startsWith("text ")) {
            return StructuredIntent(
                requestId = reqId,
                originalUtterance = rawOriginal,
                normalizedUtterance = s,
                intentDomain = IntentDomain.COMMUNICATION,
                action = SemanticAction.SEND,
                target = "sms",
                parameters = mapOf("recipient" to "Contact", "message" to rawOriginal),
                requiresConfirmation = true,
                confidence = 0.95f,
                executionStrategy = ExecutionStrategy.USER_CONFIRMATION_REQUIRED,
                canonicalToolName = "compose_sms"
            )
        }
        return null
    }

    private fun extractSplitScreenApps(s: String): Pair<String, String> {
        val clean = s.replace("in split screen", "")
            .replace("split screen", "")
            .replace("side by side", "")
            .replace("open ", "")
            .replace("show ", "")
            .replace("launch ", "")
            .trim()
        val parts = when {
            clean.contains(" and ") -> clean.split(" and ")
            clean.contains(" next to ") -> clean.split(" next to ")
            clean.contains(" with ") -> clean.split(" with ")
            clean.contains(" మరియు ") -> clean.split(" మరియు ")
            else -> emptyList()
        }
        if (parts.size >= 2) {
            val p1 = parts[0].trim()
            val p2 = parts[1].trim()
            val app1 = appAliases[p1] ?: p1.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            val app2 = appAliases[p2] ?: p2.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            return Pair(app1, app2)
        }
        return Pair("WhatsApp", "YouTube")
    }
}
